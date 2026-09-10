package com.thesystem.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.R
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.CrimsonRed
import com.thesystem.app.core.theme.ElectricBlue
import com.thesystem.app.core.theme.GridLine
import com.thesystem.app.core.theme.HunterGold
import com.thesystem.app.core.theme.NeonPurple
import com.thesystem.app.core.theme.SurfaceDark
import com.thesystem.app.core.theme.SurfaceHigh
import com.thesystem.app.core.theme.TextMuted
import com.thesystem.app.core.theme.TextPrimary
import com.thesystem.app.core.theme.VenomGreen
import com.thesystem.app.core.ui.AnimatedCounter
import com.thesystem.app.core.ui.SystemHaptics
import com.thesystem.app.core.ui.rememberSystemHaptics
import com.thesystem.app.ui.splash.Archetype
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════════════════
// THE AWAKENING FLOW — 27 cinematic pages · 5 phases · one continuous ritual.
//
//   PHASE 1 · SYSTEM INTRODUCTION   pages 01–06  (information only)
//   PHASE 2 · VESSEL INTAKE         pages 07–11  (name/age/height/weight/class)
//   PHASE 3 · POWER AWAKENING       pages 12–18  (stats + graphs + rank reveal)
//   PHASE 4 · 90-DAY TRANSFORMATION pages 19–24  (motivation + rewards honesty)
//   PHASE 5 · SYSTEM CONTRACT       pages 25–27  (consequences → hold → gate)
//
// Rules baked in: reuses OnboardingGate primitives (pure Canvas/Animatable),
// OnboardingViewModel contract (username gate → claim → metrics → finish),
// SystemMath for POWER composition & rank truth. Zero backend changes.
// Perf: graphicsLayer-only motion, ≤1 extra Canvas per page, 60fps on budget.
// ═══════════════════════════════════════════════════════════════════════════

// ── Baseline activity (vessel intake, cinematic math flavor) ────────────────
enum class Activity(val label: String, val sub: String, val hardWork: Double) {
    SEDENTARY("Resting Vessel", "little training history", 0.6),
    STEADY("Steady", "trains sometimes", 1.0),
    RELENTLESS("Relentless", "near-daily grind", 1.4),
}

/** Cinematic vessel readings — NOT progression logic. Formal numbers live in SystemMath;
 *  POWER below composes SystemMath.shape (FORM_BASE × style × hard work) at XP 0, Form 1. */
data class VesselStats(
    val str: Int, val vit: Int, val agi: Int, val per: Int,
    val power: Double, val hardWork: Double, val style: SystemMath.CombatStyle,
)

private fun computeVesselStats(age: Int, heightCm: Float, weightKg: Float, arch: Archetype, act: Activity): VesselStats {
    val youth = (30 - age).coerceAtLeast(0)
    val mass = (weightKg - 62f)
    var str = 12 + mass * 0.22f + (heightCm - 165f) * 0.10f
    var vit = 13 + act.hardWork * 4f + (mass * 0.06f)
    var agi = 15 + youth * 0.35f + (72f - weightKg) * 0.16f
    var per = 14 + (age - 13) * 0.15f + act.hardWork * 1.5f
    when (arch) {
        Archetype.SHADOW_MONARCH -> { str += 4f; per += 2f; agi += 1f }
        Archetype.WIND_WALKER -> { agi += 5f; per += 1f; vit += 1f }
        Archetype.TITAN -> { str += 2f; vit += 5f; agi -= 1f }
    }
    val style = when (arch) {
        Archetype.SHADOW_MONARCH -> SystemMath.CombatStyle.BERSERKER
        Archetype.WIND_WALKER -> SystemMath.CombatStyle.ASSASSIN
        Archetype.TITAN -> SystemMath.CombatStyle.TANK
    }
    val power = (SystemMath.FORM_BASE[0] * 100.0) * style.multiplier * act.hardWork
    return VesselStats(
        str = str.roundToInt().coerceIn(8, 34), vit = vit.roundToInt().coerceIn(8, 34),
        agi = agi.roundToInt().coerceIn(8, 34), per = per.roundToInt().coerceIn(8, 34),
        power = (power * 10.0).roundToInt() / 10.0,
        hardWork = act.hardWork, style = style,
    )
}

// ── ANIMATION KIT ────────────────────────────────────────────────────────────

/** Per-page master clock: 0→1 in 1.15s on entry. Every page choreographs from it. */
@Composable
private fun rememberPageClock(): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(1150, easing = FastOutSlowInEasing)) }
    return a.value
}

/** Timeline segment: 0 before [at], ramps to 1 across [span]. */
private fun seg(clock: Float, at: Float, span: Float = 0.22f): Float =
    ((clock - at) / span).coerceIn(0f, 1f)

/** Fade + rise reveal driven by a precomputed segment value. */
private fun Modifier.appear(c: Float, dy: Dp = 26.dp): Modifier = this.graphicsLayer {
    alpha = c
    translationY = (1f - c) * dy.toPx()
}

/** Scale-in pop driven by a segment value (0.86 → 1.0). */
private fun Modifier.popIn(c: Float): Modifier = this.graphicsLayer {
    val s = 0.86f + 0.14f * c
    alpha = c; scaleX = s; scaleY = s
}

private fun phaseOf(page: Int): Int = when (page) {
    in 1..6 -> 1; in 7..11 -> 2; in 12..18 -> 3; in 19..24 -> 4; else -> 5
}

// ══ HOST — page state machine + cinematic transitions ═══════════════════════

@Composable
fun AwakeningFlowScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(1) }
    var archetypeName by rememberSaveable { mutableStateOf(Archetype.SHADOW_MONARCH.name) }
    var activityName by rememberSaveable { mutableStateOf(Activity.STEADY.name) }
    val archetype = Archetype.valueOf(archetypeName)
    val activity = Activity.valueOf(activityName)
    val stats = remember(ui.age, ui.heightCm, ui.weightKg, archetypeName, activityName) {
        computeVesselStats(ui.age, ui.heightCm, ui.weightKg, archetype, activity)
    }
    val advance: () -> Unit = { haptics.tick(); page = (page + 1).coerceAtMost(27) }

    // Google seal → finalize the contract through the existing VM pipeline
    LaunchedEffect(ui.signedInProfile != null) {
        if (ui.signedInProfile != null) vm.finish(onDone)
    }

    SystemBackdrop {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (fadeIn(tween(540)) + slideInHorizontally(tween(540, easing = FastOutSlowInEasing)) { it / 4 } + scaleIn(initialScale = 0.965f, animationSpec = tween(540)))
                    .togetherWith(fadeOut(tween(400)) + slideOutHorizontally(tween(400, easing = FastOutSlowInEasing)) { -it / 5 } + scaleOut(targetScale = 1.03f, animationSpec = tween(400)))
            },
            label = "awakening",
        ) { p ->
            androidx.compose.runtime.key(p) {
                val clock = rememberPageClock()
                when (p) {
                    1 -> P01_SystemInit(clock, advance)
                    2 -> P02_NoMercy(clock, advance)
                    3 -> P03_LifeRpg(clock, advance)
                    4 -> P04_ProofOfGrind(clock, advance)
                    5 -> P05_Territory(clock, advance)
                    6 -> P06_TheSystem(clock) { haptics.slam(); page = 7 }
                    7 -> P07_Name(ui, vm, advance)
                    8 -> P08_Age(ui, vm, clock, advance)
                    9 -> P09_Height(ui, vm, clock, advance)
                    10 -> P10_Weight(ui, vm, clock, advance)
                    11 -> P11_Archetype(archetype, { archetypeName = it.name }, activity, { activityName = it.name }, {
                        vm.setGoal(archetype.goal); haptics.select(); page = 12
                    })
                    12 -> P12_Biometric(ui, archetype, activity, clock, advance)
                    13 -> P13_Str(stats, clock, advance)
                    14 -> P14_Vit(stats, clock, advance)
                    15 -> P15_Agi(stats, clock, advance)
                    16 -> P16_Per(stats, clock, advance)
                    17 -> P17_Power(stats, clock, advance)
                    18 -> P18_Rank(stats, clock, advance)
                    19 -> P19_Day1to30(clock, advance)
                    20 -> P20_Day31to60(clock, advance)
                    21 -> P21_Day61to90(stats, clock, advance)
                    22 -> P22_LifeGame(clock, advance)
                    23 -> P23_Conquer(clock, advance)
                    24 -> P24_Rewards(clock, advance)
                    25 -> P25_Consequences(clock, advance)
                    26 -> P26_Contract(haptics) { page = 27 }
                    27 -> P27_FinalGate(ui, vm, haptics, context)
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }
        // ── HUD chrome: phase + page counter, quiet mono ─────────────────────
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PHASE ${phaseOf(page)}/5",
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = ElectricBlue.copy(alpha = 0.65f), letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "PAGE %02d/27".format(page),
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted.copy(alpha = 0.6f), letterSpacing = 2.sp,
            )
        }
    }
}



// ── SHARED PAGE CHROME ───────────────────────────────────────────────────────

/** Info-page chrome: full-screen tap-to-continue + bottom pulsing hint. */
@Composable
private fun TapNextLayer(onTap: () -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 26.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val inf = rememberInfiniteTransition(label = "tapHint")
        val a by inf.animateFloat(0.25f, 0.85f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "tapHintA")
        Text(
            "TAP TO CONTINUE ▸",
            color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
            modifier = Modifier.graphicsLayer { alpha = a },
        )
    }
}

/** Cinematic artwork treatment: desaturated art + darkness + vignette + slow zoom + light sweep.
 *  Visual prominence stays ~10–16% — text always wins. */
@Composable
private fun CinematicArt(res: Int, clock: Float, alpha: Float = 0.5f) {
    val inf = rememberInfiniteTransition(label = "artLife$res")
    val sweepT by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(6400, easing = LinearEasing)), label = "artSweep$res")
    val artAlpha = seg(clock, 0f, 0.55f) // image reveals from darkness
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha * artAlpha
                    val s = 1f + 0.07f * clock // slow cinematic push-in
                    scaleX = s; scaleY = s
                },
        )
        // darkness plates: heavier where text lives (top + bottom)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to GateBlack.copy(alpha = 0.94f),
                    0.42f to GateBlack.copy(alpha = 0.42f),
                    0.72f to GateBlack.copy(alpha = 0.62f),
                    1f to GateBlack,
                )
            )
        )
        // vignette
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(0.55f to Color.Transparent, 1f to GateBlack, radius = 1400f)
            )
        )
        // occasional diagonal light sweep — embedded, not wallpaper
        Canvas(Modifier.fillMaxSize()) {
            val bandW = size.width * 0.35f
            val cx = kotlin.math.lerp(-bandW, size.width + bandW, sweepT)
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.045f), 1f to Color.Transparent),
                topLeft = Offset(cx - bandW / 2f, 0f), size = Size(bandW, size.height),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 1 — SYSTEM INTRODUCTION (pages 01–06)
// ═══════════════════════════════════════════════════════════════════════════

// ── PAGE 01 · SYSTEM INITIALIZATION ──────────────────────────────────────────
@Composable
private fun P01_SystemInit(clock: Float, advance: () -> Unit) {
    var lineIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val lines = 4
        for (i in 0..lines) { lineIdx = i; delay(620) }
        delay(1000); advance()
    }
    val inf = rememberInfiniteTransition(label = "radar")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(3800, easing = LinearEasing)), label = "radarRot")
    val pulse by inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "radarPulse")

    Box(Modifier.fillMaxSize()) {
        // central HUD radar
        Box(Modifier.align(Alignment.Center).size(190.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f - 6f
                drawCircle(ElectricBlue, radius = r, center = c, alpha = 0.16f * pulse, style = Stroke(1.4f))
                drawCircle(ElectricBlue, radius = r * 0.66f, center = c, alpha = 0.12f * pulse, style = Stroke(1.1f))
                drawCircle(ElectricBlue, radius = r * 0.33f, center = c, alpha = 0.10f * pulse, style = Stroke(1f))
                drawArc(
                    Brush.sweepGradient(0f to Color.Transparent, 0.82f to ElectricBlue.copy(alpha = 0.5f), 1f to ElectricBlue),
                    startAngle = rot, sweepAngle = 252f, useCenter = true, alpha = 0.30f,
                )
                drawCircle(ElectricBlue, radius = 4.5f, center = c, alpha = 0.4f + 0.6f * pulse)
            }
        }
        // boot log
        Column(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lineIdx >= 0) TerminalLine("SYSTEM INITIALIZING...", ElectricBlue)
            if (lineIdx >= 1) TerminalLine("DETECTING UNREGISTERED VESSEL...", TextMuted)
            if (lineIdx >= 2) TerminalLine("SCANNING...", TextMuted)
            if (lineIdx >= 3) TerminalLine("SIGNAL DETECTED.", VenomGreen)
            Spacer(Modifier.height(46.dp))
        }
    }
}

// ── PAGE 02 · THE WORLD HAS NO MERCY ─────────────────────────────────────────
@Composable
private fun P02_NoMercy(clock: Float, advance: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CinematicArt(R.drawable.onb_nomer, clock)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(64.dp))
            Text(
                "SYSTEM MESSAGE",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.15f)),
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.appear(seg(clock, 0.28f))) {
                GlitchRevealText("THE WORLD HAS", color = TextPrimary, fontSize = 30)
            }
            Box(Modifier.appear(seg(clock, 0.38f))) {
                GlitchRevealText("NO MERCY.", color = ElectricBlue, fontSize = 30, delayMs = 180)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "Nobody is coming to save you.\nNo coach. No audience. No excuses.",
                style = MaterialTheme.typography.bodyLarge, color = TextMuted, lineHeight = 24.sp,
                modifier = Modifier.appear(seg(clock, 0.55f)),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "But something watched you long enough\n…and decided you might be worth testing.",
                style = MaterialTheme.typography.bodyLarge, color = TextPrimary, lineHeight = 24.sp,
                modifier = Modifier.appear(seg(clock, 0.72f)),
            )
        }
        TapNextLayer(advance)
    }
}

// ── PAGE 03 · REAL LIFE → RPG ────────────────────────────────────────────────
@Composable
private fun P03_LifeRpg(clock: Float, advance: () -> Unit) {
    val stages = listOf(
        "WORKOUT" to TextMuted,
        "VERIFICATION" to ElectricBlue,
        "XP" to HunterGold,
        "LEVEL" to NeonPurple,
        "RANK" to VenomGreen,
    )
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(64.dp))
            Text("HOW IT WORKS", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.12f)))
            Spacer(Modifier.height(10.dp))
            Text("Real life is the dungeon.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
                modifier = Modifier.appear(seg(clock, 0.2f)))
            Spacer(Modifier.height(40.dp))
            stages.forEachIndexed { i, (label, color) ->
                val c = seg(clock, 0.3f + i * 0.12f)
                Column(Modifier.appear(c, dy = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(label, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 2.sp)
                    }
                    if (i < stages.lastIndex) {
                        Text("↓", color = TextMuted.copy(alpha = 0.4f), fontSize = 16.sp, modifier = Modifier.padding(start = 1.dp))
                    }
                }
            }
        }
        TapNextLayer(advance)
    }
}

// ── PAGE 04 · PROOF OF GRIND ─────────────────────────────────────────────────
@Composable
private fun P04_ProofOfGrind(clock: Float, advance: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "scan$clock")
    val scanT by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2100, easing = LinearEasing)), label = "scanT")
    Box(Modifier.fillMaxSize()) {
        CinematicArt(R.drawable.onb_proof, clock)
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(64.dp))
            Text("PROOF OF GRIND", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.12f)))
            Spacer(Modifier.height(10.dp))
            Text("No rep counts\nunless THE SYSTEM sees it.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary, lineHeight = 30.sp,
                modifier = Modifier.appear(seg(clock, 0.22f)))
            Spacer(Modifier.height(28.dp))
            // fictional verification HUD
            Column(
                Modifier.popIn(seg(clock, 0.34f)).clip(RoundedCornerShape(14.dp)).background(SurfaceDark.copy(alpha = 0.82f))
                    .border(1.dp, ElectricBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(16.dp),
            ) {
                Box {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("REP 01", "REP 02", "REP 03").forEachIndexed { i, rep ->
                            val rc = seg(clock, 0.42f + i * 0.13f, 0.12f)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsLayer { alpha = rc }) {
                                Text(rep, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(if (rc >= 1f) "✓" else "·", color = VenomGreen, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    // traveling scanline across the panel
                    Canvas(Modifier.matchParentSize()) {
                        val y = size.height * scanT
                        drawRect(ElectricBlue.copy(alpha = 0.10f), topLeft = Offset(0f, y - 8f), size = Size(size.width, 16f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "VERIFIED",
                    fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = VenomGreen, letterSpacing = 4.sp,
                    modifier = Modifier.graphicsLayer { alpha = seg(clock, 0.82f, 0.15f) },
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Camera form-scanning. Zero uploads.", style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                modifier = Modifier.appear(seg(clock, 0.88f)))
        }
        TapNextLayer(advance)
    }
}

// ── PAGE 05 · TERRITORY ──────────────────────────────────────────────────────
@Composable
private fun P05_Territory(clock: Float, advance: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CinematicArt(R.drawable.onb_territory, clock, alpha = 0.62f)
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(64.dp))
            Text("TERRITORY PROTOCOL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.12f)))
            Spacer(Modifier.height(10.dp))
            Text("The real world is the map.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
                modifier = Modifier.appear(seg(clock, 0.22f)))
            Spacer(Modifier.height(26.dp))
            Canvas(
                Modifier.popIn(seg(clock, 0.3f)).fillMaxWidth().height(220.dp)
                    .clip(RoundedCornerShape(12.dp)).border(1.dp, GridLine, RoundedCornerShape(12.dp)),
            ) {
                drawRect(SurfaceDark.copy(alpha = 0.55f))
                val cols = 6; val rows = 4
                val cw = size.width / cols; val rh = size.height / rows
                for (i in 0..cols) drawLine(ElectricBlue.copy(alpha = 0.14f), Offset(i * cw, 0f), Offset(i * cw, size.height), 1f)
                for (j in 0..rows) drawLine(ElectricBlue.copy(alpha = 0.14f), Offset(0f, j * rh), Offset(size.width, j * rh), 1f)
                // cells ignite one by one — territory waking up under you
                val total = cols * rows
                for (idx in 0 until total) {
                    val t = seg(clock, 0.34f + idx * (0.5f / total), 0.1f)
                    if (t <= 0f) continue
                    val cx = idx % cols; val cy = idx / cols
                    val mine = idx % 7 == 0
                    drawRect(
                        (if (mine) HunterGold else ElectricBlue).copy(alpha = 0.22f * t),
                        topLeft = Offset(cx * cw + 3f, cy * rh + 3f), size = Size(cw - 6f, rh - 6f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Train inside a 1KM geofence.\nThe zone learns your name.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, lineHeight = 20.sp,
                modifier = Modifier.appear(seg(clock, 0.85f)))
        }
        TapNextLayer(advance)
    }
}

// ── PAGE 06 · THE SYSTEM — ecosystem reveal + BEGIN AWAKENING ────────────────
@Composable
private fun P06_TheSystem(clock: Float, onBegin: () -> Unit) {
    val pillars = listOf(
        "STATUS" to "your stats, quests, penalties",
        "FEED" to "the hunter wire",
        "ARENA" to "60-second camera duels",
        "MAP" to "territory conquest",
        "VAULT" to "wallet, forms, power",
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(60.dp))
        Text("SYSTEM OVERVIEW", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(8.dp))
        Text("What you are entering.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.18f)))
        Spacer(Modifier.height(28.dp))
        pillars.forEachIndexed { i, (name, sub) ->
            val c = seg(clock, 0.26f + i * 0.1f)
            Row(
                Modifier.appear(c, dy = 18.dp).fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▸", color = ElectricBlue, fontSize = 15.sp)
                Spacer(Modifier.width(12.dp))
                Text(name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary, letterSpacing = 2.sp)
                Spacer(Modifier.width(12.dp))
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.popIn(seg(clock, 0.82f, 0.18f))) {
            GateKeyButton(
                text = "BEGIN AWAKENING",
                subtext = "VESSEL INTAKE BEGINS",
                accent = ElectricBlue,
                enabled = seg(clock, 0.82f) >= 1f,
                onClick = onBegin,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 2 — VESSEL INTAKE (pages 07–11)
// ═══════════════════════════════════════════════════════════════════════════

// ── PAGE 07 · NAME ───────────────────────────────────────────────────────────
@Composable
private fun P07_Name(ui: OnboardingUiState, vm: OnboardingViewModel, advance: () -> Unit) {
    val clock = rememberPageClock()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(72.dp))
        Text("VESSEL IDENTIFICATION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        GlitchWithAppear(seg(clock, 0.2f), "IDENTIFY YOUR VESSEL")
        Spacer(Modifier.height(36.dp))
        Box(Modifier.appear(seg(clock, 0.34f))) {
            Column {
                BasicTextField(
                    value = ui.username,
                    onValueChange = { vm.onUsernameChanged(it) },
                    singleLine = true,
                    cursorBrush = SolidColor(ElectricBlue),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    textStyle = TextStyle(
                        color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (ui.username.isEmpty()) {
                            Text("vessel_tag", color = TextMuted.copy(alpha = 0.35f), fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(10.dp))
                // scanning underline
                val inf = rememberInfiniteTransition(label = "underScan")
                val sx by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "underScanX")
                Box(Modifier.fillMaxWidth().height(1.dp).background(ElectricBlue.copy(alpha = 0.25f))) {
                    // sweep the 35%-wide bar across the track: travel = (0.65/0.35)·barWidth
                    Box(Modifier.fillMaxWidth(0.35f).fillMaxHeight().graphicsLayer { translationX = sx * size.width * 1.86f }.background(ElectricBlue))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // the System answers as you type — realtime verification
        val status = when {
            ui.username.length < 3 -> "MINIMUM 3 CHARACTERS"
            ui.usernameChecking -> "SCANNING VESSEL TAG…"
            ui.usernameAvailable == true -> "TAG ACCEPTED · @${ui.username}"
            ui.usernameError != null -> ui.usernameError.uppercase()
            else -> "…"
        }
        val statusColor = when {
            ui.usernameAvailable == true && !ui.usernameChecking -> VenomGreen
            ui.usernameError != null -> CrimsonRed
            else -> TextMuted
        }
        Text(status, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = statusColor, letterSpacing = 1.5.sp,
            modifier = Modifier.appear(seg(clock, 0.45f)))
        Spacer(Modifier.weight(1f))
        GateKeyButton(
            text = "LOCK TAG ▸",
            subtext = if (ui.usernameAvailable == true) "@${ui.username} RESERVED FOR BINDING" else "AWAITING VALID TAG",
            accent = ElectricBlue,
            enabled = ui.usernameAvailable == true,
            onClick = advance,
            modifier = Modifier.fillMaxWidth().appear(seg(clock, 0.55f)),
        )
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun GlitchWithAppear(c: Float, text: String, color: Color = TextPrimary, size: Int = 26) {
    Box(Modifier.graphicsLayer { alpha = c }) { GlitchRevealText(text, color = color, fontSize = size) }
}

// ── PAGE 08 · AGE DETECTION (13–30 hard range) ───────────────────────────────
@Composable
private fun P08_Age(ui: OnboardingUiState, vm: OnboardingViewModel, clock: Float, advance: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(80.dp))
        Text("AGE DETECTION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        Text("How old is the vessel?", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.2f)))
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.appear(seg(clock, 0.3f))) {
            AnimatedCounter(
                target = ui.age.toLong(), color = ElectricBlue, fontSize = 84.sp, fontWeight = FontWeight.ExtraBold,
                format = { "$it" },
            )
            Spacer(Modifier.width(10.dp))
            Text("YRS", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.appear(seg(clock, 0.42f))) {
            WheelRow(label = "VESSEL AGE", unit = "LOCKED 13–30", range = 13..30, value = ui.age, onValue = { vm.setAge(it) })
        }
        Spacer(Modifier.weight(1f))
        ContinueBar(seg(clock, 0.6f), advance)
    }
}

// ── PAGE 09 · HEIGHT SCAN (animated ruler) ───────────────────────────────────
@Composable
private fun P09_Height(ui: OnboardingUiState, vm: OnboardingViewModel, clock: Float, advance: () -> Unit) {
    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        // animated ruler — ticks grow with clock, marker slides to value
        Canvas(Modifier.width(64.dp).fillMaxHeight().graphicsLayer { alpha = seg(clock, 0.2f, 0.5f) }) {
            val ticks = 45
            for (i in 0..ticks) {
                val y = size.height * (i / ticks.toFloat())
                val major = i % 5 == 0
                val w = if (major) 26.dp.toPx() else 12.dp.toPx()
                drawLine(
                    ElectricBlue.copy(alpha = if (major) 0.5f else 0.22f),
                    Offset(0f, y), Offset(w, y), strokeWidth = if (major) 3f else 2f,
                )
            }
            // marker at mapped height 140..210
            val frac = ((ui.heightCm - 140f) / 70f).coerceIn(0f, 1f)
            val my = size.height * (1f - frac)
            drawLine(ElectricBlue, Offset(0f, my), Offset(52.dp.toPx(), my), strokeWidth = 4f)
            drawCircle(ElectricBlue, radius = 7f, center = Offset(8f, my))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height(80.dp))
            Text("HEIGHT SCAN", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.1f)))
            Spacer(Modifier.height(12.dp))
            Text("Measure the frame.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
                modifier = Modifier.appear(seg(clock, 0.2f)))
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.appear(seg(clock, 0.3f))) {
                AnimatedCounter(target = ui.heightCm.toLong(), color = ElectricBlue, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, format = { "$it" })
                Spacer(Modifier.width(10.dp)); Text("CM", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            Spacer(Modifier.height(26.dp))
            Box(Modifier.appear(seg(clock, 0.42f))) {
                WheelRow(label = "HEIGHT", unit = "CM", range = 140..210, value = ui.heightCm.roundToInt(), onValue = { vm.setHeight(it.toFloat()) })
            }
            Spacer(Modifier.weight(1f))
            ContinueBar(seg(clock, 0.6f), advance)
        }
    }
}

// ── PAGE 10 · MASS DETECTION (animated digital gauge) ────────────────────────
@Composable
private fun P10_Weight(ui: OnboardingUiState, vm: OnboardingViewModel, clock: Float, advance: () -> Unit) {
    val needleFrac = ((ui.weightKg - 40f) / 110f).coerceIn(0f, 1f)
    val needle by androidx.compose.animation.core.animateFloatAsState(
        targetValue = needleFrac,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 220f),
        label = "gaugeNeedle",
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(80.dp))
        Text("MASS DETECTION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = HunterGold, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        Text("Weigh the armor.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.2f)))
        Spacer(Modifier.height(24.dp))
        // gauge
        Canvas(Modifier.popIn(seg(clock, 0.28f)).fillMaxWidth().height(150.dp)) {
            val c = Offset(size.width / 2f, size.height)
            val r = min(size.width / 2f, size.height) - 14f
            // arc track -180..0
            drawArc(ElectricBlue.copy(alpha = 0.15f), 180f, 180f, false, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(8f, cap = StrokeCap.Round))
            drawArc(ElectricBlue, 180f, 180f * needle, false, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(8f, cap = StrokeCap.Round))
            // ticks
            for (i in 0..12) {
                val ang = PI - (i / 12f) * PI
                val inner = r - 22f; val outer = r - 8f
                drawLine(
                    ElectricBlue.copy(alpha = 0.4f),
                    Offset(c.x + (inner * cos(ang)).toFloat(), c.y - (inner * sin(ang)).toFloat()),
                    Offset(c.x + (outer * cos(ang)).toFloat(), c.y - (outer * sin(ang)).toFloat()),
                    strokeWidth = 2.4f,
                )
            }
            // needle
            val na = PI - needle * PI
            drawLine(
                HunterGold, c,
                Offset(c.x + ((r - 30f) * cos(na)).toFloat(), c.y - ((r - 30f) * sin(na)).toFloat()),
                strokeWidth = 5f, cap = StrokeCap.Round,
            )
            drawCircle(HunterGold, radius = 8f, center = c)
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.align(Alignment.CenterHorizontally).appear(seg(clock, 0.36f))) {
            AnimatedCounter(target = ui.weightKg.toLong(), color = HunterGold, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, format = { "$it" })
            Spacer(Modifier.width(8.dp)); Text("KG", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.appear(seg(clock, 0.44f))) {
            WheelRow(label = "WEIGHT", unit = "KG", range = 40..150, value = ui.weightKg.roundToInt(), onValue = { vm.setWeight(it.toFloat()) })
        }
        Spacer(Modifier.weight(1f))
        ContinueBar(seg(clock, 0.6f), advance)
    }
}

@Composable
private fun ContinueBar(c: Float, advance: () -> Unit, label: String = "CONTINUE ▸") {
    GateKeyButton(
        text = label, subtext = "SCAN LOGGED", accent = ElectricBlue, enabled = c >= 1f, onClick = advance,
        modifier = Modifier.fillMaxWidth().appear(c),
    )
    Spacer(Modifier.height(26.dp))
}

// ── PAGE 11 · ARCHETYPE + ACTIVITY ───────────────────────────────────────────
@Composable
private fun P11_Archetype(
    archetype: Archetype, onArch: (Archetype) -> Unit,
    activity: Activity, onAct: (Activity) -> Unit,
    onContinue: () -> Unit,
) {
    val clock = rememberPageClock()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("CLASS ASSIGNMENT", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = NeonPurple, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        Text("Choose your discipline.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.2f)))
        Spacer(Modifier.height(24.dp))
        Archetype.entries.forEachIndexed { i, a ->
            Box(Modifier.appear(seg(clock, 0.3f + i * 0.09f)).padding(vertical = 6.dp)) {
                ArchetypeCard(
                    title = a.title, tagline = a.tagline, stat = a.stat,
                    accent = a.accent, selected = archetype == a, onClick = { onArch(a) },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("BASELINE ACTIVITY", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.55f)))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.appear(seg(clock, 0.62f))) {
            Activity.entries.forEach { act ->
                OnboardingChip(label = act.label, selected = activity == act, accent = ElectricBlue, onClick = { onAct(act) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(activity.sub, style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.appear(seg(clock, 0.66f)))
        Spacer(Modifier.height(26.dp))
        ContinueBar(seg(clock, 0.74f), onContinue, label = "ACCEPT CLASS ▸")
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 3 — POWER / STAT AWAKENING (pages 12–18)
// ═══════════════════════════════════════════════════════════════════════════

// ── PAGE 12 · BIOMETRIC ANALYSIS ─────────────────────────────────────────────
@Composable
private fun P12_Biometric(ui: OnboardingUiState, arch: Archetype, act: Activity, clock: Float, advance: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        for (i in 0..4) { step = i; delay(700) }
        delay(700); advance()
    }
    val rows = listOf(
        "TAG" to "@${ui.username.ifBlank { "unbound" }}",
        "AGE" to "${ui.age}",
        "HEIGHT" to "${ui.heightCm.roundToInt()} cm",
        "MASS" to "${ui.weightKg.roundToInt()} kg",
        "CLASS" to arch.title.uppercase(),
        "BASELINE" to act.label.uppercase(),
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(70.dp))
        Text("BIOMETRIC ANALYSIS", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.08f)))
        Spacer(Modifier.height(24.dp))
        rows.forEachIndexed { i, (k, v) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp).appear(seg(clock, 0.14f + i * 0.07f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(v, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(30.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step >= 0) TerminalLine("SCANNING...", ElectricBlue)
            if (step >= 1) TerminalLine("ANALYZING...", ElectricBlue)
            if (step >= 2) TerminalLine("CALIBRATING...", NeonPurple)
            if (step >= 3) TerminalLine("SYNCING...", HunterGold)
        }
        Spacer(Modifier.height(22.dp))
        // linear progress
        Box(Modifier.fillMaxWidth().height(3.dp).background(SurfaceHigh, RoundedCornerShape(2.dp))) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth((step / 4f).coerceIn(0f, 1f))
                    .background(Brush.horizontalGradient(listOf(ElectricBlue, NeonPurple)), RoundedCornerShape(2.dp)),
            )
        }
    }
}

// ── PAGES 13–16 · STR / VIT / AGI / PER charging meters ─────────────────────

@Composable
private fun StatChargePage(
    label: String, sub: String, value: Int, color: Color, clock: Float,
    durationMs: Int, advance: () -> Unit,
) {
    val charge = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300)
        charge.animateTo(value.toFloat(), tween(durationMs, easing = FastOutSlowInEasing))
        delay(720)
        advance()
    }
    val max = 34f
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(90.dp))
        Text("VESSEL STAT REVEAL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = color, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp), color = color,
            modifier = Modifier.popIn(seg(clock, 0.2f)))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.appear(seg(clock, 0.3f)))
        Spacer(Modifier.height(44.dp))
        // charging bar
        Box(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(SurfaceHigh)
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(7.dp)),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth((charge.value / max).coerceIn(0f, 1f)).clip(RoundedCornerShape(7.dp))
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.5f), color))),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "${charge.value.roundToInt()}",
            fontFamily = FontFamily.Monospace, fontSize = 44.sp, fontWeight = FontWeight.Black, color = color,
        )
    }
}

@Composable private fun P13_Str(s: VesselStats, clock: Float, advance: () -> Unit) =
    StatChargePage("STR", "raw output — what your frame can deliver", s.str, CrimsonRed, clock, 1900, advance)

@Composable private fun P14_Vit(s: VesselStats, clock: Float, advance: () -> Unit) =
    StatChargePage("VIT", "endurance — how long you burn before breaking", s.vit, VenomGreen, clock, 1600, advance)

@Composable private fun P15_Agi(s: VesselStats, clock: Float, advance: () -> Unit) =
    StatChargePage("AGI", "kinetic speed — how fast the vessel answers", s.agi, ElectricBlue, clock, 850, advance)

@Composable private fun P16_Per(s: VesselStats, clock: Float, advance: () -> Unit) =
    StatChargePage("PER", "precision — how sharply the vessel reads the field", s.per, NeonPurple, clock, 1300, advance)

// ── PAGE 17 · POWER CALCULATION (+ GRAPH 2: radial composition) ─────────────
@Composable
private fun P17_Power(stats: VesselStats, clock: Float, advance: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(62.dp))
        Text("POWER CALIBRATION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = HunterGold, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.08f)))
        Spacer(Modifier.height(22.dp))
        // equation blocks assemble
        val terms = listOf(
            "BASE ${SystemMath.FORM_BASE[0].toInt() * 100}" to ElectricBlue,
            "XP 0" to HunterGold,
            "× ${stats.style.label.uppercase()}" to NeonPurple,
            "× HARD WORK ${"%.1f".format(stats.hardWork)}" to VenomGreen,
        )
        terms.forEachIndexed { i, (t, c) ->
            val sc = seg(clock, 0.16f + i * 0.09f)
            Row(
                Modifier.appear(sc, dy = 16.dp).padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.12f)).border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(t, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("＝", color = HunterGold, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.appear(seg(clock, 0.5f)))
        // GRAPH 2 — radial power composition (not an X/Y chart)
        Box(
            Modifier.popIn(seg(clock, 0.48f, 0.2f)).fillMaxWidth().height(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            RadialPowerChart(stats, seg(clock, 0.52f, 0.45f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedCounter(
                    target = (stats.power * 10).toLong(), color = HunterGold, fontSize = 44.sp, fontWeight = FontWeight.Black,
                    format = { "%.1f".format(it / 10f) },
                )
                Text("POWER", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted, letterSpacing = 3.sp)
            }
        }
        // legend
        Row(
            Modifier.fillMaxWidth().appear(seg(clock, 0.75f)),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf("BASE" to ElectricBlue, "XP" to HunterGold, "STYLE" to NeonPurple, "WORK" to VenomGreen).forEach { (l, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(c, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ContinueBar(seg(clock, 0.82f), advance)
    }
}

/** GRAPH 2 — circular/segmented power composition, drawn as one Canvas, animated sweep. */
@Composable
private fun RadialPowerChart(stats: VesselStats, segV: Float) {
    val parts = remember(stats) {
        listOf(
            100f to ElectricBlue,
            8f to HunterGold,
            (kotlin.math.abs((stats.style.multiplier - 1.0) * 100).toFloat() + 6f) to NeonPurple,
            (kotlin.math.abs((stats.hardWork - 1.0) * 100).toFloat() + 6f) to VenomGreen,
        )
    }
    Canvas(Modifier.size(230.dp)) {
        val total = parts.sumOf { it.first.toDouble() }.toFloat()
        val r = size.minDimension / 2f - 12f
        val c = Offset(size.width / 2f, size.height / 2f)
        var angle = -90f
        for ((v, color) in parts) {
            val sweep = (v / total) * 360f * segV
            if (sweep <= 0.5f) { angle += 0f; continue }
            drawArc(
                color, startAngle = angle, sweepAngle = sweep - 3f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                style = Stroke(16f, cap = StrokeCap.Butt), alpha = 0.08f + 0.85f * segV,
            )
            angle += (v / total) * 360f
        }
        // spinning scanner hand
        val inf = angle + segV * 40f
        drawCircle(ElectricBlue.copy(alpha = 0.06f), radius = r * 0.72f, center = c)
        drawCircle(ElectricBlue.copy(alpha = 0.25f), radius = 4f, center = c)
        drawLine(ElectricBlue.copy(alpha = 0.35f), c, Offset(c.x + r * 0.72f * cos(inf * PI.toFloat() / 180f), c.y + r * 0.72f * sin(inf * PI.toFloat() / 180f)), 2f)
    }
}

// ── PAGE 18 · INITIAL RANK (+ GRAPH 1: segmented stat bars) ──────────────────
@Composable
private fun P18_Rank(stats: VesselStats, clock: Float, advance: () -> Unit) {
    val rank = SystemMath.rankFor(level = 1, missedDays = 0) // the truth: every vessel starts AVERAGE
    val darkPause = seg(clock, 0f, 0.2f)          // screen goes quiet first
    val reveal = seg(clock, 0.3f, 0.3f)           // then the pulse
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(70.dp))
        Text(
            "CALIBRATING RANK…",
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted, letterSpacing = 3.sp,
            modifier = Modifier.graphicsLayer { alpha = (1f - seg(clock, 0.22f, 0.12f)) * darkPause },
        )
        if (reveal > 0f) {
            Text("INITIAL RANK", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = ElectricBlue, letterSpacing = 4.sp,
                modifier = Modifier.graphicsLayer { alpha = reveal })
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.popIn(reveal)
                    .neonGlow(ElectricBlue, alpha = 0.18f)
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    rank.title,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 54.sp, letterSpacing = 4.sp),
                    color = TextPrimary,
                )
            }
            Text(
                "Every vessel begins equal. Decay decides the rest.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                modifier = Modifier.graphicsLayer { alpha = seg(clock, 0.62f) },
            )
            Spacer(Modifier.height(30.dp))
            // GRAPH 1 — animated segmented stat bars (RPG blocks, not X/Y)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.graphicsLayer { alpha = seg(clock, 0.6f) }) {
                StatBlockRow("STR", stats.str, CrimsonRed, seg(clock, 0.62f, 0.34f))
                StatBlockRow("VIT", stats.vit, VenomGreen, seg(clock, 0.68f, 0.34f))
                StatBlockRow("AGI", stats.agi, ElectricBlue, seg(clock, 0.74f, 0.34f))
                StatBlockRow("PER", stats.per, NeonPurple, seg(clock, 0.80f, 0.34f))
            }
        }
        Spacer(Modifier.weight(1f))
        ContinueBar(seg(clock, 0.9f), advance)
    }
}

/** GRAPH 1 — one RPG segmented stat bar: 10 blocks charging 0 → value. */
@Composable
private fun StatBlockRow(label: String, value: Int, color: Color, barSeg: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color, modifier = Modifier.width(34.dp))
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            val blocks = 12
            val lit = (value / 34f * blocks * barSeg).coerceIn(0f, blocks.toFloat())
            for (i in 0 until blocks) {
                val on = i < lit.toInt()
                Box(
                    Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (on) color else SurfaceHigh),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("$value", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 4 — 90-DAY TRANSFORMATION (pages 19–24)
// ═══════════════════════════════════════════════════════════════════════════

// ── PAGE 19 · DAY 01 → DAY 30 (foundation) ───────────────────────────────────
@Composable
private fun P19_Day1to30(clock: Float, advance: () -> Unit) {
    val mark = remember { Animatable(1f) }
    LaunchedEffect(Unit) { delay(350); mark.animateTo(30f, tween(1900, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(70.dp))
        Text("PHASE ONE · FOUNDATION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        Text("Day 01 → Day 30", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.2f)))
        Spacer(Modifier.height(34.dp))
        // timeline — the marker walks a month in front of you
        Box(Modifier.popIn(seg(clock, 0.3f))) {
            Column {
                Box(Modifier.fillMaxWidth().height(2.dp).background(SurfaceHigh)) {
                    Box(Modifier.fillMaxWidth((mark.value / 30f)).fillMaxHeight().background(ElectricBlue))
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Text("DAY %02d".format(mark.value.roundToInt().coerceAtLeast(1)), fontFamily = FontFamily.Monospace, color = ElectricBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("DAY 30", fontFamily = FontFamily.Monospace, color = TextMuted, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        listOf(
            "Daily quests stop being chores — they become reflex.",
            "Your first streak ignites. Missing starts to hurt.",
            "THE PENALTY ENGINE learns to fear your discipline.",
        ).forEachIndexed { i, line ->
            Text(line, style = MaterialTheme.typography.bodyLarge, color = TextMuted,
                modifier = Modifier.appear(seg(clock, 0.45f + i * 0.1f)).padding(vertical = 6.dp))
        }
        TapOverlayFreeSpace(advance)
    }
}

@Composable
private fun TapOverlayFreeSpace(advance: () -> Unit) = TapNextLayer(advance)

// ── PAGE 20 · DAY 31 → DAY 60 (discipline engine) ────────────────────────────
@Composable
private fun P20_Day31to60(clock: Float, advance: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(70.dp))
        Text("PHASE TWO · DISCIPLINE ENGINE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = NeonPurple, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        Text("Day 31 → Day 60", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.2f)))
        Spacer(Modifier.height(30.dp))
        // 30-cell streak grid ignites one by one
        Column(Modifier.popIn(seg(clock, 0.3f))) {
            val cells = 30
            val perRow = 6
            for (row in 0 until cells / perRow) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    for (c in 0 until perRow) {
                        val idx = row * perRow + c
                        val lit = seg(clock, 0.32f + idx * 0.012f, 0.04f) >= 1f
                        Box(
                            Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (lit) VenomGreen else SurfaceHigh),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        listOf(
            "Consistency becomes your identity, not your effort.",
            "Forms expose themselves. Power compounds.",
            "Other hunters start noticing the name.",
        ).forEachIndexed { i, line ->
            Text(line, style = MaterialTheme.typography.bodyLarge, color = TextMuted,
                modifier = Modifier.appear(seg(clock, 0.72f + i * 0.08f)).padding(vertical = 5.dp))
        }
        TapOverlayFreeSpace(advance)
    }
}

// ── PAGE 21 · DAY 61 → DAY 90 (the transformation) ───────────────────────────
@Composable
private fun P21_Day61to90(stats: VesselStats, clock: Float, advance: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CinematicArt(R.drawable.onb_transform, clock)
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(64.dp))
            Text("PHASE THREE · TRANSFORMATION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = HunterGold, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.14f)))
            Spacer(Modifier.height(12.dp))
            GlitchWithAppear(seg(clock, 0.24f), "DAY 90 MIRRORS\nARE RUTHLESS", size = 26)
            Spacer(Modifier.height(26.dp))
            // potential projection — honest framing
            listOf(
                "STR" to stats.str, "VIT" to stats.vit, "AGI" to stats.agi, "PER" to stats.per,
            ).forEachIndexed { i, (label, v) ->
                val ceiling = (v * 2.6f).roundToInt().coerceAtMost(99)
                Row(
                    Modifier.appear(seg(clock, 0.45f + i * 0.08f)).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted, modifier = Modifier.width(40.dp))
                    Text("$v", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = TextMuted)
                    Text("  →  ", fontFamily = FontFamily.Monospace, color = HunterGold)
                    AnimatedCounter(target = ceiling.toLong(), color = HunterGold, fontSize = 18.sp, fontWeight = FontWeight.Black, format = { "$it" })
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "POTENTIAL CEILING — earned only by showing up, daily.",
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted, letterSpacing = 1.6.sp,
                modifier = Modifier.appear(seg(clock, 0.85f)),
            )
        }
        TapNextLayer(advance)
    }
}

// ── PAGE 22 · YOUR LIFE BECOMES THE GAME ─────────────────────────────────────
@Composable
private fun P22_LifeGame(clock: Float, advance: () -> Unit) {
    val flow = listOf(
        "REAL WORKOUTS" to TextPrimary,
        "XP" to HunterGold,
        "LEVELS" to NeonPurple,
        "RANKS" to ElectricBlue,
        "CHALLENGES" to CrimsonRed,
        "COMPETITION" to VenomGreen,
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(70.dp))
        Text("THE LOOP", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        Text("Your life becomes the game.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.2f)))
        Spacer(Modifier.height(34.dp))
        flow.forEachIndexed { i, (label, color) ->
            val c = seg(clock, 0.28f + i * 0.1f)
            Column(Modifier.appear(c, dy = 22.dp)) {
                Text(
                    label, fontFamily = FontFamily.Monospace, fontSize = 21.sp,
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium, color = color, letterSpacing = 2.sp,
                )
                if (i < flow.lastIndex) Text("↓", color = TextMuted.copy(alpha = 0.35f), fontSize = 14.sp)
            }
        }
        TapOverlayFreeSpace(advance)
    }
}

// ── PAGE 23 · COMPETE & CONQUER ──────────────────────────────────────────────
@Composable
private fun P23_Conquer(clock: Float, advance: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CinematicArt(R.drawable.onb_conquer, clock)
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(64.dp))
            Text("THE HUNGER GAMES", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CrimsonRed, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.12f)))
            Spacer(Modifier.height(12.dp))
            Text("Compete.\nConquer.\nBe remembered.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary, lineHeight = 30.sp,
                modifier = Modifier.appear(seg(clock, 0.22f)))
            Spacer(Modifier.height(30.dp))
            listOf(
                "ARENA DUELS" to "60s camera-verified 1v1",
                "TERRITORY WAR" to "your streets, your zones",
                "CLAN SIEGES" to "guilds tax what you fail to defend",
                "RIVALRIES" to "someone is always hunting your rank",
                "SOCIAL PROOF" to "the wire records everything",
            ).forEachIndexed { i, (k, v) ->
                Row(
                    Modifier.appear(seg(clock, 0.42f + i * 0.09f)).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("◆", color = ElectricBlue, fontSize = 11.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(k, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary, letterSpacing = 1.5.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
            }
        }
        TapNextLayer(advance)
    }
}

// ── PAGE 24 · REWARDS / EARNING POTENTIAL (honest, no income promises) ───────
@Composable
private fun P24_Rewards(clock: Float, advance: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "shards$clock")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "shardsT")
    Box(Modifier.fillMaxSize()) {
        CinematicArt(R.drawable.onb_rewards, clock, alpha = 0.58f)
        // sparse golden shards drifting up — elegant, not a particle explosion
        Canvas(Modifier.fillMaxSize()) {
            val rnd = kotlin.random.Random(4)
            repeat(9) { i ->
                val sx = (0.1f + 0.85f * rnd.nextFloat()) * size.width
                val sp = 0.4f + rnd.nextFloat() * 0.6f
                val y = (1f - ((t * sp + i * 0.13f) % 1f)) * size.height
                val s = 2.2f + rnd.nextFloat() * 3.5f
                drawCircle(HunterGold.copy(alpha = 0.35f), radius = s, center = Offset(sx, y))
            }
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(64.dp))
            Text("THE VAULT", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = HunterGold, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.12f)))
            Spacer(Modifier.height(12.dp))
            Text("EARN THROUGH\nYOUR GRIND.", style = MaterialTheme.typography.displayMedium.copy(fontSize = 40.sp, lineHeight = 44.sp), color = HunterGold,
                modifier = Modifier.appear(seg(clock, 0.24f)))
            Spacer(Modifier.height(22.dp))
            listOf(
                "UNLOCK REWARD OPPORTUNITIES.",
                "Grind quests, win duels, hold territory — VC flows to the disciplined.",
                "Redeem through the Vault ecosystem as it opens.",
            ).forEachIndexed { i, line ->
                Text(line, style = MaterialTheme.typography.bodyLarge,
                    color = if (i == 0) TextPrimary else TextMuted,
                    modifier = Modifier.appear(seg(clock, 0.45f + i * 0.1f)).padding(vertical = 5.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "* Rewards depend on effort, performance and availability. Nothing here is a guaranteed income.",
                style = MaterialTheme.typography.bodySmall, color = TextMuted,
                modifier = Modifier.appear(seg(clock, 0.85f)),
            )
        }
        TapNextLayer(advance)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 5 — SYSTEM CONTRACT (pages 25–27)
// ═══════════════════════════════════════════════════════════════════════════

// ── PAGE 25 · CONSEQUENCES (danger semantics) ────────────────────────────────
@Composable
private fun P25_Consequences(clock: Float, advance: () -> Unit) {
    val decay = remember { Animatable(100f) }
    LaunchedEffect(Unit) { delay(600); decay.animateTo(73f, tween(2200, easing = LinearEasing)) }
    val inf = rememberInfiniteTransition(label = "penaltyFlicker$clock")
    val flicker by inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(640), RepeatMode.Reverse), label = "pf")
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(70.dp))
        Text("SYSTEM WARNING", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = CrimsonRed, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.08f)))
        Spacer(Modifier.height(12.dp))
        Text("The System shows mercy\nto no one.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary, lineHeight = 28.sp,
            modifier = Modifier.appear(seg(clock, 0.18f)))
        Spacer(Modifier.height(30.dp))
        listOf(
            "MISS QUESTS" to "the chain breaks",
            "LOSE MOMENTUM" to "streak resets to zero",
            "XP DECAY" to "the math eats what you earned",
            "RANK DEGRADES" to "AVERAGE → GARBAGE → LOSER",
        ).forEachIndexed { i, (k, v) ->
            Row(
                Modifier.appear(seg(clock, 0.3f + i * 0.1f)).padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▍", color = CrimsonRed, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(k, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = CrimsonRed, fontSize = 13.sp, letterSpacing = 1.5.sp)
                    Text(v, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        // decay bar draining
        Column(Modifier.appear(seg(clock, 0.7f))) {
            Row {
                Text("XP INTEGRITY", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("${decay.value.roundToInt()}%", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CrimsonRed, fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer { alpha = flicker })
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(SurfaceHigh)) {
                Box(Modifier.fillMaxWidth(decay.value / 100f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(CrimsonRed))
            }
        }
        TapOverlayFreeSpace(advance)
    }
}

// ── PAGE 26 · THE CONTRACT (press & hold 1.5s) ───────────────────────────────
@Composable
private fun P26_Contract(haptics: SystemHaptics, onSealed: () -> Unit) {
    val clock = rememberPageClock()
    var sealed by remember { mutableStateOf(false) }
    var ripple by remember { mutableIntStateOf(0) }
    LaunchedEffect(sealed) {
        if (sealed) { ripple++; delay(1150); onSealed() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("THE CONTRACT", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 4.sp,
                modifier = Modifier.appear(seg(clock, 0.1f)))
            Spacer(Modifier.height(12.dp))
            Text("PRESS & HOLD TO AWAKEN", style = MaterialTheme.typography.titleLarge, color = TextPrimary, letterSpacing = 2.sp,
                modifier = Modifier.appear(seg(clock, 0.2f)))
            Spacer(Modifier.height(40.dp))
            Box(Modifier.popIn(seg(clock, 0.3f))) {
                FingerprintHold(haptics = haptics, onHoldComplete = { sealed = true })
            }
            Spacer(Modifier.height(30.dp))
            if (sealed) {
                GlitchRevealText("SYSTEM CONTRACT ACCEPTED", color = VenomGreen, fontSize = 15, mono = true)
            } else {
                Text(
                    "Hold for 1.5 seconds. Early release voids the seal.",
                    style = MaterialTheme.typography.bodySmall, color = TextMuted,
                    modifier = Modifier.graphicsLayer { alpha = seg(clock, 0.5f) },
                )
            }
        }
        FlashRipple(ripple, ElectricBlue)
    }
}

// ── PAGE 27 · FINAL GATE ─────────────────────────────────────────────────────
@Composable
private fun P27_FinalGate(ui: OnboardingUiState, vm: OnboardingViewModel, haptics: SystemHaptics, context: android.content.Context) {
    val clock = rememberPageClock()
    var docOpen by remember { mutableStateOf<com.thesystem.app.data.model.LegalDocDto?>(null) }
    Box(Modifier.fillMaxSize()) {
        CinematicArt(R.drawable.onb_gate, clock, alpha = 0.6f)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(80.dp))
            Text("FINAL GATE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = VenomGreen, letterSpacing = 4.sp,
                modifier = Modifier.appear(seg(clock, 0.1f)))
            Spacer(Modifier.height(12.dp))
            GlitchWithAppear(seg(clock, 0.2f), "VESSEL VERIFIED", VenomGreen, 30)
            Spacer(Modifier.height(14.dp))
            Text(
                "Your journey begins now.",
                style = MaterialTheme.typography.bodyLarge, color = TextPrimary,
                modifier = Modifier.appear(seg(clock, 0.45f)),
            )
            Spacer(Modifier.height(30.dp))
            HoloStatusPanel(
                status = when {
                    ui.signingIn || ui.busy -> "BINDING VESSEL…"
                    ui.signedInProfile != null -> "VESSEL BOUND"
                    else -> "AWAITING SIGNATURE"
                },
                accent = if (ui.signedInProfile != null) VenomGreen else ElectricBlue,
            )
            Spacer(Modifier.height(34.dp))
            Box(Modifier.popIn(seg(clock, 0.55f)).fillMaxWidth()) {
                GateKeyButton(
                    text = "CONTINUE WITH GOOGLE",
                    subtext = "FINAL GATE KEY",
                    loading = ui.signingIn || ui.busy,
                    enabled = !ui.signingIn && !ui.busy,
                    accent = VenomGreen,
                    onClick = { haptics.select(); vm.signInWithGoogle(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val gateError = ui.authError ?: ui.error
            if (gateError != null) {
                Spacer(Modifier.height(12.dp))
                Text(gateError, style = MaterialTheme.typography.bodySmall, color = CrimsonRed, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.appear(seg(clock, 0.7f))) {
                LegalFooter(
                    onTerms = { vm.ui.value.termsDoc?.let { docOpen = it } },
                    onPrivacy = { vm.ui.value.privacyDoc?.let { docOpen = it } },
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    docOpen?.let { doc ->
        AlertDialog(
            onDismissRequest = { docOpen = null },
            containerColor = com.thesystem.app.core.theme.SurfaceHigh,
            title = { Text(doc.title, color = ElectricBlue, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(doc.contentMarkdown, style = MaterialTheme.typography.bodySmall, color = TextPrimary, lineHeight = 18.sp)
                }
            },
            confirmButton = { TextButton(onClick = { docOpen = null }) { Text("CLOSE", color = ElectricBlue) } },
        )
    }
}
