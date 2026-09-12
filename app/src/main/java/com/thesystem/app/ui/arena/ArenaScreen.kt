package com.thesystem.app.ui.arena

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.*

/** Tab 3 — Arena: realtime battles, clan tournaments, prediction pools, leaderboards. */
@Composable
fun ArenaScreen(nav: NavHostController, vm: ArenaViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    var subTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("BATTLES", "EVENTS", "PREDICT", "RANKS")

    SystemBackground(wallpaperAlpha = 0.12f) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Grid.Margin),
            ) {
                Text("THE ARENA", style = MaterialTheme.typography.headlineMedium, color = PaperWhite, modifier = Modifier.weight(1f))
                VcChip(s.profile?.vcBalance ?: 0)
            }
            Spacer(Modifier.height(Grid.S12))
            // full-bleed tab rail — labels stay on one line even on 320dp devices
            SystemTabBar(tabs = tabs, selected = subTab, onSelect = { i -> haptics.select(); subTab = i })
            Box(Modifier.fillMaxSize().padding(horizontal = Grid.Margin)) {
                // skeleton shimmer while the arena resolves — never a dead spinner
                if (s.loading) SkeletonCards(4) else when (subTab) {
                    0 -> BattlesTab(s, vm, nav)
                    1 -> TournamentsTab(s, vm)
                    2 -> PredictTab(s, vm)
                    3 -> LeaderboardTab(s)
                }
            }
        }
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snack) }
    }
}

// ── BATTLES ──────────────────────────────────────────────────────────────────
@Composable
private fun BattlesTab(s: ArenaState, vm: ArenaViewModel, nav: NavHostController) {
    val haptics = rememberSystemHaptics()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            GlowCard(glow = CrimsonRed) {
                Text("SUMMON A RIVAL", style = MaterialTheme.typography.labelLarge, color = CrimsonRed)
                OutlinedTextField(
                    value = s.opponentQuery,
                    onValueChange = vm::onOpponentQuery,
                    label = { Text("Search @username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CrimsonRed, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                )
                s.opponentResults.take(4).forEach { u ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("@${u.username} · LV ${u.level}", color = TextPrimary, modifier = Modifier.weight(1f))
                        NeonButton("CHALLENGE", { haptics.select(); vm.challenge(u) { id -> nav.navigate(Routes.battle(id)) } }, color = CrimsonRed)
                    }
                }
            }
        }
        item { SectionTitle("Live & Open Battles", CrimsonRed) }
        if (s.openBattles.isEmpty()) item { EmptyState("No wars raging. Summon a rival above.") }
        items(s.openBattles, key = { it.id }) { b ->
            val meIn = b.playerA == s.profile?.id || b.playerB == s.profile?.id
            // DESIGN 2.5 MONARCH EDGE — live battles are System windows: chamfer + hologram frame
            HudFrameCard(
                accent = if (b.status == "LIVE") CrimsonRed else if (meIn) ElectricBlue else TextMuted,
                glow = b.status == "LIVE",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("@${b.playerAName ?: "?"} vs @${b.playerBName ?: "?"}", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text("${b.scoreA} — ${b.scoreB} · ${b.status}", style = MaterialTheme.typography.bodyMedium,
                            color = if (b.status == "LIVE") CrimsonRed else TextMuted)
                    }
                    when {
                        meIn && b.status != "FINISHED" -> NeonButton("ENTER", { nav.navigate(Routes.battle(b.id)) })
                        b.status == "LIVE" -> Text("[ LIVE ]", color = PaperWhite, style = MonoLabel)
                        b.status == "FINISHED" -> Text(if (b.winner == s.profile?.id) "VICTORY" else "CLOSED", color = VenomGreen, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ── TOURNAMENTS (entry-fee gate) ─────────────────────────────────────────────
@Composable
private fun TournamentsTab(s: ArenaState, vm: ArenaViewModel) {
    val haptics = rememberSystemHaptics()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { SectionTitle("Solo & Clan Tournaments", HunterGold) }
        if (s.tournaments.isEmpty()) item { EmptyState("The admins have not opened a bracket yet.") }
        items(s.tournaments, key = { it.id }) { t ->
            val joined = t.id in s.myTournamentIds
            val canAfford = (s.profile?.vcBalance ?: 0) >= t.entryFeeVc
            GlowCard(glow = if (t.type == "CLAN") NeonPurple else HunterGold) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            listOfNotNull(
                                if (t.type == "CLAN") "CLAN WAR" else "SOLO",
                                t.status,
                                t.startsAt?.let { "opens ${it.take(10)}" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (t.type == "CLAN" && s.myClan == null) {
                            Text("Requires Shadow Guild membership", color = CrimsonRed, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        VcChip(t.entryFeeVc)
                        Spacer(Modifier.height(6.dp))
                        when {
                            joined -> Text("ENLISTED", color = VenomGreen, style = MaterialTheme.typography.labelLarge)
                            t.status != "OPEN" -> Text(t.status, color = TextMuted, style = MaterialTheme.typography.labelLarge)
                            !canAfford -> Text("TOO POOR", color = CrimsonRed, style = MaterialTheme.typography.labelLarge)
                            else -> NeonButton("PAY & JOIN", { haptics.success(); vm.joinTournament(t) }, color = HunterGold)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ── PREDICT (dynamic betting, 15% house cut) ─────────────────────────────────
@Composable
private fun PredictTab(s: ArenaState, vm: ArenaViewModel) {
    val haptics = rememberSystemHaptics()
    var betPool by remember { mutableStateOf<PoolDto?>(null) }
    var betBattle by remember { mutableStateOf<BattleDto?>(null) }
    var betSide by remember { mutableStateOf("A") }
    var betAmount by remember { mutableStateOf("100") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { SectionTitle("Prediction Pools — 15% house cut", ElectricBlue) }
        if (s.pools.isEmpty()) item { EmptyState("No open pools. Pools spawn automatically when a battle is created.") }
        items(s.pools, key = { it.first.id }) { (pool, battle) ->
            GlowCard(glow = ElectricBlue) {
                Text("@${battle?.playerAName ?: "A"} vs @${battle?.playerBName ?: "B"}", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    "Pool ${SystemMath.formatVc(pool.totalPoolVc)} · A ${SystemMath.formatVc(pool.totalAVc)} / B ${SystemMath.formatVc(pool.totalBVc)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                NeonButton("PLACE PREDICTION", { haptics.select(); betPool = pool; betBattle = battle; betSide = "A"; betAmount = "100" }, Modifier.fillMaxWidth())
            }
        }
        item { SectionTitle("My Bets", ElectricBlue) }
        items(s.myBets, key = { it.id }) { bet ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Side ${bet.side}", style = MaterialTheme.typography.labelSmall, color = ElectricBlue, modifier = Modifier.width(60.dp))
                Text(SystemMath.formatVc(bet.amountVc), color = TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    when (bet.status) { "WON" -> "+${SystemMath.formatVc(bet.payoutVc ?: 0)} WON"; "LOST" -> "LOST"; "REFUNDED" -> "REFUNDED"; else -> "OPEN" },
                    color = when (bet.status) { "WON" -> VenomGreen; "LOST" -> CrimsonRed; else -> TextMuted },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }

    // Betting sheet with live payout preview: (Bet / WinningPool) × TotalPool × 0.85
    betPool?.let { pool ->
        val amount = betAmount.toLongOrNull() ?: 0L
        val projectedWinningPool = (if (betSide == "A") pool.totalAVc else pool.totalBVc) + amount
        val projectedTotal = pool.totalPoolVc + amount
        val payout = SystemMath.expectedPayout(amount, projectedWinningPool, projectedTotal)
        AlertDialog(
            onDismissRequest = { betPool = null },
            containerColor = SurfaceDark,
            title = { Text("PREDICT — @${betBattle?.playerAName ?: "A"} vs @${betBattle?.playerBName ?: "B"}", color = ElectricBlue) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonButton("SIDE A — @${betBattle?.playerAName ?: "A"}", { betSide = "A" }, Modifier.weight(1f), color = if (betSide == "A") ElectricBlue else TextMuted)
                        NeonButton("SIDE B — @${betBattle?.playerBName ?: "B"}", { betSide = "B" }, Modifier.weight(1f), color = if (betSide == "B") CrimsonRed else TextMuted)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = betAmount, onValueChange = { betAmount = it.filter(Char::isDigit).take(9) },
                        label = { Text("Bet (VC)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ElectricBlue, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Projected payout: ${SystemMath.formatVc(payout)}", color = HunterGold, fontWeight = FontWeight.Bold)
                    Text("Formula: (your bet ${SystemMath.formatVc(amount)} ÷ winning pool ${SystemMath.formatVc(projectedWinningPool)}) × (total ${SystemMath.formatVc(projectedTotal)} × 0.85)",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Balance: ${SystemMath.formatVc(s.profile?.vcBalance ?: 0)}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                NeonButton("LOCK BET", {
                    haptics.success(); vm.placeBet(pool, betSide, amount); betPool = null
                }, enabled = amount > 0 && amount <= (s.profile?.vcBalance ?: 0), color = HunterGold)
            },
            dismissButton = { TextButton(onClick = { betPool = null }) { Text("Cancel", color = TextMuted) } },
        )
    }
}

// ── RANKS — ROUND 5: rows spring to new positions, climbers show ▲▼ deltas ═══
@Composable
private fun LeaderboardTab(s: ArenaState) {
    val rows = s.leaders.take(50)
    val positions = rows.mapIndexed { i, u -> u.id to i }.toMap()
    var prevPositions by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    SideEffect { prevPositions = positions } // after every successful composition, positions become history
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { SectionTitle("Global XP Leaderboard", NeonPurple) }
        items(rows, key = { it.id }) { u ->
            val i = positions[u.id] ?: 0
            val delta = prevPositions[u.id]?.let { it - i } ?: 0 // + = climbed, − = overtaken
            val rank = SystemMath.rankFor(u.level, u.missedDays)
            GlowCard(
                glow = if (u.id == s.profile?.id) HunterGold else SurfaceHigh,
                pulse = u.id == s.profile?.id, // your row breathes — always findable
                modifier = Modifier.animateItem(), // smooth teleport when the ladder reorders
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${i + 1}", color = HunterGold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text("@${u.username}", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("LV ${u.level} · ${SystemMath.formatXp(u.xp)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (delta > 0) Text("▲$delta", color = VenomGreen, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                    else if (delta < 0) Text("▼${-delta}", color = CrimsonRed, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                    RankBadge(rank)
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
