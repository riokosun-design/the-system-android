package com.thesystem.app.core

import java.util.Calendar

/**
 * vC56 §4 — NIGHT QUIET HOURS LAW: 22:30 → 06:30 local, every day, no
 * exceptions. A hunter's sleep is a stat the System guards harder than any
 * reminder.
 *
 * LAW:
 *   isQuiet    — the instant falls inside [22:30, 06:30) local.
 *   defer      — a scheduled instant inside quiet hours slides to the NEXT
 *                06:30 gate (same night → this morning's gate; deep night →
 *                tomorrow's). Outside quiet hours the instant stands.
 *   canPostNow — spontaneous notifications die silently instead of buzzing.
 *
 * Silent ≠ soft: discipline ENFORCEMENT (locks, seals, watchdog verdicts)
 * runs 24/7 and never sleeps — only the NOISE is quarantined. Pure JVM: the
 * calendar math is proven in the engine harness, not hoped for.
 */
object QuietHours {

    const val START_MINUTES = 22 * 60 + 30   // 22:30
    const val END_MINUTES = 6 * 60 + 30      // 06:30

    fun isQuiet(nowMs: Long = System.currentTimeMillis()): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return mins >= START_MINUTES || mins < END_MINUTES
    }

    fun canPostNow(nowMs: Long = System.currentTimeMillis()): Boolean = !isQuiet(nowMs)

    /**
     * Slide an instant out of the quiet window onto the 06:30 gate.
     *  - 18:00 → stands (not quiet)
     *  - 23:40 Tue → 06:30 Wed
     *  - 03:15 Wed → 06:30 Wed
     *  - exactly 22:30 counts as quiet; exactly 06:30 does not.
     */
    fun defer(atMs: Long): Long {
        if (!isQuiet(atMs)) return atMs
        val c = Calendar.getInstance().apply { timeInMillis = atMs }
        val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (mins >= START_MINUTES) c.add(Calendar.DAY_OF_YEAR, 1)   // deep night → tomorrow's gate
        c.set(Calendar.HOUR_OF_DAY, END_MINUTES / 60)
        c.set(Calendar.MINUTE, END_MINUTES % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
