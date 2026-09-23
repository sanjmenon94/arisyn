package com.training.app.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

@Entity(tableName = "exercises") data class ExerciseEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String, val muscleGroup: String,
    val equipment: String = "dumbbell", val musclewikiSlug: String? = null, val isTimeBased: Boolean = false, val imageUrl: String? = null
)
// roundsPrescribed is the circuit's full round count at the default (45min) duration — every
// exercise on the day shares this one number now that sessions run as a circuit rather than
// each exercise carrying its own independent set count.
@Entity(tableName = "program_days") data class ProgramDayEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val weekday: Int, val focus: String, val cue: String, val roundsPrescribed: Int = 4
)
@Entity(tableName = "program_exercises", foreignKeys = [ForeignKey(entity=ProgramDayEntity::class,parentColumns=["id"],childColumns=["programDayId"]), ForeignKey(entity=ExerciseEntity::class,parentColumns=["id"],childColumns=["exerciseId"])], indices = [Index("programDayId"), Index("exerciseId")])
data class ProgramExerciseEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val programDayId: String, val exerciseId: String, val orderIndex: Int, val targetSets: Int, val targetReps: String, val prescribedWeightKg: Double?, val prescribedReps: Int)
// finisherDurationSec/finisherRoundsCompleted log the end-of-session conditioning block as one
// block on the session itself (not individual weighted sets, per the circuit-format spec) — null
// on both means no finisher was done this session (skipped at 15/30min, or manually skipped).
@Entity(tableName = "sessions", indices = [Index("sessionDate")]) data class SessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val sessionDate: String, val programDayId: String?, val weekNumber: Int, val burnRating: Int? = null, val notes: String? = null, val cardioDone: Boolean = false, val selectedDurationMinutes: Int? = null, val selectedIntensity: String? = null, val startedAt: Long? = null, val completedAt: Long? = null, val pausedAt: Long? = null, val pausedDurationMs: Long = 0, val createdAt: Long = Instant.now().toEpochMilli(), val updatedAt: Long = Instant.now().toEpochMilli(), val syncState: String = "pending", val finisherDurationSec: Int? = null, val finisherRoundsCompleted: Int? = null
)
// id is derived from (sessionId, exerciseId, setNumber), not a random UUID — that was the bug.
// upsertSet relies on OnConflictStrategy.REPLACE to treat re-logging a set slot as an update, but
// REPLACE only fires on a primary-key collision. A random id meant every insert for an
// already-logged slot silently added a second row instead of replacing it: duplicate rows the UI
// then double-counted into volume, and a single unlog's DELETE (which matches on session+exercise+
// setNumber, not id) could remove more than one row at once. One set slot can now only ever exist
// as one row.
@Entity(tableName = "set_logs", indices = [Index("sessionId"),Index("exerciseId")]) data class SetLogEntity(
    val sessionId: String, val exerciseId: String, val setNumber: Int, val weightKg: Double?, val reps: Int, val isExtra: Boolean = false, val completedAt: Long = Instant.now().toEpochMilli(), val syncState: String = "pending",
    @PrimaryKey val id: String = "$sessionId:$exerciseId:$setNumber"
)
@Entity(tableName = "body_metrics", indices = [Index(value=["recordedAt","source"], unique=true)]) data class BodyMetricEntity(@PrimaryKey val id: String=UUID.randomUUID().toString(), val recordedAt: String, val weightKg: Double, val source: String="manual", val syncState:String="pending")
@Entity(tableName="sync_outbox", indices=[Index(value=["entityType","entityId"],unique=true)]) data class OutboxEntity(@PrimaryKey val id:String=UUID.randomUUID().toString(), val entityType:String, val entityId:String, val operation:String="upsert", val createdAt:Long=Instant.now().toEpochMilli(), val attempts:Int=0, val lastError:String?=null)
// Single-row table (fixed id=0) rather than a preferences file — these are just three nullable
// numbers that need to survive reinstall-free and show up in a Flow like everything else the
// app reads, so it fits the existing Room/DAO pattern better than introducing DataStore for it.
// Null means "not set" (no goal, no ring) rather than 0, which would render as an impossible
// always-complete ring.
@Entity(tableName = "user_goals") data class UserGoalsEntity(
    @PrimaryKey val id: Int = 0, val dailyCalories: Int? = null, val dailyDistanceKm: Double? = null, val dailySteps: Int? = null
)
// Manual stand-in for sleep until Health Connect actually delivers it — one row per date
// (REPLACE on conflict), so re-logging the same night updates it rather than duplicating.
@Entity(tableName = "sleep_entries") data class SleepEntryEntity(
    @PrimaryKey val recordedAt: String, val hours: Double
)
// Single-row baseline for the 5K plan's week 1-2 targets — set either from recent Health Connect
// run history or, when there isn't enough of that, directly from the user. Null means neither has
// happened yet, which is what gates whether the plan can be generated at all.
// planStartDate anchors week 1 to a fixed Monday (the week the plan was generated) so week
// numbering stays stable across app restarts, rather than being re-derived from "today" every time.
@Entity(tableName = "race_baseline") data class RaceBaselineEntity(
    @PrimaryKey val id: Int = 0, val easyPaceSecPerKm: Int?, val source: String = "manual", val planStartDate: String? = null
)
// One row per (week, sessionType) — three rows per week (tempo/interval/long_run), not one row
// covering the whole week, since each session type carries its own pace/distance/duration target.
// adjustment is computed once, at the start of the week it applies to, from how the *previous*
// week's sessions actually went (see RacePlan.computeAdjustment) — never recomputed mid-week.
@Entity(tableName = "race_plan_weeks", indices = [Index(value = ["weekNumber", "sessionType"], unique = true)]) data class RacePlanWeekEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val weekNumber: Int, val sessionType: String,
    val targetPacePerKm: String?, val targetDistanceKm: Double?, val targetDurationMin: Double?,
    val adjustment: String = "hold", val adjustmentComputed: Boolean = false
)
// The actual logged result for one real run against one of the week's templates. id is derived
// from sessionDate, not a random UUID — the same fix SetLogEntity needed earlier: only one run
// can ever happen on a given date, so re-logging that date (correcting a number, say) must REPLACE
// the existing row, not insert a second one alongside it.
@Entity(tableName = "race_plan_sessions", indices = [Index("racePlanWeekId")]) data class RacePlanSessionEntity(
    val racePlanWeekId: String, val sessionDate: String,
    val actualPacePerKm: String? = null, val actualDistanceKm: Double? = null, val actualDurationMin: Double? = null,
    val completed: Boolean = false,
    @PrimaryKey val id: String = sessionDate
)

data class ProgramExerciseRow(@Embedded val item: ProgramExerciseEntity, @Relation(parentColumn="exerciseId", entityColumn="id") val exercise: ExerciseEntity)
data class SetLogDetail(@Embedded val log: SetLogEntity, val exerciseName: String, val exerciseImageUrl: String? = null)
data class SessionSummaryRow(@Embedded val session: SessionEntity, val dayFocus: String?, val exerciseCount: Int, val volume: Double, val loggedStartedAt: Long?, val loggedEndedAt: Long?)

@Dao interface TrainingDao {
    @Query("SELECT * FROM program_days WHERE weekday=:weekday LIMIT 1") fun observeDay(weekday:Int): Flow<ProgramDayEntity?>
    @Query("SELECT * FROM program_days WHERE id=:id LIMIT 1") fun observeDayById(id:String): Flow<ProgramDayEntity?>
    @Transaction @Query("SELECT * FROM program_exercises WHERE programDayId=:dayId ORDER BY orderIndex") fun observeExercises(dayId:String): Flow<List<ProgramExerciseRow>>
    @Query("SELECT * FROM sessions WHERE sessionDate=:date LIMIT 1") suspend fun sessionFor(date:String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE sessionDate=:date LIMIT 1") fun observeSessionForDate(date:String): Flow<SessionEntity?>
    @Query("SELECT * FROM set_logs WHERE sessionId=:sessionId AND exerciseId=:exerciseId ORDER BY setNumber") fun observeSets(sessionId:String, exerciseId:String): Flow<List<SetLogEntity>>
    @Query("SELECT * FROM set_logs WHERE exerciseId=:exerciseId AND sessionId!=:currentSessionId ORDER BY completedAt DESC LIMIT 1") fun observeLastPerformance(exerciseId:String, currentSessionId:String): Flow<SetLogEntity?>
    @Query("SELECT sl.*, e.name as exerciseName, e.imageUrl as exerciseImageUrl FROM set_logs sl JOIN exercises e ON e.id = sl.exerciseId WHERE sl.sessionId=:sessionId ORDER BY sl.setNumber") fun observeSessionSetDetails(sessionId:String): Flow<List<SetLogDetail>>
    @Query("SELECT sl.*, e.name as exerciseName, e.imageUrl as exerciseImageUrl FROM set_logs sl JOIN exercises e ON e.id = sl.exerciseId ORDER BY sl.completedAt") fun observeAllSetDetails(): Flow<List<SetLogDetail>>
    @Query("SELECT COUNT(*) FROM set_logs WHERE sessionId=:sessionId") suspend fun countSetsForSession(sessionId:String): Int
    @Query("""SELECT s.*, d.focus as dayFocus,
        (SELECT COUNT(DISTINCT sl.exerciseId) FROM set_logs sl WHERE sl.sessionId=s.id) as exerciseCount,
        (SELECT COALESCE(SUM(sl.weightKg*sl.reps),0.0) FROM set_logs sl WHERE sl.sessionId=s.id) as volume,
        (SELECT MIN(sl.completedAt) FROM set_logs sl WHERE sl.sessionId=s.id) as loggedStartedAt,
        (SELECT MAX(sl.completedAt) FROM set_logs sl WHERE sl.sessionId=s.id) as loggedEndedAt
        FROM sessions s LEFT JOIN program_days d ON d.id = s.programDayId
        WHERE s.completedAt IS NOT NULL
        ORDER BY s.sessionDate DESC""") fun observeSessionSummaries(): Flow<List<SessionSummaryRow>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertExercises(rows:List<ExerciseEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertDays(rows:List<ProgramDayEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertProgram(rows:List<ProgramExerciseEntity>)
    @Query("DELETE FROM program_exercises") suspend fun clearProgram()
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertSession(row:SessionEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertSet(row:SetLogEntity)
    @Query("DELETE FROM set_logs WHERE sessionId=:sessionId AND exerciseId=:exerciseId AND setNumber=:setNumber") suspend fun deleteSet(sessionId:String, exerciseId:String, setNumber:Int)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun enqueue(row:OutboxEntity)
    @Query("SELECT * FROM body_metrics ORDER BY recordedAt") fun metrics():Flow<List<BodyMetricEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertMetric(row:BodyMetricEntity)
    @Query("SELECT * FROM user_goals WHERE id=0") fun observeGoals(): Flow<UserGoalsEntity?>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertGoals(row:UserGoalsEntity)
    @Query("SELECT * FROM sleep_entries WHERE recordedAt=:date LIMIT 1") fun observeSleep(date:String): Flow<SleepEntryEntity?>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertSleep(row:SleepEntryEntity)

    @Query("SELECT * FROM race_baseline WHERE id=0") fun observeRaceBaseline(): Flow<RaceBaselineEntity?>
    @Query("SELECT * FROM race_baseline WHERE id=0") suspend fun raceBaselineOnce(): RaceBaselineEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertRaceBaseline(row:RaceBaselineEntity)
    @Query("SELECT COUNT(*) FROM race_plan_weeks") suspend fun racePlanWeekCount(): Int
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertRacePlanWeeks(rows:List<RacePlanWeekEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertRacePlanWeek(row:RacePlanWeekEntity)
    @Query("SELECT * FROM race_plan_weeks WHERE weekNumber=:weekNumber ORDER BY sessionType") fun observeRacePlanWeek(weekNumber:Int): Flow<List<RacePlanWeekEntity>>
    @Query("SELECT * FROM race_plan_weeks WHERE weekNumber=:weekNumber ORDER BY sessionType") suspend fun racePlanWeekOnce(weekNumber:Int): List<RacePlanWeekEntity>
    @Query("SELECT * FROM race_plan_weeks WHERE weekNumber=:weekNumber AND sessionType=:type LIMIT 1") suspend fun racePlanWeekTypeOnce(weekNumber:Int, type:String): RacePlanWeekEntity?
    @Query("SELECT * FROM race_plan_weeks ORDER BY weekNumber, sessionType") fun observeAllRacePlanWeeks(): Flow<List<RacePlanWeekEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertRacePlanSession(row:RacePlanSessionEntity)
    @Query("SELECT * FROM race_plan_sessions WHERE racePlanWeekId=:weekId") suspend fun racePlanSessionsForWeek(weekId:String): List<RacePlanSessionEntity>
    @Query("SELECT * FROM race_plan_sessions WHERE sessionDate=:date LIMIT 1") fun observeRacePlanSessionForDate(date:String): Flow<RacePlanSessionEntity?>
    @Query("""SELECT rs.* FROM race_plan_sessions rs JOIN race_plan_weeks rw ON rw.id = rs.racePlanWeekId WHERE rw.weekNumber=:weekNumber""") suspend fun racePlanSessionsForWeekNumber(weekNumber:Int): List<RacePlanSessionEntity>
}
@Database(entities=[ExerciseEntity::class,ProgramDayEntity::class,ProgramExerciseEntity::class,SessionEntity::class,SetLogEntity::class,BodyMetricEntity::class,OutboxEntity::class,UserGoalsEntity::class,SleepEntryEntity::class,RaceBaselineEntity::class,RacePlanWeekEntity::class,RacePlanSessionEntity::class], version=9, exportSchema=false)
abstract class TrainingDatabase:RoomDatabase(){ abstract fun dao():TrainingDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE exercises ADD COLUMN imageUrl TEXT") }
}
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN startedAt INTEGER")
        db.execSQL("ALTER TABLE sessions ADD COLUMN completedAt INTEGER")
    }
}
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN pausedAt INTEGER")
        db.execSQL("ALTER TABLE sessions ADD COLUMN pausedDurationMs INTEGER NOT NULL DEFAULT 0")
    }
}
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE set_logs ADD COLUMN isExtra INTEGER NOT NULL DEFAULT 0")
    }
}
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS user_goals (id INTEGER NOT NULL PRIMARY KEY, dailyCalories INTEGER, dailyDistanceKm REAL, dailySteps INTEGER)")
    }
}
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_entries (recordedAt TEXT NOT NULL PRIMARY KEY, hours REAL NOT NULL)")
    }
}
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE program_days ADD COLUMN roundsPrescribed INTEGER NOT NULL DEFAULT 4")
        db.execSQL("ALTER TABLE sessions ADD COLUMN finisherDurationSec INTEGER")
        db.execSQL("ALTER TABLE sessions ADD COLUMN finisherRoundsCompleted INTEGER")
    }
}
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS race_baseline (id INTEGER NOT NULL PRIMARY KEY, easyPaceSecPerKm INTEGER, source TEXT NOT NULL, planStartDate TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS race_plan_weeks (id TEXT NOT NULL PRIMARY KEY, weekNumber INTEGER NOT NULL, sessionType TEXT NOT NULL, targetPacePerKm TEXT, targetDistanceKm REAL, targetDurationMin REAL, adjustment TEXT NOT NULL, adjustmentComputed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_race_plan_weeks_weekNumber_sessionType ON race_plan_weeks(weekNumber, sessionType)")
        db.execSQL("CREATE TABLE IF NOT EXISTS race_plan_sessions (id TEXT NOT NULL PRIMARY KEY, racePlanWeekId TEXT NOT NULL, sessionDate TEXT NOT NULL, actualPacePerKm TEXT, actualDistanceKm REAL, actualDurationMin REAL, completed INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_race_plan_sessions_racePlanWeekId ON race_plan_sessions(racePlanWeekId)")
    }
}
