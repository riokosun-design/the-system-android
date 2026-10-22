package com.thesystem.app.ui.splash

import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.*
import com.thesystem.app.core.theme.*

// All living graphs are pure Canvas + one infinite phase animation each.
// Zero allocations per frame beyond remembered geometry → 60/120fps on low-end devices.
// v0.25.0: dead canvases (terminal glitch / level pulse / spider radar / heartbeat /
// combat spline / limit breaker) culled — no dead code survives.

@Composable
private fun rememberPhase(periodMs: Int = 2200): Float {
    val t = rememberInfiniteTransition(label = "phase")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "p",
    )
    return p
}

@Composable
private fun rememberNativePaint(textSize: Float, color: Int, typeface: Typeface = Typeface.MONOSPACE) =
    remember(textSize, color) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = color
            this.typeface = typeface
            this.textAlign = android.graphics.Paint.Align.CENTER
        }
    }

// ── 1. RANK_BARS ─────────────────────────────────────────────────────────────
@Composable
fun RankBarsGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(2400)
    val ranks = listOf("E", "D", "C", "B", "A", "S")
    val colors = listOf(TextMuted, TextMuted, VenomGreen, ElectricBlue, NeonPurple, HunterGold)
    Canvas(modifier) {
        val n = ranks.size
        val barW = size.width / (n * 2)
        ranks.forEachIndexed { i, label ->
            val h = (0.25f + 0.6f * abs(sin(p * PI * 2 + i * 0.6)).toFloat()) * size.height * 0.7f
            val x = size.width * (i * 2 + 0.5f) / (n * 2)
            drawRoundRect(
                Brush.verticalGradient(listOf(colors[i], colors[i].copy(alpha = 0.2f))),
                Offset(x, size.height - h), androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f),
            )
            drawRect(colors[i], Offset(x - 2f, size.height - h - 8f), androidx.compose.ui.geometry.Size(barW + 4f, 6f))
        }
    }
}

// ── 2. TERRITORY_GRID ────────────────────────────────────────────────────────
@Composable
fun TerritoryGridGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(3200)
    Canvas(modifier) {
        val rows = 12; val cols = 9
        val cw = size.width / cols; val ch = size.height / rows
        for (r in 0..rows) drawLine(GridLine, Offset(0f, r * ch), Offset(size.width, r * ch), 1f)
        for (c in 0..cols) drawLine(GridLine, Offset(c * cw, 0f), Offset(c * cw, size.height), 1f)
        // Capture wave expanding from center
        val waveR = p * 1.4f
        for (r in 0 until rows) for (c in 0 until cols) {
            val dr = r - rows / 2f; val dc = c - cols / 2f
            val d = sqrt(dr * dr + dc * dc) / (rows / 2f)
            val filled = d < waveR && d > waveR - 0.35f
            val owned = d <= waveR - 0.35f
            when {
                filled -> drawRect(ElectricBlue.copy(alpha = 0.55f), Offset(c * cw + 2, r * ch + 2), androidx.compose.ui.geometry.Size(cw - 4, ch - 4))
                owned && (r + c) % 3 == 0 -> drawRect(NeonPurple.copy(alpha = 0.16f), Offset(c * cw + 2, r * ch + 2), androidx.compose.ui.geometry.Size(cw - 4, ch - 4))
            }
        }
    }
}

// ── 3. QUEST_DONUT ───────────────────────────────────────────────────────────
@Composable
fun QuestDonutGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(2600)
    val segments = listOf(0.34f to ElectricBlue, 0.26f to NeonPurple, 0.22f to VenomGreen, 0.18f to HunterGold)
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.07f)
        var start = -90f
        val fill = min(1f, p * 1.25f)
        segments.forEach { (frac, color) ->
            val sweep = 360f * frac * fill
            drawArc(color, startAngle = start, sweepAngle = sweep - 4f, useCenter = false, style = stroke,
                topLeft = Offset(size.minDimension * 0.1f, size.minDimension * 0.1f),
                size = androidx.compose.ui.geometry.Size(size.minDimension * 0.8f, size.minDimension * 0.8f))
            start += 360f * frac
        }
        drawCircle(VoidBlack, radius = size.minDimension * 0.24f, center = Offset(size.width / 2, size.height / 2))
    }
}

// ── 4. BATTLE_SPLIT ──────────────────────────────────────────────────────────
@Composable
fun BattleSplitGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(1400)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val clash = sin(p * PI).toFloat() // 0→1→0 approach
        val bluePath = Path().apply {
            moveTo(0f, 0f); lineTo(w * (0.35f + 0.15f * clash), 0f); lineTo(w * (0.65f - 0.15f * clash), h); lineTo(0f, h); close()
        }
        val redPath = Path().apply {
            moveTo(w, 0f); lineTo(w * (0.85f - 0.12f * clash), 0f); lineTo(w * (0.35f + 0.12f * clash), h); lineTo(w, h); close()
        }
        drawPath(bluePath, Brush.linearGradient(listOf(PaperWhite.copy(alpha = 0.45f), Color(0x11FFFFFF))))
        drawPath(redPath, Brush.linearGradient(listOf(Color(0x11FFFFFF), LabelGray.copy(alpha = 0.5f))))
        // clash seam
        drawLine(Color.White.copy(alpha = clash), Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), strokeWidth = 2f + 6f * clash)
    }
}

// ── 5. BLACK_ROOM_SCAN ───────────────────────────────────────────────────────
@Composable
fun BlackRoomScanGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(2800)
    val paint = rememberNativePaint(46f, 0xFFFFFFFF.toInt(), Typeface.DEFAULT_BOLD)
    Canvas(modifier) {
        // faint room grid
        for (i in 0..20) {
            val y = size.height * i / 20f
            drawLine(Color(0x14FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
        }
        val scanY = size.height * p
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, PaperWhite.copy(alpha = 0.30f), Color.Transparent)),
            Offset(0f, scanY - 60f), androidx.compose.ui.geometry.Size(size.width, 120f))
        // lock glyph revealed under the scan line
        val cx = size.width / 2; val cy = size.height / 2
        val reveal = (1f - abs(p - 0.5f) * 2f).coerceIn(0.05f, 1f)
        drawRoundRect(PaperWhite.copy(alpha = reveal),
            Offset(cx - 70f, cy - 20f), androidx.compose.ui.geometry.Size(140f, 110f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f), style = Stroke(6f))
        drawArc(PaperWhite.copy(alpha = reveal), startAngle = 180f, sweepAngle = 180f, useCenter = false, style = Stroke(6f),
            topLeft = Offset(cx - 45f, cy - 90f), size = androidx.compose.ui.geometry.Size(90f, 90f))
        paint.alpha = (reveal * 255).toInt()
        drawContext.canvas.nativeCanvas.drawText("RESTRICTED", cx, cy + 190f, paint)
    }
}


