package com.thesystem.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.thesystem.app.core.QuietHours
import com.thesystem.app.data.model.QuestDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.Calendar

/**
 * QUEST RHYTHM (vC54 §1) — the circadian war clock.
 *
 * Every daily quest carries a server-stamped `scheduled_window` ("HH:MM", from
 * the Groq quest engine's circadian law). This object mirrors those windows
 * device-locally so the System keeps hunting even while the app is dead:
 *
 *  • Exact `AlarmManager` pings fire at each window with a HIGH-priority,
 *    ALARM-SOUND [CHANNEL_CIRCADIAN] notification — you cannot miss a war.
 *  • While any window (or the 15–30 min post-quest recovery break) is live,
 *    the discipline sentinel hard-locks the toggled entertainment apps.
 *  • Completing a block marks its window done and arms the recovery-end ping.
 *
 * All state is device-local prefs: verdicts never need the network and never
 * mint XP — the server stays the only progress judge.
 */
object QuestRhythm {

    @Serializable
    data class Window(
        val id: Long,
        val title: String,
        val startMin: Int,          // minutes into the local day
        val estSec: Int,            // server estimate for the block
        val done: Boolean = false,
    ) {
        val lenSec: Int get() = estSec.coerceIn(MIN_WINDOW_SEC, MAX_WINDOW_SEC)
        val endMin: Int get() = startMin + lenSec / 60
    }

    private const val PREFS = "system_quest_rhythm"
    private const val KEY_DATE = "date"
    private const val KEY_WINDOWS = "windows"
    private const val KEY_NOTICE_DATE = "notice_date"       // auto-reschedule notice: once/day
    private const val KEY_EXEC_ID = "exec_id"               // block currently being executed
    private const val KEY_EXEC_AT = "exec_at"               // epoch ms the execution began
    const val MIN_WINDOW_SEC = 900                          // a war window is never under 15 min
    const val MAX_WINDOW_SEC = 3600                         // …and never beyond one hour

    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── sync ────────────────────────────────────────────────────────────────

    /** Called after every server quest sync: mirrors TODAY's mandatory protocol
     *  (trials excluded — the penalty system owns those) and re-arms alarms. */
    fun syncFromQuests(context: Context, todayQuests: List<QuestDto>) {
        val today = LocalDate.now().toString()
        val prevDone = windows(context).associate { it.id to it.done }
        val list = todayQuests.sortedBy { it.seq }.mapNotNull { q ->
            val start = windowStartMin(q) ?: return@mapNotNull null
            Window(
                id = q.id,
                title = q.title,
                startMin = start,
                estSec = q.estDurationSec,
                done = q.isDone || prevDone[q.id] == true,
            )
        }
        prefs(context).edit()
            .putString(KEY_DATE, today)
            .putString(KEY_WINDOWS, json.encodeToString(ListSerializer(Window.serializer()), list))
            .apply()
        armToday(context)
    }

    /** "HH:MM" → minutes-of-day. Anything malformed is dropped, never guessed. */
    private fun windowStartMin(q: QuestDto): Int? {
        val w = q.scheduledWindow ?: return defaultSlotMin(q.seq)
        val p = w.split(":")
        if (p.size != 2) return defaultSlotMin(q.seq)
        val h = p[0].toIntOrNull() ?: return defaultSlotMin(q.seq)
        val m = p[1].toIntOrNull() ?: return defaultSlotMin(q.seq)
        if (h !in 0..23 || m !in 0..59) return defaultSlotMin(q.seq)
        return h * 60 + m
    }

    /** Fallback rhythm for engine rows that shipped without a window stamp. */
    private fun defaultSlotMin(seq: Int): Int = when (seq) {
        1 -> 6 * 60          // morning block
        2 -> 16 * 60 + 30    // peak-temperature war
        else -> 20 * 60      // evening polish
    }

    fun windows(context: Context): List<Window> {
        val p = prefs(context)
        if (p.getString(KEY_DATE, null) != LocalDate.now().toString()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Window.serializer()), p.getString(KEY_WINDOWS, null) ?: return emptyList())
        }.getOrDefault(emptyList())
    }

    fun minutesNow(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    // ── window law ──────────────────────────────────────────────────────────

    /** True while any unfinished window is open — or while the mandatory
     *  15–30 min post-quest recovery break still runs (vC54 §1). */
    fun isQuestWindowActive(context: Context): Boolean {
        val now = minutesNow()
        if (windows(context).any { !it.done && now in it.startMin until it.startMin + it.lenSec / 60 + 1 }) return true
        return RecoveryTracker.remainingSec(context) > 0
    }

    /** The first open window containing "now", if any (HUD / lock copy). */
    fun activeWindow(context: Context): Window? {
        val now = minutesNow()
        return windows(context).firstOrNull { !it.done && now in it.startMin until it.endMin + 1 }
    }

    /** Latest unfinished-window deadline (epoch ms), or 0 when none known. */
    fun lastDeadlineMillis(context: Context): Long {
        val ws = windows(context).filter { !it.done }
        if (ws.isEmpty()) return 0L
        val latest = ws.maxOf { it.startMin + it.lenSec / 60 }
        return atMinutesToday(latest)
    }

    // ── execution marks ─────────────────────────────────────────────────────

    fun markExecutionStarted(context: Context, questId: Long) {
        prefs(context).edit()
            .putLong(KEY_EXEC_ID, questId)
            .putLong(KEY_EXEC_AT, System.currentTimeMillis())
            .apply()
    }

    /** Block verified → its window is done; schedule the recovery-end ping and
     *  refresh the watchdog next boundary (the break counts as activity). */
    fun onQuestCompleted(context: Context) {
        val execId = prefs(context).getLong(KEY_EXEC_ID, -1L)
        if (execId >= 0) {
            val list = windows(context).map { if (it.id == execId) it.copy(done = true) else it }
            prefs(context).edit()
                .putString(KEY_WINDOWS, json.encodeToString(ListSerializer(Window.serializer()), list))
                .apply()
        }
        val recoveryUntil = RecoveryTracker.deadline(context)
        if (recoveryUntil > System.currentTimeMillis()) {
            exactAlarm(
                context,
                at = recoveryUntil,
                requestCode = 6601,
                extras = mapOf(
                    "kind" to "RECOVERY_END",
                    "title" to "RECOVERY COMPLETE",
                    "body" to "The break is over. The next block is armed — move. ⚡",
                ),
            )
        }
        SystemWatchdog.markActivity(context)
        armToday(context)
    }

    // ── auto-reschedule (watchdog, <1h breach) ──────────────────────────────

    /** Every unfinished window that has already expired (or is expiring right
     *  now) is re-armed from "now + 10 min", stacked with 15 min breathing
     *  room. Returns true when at least one window moved. */
    fun shiftRemainingToNow(context: Context): Boolean {
        val now = minutesNow()
        val list = windows(context)
        var cursor = now + 10
        var moved = false
        val shifted = list.sortedBy { it.startMin }.map { w ->
            if (!w.done && w.startMin <= now) {
                moved = true
                val nw = w.copy(startMin = (cursor % 1440))
                cursor += nw.lenSec / 60 + 15
                nw
            } else w
        }
        if (!moved) return false
        prefs(context).edit()
            .putString(KEY_WINDOWS, json.encodeToString(ListSerializer(Window.serializer()), shifted))
            .apply()
        armToday(context)
        return true
    }

    /** The reschedule notice fires at most once per local day. */
    fun noticeStampedToday(context: Context): Boolean =
        prefs(context).getString(KEY_NOTICE_DATE, null) == LocalDate.now().toString()

    fun stampNotice(context: Context) {
        prefs(context).edit().putString(KEY_NOTICE_DATE, LocalDate.now().toString()).apply()
    }

    // ── alarms ──────────────────────────────────────────────────────────────

    /** Re-arm every future window ping. Called on sync / app start / boot. */
    fun armToday(context: Context) {
        val now = Calendar.getInstance().timeInMillis
        windows(context).filter { !it.done }.forEach { w ->
            val at = atMinutesToday(w.startMin) // the war opens at the stamped slot
            if (at > now) {
                exactAlarm(
                    context,
                    at = at,
                    requestCode = (6500 + (w.id % 400).toInt()),
                    extras = mapOf(
                        "kind" to "WINDOW_OPEN",
                        "title" to "QUEST WINDOW OPEN — ${w.title.uppercase()}",
                        "body" to "Your scheduled block is live. Enter the System and execute. Social media is sealed.",
                    ),
                )
            }
        }
    }

    fun nextBoundaryMillis(context: Context): Long {
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Long>()
        windows(context).filter { !it.done }.forEach { w ->
            candidates += atMinutesToday(w.startMin)
            candidates += atMinutesToday(w.startMin + w.lenSec / 60)          // deadline
            candidates += atMinutesToday(w.startMin + w.lenSec / 60 + 60)     // grace end
        }
        val recovery = RecoveryTracker.deadline(context)
        if (recovery > now) candidates += recovery
        return candidates.filter { it > now }.minOrNull() ?: 0L
    }

    private fun atMinutesToday(min: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, (min / 60) % 24)
            set(Calendar.MINUTE, min % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    /** vC56 §4 — any war ping drifting into the 22:30–06:30 quiet window is
     *  deferred to the next 06:30 gate; the System never buzzes a sleeping
     *  hunter. */
    private fun exactAlarm(context: Context, at: Long, requestCode: Int, extras: Map<String, String>) {
        val fireAt = QuietHours.defer(at)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, QuestWindowReceiver::class.java).apply { extras.forEach { (k, v) -> putExtra(k, v) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi) }
                    .getOrElse { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi) }
            }
        }
    }
}

/**
 * Window pings land here: HIGH-priority, ALARM-SOUND circadian notifications —
 * a scheduled war opens with a signal, not a whisper (vC54 §1).
 */
class QuestWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val title = intent.getStringExtra("title") ?: return
        val body = intent.getStringExtra("body") ?: return
        QuestNotifier.circadian(
            context, title, body,
            notifId = if (kind == "RECOVERY_END") 6601 else 6602,
        )
    }
}
