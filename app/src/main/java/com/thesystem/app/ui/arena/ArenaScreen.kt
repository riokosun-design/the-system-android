package com.thesystem.app.ui.arena

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ARENA — three clearly ordered sections.
 *
 * 1 MATCHMAKING — search a username → inspect their public battle record →
 *   challenge LIVE (push-up / squat) or SCHEDULE a war for a date and time.
 * 2 PREDICTION / SPECTATOR — back a board with FREE, non-redeemable points
 *   (13+ product: no cash, no purchase, no withdrawal, no payout) and WATCH a
 *   live board through the war-room engine.
 * 3 CHALLENGES — created / received / scheduled / completed history.
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
                item { SectionTitle("1 · MATCHMAKING", SkyBlue) }
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
                        Spacer(Modifier.height(Grid.S8))
                        Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                            s.opponentResults.take(8).forEach { u ->
                                SystemChip(
                                    "@${u.username}",
                                    PaperWhite,
                                    Modifier.clickable { haptics.tick(); vm.inspect(u) },
                                )
                            }
                        }
                        if (s.opponentQuery.length >= 2 && s.opponentResults.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("No hunter under that handle.", style = MaterialTheme.typography.bodySmall, color = FaintGray)
                        }
                    }
                }

                // draft selector (exercise + length + optional schedule)
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
                        Text("SCHEDULED WAR — optional", style = MonoLabel, color = LabelGray)
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
                            Text("Scheduled: $it", style = MonoLabel, color = SkyBlue)
                        }
                    }
                }

                // live boards (spectate)
                if (s.live.isNotEmpty()) {
                    item { Text("LIVE NOW", style = MonoLabel, color = SkyBlue) }
                    items(s.live.take(6), key = { it.battleId }) { row ->
                        LiveBoardRow(row) { haptics.select(); nav.navigate(Routes.battle(row.battleId)) }
                    }
                }

                // ══ 2 · PREDICTION / SPECTATOR ══════════════════════════════
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("2 · PREDICTION / SPECTATOR", SkyBlue)
                        Spacer(Modifier.weight(1f))
                        GhostButton("CLAIM +50", { vm.claimAllowance() })
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

                // ══ 3 · CHALLENGES ══════════════════════════════════════════
                item { SectionTitle("3 · CHALLENGES") }
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
                            else NeonButton("CLAIM", { vm.claimChallenge(c.id) }, color = SkyBlue,
                                enabled = c.wins >= c.required)
                        }
                    }
                }

                // received / sent scheduled wars
                if (s.matches.isNotEmpty()) {
                    item { Text("SCHEDULED WARS", style = MonoLabel, color = SkyBlue) }
                    items(s.matches, key = { it.id }) { m -> ScheduledRow(m, s.myProfile?.id, vm, nav) }
                }

                // open lobbies + history
                if (s.myBattles.isNotEmpty()) {
                    item { Text("MY OPEN WARS", style = MonoLabel, color = LabelGray) }
                    items(s.myBattles.take(6), key = { it.id }) { b ->
                        Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.battle(b.id)) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${b.exerciseType} · ${b.durationSec}s", style = MonoData, color = PaperWhite)
                            Spacer(Modifier.weight(1f))
                            Text(b.status, style = MonoLabel, color = LabelGray)
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

@Composable
private fun LiveBoardRow(row: ArenaLiveRow, onWatch: () -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth().clickable { onWatch() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${row.playerAName} vs ${row.playerBName}", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${row.exerciseType} · ${row.durationSec}s · ${row.status}" +
                        if (row.iAmIn) " · YOU ARE IN" else "",
                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                )
            }
            Text("${row.scoreA} : ${row.scoreB}", style = MonoData, color = SkyBlue)
            Spacer(Modifier.width(Grid.S12))
            Text(if (row.iAmIn) "ENTER" else "WATCH", style = MonoLabel, color = if (row.iAmIn) SkyBlue else LabelGray)
        }
    }
}

@Composable
private fun PredictionRow(row: PredictionHubRow, vm: ArenaViewModel) {
    var points by remember { mutableStateOf(10L) }
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${row.playerAName} vs ${row.playerBName}", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
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
private fun ScheduledRow(m: ScheduledMatchDto, myId: String?, vm: ArenaViewModel, nav: NavHostController) {
    val incoming = m.player2Id == myId && m.status == "PENDING"
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${m.player1Name} vs ${m.player2Name}",
                    color = PaperWhite, style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${m.exerciseType} · ${m.durationSeconds}s · ${m.scheduledTime.take(16).replace('T', ' ')} · ${m.status}",
                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                )
            }
            when {
                incoming -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeonButton("ACCEPT", { vm.respond(m.id, true) }, color = SkyBlue)
                    NeonButton("DECLINE", { vm.respond(m.id, false) }, color = LabelGray)
                }
                m.status == "ACCEPTED" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MATCH READY", style = MonoLabel, color = SkyBlue)
                    GhostButton("WAR ROOM", { nav.navigate(Routes.battle(m.battleId)) })
                }
                m.status == "PENDING" -> GhostButton("CANCEL", { vm.cancelMatch(m.id) })
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
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.78f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(PanelGray)
                .border(1.dp, SkyBlue.copy(alpha = 0.45f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}
                .padding(Grid.S16),
        ) {
            Text("HUNTER RECORD", style = MonoLabel, color = SkyBlue)
            Text(name, color = PaperWhite, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Grid.S8))
            Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                StatTile("LEVEL", "${stats.level}", PaperWhite, Modifier.weight(1f))
                StatTile("WINS", "${stats.wins}", SkyBlue, Modifier.weight(1f))
                StatTile("WIN %", "${stats.winRate}", PaperWhite, Modifier.weight(1f))
                StatTile("PACE", "${stats.pacePerMin}/m", PaperWhite, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Grid.S8))
            if (stats.recent.isNotEmpty()) {
                Text("RECENT VERIFIED", style = MonoLabel, color = LabelGray)
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
