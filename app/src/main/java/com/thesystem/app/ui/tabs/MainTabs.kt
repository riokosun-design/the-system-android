package com.thesystem.app.ui.tabs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.SystemMotion
import com.thesystem.app.core.ui.pressScale
import com.thesystem.app.core.ui.rememberSystemHaptics
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.ui.arena.ArenaScreen
import com.thesystem.app.ui.dashboard.DashboardScreen
import com.thesystem.app.ui.profile.ProfileScreen
import com.thesystem.app.ui.protocol.ProtocolScreen
import com.thesystem.app.ui.territory.TerritoryScreen

/** Strict 5-tab navbar (Section 1). No more, no less. */
enum class SystemTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Status", Icons.Default.Dashboard),
    TERRITORY("Map", Icons.Default.Map),
    ARENA("Arena", Icons.Default.Whatshot),
    PROTOCOL("Protocol", Icons.Default.Lock),
    PROFILE("Vault", Icons.Default.Person),
}

@Composable
fun MainTabs(profile: UserDto, nav: NavHostController, onSignOut: () -> Unit) {
    var tab by remember { mutableStateOf(SystemTab.DASHBOARD) }
    val haptics = rememberSystemHaptics()

    Scaffold(
        containerColor = VoidBlack,
        bottomBar = {
            SystemNavBar(
                selected = tab,
                onSelect = { t ->
                    if (t != tab) { haptics.select(); tab = t }
                },
            )
        },
    ) { padding ->
        // Crossfade + micro-rise between tabs: navigation feels like teleporting,
        // not page-flipping. AnimatedContent disposes off-screen tabs (map/camera
        // heavy screens don't burn frames when hidden).
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(SystemMotion.snap) + slideInVertically(SystemMotion.medium) { it / 24 })
                    .togetherWith(fadeOut(SystemMotion.snap))
            },
            label = "tabSwitch",
            modifier = Modifier.padding(padding),
        ) { t ->
            when (t) {
                SystemTab.DASHBOARD -> DashboardScreen(nav = nav)
                SystemTab.TERRITORY -> TerritoryScreen()
                SystemTab.ARENA -> ArenaScreen(nav = nav)
                SystemTab.PROTOCOL -> ProtocolScreen(nav = nav)
                SystemTab.PROFILE -> ProfileScreen(profile = profile, nav = nav, onSignOut = onSignOut)
            }
        }
    }
}

// ── CUSTOM NAV BAR — glass panel + sliding neon pill + bouncing icons ════════
// Replaces stock NavigationBar: stock indicator is fine, but OURS feels alive.
// The pill springs between slots; icons pop on select; all graphicsLayer work.

@Composable
private fun SystemNavBar(selected: SystemTab, onSelect: (SystemTab) -> Unit) {
    val tabs = SystemTab.entries
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(SurfaceDark.copy(alpha = 0.96f), VoidBlack)))
            .border(width = 1.dp, brush = Brush.verticalGradient(listOf(GridLine, androidx.compose.ui.graphics.Color.Transparent)), shape = RoundedCornerShape(0.dp))
            .navigationBarsPadding()
            .height(66.dp),
    ) {
        val slotWidth = maxWidth / tabs.size
        val slotWidthPx = with(LocalDensity.current) { slotWidth.toPx() }
        val pillWidth = 54.dp
        val pillOffsetPx = with(LocalDensity.current) { pillWidth.toPx() }
        // Center-target of the pill for the selected slot, spring-animated.
        val targetX = selected.ordinal * slotWidthPx + (slotWidthPx - pillOffsetPx) / 2f
        val pillX by animateOffsetAsState(
            targetValue = Offset(targetX, 0f),
            animationSpec = spring(dampingRatio = 0.58f, stiffness = 480f),
            label = "navPill",
        )

        // sliding neon pill — state read inside graphicsLayer, so zero relayouts per frame
        Box(
            Modifier
                .graphicsLayer { translationX = pillX.x }
                .width(pillWidth)
                .fillMaxHeight()
                .padding(vertical = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.radialGradient(listOf(ElectricBlue.copy(alpha = 0.20f), ElectricBlue.copy(alpha = 0.04f))))
                .border(1.dp, ElectricBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
        )

        Row(Modifier.fillMaxSize()) {
            tabs.forEach { t ->
                val isSel = t == selected
                val iconScale by animateFloatAsState(if (isSel) 1.22f else 1f, SystemMotion.springPop, label = "tabIcon")
                val iconY by animateFloatAsState(if (isSel) -2f else 0f, SystemMotion.springPop, label = "tabIconY")
                val color = if (isSel) ElectricBlue else TextMuted
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(0.88f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null, // indicator pill IS the feedback; ripple would double up
                        ) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        t.icon,
                        contentDescription = t.label,
                        tint = color,
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale; scaleY = iconScale
                            translationY = iconY * density
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}
