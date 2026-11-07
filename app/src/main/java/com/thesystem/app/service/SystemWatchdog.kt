package com.thesystem.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * SYSTEM WATCHDOG (vC54 §3) — the 24-hour sentinel that never sleeps.
 *
 * Two rails watch the hunter while the app is dead:
 *
 *   • WorkManager — a periodic audit every ~15 min (the OS minimum), resilient
 *     across reboots and process death.
 *   • AlarmManager — an exact boundary alarm pinned to the next quest-window
 *     edge (start / deadline / +1h grace end), re-armed after every audit.
 *
 * Verdicts (device-local, offline-proof; XP and decay stay with the server):
 *
 *   • app silent for 24h+, or a quest window breached by more than one hour →
 *     CRIMSON LOCKDOWN engages (PenaltyLockdown) — Instagram, YouTube and
 *     Snapchat seal until the redemption gauntlet is cleared.
 *   • window breached by under one hour → the remaining windows
 *     auto-reschedule to "now + 10 min" and the hunter gets one SYSTEM NOTICE.
 */
object SystemWatchdog {

    private const val PREFS = "system_watchdog"
    private const val KEY_LAST_ACTIVITY = "last_activity_ms"
    private const val WORK_NAME = "system_watchdog_periodic"
    private const val REQ_ALARM = 6699

    /** 24 hours of silence = abandonment. */
    private const val SILENCE_LIMIT_MS = 24L * 60 * 60 * 1000
    /** Quest windows breached by this long detonate the crimson lockdown. */
    const val GRACE_MS = 60L * 60 * 1000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Any real use of the System (launch, quest sync, completion, battle) punches this clock. */
    fun markActivity(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis()).apply()
    }

    fun lastActivityMs(context: Context): Long = prefs(context).getLong(KEY_LAST_ACTIVITY, 0L)

    /** Arm both rails. Safe to call repeatedly — WorkManager KEEP is idempotent. */
    fun arm(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
        scheduleNextAlarm(context)
    }

    // ── the audit ───────────────────────────────────────────────────────────

    fun audit(context: Context) {
        val now = System.currentTimeMillis()
        if (PenaltyLockdown.isActive(context)) {
            scheduleNextAlarm(context)
            return
        }

        // 24h+ of total silence → the hunter abandoned the System mid-war.
        val last = lastActivityMs(context)
        if (last > 0L && now - last >= SILENCE_LIMIT_MS) {
            PenaltyLockdown.engage(context, "24h silence — the System was abandoned. ")
            scheduleNextAlarm(context)
            return
        }

        // Quest-window lateness (only meaningful when we mirror today's protocol).
        val deadline = QuestRhythm.lastDeadlineMillis(context)
        if (deadline > 0L) {
            when {
                now - deadline > GRACE_MS ->
                    PenaltyLockdown.engage(context, "Quest window breached past the 1-hour grace. ")
                now > deadline && !QuestRhythm.noticeStampedToday(context) -> {
                    if (QuestRhythm.shiftRemainingToNow(context)) {
                        QuestRhythm.stampNotice(context)
                        QuestNotifier.circadian(
                            context,
                            title = "SYSTEM NOTICE",
                            body = "[ SYSTEM NOTICE: Schedule adjusted. Remaining quest windows " +
                                "re-armed from +10 min. Execute them — one more hour and lockdown lands. ]",
                            notifId = 6710,
                        )
                    }
                }
            }
        }
        scheduleNextAlarm(context)
    }

    // ── the exact boundary fallback ─────────────────────────────────────────

    fun scheduleNextAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager ?: return
        val next = QuestRhythm.nextBoundaryMillis(context)
        val at = if (next > 0L) next else System.currentTimeMillis() + 3 * 60 * 60 * 1000L
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_ALARM,
            Intent(context, WatchdogAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                    .getOrElse { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            }
        }
    }
}

/** WorkManager rail — runs even when the app was swiped away for good. */
class WatchdogWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        SystemWatchdog.audit(applicationContext)
    }.fold({ Result.success() }, { Result.retry() })
}

/** AlarmManager rail — fires at quest-window boundaries while the app is dead. */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            runCatching { SystemWatchdog.audit(context.applicationContext) }
            pending.finish()
        }.start()
    }
}
