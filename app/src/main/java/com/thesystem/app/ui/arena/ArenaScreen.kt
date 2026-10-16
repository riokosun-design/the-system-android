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
import androidx.compose.runtime.saveable.rememberSaveable
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
 * ARENA — five independent feature areas behind a segmented selector.
 * The hunter enters, picks a section, and ONLY that feature owns the screen —
 * no giant continuous page. Real backend data only: empty states tell the
 * truth, placeholder matches do not exist anywhere in this file.
 *
 *   MATCH    · username search → public record → live/scheduled war + open wars
 *   RANDOM   · real queue; pairs two waiting hunters, never bots
 *   PREDICT  · FREE non-redeemable spectator points (13+, no cash value)
 *   LIVE     · real wars in progress / joinable lobbies + daily battle blocks
 *   SCHEDULED· booked wars, accept/decline, enter the War Room at the hour
 */
@Composable
fun ArenaScreen(nav: NavHostController, vm: ArenaViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val snack = remember { SnackbarHostState() }
    // the section selector — survives rotation, never scrolls away
    var section by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(s.notice, s.error) {
        s.error?.let { snack.showSnackbar(it); vm.consumeNotice() }
        s.notice?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    SystemBackground {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Grid.Margin).padding(top = Grid.S16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("SYSTEM", style = MonoLabel, color = SkyBlue)
                        Text("ARENA", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("PREDICTION PTS", style = MonoLabel, color = LabelGray)
                        Text("${s.predictionPoints}", style = MonoData, color = SkyBlue)
                    }
                }
                Spacer(Modifier.height(Grid.S8))

                // ══ §5 HERO — 1v1 RANDOM MATCHMAKING, always on top, pulsing ══
                val heroPulse = rememberInfiniteTransition(label = "heroPulse")
                val heroA by heroPulse.animateFloat(
                    0.22f, 0.85f,
                    infiniteRepeatable(tween(1400), RepeatMode.Reverse),
                    label = "heroA",
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Grid.Margin)
                        .border(1.5.dp, SkyBlue.copy(alpha = heroA), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1v1 RANDOM MATCHMAKING", color = PaperWhite, fontWeight = FontWeight.Bold,
                            fontSize = 13.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                        Text("LIVE QUEUE", style = MonoLabel, color = SkyBlue)
                    }
                    Spacer(Modifier.height(Grid.S8))
                    RandomMatchmakingPanel(
                        s = s,
                        onExercise = { haptics.tick(); vm.setQueueExercise(it) },
                        onFind = { haptics.slam(); vm.findRandomOpponent() },
                        onCancel = { haptics.tick(); vm.cancelRandomSearch() },
                        onEnter = { battleId -> haptics.slam(); nav.navigate(Routes.battle(battleId)) },
                        onDecline = { haptics.tick(); vm.declineRandomMatch() },
                    )
                }
                Spacer(Modifier.height(Grid.CardSpace))

                // ══ §5 ACTIVE TOURNAMENTS — live brackets, entry status, countdowns ══
                ArenaTournamentsSection(nav, Modifier.padding(horizontal = Grid.Margin))
                Spacer(Modifier.height(Grid.S8))

                SystemTabBar(
                    tabs = listOf("MATCH", "PREDICT", "LIVE", "PLANNED"),
                    selected = section,
                    onSelect = { haptics.select(); section = it },
                )

                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = Grid.Margin),
                    verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                    contentPadding = PaddingValues(vertical = Grid.S16),
                ) {
                    when (section) {
                        // ══ 1 · MATCHMAKING (+ my open wars) ════════════════
                        0 -> {
                            item { SectionTitle("MATCHMAKING", PaperWhite) }
                            item {
                                GlowCard(modifier = Modifier.fillMaxWidth()) {
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
                                    Text("SCHEDULE — optional", style = MonoLabel, color = LabelGray)
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
                        }

                        // ══ 2 · PREDICTION / SPECTATOR ══════════════════════
                        1 -> {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SectionTitle("PREDICTION / SPECTATOR", PaperWhite)
                                    Spacer(Modifier.weight(1f))
                                    GhostButton("CLAIM +50", { haptics.tick(); vm.claimAllowance() })
                                }
                            }
                            item {
                                Text(
                                    "Free spectator points — never bought, never cashed out.",
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
                        }

                        // ══ 3 · LIVE CHALLENGES (+ daily battle blocks) ═════
                        2 -> {
                            item { SectionTitle("LIVE CHALLENGES", PaperWhite) }
                            if (s.live.isEmpty()) {
                                item {
                                    EmptyState(
                                        "No live wars right now. Send a challenge in MATCH or enter the random queue — " +
                                            "the board lights up the moment a real war starts."
                                    )
                                }
                            } else {
                                items(s.live.take(8), key = { it.battleId }) { row ->
                                    LiveBoardRow(row) { haptics.select(); nav.navigate(Routes.battle(row.battleId)) }
                                }
                            }
                            if (s.challenges.isNotEmpty()) {
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
                            }
                        }

                        // ══ 5 · SCHEDULED CHALLENGES ════════════════════════
                        else -> {
                            item { SectionTitle("SCHEDULED CHALLENGES", PaperWhite) }
                            if (s.matches.isEmpty()) {
                                item {
                                    EmptyState("No scheduled wars. Book one from MATCH — pick +1H / +3H / +24H, send, the rival decides here.")
                                }
                            } else {
                                items(s.matches, key = { it.id }) { m -> ScheduledRow(m, s.myProfile?.id, vm, nav, haptics) }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(60.dp)) }
                }
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
        Text("BATTLE TYPE", style = MonoLabel, color = LabelGray)
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

// ════════════════════════════════════════════════════════════════════════════
// §5 — ACTIVE TOURNAMENTS: server-owned brackets surface live inside the Arena.
// Reads the shared TournamentsViewModel (10s poll), deep-links the full rail.
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun ArenaTournamentsSection(nav: NavHostController, modifier: Modifier = Modifier) {
    val tvm: com.thesystem.app.ui.tournaments.TournamentsViewModel = hiltViewModel()
    val ts by tvm.state.collectAsStateWithLifecycle()
    // countdown ticker — re-derives labels every 30s without hammering the server
    val tick by produceState(0) { while (true) { kotlinx.coroutines.delay(30_000); value++ } }
    // raw ISO startsAt parsed once per tournament, label regenerated per tick
    fun countdown(iso: String?): String {
        @Suppress("UNUSED_EXPRESSION") tick
        val start = iso?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return ""
        val ms = start.toEpochMilli() - System.currentTimeMillis()
        if (ms <= 0) return "LIVE NOW"
        val m = ms / 60_000
        return when {
            m < 60 -> "starts in ${m}m"
            m < 60 * 24 -> "starts in ${m / 60}h ${m % 60}m"
            else -> "starts in ${m / (60 * 24)}d"
        }
    }

    val live = remember(ts.tournaments, tick) {
        (ts.tournaments.filter { it.status in listOf("OPEN", "IN_PROGRESS", "LIVE", "REGISTRATION_OPEN") }
            .sortedBy { it.status != "OPEN" && it.status != "REGISTRATION_OPEN" }
            .take(4))
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("ACTIVE TOURNAMENTS", PaperWhite)
            Spacer(Modifier.weight(1f))
            GhostButton("ALL", { nav.navigate(Routes.TOURNAMENTS) })
        }
        Spacer(Modifier.height(6.dp))
        if (live.isEmpty()) {
            Text("No brackets running. Watch this space.", style = MonoLabel, color = FaintGray)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                live.forEach { t ->
                    val registered = ts.myEntries.containsKey(t.id)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(LineSoft.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                            .clickable { nav.navigate(Routes.TOURNAMENTS) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                fontFamily = SystemMono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildString {
                                    append("ENTRY ${t.entryFeeVc} VC · POOL ${t.prizePoolVc} VC")
                                    countdown(t.startsAt).takeIf { it.isNotBlank() }?.let { append(" · ${it.uppercase()}") }
                                },
                                style = MonoLabel, color = LabelGray, maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                registered -> "REGISTERED"
                                t.status == "OPEN" || t.status == "REGISTRATION_OPEN" -> "JOIN →"
                                else -> t.status
                            },
                            style = MonoLabel,
                            color = if (registered) SkyBlue else PaperWhite,
                        )
                    }
                }
            }
        }
    }
}
