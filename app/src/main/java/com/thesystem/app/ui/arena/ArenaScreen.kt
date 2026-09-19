package com.thesystem.app.ui.arena

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.*
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ARENA — a proper competitive system, five clearly separated sections.
 *
 * 1 · MATCHMAKING          — username search → public record → live/scheduled war
 * 2 · RANDOM MATCHMAKING   — real queue; pairs two waiting hunters, never bots
 * 3 · PREDICTION / SPECTATOR — FREE non-redeemable points (13+, no cash value)
 * 4 · LIVE CHALLENGES      — real wars in progress / joinable lobbies
 * 5 · SCHEDULED CHALLENGES — booked wars, accept/decline, enter at the hour
 *
 * Sections marked with real backend data only: empty states are the truth,
 * placeholder matches do not exist anywhere in this file.
 */
@Composable
fun ArenaScreen(nav: NavHostController, vm: ArenaViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(s.notice, s.error) {
        s.error?.let { snack.showSnackbar(it); vm.consumeNotice() }
        s.notice?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    SystemBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin),
                verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                contentPadding = PaddingValues(vertical = Grid.S16),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SYSTEM", style = MonoLabel, color = SkyBlue)
                            Text("ARENA", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 1.5.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("PREDICTION PTS", style = MonoLabel, color = LabelGray)
                            Text("${s.predictionPoints}", style = MonoData, color = SkyBlue)
                        }
                    }
                }

                // ══ 1 · MATCHMAKING ═════════════════════════════════════════
                item { SectionTitle("1 · MATCHMAKING", PaperWhite) }
                item {
                    GlowCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Search a hunter by username, check their public record, then send the challenge.",
                            style = MaterialTheme.typography.bodySmall, color = LabelGray,
                        )
                        Spacer(Modifier.height(Grid.S8))
                        OutlinedTextField(
                            value = s.opponentQuery,
                            onValueChange = vm::onQuery,
                            label = { Text("USERNAME", style = MonoLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (s.opponentResults.isNotEmpty()) {
                            Spacer(Modifier.height(Grid.S8))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                                items(s.opponentResults.take(10), key = { it.id }) { u ->
                                    SystemChip(
                                        "@${u.username}",
                                        PaperWhite,
                                        Modifier.clickable { haptics.tick(); vm.inspect(u) },
                                    )
                                }
                            }
                        }
                        if (s.opponentQuery.length >= 2 && s.opponentResults.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("No hunter under that handle.", style = MaterialTheme.typography.bodySmall, color = FaintGray)
                        }
                    }
                }

                item {
                    GlowCard(modifier = Modifier.fillMaxWidth()) {
                        Text("WAR CONFIGURATION", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                        Spacer(Modifier.height(Grid.S8))
                        Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                            FilterChipPill("PUSH-UP", s.draftExercise == "PUSHUP") { vm.setExercise("PUSHUP") }
                            FilterChipPill("SQUAT", s.draftExercise == "SQUAT") { vm.setExercise("SQUAT") }
                        }
                        Spacer(Modifier.height(Grid.S8))
                        Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                            listOf(30, 60, 120).forEach { d ->
                                FilterChipPill("${d}s", s.draftDuration == d) { vm.setDuration(d) }
                            }
                        }
                        Spacer(Modifier.height(Grid.S12))
                        Text("SCHEDULED WAR — optional · lands in section 5", style = MonoLabel, color = LabelGray)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                            listOf(1, 3, 24).forEach { h ->
                                FilterChipPill("+${h}H", s.draftScheduledAt != null) {
                                    vm.setScheduledAt(
                                        LocalDateTime.now(ZoneId.systemDefault()).plusHours(h.toLong())
                                            .withSecond(0).withNano(0).toString() + "Z"
                                    )
                                }
                            }
                            FilterChipPill("NOW", s.draftScheduledAt == null) { vm.setScheduledAt(null) }
                        }
                        s.draftScheduledAt?.let {
                            Spacer(Modifier.height(6.dp))
                            Text("Scheduled: $it", style = MonoLabel, color = SkyBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // ══ 2 · RANDOM MATCHMAKING ══════════════════════════════════
                item { SectionTitle("2 · RANDOM MATCHMAKING", PaperWhite) }
                item {
                    RandomMatchmakingPanel(
                        s = s,
                        onExercise = { haptics.tick(); vm.setQueueExercise(it) },
                        onFind = { haptics.slam(); vm.findRandomOpponent() },
                        onCancel = { haptics.tick(); vm.cancelRandomSearch() },
                        onEnter = { battleId -> haptics.slam(); nav.navigate(Routes.battle(battleId)) },
                        onDecline = { haptics.tick(); vm.declineRandomMatch() },
                    )
                }

                // ══ 3 · PREDICTION / SPECTATOR ══════════════════════════════
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("3 · PREDICTION / SPECTATOR", PaperWhite)
                        Spacer(Modifier.weight(1f))
                        GhostButton("CLAIM +50", { haptics.tick(); vm.claimAllowance() })
                    }
                }
                item {
                    Text(
                        "Free, non-redeemable points. They cannot be bought, cashed out or withdrawn — " +
                            "predictions are a spectator sport, nothing else.",
                        style = MaterialTheme.typography.bodySmall, color = LabelGray,
                    )
                }
                if (s.hub.isEmpty()) {
                    item { EmptyState("No open boards right now. Live wars appear here as soon as they are created.") }
                } else {
                    items(s.hub.take(8), key = { it.battleId }) { row -> PredictionRow(row, vm) }
                }
                if (s.myPredictions.isNotEmpty()) {
                    item { Text("MY SLIPS", style = MonoLabel, color = LabelGray) }
                    items(s.myPredictions.take(6), key = { it.id }) { b ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("SIDE ${b.side}", style = MonoLabel, color = if (b.status == "WON") SkyBlue else LabelGray)
                            Spacer(Modifier.weight(1f))
                            Text("${b.amount} pts · ${b.status}", style = MaterialTheme.typography.bodySmall, color = LabelGray)
                        }
                    }
                }

                // ══ 4 · LIVE CHALLENGES ═════════════════════════════════════
                item { SectionTitle("4 · LIVE CHALLENGES", PaperWhite) }
                item {
                    Text(
                        "Real wars happening on the grid right now. Spectate any live board — nothing here is simulated.",
                        style = MaterialTheme.typography.bodySmall, color = LabelGray,
                    )
                }
                if (s.live.isEmpty()) {
                    item {
                        EmptyState(
                            "No live wars at this moment. Send a challenge in section 1 " +
                                "or enter the random queue in section 2 — the board lights up the moment a war starts."
                        )
                    }
                } else {
                    items(s.live.take(8), key = { it.battleId }) { row ->
                        LiveBoardRow(row) { haptics.select(); nav.navigate(Routes.battle(row.battleId)) }
                    }
                }

                // ══ 5 · SCHEDULED CHALLENGES ════════════════════════════════
                item { SectionTitle("5 · SCHEDULED CHALLENGES", PaperWhite) }
                item {
                    Text(
                        "Book a war for later: pick +1H / +3H / +24H in the war configuration above, send it from " +
                            "the hunter's record sheet, and the opponent accepts or declines here. Accepted wars open " +
                            "the War Room at the scheduled hour.",
                        style = MaterialTheme.typography.bodySmall, color = LabelGray,
                    )
                }
                if (s.matches.isEmpty()) {
                    item { EmptyState("No scheduled wars on the calendar. Book one from section 1.") }
                } else {
                    items(s.matches, key = { it.id }) { m -> ScheduledRow(m, s.myProfile?.id, vm, nav, haptics) }
                }

                // ── supporting tail: my open lobbies + daily battle blocks ────
                if (s.myBattles.isNotEmpty()) {
                    item { SectionTitle("MY OPEN WARS") }
                    items(s.myBattles.take(6), key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.navigate(Routes.battle(b.id)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${b.exerciseType} · ${b.durationSec}s", style = MonoData, color = PaperWhite)
                            Spacer(Modifier.weight(1f))
                            Text(b.status, style = MonoLabel, color = LabelGray)
                        }
                    }
                }

                item { SectionTitle("DAILY BATTLE CHALLENGES") }
                items(s.challenges, key = { it.id }) { c ->
                    GlowCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.exercise, color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "WON TODAY ${c.wins}/${c.required} · +120 XP · +25 VC",
                                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                                )
                            }
                            if (c.claimed) Text("CLAIMED", style = MonoLabel, color = LabelGray)
                            else NeonButton(
                                "CLAIM", { haptics.select(); vm.claimChallenge(c.id) },
                                color = SkyBlue, enabled = c.wins >= c.required,
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(60.dp)) }
            }

            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))

            // inspect sheet → challenge / schedule
            s.inspecting?.let { stats ->
                OpponentSheet(
                    name = s.inspectingName ?: "HUNTER",
                    stats = stats,
                    scheduled = s.draftScheduledAt != null,
                    onChallenge = { vm.dismissInspect(); vm.challenge(stats.userId) { id -> nav.navigate(Routes.battle(id)) } },
                    onSchedule = { vm.dismissInspect(); vm.schedule(stats.userId) },
                    onDismiss = { vm.dismissInspect() },
                )
            }
        }
    }
}

// ── SECTION 2 panel — the queue state machine, told honestly ────────────────

@Composable
private fun RandomMatchmakingPanel(
    s: ArenaState,
    onExercise: (String) -> Unit,
    onFind: () -> Unit,
    onCancel: () -> Unit,
    onEnter: (String) -> Unit,
    onDecline: () -> Unit,
) {
    val q = s.queue
    val waiting = q?.status == "WAITING"
    val matched = q?.status == "MATCHED" && q.battleId != null

    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Drop your handle in the queue — the System pairs you with another real hunter " +
                "waiting for the same battle. No bots. No ghosts. If nobody is waiting, you wait.",
            style = MaterialTheme.typography.bodySmall, color = LabelGray,
        )
        Spacer(Modifier.height(Grid.S12))
        Text("CHOOSE BATTLE TYPE", style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
            FilterChipPill("PUSH-UP", s.queueExercise == "PUSHUP") { if (!waiting) onExercise("PUSHUP") }
            FilterChipPill("SQUAT", s.queueExercise == "SQUAT") { if (!waiting) onExercise("SQUAT") }
        }
        Spacer(Modifier.height(Grid.S12))

        when {
            matched -> {
                // MATCH FOUND — the only state that may say so
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, PaperWhite, RoundedCornerShape(8.dp))
                        .padding(Grid.S12),
                ) {
                    Column {
                        Text("OPPONENT FOUND", color = PaperWhite, fontFamily = SystemMono,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${q?.opponentName ?: "A real hunter"} accepted the same battle — " +
                                "${q?.exercise ?: s.queueExercise} · ${q?.durationSec ?: 60}s.",
                            style = MaterialTheme.typography.bodySmall, color = LabelGray,
                        )
                    }
                }
                Spacer(Modifier.height(Grid.S8))
                NeonButton("ENTER WAR ROOM", { q?.battleId?.let(onEnter) }, Modifier.fillMaxWidth(), color = SkyBlue)
                Spacer(Modifier.height(6.dp))
                GhostButton("DECLINE — RELEASE BOTH", onDecline, Modifier.fillMaxWidth())
            }
            waiting -> {
                SearchingIndicator()
                Spacer(Modifier.height(Grid.S8))
                GhostButton("CANCEL SEARCH", onCancel, Modifier.fillMaxWidth())
            }
            else -> {
                NeonButton(
                    if (s.queueBusy) "ENTERING QUEUE…" else "FIND OPPONENT",
                    onFind, Modifier.fillMaxWidth(), color = PaperWhite, enabled = !s.queueBusy,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "You are matched only when a real opponent exists. Until then the panel says SEARCHING.",
                    style = MaterialTheme.typography.bodySmall, color = FaintGray,
                )
            }
        }
    }
}

/** The honest waiting state — a slow pulse, never a fake countdown. */
@Composable
private fun SearchingIndicator() {
    val pulse by rememberInfiniteTransition(label = "queuePulse").animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RaisedGray)
            .padding(Grid.S12),
    ) {
        Column {
            Text(
                "SEARCHING FOR OPPONENT…",
                color = PaperWhite.copy(alpha = pulse),
                fontFamily = SystemMono, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "You are live in the queue. Keep this panel open — the match lands here.",
                style = MaterialTheme.typography.bodySmall, color = LabelGray,
            )
        }
    }
}

// ── existing rows, polished ─────────────────────────────────────────────────

@Composable
private fun LiveBoardRow(row: ArenaLiveRow, onWatch: () -> Unit) {
    val stateLabel = when {
        row.iAmIn -> "YOU ARE IN"
        row.status.uppercase() == "LIVE" -> "LIVE"
        row.status.uppercase() in listOf("PENDING", "LOBBY") -> "JOINABLE"
        row.status.uppercase() == "FULL" -> "FULL"
        else -> "ENDED"
    }
    val stateColor = when {
        row.iAmIn -> SkyBlue
        row.status.uppercase() == "LIVE" -> PaperWhite
        row.status.uppercase() in listOf("PENDING", "LOBBY") -> PaperWhite
        else -> FaintGray
    }
    GlowCard(modifier = Modifier.fillMaxWidth().clickable { onWatch() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${row.playerAName ?: "?"} vs ${row.playerBName ?: "?"}",
                    color = PaperWhite, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${row.exerciseType} · ${row.durationSec}s",
                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${row.scoreA} : ${row.scoreB}", style = MonoData, color = SkyBlue)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SystemChip(stateLabel, stateColor)
                    Text(if (row.iAmIn) "ENTER" else "WATCH", style = MonoLabel, color = if (row.iAmIn) SkyBlue else LabelGray)
                }
            }
        }
    }
}

@Composable
private fun PredictionRow(row: PredictionHubRow, vm: ArenaViewModel) {
    var points by remember { mutableStateOf(10L) }
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${row.playerAName ?: "?"} vs ${row.playerBName ?: "?"}",
                    color = PaperWhite, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${row.exerciseType} · ${row.durationSec}s" +
                        (row.scheduledAt?.let { " · ${it.take(16).replace('T', ' ')}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                )
            }
            Text("x${row.oddsA ?: "—"} / x${row.oddsB ?: "—"}", style = MonoLabel, color = LabelGray)
        }
        Spacer(Modifier.height(Grid.S8))
        if (row.mySide != null) {
            Text("BACKED ${row.mySide} · ${row.myAmount} pts", style = MonoLabel, color = SkyBlue)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                Text("PTS", style = MonoLabel, color = LabelGray)
                listOf(5L, 10L, 25L).forEach { p ->
                    FilterChipPill("$p", points == p) { points = p }
                }
                Spacer(Modifier.weight(1f))
                NeonButton("BACK A", { vm.predict(row, "A", points) }, color = SkyBlue)
                Spacer(Modifier.width(6.dp))
                NeonButton("BACK B", { vm.predict(row, "B", points) }, color = LabelGray)
            }
        }
    }
}

@Composable
private fun ScheduledRow(
    m: ScheduledMatchDto,
    myId: String?,
    vm: ArenaViewModel,
    nav: NavHostController,
    haptics: com.thesystem.app.core.ui.SystemHaptics,
) {
    val incoming = m.player2Id == myId && m.status == "PENDING"
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${m.player1Name ?: "?"} vs ${m.player2Name ?: "?"}",
                    color = PaperWhite, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${m.exerciseType} · ${m.durationSeconds}s",
                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                )
                Text(
                    "${m.scheduledTime.take(16).replace('T', ' ')} · ${m.status}",
                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                )
            }
            when {
                incoming -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeonButton("ACCEPT", { haptics.select(); vm.respond(m.id, true) }, color = SkyBlue)
                    NeonButton("DECLINE", { haptics.tick(); vm.respond(m.id, false) }, color = LabelGray)
                }
                m.status == "ACCEPTED" -> Column(horizontalAlignment = Alignment.End) {
                    Text("MATCH READY", style = MonoLabel, color = SkyBlue)
                    Spacer(Modifier.height(6.dp))
                    GhostButton("WAR ROOM", { haptics.slam(); nav.navigate(Routes.battle(m.battleId)) })
                }
                m.status == "PENDING" -> GhostButton("CANCEL", { haptics.tick(); vm.cancelMatch(m.id) })
                else -> Text(m.status, style = MonoLabel, color = LabelGray)
            }
        }
    }
}

@Composable
private fun OpponentSheet(
    name: String,
    stats: HunterStatsDto,
    scheduled: Boolean,
    onChallenge: () -> Unit,
    onSchedule: () -> Unit,
    onDismiss: () -> Unit,
) {
    SystemBottomSheet(title = "HUNTER RECORD", onDismiss = onDismiss, accent = SkyBlue) {
        Text(name, color = PaperWhite, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Grid.S8))
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
            StatTile("LEVEL", "${stats.level}", PaperWhite, Modifier.weight(1f))
            StatTile("WINS", "${stats.wins}", SkyBlue, Modifier.weight(1f))
            StatTile("WIN %", "${stats.winRate}", PaperWhite, Modifier.weight(1f))
            StatTile("PACE", "${stats.pacePerMin}/m", PaperWhite, Modifier.weight(1f))
        }
        if (stats.recent.isNotEmpty()) {
            Spacer(Modifier.height(Grid.S8))
            Text("RECENT VERIFIED", style = MonoLabel, color = LabelGray)
            Spacer(Modifier.height(4.dp))
            stats.recent.take(3).forEach { r ->
                Text(
                    "${if (r.won) "WIN " else "LOSS"} ${r.scoreMe}:${r.scoreFoe} · ${r.at?.take(10) ?: ""}",
                    style = MaterialTheme.typography.bodySmall, color = if (r.won) PaperWhite else LabelGray,
                )
            }
        }
        Spacer(Modifier.height(Grid.S12))
        NeonButton("CHALLENGE LIVE", onChallenge, Modifier.fillMaxWidth(), color = SkyBlue)
        Spacer(Modifier.height(6.dp))
        GhostButton(if (scheduled) "CONFIRM SCHEDULED WAR" else "PICK A TIME ABOVE FIRST", onSchedule, Modifier.fillMaxWidth())
    }
}

@Composable
fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) SkyBlue.copy(alpha = 0.18f) else RaisedGray)
            .border(1.dp, if (selected) SkyBlue else LineStrong, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label.uppercase(), style = MonoLabel,
            color = if (selected) SkyBlue else LabelGray, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
