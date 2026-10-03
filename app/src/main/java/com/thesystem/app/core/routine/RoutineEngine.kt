package com.thesystem.app.core.routine

/**
 * ADAPTIVE ROUTINE ENGINE (STEP 7) — the 4-week progression arc behind the
 * daily routine. Pure Kotlin, zero android: the sim harness owns the proof,
 * the drafter only maps these values onto the day.
 *
 * THE FOUR LAWS
 *
 * 1. ANCHOR LAW — a schedule that moves is not a schedule. Wake 06:30 and
 *    lights-out 23:00 are BYTE-IDENTICAL in all four weeks. Progression
 *    happens in the WORK blocks, never in the anchors; identity forms around
 *    the immovable.
 *
 * 2. PROGRESSION LAW (behavioral) — week 1 is embarrassing-small on purpose
 *    (5-minute walk, 20-minute training): the smallest habit that survives
 *    contact with a bad day. Growth uses ABSOLUTE minute steps (≤ +10 min
 *    training / ≤ +5 min walk / ≤ +1 quest slot per week) — the unit human
 *    habituation actually feels, never percentage jumps.
 *
 * 3. SAFETY LAW — minors train capped (≤30 min, ≤3 morning quest slots); one
 *    lighter recovery-emphasis day per week (Sunday: training block becomes
 *    RECOVERY at the SAME time slot — no skip-day psychology, anchors never
 *    skip); sleep window ≥ 7.5 h is structural, not aspirational.
 *
 * 4. ADAPTIVE LAW — the week state is server-owned (users.routine_week,
 *    mig 023). Weekly evaluation: ≥5 active days of the last 7 advances one
 *    week; ≤2 consolidates one (framed as REBUILD, never punishment); else
 *    HOLD. Max 4, floor 1. The verdict math here MIRRORS the SQL exactly —
 *    the sim pins both to the same truth.
 */
data class WeekPlan(
    val week: Int,                  // 1..4 — server-owned
    val name: String,
    val focus: String,              // honest one-line behavioral intent
    val wakeMin: Int,               // ANCHOR — fixed
    val lightsOutMin: Int,          // ANCHOR — fixed
    val morningQuestCap: Int,       // morning cluster size cap
    val trainingMin: Int,           // primary session length
    val walkMin: Int,               // NEAT base
    val recoveryMin: Int,           // dedicated recovery block
)

enum class WeekVerdict { ADVANCE, HOLD, CONSOLIDATE }

object RoutineEngine {

    const val WAKE_MIN = 6 * 60 + 30          // 06:30 — never moves
    const val LIGHTS_OUT_MIN = 23 * 60        // 23:00 — never moves
    const val MIN_SLEEP_HOURS = 7.5
    const val ADVANCE_ACTIVE_DAYS = 5         // of the last 7
    const val CONSOLIDATE_ACTIVE_DAYS = 2
    const val MAX_WEEK = 4

    const val MORNING_SLOT_MIN = 6 * 60 + 45    // first cluster opens 06:45
    const val EVENING_SLOT_MIN = 16 * 60        // overflow cluster opens 16:00

    /** The four-week arc. Values are the law — change here, sim re-verifies. */
    fun weekPlan(week: Int, minor: Boolean = false): WeekPlan {
        val w = week.coerceIn(1, MAX_WEEK)
        var plan = when (w) {
            1 -> WeekPlan(
                week = 1, name = "FOUNDATION", focus = "show up small — five minutes still counts",
                wakeMin = WAKE_MIN, lightsOutMin = LIGHTS_OUT_MIN,
                morningQuestCap = 2, trainingMin = 20, walkMin = 5, recoveryMin = 10,
            )
            2 -> WeekPlan(
                week = 2, name = "BUILD", focus = "+10 training min · +5 walk min — anchors untouched",
                wakeMin = WAKE_MIN, lightsOutMin = LIGHTS_OUT_MIN,
                morningQuestCap = 3, trainingMin = 30, walkMin = 10, recoveryMin = 10,
            )
            3 -> WeekPlan(
                week = 3, name = "TEMPER", focus = "hold the load, sharpen recovery",
                wakeMin = WAKE_MIN, lightsOutMin = LIGHTS_OUT_MIN,
                morningQuestCap = 3, trainingMin = 40, walkMin = 15, recoveryMin = 15,
            )
            else -> WeekPlan(
                week = 4, name = "ADVANCED", focus = "full protocol — earned, not given",
                wakeMin = WAKE_MIN, lightsOutMin = LIGHTS_OUT_MIN,
                morningQuestCap = 4, trainingMin = 45, walkMin = 20, recoveryMin = 20,
            )
        }
        if (minor) plan = plan.copy(
            morningQuestCap = plan.morningQuestCap.coerceAtMost(3),
            trainingMin = plan.trainingMin.coerceAtMost(30),
            focus = plan.focus + " · under-18 conservative scaling",
        )
        return plan
    }

    /** Sleep window sanity — structural across every plan. */
    val sleepHours: Double get() =
        ((24 * 60 - LIGHTS_OUT_MIN) + WAKE_MIN) / 60.0

    /** Weekly adherence verdict — MIRRORS migration 023 SQL exactly. */
    fun weeklyVerdict(activeDays: Int): WeekVerdict = when {
        activeDays >= ADVANCE_ACTIVE_DAYS -> WeekVerdict.ADVANCE
        activeDays <= CONSOLIDATE_ACTIVE_DAYS -> WeekVerdict.CONSOLIDATE
        else -> WeekVerdict.HOLD
    }

    fun nextWeek(current: Int, verdict: WeekVerdict): Int = when (verdict) {
        WeekVerdict.ADVANCE -> (current + 1).coerceAtMost(MAX_WEEK)
        WeekVerdict.CONSOLIDATE -> (current - 1).coerceAtLeast(1)
        WeekVerdict.HOLD -> current.coerceIn(1, MAX_WEEK)
    }

    /** Honest UI copy for a verdict — SYSTEM voice: direct, no shaming. */
    fun verdictCopy(verdict: WeekVerdict, fromWeek: Int, toWeek: Int, activeDays: Int): String = when (verdict) {
        WeekVerdict.ADVANCE -> "WEEK $fromWeek → $toWeek — $activeDays/7 active days. The load grows; the anchors stand."
        WeekVerdict.HOLD -> "WEEK $toWeek HOLDS — $activeDays/7 active days. Consistency before intensity."
        WeekVerdict.CONSOLIDATE -> "REBUILD WEEK — $activeDays/7 active days. One step softer, zero shame. Chain unbroken."
    }
}
