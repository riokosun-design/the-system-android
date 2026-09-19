package com.thesystem.app.core

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * THE SYSTEM — Core Rule Engine.
 *
 * ⚠ These rules are mirrored 1:1 in `supabase/migrations/002_functions.sql`.
 * The server is the source of truth; this engine exists for instant UI math
 * (progress bars, prediction previews, power previews) with zero network cost.
 * Never change one side without the other.
 */
object SystemMath {

    // ── Leveling: XP = 100 × N^1.8 ───────────────────────────────────────────
    const val MAX_LEVEL = 100
    private const val XP_BASE = 100.0
    private const val XP_EXPONENT = 1.8

    /** Total accumulated XP required to BE [level]. Level 1 = 0 XP. */
    fun xpRequiredForLevel(level: Int): Long =
        (XP_BASE * (level - 1).coerceAtLeast(0).toDouble().pow(XP_EXPONENT)).roundToLong()

    fun levelForXp(totalXp: Long): Int {
        var level = 1
        while (level < MAX_LEVEL && totalXp >= xpRequiredForLevel(level + 1)) level++
        return level
    }

    /** 0f..1f progress through the current level. */
    fun progressInLevel(totalXp: Long): Float {
        val lvl = levelForXp(totalXp)
        if (lvl >= MAX_LEVEL) return 1f
        val floorXp = xpRequiredForLevel(lvl)
        val span = (xpRequiredForLevel(lvl + 1) - floorXp).coerceAtLeast(1L).toFloat()
        return ((totalXp - floorXp).toFloat() / span).coerceIn(0f, 1f)
    }

    // ── Ranks: penalty ranks override level ranks ────────────────────────────
    enum class HunterRank(val title: String, val minLevel: Int, val isPenaltyRank: Boolean = false) {
        LOSER("LOSER", 0, isPenaltyRank = true),
        GARBAGE("GARBAGE", 0, isPenaltyRank = true),
        AVERAGE("AVERAGE", 1),
        ELITE("ELITE", 25),
        S_RANK("S-RANK", 50),
        MASTERPIECE("MASTERPIECE", 80);

        val order: Int get() = when (this) { LOSER -> 0; GARBAGE -> 1; AVERAGE -> 2; ELITE -> 3; S_RANK -> 4; MASTERPIECE -> 5 }

        companion object {
            val storeRanks get() = listOf(AVERAGE, ELITE, S_RANK, MASTERPIECE)
            fun fromTitle(t: String?): HunterRank = entries.firstOrNull { it.title == t || it.name == t } ?: AVERAGE
        }
    }

    /** Rank is a function of level, but 3+ consecutive skipped days degrade you to GARBAGE, 5+ to LOSER. */
    fun rankFor(level: Int, missedDays: Int): HunterRank = when {
        missedDays >= 5 -> HunterRank.LOSER
        missedDays >= 3 -> HunterRank.GARBAGE
        else -> HunterRank.entries.filter { !it.isPenaltyRank }.last { level >= it.minLevel }
    }

    // ── HUNTER TIERS — the F → SS progression ladder (RANK screen, 0.6.0) ──
    // Pure function of level; penalty ranks (GARBAGE/LOSER) stay a separate
    // overlay discipline via rankFor() so degradation never erases the ladder.
    enum class HunterTier(val letter: String, val minLevel: Int) {
        F("F", 1), E("E", 5), D("D", 12), C("C", 22), B("B", 35), A("A", 50), S("S", 70), SS("SS", 90);

        val title: String get() = "$letter-RANK"
    }

    fun tierFor(level: Int): HunterTier = HunterTier.entries.last { level >= it.minLevel }

    fun nextTier(level: Int): HunterTier? = HunterTier.entries.firstOrNull { it.minLevel > level }

    /** 0..1 progress from the current tier's floor to the next tier's gate. */
    fun tierProgress(level: Int): Float {
        val cur = tierFor(level)
        val nxt = nextTier(level) ?: return 1f
        return ((level - cur.minLevel).toFloat() / (nxt.minLevel - cur.minLevel)).coerceIn(0f, 1f)
    }

    fun penaltyStateFor(missedDays: Int): String = when {
        missedDays <= 0 -> "CLEAR"
        missedDays == 1 -> "WARNING"
        missedDays == 2 -> "DECAY"
        missedDays >= 5 -> "LOSER"
        else -> "GARBAGE"
    }

    // ── Penalty decay ────────────────────────────────────────────────────────
    /** XP lost when you skip a day. Escalates 3% per consecutive missed day. */
    fun xpDecayForMissedDay(currentXp: Long, missedDaysSoFar: Int): Long {
        val dayIndex = (missedDaysSoFar + 1).coerceAtLeast(1)
        return floor(currentXp * (0.03 * dayIndex)).toLong().coerceIn(0, currentXp)
    }

    // ── Form Evolution & Mystery Power (max 5) ───────────────────────────────
    val FORM_UNLOCK_LEVELS = intArrayOf(5, 20, 40, 60, 85)
    val FORM_BASE = doubleArrayOf(1.0, 1.75, 2.6, 3.8, 5.5)
    const val MAX_FORMS = 5

    enum class CombatStyle(val label: String, val multiplier: Double) {
        BALANCED("Balanced", 1.00),
        BERSERKER("Berserker", 1.15),
        MONK("Monk", 1.05),
        ASSASSIN("Assassin", 1.10),
        TANK("Tank", 0.95);

        companion object { fun from(s: String?) = entries.firstOrNull { it.name == s } ?: BALANCED }
    }

    /** Hard Work Multiplier: streaks & session volume pump it, decay kills it. Range 0.5x..3.0x. */
    fun hardWorkMultiplier(streakDays: Int, weeklySessions: Int, missedDays: Int): Double =
        (1.0 + 0.05 * streakDays.coerceIn(0, 20) + 0.08 * weeklySessions.coerceIn(0, 10) - 0.10 * missedDays.coerceAtLeast(0))
            .coerceIn(0.5, 3.0)

    /**
     * Power = (FormBase×100 + XP/250) × CombatStyle × HardWork.
     * A lazy Form 5 (mult 0.5) outputs less than a hardcore Form 1 (mult 3.0) — by design.
     */
    fun formPower(totalXp: Long, formIndex: Int, style: CombatStyle, hardWork: Double): Double {
        val idx = (formIndex - 1).coerceIn(0, MAX_FORMS - 1)
        val raw = (FORM_BASE[idx] * 100.0 + totalXp / 250.0) * style.multiplier * hardWork
        return (raw * 10.0).roundToLong() / 10.0
    }

    // ── Arena constants ──────────────────────────────────────────────────────
    const val BATTLE_WIN_XP = 150L
    const val BATTLE_LOSS_XP = 20L
    const val BATTLE_DURATION_SECONDS = 60

    // ── Prediction Engine: Payout = (Bet / WinningPool) × (TotalPool × 0.85) ─
    const val PLATFORM_CUT_BPS = 1500 // 15%
    fun expectedPayout(betVc: Long, winningSidePoolVc: Long, totalPoolVc: Long): Long {
        if (winningSidePoolVc <= 0L) return betVc // your bet is the entire winning pool
        val distributable = totalPoolVc * (10000 - PLATFORM_CUT_BPS) / 10000.0
        return floor((betVc.toDouble() / winningSidePoolVc.toDouble()) * distributable).toLong()
    }

    // ── Clan / Shadow Guild gates ────────────────────────────────────────────
    const val CLAN_MIN_LEVEL = 30
    const val CLAN_CREATE_COST_VC = 500L
    const val GUILD_TAX_BPS = 500 // 5% of XP-equivalent VC from non-members in shielded zones
    const val TERRITORY_ZONE_RADIUS_METERS = 1000f
    const val TERRITORY_GEOHASH_PRECISION = 6 // ≈ 1.2km x 0.61km cells

    // ── Referral engine cuts ─────────────────────────────────────────────────
    const val REF_CUT_MERCH = 0.10        // 10% merch (5-10 band, configured server-side per item)
    const val REF_CUT_BLACK_ROOM = 0.10   // 10% Black Room unlocks
    const val REF_CUT_TOURNAMENT = 0.05   // 5% of tournament winnings
    const val REF_CUT_CPA = 0.02          // 2% of CPA VC earnings

    // ── Formatting ───────────────────────────────────────────────────────────
    fun formatVc(v: Long): String = "%,d VC".format(v)
    fun formatXp(v: Long): String = "%,d XP".format(v)
}
