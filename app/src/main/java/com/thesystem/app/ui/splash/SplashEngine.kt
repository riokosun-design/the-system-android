package com.thesystem.app.ui.splash

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.Scanlines
import com.thesystem.app.core.ui.SystemMotion
import kotlinx.coroutines.delay

/**
 * The launch sequence — trimmed to the three marks the product actually stands
 * on: RANK → BLACK ROOM → TERRITORY.
 *
 * Radar, combat, quest-donut, limit-break and battle-split animations are
 * DELETED from the rotation (they advertised removed or secondary systems).
 * The Territory card is a pure visual: territory gameplay no longer exists.
 * Their canvas implementations remain unreferenced in SplashCanvases.kt.
 */
enum class SplashVariant(val label: String) {
    RANK_BARS("RANKS"),
    BLACK_ROOM_SCAN("BLACK ROOM"),
    TERRITORY_GRID("TERRITORY"),   // legacy canvas — kept out of the rotation (gameplay sunset)
    QUEST_DONUT("DAILY PROTOCOL"),
    BATTLE_SPLIT("ARENA"),
}

/**
 * HONEST ROTATION — only marks the product actually stands on TODAY:
 * Ranks, the Black Room, the Daily Protocol, the Arena. Territory canvases
 * stay compiled but out of rotation (β decision: dead gameplay never
 * advertises on the first frame a hunter sees).
 */
val HONEST_SPLASH_ROTATION: List<SplashVariant> = listOf(
    SplashVariant.RANK_BARS,
    SplashVariant.BLACK_ROOM_SCAN,
    SplashVariant.QUEST_DONUT,
    SplashVariant.BATTLE_SPLIT,
)

/**
 * Dynamic Animated Splash System: crossfades between canvas variants at [variantMillis] cadence.
 * Pure hardware-accelerated Canvas drawing — no GIF/Lottie decode cost, 60/120fps safe.
 */
@Composable
fun DynamicSplash(
    variants: List<SplashVariant> = HONEST_SPLASH_ROTATION,
    variantMillis: Long = 1600,
    showBranding: Boolean = true,
    onFinished: (() -> Unit)? = null,
) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(variants) {
        val cycles = variants.size
        while (true) {
            delay(variantMillis)
            index++
            if (onFinished != null && index >= cycles) { onFinished(); index = 0 } else index %= cycles
        }
    }

    // ROUND 3: brand slams in with the punch spring on boot
    var booted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { booted = true }
    val brandScale by animateFloatAsState(if (booted) 1f else 0.82f, SystemMotion.springPunch, label = "brandPop")

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF0D0D0D), InkBlack)))) {
        Crossfade(targetState = variants[index % variants.size], animationSpec = tween(450), label = "splashXfade") { v ->
            Box(Modifier.fillMaxSize()) { SplashGraph(v, Modifier.fillMaxSize()) }
        }
        // CRT scanline texture — sells the "you are inside THE SYSTEM" fantasy
        Scanlines(Modifier.matchParentSize())
        // 0.25.2 §1.3 — the native splash's S-logo never "ends"... it just walks
        // straight into the app. Same mark, now glowing live over the motion
        // graphs — zero black gap between OS splash and our launch flow.
        SystemSEmblem(
            Modifier
                .align(Alignment.Center)
                .size(132.dp)
                .graphicsLayer { scaleX = brandScale; scaleY = brandScale },
        )
        if (showBranding) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "THE SYSTEM",
                    style = MaterialTheme.typography.displayMedium,
                    color = ElectricBlue,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.graphicsLayer { scaleX = brandScale; scaleY = brandScale },
                )
                Spacer(Modifier.height(6.dp))
                Crossfade(targetState = variants[index % variants.size].label, animationSpec = tween(300), label = "splashLabel") { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/**
 * THE SYSTEM S-MARK — the live, breathing twin of res/drawable/ic_system_s.xml
 * (0.25.2 §1). Sovereign white S spine + teal cut slivers + hunter's-eye dot,
 * drawn as pure Canvas strokes with a 3-pass additive bloom.
 *
 * CRASH LAW (spec §2.5): NO BlurMaskFilter anywhere — a radius ≤ 0 filter is an
 * instant IllegalArgumentException on several OEM builds. Bloom here is layered
 * alpha strokes; every width/radius is coerceAtLeast-clamped, and zero/NaN
 * canvas sizes skip the frame entirely.
 */
@Composable
fun SystemSEmblem(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "sMarkPulse")
    val breathe by pulse.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "sMarkBreathe",
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w < 4f || h < 4f || !w.isFinite() || !h.isFinite()) return@Canvas
        // Geometry mirrors the native splash vector: 240-unit box, centred.
        val s = (size.minDimension / 240f).coerceAtLeast(0.01f)
        val ox = (w - 240f * s) / 2f
        val oy = (h - 240f * s) / 2f
        fun px(x: Float) = ox + x * s
        fun py(y: Float) = oy + y * s
        val teal = Color(0xFF35E0D4)
        val centre = Offset(w / 2f, h / 2f)
        // Dark halo so the mark stays legible over any live background graph.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xCC000000), Color(0x00000000)),
                center = centre,
                radius = (118f * s).coerceAtLeast(1f),
            ),
            radius = (118f * s).coerceAtLeast(1f),
            center = centre,
        )
        val spine = Path().apply {
            moveTo(px(168f), py(78f))
            cubicTo(px(148f), py(54f), px(108f), py(50f), px(88f), py(66f))
            cubicTo(px(70f), py(80f), px(72f), py(102f), px(92f), py(113f))
            cubicTo(px(108f), py(122f), px(140f), py(122f), px(154f), py(134f))
            cubicTo(px(170f), py(146f), px(170f), py(170f), px(148f), py(182f))
            cubicTo(px(126f), py(195f), px(92f), py(192f), px(74f), py(172f))
        }
        // 3-pass bloom: wide white haze → teal under-glow → crisp white core.
        drawPath(
            spine, Color.White.copy(alpha = 0.10f + 0.06f * breathe),
            style = Stroke(width = (46f * s).coerceAtLeast(1f), cap = StrokeCap.Round),
        )
        drawPath(
            spine, teal.copy(alpha = 0.14f + 0.08f * breathe),
            style = Stroke(width = (33f * s).coerceAtLeast(1f), cap = StrokeCap.Round),
        )
        drawPath(
            spine, Color.White.copy(alpha = 0.90f + 0.10f * breathe),
            style = Stroke(width = (24f * s).coerceAtLeast(1f), cap = StrokeCap.Round),
        )
        val topSliver = Path().apply {
            moveTo(px(80f), py(66f)); lineTo(px(106f), py(50f))
            lineTo(px(113f), py(61f)); lineTo(px(87f), py(77f)); close()
        }
        val bottomSliver = Path().apply {
            moveTo(px(160f), py(174f)); lineTo(px(134f), py(190f))
            lineTo(px(127f), py(179f)); lineTo(px(153f), py(163f)); close()
        }
        drawPath(topSliver, teal)
        drawPath(bottomSliver, teal)
        drawCircle(
            teal,
            radius = (6.5f * s).coerceAtLeast(0.5f),
            center = Offset(px(156f), py(86f)),
        )
    }
}

@Composable
fun SplashGraph(variant: SplashVariant, modifier: Modifier = Modifier) {
    when (variant) {
        SplashVariant.RANK_BARS -> RankBarsGraph(modifier)
        SplashVariant.BLACK_ROOM_SCAN -> BlackRoomScanGraph(modifier)
        SplashVariant.TERRITORY_GRID -> TerritoryGridGraph(modifier)
        SplashVariant.QUEST_DONUT -> QuestDonutGraph(modifier)
        SplashVariant.BATTLE_SPLIT -> BattleSplitGraph(modifier)
    }
}
