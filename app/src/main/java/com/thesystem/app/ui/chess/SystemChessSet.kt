package com.thesystem.app.ui.chess

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import com.thesystem.app.chess.BISHOP
import com.thesystem.app.chess.KING
import com.thesystem.app.chess.KNIGHT
import com.thesystem.app.chess.PAWN
import com.thesystem.app.chess.QUEEN
import com.thesystem.app.chess.ROOK
import com.thesystem.app.chess.colorOf
import com.thesystem.app.chess.typeOf

/**
 * THE SYSTEM CHESS SET — one coherent professional piece family, hand-built
 * as vector silhouettes in a 40×40 space: PAWN / ROOK / KNIGHT / BISHOP /
 * QUEEN / KING. Zero assets, zero fonts — pure Path geometry, cached once,
 * reused for board squares, captured-piece strips and the intro knight.
 *
 * Visual law: BLACK + WHITE + SYSTEM BLUE. White army = near-white bodies
 * with dark edge lines; black army = near-black bodies with white edges.
 */
private val FillWhite = Color(0xFFF4F4F4)
private val FillBlack = Color(0xFF141417)
private val EdgeOnWhite = Color(0xFF0C0C0E)
private val EdgeOnBlack = Color(0xFFE8E8E8)

private const val OUTLINE_W = 1.15f

/** Fills + detail strokes (drawn in the piece's edge color for contrast). */
class PieceArt(val fills: List<Path>, val details: List<Pair<Path, Float>>)

private fun baseSlab(x0: Float, y0: Float, x1: Float, y1: Float) = Path().apply {
    moveTo(x0 + 1.2f, y0)
    lineTo(x1 - 1.2f, y0)
    lineTo(x1, y0 + 1.2f)
    lineTo(x1, y1)
    lineTo(x0, y1)
    lineTo(x0, y0 + 1.2f)
    close()
}

object SystemChessSet {

    private val cache = HashMap<Int, PieceArt>()

    fun art(type: Int): PieceArt = cache.getOrPut(type) { build(type) }

    private fun build(type: Int): PieceArt = when (type) {
        PAWN -> PawnArt
        KNIGHT -> KnightArt
        BISHOP -> BishopArt
        ROOK -> RookArt
        QUEEN -> QueenArt
        else -> KingArt
    }

    // ── PAWN ─────────────────────────────────────────────────────────────
    private val PawnArt: PieceArt by lazy {
        val head = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(14f, 5.5f, 26f, 17.5f))
        }
        val body = Path().apply {
            moveTo(20f, 16.5f)
            cubicTo(16.2f, 16.5f, 14.5f, 19.2f, 14.8f, 22.2f)
            lineTo(13.2f, 29.6f)
            lineTo(26.8f, 29.6f)
            lineTo(25.2f, 22.2f)
            cubicTo(25.5f, 19.2f, 23.8f, 16.5f, 20f, 16.5f)
            close()
        }
        PieceArt(listOf(head, body, baseSlab(11f, 29.6f, 29f, 33.6f)), emptyList())
    }

    // ── ROOK ─────────────────────────────────────────────────────────────
    private val RookArt: PieceArt by lazy {
        val body = Path().apply {
            moveTo(9.5f, 4.6f)
            lineTo(14f, 4.6f); lineTo(14f, 7.6f); lineTo(17.5f, 7.6f); lineTo(17.5f, 4.6f)
            lineTo(22.5f, 4.6f); lineTo(22.5f, 7.6f); lineTo(26f, 7.6f); lineTo(26f, 4.6f)
            lineTo(30.5f, 4.6f)
            lineTo(30.5f, 12.2f)
            lineTo(27.5f, 14.2f)
            lineTo(27.5f, 28.6f)
            lineTo(12.5f, 28.6f)
            lineTo(12.5f, 14.2f)
            lineTo(9.5f, 12.2f)
            close()
        }
        val ring = Path().apply {
            moveTo(12.5f, 17.4f); lineTo(27.5f, 17.4f)
        }
        PieceArt(listOf(body, baseSlab(9.5f, 28.6f, 30.5f, 33.6f)), listOf(ring to 1.0f))
    }

    // ── KNIGHT (classic left-facing profile, mane zigzag) ────────────────
    private val KnightArt: PieceArt by lazy {
        val head = Path().apply {
            moveTo(7.4f, 20.6f)          // muzzle tip
            lineTo(11.2f, 12.4f)         // forehead
            lineTo(17.8f, 6.4f)          // front of poll
            lineTo(20.6f, 3.6f)          // ear tip
            lineTo(22.4f, 6.6f)          // ear dip
            lineTo(25.8f, 5.4f)          // mane point 1
            lineTo(25.2f, 8.6f)
            lineTo(28.4f, 8.2f)          // mane point 2
            lineTo(27.6f, 11.4f)
            lineTo(30.2f, 11.8f)         // mane point 3
            lineTo(29.0f, 14.6f)
            cubicTo(30.8f, 18.5f, 31.2f, 24f, 30.6f, 30.8f)   // back of neck → base
            lineTo(10.2f, 30.8f)
            cubicTo(10.6f, 27.6f, 11.2f, 25.2f, 12.4f, 23.0f) // chest front
            lineTo(9.4f, 23.8f)          // muzzle chin
            close()
        }
        // eye — detail stroke dot
        val eye = Path().apply {
            moveTo(16f, 13.6f); lineTo(16.2f, 13.6f)
        }
        // nostril hint near the muzzle
        val nostril = Path().apply {
            moveTo(9.6f, 21.3f); lineTo(9.8f, 21.3f)
        }
        PieceArt(listOf(head, baseSlab(9f, 30.8f, 31.6f, 34.2f)), listOf(eye to 3.2f, nostril to 2.4f))
    }

    // ── BISHOP ───────────────────────────────────────────────────────────
    private val BishopArt: PieceArt by lazy {
        val ball = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(17.6f, 2.6f, 22.4f, 7.4f))
        }
        val mitre = Path().apply {
            moveTo(20f, 7.8f)
            cubicTo(14.5f, 8.8f, 13.2f, 13.6f, 15.5f, 16.9f)
            cubicTo(16.8f, 18.7f, 18.4f, 19.4f, 20f, 19.4f)
            cubicTo(21.6f, 19.4f, 23.2f, 18.7f, 24.5f, 16.9f)
            cubicTo(26.8f, 13.6f, 25.5f, 8.8f, 20f, 7.8f)
            close()
        }
        val collar = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(17f, 19.9f, 23f, 22.1f, 1f, 1f)
            )
        }
        val torso = Path().apply {
            moveTo(20f, 22.1f)
            cubicTo(16f, 24.6f, 14f, 27.6f, 12.5f, 29.6f)
            lineTo(27.5f, 29.6f)
            cubicTo(26f, 27.6f, 24f, 24.6f, 20f, 22.1f)
            close()
        }
        // the signature diagonal slit across the mitre
        val slit = Path().apply {
            moveTo(17.2f, 12.4f); lineTo(22.8f, 10.6f)
        }
        PieceArt(listOf(ball, mitre, collar, torso, baseSlab(11f, 29.6f, 29f, 33.6f)), listOf(slit to 1.3f))
    }

    // ── QUEEN (five-point coronet + balls) ───────────────────────────────
    private val QueenArt: PieceArt by lazy {
        val crown = Path().apply {
            moveTo(8.2f, 7.8f)
            lineTo(12.6f, 15.2f)
            lineTo(14.4f, 6.6f)
            lineTo(18.4f, 15.2f)
            lineTo(20f, 5.9f)
            lineTo(21.6f, 15.2f)
            lineTo(25.6f, 6.6f)
            lineTo(27.4f, 15.2f)
            lineTo(31.8f, 7.8f)
            lineTo(29.2f, 18.5f)
            lineTo(10.8f, 18.5f)
            close()
        }
        val balls = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(6.5f, 4.7f, 9.9f, 8.1f))
            addOval(androidx.compose.ui.geometry.Rect(12.7f, 3.5f, 16.1f, 6.9f))
            addOval(androidx.compose.ui.geometry.Rect(18.3f, 2.9f, 21.7f, 6.3f))
            addOval(androidx.compose.ui.geometry.Rect(23.9f, 3.5f, 27.3f, 6.9f))
            addOval(androidx.compose.ui.geometry.Rect(30.1f, 4.7f, 33.5f, 8.1f))
        }
        val band = Path().apply {
            moveTo(12f, 19.5f)
            lineTo(28f, 19.5f)
            lineTo(25.5f, 26f)
            lineTo(14.5f, 26f)
            close()
        }
        val skirt = Path().apply {
            moveTo(14.5f, 26f)
            cubicTo(13.5f, 28f, 12.5f, 29.6f, 11.5f, 30.6f)
            lineTo(28.5f, 30.6f)
            cubicTo(27.5f, 29.6f, 26.5f, 28f, 25.5f, 26f)
            close()
        }
        val waist = Path().apply {
            moveTo(14.5f, 23f); lineTo(25.5f, 23f)
        }
        PieceArt(listOf(crown, balls, band, skirt, baseSlab(10.5f, 30.6f, 29.5f, 34.2f)), listOf(waist to 1.0f))
    }

    // ── KING (cross finial + arch crown) ─────────────────────────────────
    private val KingArt: PieceArt by lazy {
        val crossV = Path().apply { addRect(androidx.compose.ui.geometry.Rect(19.1f, 3.2f, 20.9f, 8.6f)) }
        val crossH = Path().apply { addRect(androidx.compose.ui.geometry.Rect(17.4f, 4.9f, 22.6f, 6.6f)) }
        val arch = Path().apply {
            moveTo(13.2f, 22.5f)
            cubicTo(11.2f, 14.5f, 14.2f, 10.2f, 20f, 10.2f)
            cubicTo(25.8f, 10.2f, 28.8f, 14.5f, 26.8f, 22.5f)
            close()
        }
        val collar = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(12.8f, 23.3f, 27.2f, 25.7f, 1f, 1f))
        }
        val skirt = Path().apply {
            moveTo(14f, 26.2f)
            cubicTo(13.2f, 28.2f, 12.2f, 29.7f, 11.3f, 30.7f)
            lineTo(28.7f, 30.7f)
            cubicTo(27.8f, 29.7f, 26.8f, 28.2f, 26f, 26.2f)
            close()
        }
        PieceArt(
            listOf(crossV, crossH, arch, collar, skirt, baseSlab(10.3f, 30.7f, 29.7f, 34.2f)),
            emptyList(),
        )
    }
}

/**
 * Draw one chess piece. `piece` is the engine int (type | BLACK_BIT).
 * `size` in px; art is centered horizontally inside the box and sits on
 * its base — the caller passes the square rect.
 */
fun DrawScope.drawChessPiece(piece: Int, left: Float, top: Float, size: Float, alpha: Float = 1f) {
    val type = typeOf(piece)
    val white = colorOf(piece) == 0
    val fillC = if (white) FillWhite else FillBlack
    val lineC = if (white) EdgeOnWhite else EdgeOnBlack
    val art = SystemChessSet.art(type)
    // 10% breathing margin around the 40-space art
    val s = size * 0.86f / 40f
    val ox = left + (size - 40f * s) / 2f
    val oy = top + (size - 36f * s) * 0.5f - size * 0.02f
    withTransform({
        translate(ox, oy)
        scale(s, s, Offset.Zero)
    }) {
        for (p in art.fills) {
            drawPath(p, fillC, alpha = alpha)
            drawPath(p, lineC, alpha = alpha, style = Stroke(OUTLINE_W))
        }
        for ((d, w) in art.details) {
            drawPath(d, lineC, alpha = alpha, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

/** Fill/edge pair exposed for non-board callers (intro knight, hub glyph). */
object ChessSetBrushes {
    val whiteFill get() = FillWhite
    val blackFill get() = FillBlack
    val whiteEdge get() = EdgeOnWhite
    val blackEdge get() = EdgeOnBlack
}
