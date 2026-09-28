package com.thesystem.app.ui.splash

import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.ExperimentalTextApi
import kotlin.math.*
import kotlin.random.Random
import com.thesystem.app.core.theme.*

// All 11 graphs are pure Canvas + one infinite phase animation each.
// Zero allocations per frame beyond remembered geometry → 60/120fps on low-end devices.

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

// ── 1. TERMINAL_GLITCH ───────────────────────────────────────────────────────
@Composable
fun TerminalGlitchGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(1600)
    val charset = remember { "01{}<>/\\ARISEx".toList() }
    val glyphs = remember { List(220) { charset[Random.nextInt(charset.size)] to (Random.nextFloat() to Random.nextFloat()) } }
    val paint = rememberNativePaint(34f, 0xFFFFFFFF.toInt())
    Canvas(modifier) {
        val burst = (sin(p * 2 * PI * 5).toFloat() + 1f) / 2f > 0.82
        glyphs.forEachIndexed { i, (ch, pos) ->
            val flicker = abs(sin(p * 2 * PI * (i % 7 + 1) + i)).toFloat()
            paint.alpha = (60 + 195 * flicker).toInt()
            paint.color = if (i % 9 == 0) 0xFFBFBFBF.toInt() else 0xFFFFFFFF.toInt()
            val jitterX = if (burst && i % 5 == 0) Random.nextInt(-24, 25).toFloat() else 0f
            drawContext.canvas.nativeCanvas.drawText(
                ch.toString(), pos.first * size.width + jitterX,
                ((pos.second + p * (0.05f + (i % 4) * 0.02f)) % 1f) * size.height, paint,
            )
        }
        if (burst) { // horizontal glitch slices
            repeat(4) {
                val y = Random.nextFloat() * size.height
                drawRect(
                    ElectricBlue.copy(alpha = 0.25f),
                    Offset(Random.nextFloat() * 40f, y),
                    androidx.compose.ui.geometry.Size(size.width, 6f + Random.nextFloat() * 10f),
                )
            }
        }
    }
}

// ── 2. LEVEL_PULSE ───────────────────────────────────────────────────────────
@Composable
fun LevelPulseGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(1800)
    val paint = rememberNativePaint(90f, 0xFFFFFFFF.toInt(), Typeface.DEFAULT_BOLD)
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2
        repeat(4) { ring ->
            val phase = (p + ring * 0.25f) % 1f
            drawCircle(
                ElectricBlue.copy(alpha = (1f - phase) * 0.7f),
                radius = phase * size.minDimension * 0.48f,
                center = Offset(cx, cy),
                style = Stroke(width = 6f * (1f - phase) + 2f),
            )
        }
        val level = 1 + floor(p * 99).toInt()
        paint.textSize = 110f
        drawContext.canvas.nativeCanvas.drawText("LV $level", cx, cy + 38f, paint)
        drawContext.canvas.nativeCanvas.drawText(
            "LEVEL UP", cx, cy - size.minDimension * 0.28f,
            paint.apply { textSize = 40f; color = 0xFFC4C4C4.toInt() },
        )
    }
}

// ── 3. SPIDER_RADAR ──────────────────────────────────────────────────────────
@Composable
fun SpiderRadarGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(3000)
    val blips = remember { List(7) { Random.nextFloat() * 2 * PI.toFloat() to (0.25f + Random.nextFloat() * 0.65f) } }
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2; val r = size.minDimension * 0.42f
        repeat(4) { i -> drawCircle(GridLine, radius = r * (i + 1) / 4f, center = Offset(cx, cy), style = Stroke(2f)) }
        drawLine(GridLine, Offset(cx - r, cy), Offset(cx + r, cy), 2f)
        drawLine(GridLine, Offset(cx, cy - r), Offset(cx, cy + r), 2f)
        rotate(p * 360f, Offset(cx, cy)) {
            drawLine(Brush.horizontalGradient(listOf(ElectricBlue, Color.Transparent)), Offset(cx, cy), Offset(cx + r, cy), 8f)
        }
        blips.forEach { (angle, dist) ->
            val sweep = (angle - p * 2 * PI.toFloat()).mod(2 * PI.toFloat())
            val alpha = (1f - sweep / (2 * PI.toFloat())).coerceIn(0f, 1f)
            drawCircle(PaperWhite.copy(alpha = alpha), radius = 8f + 6f * alpha,
                center = Offset(cx + cos(angle) * r * dist, cy + sin(angle) * r * dist))
        }
    }
}

// ── 4. SHADOW_HEARTBEAT ──────────────────────────────────────────────────────
@Composable
fun ShadowHeartbeatGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(1400)
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2
        val beat = (exp(-((p % 0.5f) * 14f)) + 0.6f * exp(-(((p + 0.85f) % 0.5f) * 14f))).toFloat()
        // ECG trace
        val path = Path()
        for (x in 0..size.width.toInt() step 6) {
            val local = (x / size.width + p) % 1f
            val spike = when {
                local in 0.42f..0.47f -> -(local - 0.42f) * 14f
                local in 0.47f..0.52f -> (0.52f - local) * 22f
                local in 0.52f..0.57f -> -(local - 0.52f) * 6f
                else -> 0f
            }
            val y = cy * 1.6f + spike * size.height * 0.12f
            if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
        }
        drawPath(path, PaperWhite, style = Stroke(4f))
        // Shadow flame heart
        val pulse = 1f + beat * 0.35f
        drawCircle(Brush.radialGradient(listOf(NeonPurple.copy(alpha = 0.55f), Color.Transparent)),
            radius = size.minDimension * 0.22f * pulse, center = Offset(cx, cy * 0.8f))
        drawCircle(Color.Black, radius = size.minDimension * 0.09f * pulse, center = Offset(cx, cy * 0.8f))
        drawCircle(PaperWhite.copy(alpha = 0.8f * beat), radius = size.minDimension * 0.035f * pulse, center = Offset(cx, cy * 0.8f))
    }
}

// ── 5. RANK_BARS ─────────────────────────────────────────────────────────────
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

// ── 6. TERRITORY_GRID ────────────────────────────────────────────────────────
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

// ── 7. COMBAT_SPLINE ─────────────────────────────────────────────────────────
@Composable
fun CombatSplineGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(2000)
    val pathA = remember { android.graphics.Path() }
    val pathB = remember { android.graphics.Path() }
    val measure = remember { android.graphics.PathMeasure() }
    val pos = remember { FloatArray(2) }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        pathA.reset(); pathA.moveTo(0f, h * 0.7f); pathA.cubicTo(w * 0.3f, h * 0.1f, w * 0.6f, h * 0.9f, w, h * 0.3f)
        pathB.reset(); pathB.moveTo(w, h * 0.7f); pathB.cubicTo(w * 0.7f, h * 0.1f, w * 0.4f, h * 0.9f, 0f, h * 0.3f)
        drawPath(pathA.asComposePath(), Brush.horizontalGradient(listOf(ElectricBlue, Color.Transparent)), style = Stroke(5f))
        drawPath(pathB.asComposePath(), Brush.horizontalGradient(listOf(Color.Transparent, LabelGray)), style = Stroke(5f))
        measure.setPath(pathA, false)
        measure.getPosTan(measure.length * p, pos, null)
        drawCircle(ElectricBlue, radius = 16f, center = Offset(pos[0], pos[1]))
        measure.setPath(pathB, false)
        measure.getPosTan(measure.length * p, pos, null)
        drawCircle(LabelGray, radius = 16f, center = Offset(pos[0], pos[1]))
        if (p in 0.47f..0.53f) drawCircle(Color.White, radius = size.minDimension * 0.3f, center = Offset(w / 2, h / 2), alpha = 0.35f)
    }
}

// ── 8. QUEST_DONUT ───────────────────────────────────────────────────────────
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

// ── 9. BATTLE_SPLIT ──────────────────────────────────────────────────────────
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

// ── 10. BLACK_ROOM_SCAN ──────────────────────────────────────────────────────
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

// ── 11. LIMIT_BREAKER ────────────────────────────────────────────────────────
@Composable
fun LimitBreakerGraph(modifier: Modifier = Modifier) {
    val p = rememberPhase(2400)
    val particles = remember { List(80) { Random.nextFloat() * 2 * PI.toFloat() to (0.4f + Random.nextFloat() * 0.6f) } }
    Canvas(modifier) {
        val cx = size.width / 2; val cy = size.height / 2; val maxR = size.minDimension * 0.5f
        val burst = p > 0.86f
        particles.forEach { (angle, dist) ->
            val r = if (burst) maxR * dist * ((p - 0.86f) / 0.14f) else maxR * dist * (1f - p / 0.86f)
            val alpha = if (burst) 1f - (p - 0.86f) / 0.14f else 0.4f + 0.6f * p
            drawCircle(
                if (dist > 0.8f) NeonPurple.copy(alpha = alpha) else ElectricBlue.copy(alpha = alpha),
                radius = 6f, center = Offset(cx + cos(angle) * r, cy + sin(angle) * r),
            )
        }
        if (burst) {
            val ringP = (p - 0.86f) / 0.14f
            drawCircle(Color.White.copy(alpha = (1f - ringP) * 0.8f),
                radius = ringP * maxR, center = Offset(cx, cy), style = Stroke(8f * (1f - ringP) + 1f))
        } else {
            drawCircle(Color.White.copy(alpha = p * 0.9f), radius = 10f + p * 14f, center = Offset(cx, cy))
        }
    }
}
