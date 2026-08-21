package com.thesystem.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.thesystem.app.core.theme.ElectricBlue
import com.thesystem.app.core.theme.HunterGold
import com.thesystem.app.core.theme.NeonPurple
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ── EFFECTS ENGINE ───────────────────────────────────────────────────────────
// All effects are single-Canvas, zero-allocation-per-frame, capped particle
// counts, preallocated state. This is how you get "juice" without jank:
// no objects created in draw loops → no GC pauses → 60/120fps on low-end.

// ══ 1. CONFETTI / XP BURST — the quest-clear dopamine hit ═══════════════════

private class BurstParticle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float,
    val maxLife: Float,
    val size: Float,
    val color: Color,
)

/** Fire-and-forget one-shot particle burst. Call [burstKey]++ (or [burstSignal]) to re-fire. */
@Composable
fun BoxScope.XpBurst(
    burstSignal: Int,
    modifier: Modifier = Modifier,
    color: Color = HunterGold,
    accent: Color = ElectricBlue,
) {
    if (burstSignal <= 0) return
    // Keyed per-signal: each burst fully resets and replays.
    androidx.compose.runtime.key(burstSignal) {
        val particles = remember {
            val rnd = Random(burstSignal)
            Array(28) {
                val angle = rnd.nextFloat() * 2f * PI.toFloat()
                val speed = 220f + rnd.nextFloat() * 620f
                val life = 0.55f + rnd.nextFloat() * 0.5f
                BurstParticle(
                    x = 0f, y = 0f,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed - 280f, // bias upward, like XP rising
                    life = life, maxLife = life,
                    size = 2.5f + rnd.nextFloat() * 4.5f,
                    color = if (rnd.nextFloat() < 0.6f) color else accent,
                )
            }
        }
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1.05f, tween(950, easing = LinearEasing)) }
        val t = progress.value
        Canvas(modifier.fillMaxSize()) {
            if (t >= 1f) return@Canvas
            val cx = size.width / 2f
            val cy = size.height / 2f
            for (p in particles) {
                val elapsed = t * p.maxLife
                if (elapsed > p.life) continue
                val px = cx + p.vx * elapsed
                val py = cy + p.vy * elapsed + 480f * elapsed * elapsed // gravity
                val fade = 1f - (elapsed / p.life)
                drawCircle(p.color.copy(alpha = fade), radius = p.size * fade + 0.5f, center = Offset(px, py))
            }
        }
    }
}

// ══ 2. AMBIENT FLOATERS — slow drifting "energy motes" behind content ═══════
// Gives screens a living, breathing backdrop at near-zero cost (24 dots, one Canvas).

private class Mote(val bx: Float, val speed: Float, val size: Float, val phase: Float, val drift: Float)

@Composable
fun AmbientMotes(
    modifier: Modifier = Modifier,
    color: Color = ElectricBlue,
    count: Int = 22,
    seed: Int = 7,
) {
    val motes = remember(seed, count) {
        val rnd = Random(seed)
        Array(count) {
            Mote(
                bx = rnd.nextFloat(),
                speed = 0.02f + rnd.nextFloat() * 0.05f, // fraction of height per second-ish
                size = 1f + rnd.nextFloat() * 2.4f,
                phase = rnd.nextFloat() * 2f * PI.toFloat(),
                drift = 12f + rnd.nextFloat() * 30f,
            )
        }
    }
    val inf = rememberInfiniteTransition(label = "motes")
    val t by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
        label = "motesT",
    )
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        for (m in motes) {
            val yFrac = (1f - ((t * m.speed * 60f + m.phase / (2f * PI.toFloat())) % 1f) + 1f) % 1f
            val x = m.bx * w + sin(t * 2f * PI.toFloat() * m.speed * 10f + m.phase) * m.drift
            val alpha = 0.10f + 0.10f * sin(t * 2f * PI.toFloat() * 3f + m.phase)
            drawCircle(color.copy(alpha = alpha.coerceIn(0.03f, 0.2f)), radius = m.size, center = Offset(x, yFrac * h))
        }
    }
}

// ══ 3. STREAK FLAME — the daily-login hook ══════════════════════════════════

@Composable
fun StreakFlame(days: Int, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "flame")
    val flicker by inf.animateFloat(
        initialValue = 0.82f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "flicker",
    )
    val color = when {
        days >= 30 -> HunterGold
        days >= 7  -> NeonPurple
        else       -> ElectricBlue
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2f
        val fh = h * flicker
        // outer flame
        drawCircle(color.copy(alpha = 0.25f), radius = w * 0.48f, center = Offset(cx, h * 0.62f))
        // flame body: teardrop approximated with two arcs + tip
        drawCircle(color.copy(alpha = 0.85f), radius = w * 0.30f, center = Offset(cx, h * 0.68f))
        drawCircle(color, radius = w * 0.16f * flicker, center = Offset(cx, h * 0.70f - fh * 0.18f))
        // white-hot core for big streaks
        if (days >= 7) drawCircle(Color.White.copy(alpha = 0.9f), radius = w * 0.08f, center = Offset(cx, h * 0.76f))
    }
}

// ══ 4. SHIMMER SKELETON — loading that feels premium, not broken ════════════

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "shimmer")
    val x by inf.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shimmerX",
    )
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(com.thesystem.app.core.theme.SurfaceHigh)
        val cx = x * w
        val band = w * 0.35f
        // diagonal light sweep
        val steps = 12
        for (i in 0 until steps) {
            val f = i / steps.toFloat()
            val alpha = (0.10f * (1f - kotlin.math.abs(f - 0.5f) * 2f)).coerceAtLeast(0f)
            drawRect(
                Color.White.copy(alpha = alpha),
                topLeft = Offset(cx - band / 2f + f * band, 0f),
                size = androidx.compose.ui.geometry.Size(band / steps, h),
            )
        }
    }
}

// ══ 5. SCANLINE OVERLAY — "SYSTEM terminal" texture, ~zero cost ═════════════

@Composable
fun Scanlines(modifier: Modifier = Modifier, lineColor: Color = Color.Black) {
    Canvas(modifier.fillMaxSize()) {
        val step = 6f
        var y = 0f
        while (y < size.height) {
            drawRect(lineColor.copy(alpha = 0.05f), topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, 1.4f))
            y += step
        }
    }
}

// ══ 6. LEVEL-UP SHOCK RING — expanding rings on rank/level change ═══════════

@Composable
fun BoxScope.LevelUpShockwave(signal: Int, modifier: Modifier = Modifier, color: Color = ElectricBlue) {
    if (signal <= 0) return
    androidx.compose.runtime.key(signal) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1f, tween(900, easing = LinearEasing)) }
        val t = progress.value
        Canvas(modifier.fillMaxSize()) {
            if (t >= 1f) return@Canvas
            val cx = size.width / 2f; val cy = size.height / 2f
            val maxR = size.minDimension * 0.75f
            // three staggered rings
            for (i in 0..2) {
                val rt = (t - i * 0.12f).coerceIn(0f, 1f)
                if (rt <= 0f) continue
                drawCircle(
                    color.copy(alpha = (1f - rt) * 0.7f),
                    radius = maxR * rt,
                    center = Offset(cx, cy),
                    style = Stroke(width = (1f - rt) * 7f + 1f),
                )
            }
        }
    }
}

/** Tiny helper so screens hold one IntState they bump to fire effects. */
@Composable
fun rememberEffectSignal(): Pair<Int, () -> Unit> {
    var s by remember { mutableIntStateOf(0) }
    return s to { s++ }
}
