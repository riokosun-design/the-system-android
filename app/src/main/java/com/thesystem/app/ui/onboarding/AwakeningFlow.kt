package com.thesystem.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.R
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.CrimsonRed
import com.thesystem.app.core.theme.ElectricBlue
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
import com.thesystem.app.ui.motion.DisciplinaryTrajectoryGraph
import com.thesystem.app.ui.motion.RankDisciplinaryMotionBackground
import com.thesystem.app.ui.motion.SystemPalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════════════
// THE AWAKENING FLOW — 12 pages · 3 phases (v0.25.0 spec §1/§3).
//
//   PHASE 1 · VESSEL INTAKE   pages 01–05  (name / age / height / mass / class)
//   PHASE 2 · POWER AWAKENING pages 06–09  (biometric → trajectory graph →
//                                           RANKS showcase → BLACK ROOM)
//   PHASE 3 · SYSTEM CONTRACT pages 10–12  (sport path → hold → final gate)
//
// The weak informational slides are DELETED — their job is done better by the
// first-launch SystemMasterIntroCinematics (spec §2). The old P01 "SYSTEM
// DETECTED" circular radar loop is permanently retired (spec §1). Only the
// two showcase pages the directive names (RANKS, BLACK ROOM) survive — now
// riding the glow-camera motion graphics engine, joined by the camera-tracked
// disciplinary trajectory graph at the stat-calculation step (spec §3.3).
// VM contract unchanged: username gate → claim → metrics → finish. Zero
// backend changes.
// ═══════════════════════════════════════════════════════════════════════════

// ── Baseline activity (vessel intake → finish contract) ─────────────────────
enum class Activity(val label: String, val sub: String) {
    SEDENTARY("Resting Vessel", "little training history"),
    STEADY("Steady", "trains sometimes"),
    RELENTLESS("Relentless", "near-daily grind"),
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
    in 1..5 -> 1; in 6..9 -> 2; else -> 3
}

// ══ HOST — page state machine + cinematic transitions ═══════════════════════

private const val LAST_PAGE = 12

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
    val advance: () -> Unit = { haptics.tick(); page = (page + 1).coerceAtMost(LAST_PAGE) }

    // system back rewinds the ritual one page instead of ejecting the hunter
    BackHandler(enabled = page > 1) { haptics.tick(); page = (page - 1).coerceAtLeast(1) }

    // Google seal → finalize the contract through the existing VM pipeline
    LaunchedEffect(ui.signedInProfile != null) {
        if (ui.signedInProfile != null) vm.finish(onDone, activityName)
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
                Box(Modifier.fillMaxSize()) {
                    pageArtFor(p)?.let { art -> CinematicArt(art.res, clock, alpha = art.alpha, mirror = art.mirror) }
                    when (p) {
                        1 -> P01_Name(ui, vm, advance)
                        2 -> P02_Age(ui, vm, clock, advance)
                        3 -> P03_Height(ui, vm, clock, advance)
                        4 -> P04_Weight(ui, vm, clock, advance)
                        5 -> P05_Archetype(archetype, { archetypeName = it.name }, activity, { activityName = it.name }, {
                            vm.setGoal(archetype.goal); haptics.select(); page = 6
                        })
                        6 -> P06_Biometric(ui, archetype, activity, clock, advance)
                        7 -> P07_Trajectory(clock, advance)
                        8 -> P08_Ranks(clock, advance)
                        9 -> P09_BlackRoom(clock, advance)
                        10 -> P10_SportPath(ui, vm, clock) { haptics.select(); page = 11 }
                        11 -> P11_Contract(haptics) { page = 12 }
                        12 -> P12_FinalGate(ui, vm, haptics, context)
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        // ── HUD chrome: phase + page counter, quiet mono ─────────────────────
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PHASE ${phaseOf(page)}/3",
                fontFamily = FontFamily.Default, fontSize = 9.sp, color = ElectricBlue.copy(alpha = 0.65f), letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "PAGE %02d/$LAST_PAGE".format(page),
                fontFamily = FontFamily.Default, fontSize = 9.sp, color = TextMuted.copy(alpha = 0.6f), letterSpacing = 2.sp,
            )
        }
    }
}



// ── SHARED PAGE CHROME ───────────────────────────────────────────────────────

/** Cinematic artwork treatment: desaturated art + darkness + vignette + slow zoom + light sweep.
 *  Visual prominence stays ~10–16% — text always wins. */
@Composable
private fun CinematicArt(res: Int, clock: Float, alpha: Float = 0.5f, mirror: Boolean = false) {
    val inf = rememberInfiniteTransition(label = "artLife$res")
    val sweepT by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(6400, easing = LinearEasing)), label = "artSweep$res")
    val artAlpha = seg(clock, 0f, 0.55f) // image reveals from darkness
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha * artAlpha
                    val s = 1f + 0.07f * clock // slow cinematic push-in
                    scaleX = if (mirror) -s else s; scaleY = s
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
            val cx = lerp(-bandW, size.width + bandW, sweepT)
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.045f), 1f to Color.Transparent),
                topLeft = Offset(cx - bandW / 2f, 0f), size = Size(bandW, size.height),
            )
        }
    }
}

// ── PAGE ART DIRECTOR — intake + contract pages carry faded hunter plates ───
// Motion pages 07/08/09 render the engine's own living backgrounds instead.
private data class PageArt(val res: Int, val alpha: Float, val mirror: Boolean = false)

private fun pageArtFor(p: Int): PageArt? = when (p) {
    1 -> PageArt(R.drawable.onb_scan, 0.22f)
    2 -> PageArt(R.drawable.onb_recruit, 0.22f)
    3 -> PageArt(R.drawable.onb_stand, 0.20f)
    4 -> PageArt(R.drawable.onb_mass, 0.20f)
    5 -> PageArt(R.drawable.onb_paths, 0.22f)
    6 -> PageArt(R.drawable.onb_sentinel, 0.18f, mirror = true)
    10 -> PageArt(R.drawable.onb_paths, 0.22f, mirror = true)
    11 -> PageArt(R.drawable.onb_scan, 0.20f)
    else -> null // 7/8/9 motion graphics · 12 carries its own gate art
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 1 — VESSEL INTAKE (pages 01–05)
// ═══════════════════════════════════════════════════════════════════════════

// ── PAGE 01 · NAME ───────────────────────────────────────────────────────────
@Composable
private fun P01_Name(ui: OnboardingUiState, vm: OnboardingViewModel, advance: () -> Unit) {
    val clock = rememberPageClock()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(72.dp))
        Text("VESSEL IDENTIFICATION", fontFamily = FontFamily.Default, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
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
                        fontFamily = FontFamily.Default, letterSpacing = 2.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (ui.username.isEmpty()) {
                            Text("vessel_tag", color = TextMuted.copy(alpha = 0.35f), fontSize = 30.sp, fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold)
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
        Text(status, fontFamily = FontFamily.Default, fontSize = 11.sp, color = statusColor, letterSpacing = 1.5.sp,
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

// ── PAGE 02 · AGE DETECTION (13–30 hard range) ───────────────────────────────
@Composable
private fun P02_Age(ui: OnboardingUiState, vm: OnboardingViewModel, clock: Float, advance: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(80.dp))
        Text("AGE DETECTION", fontFamily = FontFamily.Default, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
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
            Text("YRS", color = TextMuted, fontFamily = FontFamily.Default, fontSize = 14.sp)
        }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.appear(seg(clock, 0.42f))) {
            WheelRow(label = "VESSEL AGE", unit = "LOCKED 13–30", range = 13..30, value = ui.age, onValue = { vm.setAge(it) })
        }
        Spacer(Modifier.weight(1f))
        ContinueBar(seg(clock, 0.6f), advance)
    }
}

// ── PAGE 03 · HEIGHT SCAN (animated ruler) ───────────────────────────────────
@Composable
private fun P03_Height(ui: OnboardingUiState, vm: OnboardingViewModel, clock: Float, advance: () -> Unit) {
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
            var heightUnit by rememberSaveable { mutableStateOf("CM") } // CM | FT
            Row(
                Modifier.fillMaxWidth().appear(seg(clock, 0.1f)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("HEIGHT SCAN", fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OnboardingChip(label = "CM", selected = heightUnit == "CM", onClick = { heightUnit = "CM" })
                    OnboardingChip(label = "FT·IN", selected = heightUnit == "FT", onClick = { heightUnit = "FT" })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Measure the frame.", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
                modifier = Modifier.appear(seg(clock, 0.2f)))
            Spacer(Modifier.height(30.dp))
            val totalInches = (ui.heightCm / 2.54f).roundToInt()
            val feet = totalInches / 12
            val inches = totalInches % 12
            fun writeFtIn(f: Int, i: Int) = vm.setHeight(((f * 12 + i) * 2.54f * 10f).roundToInt() / 10f)
            if (heightUnit == "CM") {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.appear(seg(clock, 0.3f))) {
                    AnimatedCounter(target = ui.heightCm.toLong(), color = ElectricBlue, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, format = { "$it" })
                    Spacer(Modifier.width(10.dp)); Text("CM", color = TextMuted, fontFamily = FontFamily.Default, fontSize = 14.sp)
                }
            } else {
                Column(modifier = Modifier.appear(seg(clock, 0.3f))) {
                    Text(
                        "$feet′${inches}″", color = ElectricBlue, fontSize = 56.sp,
                        fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Default, letterSpacing = 2.sp,
                    )
                    Text("= ${ui.heightCm.roundToInt()} CM", color = TextMuted, fontFamily = FontFamily.Default, fontSize = 12.sp, letterSpacing = 1.5.sp)
                }
            }
            Spacer(Modifier.height(26.dp))
            if (heightUnit == "CM") {
                Box(Modifier.appear(seg(clock, 0.42f))) {
                    WheelRow(label = "HEIGHT", unit = "CM", range = 140..210, value = ui.heightCm.roundToInt(), onValue = { vm.setHeight(it.toFloat()) })
                }
            } else {
                Row(Modifier.appear(seg(clock, 0.42f)), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(1f)) {
                        WheelRow(label = "FEET", unit = "FT", range = 4..7, value = feet, onValue = { writeFtIn(it, inches) })
                    }
                    Box(Modifier.weight(1f)) {
                        WheelRow(label = "INCHES", unit = "IN", range = 0..11, value = inches, onValue = { writeFtIn(feet, it) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            ContinueBar(seg(clock, 0.6f), advance)
        }
    }
}

// ── PAGE 04 · MASS DETECTION (animated digital gauge) ────────────────────────
@Composable
private fun P04_Weight(ui: OnboardingUiState, vm: OnboardingViewModel, clock: Float, advance: () -> Unit) {
    val needleFrac = ((ui.weightKg - 40f) / 110f).coerceIn(0f, 1f)
    val needle by androidx.compose.animation.core.animateFloatAsState(
        targetValue = needleFrac,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 220f),
        label = "gaugeNeedle",
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(80.dp))
        Text("MASS DETECTION", fontFamily = FontFamily.Default, fontSize = 10.sp, color = HunterGold, letterSpacing = 3.sp,
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
            Spacer(Modifier.width(8.dp)); Text("KG", color = TextMuted, fontFamily = FontFamily.Default, fontSize = 14.sp)
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
private fun ContinueBar(c: Float, advance: () -> Unit, label: String = "CONTINUE ▸", subtext: String = "SCAN LOGGED") {
    GateKeyButton(
        text = label, subtext = subtext, accent = ElectricBlue, enabled = c >= 0.3f, onClick = advance,
        modifier = Modifier.fillMaxWidth().appear(c, dy = 10.dp), // shallow travel — hit target stops moving fast
    )
    Spacer(Modifier.height(26.dp))
}

// ── PAGE 05 · ARCHETYPE + ACTIVITY ───────────────────────────────────────────
@Composable
private fun P05_Archetype(
    archetype: Archetype, onArch: (Archetype) -> Unit,
    activity: Activity, onAct: (Activity) -> Unit,
    onContinue: () -> Unit,
) {
    val clock = rememberPageClock()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("CLASS ASSIGNMENT", fontFamily = FontFamily.Default, fontSize = 10.sp, color = NeonPurple, letterSpacing = 3.sp,
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
        Text("BASELINE ACTIVITY", fontFamily = FontFamily.Default, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
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
// PHASE 2 — POWER AWAKENING (pages 06–09, motion-graphics carried)
// ═══════════════════════════════════════════════════════════════════════════

// ── PAGE 06 · BIOMETRIC ANALYSIS (auto-advance scan) ─────────────────────────
@Composable
private fun P06_Biometric(ui: OnboardingUiState, arch: Archetype, act: Activity, clock: Float, advance: () -> Unit) {
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
        Text("BIOMETRIC ANALYSIS", fontFamily = FontFamily.Default, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.08f)))
        Spacer(Modifier.height(24.dp))
        rows.forEachIndexed { i, (k, v) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp).appear(seg(clock, 0.14f + i * 0.07f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k, fontFamily = FontFamily.Default, fontSize = 11.sp, color = TextMuted, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(v, fontFamily = FontFamily.Default, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
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

// ── PAGE 07 · DISCIPLINARY TRAJECTORY (spec §3.3 — camera-tracked graph) ─────
// The first-time stat/progress calculation step: the engine's glowing camera
// lens hunts the tip of the hunter's growth curve while the quitter's line
// rots into the penalty zone. The COMMIT key unlocks when the math lands.
@Composable
private fun P07_Trajectory(clock: Float, advance: () -> Unit) {
    var graphDone by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            "PROGRESS CALCULATION", fontFamily = FontFamily.Default, fontSize = 10.sp,
            color = ElectricBlue, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your discipline, projected.",
            style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.18f)),
        )
        Spacer(Modifier.height(18.dp))
        // the living graph — camera-tracked lens on every milestone node
        Box(
            Modifier
                .popIn(seg(clock, 0.22f, 0.2f))
                .fillMaxWidth()
                .weight(1f)
                .clip(CutCornerShape(14.dp))
                .border(1.dp, SystemPalette.ElectricCyan.copy(alpha = 0.45f), CutCornerShape(14.dp)),
        ) {
            DisciplinaryTrajectoryGraph(
                modifier = Modifier.fillMaxSize(),
                onComplete = { graphDone = true },
            )
        }
        Spacer(Modifier.height(18.dp))
        GateKeyButton(
            text = "COMMIT TO THE SYSTEM ▸",
            subtext = if (graphDone) "TRAJECTORY SEALED" else "CALCULATING CURVE…",
            accent = ElectricBlue,
            enabled = graphDone,
            onClick = { if (graphDone) advance() },
            modifier = Modifier.fillMaxWidth().appear(seg(clock, 0.5f), dy = 12.dp),
        )
        Spacer(Modifier.height(22.dp))
    }
}

// ── PAGE 08 · RANKS SHOWCASE (spec §3.2 + §4 — every rank rides its own
// disciplinary motion background; the visible rank owns the screen layer) ────
@Composable
private fun P08_Ranks(clock: Float, advance: () -> Unit) {
    val tiers = SystemMath.HunterTier.entries
    val listState = rememberLazyListState()
    val visibleRank by remember {
        derivedStateOf { tiers[listState.firstVisibleItemIndex.coerceIn(0, tiers.lastIndex)].letter }
    }

    Box(Modifier.fillMaxSize()) {
        // screen layer — the rank under inspection projects its signature behind everything
        Crossfade(targetState = visibleRank, animationSpec = tween(600), label = "rankScreenBg") { r ->
            RankDisciplinaryMotionBackground(rank = r, modifier = Modifier.fillMaxSize())
        }
        // readability scrim — foreground typography wins, always
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to GateBlack.copy(alpha = 0.88f),
                    0.5f to GateBlack.copy(alpha = 0.72f),
                    1f to GateBlack.copy(alpha = 0.90f),
                ),
            ),
        )

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(56.dp))
            Text(
                "HUNTER RANK LADDER", fontFamily = FontFamily.Default, fontSize = 10.sp,
                color = ElectricBlue, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.1f)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "From weakest F to\nAbsolute Monarch.",
                style = MaterialTheme.typography.headlineMedium, color = TextPrimary, lineHeight = 30.sp,
                modifier = Modifier.appear(seg(clock, 0.2f)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Every vessel begins equal. Decay decides the rest.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                modifier = Modifier.appear(seg(clock, 0.28f)),
            )
            Spacer(Modifier.height(20.dp))

            // the ladder — each card rides its own camera-tracked motion signature
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f).appear(seg(clock, 0.34f), dy = 30.dp),
            ) {
                items(tiers, key = { it.letter }) { t ->
                    RankShowcaseCard(tier = t, isStart = t == SystemMath.HunterTier.F)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "◂ DRAG TO INSPECT ALL 8 RANKS ▸",
                fontFamily = FontFamily.Default, fontSize = 9.sp, color = TextMuted.copy(alpha = 0.7f), letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(12.dp))
            ContinueBar(seg(clock, 0.6f), advance, label = "BEGIN AT F-RANK ▸", subtext = "THE LADDER REMEMBERS EVERYTHING")
        }
    }
}

/** One ladder card: rank motion background + scrim + sharp mono type. */
@Composable
private fun RankShowcaseCard(tier: SystemMath.HunterTier, isStart: Boolean) {
    val accent = when (tier.letter) {
        "F" -> SystemPalette.WeakSteel
        "E" -> SystemPalette.AwakenedCyan
        "D" -> SystemPalette.ElectricCyan
        "C" -> SystemPalette.ElectricCyan
        "B" -> SystemPalette.AbyssalBlue
        "A" -> SystemPalette.SovereignPurple
        "S" -> SystemPalette.SovereignPurple
        else -> SystemPalette.SovereignGold
    }
    val flavor = when (tier.letter) {
        "F" -> "The weakest — every legend starts here."
        "E" -> "Iron discipline — reflexes forged daily."
        "D" -> "Relentless grind — the protocol becomes habit."
        "C" -> "Combat ready — consistency, verified."
        "B" -> "Elite hunter — the wire knows the name."
        "A" -> "Commanding force — leaderboards bend."
        "S" -> "National authority — discipline made visible."
        else -> "Absolute Monarch — the System's own reflection."
    }
    Box(
        Modifier
            .width(268.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
    ) {
        // §4: this rank's dedicated disciplinary motion background
        RankDisciplinaryMotionBackground(rank = tier.letter, modifier = Modifier.fillMaxSize())
        // bottom-anchored scrim — type stays 100% readable over the storm
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to GateBlack.copy(alpha = 0.30f),
                    0.45f to GateBlack.copy(alpha = 0.45f),
                    1f to GateBlack.copy(alpha = 0.88f),
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(18.dp),
        ) {
            Text(
                tier.letter,
                color = accent, fontSize = 44.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                tier.title, color = TextPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "UNLOCKS AT LEVEL ${tier.minLevel}",
                color = accent.copy(alpha = 0.9f), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(flavor, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (isStart) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .clip(CutCornerShape(6.dp))
                    .background(GateBlack.copy(alpha = 0.72f))
                    .border(1.dp, VenomGreen.copy(alpha = 0.8f), CutCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    "YOUR START",
                    color = VenomGreen, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                )
            }
        }
    }
}

// ── PAGE 09 · BLACK ROOM — FORBIDDEN COURSE showcase (spec §3.2, §3.4) ───────
@Composable
private fun P09_BlackRoom(clock: Float, advance: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // the sovereign eclipse aura — only the apex signature can guard this door
        RankDisciplinaryMotionBackground(rank = "SS", modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to GateBlack.copy(alpha = 0.82f),
                    0.5f to GateBlack.copy(alpha = 0.70f),
                    1f to GateBlack.copy(alpha = 0.90f),
                ),
            ),
        )
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(56.dp))
            Text(
                "FORBIDDEN COURSE", fontFamily = FontFamily.Default, fontSize = 10.sp,
                color = SystemPalette.SovereignGold, letterSpacing = 4.sp,
                modifier = Modifier.appear(seg(clock, 0.1f)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "BLACK ROOM",
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 40.sp),
                color = TextPrimary, letterSpacing = 3.sp,
                modifier = Modifier.appear(seg(clock, 0.2f)),
            )
            Text(
                "Two-year bespoke protocol · renewal every 6 months",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                modifier = Modifier.appear(seg(clock, 0.28f)),
            )
            Spacer(Modifier.height(30.dp))
            // the gate — the server's own five locks, told straight
            Text(
                "THE GATE OPENS ONLY FOR:", fontFamily = FontFamily.Default, fontSize = 10.sp,
                color = HunterGold, letterSpacing = 2.sp,
                modifier = Modifier.appear(seg(clock, 0.4f)),
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                "LEVEL 50" to "fifty earned levels — no shortcuts",
                "1+ UNLOCKED FORM" to "a mastered discipline style",
                "≥5 PENALTY EVENTS SURVIVED" to "you fell, and you returned",
                "MUSCLE COURSE ≥ 50%" to "the primary grind, half-proven",
                "3 PERFORMANCE TRACKS DONE" to "special trials, completed",
            ).forEachIndexed { i, (k, v) ->
                Row(
                    Modifier.appear(seg(clock, 0.46f + i * 0.08f)).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("▍", color = SystemPalette.SovereignGold, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            k, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            color = TextPrimary, fontSize = 12.sp, letterSpacing = 1.2.sp,
                        )
                        Text(v, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Entry is not bought. It is unlocked — by hunters who outlast their own weakness.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted, lineHeight = 20.sp,
                modifier = Modifier.appear(seg(clock, 0.85f)),
            )
            Spacer(Modifier.weight(1f))
            ContinueBar(seg(clock, 0.9f), advance, label = "CONTINUE ▸", subtext = "THE GATE REMEMBERS")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 3 — SYSTEM CONTRACT (pages 10–12)
// ═══════════════════════════════════════════════════════════════════════════

// ── PAGE 10 · SPORT PATH ─────────────────────────────────────────────────────
/**
 * The athlete branch, offered once and honestly: pick ONE sport, or decline
 * with zero shame. Accepting adds ONE conditioning + ONE technical quest to
 * the daily floor (server-issued, mig 022 — never client-invented); declining
 * writes nothing and hides all sport content permanently. The explicit
 * opt-out is a first-class answer, not a neglected default.
 */
@Composable
private fun P10_SportPath(ui: OnboardingUiState, vm: OnboardingViewModel, clock: Float, advance: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("ATHLETE PATH", fontFamily = FontFamily.Default, fontSize = 10.sp, color = VenomGreen, letterSpacing = 3.sp,
            modifier = Modifier.appear(seg(clock, 0.1f)))
        Spacer(Modifier.height(12.dp))
        Text("Will you chase a sport?", style = MaterialTheme.typography.headlineMedium, color = TextPrimary,
            modifier = Modifier.appear(seg(clock, 0.2f)))
        Spacer(Modifier.height(8.dp))
        Text(
            "One sport. One conditioning + one technical quest on your daily floor, rotated from the System's pool. Mental and recovery pillars ride your existing rhythm.",
            style = MaterialTheme.typography.bodyMedium, color = TextMuted,
            modifier = Modifier.appear(seg(clock, 0.26f)),
        )
        Spacer(Modifier.height(20.dp))
        com.thesystem.app.core.sport.Sport.entries.forEachIndexed { i, s ->
            Box(Modifier.appear(seg(clock, 0.3f + i * 0.07f)).padding(vertical = 5.dp)) {
                ArchetypeCard(
                    title = s.label, tagline = s.tagline,
                    stat = "COND · TECH · MENTAL · RECOVERY",
                    accent = VenomGreen, selected = ui.sport == s.id && ui.sportChosen,
                    onClick = { vm.setSport(s.id) },
                )
            }
        }
        // THE EXPLICIT OPT-OUT (spec law) — same weight as any path
        Box(Modifier.appear(seg(clock, 0.6f)).padding(vertical = 8.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (ui.sportChosen && ui.sport == null) SurfaceHigh.copy(alpha = 0.8f) else SurfaceDark.copy(alpha = 0.5f))
                    .border(
                        width = if (ui.sportChosen && ui.sport == null) 1.5.dp else 1.dp,
                        color = if (ui.sportChosen && ui.sport == null) TextPrimary.copy(alpha = 0.8f) else TextMuted.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(18.dp),
                    )
                    .pointerInput(Unit) { detectTapGestures(onTap = { vm.setSport(null) }) }
                    .padding(16.dp),
            ) {
                Text(
                    "I DON'T WANT TO BECOME A SPORTS PLAYER",
                    color = if (ui.sportChosen && ui.sport == null) TextPrimary else TextMuted,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                )
                Text("The base protocol stands. No sport content will appear.", color = TextMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (ui.sportChosen) {
            ContinueBar(seg(clock, 0.7f), advance, label = "SEAL PATH ▸")
        } else {
            Text(
                "CHOOSE A PATH — OR DECLINE ONE",
                fontFamily = FontFamily.Default, fontSize = 10.sp, color = TextMuted, letterSpacing = 2.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 30.dp).graphicsLayer { alpha = seg(clock, 0.7f) },
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── PAGE 11 · THE CONTRACT (press & hold seal) ───────────────────────────────
@Composable
private fun P11_Contract(haptics: SystemHaptics, onSealed: () -> Unit) {
    val clock = rememberPageClock()
    var sealed by remember { mutableStateOf(false) }
    var ripple by remember { mutableIntStateOf(0) }
    LaunchedEffect(sealed) {
        if (sealed) { ripple++; delay(1150); onSealed() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("THE CONTRACT", fontFamily = FontFamily.Default, fontSize = 10.sp, color = ElectricBlue, letterSpacing = 4.sp,
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

// ── PAGE 12 · FINAL GATE (Google sign-in + legal) ────────────────────────────
@Composable
private fun P12_FinalGate(ui: OnboardingUiState, vm: OnboardingViewModel, haptics: SystemHaptics, context: android.content.Context) {
    val clock = rememberPageClock()
    var docOpen by remember { mutableStateOf<com.thesystem.app.data.model.LegalDocDto?>(null) }
    Box(Modifier.fillMaxSize()) {
        CinematicArt(R.drawable.onb_gate, clock, alpha = 0.6f)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(80.dp))
            Text("FINAL GATE", fontFamily = FontFamily.Default, fontSize = 10.sp, color = VenomGreen, letterSpacing = 4.sp,
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
            containerColor = SurfaceHigh,
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
