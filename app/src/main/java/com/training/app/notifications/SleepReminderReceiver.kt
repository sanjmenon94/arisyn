package com.training.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.training.app.MainActivity
import com.training.app.R

/** Fires at 9am daily. Handles two intents: the alarm itself (show the reminder, then queue
 * tomorrow's), and the notification's own Dismiss action (the only way to clear it — the
 * notification is `setOngoing(true)` specifically so a stray swipe doesn't lose the reminder
 * before the user has actually logged anything). */
class SleepReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            return
        }
        showNotification(context)
        AlarmScheduler.scheduleNext9AM(context)
    }

    companion object {
        const val CHANNEL_ID = "sleep_reminder"
        const val NOTIFICATION_ID = 4200
        const val ACTION_DISMISS = "com.training.app.action.DISMISS_SLEEP_REMINDER"

        fun showNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Sleep reminder", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "A daily 9am nudge to log last night's sleep" }
                )
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SLEEP_ENTRY
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPending = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val dismissPending = PendingIntent.getBroadcast(
                context, 1, Intent(context, SleepReminderReceiver::class.java).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_sleep)
                .setContentTitle("How did you sleep?")
                .setContentText("Tap to log last night's sleep in Arisyn")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(contentPending)
                .addAction(0, "Dismiss", dismissPending)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }
    }
}
