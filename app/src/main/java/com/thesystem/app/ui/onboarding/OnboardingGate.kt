package com.thesystem.app.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.CrimsonRed
import com.thesystem.app.core.theme.ElectricBlue
import com.thesystem.app.core.theme.HunterGold
import com.thesystem.app.core.theme.NeonPurple
import com.thesystem.app.core.theme.SurfaceDark
import com.thesystem.app.core.theme.TextMuted
import com.thesystem.app.core.theme.TextPrimary
import com.thesystem.app.core.ui.SystemHaptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════════════
// ONBOARDING GATE — AAA cinematic primitives bank.
// Everything here is pure Canvas/Animatable: zero assets, zero Lottie,
// zero per-frame allocations on hot paths (drawWithCache + precomputed motes).
// Palette note: cinematic screens intentionally run HOTTER (dim cyan/purple
// glow) than the app's quiet interior — this is the lock-in ritual, not the UI.
// ═══════════════════════════════════════════════════════════════════════════

val GateBlack = Color(0xFF07090E)

// ── 1. NEON GLOW MODIFIER ────────────────────────────────────────────────────
/**
 * Soft radial halo behind any content, hardware-rendered via drawWithCache.
 * The Brush + gradient are built ONCE per size/color change — never per frame.
 */
fun Modifier.neonGlow(
    color: Color,
    radiusFraction: Float = 0.85f,
    alpha: Float = 0.22f,
): Modifier = this.drawWithCache {
    val halo = Brush.radialGradient(
        0f to color.copy(alpha = alpha),
        0.62f to color.copy(alpha = alpha * 0.32f),
        1f to Color.Transparent,
        center = Offset(size.width / 2f, size.height / 2f),
        radius = (maxOf(size.width, size.height) / 2f) / radiusFraction.coerceIn(0.2f, 1f),
    )
    onDrawBehind { drawRect(halo) }
}

// ── 2. AMBIENT SYSTEM BACKDROP — mana field + vignette on every screen ──────

private class Mote(
    val x: Float, val y: Float,
    val speed: Float, val size: Float,
    val phase: Float, val drift: Float,
    val color: Color, val twinkle: Float,
)

@Composable
fun SystemBackdrop(
    particleCount: Int = 26,
    accentA: Color = ElectricBlue,
    accentB: Color = NeonPurple,
    content: @Composable () -> Unit,
) {
    val motes = remember(particleCount) {
        val rnd = kotlin.random.Random(11)
        List(particleCount) {
            Mote(
                x = rnd.nextFloat(), y = rnd.nextFloat(),
                speed = 0.55f + rnd.nextFloat() * 1.4f,   // upward travels per cycle
                size = 1.2f + rnd.nextFloat() * 2.6f,
                phase = rnd.nextFloat() * 2f * PI.toFloat(),
                drift = 10f + rnd.nextFloat() * 26f,
                color = if (it % 3 == 0) accentB else accentA,
                twinkle = 0.6f + rnd.nextFloat() * 1.5f,
            )
        }
    }
    val inf = rememberInfiniteTransition(label = "manaField")
    val t by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing)),
        label = "manaT",
    )
    Box(Modifier.fillMaxSize().background(GateBlack)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            for (m in motes) {
                val yFrac = (((m.y - t * m.speed) % 1f) + 1f) % 1f
                val x = m.x * w + sin(t * 2f * PI.toFloat() * m.twinkle + m.phase) * m.drift
                val tw = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * m.twinkle * 1.7f + m.phase)
                // soft glow: two-layer dot, alpha passed as param — no Color allocations
                drawCircle(m.color, radius = m.size * 3.2f, center = Offset(x, yFrac * h), alpha = 0.05f + 0.06f * tw)
                drawCircle(m.color, radius = m.size, center = Offset(x, yFrac * h), alpha = 0.16f + 0.30f * tw)
            }
            // vignette: keeps edges cinematically dark
            drawRect(
                Brush.radialGradient(
                    0.55f to Color.Transparent, 1f to GateBlack,
                    center = Offset(w / 2f, h * 0.42f), radius = maxOf(w, h) * 0.75f,
                )
            )
        }
        content()
    }
}

// ── 3. GLITCH REVEAL TEXT — RGB split + jitter, then crisp neon ─────────────

private const val GLITCH_MS = 420

@Composable
fun GlitchRevealText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: Int = 22,
    mono: Boolean = false,
    delayMs: Long = 0,
) {
    var phase by remember(text) { mutableIntStateOf(0) } // 0 hidden → 1 glitch → 2 solid
    var jitX by remember(text) { mutableIntStateOf(0) }
    var jitY by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        delay(delayMs)
        phase = 1
        repeat(GLITCH_MS / 28) {
            jitX = (-3..3).random(); jitY = (-2..2).random()
            delay(28)
        }
        jitX = 0; jitY = 0; phase = 2
    }
    if (phase == 0) { Spacer(modifier.height((fontSize * 1.6).dp)); return }
    val style = MaterialTheme.typography.headlineMedium.copy(
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        fontSize = fontSize.sp,
        shadow = androidx.compose.ui.graphics.Shadow(
            color = color.copy(alpha = 0.7f),
            blurRadius = if (phase == 1) 2f else 14f,
        ),
    )
    Box(modifier) {
        if (phase == 1) {
            Text(text, color = CrimsonRed.copy(alpha = 0.55f), style = style,
                modifier = Modifier.graphicsLayer { translationX = jitX - 3f; translationY = -jitY.toFloat() })
            Text(text, color = ElectricBlue.copy(alpha = 0.55f), style = style,
                modifier = Modifier.graphicsLayer { translationX = jitX + 3f; translationY = jitY.toFloat() })
        }
        Text(text, color = color, style = style,
            modifier = Modifier.graphicsLayer { translationX = jitX.toFloat() })
    }
}

// ── 4. TERMINAL LINE — monospace char-by-char boot log ───────────────────────

@Composable
fun TerminalLine(text: String, color: Color = ElectricBlue, fontSize: Int = 13) {
    var count by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) { while (count < text.length) { delay(16); count++ } }
    Text(
        text = "> " + text.take(count) + if (count < text.length) "▊" else "",
        fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = color,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace, color = color,
        ),
    )
}

// ── 5. SELECTABLE SYSTEM CHIP ────────────────────────────────────────────────

@Composable
fun OnboardingChip(
    label: String,
    selected: Boolean,
    accent: Color = ElectricBlue,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else SurfaceDark.copy(alpha = 0.6f))
            .border(1.dp, if (selected) accent.copy(alpha = 0.8f) else TextMuted.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) accent else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── 6. FINGERPRINT CONTRACT GATE — hold 1.5s, neon sweep, micro-haptics ─────

@Composable
fun FingerprintHold(
    haptics: SystemHaptics,
    onHoldComplete: () -> Unit,
    modifier: Modifier = Modifier,
    ringColor: Color = ElectricBlue,
    sealColor: Color = CrimsonRed,
) {
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val inf = rememberInfiniteTransition(label = "printPulse")
    val idlePulse by inf.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "printA")

    Box(modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 10f
            // track ring
            drawCircle(ringColor, radius = r, center = c, alpha = 0.10f, style = Stroke(7f))
            // sweep: cyan first half, crimson seal second half — zero gradient alloc
            val sweep = progress.value * 360f
            if (sweep > 0f) {
                drawArc(ringColor, -90f, minOf(sweep, 180f), false, topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(7f, cap = StrokeCap.Round))
                if (sweep > 180f) drawArc(sealColor, 90f, sweep - 180f, false, topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(7f, cap = StrokeCap.Round))
            }
            // core glow as press builds
            if (progress.value > 0f) drawCircle(ringColor, radius = r * 0.82f * progress.value, center = c, alpha = 0.10f * progress.value)
            // fingerprint arcs — idle pulse keeps it alive
            val a = (0.35f + 0.65f * progress.value) * if (holding) 1f else idlePulse
            val iconR = r * 0.58f
            listOf(0.32f, 0.55f, 0.78f, 1f).forEachIndexed { i, f ->
                drawArc(ringColor, startAngle = 120f + i * 14f, sweepAngle = 290f - i * 26f, useCenter = false,
                    topLeft = Offset(c.x - iconR * f, c.y - iconR * f),
                    size = androidx.compose.ui.geometry.Size(iconR * f * 2, iconR * f * 2),
                    alpha = a, style = Stroke(4.2f, cap = StrokeCap.Round))
            }
            drawCircle(ringColor, radius = 5.5f, center = c, alpha = a)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
            Text(
                "${(progress.value * 100).roundToInt()}%",
                color = if (progress.value >= 1f) sealColor else ringColor,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            )
        }
        // hold interceptor
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            coroutineScope {
                                holding = true
                                val fill = launch { progress.animateTo(1f, tween(1500, easing = LinearEasing)) }
                                val taps = launch {
                                    while (true) { haptics.tick(); delay(120) }
                                }
                                val released = tryAwaitRelease()
                                holding = false
                                taps.cancel()
                                if (fill.isActive) {
                                    fill.cancel()
                                    if (released) launch { progress.animateTo(0f, tween(280)) } // early release = contract void
                                } else if (progress.value >= 1f) {
                                    haptics.slam()
                                    onHoldComplete()
                                }
                            }
                        },
                    )
                },
        )
    }
}

// ── 7. HORIZONTAL WHEEL PICKER — snap-to-center value reel ──────────────────

private fun LazyListState.centerIndex(): Int {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return 0
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - mid) }?.index ?: 0
}

@Composable
fun WheelRow(
    label: String,
    unit: String,
    range: IntRange,
    value: Int,
    onValue: (Int) -> Unit,
    accent: Color = ElectricBlue,
) {
    val state = rememberLazyListState()
    val items = remember(range) { range.toList() }
    LaunchedEffect(Unit) { state.scrollToItem((value - range.first).coerceIn(0, items.lastIndex)) }
    LaunchedEffect(state) { snapshotFlow { state.centerIndex() }.collect { onValue(items[it]) } }
    val centerIdx by remember { derivedStateOf { state.centerIndex() } }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.width(6.dp))
            Text(unit, fontSize = 10.sp, color = accent.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("${items[centerIdx]}", color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            val sidePad = (maxWidth - 56.dp) / 2
            LazyRow(
                state = state,
                flingBehavior = rememberSnapFlingBehavior(state),
                contentPadding = PaddingValues(horizontal = sidePad),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items) { i, v ->
                    val sel = i == centerIdx
                    Box(
                        Modifier.width(56.dp).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$v",
                            color = if (sel) accent else TextMuted.copy(alpha = 0.4f),
                            fontSize = if (sel) 20.sp else 14.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            // center rails
            Box(Modifier.align(Alignment.Center).width(56.dp).fillMaxHeight()) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.5f)))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.5f)))
            }
            // edge fades
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(48.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(GateBlack, Color.Transparent))))
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(48.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.Transparent, GateBlack))))
            }
        }
    }
}

// ── 8. ARCHETYPE CARD — target class with living neon border ────────────────

@Composable
fun ArchetypeCard(
    title: String,
    tagline: String,
    stat: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val inf = rememberInfiniteTransition(label = "archPulse$title")
    val pulse by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "archA$title")
    val borderA = if (selected) pulse else 0.22f
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.neonGlow(accent, alpha = 0.16f) else Modifier)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceDark.copy(alpha = 0.72f))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                brush = Brush.horizontalGradient(listOf(accent.copy(alpha = borderA), accent.copy(alpha = borderA * 0.25f))),
                shape = RoundedCornerShape(18.dp),
            )
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = if (selected) accent else TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(tagline, color = TextMuted, fontSize = 12.sp)
                }
                if (selected) {
                    Text("SELECTED", color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, modifier = Modifier.graphicsLayer { this.alpha = pulse })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stat, color = accent.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── 9. HOLO STATUS PANEL — floating system readout ───────────────────────────

@Composable
fun HoloStatusPanel(status: String, accent: Color = ElectricBlue, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "holo")
    val scan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "holoScan")
    val glow by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "holoGlow")
    Box(modifier.width(252.dp).height(86.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 14.dp.toPx()
            drawRoundRect(accent.copy(alpha = 0.05f + 0.05f * glow), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            drawRoundRect(accent, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                alpha = 0.35f + 0.30f * glow, style = Stroke(2f))
            val y = size.height * scan
            drawRect(accent, topLeft = Offset(0f, (y - 10f).coerceAtLeast(0f)), size = androidx.compose.ui.geometry.Size(size.width, 20f), alpha = 0.08f)
            val c = accent.copy(alpha = 0.85f * glow); val l = 16f; val sw = 3f
            val W = size.width; val H = size.height
            drawLine(c, Offset(0f, 0f), Offset(l, 0f), sw); drawLine(c, Offset(0f, 0f), Offset(0f, l), sw)
            drawLine(c, Offset(W, 0f), Offset(W - l, 0f), sw); drawLine(c, Offset(W, 0f), Offset(W, l), sw)
            drawLine(c, Offset(0f, H), Offset(l, H), sw); drawLine(c, Offset(0f, H), Offset(0f, H - l), sw)
            drawLine(c, Offset(W, H), Offset(W - l, H), sw); drawLine(c, Offset(W, H), Offset(W, H - l), sw)
        }
        Text(status, color = accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}

// ── 10. S-RANK GATE KEY BUTTON — metallic slab, breathing neon frame ─────────

@Composable
fun GateKeyButton(
    text: String,
    modifier: Modifier = Modifier,
    subtext: String? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    accent: Color = ElectricBlue,
    onClick: () -> Unit,
) {
    val inf = rememberInfiniteTransition(label = "gateKey")
    val pulse by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "gkPulse")
    val sweep by inf.animateFloat(-0.2f, 1.2f, infiniteRepeatable(tween(2100, easing = LinearEasing)), label = "gkSweep")
    val metal = remember { Brush.verticalGradient(listOf(Color(0xFF1B212C), Color(0xFF10141B), Color(0xFF171D27))) }
    val frameAlpha = if (enabled) pulse else 0.15f
    Box(
        modifier
            .then(if (enabled) Modifier.neonGlow(accent, alpha = 0.14f) else Modifier)
            .clip(RoundedCornerShape(15.dp))
            .background(metal)
            .border(1.5.dp, Brush.horizontalGradient(listOf(accent.copy(alpha = frameAlpha), accent.copy(alpha = frameAlpha * 0.3f), accent.copy(alpha = frameAlpha))), RoundedCornerShape(15.dp))
            .pointerInput(enabled, loading) { if (enabled && !loading) detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val x = size.width * sweep
                drawRect(accent, topLeft = Offset((x - 46f).coerceAtLeast(0f), 0f), size = androidx.compose.ui.geometry.Size(46f, size.height), alpha = 0.6f)
                drawRect(accent.copy(alpha = 0.2f), size = size)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = if (enabled) TextPrimary else TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
            if (subtext != null) Text(subtext, color = accent.copy(alpha = frameAlpha), fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        }
    }
}

// ── 11. FLASH RIPPLE — white→electric detonation on contract seal ────────────

@Composable
fun FlashRipple(trigger: Int, color: Color = ElectricBlue) {
    if (trigger <= 0) return
    val a = remember { Animatable(0f) }
    LaunchedEffect(trigger) { a.snapTo(0f); a.animateTo(1f, tween(850, easing = LinearEasing)) }
    Canvas(Modifier.fillMaxSize()) {
        val maxR = kotlin.math.sqrt(size.width * size.width + size.height * size.height) / 2f
        drawCircle(
            color = lerp(Color.White, color, a.value),
            radius = (maxR * a.value).coerceAtLeast(0.01f),
            center = Offset(size.width / 2f, size.height / 2f),
            alpha = (1f - a.value) * 0.85f,
        )
    }
}

// ── 12. LEGAL FOOTER — backend-linked protocols line ─────────────────────────

@Composable
fun LegalFooter(onTerms: () -> Unit, onPrivacy: () -> Unit) {
    val annotated = buildAnnotatedString {
        append("By entering, you accept The System's Protocols\n")
        pushStringAnnotation(tag = "legal", annotation = "terms")
        withStyle(SpanStyle(color = ElectricBlue)) { append("Terms of Service") }
        pop()
        append("  ·  ")
        pushStringAnnotation(tag = "legal", annotation = "privacy")
        withStyle(SpanStyle(color = ElectricBlue)) { append("Privacy Policy") }
        pop()
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 17.sp),
        textAlign = TextAlign.Center,
        onClick = { off ->
            annotated.getStringAnnotations("legal", off, off).firstOrNull()?.let {
                if (it.item == "terms") onTerms() else onPrivacy()
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
}
