package com.thesystem.app.ui.tabs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.SystemMotion
import com.thesystem.app.core.ui.pressScale
import com.thesystem.app.core.ui.rememberSystemHaptics
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.ui.arena.ArenaScreen
import com.thesystem.app.ui.dashboard.DashboardScreen
import com.thesystem.app.ui.profile.ProfileScreen
import com.thesystem.app.ui.market.MarketScreen

/**
 * Strict 4-tab rail. The training catalog and the Black Room are reached from
 * Status and the Market — they are not tabs.
 */
enum class SystemTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Status", Icons.Default.Dashboard),
    ARENA("Arena", Icons.Default.Whatshot),
    MARKET("Market", Icons.Default.Storefront),
    PROFILE("Vault", Icons.Default.Person),
}

@Composable
fun MainTabs(profile: UserDto, nav: NavHostController, onSignOut: () -> Unit) {
    var tab by remember { mutableStateOf(SystemTab.DASHBOARD) }
    val haptics = rememberSystemHaptics()

    Scaffold(
        containerColor = InkBlack,
        bottomBar = {
            SystemBottomBar(
                selected = tab,
                onSelect = { t -> if (t != tab) { haptics.select(); tab = t } },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(SystemMotion.snap) + slideInVertically(tween(360)) { it / 28 })
                    .togetherWith(fadeOut(SystemMotion.snap))
            },
            label = "tabSwitch",
            modifier = Modifier.padding(padding),
        ) { t ->
            when (t) {
                SystemTab.DASHBOARD -> DashboardScreen(nav = nav)
                SystemTab.ARENA -> ArenaScreen(nav = nav)
                SystemTab.MARKET -> MarketScreen(nav = nav)
                SystemTab.PROFILE -> ProfileScreen(profile = profile, nav = nav, onSignOut = onSignOut)
            }
        }
    }
}

/** MONOCHROME TAB RAIL — flat black, hairline top; 2dp white underline on active. */
@Composable
private fun SystemBottomBar(selected: SystemTab, onSelect: (SystemTab) -> Unit) {
    val tabs = SystemTab.entries
    Column(
        Modifier
            .fillMaxWidth()
            .background(InkBlack)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            tabs.forEach { t ->
                val isSel = t == selected
                val tint = if (isSel) PaperWhite else FaintGray
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(0.9f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.label.uppercase(),
                        color = tint,
                        fontSize = 10.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(22.dp)
                            .background(if (isSel) PaperWhite else androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }
    }
}
