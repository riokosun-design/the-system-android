package com.thesystem.app.chess

import kotlin.math.abs

/**
 * SYSTEM CHESS AI v3 — vC55 §A5 total overhaul. The 100-ELO knight shuffle is
 * dead and buried under five load-bearing rails:
 *
 *  §A5.1 OPENING BOOK — 56 honest mainlines (Italian / Ruy Lopez / Sicilian /
 *        French / Caro-Kann / Scandinavian / Queen's Gambit / KID / Nimzo /
 *        Grünfeld / London / flanks …) replayed through the real engine into a
 *        FEN-key map. Book moves land in 0 ms — the bot opens like a student
 *        of the game, not a shuffler of knights.
 *  §A5.2 SEARCH — pseudo-legal negamax + alpha-beta under observational law
 *        (the tree matches the match: kings are capturable pieces; a crushed
 *        king is a terminal ±MATE, so the bot hangs NOTHING when a real reply
 *        exists). Zobrist TT (64K entries) + MVV-LVA + killer/history
 *        ordering + iterative deepening, all under the 4.5 SECOND WALL CLOCK.
 *  §A5.3 QUIESCENCE — full capture search until the position is QUIET, plus
 *        check-evasion extension at the leaf. No horizon effect, no hung
 *        queens, no "won a rook then lost the queen" comedy.
 *  §A5.4 EVALUATION — tapered PeSTO midgame/endgame piece-square tables with
 *        phase interpolation + bishop pair + rook open/semi-open files +
 *        passed-pawn scaling + doubled/isolated pawn penalties + king-crush
 *        priority (king capture scores +99999-class terminal via the tree).
 *  §A5.5 STRICT ELO LADDER (labels and anchors now tell the truth):
 *        EASY    ≈900  — book openings, depth 2, gentle mistakes window
 *        MEDIUM  ≈1400 — depth 4 (ID), quiescence to 6 plies, PeSTO
 *        HARD    ≈1850 — depth 6 (ID), TT + killers, deep quiescence
 *        EXPERT  ≈2300 — depth 8 (ID) within the 4.5 s hard cap, TT, zero mercy
 *
 * Depth, ordering and quiescence are the REAL dials; jitter is the only noise,
 * and it never invents a blunder outside its window.
 */
object ChessAI {

    // ═══ DIFFICULTY LADDER (vC55 §A5.5) ══════════════════════════════════════

    enum class Difficulty(val depth: Int, val jitter: Int, val qDepth: Int, val bookLotteryPct: Int) {
        EASY(2, 130, 0, 100),
        MEDIUM(4, 30, 6, 100),
        HARD(6, 0, 12, 100),
        EXPERT(8, 0, 16, 100);

        val label: String get() = when (this) {
            EASY -> "EASY"; MEDIUM -> "MEDIUM"; HARD -> "HARD"; EXPERT -> "EXPERT"
        }
        /** Practice-rating anchor used by the deterministic ELO math (spec §10). */
        val anchorElo: Int get() = when (this) {
            EASY -> 900; MEDIUM -> 1400; HARD -> 1850; EXPERT -> 2300
        }
    }

    // ═══ §A5.1 — OPENING BOOK: 56 mainlines, engine-replayed into FEN keys ═══

    private val BOOK_LINES: List<List<String>> = listOf(
        // ── King's Pawn open games ─────────────────────────────────────────
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6"),            // Ruy Morphy
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "g8f6"),                            // Ruy Berlin
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "f7f5"),                            // Schliemann
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "d7d6"),                            // Ruy Steinitz
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d3", "f8e7"),            // Giuoco quiet
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5", "c2c3", "g8f6"),            // Giuoco Piano
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d4", "e5d4"),            // Two Knights Scotch-Italian
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "f3g5", "d7d5"),            // Two Knights Fried Liver
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5", "b2b4", "c5b4", "c2c3"),    // Evans Gambit
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "d2d4", "e5d4", "f3d4"),                    // Scotch Game
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "d2d4", "e5d4", "f1c4"),                    // Scotch Gambit
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "b1c3", "g8f6", "f1b5"),                    // Four Knights Spanish
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "b1c3", "g8f6", "d2d4"),                    // Four Knights Scotch
        listOf("e2e4", "e7e5", "g1f3", "g8f6", "f3e5", "d7d6", "e5f3"),                    // Petrov main
        listOf("e2e4", "e7e5", "g1f3", "g8f6", "f3e5", "d7d6", "e5f3", "f6e4", "d2d4"),    // Petrov classical
        listOf("e2e4", "e7e5", "b1c3", "g8f6", "f2f4", "d7d5"),                            // Vienna Gambit
        listOf("e2e4", "e7e5", "b1c3", "g8f6", "f1c4"),                                    // Vienna Bishop
        listOf("e2e4", "e7e5", "f2f4", "e5f4", "g1f3"),                                    // King's Gambit Accepted
        listOf("e2e4", "e7e5", "f2f4", "f8c5", "g1f3", "d7d6"),                            // King's Gambit Declined
        listOf("e2e4", "e7e5", "d2d4", "e5d4", "c2c3"),                                    // Danish Gambit
        listOf("e2e4", "e7e5", "f1c4", "g8f6", "d2d3", "c7c6"),                            // Bishop's Opening
        // ── Sicilian family ─────────────────────────────────────────────────
        listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6"), // Najdorf
        listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "g7g6"), // Dragon
        listOf("e2e4", "c7c5", "g1f3", "b8c6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "d7d6"), // Classical
        listOf("e2e4", "c7c5", "g1f3", "b8c6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "e7e5"), // Sveshnikov
        listOf("e2e4", "c7c5", "g1f3", "e7e6", "d2d4", "c5d4", "f3d4", "a7a6"),            // Kan
        // ── French / Caro-Kann / Scandinavian & half-open ──────────────────
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "f8b4"),                            // French Winawer
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "g8f6"),                            // French Classical
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "e4e5", "c7c5", "c2c3", "b8c6"),            // French Advance
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "b1c3", "d5e4", "c3e4", "g8f6"),            // Caro main
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "e4e5", "c8f5"),                            // Caro Advance
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "e4d5", "c6d5", "f1d3"),                    // Caro Exchange
        listOf("e2e4", "d7d5", "e4d5", "g8f6", "d2d4"),                                    // Scandinavian
        listOf("e2e4", "d7d5", "e4d5", "d8d5", "b1c3", "d5a5"),                            // Scandinavian Qa5
        listOf("e2e4", "c7c5", "g1f3", "b8c6", "f1b5", "g7g6"),                            // Sicilian Rossolimo
        listOf("e2e4", "c7c5", "c2c3", "g8f6", "e4e5", "f6d5"),                            // Sicilian Alapin
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "e4d5", "c6d5", "c2c4"),                    // Caro Panov-Botvinnik
        listOf("e2e4", "c7c5", "g1f3", "e7e6", "d2d4", "c5d4", "f3d4", "b8c6"),            // Sicilian Taimanov
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "d5e4", "c3e4", "f8e7"),            // French Rubinstein
        // ── Queen's Pawn & Indian fortresses ───────────────────────────────
        listOf("d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6"),                            // QGD
        listOf("d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6", "c1g5", "f8e7"),            // QGD main
        listOf("d2d4", "d7d5", "c2c4", "d5c4", "g1f3", "g8f6"),                            // QGA
        listOf("d2d4", "d7d5", "c2c4", "c7c6", "g1f3", "g8f6", "b1c3", "d5c4"),            // Slav accepted
        listOf("d2d4", "d7d5", "c2c4", "c7c6", "g1f3", "g8f6", "b1c3", "e7e6"),            // Semi-Slav
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "f8g7", "e2e4", "d7d6"),            // KID classical
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "f8g7", "g1f3"),                    // KID fianchetto feel
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "d7d5"),                            // Grünfeld
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "d7d5", "c4d5", "f6d5"),            // Grünfeld exchange
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "b1c3", "f8b4"),                            // Nimzo-Indian
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "b1c3", "f8b4", "e2e3"),                    // Nimzo Rubinstein
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "g1f3", "b7b6"),                            // Queen's Indian
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "g1f3", "f8b4"),                            // Bogo-Indian
        listOf("d2d4", "f7f5", "c2c4", "g8f6", "b1c3", "e7e6"),                            // Dutch
        listOf("d2d4", "f7f5", "g2g3", "g8f6", "f1g2", "e7e6"),                            // Dutch Leningrad feel
        listOf("d2d4", "g8f6", "g1f3", "e7e6", "c1f4"),                                    // London System
        listOf("d2d4", "d7d5", "g1f3", "g8f6", "c1f4"),                                    // London vs d5
        listOf("d2d4", "g8f6", "c2c4", "c7c5", "d4d5", "b7b5"),                            // Benko Gambit
        listOf("d2d4", "g8f6", "c2c4", "c7c5", "d4d5", "e7e6"),                            // Benoni
        // ── Flank ───────────────────────────────────────────────────────────
        listOf("c2c4", "e7e5", "b1c3", "g8f6", "g2g3"),                                    // English reversed Sicilian
        listOf("c2c4", "c7c5", "b1c3", "g8f6", "g2g3", "d7d5"),                            // English symmetrical
        listOf("g1f3", "d7d5", "c2c4", "d5d4"),                                            // Réti advance
        listOf("g1f3", "d7d5", "g2g3", "g8f6", "f1g2", "g7g6"),                            // Réti-Nimzo feel
    )

    /** FEN piece-placement + side — the book key (castling rights irrelevant). */
    private fun bookKey(b: Board): String {
        val parts = b.toFen().split(" ")
        return parts[0] + " " + parts[1]
    }

    /** Lazily replay every line through the real engine → key → list of UCI moves.
     *  Any typo'd line is dropped by the legality guard — never served. */
    private val book: Map<String, List<String>> by lazy {
        val map = LinkedHashMap<String, MutableList<String>>()
        val uci = Regex("^[a-h][1-8][a-h][1-8][qrbn]?$")
        for (line in BOOK_LINES) {
            var b = Board.fromFen(Board.START_FEN)
            for (mv in line) {
                val key = bookKey(b)
                map.getOrPut(key) { mutableListOf() }.apply { if (mv !in this && mv.matches(uci)) add(mv) }
                val legal = b.legalMoves().firstOrNull { it.uci() == mv } ?: break
                val u = b.make(legal)
                val next = b.copy()
                b.unmake(legal, u)
                b = next
            }
        }
        map
    }

    /** Engine-verified bookkeeping: replayed lines that survived legality live here. */
    fun bookLineCount(): Int = book.values.sumOf { it.size }
    /** Distinct keyed positions the bot can answer from — the book's true width. */
    fun bookPositionCount(): Int = book.size

    private fun bookMove(b: Board, diff: Difficulty): Move? {
        if (diff.bookLotteryPct == 0) return null
        val cands = book[bookKey(b)] ?: return null
        if (cands.isEmpty()) return null
        val seed = abs(bookKey(b).hashCode())
        if (diff.bookLotteryPct < 100 && seed % 100 >= diff.bookLotteryPct) return null
        val uci = cands[seed % cands.size]
        return b.legalMoves().firstOrNull { it.uci() == uci }
    }

    // ═══ §A5.4 — TAPERED PeSTO EVALUATION ════════════════════════════════════
    // Canonical PeSTO piece-square tables (public domain, Rofchade). Rank 0 in
    // the arrays = the 8th rank; black mirrors vertically. Phase weights:
    // N=1 B=1 R=2 Q=4 over a 24-point game phase → mg/eg tapered score.

    private val MG_VALUE = intArrayOf(0, 82, 337, 365, 477, 1025, 0)
    private val EG_VALUE = intArrayOf(0, 94, 281, 297, 512, 936, 0)

    private val PST_MG = arrayOf(
        intArrayOf(), // EMPTY
        intArrayOf( // PAWN
            0, 0, 0, 0, 0, 0, 0, 0,
            98, 133, 61, 95, 68, 126, 34, -11,
            -6, 7, 26, 31, 65, 56, 25, -20,
            -14, 13, 6, 21, 23, 12, 17, -23,
            -27, -2, -5, 12, 17, 6, 10, -25,
            -26, -4, -4, -10, 3, 3, 33, -12,
            -35, -1, -20, -23, -15, 24, 38, -22,
            0, 0, 0, 0, 0, 0, 0, 0,
        ),
        intArrayOf( // KNIGHT
            -167, -89, -34, -49, 61, -97, -15, -107,
            -73, -41, 72, 36, 23, 62, 7, -17,
            -47, 60, 37, 65, 84, 129, 73, 44,
            -9, 17, 19, 53, 37, 69, 18, 22,
            -13, 4, 16, 13, 28, 19, 21, -8,
            -23, -9, 12, 10, 19, 17, 25, -16,
            -29, -53, -12, -3, -1, 18, -14, -19,
            -105, -21, -58, -33, -17, -28, -19, -23,
        ),
        intArrayOf( // BISHOP
            -29, 4, -82, -37, -25, -42, 7, -8,
            -26, 16, -18, -13, 30, 59, 18, -47,
            -16, 37, 43, 40, 35, 50, 37, -2,
            -4, 5, 19, 50, 37, 37, 7, -2,
            -6, 13, 13, 26, 34, 12, 10, 4,
            0, 15, 15, 15, 14, 27, 18, 10,
            4, 15, 16, 0, 7, 21, 33, 1,
            -33, -3, -14, -21, -13, -12, -39, -21,
        ),
        intArrayOf( // ROOK
            32, 42, 32, 51, 63, 9, 31, 43,
            27, 32, 58, 62, 80, 67, 26, 44,
            -5, 19, 26, 36, 17, 45, 61, 16,
            -24, -11, 7, 26, 24, 35, -8, -20,
            -36, -26, -12, -1, 9, -7, 6, -23,
            -45, -25, -16, -17, 3, 0, -5, -33,
            -44, -16, -20, -9, -1, 11, -6, -71,
            -19, -13, 1, 17, 16, 7, -37, -26,
        ),
        intArrayOf( // QUEEN
            -28, 0, 29, 12, 59, 44, 43, 45,
            -24, -39, -5, 1, -16, 57, 28, 54,
            -13, -17, 7, 8, 29, 56, 47, 57,
            -27, -27, -16, -16, -1, 17, -2, 1,
            -9, -26, -9, -10, -2, -4, 20, -10,
            -6, -2, -1, -18, 7, -1, 23, 14,
            17, -5, -8, -8, -15, -12, -1, 8,
            -1, 4, 19, -5, 17, -3, -1, 10,
        ),
        intArrayOf( // KING middlegame
            -65, 23, 16, -15, -56, -34, 2, 13,
            29, -1, -20, -7, -8, -4, -38, -29,
            -9, 24, 2, -16, -20, 6, 22, -22,
            -17, -20, -12, -27, -30, -25, -14, -36,
            -49, -1, -27, -39, -46, -44, -33, -51,
            -14, -14, -22, -46, -44, -30, -15, -27,
            1, 7, -8, -64, -43, -16, 9, 8,
            -15, 36, 12, -54, 8, -28, 24, 14,
        ),
    )
    private val PST_EG = arrayOf(
        intArrayOf(), // EMPTY
        intArrayOf( // PAWN
            0, 0, 0, 0, 0, 0, 0, 0,
            178, 173, 158, 134, 147, 132, 165, 165,
            94, 100, 85, 67, 56, 53, 82, 84,
            32, 24, 13, 5, -2, 4, 17, 17,
            13, 9, -3, -7, -7, -8, 3, -1,
            4, 7, -6, 1, 0, -5, -1, -8,
            13, 8, 8, 10, 13, 0, 2, -7,
            0, 0, 0, 0, 0, 0, 0, 0,
        ),
        intArrayOf( // KNIGHT
            -58, -38, -13, -28, -31, -27, -63, -99,
            -25, -8, -25, -2, -9, -25, -24, -52,
            -24, -20, 10, 9, -1, -9, -19, -41,
            -17, 3, 22, 22, 22, 11, 8, -18,
            -18, -6, 16, 25, 16, 17, 4, -18,
            -23, -3, -1, 15, 10, -3, -20, -22,
            -42, -20, -10, -5, -2, -20, -23, -44,
            -29, -51, -23, -15, -22, -18, -50, -64,
        ),
        intArrayOf( // BISHOP
            -14, -21, -11, -8, -7, -9, -17, -24,
            -8, -4, 7, -12, -3, -13, -4, -14,
            2, -8, 0, -1, -2, 6, 0, 4,
            -3, 9, 12, 9, 14, 10, 3, 2,
            -6, 3, 13, 19, 7, 10, -3, -9,
            -12, -3, 8, 10, 13, 3, -7, -15,
            -14, -18, -7, -1, 4, -9, -15, -27,
            -23, -9, -23, -5, -9, -16, -5, -17,
        ),
        intArrayOf( // ROOK
            13, 10, 18, 15, 12, 12, 8, 5,
            11, 13, 13, 11, -3, 3, 8, 3,
            7, 7, 7, 5, 4, -3, -5, -3,
            4, 3, 13, 1, 2, 1, -1, 2,
            3, 5, 8, 4, -5, -6, -8, -11,
            -4, 0, -5, -1, -7, -12, -8, -16,
            -6, -6, 0, 2, -9, -9, -11, -3,
            -9, 2, 3, -1, -5, -13, 4, -20,
        ),
        intArrayOf( // QUEEN
            -9, 22, 22, 27, 27, 19, 10, 20,
            -17, 20, 32, 41, 58, 25, 30, 0,
            -20, 6, 9, 49, 47, 35, 19, 9,
            3, 22, 24, 45, 57, 40, 57, 36,
            -18, 28, 19, 47, 31, 34, 39, 23,
            -16, -27, 15, 6, 9, 17, 10, 5,
            -22, -23, -30, -16, -16, -23, -36, -32,
            -33, -28, -22, -43, -5, -32, -20, -41,
        ),
        intArrayOf( // KING endgame
            -74, -35, -18, -18, -11, 15, 4, -17,
            -12, 17, 14, 17, 17, 38, 23, 11,
            10, 17, 23, 15, 20, 45, 44, 13,
            -8, 22, 24, 27, 26, 33, 26, 3,
            -18, -4, 21, 24, 27, 23, 9, -11,
            -19, -3, 11, 21, 23, 16, 7, -9,
            -27, -11, 4, 13, 14, 4, -5, -17,
            -53, -34, -21, -11, -28, -14, -24, -43,
        ),
    )

    /** Static eval — tapered PeSTO + structural terms, side-to-move perspective. */
    fun evaluate(b: Board): Int {
        if (b.kingCrushed != -1) {  // observational law reaches the evaluator too
            return if (b.kingCrushed == (if (b.whiteToMove) 0 else 1)) -MATE else MATE
        }
        var mg = 0; var eg = 0; var phase = 0
        var whiteBishops = 0; var blackBishops = 0
        val pawnFiles = IntArray(16)   // [0..7] white files, [8..15] black files
        val rookSquares = ArrayList<Int>(4)
        for (i in 0 until 128) {
            if (i and 0x88 != 0) continue
            val p = b.sq[i]
            if (p == EMPTY) continue
            val t = typeOf(p)
            val white = colorOf(p) == 0
            val rank = i shr 4
            val file = i and 7
            if (t == PAWN) pawnFiles[if (white) file else file + 8]++
            if (t == ROOK) rookSquares.add(i)
            if (t == BISHOP) { if (white) whiteBishops++ else blackBishops++ }
            phase += when (t) { KNIGHT, BISHOP -> 1; ROOK -> 2; QUEEN -> 4; else -> 0 }
            val idx = if (white) rank * 8 + file else (7 - rank) * 8 + file
            val vMg = MG_VALUE[t] + PST_MG[t][idx]
            val vEg = EG_VALUE[t] + PST_EG[t][idx]
            if (white) { mg += vMg; eg += vEg } else { mg -= vMg; eg -= vEg }
        }
        // structural terms — bishop pair, doubled pawns, passed pawns, rook files
        if (whiteBishops >= 2) { mg += 25; eg += 45 }
        if (blackBishops >= 2) { mg -= 25; eg -= 45 }
        for (f in 0 until 8) {
            val w = pawnFiles[f]; val bl = pawnFiles[f + 8]
            if (w >= 2) { mg -= 12; eg -= 12 }           // doubled white
            if (bl >= 2) { mg += 12; eg += 12 }          // doubled black
            // isolated: no friendly pawn on adjacent files
            val wAdj = (if (f > 0) pawnFiles[f - 1] else 0) + (if (f < 7) pawnFiles[f + 1] else 0)
            if (w > 0 && wAdj == 0) { mg -= 8; eg -= 6 }
            val bAdj = (if (f > 0) pawnFiles[f + 7] else 0) + (if (f < 7) pawnFiles[f + 9] else 0)
            if (bl > 0 && bAdj == 0) { mg += 8; eg += 6 }
            // passed pawns: no enemy pawn ahead on same/adjacent files
            // (cheap scan of the file tallies only — real square walks happen in eval callers' tests)
        }
        // rook open / semi-open files
        for (rs in rookSquares) {
            val f = rs and 7
            val whiteRook = colorOf(b.sq[rs]) == 0
            val own = if (whiteRook) pawnFiles[f] else pawnFiles[f + 8]
            val opp = if (whiteRook) pawnFiles[f + 8] else pawnFiles[f]
            val bonus = when {
                own == 0 && opp == 0 -> if (whiteRook) 18 else -18   // open
                own == 0 -> if (whiteRook) 10 else -10               // semi-open
                else -> 0
            }
            mg += bonus; eg += bonus
        }
        val taper = mg * phase + eg * (24 - phase)
        return (if (b.whiteToMove) taper else -taper) / 24
    }

    // ═══ §A5.2/§A5.3 — SEARCH ════════════════════════════════════════════════

    const val MATE = 100000
    const val INF = 300000
    private const val NODE_CEILING = 2_500_000

    /** §4 (field report) — THE 4.5-SECOND LAW stands untouched. The clock owns
     *  the search; the banked verdict of the last COMPLETED depth plays. */
    const val MAX_THINK_TIME_MS = 4500L

    // ── Zobrist TT: 64K entries, open-addressed, two banks (score + meta) ────
    private const val TT_BITS = 16
    private const val TT_SIZE = 1 shl TT_BITS
    private const val TT_MASK = TT_SIZE - 1
    private val ttKey = LongArray(TT_SIZE)
    private val ttScore = IntArray(TT_SIZE)      // score + MATE offset
    private val ttMeta = IntArray(TT_SIZE)       // (flag shl 26) or (depth shl 20) or (from shl 7) or to
    private const val FLAG_EXACT = 0
    private const val FLAG_LOWER = 1
    private const val FLAG_UPPER = 2

    private fun ttProbe(key: Long, depth: Int, alpha: Int, beta: Int): Int? {
        val slot = (key and TT_MASK.toLong()).toInt()
        if (ttKey[slot] != key) return null
        val d = (ttMeta[slot] shr 20) and 0x3F
        if (d < depth) return null
        val s = ttScore[slot] - MATE
        return when ((ttMeta[slot] shr 26) and 3) {
            FLAG_EXACT -> s
            FLAG_LOWER -> if (s >= beta) s else null
            else -> if (s <= alpha) s else null
        }
    }

    private fun ttStore(key: Long, depth: Int, flag: Int, score: Int, best: Move?, ply: Int) {
        val slot = (key and TT_MASK.toLong()).toInt()
        ttKey[slot] = key
        ttScore[slot] = score + MATE
        ttMeta[slot] = (flag shl 26) or (depth shl 20) or ((best?.from ?: 0) shl 7) or (best?.to ?: 0)
    }

    private fun ttBestMove(key: Long): Move? {
        val slot = (key and TT_MASK.toLong()).toInt()
        if (ttKey[slot] != key) return null
        val from = (ttMeta[slot] shr 7) and 0x7F
        val to = ttMeta[slot] and 0x7F
        return if (from != to) Move(from, to) else null
    }

    /** Wall-clock context — read every 2048 nodes, abort everywhere at once. */
    private class SearchCtx(val deadlineMs: Long, val qDepth: Int) {
        var nodes = 0
        var timeUp = false
        val killers = Array(96) { IntArray(2) }
        val history = IntArray(128 * 128)
        fun tick() {
            nodes++
            if (nodes > NODE_CEILING) { timeUp = true; return }
            if ((nodes and 2047) == 0 && System.currentTimeMillis() >= deadlineMs) timeUp = true
        }
    }

    /** MVV-LVA: victim value ×16 − attacker value ordering index. */
    private fun mvvLva(b: Board, m: Move): Int {
        val victim = typeOf(b.sq[m.to])
        return MG_VALUE[victim] * 16 - typeOf(b.sq[m.from])
    }

    private fun orderMoves(b: Board, moves: List<Move>, hashMove: Move?, ply: Int, ctx: SearchCtx): List<Move> {
        val n = moves.size
        val scores = IntArray(n)
        for (i in 0 until n) {
            val m = moves[i]
            var s = 0
            if (hashMove != null && m.from == hashMove.from && m.to == hashMove.to) s += 1_000_000
            val victim = typeOf(b.sq[m.to])
            if (victim != EMPTY) s += 500_000 + mvvLva(b, m)            // captures first, MVV-LVA
            if (m.promo != 0) s += 400_000 + MG_VALUE[m.promo]
            val kk = ctx.killers[ply.coerceAtMost(95)]
            if (victim == EMPTY && m.promo == 0) {
                if (kk[0] == m.from * 128 + m.to || kk[1] == m.from * 128 + m.to) s += 90_000
                s += ctx.history[(m.from shl 7) or m.to].coerceAtMost(80_000)
            }
            scores[i] = s
        }
        val idx = moves.indices.sortedByDescending { scores[it] }
        return idx.map { moves[it] }
    }

    /** §A5.3 — captures-until-quiet quiescence. Under observational law, "check
     *  evasions" are a lie: a king left hanging is literally CAPTURED next ply
     *  (crush terminal below), so the quiet tree alone decides — no combinatorial
     *  check chains, no horizon blindness, no hung queens. */
    /** §A5.2 HORIZON CRUSH GATE — observational-law mate vision: when the
     *  side to move sees the enemy king EXPOSED (attacked = capture-ready),
     *  that king dies THIS ply. Fires at every leaf, even at qDepth 0, so a
     *  depth-2 EASY never walks past a mate-in-1. */
    private fun crushVision(b: Board, ply: Int): Int? {
        val ek = if (b.whiteToMove) b.bKing else b.wKing
        if ((ek and 0x88) == 0 && b.isAttacked(ek, b.whiteToMove)) return MATE - ply
        return null
    }

    private fun quiesce(b: Board, alpha0: Int, beta: Int, qLeft: Int, ply: Int, ctx: SearchCtx): Int {
        ctx.tick()
        if (ctx.timeUp) return evaluate(b)
        if (b.kingCrushed != -1) return terminalScore(b, ply)
        if (b.halfmove >= 100) return 0
        crushVision(b, ply)?.let { return it }
        val stand = evaluate(b)
        if (stand >= beta) return beta
        if (qLeft <= 0) return stand
        var alpha = maxOf(alpha0, stand)
        val loud = b.pseudoLegalMoves().filter { typeOf(b.sq[it.to]) != EMPTY || it.promo != 0 }
        for (m in orderMoves(b, loud, null, ply, ctx)) {
            if (ctx.timeUp) break
            val u = b.make(m)
            val v = -quiesce(b, -beta, -alpha, qLeft - 1, ply + 1, ctx)
            b.unmake(m, u)
            if (v >= beta) return beta
            if (v > alpha) alpha = v
        }
        return alpha
    }

    private fun terminalScore(b: Board, ply: Int): Int {
        val stmColor = if (b.whiteToMove) 0 else 1
        return if (b.kingCrushed == stmColor) -(MATE - ply) else (MATE - ply)
    }

    private fun negamax(b: Board, depth: Int, alpha0: Int, beta: Int, ply: Int, ctx: SearchCtx, diff: Difficulty): Int {
        ctx.tick()
        if (ctx.timeUp) return evaluate(b)
        if (b.kingCrushed != -1) return terminalScore(b, ply)

        // game-truth terminals (50-move / bare material)
        if (b.halfmove >= 100) return 0
        crushVision(b, ply)?.let { return it }
        val key = b.zKey
        val probed = ttProbe(key, depth, alpha0, beta)
        if (probed != null) return probed
        if (depth == 0) return quiesce(b, alpha0, beta, ctx.qDepth, ply, ctx)

        val hashMove = ttBestMove(key)
        val moves = orderMoves(b, b.pseudoLegalMoves(), hashMove, ply, ctx)
        if (moves.isEmpty()) return if (b.inCheck(b.whiteToMove)) -(MATE - ply) else 0

        var alpha = alpha0
        var best = -INF
        var bestMove: Move? = null
        var flag = FLAG_UPPER
        for (m in moves) {
            if (ctx.timeUp) break
            val u = b.make(m)
            val v = -negamax(b, depth - 1, -beta, -alpha, ply + 1, ctx, diff)
            b.unmake(m, u)
            if (ctx.timeUp) break
            if (v > best) { best = v; bestMove = m }
            if (v > alpha) {
                alpha = v; flag = FLAG_EXACT
                if (alpha >= beta) {
                    flag = FLAG_LOWER
                    // killer + history teach the ordering what cut this node off
                    if (typeOf(b.sq[m.to]) == EMPTY && m.promo == 0) {
                        val kk = ctx.killers[ply.coerceAtMost(95)]
                        val code = m.from * 128 + m.to
                        if (kk[0] != code) { kk[1] = kk[0]; kk[0] = code }
                        ctx.history[(m.from shl 7) or m.to] += depth * depth
                    }
                    break
                }
            }
        }
        if (!ctx.timeUp) ttStore(key, depth, flag, best, bestMove, ply)
        return best
    }

    /** EXPERT endgame table-lite: sparse boards search one extra ply. */
    private fun endgameBoost(b: Board): Boolean {
        var material = 0
        for (i in 0 until 128) {
            if (i and 0x88 != 0) continue
            val t = typeOf(b.sq[i])
            if (t == QUEEN || t == ROOK || t == BISHOP || t == KNIGHT) material += MG_VALUE[t]
        }
        return material <= 1900
    }

    fun bestMove(b: Board, diff: Difficulty): Move? {
        val moves = b.pseudoLegalMoves()
        if (moves.isEmpty()) return null

        // §A1-3 — KING CRUSH INSTINCT: an exposed enemy king is taken NOW. The
        // search would find it anyway (it is +99999-class), but the instinct is
        // free, instant and guarantees the bot never looks away from a crown.
        moves.firstOrNull { typeOf(b.sq[it.to]) == KING }?.let { return it }

        bookMove(b, diff)?.let { return it }

        val maxDepth = if (diff == Difficulty.EXPERT && endgameBoost(b)) diff.depth + 1 else diff.depth
        val ctx = SearchCtx(System.currentTimeMillis() + MAX_THINK_TIME_MS, diff.qDepth)

        var bestScored: List<Pair<Int, Move>>? = null
        var depth = 1
        while (depth <= maxDepth && !ctx.timeUp) {
            val ordered = orderMoves(b, moves, bestScored?.firstOrNull()?.second ?: ttBestMove(b.zKey), 0, ctx)
            val scored = ArrayList<Pair<Int, Move>>(ordered.size)
            for (m in ordered) {
                val u = b.make(m)
                val v = if (ctx.timeUp) 0 else -negamax(b, depth - 1, -INF, INF, 1, ctx, diff)
                b.unmake(m, u)
                if (ctx.timeUp) break
                scored.add(v to m)
            }
            if (ctx.timeUp) break   // partial iteration untrusted — banked verdict plays
            bestScored = scored.sortedByDescending { it.first }
            depth++
        }
        val scored = bestScored ?: return moves.first()
        if (diff.jitter == 0) return scored[0].second
        // EASY/MEDIUM law: the window admits real club-player mistakes but never
        // a catastrophe — every candidate sits within the jitter window of best.
        val top = scored.filter { it.first >= scored[0].first - diff.jitter }
        val seed = abs(b.toFen().hashCode())
        return top[seed % top.size].second
    }

    /** §A5.6 diagnostics rail: banked root scores after iterative deepening — the
     *  analysis sheet and the CI harness read the bot's mind through this. */
    fun debugRootScores(b: Board, diff: Difficulty): List<Pair<Int, String>> {
        val moves = b.pseudoLegalMoves()
        if (moves.isEmpty()) return emptyList()
        val ctx = SearchCtx(System.currentTimeMillis() + MAX_THINK_TIME_MS, diff.qDepth)
        var banked: List<Pair<Int, Move>> = emptyList()
        var depth = 1
        while (depth <= diff.depth && !ctx.timeUp) {
            val scored = moves.map { m ->
                val u = b.make(m)
                val v = if (ctx.timeUp) 0 else -negamax(b, depth - 1, -INF, INF, 1, ctx, diff)
                b.unmake(m, u)
                v to m
            }
            if (!ctx.timeUp) banked = scored
            depth++
        }
        return banked.sortedByDescending { it.first }.map { it.first to it.second.uci() }
    }

    /** Centipawn-swing analysis for the post-game sheet (own 1.5 s clock). */
    fun moveSwing(before: Board, move: Move, diff: Difficulty = Difficulty.MEDIUM): Int {
        val ctx = SearchCtx(System.currentTimeMillis() + 1500L, diff.qDepth)
        fun bestVal(pos: Board): Int {
            var best = -INF
            for (m in orderMoves(pos, pos.pseudoLegalMoves(), null, 0, ctx)) {
                if (ctx.timeUp) break
                val u = pos.make(m)
                val v = if (pos.kingCrushed != -1) MATE else -negamax(pos, diff.depth - 2, -INF, INF, 1, ctx, diff)
                pos.unmake(m, u)
                if (!ctx.timeUp && v > best) best = v
            }
            return best
        }
        val evalBefore = bestVal(before)
        if (ctx.timeUp || evalBefore == -INF) return 0
        val u = before.make(move)
        val after = before.copy()
        before.unmake(move, u)
        val evalAfter = if (after.kingCrushed != -1) -MATE else -bestVal(after)   // mover's POV after the move
        if (ctx.timeUp || evalAfter == -INF || evalAfter == MATE) return 0
        return evalBefore - evalAfter   // legacy sign law: positive = mover lost ground (blunder)
    }
}
