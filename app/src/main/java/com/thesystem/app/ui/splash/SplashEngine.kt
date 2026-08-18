package com.thesystem.app.ui.splash

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.Scanlines
import com.thesystem.app.core.ui.SystemMotion
import kotlinx.coroutines.delay

/** The 11 modular splash graphs (Section 2-A). Admin can reorder/restrict via remote config later. */
enum class SplashVariant(val label: String) {
    TERMINAL_GLITCH("TERMINAL"),
    LEVEL_PULSE("LEVEL UP"),
    SPIDER_RADAR("RADAR"),
    SHADOW_HEARTBEAT("HEARTBEAT"),
    RANK_BARS("RANKS"),
    TERRITORY_GRID("TERRITORY"),
    COMBAT_SPLINE("COMBAT"),
    QUEST_DONUT("QUESTS"),
    BATTLE_SPLIT("BATTLE"),
    BLACK_ROOM_SCAN("BLACK ROOM"),
    LIMIT_BREAKER("LIMIT BREAK"),
}

/**
 * Dynamic Animated Splash System: crossfades between canvas variants at [variantMillis] cadence.
 * Pure hardware-accelerated Canvas drawing — no GIF/Lottie decode cost, 60/120fps safe.
 */
@Composable
fun DynamicSplash(
    variants: List<SplashVariant> = SplashVariant.entries,
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

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF10141D), VoidBlack)))) {
        Crossfade(targetState = variants[index % variants.size], animationSpec = tween(450), label = "splashXfade") { v ->
            Box(Modifier.fillMaxSize()) { SplashGraph(v, Modifier.fillMaxSize()) }
        }
        // CRT scanline texture — sells the "you are inside THE SYSTEM" fantasy
        Scanlines(Modifier.matchParentSize())
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

@Composable
fun SplashGraph(variant: SplashVariant, modifier: Modifier = Modifier) {
    when (variant) {
        SplashVariant.TERMINAL_GLITCH -> TerminalGlitchGraph(modifier)
        SplashVariant.LEVEL_PULSE -> LevelPulseGraph(modifier)
        SplashVariant.SPIDER_RADAR -> SpiderRadarGraph(modifier)
        SplashVariant.SHADOW_HEARTBEAT -> ShadowHeartbeatGraph(modifier)
        SplashVariant.RANK_BARS -> RankBarsGraph(modifier)
        SplashVariant.TERRITORY_GRID -> TerritoryGridGraph(modifier)
        SplashVariant.COMBAT_SPLINE -> CombatSplineGraph(modifier)
        SplashVariant.QUEST_DONUT -> QuestDonutGraph(modifier)
        SplashVariant.BATTLE_SPLIT -> BattleSplitGraph(modifier)
        SplashVariant.BLACK_ROOM_SCAN -> BlackRoomScanGraph(modifier)
        SplashVariant.LIMIT_BREAKER -> LimitBreakerGraph(modifier)
    }
}
