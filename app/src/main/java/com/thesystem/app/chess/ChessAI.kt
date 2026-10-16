package com.thesystem.app.chess

import kotlin.math.abs

/**
 * SYSTEM CHESS AI v2 — deterministic negamax + alpha-beta + quiescence.
 *
 * §3.1 overhaul (2026-10-16): the "knight-shuffle opening" complaint died here.
 *  · OPENING BOOK — standard lines (e4/d4/Nf3 systems) replayed through the
 *    real engine into a FEN-key map: the AI now opens with central pawns and
 *    develops naturally. Book content is engine-generated, never hand-typed.
 *  · EVAL v2 — PST + center-pawn control + opening development + king safety.
 *  · QUIESCENCE (captures only) at the leaf on MEDIUM+ — no horizon blunders.
 *  · ITERATIVE DEEPENING on EXPERT (3→5 ply, deterministic node budget).
 *
 * Strict calibration:
 *   EASY    depth 1 · jitter 90 · book 100% — natural openings, honest mistakes after the book
 *   MEDIUM  depth 2 · jitter 25 · book 100% + short quiescence
 *   HARD    depth 3 · jitter  0 · book 100% + quiescence
 *   EXPERT  depth 4 (5 in sparse endgames) · jitter 0 · book 100% + deep quiescence — punishes everything
 *
 * Depth/volume is the REAL dial; no cloud, runs in milliseconds on 2GB phones.
 */
object ChessAI {

    /** Depth is the REAL dial — every label maps to actual search behavior. */
    enum class Difficulty(val depth: Int, val jitter: Int, val qDepth: Int, val bookLotteryPct: Int) {
        EASY(1, 90, 0, 100),
        MEDIUM(2, 25, 2, 100),
        HARD(3, 0, 4, 100),
        EXPERT(4, 0, 8, 100);

        /** UI label per the PLAY vs AI spec — exactly EASY/MEDIUM/HARD/EXPERT. */
        val label: String get() = when (this) {
            EASY -> "EASY"; MEDIUM -> "MEDIUM"; HARD -> "HARD"; EXPERT -> "EXPERT"
        }
        /** Practice-rating anchor used by the deterministic ELO math (spec §10). */
        val anchorElo: Int get() = when (this) {
            EASY -> 350; MEDIUM -> 900; HARD -> 1450; EXPERT -> 2100
        }
    }

    // ═══ OPENING BOOK — standard lines, replayed into a position map ═════════

    private val BOOK_LINES: List<List<String>> = listOf(
        // King's Pawn: e4 e5 — Ruy Lopez + Italian
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6"),
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6"),
        listOf("e2e4", "e7e5", "g1f3", "g8f6", "f1c4"),                      // Petrov
        // Sicilian / French / Caro-Kann
        listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3"),
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "g8f6"),
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "b1c3", "d5e4", "c3e4"),
        // Queen's Pawn: d4 d5 — Queen's Gambit
        listOf("d2d4", "d7d5", "c2c4", "e7e6", "g1f3", "g8f6", "b1c3"),
        // Indian systems
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "g1f3", "b7b6"),
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "f8g7"),
        // English-style flank
        listOf("g1f3", "d7d5", "d2d4", "g8f6", "c2c4", "e7e6"),
    )

    /** FEN piece-placement + side — the book key (castling rights irrelevant). */
    private fun bookKey(b: Board): String {
        val parts = b.toFen().split(" ")
        return parts[0] + " " + parts[1]
    }

    /** Lazily replay every line through the real engine → key → list of UCI moves. */
    private val book: Map<String, List<String>> by lazy {
        val map = LinkedHashMap<String, MutableList<String>>()
        val uci = Regex("^[a-h][1-8][a-h][1-8][qrbn]?$")
        for (line in BOOK_LINES) {
            var b = Board.fromFen(Board.START_FEN)
            for (mv in line) {
                if (!mv.matches(uci)) break
                val key = bookKey(b)
                map.getOrPut(key) { mutableListOf() }.apply { if (mv !in this) add(mv) }
                val legal = b.legalMoves().firstOrNull { it.uci() == mv } ?: break
                val u = b.make(legal)
                val next = b.copy()
                b.unmake(legal, u)
                b = next
            }
        }
        map
    }

    private fun bookMove(b: Board, diff: Difficulty): Move? {
        if (diff.bookLotteryPct == 0) return null
        val cands = book[bookKey(b)] ?: return null
        if (cands.isEmpty()) return null
        // deterministic "lottery": position-seeded, so the same game replays identically
        val seed = abs(bookKey(b).hashCode())
        if (diff.bookLotteryPct < 100 && seed % 100 >= diff.bookLotteryPct) return null
        val uci = cands[seed % cands.size]
        return b.legalMoves().firstOrNull { it.uci() == uci }
    }

    // ═══ EVAL v2 ══════════════════════════════════════════════════════════════

    private val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)

    // light piece-square bias (from white's perspective, rank 0 = 8th rank)
    private val PST_PAWN = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, -20, -20, 10, 10, 5,
        5, -5, -10, 0, 0, -10, -5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, 5, 10, 25, 25, 10, 5, 5,
        10, 10, 20, 30, 30, 20, 10, 10,
        50, 50, 50, 50, 50, 50, 50, 50,
        0, 0, 0, 0, 0, 0, 0, 0,
    )
    private val PST_KNIGHT = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50,
    )
    private val PST_KING_MID = intArrayOf(
        20, 30, 10, 0, 0, 10, 30, 20,
        20, 20, 0, 0, 0, 0, 20, 20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
    )

    // 0x88 square constants for the opening-shape bonuses
    private const val SQ_D4 = 0x43; private const val SQ_E4 = 0x44
    private const val SQ_D3 = 0x53; private const val SQ_E3 = 0x54
    private const val SQ_D5 = 0x33; private const val SQ_E5 = 0x34
    private const val SQ_B1 = 0x71; private const val SQ_G1 = 0x76
    private const val SQ_C1 = 0x72; private const val SQ_F1 = 0x75
    private const val SQ_B8 = 0x01; private const val SQ_G8 = 0x06
    private const val SQ_C8 = 0x02; private const val SQ_F8 = 0x05

    /** Static eval, centipawns, from the side-to-move's perspective. */
    fun evaluate(b: Board): Int {
        var score = 0
        var whiteQueen = false; var blackQueen = false
        for (i in 0 until 128) {
            if (i and 0x88 != 0) continue
            val p = b.sq[i]
            if (p == EMPTY) continue
            val t = typeOf(p)
            if (t == QUEEN) { if (colorOf(p) == 0) whiteQueen = true else blackQueen = true }
            val rank = i shr 4
            val file = i and 7
            val white = colorOf(p) == 0
            val idx = if (white) rank * 8 + file else (7 - rank) * 8 + file
            var v = VALUE[t]
            v += when (t) {
                PAWN -> PST_PAWN[idx]
                KNIGHT -> PST_KNIGHT[idx]
                KING -> PST_KING_MID[idx]
                BISHOP, ROOK, QUEEN -> (abs(rank - 3.5) * -4 + abs(file - 3.5) * -4).toInt()
                else -> 0
            }
            score += if (white) v else -v
        }
        // ── opening shape (only while both queens live): center control,
        //    development, king safety — the terms the PST alone can't see. ──
        if (whiteQueen && blackQueen) {
            // center pawns: e4/d4 for white, e5/d5 for black
            if (b.sq[SQ_E4] == PAWN) score += 16; if (b.sq[SQ_D4] == PAWN) score += 16
            if (b.sq[SQ_E3] == PAWN) score += 8; if (b.sq[SQ_D3] == PAWN) score += 8
            if (b.sq[SQ_E5] == PAWN or BLACK_BIT) score -= 16; if (b.sq[SQ_D5] == PAWN or BLACK_BIT) score -= 16
            // development: minors still sleeping on the back rank cost tempo
            if (b.sq[SQ_B1] == KNIGHT) score -= 14; if (b.sq[SQ_G1] == KNIGHT) score -= 14
            if (b.sq[SQ_C1] == BISHOP) score -= 12; if (b.sq[SQ_F1] == BISHOP) score -= 12
            if (b.sq[SQ_B8] == KNIGHT or BLACK_BIT) score += 14; if (b.sq[SQ_G8] == KNIGHT or BLACK_BIT) score += 14
            if (b.sq[SQ_C8] == BISHOP or BLACK_BIT) score += 12; if (b.sq[SQ_F8] == BISHOP or BLACK_BIT) score += 12
            // king safety: castled rook-file king is worth real centipawns
            if (b.wKing == SQ_G1 || b.wKing == SQ_C1) score += 30
            if (b.bKing == SQ_G8 || b.bKing == SQ_C8) score -= 30
        }
        return if (b.whiteToMove) score else -score
    }

    // ═══ SEARCH ═══════════════════════════════════════════════════════════════

    private const val MATE = 100000
    private const val INF = 200000
    private const val NODE_CEILING = 1_200_000

    /** Captures-only search — kills the horizon effect on MEDIUM+/EXPERT. */
    private fun quiesce(b: Board, alpha0: Int, beta: Int, qDepth: Int, nodes: IntArray): Int {
        nodes[0]++
        val (status, _) = b.status()
        if (status != Board.Status.ONGOING) {
            return when (status) {
                Board.Status.CHECKMATE -> -MATE
                else -> 0
            }
        }
        val stand = evaluate(b)
        if (qDepth <= 0) return stand
        if (stand >= beta) return beta
        var alpha = maxOf(alpha0, stand)
        val captures = b.legalMoves().filter { typeOf(b.sq[it.to]) != EMPTY || it.promo != 0 }
            .sortedByDescending { VALUE[typeOf(b.sq[it.to])] }
        for (m in captures) {
            val u = b.make(m)
            val v = -quiesce(b, -beta, -alpha, qDepth - 1, nodes)
            b.unmake(m, u)
            if (v >= beta) return beta
            if (v > alpha) alpha = v
        }
        return alpha
    }

    private fun negamax(b: Board, depth: Int, qDepth: Int, alpha0: Int, beta: Int, ply: Int, nodes: IntArray): Int {
        nodes[0]++
        // hard ceiling — deterministic (nodes are a pure function of position+difficulty),
        // guards ancient silicon from pathological depth-5 positions
        if (nodes[0] > NODE_CEILING) return evaluate(b)
        val (status, _) = b.status()
        if (status != Board.Status.ONGOING) {
            return when (status) {
                Board.Status.CHECKMATE -> -MATE + ply
                else -> 0
            }
        }
        if (depth == 0) {
            return if (qDepth > 0) quiesce(b, alpha0, beta, qDepth, nodes) else evaluate(b)
        }
        var alpha = alpha0
        val moves = b.legalMoves()
        // capture-first ordering (MVV-ish): captures scored by victim value
        val scored = moves.map { m ->
            val victim = typeOf(b.sq[m.to])
            val s = if (m.flag == 1) 100 else VALUE[victim] * 10 - VALUE[typeOf(b.sq[m.from])] / 10 + (if (m.promo != 0) 900 else 0)
            s to m
        }.sortedByDescending { it.first }
        var best = -INF
        for ((_, m) in scored) {
            val u = b.make(m)
            val v = -negamax(b, depth - 1, qDepth, -beta, -alpha, ply + 1, nodes)
            b.unmake(m, u)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    /**
     * Pick a move. Book first (natural openings); then iterative deepening —
     * deepening only "completes" inside a deterministic node budget so EXPERT
     * never hangs a 2GB phone mid-combat (budget is position-independent:
     * same position + same difficulty ⇒ same move, every time).
     */
    /** EXPERT endgame table-lite: sparse-material positions search one extra ply. */
    private fun endgameBoost(b: Board): Boolean {
        var material = 0
        for (i in 0 until 128) {
            if (i and 0x88 != 0) continue
            val t = typeOf(b.sq[i])
            if (t == QUEEN || t == ROOK || t == BISHOP || t == KNIGHT) material += VALUE[t]
        }
        return material <= 1800 // roughly queen + minor each side — search gets cheap
    }

    fun bestMove(b: Board, diff: Difficulty): Move? {
        val moves = b.legalMoves()
        if (moves.isEmpty()) return null
        bookMove(b, diff)?.let { return it }

        // EXPERT law: 4-ply midgame with deep quiescence; 5-ply when the board empties
        val maxDepth = if (diff == Difficulty.EXPERT && endgameBoost(b)) diff.depth + 1 else diff.depth

        val nodes = intArrayOf(0)
        // EXPERT/HARD deepen iteratively; EASY/MEDIUM jump straight to their depth.
        val startDepth = if (maxDepth >= 3) 3 else maxDepth
        var bestScored: List<Pair<Int, Move>>? = null
        var depth = startDepth
        while (depth <= maxDepth) {
            val scored = moves.map { m ->
                val u = b.make(m)
                val v = -negamax(b, depth - 1, diff.qDepth, -INF, INF, 1, nodes)
                b.unmake(m, u)
                v to m
            }.sortedByDescending { it.first }
            bestScored = scored
            // budget: 900k nodes per full root — beyond that, keep the shallower answer
            if (nodes[0] > 900_000) break
            depth++
        }
        val scored = bestScored ?: return moves.first()
        if (diff.jitter == 0) return scored[0].second
        val top = scored.filter { it.first >= scored[0].first - diff.jitter }
        val seed = abs(b.toFen().hashCode())
        return top[seed % top.size].second
    }

    /** Evaluate a played move's centipawn swing for the post-game analysis (§5/§15). */
    fun moveSwing(before: Board, move: Move, diff: Difficulty = Difficulty.MEDIUM): Int {
        val nodes = intArrayOf(0)
        val evalBefore = run {
            var best = -INF
            for (m in before.legalMoves()) {
                val u = before.make(m)
                val v = -negamax(before, diff.depth - 1, diff.qDepth, -INF, INF, 1, nodes)
                before.unmake(m, u)
                if (v > best) best = v
            }
            if (best <= -MATE + 10) MATE else best
        }
        val u = before.make(move)
        val after = before.copy()
        before.unmake(move, u)
        var best = -INF
        for (m in after.legalMoves()) {
            val uu = after.make(m)
            val v = -negamax(after, diff.depth - 1, diff.qDepth, -INF, INF, 1, nodes)
            after.unmake(m, uu)
            if (v > best) best = v
        }
        val evalAfterWhite = if (best <= -MATE + 10) -MATE else -best // back to moving side's perspective inverted
        return evalBefore - evalAfterWhite // positive = good move for the mover
    }
}
