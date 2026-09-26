package com.thesystem.app.ui.chess

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.thesystem.app.chess.Board
import com.thesystem.app.chess.EMPTY
import com.thesystem.app.chess.Move
import com.thesystem.app.chess.PAWN
import com.thesystem.app.chess.typeOf
import com.thesystem.app.core.theme.SkyBlue

/** Monochrome square palette — light squares clearly lifted, dark squares near-black. */
private val LIGHT_SQ = Color(0xFF3C3C41)
private val DARK_SQ = Color(0xFF0E0E10)

/** A move currently animating on the board — slides, never teleports. */
data class AnimMove(
    val from: Int,
    val to: Int,
    val movingPiece: Int,   // piece BEFORE the move (pawn stays pawn for promo slide)
    val captured: Int,      // piece that stood on `to` before the move (EMPTY if none)
)

/**
 * THE SYSTEM CHESSBOARD — one Canvas, real 8×8 geometry, hardware-drawn.
 *
 *  · clearly distinguished light/dark squares, vector piece set (SystemChessSet)
 *  · selected-square frame, legal-move dots, capture rings, last-move wash
 *  · pulsing royal frame on the checked king
 *  · short slide animation per move + capture fade + promotion crossfade
 *  · file/rank coordinate etching — the board behaves like a real board
 */
@Composable
fun ChessBoard(
    board: Board,
    myColor: Int,
    selected: Int,
    targets: Set<Int>,
    lastMove: Pair<Int, Int>?,
    onSquare: (Int) -> Unit,
    modifier: Modifier = Modifier,
    checkSquare: Int = -1,
    hint: Pair<Int, Int>? = null,        // SHOW MOVE HINTS — engine-whispered from→to
    anim: AnimMove? = null,
    onAnimDone: () -> Unit = {},
) {
    // slide progress for the one moving piece
    val progress = remember { Animatable(1f) }
    LaunchedEffect(anim) {
        if (anim != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(190, easing = FastOutSlowInEasing))
            onAnimDone()
        }
    }
    // quiet pulse for the check frame
    val inf = rememberInfiniteTransition(label = "boardPulse")
    val checkA by inf.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse),
        label = "checkA",
    )
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }

    Canvas(
        modifier
            .aspectRatio(1f)
            .pointerInput(myColor) {
                detectTapGestures { off ->
                    val cell = size.width / 8f
                    if (cell <= 0f) return@detectTapGestures
                    val vF = (off.x / cell).toInt().coerceIn(0, 7)
                    val vR = (off.y / cell).toInt().coerceIn(0, 7)
                    val r = if (myColor == 0) vR else 7 - vR
                    val f = if (myColor == 0) vF else 7 - vF
                    onSquare((r shl 4) or f)
                }
            },
    ) {
        val cell = size.minDimension / 8f
        if (cell <= 0f) return@Canvas
        labelPaint.textSize = cell * 0.22f

        fun visualX(f: Int) = if (myColor == 0) f else 7 - f
        fun visualY(r: Int) = if (myColor == 0) r else 7 - r

        // ── squares + highlights ─────────────────────────────────────────
        for (r in 0 until 8) {
            for (f in 0 until 8) {
                val sq = (r shl 4) or f
                val left = visualX(f) * cell
                val top = visualY(r) * cell
                val light = (r + f) % 2 == 0
                drawRect(
                    if (light) LIGHT_SQ else DARK_SQ,
                    Offset(left, top),
                    androidx.compose.ui.geometry.Size(cell, cell),
                )
                // last-move wash
                if (lastMove != null && (sq == lastMove.first || sq == lastMove.second)) {
                    drawRect(SkyBlue.copy(alpha = 0.20f), Offset(left, top), androidx.compose.ui.geometry.Size(cell, cell))
                }
                // selected frame
                if (sq == selected) {
                    drawRect(SkyBlue.copy(alpha = 0.22f), Offset(left, top), androidx.compose.ui.geometry.Size(cell, cell))
                    drawRect(
                        SkyBlue, Offset(left + 1f, top + 1f),
                        androidx.compose.ui.geometry.Size(cell - 2f, cell - 2f),
                        style = Stroke(2.2f),
                    )
                }
                // checked king frame — royal pulse
                if (sq == checkSquare) {
                    drawRect(Color.White.copy(alpha = 0.16f * checkA), Offset(left, top), androidx.compose.ui.geometry.Size(cell, cell))
                    drawRect(
                        Color.White.copy(alpha = 0.55f + 0.45f * checkA),
                        Offset(left + 2f, top + 2f),
                        androidx.compose.ui.geometry.Size(cell - 4f, cell - 4f),
                        style = Stroke(2.4f),
                    )
                }
                // legal targets
                if (sq in targets) {
                    if (board.sq[sq] != EMPTY) {
                        drawCircle(
                            SkyBlue.copy(alpha = 0.85f),
                            radius = cell * 0.44f,
                            center = Offset(left + cell / 2f, top + cell / 2f),
                            style = Stroke(cell * 0.055f),
                        )
                    } else {
                        drawCircle(
                            SkyBlue.copy(alpha = 0.85f),
                            radius = cell * 0.115f,
                            center = Offset(left + cell / 2f, top + cell / 2f),
                        )
                    }
                }
            }
        }

        // ── coordinate etching (rank numbers on left, file letters on bottom) ──
        for (i in 0 until 8) {
            // rank number — left edge square of visual row i
            val leftR = if (myColor == 0) i else 7 - i
            val leftF = if (myColor == 0) 0 else 7
            val rankNum = 8 - leftR
            val lightL = (leftR + leftF) % 2 == 0
            labelPaint.color = if (lightL) 0xD9E2E2E6.toInt() else 0xD98A8A90.toInt()
            drawContext.canvas.nativeCanvas.drawText(
                rankNum.toString(), cell * 0.07f, i * cell + cell * 0.22f, labelPaint,
            )
            // file letter — bottom edge square of visual column i
            val botR = if (myColor == 0) 7 else 0
            val botF = if (myColor == 0) i else 7 - i
            val fileChar = 'a' + botF
            val lightB = (botR + botF) % 2 == 0
            labelPaint.color = if (lightB) 0xD9E2E2E6.toInt() else 0xD98A8A90.toInt()
            val w = labelPaint.measureText(fileChar.toString())
            drawContext.canvas.nativeCanvas.drawText(
                fileChar.toString(), i * cell + cell - w - cell * 0.08f, 7f * cell + cell * 0.92f, labelPaint,
            )
        }

        // ── hint arrow (SHOW MOVE HINTS) — quiet, under the pieces ───────────
        if (hint != null) {
            val hfx = visualX(hint.first and 7) * cell + cell / 2f
            val hfy = visualY(hint.first shr 4) * cell + cell / 2f
            val htx = visualX(hint.second and 7) * cell + cell / 2f
            val hty = visualY(hint.second shr 4) * cell + cell / 2f
            val hc = SkyBlue.copy(alpha = 0.62f)
            // shaft stops short of the destination center so pieces stay readable
            val dx = htx - hfx; val dy = hty - hfy
            val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val ux = dx / len; val uy = dy / len
            val sx = hfx + ux * cell * 0.18f; val sy = hfy + uy * cell * 0.18f
            val ex = htx - ux * cell * 0.24f; val ey = hty - uy * cell * 0.24f
            drawLine(hc, Offset(sx, sy), Offset(ex, ey), strokeWidth = cell * 0.085f)
            // arrowhead
            val px = -uy; val py = ux
            drawLine(hc, Offset(ex, ey), Offset(ex - ux * cell * 0.22f + px * cell * 0.15f, ey - uy * cell * 0.22f + py * cell * 0.15f), strokeWidth = cell * 0.085f)
            drawLine(hc, Offset(ex, ey), Offset(ex - ux * cell * 0.22f - px * cell * 0.15f, ey - uy * cell * 0.22f - py * cell * 0.15f), strokeWidth = cell * 0.085f)
            drawCircle(hc, radius = cell * 0.10f, center = Offset(hfx, hfy), style = Stroke(cell * 0.05f))
        }

        // ── pieces (skip the landing square while the mover is sliding) ──
        val animating = anim != null && progress.value < 0.999f
        for (r in 0 until 8) {
            for (f in 0 until 8) {
                val sq = (r shl 4) or f
                val p = board.sq[sq]
                if (p == EMPTY) continue
                if (animating && sq == anim!!.to) continue
                drawChessPiece(p, visualX(f) * cell, visualY(r) * cell, cell)
            }
        }

        // ── the moving piece: slide + capture fade + promotion crossfade ─
        if (anim != null && animating) {
            val a = anim!!
            val t = progress.value
            val fx = visualX(a.from and 7) * cell
            val fy = visualY(a.from shr 4) * cell
            val tx = visualX(a.to and 7) * cell
            val ty = visualY(a.to shr 4) * cell
            // captured defender fades out under the attacker
            if (a.captured != EMPTY) {
                drawChessPiece(a.captured, tx, ty, cell, alpha = (1f - t * 1.8f).coerceIn(0f, 1f))
            }
            val cx = fx + (tx - fx) * t
            val cy = fy + (ty - fy) * t
            val landed = board.sq[a.to]
            if (typeOf(landed) != typeOf(a.movingPiece) && landed != EMPTY) {
                // promotion crossfade in the last quarter of the slide
                if (t < 0.75f) {
                    drawChessPiece(a.movingPiece, cx, cy, cell)
                } else {
                    val k = (t - 0.75f) / 0.25f
                    drawChessPiece(a.movingPiece, cx, cy, cell, alpha = (1f - k).coerceIn(0f, 1f))
                    drawChessPiece(landed, tx, ty, cell, alpha = k)
                }
            } else {
                drawChessPiece(a.movingPiece, cx, cy, cell)
            }
        }
    }
}

/** Which board squares a tap should produce — legal target lookup helper. */
fun targetsFor(moves: List<Move>, from: Int): Set<Int> =
    moves.filter { it.from == from }.map { it.to }.toSet()

/** Compact SAN for the move log: castle-safe, capture/promo/check aware. */
fun sanText(before: Board, m: Move, after: Board): String {
    if (m.flag == 2) { // castle
        val suffix = mateSuffix(after)
        return (if ((m.to and 7) == 6) "O-O" else "O-O-O") + suffix
    }
    val piece = before.sq[m.from]
    val type = typeOf(piece)
    val capture = before.sq[m.to] != EMPTY || m.flag == 1
    val sb = StringBuilder()
    when (type) {
        PAWN -> {
            if (capture) sb.append(('a' + (m.from and 7)))
        }
        else -> sb.append(
            when (type) {
                com.thesystem.app.chess.KNIGHT -> 'N'
                com.thesystem.app.chess.BISHOP -> 'B'
                com.thesystem.app.chess.ROOK -> 'R'
                com.thesystem.app.chess.QUEEN -> 'Q'
                else -> 'K'
            },
        )
    }
    if (capture) sb.append('x')
    sb.append(com.thesystem.app.chess.sqName(m.to))
    if (m.promo != 0) sb.append('=').append(
        when (m.promo) {
            com.thesystem.app.chess.QUEEN -> 'Q'
            com.thesystem.app.chess.ROOK -> 'R'
            com.thesystem.app.chess.BISHOP -> 'B'
            else -> 'N'
        },
    )
    sb.append(mateSuffix(after))
    return sb.toString()
}

private fun mateSuffix(after: Board): String {
    val (st, _) = after.status()
    return when (st) {
        Board.Status.CHECKMATE -> "#"
        else -> if (after.inCheck(after.whiteToMove)) "+" else ""
    }
}
