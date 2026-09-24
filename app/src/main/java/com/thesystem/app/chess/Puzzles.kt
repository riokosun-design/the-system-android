package com.thesystem.app.chess

/**
 * SYSTEM PUZZLE PACK — hand-verified positions. Every line is engine-checked;
 * user plays their own moves, opponent replies are scripted from the line.
 * Difficulty 1..3; the puzzle engine adapts band per hunter performance (§14).
 */
data class Puzzle(
    val id: String,
    val fen: String,
    val line: List<String>,   // full UCI line: user, reply, user, reply, ...
    val motif: String,        // MATE | FORK | PIN | SKEWER | DISCOVERY | DEFLECTION | TACTIC
    val diff: Int,
    val hint: String,
) {
    /** Indices in [line] the hunter must play (every even ply — side to move first). */
    val userPlies: List<Int> get() = line.indices.filter { it % 2 == 0 }
}

object PuzzlePack {

    val ALL: List<Puzzle> = listOf(
        // ── TIER I ──────────────────────────────────────────────────────
        Puzzle(
            "m1", "6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1",
            listOf("d1d8"), "MATE", 1, "The back rank is a prison.",
        ),
        Puzzle(
            "m2", "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
            listOf("h5f7"), "MATE", 1, "The bishop kisses the queen's target.",
        ),
        Puzzle(
            "m3", "k7/2R5/1K6/8/8/8/8/8 w - - 0 1",
            listOf("c7c8"), "MATE", 1, "Cut the edge. No ceremony.",
        ),
        Puzzle(
            "t1", "4k3/8/8/8/2r5/8/4B3/4K3 w - - 0 1",
            listOf("e2c4"), "TACTIC", 1, "The rook is hanging. Collect.",
        ),

        // ── TIER II ─────────────────────────────────────────────────────
        Puzzle(
            "m4", "2r3k1/5p1p/6pQ/8/8/2B5/6PP/6K1 w - - 0 1",
            listOf("h6g7"), "MATE", 2, "The bishop on c3 is the silent witness.",
        ),
        Puzzle(
            "f1", "r3k3/8/8/1N6/8/8/8/4K3 w q - 0 1",
            listOf("b5c7", "e8d8", "c7a8"), "FORK", 2, "One square. Two prisoners.",
        ),
        Puzzle(
            "p1", "4k3/4n3/8/8/8/8/8/R5K1 w - - 0 1",
            listOf("a1e1", "e8f7", "e1e7"), "PIN", 2, "Freeze it on the file. Then harvest.",
        ),
        Puzzle(
            "s1", "4k2q/8/8/8/8/8/8/R3K3 w - - 0 1",
            listOf("a1a8", "e8d7", "a8h8"), "SKEWER", 2, "Drive the king off the rank.",
        ),
        Puzzle(
            "dc1", "4k3/8/8/4N3/3q4/8/7P/4R2K w - - 0 1",
            listOf("e5c6", "e8d7", "c6d4"), "DISCOVERY", 2, "Move the knight. Open the file.",
        ),
        Puzzle(
            "t2", "4k3/8/8/8/4B3/3q4/8/4R1K1 w - - 0 1",
            listOf("e4d3", "e8d8"), "TACTIC", 2, "Take with tempo. The file opens by itself.",
        ),

        // ── TIER III ────────────────────────────────────────────────────
        Puzzle(
            "df1", "6k1/5q1p/6p1/8/8/8/6PP/3R2K1 w - - 0 1",
            listOf("d1d8", "f7f8", "d8f8"), "DEFLECTION", 3, "Drag the queen into the trade.",
        ),
        Puzzle(
            "m5", "k7/8/3R4/8/8/8/8/K6R w - - 0 1",
            listOf("d6d7", "a8b8", "h1h8"), "MATE", 3, "Wait one move. Then close the lid.",
        ),
    )

    fun byDiff(d: Int): List<Puzzle> = ALL.filter { it.diff == d }

    /** Date-seeded daily pick — deterministic worldwide, rotates tiers. */
    fun daily(epochDay: Long): Puzzle = ALL[(epochDay % ALL.size).toInt()]
}
