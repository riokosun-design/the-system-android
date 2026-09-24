package com.thesystem.app.chess

import kotlin.math.abs

/**
 * SYSTEM CHESS AI — deterministic negamax + alpha-beta. Tiny by design:
 * material + piece-square tables + capture ordering. Depth is the DIFFICULTY
 * dial (§20: no heavy models on 2GB devices, this runs in milliseconds).
 */
object ChessAI {

    enum class Difficulty(val depth: Int, val jitter: Int) {
        EASY(1, 60), MEDIUM(2, 20), HARD(3, 0),
    }

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

    /** Static eval, centipawns, from the side-to-move's perspective. */
    fun evaluate(b: Board): Int {
        var score = 0
        for (i in 0 until 128) {
            if (i and 0x88 != 0) continue
            val p = b.sq[i]
            if (p == EMPTY) continue
            val t = typeOf(p)
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
        return if (b.whiteToMove) score else -score
    }

    private const val MATE = 100000
    private const val INF = 200000

    private fun negamax(b: Board, depth: Int, alpha0: Int, beta: Int, ply: Int, nodes: IntArray): Int {
        nodes[0]++
        val (status, _) = b.status()
        if (status != Board.Status.ONGOING) {
            return when (status) {
                Board.Status.CHECKMATE -> -MATE + ply
                else -> 0
            }
        }
        if (depth == 0) return evaluate(b)
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
            val v = -negamax(b, depth - 1, -beta, -alpha, ply + 1, nodes)
            b.unmake(m, u)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    /**
     * Pick a move. EASY/MEDIUM add deterministic jitter (seeded by position hash)
     * among near-equal candidates so training games don't feel robotic.
     */
    fun bestMove(b: Board, diff: Difficulty): Move? {
        val moves = b.legalMoves()
        if (moves.isEmpty()) return null
        val nodes = intArrayOf(0)
        val scored = moves.map { m ->
            val u = b.make(m)
            val v = -negamax(b, diff.depth - 1, -INF, INF, 1, nodes)
            b.unmake(m, u)
            v to m
        }.sortedByDescending { it.first }
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
                val v = -negamax(before, diff.depth - 1, -INF, INF, 1, nodes)
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
            val v = -negamax(after, diff.depth - 1, -INF, INF, 1, nodes)
            after.unmake(m, uu)
            if (v > best) best = v
        }
        val evalAfterWhite = if (best <= -MATE + 10) -MATE else -best // back to moving side's perspective inverted
        return evalBefore - evalAfterWhite // positive = good move for the mover
    }
}
