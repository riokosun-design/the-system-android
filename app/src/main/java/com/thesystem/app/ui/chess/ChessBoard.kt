package com.thesystem.app.ui.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.chess.BLACK_BIT
import com.thesystem.app.chess.Board
import com.thesystem.app.chess.EMPTY
import com.thesystem.app.chess.colorOf
import com.thesystem.app.chess.typeOf
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue

/** Monochrome square palette — light squares gray, dark squares near-black. */
private val LIGHT_SQ = Color(0xFF3A3A3C)
private val DARK_SQ = Color(0xFF141414)

/** White side = filled glyphs; black side = outline glyphs. Monochrome-honest. */
private val FILLED = arrayOf("♟", "♞", "♝", "♜", "♛", "♚") // P N B R Q K
private val OUTLINE = arrayOf("♙", "♘", "♗", "♖", "♕", "♔")

/**
 * SYSTEM CHESSBOARD — 8×8 composable grid. `myColor` 0 = white at bottom.
 * Square taps are reported as 0x88 indices. No assets — pure text glyphs,
 * so it's feather-light on 2GB devices.
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
) {
    val ranks = if (myColor == 0) (0 until 8) else (7 downTo 0)
    val files = if (myColor == 0) (0 until 8) else (7 downTo 0)
    Column(modifier.aspectRatio(1f)) {
        for (r in ranks) {
            Row(Modifier.weight(1f)) {
                for (f in files) {
                    val sq = (r shl 4) or f
                    val p = board.sq[sq]
                    val isLight = (r + f) % 2 == 0
                    val isSel = sq == selected
                    val isTarget = sq in targets
                    val isLast = lastMove != null && (sq == lastMove.first || sq == lastMove.second)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(
                                when {
                                    isLast -> if (isLight) LIGHT_SQ.copy(alpha = 0.85f) else DARK_SQ
                                    isLight -> LIGHT_SQ
                                    else -> DARK_SQ
                                }
                            )
                            .then(
                                if (isSel) Modifier.border(2.dp, SkyBlue)
                                else if (isLast) Modifier.border(1.dp, SkyBlue.copy(alpha = 0.45f))
                                else Modifier
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSquare(sq) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (p != EMPTY) {
                            val white = colorOf(p) == 0
                            Text(
                                (if (white) FILLED else OUTLINE)[typeOf(p) - 1],
                                color = if (white) PaperWhite else Color(0xFFCFCFCF),
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                            )
                        } else if (isTarget) {
                            Box(
                                Modifier
                                    .fillMaxSize(0.26f)
                                    .background(SkyBlue.copy(alpha = 0.85f), CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Which board squares a tap should produce — legal target lookup helper. */
fun targetsFor(moves: List<com.thesystem.app.chess.Move>, from: Int): Set<Int> =
    moves.filter { it.from == from }.map { it.to }.toSet()
