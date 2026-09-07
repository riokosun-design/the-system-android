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
                    item { Box(Modifier.enterAnim(4)) { SectionTitle("Daily Anime Quests — Leguna S.1 AI") } }
                    if (s.quests.isEmpty()) item { EmptyState("Leguna is compiling your daily protocol… pull to refresh.") }
                    items(s.quests, key = { it.id }) { q ->
                        // PENALTY RISK tags arm in the evening or once decay has begun
                        val lateDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 21
                        val risk = (s.profile?.missedDays ?: 0) > 0 || lateDay
                        QuestCard(q, penaltyRisk = risk) {
                            haptics.success()
                            fireBurst()
                            vm.completeQuest(q)
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
                val base = 4 + if ((s.profile?.missedDays ?: 0) > 0) 1 else 0
                val firstOpen = base + s.quests.indexOfFirst { !it.completed }
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
    GlowCard(glow = CrimsonRed, pulse = true) {
        Text("⚠ PENALTY ENGINE ARMED", color = CrimsonRed, style = MaterialTheme.typography.titleMedium)
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
    val allGood = s.buffs.isNotEmpty() && s.buffs.all { it.second }
    GlowCard(glow = if (allGood) VenomGreen else if (s.buffs.isEmpty()) TextMuted else WarningAmber) {
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

// ── QUEST CARD — animated fill, press-squish, CLEARED flips green ═══════════

@Composable
private fun QuestCard(q: QuestDto, penaltyRisk: Boolean = false, onComplete: () -> Unit) {
    val done = q.completed
    val progress by animateFloatAsState(
        targetValue = if (q.targetValue > 0) (q.progress.toFloat() / q.targetValue).coerceIn(0f, 1f) else 0f,
        animationSpec = SystemMotion.springSoft,
        label = "questFill",
    )
    val doneAlpha by animateFloatAsState(if (done) 1f else 0f, label = "questDone")
    // DESIGN 2.5 MONARCH EDGE — authentic Solo Leveling quest window:
    // chamfered hologram slab + bracket status tags, nothing else screams.
    val accent = if (done) VenomGreen else ElectricBlue
    HudFrameCard(accent = accent, glow = !done, modifier = Modifier.pressScale(0.98f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[ DAILY QUEST ]", color = TextMuted, fontSize = 9.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            when {
                done -> HudTag("COMPLETE", VenomGreen)
                penaltyRisk -> HudTag("PENALTY RISK", CrimsonRed)
                else -> HudTag("IN PROGRESS", ElectricBlue)
            }
        }
        Spacer(Modifier.height(Grid.S8))
        Text(q.title, style = MaterialTheme.typography.titleMedium, color = if (done) TextMuted else TextPrimary)
        Spacer(Modifier.height(Grid.S8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(SurfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(progress)
                            .background(if (done) VenomGreen else ElectricBlue, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    )
                }
                Spacer(Modifier.height(Grid.S8))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SystemChip("+${q.xpReward} XP", HunterGold)
                    Spacer(Modifier.width(Grid.S8))
                    Text("${q.progress}/${q.targetValue}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(contentAlignment = Alignment.Center) {
                // LOG fades out / CLEARED stamps in — crossfade on one slot
                if (doneAlpha < 1f) {
                    Box(Modifier.graphicsLayer { alpha = 1f - doneAlpha }) {
                        NeonButton("LOG", onComplete, color = ElectricBlue)
                    }
                }
                if (doneAlpha > 0f) {
                    Text(
                        "CLEARED",
                        color = VenomGreen,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.graphicsLayer {
                            alpha = doneAlpha
                            val s = 0.8f + 0.2f * doneAlpha
                            scaleX = s; scaleY = s
                        },
                    )
                }
            }
        }
    }
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
