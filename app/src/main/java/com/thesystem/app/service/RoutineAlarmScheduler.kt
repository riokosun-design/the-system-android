package com.thesystem.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * ROUTINE ALARMS (spec §7) — hunter-controlled wakeups for routine blocks.
 *
 * Laws: alarms are DEVICE-LOCAL (prefs + AlarmManager), exact where the OS
 * allows, resilient across reboot via [RoutineBootReceiver]. The hunter can
 * always disable, skip or delete — the System never forces an alarm to live.
 */
object RoutineAlarmScheduler {

    @Serializable
    data class RoutineAlarm(
        val id: Int,
        val label: String,
        val minutesOfDay: Int,          // 0..1439
        val daysMask: Int = 0b1111111,  // bit0=Mon .. bit6=Sun
        val enabled: Boolean = true,
        val snoozeMin: Int = 5,
    ) {
        /** Stable channel for editing: hash of identity, not of mutable fields. */
        companion object {
            fun stableId(label: String, minutes: Int): Int =
                (label.trim().lowercase() + "#" + minutes).hashCode() and 0x7FFFFFFF
        }
    }

    private const val PREFS = "routine_alarms"
    private val json = Json { ignoreUnknownKeys = true }

    fun alarms(context: Context): List<RoutineAlarm> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", null)
            ?: return emptyList()
        json.decodeFromString(ListSerializer(RoutineAlarm.serializer()), raw)
    }.getOrDefault(emptyList())

    fun save(context: Context, alarm: RoutineAlarm) {
        val list = alarms(context).filter { it.id != alarm.id } + alarm
        persist(context, list)
        if (alarm.enabled) schedule(context, alarm) else cancel(context, alarm.id)
    }

    fun delete(context: Context, id: Int) {
        persist(context, alarms(context).filter { it.id != id })
        cancel(context, id)
    }

    fun rescheduleAll(context: Context) {
        alarms(context).filter { it.enabled }.forEach { schedule(context, it) }
    }

    private fun persist(context: Context, list: List<RoutineAlarm>) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("list", json.encodeToString(ListSerializer(RoutineAlarm.serializer()), list))
                .apply()
        }
    }

    fun nextTriggerMillis(alarm: RoutineAlarm): Long {
        val now = Calendar.getInstance()
        val cal = now.clone() as Calendar
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, alarm.minutesOfDay / 60)
        cal.set(Calendar.MINUTE, alarm.minutesOfDay % 60)
        for (i in 0..7) {
            val dayBit = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) // Mon=0
            if (i > 0 || cal.timeInMillis > now.timeInMillis) {
                if ((alarm.daysMask shr dayBit) and 1 == 1) return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun schedule(context: Context, alarm: RoutineAlarm) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, alarm)
        val at = nextTriggerMillis(alarm)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                    .getOrElse { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            }
        }
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, RoutineAlarm(id, "", 0))
        runCatching { am.cancel(pi) }
    }

    private fun pending(context: Context, alarm: RoutineAlarm): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarm.id,
            Intent(context, QuestReminderReceiver::class.java).apply {
                putExtra("title", "ROUTINE — ${alarm.label.uppercase()}")
                putExtra("body", "Your scheduled block is due. Own it. ⚡")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Alarms must survive reboot — same contract as the step service. */
class RoutineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RoutineAlarmScheduler.rescheduleAll(context)
        }
    }
}
