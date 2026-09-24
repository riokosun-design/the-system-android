package com.thesystem.app.chess

/**
 * SYSTEM CHESS ENGINE — pure Kotlin, zero dependencies, mailbox-0x88.
 * Lightweight by design (2GB devices): arrays + ints, no allocations in the hot
 * path beyond move lists. Deterministic rules own legality; AI only recommends.
 */

const val EMPTY = 0
const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6
const val BLACK_BIT = 8

fun colorOf(p: Int): Int = if (p == EMPTY) -1 else if (p and BLACK_BIT != 0) 1 else 0  // 0=W 1=B
fun typeOf(p: Int): Int = p and 7

/** Move: promo = promotion piece type (KNIGHT..QUEEN) or 0; flag: 1=EP, 2=castle, 3=double push. */
data class Move(val from: Int, val to: Int, val promo: Int = 0, val flag: Int = 0) {
    fun uci(): String = sqName(from) + sqName(to) + when (promo) {
        KNIGHT -> "n"; BISHOP -> "b"; ROOK -> "r"; QUEEN -> "q"; else -> ""
    }
}

fun sqName(sq: Int): String = "${'a' + (sq and 7)}${8 - (sq shr 4)}"
fun sqFromName(name: String): Int = (8 - (name[1] - '0')) shl 4 or (name[0] - 'a')

class Board(
    val sq: IntArray = IntArray(128),
    var whiteToMove: Boolean = true,
    var castling: Int = 0,          // 1=K 2=Q 4=k 8=q
    var ep: Int = -1,               // 0x88 square behind the double-pushed pawn
    var halfmove: Int = 0,
    var fullmove: Int = 1,
    var wKing: Int = -1,
    var bKing: Int = -1,
) {
    fun copy() = Board(sq.copyOf(), whiteToMove, castling, ep, halfmove, fullmove, wKing, bKing)

    companion object {
        val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun fromFen(fen: String): Board {
            val b = Board()
            val parts = fen.trim().split(Regex("\\s+"))
            val rows = parts[0].split("/")
            require(rows.size == 8) { "bad fen" }
            for (r in 0 until 8) {
                var f = 0
                for (c in rows[r]) {
                    if (c.isDigit()) { f += c - '0' } else {
                        val type = when (c.lowercaseChar()) {
                            'p' -> PAWN; 'n' -> KNIGHT; 'b' -> BISHOP
                            'r' -> ROOK; 'q' -> QUEEN; 'k' -> KING
                            else -> throw IllegalArgumentException("bad fen piece $c")
                        }
                        val piece = type or (if (c.isLowerCase()) BLACK_BIT else 0)
                        b.sq[(r shl 4) or f] = piece
                        if (type == KING) {
                            if (c.isUpperCase()) b.wKing = (r shl 4) or f else b.bKing = (r shl 4) or f
                        }
                        f++
                    }
                }
            }
            b.whiteToMove = parts.getOrNull(1) != "b"
            b.castling = 0
            parts.getOrNull(2)?.forEach { c ->
                when (c) { 'K' -> b.castling = b.castling or 1; 'Q' -> b.castling = b.castling or 2
                           'k' -> b.castling = b.castling or 4; 'q' -> b.castling = b.castling or 8 }
            }
            b.ep = parts.getOrNull(3)?.let { if (it == "-") -1 else sqFromName(it) } ?: -1
            b.halfmove = parts.getOrNull(4)?.toIntOrNull() ?: 0
            b.fullmove = parts.getOrNull(5)?.toIntOrNull() ?: 1
            return b
        }
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 0 until 8) {
            var empty = 0
            for (f in 0 until 8) {
                val p = sq[(r shl 4) or f]
                if (p == EMPTY) { empty++; continue }
                if (empty > 0) { sb.append(empty); empty = 0 }
                val c = when (typeOf(p)) { PAWN -> "p"; KNIGHT -> "n"; BISHOP -> "b"; ROOK -> "r"; QUEEN -> "q"; else -> "k" }
                sb.append(if (colorOf(p) == 0) c.uppercase() else c)
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        var rights = ""
        if (castling and 1 != 0) rights += "K"
        if (castling and 2 != 0) rights += "Q"
        if (castling and 4 != 0) rights += "k"
        if (castling and 8 != 0) rights += "q"
        sb.append(rights.ifEmpty { "-" })
        sb.append(' ').append(if (ep >= 0) sqName(ep) else "-")
        sb.append(' ').append(halfmove).append(' ').append(fullmove)
        return sb.toString()
    }

    // ── attack detection ────────────────────────────────────────────────────
    fun isAttacked(target: Int, byWhite: Boolean): Boolean {
        // pawns
        val pawnDir = if (byWhite) 16 else -16
        for (df in intArrayOf(-1, 1)) {
            val s = target + pawnDir + df
            if (s and 0x88 == 0 && sq[s] == (PAWN or (if (byWhite) 0 else BLACK_BIT))) return true
        }
        // knights
        val knight = KNIGHT or (if (byWhite) 0 else BLACK_BIT)
        for (d in intArrayOf(14, 18, 31, 33, -14, -18, -31, -33)) {
            val s = target + d
            if (s and 0x88 == 0 && sq[s] == knight) return true
        }
        // king
        val king = KING or (if (byWhite) 0 else BLACK_BIT)
        for (d in intArrayOf(1, 15, 16, 17, -1, -15, -16, -17)) {
            val s = target + d
            if (s and 0x88 == 0 && sq[s] == king) return true
        }
        // sliders
        val enemyBishop = BISHOP or (if (byWhite) 0 else BLACK_BIT)
        val enemyRook = ROOK or (if (byWhite) 0 else BLACK_BIT)
        val enemyQueen = QUEEN or (if (byWhite) 0 else BLACK_BIT)
        for (d in intArrayOf(15, 17, -15, -17)) {
            var s = target + d
            while (s and 0x88 == 0) {
                val p = sq[s]
                if (p != EMPTY) { if (p == enemyBishop || p == enemyQueen) return true; break }
                s += d
            }
        }
        for (d in intArrayOf(1, 16, -1, -16)) {
            var s = target + d
            while (s and 0x88 == 0) {
                val p = sq[s]
                if (p != EMPTY) { if (p == enemyRook || p == enemyQueen) return true; break }
                s += d
            }
        }
        return false
    }

    fun inCheck(white: Boolean): Boolean = isAttacked(if (white) wKing else bKing, !white)

    // ── pseudo-legal generation ──────────────────────────────────────────────
    private fun genPseudo(moves: MutableList<Move>) {
        val mine = if (whiteToMove) 0 else BLACK_BIT
        val theirs = if (whiteToMove) BLACK_BIT else 0
        for (i in 0 until 128) {
            if (i and 0x88 != 0) continue
            val p = sq[i]
            if (p == EMPTY || colorOf(p) != (if (whiteToMove) 0 else 1)) continue
            when (typeOf(p)) {
                PAWN -> {
                    val dir = if (whiteToMove) -16 else 16
                    val startRank = if (whiteToMove) 6 else 1
                    val promoRank = if (whiteToMove) 0 else 7
                    val one = i + dir
                    if (one and 0x88 == 0 && sq[one] == EMPTY) {
                        if ((one shr 4) == promoRank) {
                            for (pr in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) moves.add(Move(i, one, pr))
                        } else {
                            moves.add(Move(i, one))
                            val two = i + dir * 2
                            if ((i shr 4) == startRank && sq[two] == EMPTY) moves.add(Move(i, two, 0, 3))
                        }
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val t = i + dir + df
                        if (t and 0x88 != 0) continue
                        if (sq[t] != EMPTY && colorOf(sq[t]) == colorOf(theirs or 1)) {
                            if ((t shr 4) == promoRank) for (pr in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) moves.add(Move(i, t, pr))
                            else moves.add(Move(i, t))
                        }
                        if (t == ep) moves.add(Move(i, t, 0, 1))
                    }
                }
                KNIGHT -> for (d in intArrayOf(14, 18, 31, 33, -14, -18, -31, -33)) {
                    val t = i + d
                    if (t and 0x88 != 0) continue
                    if (sq[t] == EMPTY || colorOf(sq[t]) == colorOf(theirs or 1)) moves.add(Move(i, t))
                }
                BISHOP, ROOK, QUEEN -> {
                    val dirs = when (typeOf(p)) {
                        BISHOP -> intArrayOf(15, 17, -15, -17)
                        ROOK -> intArrayOf(1, 16, -1, -16)
                        else -> intArrayOf(1, 15, 16, 17, -1, -15, -16, -17)
                    }
                    for (d in dirs) {
                        var t = i + d
                        while (t and 0x88 == 0) {
                            if (sq[t] == EMPTY) moves.add(Move(i, t))
                            else { if (colorOf(sq[t]) == colorOf(theirs or 1)) moves.add(Move(i, t)); break }
                            t += d
                        }
                    }
                }
                KING -> {
                    for (d in intArrayOf(1, 15, 16, 17, -1, -15, -16, -17)) {
                        val t = i + d
                        if (t and 0x88 != 0) continue
                        if (sq[t] == EMPTY || colorOf(sq[t]) == colorOf(theirs or 1)) moves.add(Move(i, t))
                    }
                    // castling
                    if (whiteToMove && i == 0x74 && !isAttacked(0x74, false)) {
                        if (castling and 1 != 0 && sq[0x75] == EMPTY && sq[0x76] == EMPTY
                            && sq[0x77] == (ROOK or mine) && !isAttacked(0x75, false) && !isAttacked(0x76, false))
                            moves.add(Move(i, 0x76, 0, 2))
                        if (castling and 2 != 0 && sq[0x73] == EMPTY && sq[0x72] == EMPTY && sq[0x71] == EMPTY
                            && sq[0x70] == (ROOK or mine) && !isAttacked(0x73, false) && !isAttacked(0x72, false))
                            moves.add(Move(i, 0x72, 0, 2))
                    }
                    if (!whiteToMove && i == 0x04 && !isAttacked(0x04, true)) {
                        if (castling and 4 != 0 && sq[0x05] == EMPTY && sq[0x06] == EMPTY
                            && sq[0x07] == (ROOK or mine) && !isAttacked(0x05, true) && !isAttacked(0x06, true))
                            moves.add(Move(i, 0x06, 0, 2))
                        if (castling and 8 != 0 && sq[0x03] == EMPTY && sq[0x02] == EMPTY && sq[0x01] == EMPTY
                            && sq[0x00] == (ROOK or mine) && !isAttacked(0x03, true) && !isAttacked(0x02, true))
                            moves.add(Move(i, 0x02, 0, 2))
                    }
                }
            }
        }
    }

    data class Undo(val captured: Int, val castling: Int, val ep: Int, val half: Int, var epPawnSq: Int)

    fun make(m: Move): Undo {
        val undo = Undo(sq[m.to], castling, ep, halfmove, -1)
        val p = sq[m.from]
        halfmove = if (typeOf(p) == PAWN || sq[m.to] != EMPTY) 0 else halfmove + 1
        sq[m.to] = if (m.promo != 0) m.promo or (p and BLACK_BIT) else p
        sq[m.from] = EMPTY
        if (typeOf(p) == KING) {
            if (colorOf(p) == 0) { wKing = m.to; castling = castling and 12 }
            else { bKing = m.to; castling = castling and 3 }
            if (m.flag == 2) {
                when (m.to) {
                    0x76 -> { sq[0x75] = sq[0x77]; sq[0x77] = EMPTY }
                    0x72 -> { sq[0x73] = sq[0x70]; sq[0x70] = EMPTY }
                    0x06 -> { sq[0x05] = sq[0x07]; sq[0x07] = EMPTY }
                    0x02 -> { sq[0x03] = sq[0x00]; sq[0x00] = EMPTY }
                }
            }
        }
        if (m.flag == 1) { // en passant capture
            val capSq = m.to + (if (whiteToMove) 16 else -16)
            undo.epPawnSq = capSq
            sq[capSq] = EMPTY
        }
        // rook captured on its home square loses that castling right
        when (m.to) {
            0x77 -> castling = castling and 14
            0x70 -> castling = castling and 13
            0x07 -> castling = castling and 11
            0x00 -> castling = castling and 7
        }
        if (m.from == 0x77 || m.from == 0x74) castling = castling and 14
        if (m.from == 0x70 || m.from == 0x74) castling = castling and 13
        if (m.from == 0x07 || m.from == 0x04) castling = castling and 11
        if (m.from == 0x00 || m.from == 0x04) castling = castling and 7
        ep = if (m.flag == 3) m.from + (if (whiteToMove) -16 else 16) else -1
        if (!whiteToMove) fullmove++
        whiteToMove = !whiteToMove
        return undo
    }

    fun unmake(m: Move, undo: Undo) {
        whiteToMove = !whiteToMove
        if (!whiteToMove) fullmove--   // white now to move again after unmaking black's move
        val moved = sq[m.to]
        sq[m.from] = if (m.promo != 0) PAWN or (moved and BLACK_BIT) else moved
        sq[m.to] = undo.captured
        if (typeOf(sq[m.from]) == KING) {
            if (colorOf(sq[m.from]) == 0) wKing = m.from else bKing = m.from
            if (m.flag == 2) {
                when (m.to) {
                    0x76 -> { sq[0x77] = sq[0x75]; sq[0x75] = EMPTY }
                    0x72 -> { sq[0x70] = sq[0x73]; sq[0x73] = EMPTY }
                    0x06 -> { sq[0x07] = sq[0x05]; sq[0x05] = EMPTY }
                    0x02 -> { sq[0x00] = sq[0x03]; sq[0x03] = EMPTY }
                }
            }
        }
        if (m.flag == 1) {
            sq[undo.epPawnSq] = PAWN or (if (whiteToMove) BLACK_BIT else 0)
            sq[m.to] = EMPTY
        }
        castling = undo.castling
        ep = undo.ep
        halfmove = undo.half
    }

    /** Fully legal moves (king-safety filtered). */
    fun legalMoves(): List<Move> {
        val pseudo = ArrayList<Move>(64)
        genPseudo(pseudo)
        val out = ArrayList<Move>(pseudo.size)
        val meWhite = whiteToMove
        for (m in pseudo) {
            val u = make(m)
            if (!inCheck(meWhite)) out.add(m)
            unmake(m, u)
        }
        return out
    }

    fun play(m: Move) { make(m) }

    fun playUci(uci: String): Boolean {
        val m = legalMoves().firstOrNull { it.uci() == uci } ?: return false
        make(m); return true
    }

    enum class Status { ONGOING, CHECKMATE, STALEMATE, DRAW_50, DRAW_MATERIAL }

    fun status(): Pair<Status, Boolean> { // status + whiteToMove flag retained for mate side
        if (halfmove >= 100) return Status.DRAW_50 to whiteToMove
        if (insufficientMaterial()) return Status.DRAW_MATERIAL to whiteToMove
        if (legalMoves().isEmpty()) return (if (inCheck(whiteToMove)) Status.CHECKMATE else Status.STALEMATE) to whiteToMove
        return Status.ONGOING to whiteToMove
    }

    private fun insufficientMaterial(): Boolean {
        var bishops = 0; var knights = 0
        for (i in 0 until 128) {
            if (i and 0x88 != 0) continue
            when (typeOf(sq[i])) {
                PAWN, ROOK, QUEEN -> return false
                BISHOP -> bishops++
                KNIGHT -> knights++
                else -> {}
            }
        }
        return bishops + knights <= 1
    }
}
