package com.training.app.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector
import com.training.app.data.local.SessionSummaryRow
import com.training.app.data.local.SetLogDetail
import java.time.LocalDate

data class Milestone(val id: String, val label: String, val icon: ImageVector, val earned: Boolean)

/** Computed from existing session/set-log data — no dedicated badges table (not this round). */
object Milestones {
    fun compute(summaries: List<SessionSummaryRow>, allDetails: List<SetLogDetail>): List<Milestone> {
        val dates = summaries.mapNotNull { runCatching { LocalDate.parse(it.session.sessionDate) }.getOrNull() }.toSet()
        val bestStreak = dates.maxOfOrNull { Badges.streak(it.toString(), dates.map { d -> d.toString() }.toSet()) } ?: 0
        val hasPr = summaries.any { Badges.personalRecordBadges(it.session.id, allDetails).isNotEmpty() }
        return listOf(
            Milestone("first_session", "First session", Icons.Default.EmojiEvents, summaries.isNotEmpty()),
            Milestone("streak_3", "3 day streak", Icons.Default.LocalFireDepartment, bestStreak >= 3),
            Milestone("streak_7", "7 day streak", Icons.Default.Whatshot, bestStreak >= 7),
            Milestone("ten_workouts", "10 workouts", Icons.Default.MilitaryTech, summaries.size >= 10),
            Milestone("first_pr", "First PR", Icons.Default.TrendingUp, hasPr)
        )
    }
}
