package com.training.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Tiered haptics using the system's predefined effects so feel stays consistent across devices. */
object Haptics {
    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private fun fire(context: Context, effect: Int, fallbackMs: Long) {
        val v = vibrator(context)
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.vibrate(VibrationEffect.createPredefined(effect))
        else @Suppress("DEPRECATION") v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Light tick — navigating between screens/tabs. */
    fun tick(context: Context) = fire(context, VibrationEffect.EFFECT_TICK, 10)
    /** Medium tap — checking off (or un-checking) a set. */
    fun click(context: Context) = fire(context, VibrationEffect.EFFECT_CLICK, 20)
    /** Stronger success pulse — completing a workout, or a badge/streak earned. */
    fun heavyClick(context: Context) = fire(context, VibrationEffect.EFFECT_HEAVY_CLICK, 40)
}
