package com.training.app.data

import com.training.app.data.local.SetLogDetail
import java.time.LocalDate

/** Computed on the fly from existing session/set-log data — no dedicated badge storage. */
object Badges {
    fun streak(sessionDate: String, allSessionDates: Set<String>): Int {
        var date = runCatching { LocalDate.parse(sessionDate) }.getOrNull() ?: return 0
        var count = 0
        while (allSessionDates.contains(date.toString())) { count++; date = date.minusDays(1) }
        return count
    }

    // No emoji anywhere in the app, per the voice guide's mechanical rules.
    fun streakBadge(streak: Int): String? = if (streak >= 3) "$streak-day streak" else null

    /** Streak as of today, not anchored to a specific session — for the Progress stat header. */
    fun currentStreak(allSessionDates: Set<String>): Int {
        val today = LocalDate.now()
        val anchor = when { allSessionDates.contains(today.toString()) -> today; allSessionDates.contains(today.minusDays(1).toString()) -> today.minusDays(1); else -> return 0 }
        return streak(anchor.toString(), allSessionDates)
    }

    fun personalRecordBadges(sessionId: String, allDetails: List<SetLogDetail>): List<String> {
        val sessionLogs = allDetails.filter { it.log.sessionId == sessionId }
        if (sessionLogs.isEmpty()) return emptyList()
        val sessionStart = sessionLogs.minOf { it.log.completedAt }
        return sessionLogs.groupBy { it.log.exerciseId }.mapNotNull { (exerciseId, logs) ->
            val sessionMax = logs.mapNotNull { it.log.weightKg }.maxOrNull() ?: return@mapNotNull null
            val priorMax = allDetails.filter { it.log.exerciseId == exerciseId && it.log.completedAt < sessionStart }
                .mapNotNull { it.log.weightKg }.maxOrNull()
            if (priorMax != null && sessionMax > priorMax) "New PR: ${logs.first().exerciseName}" else null
        }
    }
}
