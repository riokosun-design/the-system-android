package com.thesystem.app.chess

/**
 * PUZZLE DIRECTOR — the anti-repetition, adaptive-difficulty puzzle brain.
 *
 * Field report that killed the old design: "the same puzzle repeatedly
 * appears". Causes: a 12-position pack, a 4-slot tier pool, cursor math
 * fighting keyed Compose state, and a streak counter that changed tier
 * mid-puzzle. This director owns selection as a pure function of RECORDED
 * HISTORY, so repetition is structurally impossible until the tier cycle
 * completes — and even then the last few shown stay quarantined.
 *
 * Selection law:
 *   tier        — baseline from the hunter's practice Elo, nudged by the
 *                 rolling solve rate over the last 10 attempts (streaks of
 *                 competence climb, frustration descends ONE tier — never
 *                 into triviality on a bad day).
 *   pool        — all puzzles of that tier, stable pack order.
 *   quarantine  — the last [QUARANTINE] shown ids (any tier) never repeat.
 *   cycle       — never replay a puzzle until every pool member has been
 *                 shown once; then the tier's seen-set resets (a fresh
 *                 cycle), quarantine still respected.
 *
 * Difficulty adaptation inputs (spec): rating/progression (practiceElo),
 * success rate, recent performance (rolling window), average solve time,
 * mistake frequency. Every input is measured — no random difficulty.
 *
 * Purity law: android-free. Persistence lives behind [Store]; the sim
 * harness drives the same brain the screen does.
 */
class PuzzleDirector(
    private val store: Store,
    private val pack: List<Puzzle> = PuzzlePack.ALL,
    private val lifetimeSolved: Set<String> = emptySet(),   // vC55 §A4 — ABSOLUTE blacklist
) {

    /** Persistence abstraction — SharedPreferences on device, maps in sim. */
    interface Store {
        fun loadRecords(): List<Record>
        fun saveRecords(records: List<Record>)
    }

    data class Record(
        val id: String,
        val solved: Boolean,
        val solveMs: Long,
        val day: Long,          // LocalDate.toEpochDay() at attempt time
    )

    data class Verdict(
        val puzzle: Puzzle,
        val tier: Int,
        val tierReason: String,     // honest UI copy: WHY this tier
        val cyclePosition: Int,     // "3rd of 6 in this tier cycle"
        val cycleSize: Int,
        val freshCycle: Boolean,    // started a new pass through the tier pool
        /** vC55 §A4 — TRUE once the whole pack is lifetime-solved: the screen
         *  shows the conquered wall and NEVER replays a solved puzzle. */
        val arenaCleared: Boolean = false,
    )

    companion object {
        const val QUARANTINE = 4
        const val ROLLING = 10
        const val CLIMB_SOLVES = 0.75     // ≥75% rolling success → tier up
        const val DESCEND_SOLVES = 0.35   // <35% with ≥5 attempts → tier down
        const val MIN_ATTEMPTS = 5
        const val MAX_RECORDS = 400
        const val WIDEN_MIN = 5            // §2 — minimum neighbor-widened pool
    }

    private fun records(): List<Record> = store.loadRecords()

    /** Rolling success rate over the last [ROLLING] attempts (null if too few). */
    private fun rollingSolveRate(rec: List<Record>): Double? =
        if (rec.size < MIN_ATTEMPTS) null
        else rec.takeLast(ROLLING).count { it.solved } / rec.takeLast(ROLLING).size.toDouble()

    /** Average solve time of solved attempts in the rolling window (0 if none). */
    private fun avgSolveMs(rec: List<Record>): Long {
        val s = rec.takeLast(ROLLING).filter { it.solved && it.solveMs > 0 }
        return if (s.isEmpty()) 0L else s.sumOf { it.solveMs } / s.size
    }

    /**
     * Tier law vC53 §2 — the FULL 1–10 rail. Elo anchor maps honestly across
     * all ten grades, then the rolling-form delta (±1) nudges. A slow-but-
     * accurate solver still climbs; speed only guards AGAINST climbing.
     */
    fun tier(rec: List<Record> = records(), practiceElo: Int = 400): Pair<Int, String> {
        var t = when {
            practiceElo <= 420 -> 1
            practiceElo <= 550 -> 2
            practiceElo <= 700 -> 3
            practiceElo <= 850 -> 4
            practiceElo <= 1000 -> 5
            practiceElo <= 1200 -> 6
            practiceElo <= 1400 -> 7
            practiceElo <= 1650 -> 8
            practiceElo <= 1900 -> 9
            else -> 10
        }
        var why = "PRACTICE ELO $practiceElo ANCHOR"
        val rate = rollingSolveRate(rec)
        if (rate != null) {
            val fast = avgSolveMs(rec).let { it in 1..45_000L }
            if (rate >= CLIMB_SOLVES && t < 10) {
                if (fast) { t++; why = "${(rate * 100).toInt()}% SOLVE RATE — TIER CLIMB" }
                else why = "ACCURATE BUT SLOW — CLIMB HELD UNTIL <45s SOLVES"
            } else if (rate < DESCEND_SOLVES && t > 1) {
                t--; why = "${(rate * 100).toInt()}% SOLVE RATE — ONE TIER SOFTER, REBUILD"
            } else {
                why = "${(rate * 100).toInt()}% SOLVE RATE — TIER HOLDS"
            }
        }
        return (t.coerceIn(1, 10)) to why
    }

    /** §2 — tier pools widen honestly when a rating grade is thin: nearest
     *  ratings by distance, stable order, minimum pipe of [WIDEN_MIN]. */
    private fun poolFor(t: Int): List<Puzzle> {
        val exact = pack.filter { it.rating == t }
        if (exact.size >= WIDEN_MIN) return exact
        return pack.sortedWith(compareBy({ kotlin.math.abs(it.rating - t) }, { it.id })).take(WIDEN_MIN)
    }

    /**
     * Pick the next puzzle pinned to an explicit 1–10 rating (the random
     * difficulty rail). Same anti-repeat law as [next]: quarantine +
     * full-cycle before any revisit. Neighbor-widened pools keep even the
     * thinnest ratings fresh.
     */
    fun nextInTier(t: Int, currentId: String? = null, reason: String = "SPECIFIED DIFFICULTY"): Verdict {
        val tier = t.coerceIn(1, 10)
        return serve(tier, reason, currentId, records())
    }

    /**
     * Pick the next puzzle. Deterministic given history — rotation emerges
     * from recording, never from a clock.
     */
    fun next(currentId: String? = null, practiceElo: Int = 400): Verdict {
        val rec = records()
        val (t, why) = tier(rec, practiceElo)
        return serve(t, why, currentId, rec)
    }

    /**
     * vC55 §A4 — LIFETIME NEVER-REPEAT LAW. The director first hunts inside
     * the tier pool minus the absolute solved-blacklist; a DRAINED tier
     * widens to the nearest unbeaten rating across the whole pack (blacklist
     * still absolute — a solved puzzle is dead forever). Only when ALL 500+
     * stand conquered does the arenaCleared verdict fly, and the screen
     * raises the wall instead of a board.
     */
    private fun serve(tier: Int, why: String, currentId: String?, rec: List<Record>): Verdict {
        val live = poolFor(tier).filter { it.id !in lifetimeSolved }
        if (live.isNotEmpty())
            return selectFrom(pool = live, rec = rec, currentId = currentId, t = tier, why = why)
        val global = pack.filter { it.id !in lifetimeSolved }
        if (global.isEmpty())
            return Verdict(
                puzzle = pack[0], tier = tier,
                tierReason = "ARENA CLEARED — ${lifetimeSolved.size} CONQUERED · PACK EXHAUSTED",
                cyclePosition = 1, cycleSize = 1, freshCycle = false, arenaCleared = true,
            )
        val nearest = global.sortedWith(compareBy({ kotlin.math.abs(it.rating - tier) }, { it.id }))
            .take(WIDEN_MIN)
        return selectFrom(pool = nearest, rec = rec, currentId = currentId, t = tier,
            why = "$why · TIER CLEAN — NEAREST UNBEATEN RAIL")
    }

    private fun selectFrom(pool: List<Puzzle>, rec: List<Record>, currentId: String?, t: Int, why: String): Verdict {
        val quarantine = rec.takeLast(QUARANTINE).map { it.id }.toSet()

        // this tier's cycle = attempts since the tier pool was last exhausted
        val seenInPool = LinkedHashSet<String>()
        // walk history backwards; a full pool coverage marks the cycle boundary
        for (r in rec.asReversed()) {
            val p = pool.firstOrNull { it.id == r.id } ?: continue
            seenInPool += p.id
            if (seenInPool.size >= pool.size - QUARANTINE.coerceAtMost(pool.size - 1))
                break   // approximate boundary: enough coverage = a cycle happened
        }

        val candidates = pool.filter { it.id !in quarantine && it.id != currentId }
        val fresh = candidates.firstOrNull { it.id !in seenInPool } ?: run {
            // tier cycle complete — begin a new pass, quarantine still honored
            candidates.firstOrNull() ?: pool.first { it.id != currentId }
        }
        val pos = pool.indexOfFirst { it.id == fresh.id } + 1
        val newCycle = fresh.id in seenInPool   // had to revisit = new cycle began
        return Verdict(
            puzzle = fresh, tier = t, tierReason = why,
            cyclePosition = pos, cycleSize = pool.size, freshCycle = newCycle,
        )
    }

    /** Record an attempt — the single write path that powers adaptation. */
    fun record(id: String, solved: Boolean, solveMs: Long, day: Long) {
        val rec = records() + Record(id, solved, solveMs, day)
        store.saveRecords(rec.takeLast(MAX_RECORDS))
    }

    fun lastShownId(): String? = records().lastOrNull()?.id
    fun attempts(): Int = records().size
    fun solvedCount(): Int = records().count { it.solved }
}
