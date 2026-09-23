package com.training.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZoneId
import java.time.ZonedDateTime

/** Schedules the next 9am sleep-reminder firing, one alarm at a time — the receiver reschedules
 * the day after itself when it fires, rather than using AlarmManager.setRepeating, since exact
 * repeating alarms aren't reliably exact on modern Android; a self-rescheduling chain is. */
object AlarmScheduler {
    private const val REQUEST_CODE = 9001

    fun scheduleNext9AM(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) return
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(9, 0).atZone(ZoneId.systemDefault())
        if (!next.isAfter(now)) next = next.plusDays(1)
        val intent = Intent(context, SleepReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending)
        } catch (e: SecurityException) {
            // Exact-alarm permission was revoked between the canScheduleExactAlarms() check and
            // this call (e.g. the user just turned it off in Settings) — skip silently rather than
            // crash; the reminder just won't fire until the permission is granted again.
        }
    }
}
