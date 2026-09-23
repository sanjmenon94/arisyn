package com.training.app.data

import kotlin.math.roundToInt

/** "M:SS per km" pace strings, parsed/formatted/adjusted in one place rather than scattered
 * string math — the race plan stores pace as text (e.g. "6:30") to match the spec's schema, but
 * every push/hold/pull-back computation needs it as a plain integer of seconds to do arithmetic on. */
object Pace {
    fun parseToSeconds(pace: String?): Int? {
        if (pace.isNullOrBlank()) return null
        val parts = pace.trim().split(":")
        if (parts.size != 2) return null
        val min = parts[0].toIntOrNull() ?: return null
        val sec = parts[1].toIntOrNull() ?: return null
        return min * 60 + sec
    }

    fun formatFromSeconds(totalSeconds: Int): String {
        val clamped = totalSeconds.coerceAtLeast(0)
        return "${clamped / 60}:${(clamped % 60).toString().padStart(2, '0')}"
    }

    /** A logged pace "hits" a target if it's at or faster than target, within a small cushion —
     * GPS/watch pace noise means requiring an exact-or-better match would fail runs that were
     * genuinely on pace. */
    fun withinTarget(actualPace: String?, targetPace: String?, cushionSeconds: Int = 10): Boolean {
        val actual = parseToSeconds(actualPace) ?: return false
        val target = parseToSeconds(targetPace) ?: return true
        return actual <= target + cushionSeconds
    }

    fun secondsPerKmFromDistanceDuration(distanceKm: Double, durationMin: Double): Int? {
        if (distanceKm <= 0.0 || durationMin <= 0.0) return null
        return ((durationMin * 60.0) / distanceKm).roundToInt()
    }
}
