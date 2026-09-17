package com.thesystem.app.service

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * RECOVERY WINDOW — 2–5 minutes between protocol blocks (server-configurable
 * per quest via `daily_quests.rest_sec`).
 *
 * The deadline is persisted, so leaving the app, locking the screen or a
 * process restart never loses the countdown. The next block stays locked
 * until the window closes: that is the point of the protocol.
 */
object RecoveryTracker {

    private const val PREFS = "system_recovery"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called when a block completes — starts the recovery countdown. */
    fun markBlockDone(context: Context, date: String, seq: Int, restSec: Int) {
        val until = System.currentTimeMillis() + restSec.coerceIn(0, 900) * 1000L
        prefs(context).edit()
            .putLong("until_$date", until)
            .putInt("after_seq_$date", seq)
            .apply()
    }

    fun deadline(context: Context, date: String = today()): Long =
        prefs(context).getLong("until_$date", 0L)

    fun afterSeq(context: Context, date: String = today()): Int =
        prefs(context).getInt("after_seq_$date", 0)

    /** Remaining seconds of the current recovery window (0 = ready). */
    fun remainingSec(context: Context, date: String = today()): Int {
        val until = deadline(context, date)
        if (until <= 0L) return 0
        return ((until - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
    }

    fun isBlocked(context: Context, seq: Int, date: String = today()): Boolean =
        remainingSec(context, date) > 0 && seq > afterSeq(context, date)

    fun clear(context: Context, date: String = today()) {
        prefs(context).edit().remove("until_$date").remove("after_seq_$date").apply()
    }

    /** "MM:SS" for the HUD countdown. */
    fun format(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

    fun today(): String = LocalDate.now().toString()

    fun nowLocal(): LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())
}
