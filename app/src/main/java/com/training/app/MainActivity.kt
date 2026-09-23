package com.training.app

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.training.app.data.TrainingRepository
import com.training.app.data.local.MIGRATION_1_2
import com.training.app.data.local.MIGRATION_2_3
import com.training.app.data.local.MIGRATION_3_4
import com.training.app.data.local.MIGRATION_4_5
import com.training.app.data.local.MIGRATION_5_6
import com.training.app.data.local.MIGRATION_6_7
import com.training.app.data.local.MIGRATION_7_8
import com.training.app.data.local.MIGRATION_8_9
import com.training.app.data.local.TrainingDatabase
import com.training.app.notifications.AlarmScheduler
import com.training.app.ui.TrainingApp
import com.training.app.ui.theme.TrainingTheme
import com.training.app.viewmodel.TrainingViewModel

class MainActivity : ComponentActivity() {
    private lateinit var model: TrainingViewModel

    // Android 13+ requires this at runtime — without it the 9am reminder would silently never
    // show. Nothing else in the flow depends on the result: if denied, the alarm still schedules
    // and fires, it just has no notification to show.
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val db = Room.databaseBuilder(applicationContext, TrainingDatabase::class.java, "training.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(c: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return TrainingViewModel(TrainingRepository(db.dao())) as T
            }
        })[TrainingViewModel::class.java]

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestExactAlarmPermissionIfNeeded()
        AlarmScheduler.scheduleNext9AM(this)
        handleSleepReminderIntent(intent)

        setContent { TrainingTheme { TrainingApp(model) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSleepReminderIntent(intent)
    }

    // Tapping the reminder notification (built in SleepReminderReceiver) lands here with this
    // action; the actual dialog is opened by TodayScreen, which observes this flag on the shared
    // ViewModel rather than a navigation route, since the sleep entry point is a dialog, not a
    // screen.
    private fun handleSleepReminderIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_SLEEP_ENTRY) model.triggerSleepEntry()
    }

    // Exact alarms need an explicit user grant on Android 12+ (a manifest declaration alone isn't
    // enough once targeting API 33+) — this sends them straight to the one settings screen that
    // grants it, only when it isn't already on.
    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = getSystemService(AlarmManager::class.java)
        if (!am.canScheduleExactAlarms()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    companion object {
        const val ACTION_OPEN_SLEEP_ENTRY = "com.training.app.action.OPEN_SLEEP_ENTRY"
    }
}
