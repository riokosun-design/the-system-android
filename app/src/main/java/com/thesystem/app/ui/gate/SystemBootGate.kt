package com.thesystem.app.ui.gate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val AWAKENING_TEXTS = listOf(
    "Is there a version of yourself you dream of becoming?",
    "Are you willing to face your own shadows to grow?",
    "Are you ready to lock in?",
)

private val WELCOME_TEXTS = listOf(
    "Welcome to The System!",
    "Your determination to level up in real life\nhas been acknowledged.",
    "Time to unlock your hidden potential.",
)

/**
 * THE SYSTEM ACCESS GATE — the cinematic that plays on EVERY cold open.
 * Acts: TEXT_FADE → FINGERPRINT_HOLD (haptic charge ritual + screen shake) → WELCOME → AUTH_SYNC.
 * Phase machine lives in [BootGateViewModel]; every frame is Canvas/graphicsLayer (60/120fps-safe, no sleeps).
 */
@Composable
fun SystemBootGate(onFinished: () -> Unit, vm: BootGateViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val scope = rememberCoroutineScope()
    val (boom, fireBoom) = rememberEffectSignal()
    val shake = remember { Animatable(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(VoidBlack)
            .graphicsLayer { translationX = shake.value * 26.dp.toPx() }, // screen-shake runs on the render thread
    ) {
        when (s.phase) {
            SplashPhase.TEXT_FADE -> AwakeningPhase(s.gateArtUrl, s.gateArtAlpha) { vm.setPhase(SplashPhase.FINGERPRINT_HOLD) }
            SplashPhase.FINGERPRINT_HOLD -> FingerprintPhase(
                onLocked = {
                    fireBoom()
                    haptics.slam()
                    scope.launch {
                        shake.animateTo(
                            0f,
                            keyframes {
                                durationMillis = 460
                                0f at 0
                                -1f at 50
                                1f at 110
                                -0.7f at 180
                                0.6f at 250
                                -0.3f at 320
                                0f at 460
                            },
                        )
                    }
                    vm.setPhase(SplashPhase.WELCOME)
                },
            )
            SplashPhase.WELCOME -> WelcomePhase { vm.setPhase(SplashPhase.AUTH_SYNC) }
            SplashPhase.AUTH_SYNC -> AuthSyncPhase(s, vm, onFinished)
        }
        // explosive neon ripple at the moment of lock-in
        LevelUpShockwave(boom, color = ElectricBlue)
    }
}

// ── PHASE 1: THE AWAKENING ─ fading texts over the monarch's silhouette ═════

@Composable
private fun AwakeningPhase(artUrl: String?, artAlpha: Float, onDone: () -> Unit) {
    var idx by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        AWAKENING_TEXTS.indices.forEach { i ->
            idx = i
            visible = true
            delay(2450)   // 1500ms fadeIn + ~1s to actually read it
            visible = false
            delay(1600)   // 1500ms fadeOut + a beat of darkness
        }
        onDone()
    }
    Box(Modifier.fillMaxSize()) {
        if (artUrl != null) {
            AsyncImage(model = artUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = artAlpha)
        } else {
            MonarchSilhouette(Modifier.fillMaxSize())
        }
        AmbientMotes(color = NeonPurple, count = 16, seed = 11)
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(1500)),
            exit = fadeOut(tween(1500)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                AWAKENING_TEXTS[idx],
                color = ElectricBlue,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 34.dp),
            )
        }
    }
}

/** Faint pulsing silhouette of the shadow monarch — pure Canvas, zero assets. */
@Composable
private fun MonarchSilhouette(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "monarch")
    val pulse by inf.animateFloat(0.14f, 0.34f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "monarchA")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2f
        drawCircle(NeonPurple.copy(alpha = pulse * 0.45f), radius = w * 0.40f, center = Offset(cx, h * 0.40f))
        val p = Path().apply {
            moveTo(cx, h * 0.16f)                                   // crown peak
            lineTo(cx - w * 0.05f, h * 0.24f)
            lineTo(cx - w * 0.13f, h * 0.20f)                       // left spike
            lineTo(cx - w * 0.14f, h * 0.30f)
            lineTo(cx - w * 0.24f, h * 0.24f)                       // left horn
            lineTo(cx - w * 0.22f, h * 0.36f)
            lineTo(cx - w * 0.17f, h * 0.44f)                       // jaw
            lineTo(cx - w * 0.24f, h * 0.58f)                       // shoulder
            lineTo(cx - w * 0.30f, h * 0.95f)                       // cloak edge
            lineTo(cx + w * 0.30f, h * 0.95f)
            lineTo(cx + w * 0.24f, h * 0.58f)
            lineTo(cx + w * 0.17f, h * 0.44f)
            lineTo(cx + w * 0.22f, h * 0.36f)
            lineTo(cx + w * 0.24f, h * 0.24f)
            lineTo(cx + w * 0.14f, h * 0.30f)
            lineTo(cx + w * 0.13f, h * 0.20f)
            lineTo(cx + w * 0.05f, h * 0.24f)
            close()
        }
        drawPath(p, Color(0xFF04060B).copy(alpha = 0.94f))
        // electric rim-light threading the crown
        drawLine(ElectricBlue.copy(alpha = pulse), Offset(cx - w * 0.13f, h * 0.20f), Offset(cx, h * 0.16f), strokeWidth = 2.5f)
        drawLine(ElectricBlue.copy(alpha = pulse), Offset(cx, h * 0.16f), Offset(cx + w * 0.13f, h * 0.20f), strokeWidth = 2.5f)
        // the cold stare
        val eyeA = (pulse + 0.35f).coerceAtMost(0.85f)
        drawCircle(ElectricBlue.copy(alpha = eyeA), radius = 5f, center = Offset(cx - w * 0.055f, h * 0.335f))
        drawCircle(ElectricBlue.copy(alpha = eyeA), radius = 5f, center = Offset(cx + w * 0.055f, h * 0.335f))
    }
}

// ── PHASE 2: THE LOCK-IN ─ tap & hold fingerprint; release early = reset ════

@Composable
private fun FingerprintPhase(onLocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hf = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (holding) 1f else 0.55f, SystemMotion.snap, label = "fpGlow")

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Are you ready to lock in?",
            color = ElectricBlue, fontSize = 24.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "WARNING — You've seen the path ahead.\nTap and hold to lock in.",
            color = WarningAmber, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(44.dp))
        Box(
            Modifier
                .size(132.dp)
                .pressScale(0.96f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        holding = true
                        val charge = scope.launch { progress.animateTo(1f, tween(1500, easing = LinearEasing)) }
                        // continuous LongPress rumble while the charge builds
                        val rumble = scope.launch {
                            while (isActive) {
                                runCatching { hf.performHapticFeedback(HapticFeedbackType.LongPress) }
                                delay(140)
                            }
                        }
                        waitForUpOrCancellation()
                        holding = false
                        charge.cancel(); rumble.cancel()
                        if (progress.value >= 0.999f) onLocked()
                        else scope.launch { progress.animateTo(0f, tween(260, easing = SystemMotion.Emphasize)) } // released early → reset
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val r = size.minDimension / 2f - stroke
                drawCircle(Color(0x2200F0FF), radius = r, style = Stroke(stroke))
                drawArc(ElectricBlue, startAngle = -90f, sweepAngle = 360f * progress.value, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(ElectricBlue.copy(alpha = (0.10f + 0.22f * progress.value) * glow), radius = r - stroke * 2.2f)
            }
            Icon(Icons.Default.Fingerprint, contentDescription = "Hold to lock in", tint = ElectricBlue.copy(alpha = glow), modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("${(progress.value * 100).toInt()}%", color = HunterGold, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

// ── PHASE 3: THE SYSTEM WELCOME ─ deep blue radial, texts fall in sequence ══

@Composable
private fun WelcomePhase(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(550); step = 1
        delay(1250); step = 2
        delay(1250); step = 3
        delay(1700); onDone()
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF0A2540), Color(0xFF061527), VoidBlack), radius = 1500f)
        )
    ) {
        AmbientMotes(color = ElectricBlue, count = 20, seed = 4)
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WELCOME_TEXTS.forEachIndexed { i, line ->
                AnimatedVisibility(
                    visible = step > i,
                    enter = fadeIn(tween(900)) + slideInVertically(SystemMotion.medium) { it / 3 },
                ) {
                    Text(
                        line,
                        color = if (i == 0) ElectricBlue else TextPrimary,
                        fontSize = if (i == 0) 30.sp else 17.sp,
                        fontWeight = if (i == 0) FontWeight.Black else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = if (i == 0) 36.sp else 26.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// ── PHASE 4: HOLOGRAPHIC AUTH & DATA SYNC ═══════════════════════════════════

@Composable
private fun BoxScope.AuthSyncPhase(s: BootGateState, vm: BootGateViewModel, onFinished: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberSystemHaptics()

    // returning hunter: the gate recognizes them → sync ritual → straight in
    LaunchedEffect(s.authResolved, s.authenticated) {
        if (s.authResolved && s.authenticated) vm.syncThrough(onFinished)
    }

    s.gateArtUrl?.let {
        AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = s.gateArtAlpha)
    }
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Jin-Woo-at-the-console moment: character art above, holographic HUD below
        if (s.gateArtUrl == null) MonarchSilhouette(Modifier.fillMaxWidth().height(220.dp))
        HoloPanel(label = if (s.syncing || s.authenticated) "SAVING DATA…" else "LINK REQUIRED")
        Spacer(Modifier.height(18.dp))
        Text("Sign in to save your data", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text(
            "One account. One hunter. Your grind survives every device.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        when {
            !s.authResolved -> Text("CONTACTING THE SYSTEM…", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            !s.authenticated -> NeonButton(
                "CONTINUE WITH GOOGLE",
                { haptics.select(); vm.signInWithGoogle(context, onFinished) },
                Modifier.fillMaxWidth().height(52.dp),
                enabled = !s.syncing,
            )
        }
        s.authError?.let {
            Spacer(Modifier.height(10.dp))
            RestrictionBanner(it)
        }
    }

    // glassmorphism sync modal: dimmed world, frosted card, honest spinner
    if (s.syncing) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        GlowCard(
            glow = ElectricBlue, pulse = true,
            modifier = Modifier.align(Alignment.Center).widthIn(max = 300.dp).fillMaxWidth(0.8f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = ElectricBlue, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Text("Syncing your data…", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Floating holographic HUD: pulsing frame, corner ticks, traveling scanline. */
@Composable
private fun HoloPanel(label: String) {
    val inf = rememberInfiniteTransition(label = "holo")
    val glow by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "holoGlow")
    val scan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "holoScan")
    Box(Modifier.width(264.dp).height(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 14.dp.toPx()
            drawRoundRect(ElectricBlue.copy(alpha = 0.05f + 0.09f * glow), size = size, cornerRadius = CornerRadius(r))
            drawRoundRect(
                Brush.linearGradient(listOf(ElectricBlue.copy(alpha = 0.7f * glow), NeonPurple.copy(alpha = 0.35f * glow))),
                size = size, cornerRadius = CornerRadius(r), style = Stroke(2.5f),
            )
            // traveling scanline — the hologram is ALIVE
            val y = size.height * scan
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, ElectricBlue.copy(alpha = 0.20f), Color.Transparent)),
                topLeft = Offset(0f, (y - 14f).coerceAtLeast(0f)), size = Size(size.width, 28f),
            )
            // corner ticks
            val c = ElectricBlue.copy(alpha = 0.9f * glow)
            val l = 22f; val sw = 4f; val W = size.width; val H = size.height
            drawLine(c, Offset(0f, 0f), Offset(l, 0f), sw); drawLine(c, Offset(0f, 0f), Offset(0f, l), sw)
            drawLine(c, Offset(W, 0f), Offset(W - l, 0f), sw); drawLine(c, Offset(W, 0f), Offset(W, l), sw)
            drawLine(c, Offset(0f, H), Offset(l, H), sw); drawLine(c, Offset(0f, H), Offset(0f, H - l), sw)
            drawLine(c, Offset(W, H), Offset(W - l, H), sw); drawLine(c, Offset(W, H), Offset(W, H - l), sw)
        }
        Text(label, color = ElectricBlue, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}
