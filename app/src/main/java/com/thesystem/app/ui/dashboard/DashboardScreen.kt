package com.thesystem.app.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.QuestDto
import kotlinx.coroutines.launch

/** Tab 1 — Dashboard: the dopamine hub. Staggered entrance, rolling stats,
 *  XP bursts on every quest clear, level-up payday banner, armed-penalty pulse,
 *  pull-to-refresh for sync rituals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavHostController, vm: DashboardViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedCourse by remember { mutableStateOf(TrainingCourse.MONARCH) }

    // ── effect signals: bump → one-shot animation fires ══════════════════════
    val (burstSignal, fireBurst) = rememberEffectSignal()
    val (floaterSignal, fireFloater) = rememberEffectSignal()
    val (levelUpSignal, fireLevelUp) = rememberEffectSignal()
    var floaterText by remember { mutableStateOf("") }
    var previousLevel by remember { mutableIntStateOf(-1) }

    LaunchedEffect(s.profile?.level) {
        val lv = s.profile?.level ?: return@LaunchedEffect
        if (previousLevel in 1 until lv) {
            fireLevelUp(); fireBurst()
            haptics.slam()
        }
        previousLevel = lv
    }

    LaunchedEffect(s.notice, s.error) {
        s.error?.let { haptics.error(); snack.showSnackbar(it); vm.clearNotice(); return@LaunchedEffect }
        s.notice?.let { msg ->
            // "+61 XP — X cleared." → surface it as a rising floater, not a gray bar
            floaterText = msg.substringBefore(" —")
            fireFloater()
            snack.showSnackbar(msg); vm.clearNotice()
        }
    }

    SystemBackground(wallpaperUrl = s.wallpaper, wallpaperAlpha = s.wallpaperAlpha) {
        Box(Modifier.fillMaxSize()) {
            when {
                s.loading && s.profile == null -> {
                    Column(
                        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin, vertical = Grid.S16),
                        verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                    ) { SkeletonCards(4) }
                }
                else -> PullToRefreshBox(
                    isRefreshing = s.loading,
                    onRefresh = { haptics.tick(); vm.refresh() },
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                ) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = Grid.Margin),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                    contentPadding = PaddingValues(vertical = Grid.S16),
                ) {
                    item { Box(Modifier.enterAnim(0)) { Header(s, burstSignal, floaterSignal, floaterText, onProtocol = { haptics.select(); nav.navigate(com.thesystem.app.Routes.PROTOCOL) }) } }
                    s.profile?.let { p ->
                        if (p.missedDays > 0) {
                            item { Box(Modifier.enterAnim(1)) { PenaltyCard(s) } }
                        }
                        item {
                            Box(Modifier.enterAnim(2)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(Grid.S12)) {
                                    StreakTile(p.streakDays, Modifier.weight(1f))
                                    AnimatedStatTile(
                                        "Next level",
                                        SystemMath.xpRequiredForLevel(p.level + 1) - p.xp,
                                        ElectricBlue, Modifier.weight(1.15f),
                                    )
                                    StatTile("Missed", "${p.missedDays}", if (p.missedDays > 0) CrimsonRed else TextMuted, Modifier.weight(0.85f))
                                }
                            }
                        }
                    }
                    item { Box(Modifier.enterAnim(3)) { BuffsCard(s) } }
                    item {
                        Box(Modifier.enterAnim(4)) {
                            CourseCarousel(selectedCourse, onSelect = { c -> haptics.tick(); selectedCourse = c })
                        }
                    }
                    if (s.quests.isEmpty()) {
                        item { EmptyState("The System is compiling your adaptive protocol… pull to refresh.") }
                    } else {
                        item {
                            Box(Modifier.enterAnim(4)) {
                                AdaptiveQuestWindow(s.quests, selectedCourse) { q ->
                                    haptics.success()
                                    fireBurst()
                                    vm.completeQuest(q)
                                }
                            }
                        }
                    }
                    item { Box(Modifier.enterAnim(5)) { FormsStrip(s) } }
                    item { Spacer(Modifier.height(96.dp)) } // clears sticky bar + tab bar
                }
                }
            }

            // ── STICKY EXECUTION AREA — today's protocol status + jump to next open quest
            if (!s.loading && s.profile != null && s.quests.isNotEmpty()) {
                val total = s.quests.size
                val cleared = s.quests.count { it.completed }
                val allDone = cleared == total
                // header, [penalty], stats, buffs, carousel, quest window → index 4 (+penalty)
                val firstOpen = 4 + if ((s.profile?.missedDays ?: 0) > 0) 1 else 0
                StickyActionBar(
                    status = "$cleared/$total QUESTS CLEARED",
                    statusColor = if (allDone) VenomGreen else ElectricBlue,
                    actionText = if (allDone) "ALL CLEARED ✓" else "NEXT QUEST",
                    actionColor = if (allDone) VenomGreen else ElectricBlue,
                    enabled = !allDone,
                    onAction = {
                        haptics.tick()
                        scope.launch { listState.animateScrollToItem(firstOpen) }
                    },
                )
            }

            // celebration overlays — they read state, they never block composition
            XpBurst(burstSignal, color = HunterGold)
            LevelUpShockwave(levelUpSignal, color = ElectricBlue)
            levelUpSignal.takeIf { it > 0 }?.let {
                LevelUpBanner(levelUpSignal, level = previousLevel)
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))

            // one-time Player qualification modal (HUD notification language)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val prefs = remember { ctx.getSharedPreferences("system_hud", 0) }
            var showPlayerNote by remember { mutableStateOf(!prefs.getBoolean("notif_player_v1", false)) }
            SystemNotificationModal(
                visible = showPlayerNote && !s.loading && s.profile != null,
                body = "You have acquired the qualifications to be a Player. Your daily protocol is calibrated from your body metrics and grows with you. Will you accept?",
                onAccept = {
                    showPlayerNote = false
                    prefs.edit().putBoolean("notif_player_v1", true).apply()
                },
            )
        }
    }
}

// ── HEADER — the hunter's identity plate ═════════════════════════════════════

@Composable
private fun Header(s: DashboardState, burstSignal: Int, floaterSignal: Int, floaterText: String, onProtocol: () -> Unit) {
    val p = s.profile
    GlowCard(glow = rankColor(s.rank), pulse = true) {
        Box {
            // the Protocol rooms left the tab rail (2.7) — this lock is their door
            FloatingIconButton(
                icon = Icons.Default.Lock,
                contentDescription = "Protocol rooms",
                modifier = Modifier.align(Alignment.TopEnd),
                tint = NeonPurple,
                onClick = onProtocol,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 48.dp)) {
                XpRing(xp = p?.xp ?: 0, level = p?.level ?: 1, modifier = Modifier.size(110.dp), color = rankColor(s.rank))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("HUNTER @${p?.username ?: "…"}", style = MaterialTheme.typography.labelSmall)
                    Text(p?.displayName ?: "Unknown Hunter", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(Grid.S4))
                    RankBadge(s.rank)
                    Spacer(Modifier.height(Grid.S8))
                    XpProgressBar(p?.xp ?: 0, color = rankColor(s.rank))
                    Spacer(Modifier.height(Grid.S4))
                    AnimatedCounter(
                        target = p?.xp ?: 0,
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
            // XP gains erupt out of the header — the card the user looks at most
            XpGainFloater(floaterSignal, floaterText, Modifier.align(Alignment.TopEnd))
        }
    }
}

// ── PENALTY CARD — pulsing crimson: impossible to ignore, scary to keep ═════

@Composable
private fun PenaltyCard(s: DashboardState) {
    val p = s.profile ?: return
        GlowCard(pulse = true) {
        Text("! PENALTY ENGINE ARMED", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
        Text(
            "Missed days: ${p.missedDays}. Next decay tick: −${SystemMath.formatXp(s.projectedDecay)}. " +
                "Complete today's quests before midnight or degrade.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ── BUFFS ════════════════════════════════════════════════════════════════════

@Composable
private fun BuffsCard(s: DashboardState) {
    GlowCard {
        Text("ACTIVE BUFFS / PENALTIES", style = MaterialTheme.typography.labelLarge, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        if (s.buffs.isEmpty()) Text("No active modifiers. Train to ignite some.", style = MaterialTheme.typography.bodyMedium)
        s.buffs.forEach { (label, good) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(if (good) "▲" else "▼", color = if (good) VenomGreen else CrimsonRed, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = if (good) TextPrimary else CrimsonRed, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── ADAPTIVE QUEST WINDOW — BMR-scaled daily protocol, sky-blue HUD frame ────

private fun QuestDto.focus(): QuestFocus = when {
    title.contains("PUSH", ignoreCase = true) -> QuestFocus.PUSH
    title.contains("SQUAT", ignoreCase = true) -> QuestFocus.SQUAT
    else -> QuestFocus.RUN
}

private fun QuestDto.goalLabel(): String = when (focus()) {
    QuestFocus.PUSH -> "PUSH-UP"
    QuestFocus.SQUAT -> "SQUAT"
    QuestFocus.RUN -> "RUN"
}

@Composable
private fun AdaptiveQuestWindow(
    quests: List<QuestDto>,
    course: TrainingCourse,
    onLog: (QuestDto) -> Unit,
) {
    // the selected course's focus rows first, then the rest of the protocol
    val ordered = quests.sortedByDescending { it.focus() == course.focus }
    val goals = ordered.map { q ->
        QuestGoal(
            label = q.goalLabel(),
            progress = q.progress.coerceAtMost(q.targetValue),
            target = q.targetValue,
        ) { onLog(q) }
    }
    QuestWindowCard(goals)
}

// ── FORMS STRIP — power rolls up; mystery stays mysterious ══════════════════

@Composable
private fun FormsStrip(s: DashboardState) {
    GlowCard(glow = NeonPurple) {
        Text("FORM EVOLUTION", style = MaterialTheme.typography.labelLarge, color = NeonPurple)
        Spacer(Modifier.height(6.dp))
        if (s.forms.isEmpty()) {
            Text(
                "??? — The mechanics of your Mystery Power reveal themselves at level ${SystemMath.FORM_UNLOCK_LEVELS[0]} (Form 1).",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.forms.take(5).forEach { f ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("F${f.formIndex}", color = HunterGold, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    AnimatedCounter(
                        target = f.computedPower.toLong(),
                        color = NeonPurple,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        format = { "$it" },
                    )
                }
            }
        }
    }
}
