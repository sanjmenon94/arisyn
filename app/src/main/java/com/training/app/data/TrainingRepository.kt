package com.training.app.data

import com.training.app.data.local.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

class TrainingRepository(private val dao: TrainingDao) {
    fun today() = dao.observeDay(LocalDate.now().dayOfWeek.value)
    fun dayById(id: String) = dao.observeDayById(id)
    fun exercises(dayId:String) = dao.observeExercises(dayId)
    fun sessionForDate(date:String) = dao.observeSessionForDate(date)
    fun sessionSummaries() = dao.observeSessionSummaries()
    fun sessionSetDetails(sessionId:String) = dao.observeSessionSetDetails(sessionId)
    fun allSetDetails() = dao.observeAllSetDetails()
    // Direct DB read, not derived from a StateFlow snapshot: the completion screen needs the
    // true current count regardless of whether allSetDetails' Flow has warmed up yet on this
    // particular navigation path (it may not have, e.g. a fresh app launch or navigating
    // straight from Today without passing through an active workout screen first).
    suspend fun hasLoggedSets(sessionId:String) = dao.countSetsForSession(sessionId) > 0
    fun sets(session:String, exercise:String) = dao.observeSets(session,exercise)
    fun lastPerformance(exerciseId:String, currentSessionId:String) = dao.observeLastPerformance(exerciseId,currentSessionId)
    fun metrics()=dao.metrics()
    // REPLACE + the unique (recordedAt, source) index means logging the same day twice from the
    // same source updates that entry rather than accumulating duplicates — the random id on
    // BodyMetricEntity doesn't matter here the way it did on SetLogEntity, because this table
    // already has a real uniqueness constraint for Room to resolve against.
    suspend fun logWeight(weightKg: Double, source: String = "manual") {
        dao.upsertMetric(BodyMetricEntity(recordedAt = LocalDate.now().toString(), weightKg = weightKg, source = source))
    }
    fun goals() = dao.observeGoals()
    suspend fun saveGoals(dailyCalories: Int?, dailyDistanceKm: Double?, dailySteps: Int?) {
        dao.upsertGoals(UserGoalsEntity(dailyCalories = dailyCalories, dailyDistanceKm = dailyDistanceKm, dailySteps = dailySteps))
    }
    // A manual stand-in for sleep, keyed by date rather than the health-connect-only convention of
    // "whatever the watch says" — Health Connect stays the preferred source wherever it actually
    // has data; this only fills the gap while it doesn't.
    fun sleepFor(date: String) = dao.observeSleep(date)
    suspend fun logSleep(hours: Double, date: String = LocalDate.now().toString()) {
        dao.upsertSleep(SleepEntryEntity(recordedAt = date, hours = hours))
    }
    suspend fun ensureToday(day:ProgramDayEntity, durationMinutes:Int=45):SessionEntity = dao.sessionFor(LocalDate.now().toString()) ?: SessionEntity(sessionDate=LocalDate.now().toString(),programDayId=day.id,weekNumber=1,selectedDurationMinutes=durationMinutes,startedAt=System.currentTimeMillis()).also { save(it) }
    suspend fun save(session:SessionEntity) { dao.upsertSession(session.copy(updatedAt=System.currentTimeMillis(),syncState="pending")); dao.enqueue(OutboxEntity(entityType="sessions",entityId=session.id)) }
    // The conditioning finisher is logged as one block on the session — not individual weighted
    // sets — since it's conditioning to complete, not a lift to progress numbers on.
    suspend fun saveFinisher(session:SessionEntity, durationSec:Int, roundsCompleted:Int?) { save(session.copy(finisherDurationSec=durationSec, finisherRoundsCompleted=roundsCompleted)) }
    suspend fun completeSet(sessionId:String, exerciseId:String, number:Int, weight:Double?, reps:Int, isExtra:Boolean=false) { val row=SetLogEntity(sessionId=sessionId,exerciseId=exerciseId,setNumber=number,weightKg=weight,reps=reps,isExtra=isExtra); dao.upsertSet(row); dao.enqueue(OutboxEntity(entityType="set_logs",entityId=row.id)) }
    suspend fun uncompleteSet(sessionId:String, exerciseId:String, number:Int) { dao.deleteSet(sessionId,exerciseId,number) }
    // Pause/resume/reset only ever touch the session's own timer fields — never set_logs.
    suspend fun pauseSession(session:SessionEntity) { save(session.copy(pausedAt=System.currentTimeMillis())) }
    suspend fun resumeSession(session:SessionEntity) { val gap=System.currentTimeMillis()-(session.pausedAt?:System.currentTimeMillis()); save(session.copy(pausedAt=null,pausedDurationMs=session.pausedDurationMs+gap)) }
    suspend fun resetSessionTimer(session:SessionEntity) { save(session.copy(startedAt=System.currentTimeMillis(),pausedAt=null,pausedDurationMs=0)) }
    // Deterministic IDs + REPLACE conflict strategy make this safe to re-run every launch,
    // so corrected seed data (e.g. a fixed musclewikiSlug, or a reshuffled weekly schedule)
    // reaches devices already seeded. program_exercises is fully cleared first since its
    // primary key is derived from the (day, exercise) pairing, which a schedule change can move.
    suspend fun seed() { dao.insertDays(Seeds.days); dao.insertExercises(Seeds.exercises); dao.clearProgram(); dao.insertProgram(Seeds.program()) }

    // --- 5K race plan ---
    fun raceBaseline() = dao.observeRaceBaseline()
    suspend fun raceBaselineOnce() = dao.raceBaselineOnce()
    suspend fun setRaceBaseline(easyPaceSecPerKm: Int, source: String, planStartDate: String) {
        dao.upsertRaceBaseline(RaceBaselineEntity(easyPaceSecPerKm = easyPaceSecPerKm, source = source, planStartDate = planStartDate))
    }
    // Only ever seeds once — if a baseline (and therefore a plan) already exists, this is a no-op,
    // so calling it on every app start is safe and doesn't reset progress.
    suspend fun ensureRacePlanSeeded(baselineEasyPaceSec: Int) {
        if (dao.racePlanWeekCount() == 0) dao.insertRacePlanWeeks(RacePlan.generateInitialPlan(baselineEasyPaceSec))
    }
    fun racePlanWeek(weekNumber: Int) = dao.observeRacePlanWeek(weekNumber)
    fun allRacePlanWeeks() = dao.observeAllRacePlanWeeks()
    suspend fun racePlanWeekOnce(weekNumber: Int) = dao.racePlanWeekOnce(weekNumber)
    fun racePlanSessionForDate(date: String) = dao.observeRacePlanSessionForDate(date)
    suspend fun logRacePlanSession(weekId: String, date: String, pace: String?, distanceKm: Double?, durationMin: Double?, completed: Boolean) {
        dao.upsertRacePlanSession(RacePlanSessionEntity(racePlanWeekId = weekId, sessionDate = date, actualPacePerKm = pace, actualDistanceKm = distanceKm, actualDurationMin = durationMin, completed = completed))
    }
    // Computed exactly once per week (see adjustmentComputed) from the *previous* week's results —
    // the caller (ViewModel) only invokes this once that previous week has actually finished, so a
    // week is never re-adjusted partway through itself.
    suspend fun ensureWeekAdjustmentComputed(weekNumber: Int) {
        if (weekNumber <= 1) return
        val week = dao.racePlanWeekOnce(weekNumber)
        if (week.isEmpty() || week.all { it.adjustmentComputed }) return
        val prevWeek = dao.racePlanWeekOnce(weekNumber - 1)
        if (prevWeek.isEmpty()) return
        val prevSessions = dao.racePlanSessionsForWeekNumber(weekNumber - 1)
        val templatesByWeekId = prevWeek.associateBy { it.id }
        val adjustment = RacePlan.computeAdjustment(prevSessions, templatesByWeekId)
        RacePlan.applyAdjustment(week, adjustment)
            .map { it.copy(adjustment = adjustment.key, adjustmentComputed = true) }
            .forEach { dao.upsertRacePlanWeek(it) }
    }
}
