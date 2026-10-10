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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.SystemMotion
import com.thesystem.app.core.ui.pressScale
import com.thesystem.app.core.ui.rememberSystemHaptics
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.ui.arena.ArenaScreen
import com.thesystem.app.ui.chess.ChessHubScreen
import com.thesystem.app.ui.dashboard.DashboardScreen
import com.thesystem.app.ui.profile.ProfileScreen
import com.thesystem.app.ui.rank.RankScreen
import com.thesystem.app.ui.squad.SquadChatsScreen

/**
 * 6-slot rail (STEP 14: the SQUAD network takes the slot the Market vacated in
 * STEP 8). The training catalog, the Black Room and the Supply door are reached
 * from inside screens — they are not tabs.
 */
enum class SystemTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Status", Icons.Default.Dashboard),
    RANK("Rank", Icons.Default.MilitaryTech),
    ARENA("Arena", Icons.Default.Whatshot),
    CHESS("Chess", Icons.Default.Extension),
    SQUAD("Squad", Icons.Default.Groups),
    PROFILE("Vault", Icons.Default.Person),
}

@Composable
fun MainTabs(profile: UserDto, nav: NavHostController, onSignOut: () -> Unit) {
    var tab by remember { mutableStateOf(SystemTab.DASHBOARD) }
    val haptics = rememberSystemHaptics()
    val hubVm: com.thesystem.app.ui.arena.NotificationsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val hub by hubVm.state.collectAsStateWithLifecycle()
    var hubOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = InkBlack,
        bottomBar = {
            SystemBottomBar(
                selected = tab,
                onSelect = { t -> if (t != tab) { haptics.select(); tab = t } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // ── GLOBAL COMMAND HEADER (spec Phase 4) — the war-invite bell is
            //    persistent across every tab; badge = pending challenges. ────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(InkBlack)
                    .statusBarsPadding()
                    .height(40.dp),
            ) {
                NotificationBell(
                    count = hub.incoming.size,
                    onClick = { haptics.select(); hubOpen = true },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                )
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(SystemMotion.snap) + slideInVertically(tween(360)) { it / 28 })
                        .togetherWith(fadeOut(SystemMotion.snap))
                },
                label = "tabSwitch",
                modifier = Modifier.weight(1f),
            ) { t ->
                when (t) {
                    SystemTab.DASHBOARD -> DashboardScreen(nav = nav)
                    SystemTab.RANK -> RankScreen()
                    SystemTab.ARENA -> ArenaScreen(nav = nav)
                    SystemTab.CHESS -> ChessHubScreen(nav = nav)
                    SystemTab.SQUAD -> SquadChatsScreen(nav = nav)
                    SystemTab.PROFILE -> ProfileScreen(profile = profile, nav = nav, onSignOut = onSignOut)
                }
            }
        }
    }

    if (hubOpen) {
        WarInviteHub(
            state = hub,
            onRespond = { c, accept ->
                hubVm.respond(c, accept) { battleId ->
                    hubOpen = false
                    nav.navigate(Routes.battle(battleId))
                }
            },
            onDismiss = { hubOpen = false; hubVm.clearNotice() },
        )
    }
}

/** The bell — dim when silent, white when invites wait, cyan badge carries the count. */
@Composable
private fun NotificationBell(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(38.dp).pressScale(0.88f).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (count > 0) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
            contentDescription = "war invites",
            tint = if (count > 0) PaperWhite else FaintGray,
            modifier = Modifier.size(21.dp),
        )
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-1).dp)
                    .size(15.dp)
                    .background(SkyBlue, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${count.coerceAtMost(9)}",
                    color = InkBlack, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Invite hub — accept spawns the battle server-side (mig 028), then both drop in. */
@Composable
private fun WarInviteHub(
    state: com.thesystem.app.ui.arena.NotificationsViewModel.HubState,
    onRespond: (com.thesystem.app.data.model.MatchChallengeDto, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelGray,
        title = { Text("WAR INVITES", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp) },
        text = {
            Column {
                state.answered?.let { Text(it, color = LabelGray, fontSize = 12.sp) ; Spacer(Modifier.height(6.dp)) }
                state.error?.let { Text(it, color = LabelGray, fontSize = 12.sp); Spacer(Modifier.height(6.dp)) }
                if (state.incoming.isEmpty()) {
                    Text(
                        "No pending challenges. When a hunter calls you out by name, it lands here within seconds.",
                        color = LabelGray, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                } else {
                    state.incoming.forEach { c ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(Color(0xFF121417), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                        ) {
                            Text(c.challengerLabel, color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                (if (c.exercise == "SQUAT") "Squat war" else "Push-up war") + " · ${c.durationSec}s",
                                color = LabelGray, fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) {
                                    com.thesystem.app.core.ui.GhostButton(
                                        if (state.busyId == c.id) "…" else "DECLINE",
                                        { onRespond(c, false) }, Modifier.fillMaxWidth(),
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    com.thesystem.app.core.ui.NeonButton(
                                        if (state.busyId == c.id) "…" else "ACCEPT",
                                        { onRespond(c, true) }, Modifier.fillMaxWidth(), color = SkyBlue,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("CLOSE", color = LabelGray) }
        },
    )
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
