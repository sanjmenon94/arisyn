package com.training.app.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** Read-only Health Connect access — the watch (via Samsung Health) writes exercise sessions,
 * heart rate, steps, and sleep into Health Connect; this reads them back. Arisyn never writes
 * anything to Health Connect itself. Kept as a plain object rather than part of TrainingRepository
 * since it talks to a system service, not the app's own Room database — a fundamentally different
 * kind of data source, not a table to add to the local schema. */
object HealthConnectManager {
    // Scoped to exactly what the Profile screen asked for: sessions, heart rate, steps, sleep.
    // Each is its own Android runtime permission — Health Connect grants per record type, not
    // as one bundle, so requesting only these four keeps the permission dialog to what's needed.
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    // Weight is requested separately from the four above, deliberately — it was added later, and
    // folding it into PERMISSIONS would mean everyone who already connected suddenly reads as
    // "disconnected" just because they haven't granted a permission that didn't exist for them
    // yet. Body-weight sync is opt-in on top of an existing connection, not a requirement of one.
    val WEIGHT_PERMISSION: String = HealthPermission.getReadPermission(WeightRecord::class)

    /** False on a device where Health Connect isn't installed and can't be updated to support
     * it — distinct from "installed but not yet granted," which is what PERMISSIONS covers. */
    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(context: Context): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    /** Some watches/apps only ever write ActiveCaloriesBurnedRecord (workout-only burn) and never
     * TotalCaloriesBurnedRecord (active + resting) — Samsung Health on this device is one of them,
     * confirmed live: Total always came back empty even with a logged run in range. Falls back to
     * Active rather than summing both, since a device that writes Total already includes the
     * active portion in it; adding Active on top would double-count for anyone whose watch does
     * write Total. */
    private suspend fun caloriesInRange(c: HealthConnectClient, filter: TimeRangeFilter): Double {
        val total = c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter = filter)).records.sumOf { it.energy.inKilocalories }
        if (total > 0.0) return total
        return c.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter = filter)).records.sumOf { it.energy.inKilocalories }
    }

    /** Steps + distance for a range, via Health Connect's aggregate() rather than summing raw
     * readRecords results. Confirmed on-device: some writers (Health Sync, at least) produce
     * zero-duration StepsRecord entries (startTime == endTime), and this client library's
     * StepsRecord constructor throws IllegalArgumentException converting ANY such record —
     * which poisons readRecords for the *entire* requested range, not just that one record.
     * aggregate() sums platform-side without materializing individual records into that model,
     * so it isn't exposed to that conversion at all. */
    private data class StepsAndDistance(val steps: Long, val distanceKm: Double)
    private suspend fun stepsAndDistanceInRange(c: HealthConnectClient, filter: TimeRangeFilter): StepsAndDistance {
        val result = c.aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL), timeRangeFilter = filter))
        return StepsAndDistance(result[StepsRecord.COUNT_TOTAL] ?: 0L, result[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0)
    }

    suspend fun hasAllPermissions(context: Context): Boolean =
        client(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    suspend fun hasWeightPermission(context: Context): Boolean =
        client(context).permissionController.getGrantedPermissions().contains(WEIGHT_PERMISSION)

    /** A short, human label proving the connection actually works — the most recent exercise
     * session Health Connect knows about, from any source (the watch, Samsung Health, etc.), not
     * just ones Arisyn itself logged. Null means connected but nothing to show yet, not an error. */
    suspend fun mostRecentSessionLabel(context: Context): String? {
        val end = Instant.now()
        val start = end.minus(30, ChronoUnit.DAYS)
        val sessions = client(context).readRecords(
            ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        ).records
        val latest = sessions.maxByOrNull { it.startTime } ?: return null
        val date = latest.startTime.atZone(ZoneId.systemDefault()).toLocalDate()
        val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val dateLabel = when {
            date == today -> "today"
            date == today.minusDays(1) -> "yesterday"
            else -> date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        }
        return "Synced $dateLabel"
    }

    fun requestPermissionContract() = PermissionController.createRequestPermissionResultContract()

    /** Tested directly on-device: PermissionController.revokeAllPermissions() returns without
     * error but does not actually clear the OS-level grant on this platform/SDK combination
     * (confirmed via `dumpsys package` before and after — the permissions were still granted=true).
     * Rather than show a "Disconnected" state that doesn't reflect reality, this opens Health
     * Connect's own per-app permissions screen — the same "App access" screen for Arisyn the user
     * can already reach manually — where the toggles are known to actually work. */
    fun manageAppPermissionsIntent(context: Context): Intent =
        Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
            .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)

    /** Most recent smart-scale weight, in kilograms — from whatever wrote it into Health Connect
     * (the user's scale, via its own manufacturer app). Requires its own permission, since Health
     * Connect grants per record type; a user who connected exercise/steps/sleep/heart rate isn't
     * necessarily also sharing weight. */
    suspend fun latestWeightKg(context: Context): Double? = try {
        val end = Instant.now()
        val start = end.minus(365, ChronoUnit.DAYS)
        client(context).readRecords(ReadRecordsRequest(WeightRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end)))
            .records.maxByOrNull { it.time }?.weight?.inKilograms
    } catch (e: Exception) { null }

    /** Watch-sourced activity for a date range — separate from anything Arisyn itself logged.
     * Null means the read failed (e.g. permission was revoked outside the app since the last
     * check), not "zero activity"; callers should treat that as "couldn't refresh," not "empty
     * week." A genuinely empty week comes back as zeros, not null. */
    data class WeeklyActivity(val workoutCount: Int, val steps: Long, val avgSleepHours: Double?)

    suspend fun weeklyActivity(context: Context, start: Instant, end: Instant): WeeklyActivity? = try {
        val c = client(context)
        val filter = TimeRangeFilter.between(start, end)
        val sessions = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = filter)).records
        val steps = c.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = filter)).records.sumOf { it.count }
        val sleepSessions = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = filter)).records
        val avgSleep = sleepSessions.takeIf { it.isNotEmpty() }
            ?.map { Duration.between(it.startTime, it.endTime).toMinutes() / 60.0 }?.average()
        WeeklyActivity(sessions.size, steps, avgSleep)
    } catch (e: Exception) { null }

    /** The watch bifurcates activity by type (a lift vs. a run) — this reads that back so the
     * app can label things "Run" / "Strength" rather than a generic "workout," the same
     * distinction the watch itself makes. Falls back to "Workout" for types not worth a specific
     * label here (there are dozens in the Health Connect vocabulary; only the ones actually
     * relevant to how this user trains are named). */
    fun exerciseTypeLabel(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Run"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swim"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        else -> "Workout"
    }

    /** Just the calendar dates that have a watch-detected exercise session — used to mark a day
     * "done" (in the week strip, the streak, the calendar) even when it was never logged in
     * Arisyn itself, e.g. a run the watch caught automatically. */
    suspend fun workoutDates(context: Context, start: Instant, end: Instant): Set<LocalDate> = try {
        client(context).readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end)))
            .records.map { it.startTime.atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
    } catch (e: Exception) { emptySet() }

    /** Dates with real step activity but no logged workout or run — a day the watch saw the user
     * moving around, distinct from a day they actually trained. `excluding` should be whatever's
     * already counted as a full day (Arisyn sessions + workoutDates), so a training day never
     * shows as both — the caller decides what "full" means, this only fills in the gap below it. */
    suspend fun stepsOnlyDates(context: Context, start: Instant, end: Instant, excluding: Set<LocalDate>): Set<LocalDate> = try {
        // Grouped by calendar day via aggregateGroupByPeriod rather than reading individual
        // StepsRecord entries and bucketing them by hand — same zero-duration-record hazard as
        // stepsAndDistanceInRange, plus this is what the API is actually for.
        client(context).aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = TimeRangeFilter.between(start, end), timeRangeSlicer = Period.ofDays(1))
        ).mapNotNull { bucket -> bucket.startTime.toLocalDate().takeIf { (bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L) > 0 } }
            .filterNot { it in excluding }.toSet()
    } catch (e: Exception) { emptySet() }

    // steps/distanceKm/calories are per-session, not the day total — read from whatever the watch
    // logged during exactly that session's own start/end window, so a run's own distance doesn't
    // get diluted by (or double-count into) the rest of the day's ambient step count. Null means
    // that record type had nothing in that window (e.g. a strength session has no distance), not
    // that the read failed — a failed read fails the whole dailyActivity call instead.
    data class WorkoutSummary(val typeLabel: String, val durationMinutes: Long, val steps: Long? = null, val distanceKm: Double? = null, val calories: Long? = null)
    // distanceKm/calories here are whole-day totals (like steps already was), not just the
    // in-workout portion — used to compare a day's overall activity against a daily target, since
    // a walk taken outside a logged session should still count toward that day's distance/calories.
    data class DailyActivity(val workouts: List<WorkoutSummary>, val steps: Long, val distanceKm: Double, val calories: Long, val sleepHours: Double?)

    /** The full watch-sourced picture for one specific calendar day — what the day-tap popup
     * shows. Same null-vs-zero convention as weeklyActivity: null is "couldn't read," an instance
     * with empty/zero fields is a real, quiet day. */
    suspend fun dailyActivity(context: Context, date: LocalDate): DailyActivity? = try {
        val c = client(context)
        val dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val filter = TimeRangeFilter.between(dayStart, dayEnd)
        val sessions = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = filter)).records
        val (steps, distanceKm) = stepsAndDistanceInRange(c, filter)
        val calories = caloriesInRange(c, filter)
        val sleepSessions = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = filter)).records
        val sleepHours = sleepSessions.takeIf { it.isNotEmpty() }
            ?.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }?.let { it / 60.0 }
        val workouts = sessions.map { session ->
            val sessionFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            val (sessionSteps, sessionDistanceKm) = stepsAndDistanceInRange(c, sessionFilter)
            val sessionCalories = caloriesInRange(c, sessionFilter)
            WorkoutSummary(
                typeLabel = exerciseTypeLabel(session.exerciseType),
                durationMinutes = Duration.between(session.startTime, session.endTime).toMinutes(),
                steps = sessionSteps.takeIf { it > 0 },
                distanceKm = sessionDistanceKm.takeIf { it > 0.0 },
                calories = sessionCalories.takeIf { it > 0.0 }?.let { it.toLong() }
            )
        }
        DailyActivity(workouts = workouts, steps = steps, distanceKm = distanceKm, calories = calories.toLong(), sleepHours = sleepHours)
    } catch (e: Exception) { null }

    /** Cumulative totals for a date range, used for the Today screen's Weekly Goals rings —
     * week-to-date steps/distance/calories against the user's targets. Same null-on-failure
     * convention as the rest of this file: null means "couldn't read," not "zero this week." */
    data class WeeklyTotals(val steps: Long, val distanceKm: Double, val calories: Long)

    suspend fun weeklyTotals(context: Context, start: Instant, end: Instant): WeeklyTotals? = try {
        val c = client(context)
        val filter = TimeRangeFilter.between(start, end)
        val (steps, distanceKm) = stepsAndDistanceInRange(c, filter)
        val calories = caloriesInRange(c, filter)
        WeeklyTotals(steps, distanceKm, calories.toLong())
    } catch (e: Exception) { null }

    /** The 5K plan's baseline easy pace, derived from actual recent runs rather than a seeded
     * guess — averages distance/duration across every Run-type session in the last 30 days with
     * both a duration and a real distance logged. Null means there wasn't enough (or any) recent
     * run history to derive a baseline from, not that the read failed silently — the caller falls
     * back to asking the user directly in that case. */
    suspend fun recentRunPaceSecPerKm(context: Context): Int? = try {
        val end = Instant.now()
        val start = end.minus(30, ChronoUnit.DAYS)
        val c = client(context)
        val filter = TimeRangeFilter.between(start, end)
        val runs = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = filter)).records
            .filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING || it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL }
        var totalDistanceKm = 0.0
        var totalDurationSec = 0.0
        runs.forEach { run ->
            val runFilter = TimeRangeFilter.between(run.startTime, run.endTime)
            val distanceKm = c.readRecords(ReadRecordsRequest(DistanceRecord::class, timeRangeFilter = runFilter)).records.sumOf { it.distance.inKilometers }
            if (distanceKm > 0.3) {
                totalDistanceKm += distanceKm
                totalDurationSec += Duration.between(run.startTime, run.endTime).seconds.toDouble()
            }
        }
        if (totalDistanceKm <= 0.0) null else (totalDurationSec / totalDistanceKm).roundToInt()
    } catch (e: Exception) { null }
}
