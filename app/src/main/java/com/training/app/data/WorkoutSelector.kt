package com.training.app.data

/** Deterministic session shaping only; it never changes the stored programme or which exercises
 * appear — only how many rounds of the same circuit get done, and whether the finisher runs. */
object WorkoutSelector {
    /** 15/30min trims rounds, not exercises — a circuit's whole point is doing the same movements
     * together each round, so shortening it means fewer trips through the circuit, never a
     * shorter exercise list. 45 (or anything else) runs the full prescribed round count. */
    fun roundsForDuration(prescribedRounds: Int, minutes: Int): Int = when (minutes) {
        15 -> 2
        30 -> 3
        else -> prescribedRounds
    }

    /** The conditioning finisher only runs at the full (45min) duration — 15/30min sessions are
     * already a deliberately shortened circuit, and stacking a finisher on top of that would undo
     * the point of choosing a shorter session. */
    fun includesFinisher(minutes: Int): Boolean = minutes != 15 && minutes != 30
}
