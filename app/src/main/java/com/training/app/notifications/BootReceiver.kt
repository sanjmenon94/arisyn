package com.training.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Exact alarms don't survive a reboot — without this, the 9am reminder would silently stop
 * firing after the phone restarts until the user happened to reopen Arisyn. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AlarmScheduler.scheduleNext9AM(context)
    }
}
