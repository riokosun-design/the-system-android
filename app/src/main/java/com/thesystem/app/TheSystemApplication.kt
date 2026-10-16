package com.thesystem.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TheSystemApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // §7 ANTI-MOD PERIMETER — debugger/root/emulator/hooks/patchers/signature.
        // A breached process never reaches a single screen or network call.
        com.thesystem.app.core.security.AntiTamper.enforce(this)
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
