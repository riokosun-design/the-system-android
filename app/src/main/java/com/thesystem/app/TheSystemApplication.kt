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
        // vC54 §1 — CIRCADIAN rail: quest windows open LOUD (alarm sound), the
        // same rail carries watchdog verdicts (auto-reschedule + crimson lockdown).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CIRCADIAN, "Circadian alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        // vC53 §3 — chess audio covenant: re-arm the mute flag from disk BEFORE
        // any composable can read ChessSfx.isMuted() (cold start race closed).
        com.thesystem.app.ui.chess.ChessSfx.attach(this)
        // vC54 §3 — the 24-hour watchdog wakes even if nobody returns: WorkManager
        // + boundary alarm armed here; every process birth punches the presence clock.
        com.thesystem.app.service.SystemWatchdog.markActivity(this)
        runCatching { com.thesystem.app.service.SystemWatchdog.arm(this) }
        runCatching { com.thesystem.app.service.QuestRhythm.armToday(this) }
    }

    companion object {
        const val CHANNEL_SYSTEM = "system_events"
        const val CHANNEL_QUESTS = "quests"
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_REMINDER = "reminder"
        /** vC54 §1/§3 — circadian quest windows + watchdog verdicts (alarm sound). */
        const val CHANNEL_CIRCADIAN = "circadian"
    }
}
