package com.training.app.data

import com.training.app.data.local.RacePlanSessionEntity
import com.training.app.data.local.RacePlanWeekEntity
import java.time.DayOfWeek
import java.time.LocalDate

/** The three structured run types per week — Mon/Tue/Thu's existing easy walk/run stays as-is
 * and isn't part of this plan at all, per the spec ("useful Zone 1-2 volume, not being replaced"). */
enum class RaceSessionType { TEMPO, INTERVAL, LONG_RUN;
    val key: String get() = name.lowercase()
    /** Wed = tempo, Fri = interval, Sat = long run — the plan's fixed weekly slots. */
    val weekday: DayOfWeek get() = when (this) { TEMPO -> DayOfWeek.WEDNESDAY; INTERVAL -> DayOfWeek.FRIDAY; LONG_RUN -> DayOfWeek.SATURDAY }
}

enum class Adjustment { PUSH, HOLD, PULL_BACK;
    val key: String get() = name.lowercase()
    companion object { fun fromKey(k: String) = entries.find { it.key == k } ?: HOLD }
}

/** Deterministic session shaping, same principle as WorkoutSelector — no model, just a fixed
 * 12-week template scaled off one baseline pace, then nudged week to week by push/hold/pull-back. */
object RacePlan {
    const val TOTAL_WEEKS = 12

    // Long run distance (km) per week — a standard base-build-peak-taper shape: gradual build
    // through week 9, a short peak, then week 12 tapers to roughly 50-60% of peak volume per the
    // spec. Tempo/interval durations (minutes) follow the same build-then-taper shape.
    private val longRunKm = listOf(3.0, 3.5, 4.0, 4.5, 5.0, 5.5, 6.0, 6.5, 7.0, 7.0, 7.0, 3.0)
    private val tempoMin = listOf(15.0, 16.0, 18.0, 20.0, 20.0, 22.0, 22.0, 24.0, 25.0, 25.0, 25.0, 12.0)
    private val intervalMin = listOf(16.0, 18.0, 18.0, 20.0, 20.0, 22.0, 22.0, 24.0, 24.0, 25.0, 25.0, 12.0)

    fun weekStartDate(planStart: LocalDate, weekNumber: Int): LocalDate = planStart.plusWeeks((weekNumber - 1).toLong())
    fun sessionDate(planStart: LocalDate, weekNumber: Int, type: RaceSessionType): LocalDate {
        val monday = weekStartDate(planStart, weekNumber)
        return monday.plusDays((type.weekday.value - DayOfWeek.MONDAY.value).toLong())
    }

    /** Tempo runs at the top of Zone 3 (faster than easy, not all-out); intervals sit at or
     * faster than goal 5K pace. Both expressed as a fixed offset from the baseline easy pace —
     * a plain, explainable heuristic rather than a guess with no stated basis. */
    fun generateInitialPlan(baselineEasyPaceSec: Int): List<RacePlanWeekEntity> = buildList {
        val tempoPace = Pace.formatFromSeconds(baselineEasyPaceSec - 40)
        val intervalPace = Pace.formatFromSeconds(baselineEasyPaceSec - 65)
        val easyPace = Pace.formatFromSeconds(baselineEasyPaceSec)
        for (week in 1..TOTAL_WEEKS) {
            val i = week - 1
            add(RacePlanWeekEntity(weekNumber = week, sessionType = RaceSessionType.TEMPO.key, targetPacePerKm = tempoPace, targetDistanceKm = null, targetDurationMin = tempoMin[i]))
            add(RacePlanWeekEntity(weekNumber = week, sessionType = RaceSessionType.INTERVAL.key, targetPacePerKm = intervalPace, targetDistanceKm = null, targetDurationMin = intervalMin[i]))
            add(RacePlanWeekEntity(weekNumber = week, sessionType = RaceSessionType.LONG_RUN.key, targetPacePerKm = easyPace, targetDistanceKm = longRunKm[i], targetDurationMin = null))
        }
    }

    /** Exactly the spec's push/hold/pull-back rule: 3 structured sessions/week (tempo, interval,
     * long run) is `totalPlanned`; missing 2+ pulls back, hitting every session at/under target
     * pace pushes, anything else (one miss, or completed-but-off-pace) holds. */
    fun computeAdjustment(sessions: List<RacePlanSessionEntity>, templatesByWeekId: Map<String, RacePlanWeekEntity>): Adjustment {
        val totalPlanned = 3
        val completedCount = sessions.count { it.completed }
        if (completedCount < totalPlanned - 1) return Adjustment.PULL_BACK
        val paceHits = sessions.count { s ->
            if (!s.completed) return@count false
            val template = templatesByWeekId[s.racePlanWeekId] ?: return@count false
            // Long runs are logged against distance/effort, not a pace target — always counts as
            // a "hit" once completed, since there's no pace goal to miss for an easy-effort run.
            if (template.sessionType == RaceSessionType.LONG_RUN.key) true
            else Pace.withinTarget(s.actualPacePerKm, template.targetPacePerKm)
        }
        return when {
            completedCount == totalPlanned && paceHits == totalPlanned -> Adjustment.PUSH
            else -> Adjustment.HOLD
        }
    }

    /** Applies an adjustment to one week's three base-template rows, producing that week's actual
     * (possibly nudged) targets — push tightens pace and stretches the long run a little further,
     * pull_back trims volume while holding pace steady so the focus is on getting back on track,
     * hold leaves the base template untouched. */
    fun applyAdjustment(baseWeek: List<RacePlanWeekEntity>, adjustment: Adjustment): List<RacePlanWeekEntity> = baseWeek.map { row ->
        when (adjustment) {
            Adjustment.HOLD -> row
            Adjustment.PUSH -> row.copy(
                targetPacePerKm = row.targetPacePerKm?.let { Pace.formatFromSeconds((Pace.parseToSeconds(it) ?: 0) - 5) },
                targetDistanceKm = if (row.sessionType == RaceSessionType.LONG_RUN.key) row.targetDistanceKm?.plus(0.3) else row.targetDistanceKm
            )
            Adjustment.PULL_BACK -> row.copy(
                targetDistanceKm = if (row.sessionType == RaceSessionType.LONG_RUN.key) row.targetDistanceKm?.times(0.8) else row.targetDistanceKm,
                targetDurationMin = if (row.sessionType != RaceSessionType.LONG_RUN.key) row.targetDurationMin?.times(0.8) else row.targetDurationMin
            )
        }
    }

    /** A plain-language line for the adjustment — Progress shows this, not the raw push/hold/
     * pull_back label, per the spec. */
    fun adjustmentSummary(adjustment: Adjustment): String = when (adjustment) {
        Adjustment.PUSH -> "You hit every run on pace last week, so this week nudges the pace and long run a bit further."
        Adjustment.HOLD -> "Same targets as last week — steady as you build."
        Adjustment.PULL_BACK -> "A lighter week to get back on track after missing runs last week — pace stays the same, volume comes down."
    }
}
