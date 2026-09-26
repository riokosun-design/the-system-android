package com.thesystem.app.ui.intro

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.chess.KNIGHT
import com.thesystem.app.core.theme.InkBlack
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.LineSoft
import com.thesystem.app.core.theme.MonoLabel
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.theme.SystemMono
import com.thesystem.app.core.ui.SystemProcessing
import com.thesystem.app.core.ui.SystemStatusLine
import com.thesystem.app.core.ui.rememberSystemHaptics
import com.thesystem.app.ui.chess.ChessSetBrushes
import com.thesystem.app.ui.chess.SystemChessSet
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

// Timing spine — SHORT ≈ 1.3s every open (spec §2: 1–1.5s max); the FULL
// cinematic (≈6.2s) and FAST cut (≈2.6s) play once per install only.
private const val FULL_TOTAL = 6200L
private const val FULL_P2 = 1550L     // PHYSICAL PROTOCOL — push-up silhouette
private const val FULL_P3 = 3900L     // MENTAL PROTOCOL — knight entrance
private const val FULL_P4 = 5050L     // title: THE SYSTEM
private const val FAST_TOTAL = 2600L
private const val SHORT_TOTAL = 1300L

private enum class IntroMode { FULL, FAST, SHORT }

/**
 * THE SYSTEM ACTIVATION INTRO — replaces every generic launch loader.
 *
 *   S emblem (glitch → blue pulse → stabilize)
 *   → push-up silhouette (BODY)
 *   → chess knight hard-cut entrance (MIND)
 *   → THE SYSTEM · TRAIN YOUR BODY. TRAIN YOUR MIND.
 *
 * Pure Canvas + hard editorial cuts — no video, no assets, no spinner.
 * FAST MODE auto-engages on low-RAM devices; SKIP arms after the first cycle.
 */
@Composable
fun SystemActivationIntro(onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = rememberSystemHaptics()
    // spec §2: regular opens are a 1.3s mark — the full cinematic is a
    // once-per-install ceremony, never a tax on every launch.
    val prefs = remember { ctx.getSharedPreferences("intro_prefs", Context.MODE_PRIVATE) }
    val mode = remember {
        if (!prefs.getBoolean("cinematic_seen", false)) {
            if (isLowEndDevice(ctx)) IntroMode.FAST else IntroMode.FULL
        } else IntroMode.SHORT
    }
    val total = when (mode) { IntroMode.FULL -> FULL_TOTAL; IntroMode.FAST -> FAST_TOTAL; IntroMode.SHORT -> SHORT_TOTAL }

    var tMs by remember { mutableLongStateOf(0L) }
    var fired by remember { mutableStateOf(false) }

    LaunchedEffect(mode) {
        val start = System.currentTimeMillis()
        var lastRep = 0
        while (true) {
            val t = System.currentTimeMillis() - start
            tMs = t
            if (mode == IntroMode.FULL) {
                // haptic rep-count pulse — the floor answers each push-up
                val rep = if (t in FULL_P2 until FULL_P3) (((t - FULL_P2) / 1050).toInt() + 1) else 0
                if (rep != lastRep) { lastRep = rep; if (rep > 0) haptics.tick() }
            }
            if (t >= total) break
            delay(16)
        }
        if (!fired) { fired = true; prefs.edit().putBoolean("cinematic_seen", true).apply(); onFinished() }
    }

    fun finishNow() {
        if (!fired) {
            fired = true
            haptics.select()
            prefs.edit().putBoolean("cinematic_seen", true).apply()
            onFinished()
        }
    }

    Box(Modifier.fillMaxSize().background(InkBlack)) {
        when (mode) {
            IntroMode.FAST -> FastCut(tMs)
            IntroMode.SHORT -> ShortMark(tMs)
            IntroMode.FULL -> when {
                tMs < FULL_P2 -> PhaseActivation(tMs)
                tMs < FULL_P3 -> PhasePhysical(tMs - FULL_P2)
                tMs < FULL_P4 -> PhaseMental(tMs - FULL_P3)
                else -> PhaseTitle(tMs - FULL_P4)
            }
        }

        // SKIP — arms after the first animation cycle, stays out of the show
        if (mode != IntroMode.SHORT && tMs > 1300L) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 18.dp)
                    .border(1.dp, LineSoft)
                    .clickable { finishNow() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("SKIP ›", color = LabelGray, style = MonoLabel, letterSpacing = 2.4.sp)
            }
        }
    }
}

/** SHORT — the 1.3s everyday open: S mark, glow settle, name, hairline, gone. */
@Composable
private fun ShortMark(t: Long) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            SystemEmblemMark(
                Modifier.size(72.dp),
                glitch = if (t < 260f) (1f - t / 260f) * 0.7f else 0f,
                energy = 0.4f + 0.6f * appear(t, 200f, 420f),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "THE SYSTEM",
                color = PaperWhite.copy(alpha = appear(t, 240f, 360f)),
                fontFamily = SystemMono, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp,
            )
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.height(2.dp).width((appear(t, 420f, 420f) * 96).dp)) {
                drawRect(SkyBlue, Offset.Zero, size)
            }
        }
    }
}

/** Low-end selector: the System honors weak vessels — 4GB/Go devices get the boot cut. */
private fun isLowEndDevice(ctx: Context): Boolean {
    return runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        am.isLowRamDevice || mi.totalMem <= 4L * 1024L * 1024L * 1024L
    }.getOrDefault(false)
}

private fun appear(t: Long, start: Float, dur: Float = 220f): Float =
    ((t - start) / dur).coerceIn(0f, 1f)

// ── PHASE 01 — SYSTEM ACTIVATION ────────────────────────────────────────────

@Composable
private fun PhaseActivation(t: Long) {
    // emblem glitch decays into a stabilized mark
    val glitch = when {
        t < 480f -> (1f - t / 480f) * 0.9f
        t < 560f -> 0.5f   // hard re-strike
        else -> 0f
    }
    val energy = 0.45f + 0.55f * appear(t, 380f, 500f)
    Box(Modifier.fillMaxSize()) {
        // one quiet scan sweep — the system is reading the vessel
        Canvas(Modifier.fillMaxSize()) {
            val scanY = ((t % 1100) / 1100f) * size.height
            drawRect(SkyBlue.copy(alpha = 0.05f), Offset(0f, scanY), androidx.compose.ui.geometry.Size(size.width, 2.2f))
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SystemEmblemMark(Modifier.size(104.dp), glitch = glitch, energy = energy)
            Spacer(Modifier.height(22.dp))
            Text(
                "SYSTEM INITIALIZING",
                color = PaperWhite.copy(alpha = appear(t, 300f, 350f)),
                style = MonoLabel, fontSize = 12.sp, letterSpacing = 3.6.sp,
            )
            Spacer(Modifier.height(14.dp))
            // BODY · MIND · DISCIPLINE — the three blades, drawn sequentially
            val words = listOf("BODY", "MIND", "DISCIPLINE")
            words.forEachIndexed { i, w ->
                val a = appear(t, 820f + i * 240f, 200f)
                if (a > 0f) {
                    Text(
                        w,
                        color = if (i == 1) SkyBlue.copy(alpha = a) else PaperWhite.copy(alpha = a * 0.85f),
                        fontFamily = SystemMono, fontSize = 10.sp, letterSpacing = 5.5.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

// ── PHASE 02 — PHYSICAL PROTOCOL (push-up silhouette, cinematic) ────────────

private const val REP_MS = 1050
private const val UP_ANGLE = 0.245f     // rad ≈ 14°
private const val DOWN_ANGLE = 0.080f   // rad ≈ 4.6°

@Composable
private fun PhasePhysical(t: Long) {
    val darkFlesh = Color(0xFF101014)
    val rim = PaperWhite
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val groundY = h * 0.70f

            // rep timing: 0..0.45 descend, 0.45..1.0 rise
            val repT = ((t % REP_MS) / REP_MS.toFloat())
            val k = if (repT < 0.45f) repT / 0.45f else (1f - repT) / 0.55f
            val smooth = 0.5f - 0.5f * cos(k * PI.toFloat())
            val ang = UP_ANGLE - (UP_ANGLE - DOWN_ANGLE) * smooth
            val bottomness = 1f - smooth   // 1 at the floor for elbow flare

            // ── floor ──
            drawLine(Color(0x33FFFFFF), Offset(w * 0.14f, groundY), Offset(w * 0.86f, groundY), 2.2f)

            // ── figure geometry (toes pivot, head-side left) ──
            val pivot = Offset(w * 0.805f, groundY - 6f)
            val dirX = -cos(ang); val dirY = -kotlin.math.sin(ang)
            val bodyLen = w * 0.50f
            val hip = Offset(pivot.x + dirX * bodyLen * 0.52f, pivot.y + dirY * bodyLen * 0.52f)
            val shoulder = Offset(pivot.x + dirX * bodyLen, pivot.y + dirY * bodyLen)
            val head = Offset(shoulder.x + dirX * w * 0.052f - w * 0.012f, shoulder.y + dirY * w * 0.052f - h * 0.012f)
            val hand = Offset(shoulder.x + w * 0.012f, groundY)
            val elbow = Offset(
                (shoulder.x + hand.x) / 2f - w * 0.085f * bottomness,
                (shoulder.y + hand.y) / 2f,
            )

            // motion streaks at the explosive rise
            if (repT in 0.45f..0.62f) {
                val sa = (1f - (repT - 0.45f) / 0.17f) * 0.30f
                repeat(3) { i ->
                    val yy = shoulder.y + i * 9f
                    drawLine(LineSoft.copy(alpha = sa), Offset(shoulder.x + 26f + i * 14f, yy), Offset(shoulder.x + 96f + i * 14f, yy), 2f)
                }
            }

            // ── draw figure: white rim under dark body = anime rim-light ──
            val limbW = w * 0.030f
            val torsoW = w * 0.044f
            val headR = w * 0.036f
            // rim pass
            drawLine(rim, pivot, hip, torsoW + 5f, cap = StrokeCap.Round)
            drawLine(rim, hip, shoulder, torsoW + 5f, cap = StrokeCap.Round)
            drawLine(rim, shoulder, elbow, limbW + 4.4f, cap = StrokeCap.Round)
            drawLine(rim, elbow, hand, limbW + 4.4f, cap = StrokeCap.Round)
            drawLine(rim, Offset(pivot.x, groundY - 2f), pivot, limbW + 4f, cap = StrokeCap.Round)
            drawCircle(rim, headR + 2.6f, head)
            // core pass
            drawLine(darkFlesh, pivot, hip, torsoW, cap = StrokeCap.Round)
            drawLine(darkFlesh, hip, shoulder, torsoW, cap = StrokeCap.Round)
            drawLine(darkFlesh, shoulder, elbow, limbW, cap = StrokeCap.Round)
            drawLine(darkFlesh, elbow, hand, limbW, cap = StrokeCap.Round)
            drawLine(darkFlesh, Offset(pivot.x, groundY - 2f), pivot, limbW, cap = StrokeCap.Round)
            drawCircle(darkFlesh, headR, head)

            // system pulse ring at each completed rep
            if (repT > 0.80f) {
                val k2 = (repT - 0.80f) / 0.20f
                drawCircle(
                    SkyBlue.copy(alpha = (1f - k2) * 0.50f),
                    radius = k2 * w * 0.34f + 8f,
                    center = Offset(w * 0.42f, groundY),
                    style = Stroke(3f * (1f - k2) + 1f),
                )
            }

            // HUD corner ticks
            val tick = 13f
            val tc = PaperWhite.copy(alpha = 0.5f)
            drawLine(tc, Offset(30f, 30f), Offset(30f + tick, 30f), 2f); drawLine(tc, Offset(30f, 30f), Offset(30f, 30f + tick), 2f)
            drawLine(tc, Offset(w - 30f, 30f), Offset(w - 30f - tick, 30f), 2f); drawLine(tc, Offset(w - 30f, 30f), Offset(w - 30f, 30f + tick), 2f)
            drawLine(tc, Offset(30f, h - 30f), Offset(30f + tick, h - 30f), 2f); drawLine(tc, Offset(30f, h - 30f), Offset(30f, h - 30f - tick), 2f)
            drawLine(tc, Offset(w - 30f, h - 30f), Offset(w - 30f - tick, h - 30f), 2f); drawLine(tc, Offset(w - 30f, h - 30f), Offset(w - 30f, h - 30f - tick), 2f)
        }
        // HUD copy
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "PHYSICAL PROTOCOL",
                color = PaperWhite.copy(alpha = appear(t, 120f, 400f)),
                fontFamily = SystemMono, fontSize = 13.sp, letterSpacing = 4.5.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (t > REP_MS * 2 + 120f) "VERIFIED" else "REP ${(t / REP_MS).toInt() + 1} / 2",
                color = if (t > REP_MS * 2 + 120f) SkyBlue else LabelGray,
                style = MonoLabel, fontSize = 10.sp, letterSpacing = 3.sp,
            )
        }
    }
}

// ── PHASE 03 — MENTAL PROTOCOL (the knight arrives) ─────────────────────────

@Composable
private fun PhaseMental(t: Long) {
    val dur = 1150f
    val t01 = (t / dur).coerceIn(0f, 1f)
    // entry easing (easeOutCubic)
    val e = 1f - (1f - t01) * (1f - t01) * (1f - t01)
    val flash = (t in 20L..70L) || (t in 360L..395L)      // hard-cut white frames
    val glitchy = t in 90L..300L

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val knightH = h * 0.44f
            val groundY = h * 0.72f

            if (flash) {
                drawRect(Color.White.copy(alpha = 0.92f), Offset.Zero, size)
                return@Canvas
            }
            // board hairline
            drawLine(Color(0x33FFFFFF), Offset(w * 0.10f, groundY), Offset(w * 0.94f, groundY), 2.2f)

            val startX = w * 1.25f
            val endX = w * 0.56f
            val x = startX + (endX - startX) * e
            val top = groundY - knightH

            val art = SystemChessSet.art(KNIGHT)
            val s = knightH / 40f

            // motion-blur ghosts ride the entry
            if (t01 < 0.55f) {
                listOf(0.20f to 26f, 0.10f to 52f).forEach { (a, back) ->
                    withTransform({ translate(x + back, top); scale(s, s, Offset.Zero) }) {
                        for (p in art.fills) drawPath(p, PaperWhite.copy(alpha = a))
                    }
                }
            }
            // the knight — white form, dark edge, THE SYSTEM's own piece
            val jitter = if (glitchy) (((t / 48) % 3) - 1) * 4.2f else 0f
            withTransform({ translate(x + jitter, top); scale(s, s, Offset.Zero) }) {
                for (p in art.fills) {
                    drawPath(p, ChessSetBrushes.whiteFill)
                    drawPath(p, ChessSetBrushes.whiteEdge, style = Stroke(1.15f))
                }
                for ((d, dw) in art.details) {
                    drawPath(d, ChessSetBrushes.whiteEdge, style = Stroke(dw))
                }
            }
            // glitch slices tearing the entry
            if (glitchy) {
                repeat(4) { i ->
                    val yy = ((t / 40 + i * 173) % h.toInt().coerceAtLeast(1)).toFloat()
                    drawRect(
                        if (i % 2 == 0) SkyBlue.copy(alpha = 0.30f) else PaperWhite.copy(alpha = 0.22f),
                        Offset(0f, yy),
                        androidx.compose.ui.geometry.Size(w, 3.5f + i),
                    )
                }
            }
            // contact pulse once it locks in
            if (t01 > 0.82f) {
                val k = (t01 - 0.82f) / 0.18f
                drawCircle(
                    SkyBlue.copy(alpha = (1f - k) * 0.45f),
                    radius = 12f + k * w * 0.22f,
                    center = Offset(x + knightH * 0.5f, groundY),
                    style = Stroke(2.6f),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "MENTAL PROTOCOL",
                color = PaperWhite.copy(alpha = appear(t, 430f, 320f)),
                fontFamily = SystemMono, fontSize = 13.sp, letterSpacing = 4.5.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "ENGAGE",
                color = SkyBlue.copy(alpha = appear(t, 820f, 260f)),
                style = MonoLabel, fontSize = 10.sp, letterSpacing = 5.sp,
            )
        }
    }
}

// ── PHASE 04 — THE MARK ─────────────────────────────────────────────────────

@Composable
private fun PhaseTitle(t: Long) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SystemEmblemMark(Modifier.size(44.dp), glitch = if (t < 140f) 0.35f else 0f)
            Spacer(Modifier.height(18.dp))
            Text(
                "THE SYSTEM",
                color = PaperWhite.copy(alpha = appear(t, 60f, 420f)),
                fontFamily = SystemMono, fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.height(2.dp).width((appear(t, 260f, 500f) * 120).dp)) {
                drawRect(SkyBlue, Offset.Zero, size)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "TRAIN YOUR BODY. TRAIN YOUR MIND.",
                color = LabelGray.copy(alpha = appear(t, 480f, 500f)),
                style = MonoLabel, fontSize = 10.sp, letterSpacing = 2.6.sp,
            )
        }
    }
}

// ── FAST CUT — low-end boot (≤2.6s): same identity, minimal motion ──────────

@Composable
private fun FastCut(t: Long) {
    Box(Modifier.fillMaxSize()) {
        when {
            t < 850L -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    SystemEmblemMark(Modifier.size(88.dp), glitch = if (t < 360f) 1f - t / 400f else 0f)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "SYSTEM INITIALIZING",
                        color = PaperWhite.copy(alpha = appear(t, 260f, 300f)),
                        style = MonoLabel, fontSize = 11.sp, letterSpacing = 3.2.sp,
                    )
                }
            }
            t < 1650L -> {
                // montage flash: body, then mind — two hard cuts
                if (t < 1200L) {
                    PhasePhysicalRep(Modifier.align(Alignment.Center).size(220.dp), still = true)
                    Text(
                        "BODY", color = PaperWhite, fontFamily = SystemMono, fontSize = 10.sp, letterSpacing = 4.sp,
                        modifier = Modifier.align(BiasAlignment(0f, 0.28f)),
                    )
                } else {
                    FastKnight(Modifier.align(Alignment.Center).size(220.dp))
                    Text(
                        "MIND", color = SkyBlue, fontFamily = SystemMono, fontSize = 10.sp, letterSpacing = 4.sp,
                        modifier = Modifier.align(BiasAlignment(0f, 0.28f)),
                    )
                }
            }
            else -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "THE SYSTEM",
                        color = PaperWhite, fontFamily = SystemMono, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 5.5.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "TRAIN YOUR BODY. TRAIN YOUR MIND.",
                        color = LabelGray, style = MonoLabel, fontSize = 9.sp, letterSpacing = 2.2.sp,
                    )
                }
            }
        }
    }
}

/** Static push-up frame used only inside the fast montage. */
@Composable
private fun PhasePhysicalRep(modifier: Modifier, still: Boolean) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val groundY = h * 0.82f
        val ang = if (still) 0.16f else UP_ANGLE
        val pivot = Offset(w * 0.82f, groundY)
        val dirX = -cos(ang); val dirY = -kotlin.math.sin(ang)
        val bodyLen = w * 0.52f
        val hip = Offset(pivot.x + dirX * bodyLen * 0.52f, pivot.y + dirY * bodyLen * 0.52f)
        val shoulder = Offset(pivot.x + dirX * bodyLen, pivot.y + dirY * bodyLen)
        val head = Offset(shoulder.x + dirX * w * 0.06f - w * 0.01f, shoulder.y + dirY * w * 0.06f - h * 0.015f)
        val hand = Offset(shoulder.x + w * 0.015f, groundY)
        drawLine(Color(0x33FFFFFF), Offset(w * 0.10f, groundY), Offset(w * 0.90f, groundY), 2f)
        val limbW = w * 0.034f
        val torsoW = w * 0.05f
        drawLine(PaperWhite, pivot, hip, torsoW + 4.6f, cap = StrokeCap.Round)
        drawLine(PaperWhite, hip, shoulder, torsoW + 4.6f, cap = StrokeCap.Round)
        drawLine(PaperWhite, shoulder, hand, limbW + 4f, cap = StrokeCap.Round)
        drawCircle(PaperWhite, w * 0.042f, head)
        drawLine(Color(0xFF101014), pivot, hip, torsoW, cap = StrokeCap.Round)
        drawLine(Color(0xFF101014), hip, shoulder, torsoW, cap = StrokeCap.Round)
        drawLine(Color(0xFF101014), shoulder, hand, limbW, cap = StrokeCap.Round)
        drawCircle(Color(0xFF101014), w * 0.040f, head)
    }
}

/** Static knight frame used only inside the fast montage. */
@Composable
private fun FastKnight(modifier: Modifier) {
    Canvas(modifier) {
        val art = SystemChessSet.art(KNIGHT)
        val s = min(size.width / 40f, size.height / 40f)
        withTransform({
            translate((size.width - 40f * s) / 2f, (size.height - 40f * s) / 2f)
            scale(s, s, Offset.Zero)
        }) {
            for (p in art.fills) {
                drawPath(p, ChessSetBrushes.whiteFill)
                drawPath(p, ChessSetBrushes.whiteEdge, style = Stroke(1.15f))
            }
            for ((d, dw) in art.details) drawPath(d, ChessSetBrushes.whiteEdge, style = Stroke(dw))
        }
    }
}

// ── BOOT SYNC GATE — what shows after the intro while the session resolves ──

@Composable
fun SystemBootSync(
    label: String = "SYNCING PROFILE",
    error: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(InkBlack)
            .then(if (onRetry != null) Modifier.clickable { onRetry() } else Modifier),
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SystemEmblemMark(Modifier.size(76.dp), energy = 0.4f)
            Spacer(Modifier.height(20.dp))
            if (error == null) {
                SystemStatusLine(label)
                Spacer(Modifier.height(14.dp))
                SystemProcessing("SIGNAL LOCK", Modifier.fillMaxWidth())
            } else {
                SystemStatusLine("SIGNAL LOST", active = false)
                Spacer(Modifier.height(10.dp))
                Text(error.take(120), color = LabelGray, style = MonoLabel)
                Spacer(Modifier.height(14.dp))
                Text("TAP TO RE-SYNC", color = PaperWhite, style = MonoLabel, letterSpacing = 2.4.sp)
            }
        }
    }
}
