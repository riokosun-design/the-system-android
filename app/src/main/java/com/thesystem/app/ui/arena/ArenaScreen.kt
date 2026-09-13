package com.thesystem.app.ui.arena

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
    var duelDuration by remember { mutableIntStateOf(60) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            GlowCard(glow = CrimsonRed) {
                Text("SUMMON A RIVAL", style = MaterialTheme.typography.labelLarge, color = CrimsonRed)
                Spacer(Modifier.height(6.dp))
                Text("DUEL LENGTH", style = MonoLabel)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(30, 60, 120).forEach { secs ->
                        val picked = duelDuration == secs
                        val label = if (secs < 60) "${secs}s" else "${secs / 60} min"
                        NeonButton(label, { haptics.tick(); duelDuration = secs }, Modifier.weight(1f),
                            color = if (picked) PaperWhite else LabelGray)
                    }
                }
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
                        NeonButton("CHALLENGE", { haptics.select(); vm.challenge(u, duelDuration) { id -> nav.navigate(Routes.battle(id)) } }, color = CrimsonRed)
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
                        meIn && b.status == "LOBBY" -> Column(horizontalAlignment = Alignment.End) {
                            val readyCount = listOf(b.playerAReady, b.playerBReady).count { it }
                            Text("READY $readyCount/2", color = PaperWhite, style = MonoLabel)
                            Spacer(Modifier.height(4.dp))
                            NeonButton("ENTER LOBBY", { nav.navigate(Routes.battle(b.id)) })
                        }
                        meIn && b.status == "LIVE" -> NeonButton("ENTER WAR", { nav.navigate(Routes.battle(b.id)) })
                        b.status == "LOBBY" -> Text("[ OPEN LOBBY · ${b.durationSec}s ]", color = LabelGray, style = MonoLabel)
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

// ── PREDICT (prediction pools; "bet/wager" language is deliberately absent) ──
@Composable
private fun PredictTab(s: ArenaState, vm: ArenaViewModel) {
    val haptics = rememberSystemHaptics()
    var poolSheet by remember { mutableStateOf<PoolDto?>(null) }
    var sheetBattle by remember { mutableStateOf<BattleDto?>(null) }
    var sheetSide by remember { mutableStateOf("A") }
    var sheetAmount by remember { mutableStateOf("100") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { SectionTitle("Prediction Pools · 15% platform cut · 20% to winner", ElectricBlue) }
        if (s.pools.isEmpty()) item { EmptyState("No open pools. Pools spawn automatically when a battle is created.") }
        items(s.pools, key = { it.first.id }) { (pool, battle) ->
            GlowCard(glow = ElectricBlue) {
                // combatant row — tap a name to inspect the hunter before backing
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HunterNameChip(battle?.playerA, battle?.playerAName, vm, Modifier.weight(1f))
                    Text(" VS ", style = MonoLabel)
                    HunterNameChip(battle?.playerB, battle?.playerBName, vm, Modifier.weight(1f), alignEnd = true)
                }
                Text(
                    "Pool ${SystemMath.formatVc(pool.totalPoolVc)} · A ${SystemMath.formatVc(pool.totalAVc)} / B ${SystemMath.formatVc(pool.totalBVc)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                NeonButton("PREDICT — BACK A HUNTER", {
                    haptics.select(); poolSheet = pool; sheetBattle = battle; sheetSide = "A"; sheetAmount = "100"
                }, Modifier.fillMaxWidth())
            }
        }
        item { SectionTitle("My Predictions", ElectricBlue) }
        items(s.myBets, key = { it.id }) { pred ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SIDE ${pred.side}", style = MaterialTheme.typography.labelSmall, color = ElectricBlue, modifier = Modifier.width(70.dp))
                Text(SystemMath.formatVc(pred.amountVc), color = TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    when (pred.status) { "WON" -> "+${SystemMath.formatVc(pred.payoutVc ?: 0)} WON"; "LOST" -> "MISSED"; "REFUNDED" -> "REFUNDED"; else -> "OPEN" },
                    color = when (pred.status) { "WON" -> VenomGreen; "LOST" -> CrimsonRed; else -> TextMuted },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }

    // Inspection sheet — win rate / level / pace / last 5 wars before predicting
    s.inspectingUserId?.let {
        HunterInspectDialog(s, vm)
    }

    // Prediction sheet with live payout preview: (Back / WinningPool) × Total × 0.85
    poolSheet?.let { pool ->
        val amount = sheetAmount.toLongOrNull() ?: 0L
        val projectedWinningPool = (if (sheetSide == "A") pool.totalAVc else pool.totalBVc) + amount
        val projectedTotal = pool.totalPoolVc + amount
        val payout = SystemMath.expectedPayout(amount, projectedWinningPool, projectedTotal)
        AlertDialog(
            onDismissRequest = { poolSheet = null },
            containerColor = SurfaceDark,
            title = { Text("BACK A HUNTER — @${sheetBattle?.playerAName ?: "A"} vs @${sheetBattle?.playerBName ?: "B"}", color = ElectricBlue) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonButton("BACK A · @${sheetBattle?.playerAName ?: "A"}", { sheetSide = "A" }, Modifier.weight(1f), color = if (sheetSide == "A") PaperWhite else LabelGray)
                        NeonButton("BACK B · @${sheetBattle?.playerBName ?: "B"}", { sheetSide = "B" }, Modifier.weight(1f), color = if (sheetSide == "B") PaperWhite else LabelGray)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sheetAmount, onValueChange = { sheetAmount = it.filter(Char::isDigit).take(9) },
                        label = { Text("Backing amount (VC)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ElectricBlue, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Projected payout: ${SystemMath.formatVc(payout)}", color = PaperWhite, fontWeight = FontWeight.Bold)
                    Text("Formula: (your backing ${SystemMath.formatVc(amount)} ÷ winning side pool ${SystemMath.formatVc(projectedWinningPool)}) × (total ${SystemMath.formatVc(projectedTotal)} × 0.85)",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Balance: ${SystemMath.formatVc(s.profile?.vcBalance ?: 0)}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                NeonButton("LOCK PREDICTION", {
                    haptics.success(); vm.placePrediction(pool, sheetSide, amount); poolSheet = null
                }, enabled = amount > 0 && amount <= (s.profile?.vcBalance ?: 0))
            },
            dismissButton = { TextButton(onClick = { poolSheet = null }) { Text("Cancel", color = TextMuted) } },
        )
    }
}

/** Tappable hunter label that opens the stats inspection sheet. */
@Composable
private fun HunterNameChip(
    id: String?,
    name: String?,
    vm: ArenaViewModel,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    val haptics = rememberSystemHaptics()
    if (id == null) { Text("@${name ?: "?"}", modifier = modifier); return }
    Text(
        "@${name ?: "?"}  ⓘ",
        color = PaperWhite,
        style = MaterialTheme.typography.titleMedium,
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { haptics.tick(); vm.inspectHunter(id, name ?: "?") },
    )
}

/** Pre-prediction hunter dossier: win rate, level, pace, recent wars. */
@Composable
private fun HunterInspectDialog(s: ArenaState, vm: ArenaViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissInspection,
        containerColor = SurfaceDark,
        title = { Text("@${s.inspectingName}", color = ElectricBlue) },
        text = {
            Column {
                if (s.statsLoading || s.hunterStats == null) {
                    Text("Compiling dossier…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val st = s.hunterStats
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("LEVEL", "${st.level}", modifier = Modifier.weight(1f))
                        StatTile("WIN RATE", "${st.winRate}%", modifier = Modifier.weight(1f))
                        StatTile("WARS", "${st.total}", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("W / L", "${st.wins}/${st.losses}", modifier = Modifier.weight(1f))
                        StatTile("AVG REPS", "${st.avgScore}", modifier = Modifier.weight(1f))
                        StatTile("PACE/MIN", "${st.pacePerMin}", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("RECENT 5 WARS", style = MonoLabel)
                    Spacer(Modifier.height(4.dp))
                    st.recent.forEach { r ->
                        val tag = if (r.won) "W  " else "L  "
                        Text("$tag ${r.scoreMe} – ${r.scoreFoe}",
                            color = if (r.won) PaperWhite else LabelGray,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    if (st.recent.isEmpty()) Text("No wars on record yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { NeonButton("CLOSE", vm::dismissInspection) },
    )
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
