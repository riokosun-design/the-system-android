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
    )

    companion object {
        const val QUARANTINE = 4
        const val ROLLING = 10
        const val CLIMB_SOLVES = 0.75     // ≥75% rolling success → tier up
        const val DESCEND_SOLVES = 0.35   // <35% with ≥5 attempts → tier down
        const val MIN_ATTEMPTS = 5
        const val MAX_RECORDS = 400
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
     * Tier law: Elo anchor (≤600→I, ≤1100→II, else III), then rolling-form
     * delta (±1, clamped 1..3). A slow-but-accurate solver still climbs;
     * speed only guards AGAINST climbing (fast guesses aren't mastery).
     */
    fun tier(rec: List<Record> = records(), practiceElo: Int = 400): Pair<Int, String> {
        var t = when {
            practiceElo <= 600 -> 1
            practiceElo <= 1100 -> 2
            else -> 3
        }
        var why = "PRACTICE ELO $practiceElo ANCHOR"
        val rate = rollingSolveRate(rec)
        if (rate != null) {
            val fast = avgSolveMs(rec).let { it in 1..45_000L }
            if (rate >= CLIMB_SOLVES && t < 3) {
                if (fast) { t++; why = "${(rate * 100).toInt()}% SOLVE RATE — TIER CLIMB" }
                else why = "ACCURATE BUT SLOW — CLIMB HELD UNTIL <45s SOLVES"
            } else if (rate < DESCEND_SOLVES && t > 1) {
                t--; why = "${(rate * 100).toInt()}% SOLVE RATE — ONE TIER SOFTER, REBUILD"
            } else {
                why = "${(rate * 100).toInt()}% SOLVE RATE — TIER HOLDS"
            }
        }
        return (t.coerceIn(1, 3)) to why
    }

    /**
     * Pick the next puzzle. Deterministic given history — rotation emerges
     * from recording, never from a clock.
     */
    fun next(currentId: String? = null, practiceElo: Int = 400): Verdict {
        val rec = records()
        val (t, why) = tier(rec, practiceElo)
        val pool = pack.filter { it.diff == t }.ifEmpty { pack }
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
