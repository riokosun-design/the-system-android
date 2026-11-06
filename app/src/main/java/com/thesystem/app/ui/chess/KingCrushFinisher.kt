package com.thesystem.app.ui.chess

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.chess.BLACK_BIT
import com.thesystem.app.chess.KING
import com.thesystem.app.core.ui.rememberSystemHaptics
import kotlin.math.cos
import kotlin.math.sin

/**
 * vC53 §7 — THE FINISHER. Every King Crush / Checkmate detonates this
 * un-skippable 2.5s anime cutscene BEFORE the result panel:
 *   1. dim + radial kinetic speed-lines (60) converging on the kill square,
 *   2. the ATTACKER piece silhouette lunges across with a cyan/crimson slash,
 *   3. the enemy KING is sliced diagonally into two drifting halves + embers,
 *   4. the verdict banner slams down with the reward breakdown.
 * Pure Canvas + bundled cburnett bitmaps — 60fps on a 2GB device, zero assets.
 */
@Composable
fun KingCrushFinisher(
    attackerPiece: Int,
    bannerText: String,          // "[ KING CRUSHED ]" | "[ CHECKMATE VICTORY ]"
    rewardLine: String,          // reward breakdown ("+150 XP · RATED"), may be ""
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberSystemHaptics()
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        ChessSfx.play(ChessSfx.Sfx.CRUSH)
        ChessSfx.buzz(context, 160, 220)
        t.animateTo(1f, tween(2500, easing = LinearEasing))
        onDone()
    }
    // cinematic shakes at the two impact frames
    val shake = if (t.value > 0.30f && t.value < 0.55f) (0.55f - t.value) * 46f
                else if (t.value >= 0.55f && t.value < 0.75f) (0.75f - t.value) * 22f else 0f
    val shX = if (shake > 0f) sin(t.value * 190.0).toFloat() * shake else 0f
    val shY = if (shake > 0f) cos(t.value * 155.0).toFloat() * shake * 0.7f else 0f

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .graphicsLayer { translationX = shX; translationY = shY },
    ) {
        BackHandler { /* un-skippable by law */ }

        val pieceImgs = rememberPieceBitmaps()
        val victimKing = KING or (if (attackerPiece and BLACK_BIT != 0) 0 else BLACK_BIT)

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            if (w < 8f || h < 8f) return@Canvas
            val cx = w / 2f; val cy = h * 0.44f
            val v = t.value

            // ── 1 · radial kinetic speed-lines (60) ─────────────────────────
            val focusR = (minOf(w, h) * 0.52f)
            for (i in 0 until 60) {
                val ang = (i / 60f) * 6.2831855f + v * 0.35f
                val len = focusR * (0.55f + 0.6f * ((i * 37 % 11) / 10f)) * (0.35f + 0.65f * v)
                val ex = cx + cos(ang) * len; val ey = cy + sin(ang) * len
                val sx = cx + cos(ang) * (len * 0.45f); val sy = cy + sin(ang) * (len * 0.45f)
                val color = if (i % 2 == 0) Color.White else Color(0xFF22D3EE)
                val alpha = (0.10f + 0.20f * ((i * 53 % 7) / 6f)) * (if (v < 0.15f) v / 0.15f else 1f)
                drawLine(color.copy(alpha = alpha), Offset(sx, sy), Offset(ex, ey), strokeWidth = 2.4f)
            }
            // vignette pull toward the kill point
            drawRect(
                Brush.radialGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f), radius = focusR * 2.2f),
            )

            // ── 2 · attacker silhouette lunge + the slash ───────────────────
            val lunge = if (v < 0.34f) FastOutSlowInEasing.transform(v / 0.34f) else 1f
            val pieceSide = minOf(w, h) * 0.52f
            val px = (-pieceSide) + (cx - pieceSide * 0.62f + pieceSide) * lunge
            val py = cy - pieceSide * 0.52f - sin(v * 3.14159f) * h * 0.05f
            pieceImgs[attackerPiece]?.let { img ->
                drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(px.toInt(), py.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(pieceSide.toInt(), pieceSide.toInt()),
                    alpha = (0.85f + 0.15f * v).coerceAtMost(1f))
            }
            // cyan+c crimson slash arc, neon additive trail
            if (v in 0.18f..0.62f) {
                val sv = (v - 0.18f) / 0.44f
                val slashR = focusR * (0.55f + 0.9f * sv)
                rotate(degrees = -38f, pivot = Offset(cx, cy)) {
                    drawArc(Color(0xFF22D3EE), -64f, 128f * sv, false,
                        topLeft = Offset(cx - slashR, cy - slashR * 0.42f), size = Size(slashR * 2f, slashR * 0.84f),
                        style = Stroke(focusR * 0.055f), blendMode = BlendMode.Plus)
                    drawArc(Color(0xFFFF1744), -58f, 116f * sv, false,
                        topLeft = Offset(cx - slashR * 0.94f, cy - slashR * 0.40f), size = Size(slashR * 1.88f, slashR * 0.80f),
                        style = Stroke(focusR * 0.028f), blendMode = BlendMode.Plus)
                }
            }

            // ── 3 · the KING, sliced in two along the glowing fault ─────────
            if (v >= 0.30f) {
                val kv = ((v - 0.30f) / 0.70f).coerceIn(0f, 1f)
                val ks = minOf(w, h) * 0.30f
                val kx = cx + focusR * 0.16f; val ky = cy - ks * 0.42f
                val drift = ks * 0.42f * kv
                val fade = 1f - (kv * 0.45f)
                pieceImgs[victimKing]?.let { img ->
                    rotate(degrees = -40f, pivot = Offset(kx + ks / 2f, ky + ks / 2f)) {
                        // top-left half drifts up-left
                        translate(left = -drift, top = -drift * 0.6f) {
                            clipPath(Path().apply {
                                moveTo(kx - ks * 0.2f, ky - ks * 0.2f)
                                lineTo(kx + ks * 1.2f, ky - ks * 0.2f)
                                lineTo(kx - ks * 0.2f, ky + ks * 1.2f); close()
                            }) {
                                drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(kx.toInt(), ky.toInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(ks.toInt(), ks.toInt()), alpha = fade)
                            }
                        }
                        // bottom-right half drifts down-right, sinking
                        translate(left = drift * 0.8f, top = drift * 1.1f) {
                            clipPath(Path().apply {
                                moveTo(kx + ks * 1.2f, ky + ks * 1.2f)
                                lineTo(kx + ks * 1.2f, ky - ks * 0.2f)
                                lineTo(kx - ks * 0.2f, ky + ks * 1.2f); close()
                            }) {
                                drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(kx.toInt(), ky.toInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(ks.toInt(), ks.toInt()), alpha = fade)
                            }
                        }
                        // the glowing fault line
                        drawLine(
                            Brush.linearGradient(listOf(Color.White, Color(0xFFFF1744), Color.White)),
                            Offset(kx - ks * 0.15f, ky + ks * 1.15f), Offset(kx + ks * 1.15f, ky - ks * 0.15f),
                            strokeWidth = ks * 0.035f, blendMode = BlendMode.Plus,
                        )
                    }
                }
                // embers rising off the wound
                var rng = 0x5DEECE66DL
                for (i in 0 until 22) {
                    rng = rng xor (rng shl 13); rng = rng xor (rng ushr 7); rng = rng xor (rng shl 17)
                    val ex0 = kx + ((rng ushr 12) and 0xFF) / 255f * ks
                    val ey0 = ky + ks - ((rng ushr 24) and 0xFF) / 255f * ks * 0.4f
                    val rise = kv * (ks * 0.5f + (i % 5) * ks * 0.10f)
                    drawCircle(
                        (if (i % 3 == 0) Color(0xFF22D3EE) else Color(0xFFFF7043))
                            .copy(alpha = (1f - kv) * 0.85f),
                        radius = 2.6f + (i % 3),
                        center = Offset(ex0 + sin(kv * 6f + i) * 8f, ey0 - rise),
                    )
                }
            }
        }

        // ── 4 · the verdict banner slams in ─────────────────────────────────
        if (t.value >= 0.66f) {
            val sv = ((t.value - 0.66f) / 0.34f).coerceIn(0f, 1f)
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(top = 190.dp)
                    .graphicsLayer {
                        val s = 0.7f + 0.3f * FastOutSlowInEasing.transform(sv)
                        scaleX = s; scaleY = s; alpha = sv
                    }
                    .background(Color.Black.copy(alpha = 0.78f), CutCornerShape(10.dp))
                    .border(2.dp, Color(0xFFFFD54F), CutCornerShape(10.dp))
                    .padding(horizontal = 26.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    bannerText,
                    color = Color(0xFFFFD54F), fontSize = 18.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                )
                if (rewardLine.isNotBlank()) {
                    Text(
                        rewardLine,
                        color = Color.White, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}
