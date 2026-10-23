package com.thesystem.app.ui.splash

import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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

// ── 3. GROWTH_CURVE (0.26.0 §2/§3 — quest-donut & battle-split EXECUTED; only
// the marks the product stands on remain: ranks, the black room, growth) ─────
@Composable
fun GrowthCurveGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(3400)
    val paint = rememberNativePaint(30f, 0xFFFFFFFF.toInt(), Typeface.MONOSPACE)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (w < 4f || h < 4f) return@Canvas
        // quiet ledger grid
        for (i in 0..8) {
            val y = h * i / 8f
            drawLine(Color(0x12FFFFFF), Offset(0f, y), Offset(w, y), 1f)
        }
        // monotone discipline rise — fixed waypoints, smooth cubic joints
        val depths = listOf(0.06f, 0.16f, 0.30f, 0.46f, 0.62f, 0.80f)
        val letters = listOf("F", "E", "D", "C", "B", "A")
        val padX = w * 0.07f
        val nodes = depths.mapIndexed { i, d ->
            Offset(padX + (w - 2 * padX) * i / (depths.size - 1), h * (1f - d * 0.82f) - h * 0.06f)
        }
        val reveal = (p * 1.55f).coerceAtMost(1f)        // draws in, holds, loops
        val curve = Path().apply {
            moveTo(nodes.first().x, nodes.first().y)
            for (i in 1 until nodes.size) {
                val a = nodes[i - 1]; val b = nodes[i]
                cubicTo(
                    a.x + (b.x - a.x) * 0.5f, a.y,
                    a.x + (b.x - a.x) * 0.5f, b.y,
                    b.x, b.y,
                )
            }
        }
        clipRect(0f, 0f, w * reveal, h) {
            drawPath(curve, ElectricBlue, style = Stroke(width = 5f, cap = StrokeCap.Round))
            // soft area under the rise — one gradient, no glow stack
            val area = Path().apply {
                addPath(curve)
                lineTo(nodes.last().x, h); lineTo(nodes.first().x, h); close()
            }
            drawPath(
                area,
                Brush.verticalGradient(
                    listOf(ElectricBlue.copy(alpha = 0.20f), Color.Transparent),
                    startY = h * 0.25f, endY = h,
                ),
            )
        }
        // waypoint seals + ladder letters
        nodes.forEachIndexed { i, n ->
            if (n.x <= w * reveal) {
                val isNewest = i == nodes.indexOfLast { it.x <= w * reveal }
                drawCircle(ElectricBlue, radius = if (isNewest) 9f else 6f, center = n)
                drawCircle(PaperWhite.copy(alpha = 0.7f), radius = if (isNewest) 12f else 9f, center = n, style = Stroke(2f))
                paint.alpha = if (isNewest) 235 else 150
                drawContext.canvas.nativeCanvas.drawText(letters[i], n.x, (n.y + 46f).coerceAtMost(h - 8f), paint)
            }
        }
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


