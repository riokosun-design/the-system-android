package com.thesystem.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.thesystem.app.MainActivity
import com.thesystem.app.R
import com.thesystem.app.TheSystemApplication

/**
 * SYSTEM-voice notifications.
 *
 * A completion is a SYSTEM EVENT, not a chat ping: dark glass panel, electric
 * blue hairline, HUD typography, quest icon, "QUEST COMPLETE", title, XP, and
 * the hunter's rank line. One id per quest type is reused and [dedupeSec]
 * suppresses repeats — the protocol never spams.
 */
object QuestNotifier {

    private const val NOTIF_QUEST_COMPLETE = 7100
    private const val NOTIF_REMINDER = 7200
    private val lastFired = HashMap<Int, Long>()

    /** Quest completion event: QUEST COMPLETE / title / +XP / VERIFIED / rank. */
    fun questComplete(
        context: Context,
        title: String,
        xp: Int,
        verifiedLabel: String,
        rankLine: String,
        notifId: Int = NOTIF_QUEST_COMPLETE,
    ) {
        if (!allow(notifId, dedupeSec = 2)) return
        val body = "+$xp XP · $verifiedLabel\n$rankLine"
        post(
            context = context,
            id = notifId,
            channel = TheSystemApplication.CHANNEL_QUESTS,
            title = "QUEST COMPLETE",
            subtitle = title.uppercase(),
            body = body,
            priority = NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    /** Reminder before a scheduled war, or when the recovery window closes. */
    fun reminder(context: Context, title: String, body: String, notifId: Int = NOTIF_REMINDER) {
        if (!allow(notifId, dedupeSec = 30)) return
        post(
            context = context,
            id = notifId,
            channel = TheSystemApplication.CHANNEL_REMINDER,
            title = title,
            subtitle = null,
            body = body,
            priority = NotificationCompat.PRIORITY_HIGH,
        )
    }

    private fun allow(id: Int, dedupeSec: Int): Boolean {
        val now = System.currentTimeMillis()
        val last = lastFired[id] ?: 0L
        if (now - last < dedupeSec * 1000L) return false
        lastFired[id] = now
        return true
    }

    private fun post(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        subtitle: String?,
        body: String,
        priority: Int,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm, channel, title)
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (subtitle != null) "$subtitle — $body" else body
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_system)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFF38BDF8.toInt())
            .setColorized(false)
            .setAutoCancel(true)
            .setPriority(priority)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    private fun ensureChannel(nm: NotificationManager, channel: String, title: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(channel) != null) return
        nm.createNotificationChannel(
            NotificationChannel(channel, "SYSTEM events", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}

/**
 * Scheduled-war / recovery-window alarms land here and re-post through the
 * SYSTEM voice. Registered in the manifest, no-ops if the app is gone.
 */
class QuestReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "SYSTEM REMINDER"
        val body = intent.getStringExtra("body") ?: return
        QuestNotifier.reminder(context, title, body, notifId = 7201)
    }
}
