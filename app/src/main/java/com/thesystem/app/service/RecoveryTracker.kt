package com.thesystem.app.service

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * RECOVERY WINDOW — the mandatory post-quest break (vC54 §1): a 15–30 minute
 * scientific recovery band. The server still proposes the rest per quest via
 * `daily_quests.rest_sec`; the device clamps it into the band
 * ([MIN_BREAK_SEC]…[MAX_BREAK_SEC]). Weak recovery is a lie.
 *
 * The deadline is persisted, so leaving the app, locking the screen or a
 * process restart never loses the countdown. The next block stays locked
 * until the window closes: that is the point of the protocol.
 */
object RecoveryTracker {

    private const val PREFS = "system_recovery"

    /** vC54 §1 — the recovery band is law: 15–30 minutes, nothing less. */
    const val MIN_BREAK_SEC = 900
    const val MAX_BREAK_SEC = 1800

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called when a block completes — starts the mandatory recovery countdown. */
    fun markBlockDone(context: Context, date: String, seq: Int, restSec: Int) {
        val bandSec = if (restSec <= 0) MIN_BREAK_SEC else restSec.coerceIn(MIN_BREAK_SEC, MAX_BREAK_SEC)
        val until = System.currentTimeMillis() + bandSec * 1000L
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
