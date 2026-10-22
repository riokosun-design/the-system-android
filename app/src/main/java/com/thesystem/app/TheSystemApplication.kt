package com.thesystem.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TheSystemApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 0.25.1 — forensic hook goes FIRST: whatever dies after this line leaves
        // written testimony for the next launch (nightly panel, release silence).
        com.thesystem.app.core.CrashTrace.install(this)
        // §7 ANTI-MOD PERIMETER — 0.25.2 §2.1 hotfix: the execution warrant only
        // stands in RELEASE builds. The nightly (debug) lane must NEVER self-kill
        // at launch: budget-ROM false positives (test-keys build tags, stale su
        // stubs, vendor /system mounts) were executing honest hunters at frame 0.
        // Debug = sweep, NOTE the verdict, and let the hunter in. Release = enforce.
        if (BuildConfig.DEBUG) {
            com.thesystem.app.core.security.AntiTamper.sweep(this)
                ?.let { android.util.Log.w("AntiTamper", "nightly perimeter note: $it") }
        } else {
            com.thesystem.app.core.security.AntiTamper.enforce(this)
        }
        // Notification rail: quest events use the SYSTEM channel (dark glass,
        // blue border, HUD typography). Tracking is silent + ongoing; reminders
        // wake the hunter for scheduled wars.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYSTEM, "SYSTEM events", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUESTS, "Quest protocol", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Step & distance tracking", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Scheduled wars", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_SYSTEM = "system_events"
        const val CHANNEL_QUESTS = "quests"
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_REMINDER = "reminder"
    }
}
