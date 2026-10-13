package com.thesystem.app.ui.tournaments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.BattleDto
import com.thesystem.app.data.model.TournamentDto
import com.thesystem.app.data.repo.ArenaRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 0.22.0 — TOURNAMENTS: server-owned bracket rail finally gets its user surface. */
data class TournamentsState(
    val loading: Boolean = true,
    val tournaments: List<TournamentDto> = emptyList(),
    val myEntries: Map<String, Long> = emptyMap(), // tournamentId → fee paid
    val myId: String = "",
    val active: TournamentDto? = null,
    val fixtures: List<BattleDto> = emptyList(),
    val joining: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class TournamentsViewModel @Inject constructor(
    private val arena: ArenaRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TournamentsState())
    val state: StateFlow<TournamentsState> = _state

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(myId = system.profile()?.id ?: "")
            refresh()
            while (true) { delay(10000); refresh(silent = true) }
        }
    }

    private suspend fun refresh(silent: Boolean = false) {
        val active = _state.value.active
        _state.value = _state.value.copy(
            loading = if (silent) _state.value.loading else false,
            tournaments = arena.tournaments(),
            myEntries = arena.myParticipations().toMap(),
            fixtures = active?.let { arena.tournamentBattles(it.id) } ?: _state.value.fixtures,
        )
    }

    fun refreshNow() = viewModelScope.launch { refresh(silent = true) }

    fun open(t: TournamentDto) = viewModelScope.launch {
        _state.value = _state.value.copy(active = t, fixtures = arena.tournamentBattles(t.id))
    }

    fun close() { _state.value = _state.value.copy(active = null, fixtures = emptyList()) }

    /** Entry fee is charged atomically server-side by join_tournament — client never touches VC. */
    fun join(t: TournamentDto) {
        if (_state.value.joining) return
        viewModelScope.launch {
            _state.value = _state.value.copy(joining = true)
            arena.joinTournament(t.id).fold(
                onSuccess = {
                    _state.value = _state.value.copy(notice = "ENTERED — ${t.entryFeeVc} VC staked. Fixtures drop when the bracket opens.")
                    refresh(silent = true)
                },
                onFailure = {
                    _state.value = _state.value.copy(error = when {
                        it.message?.contains("insufficient") == true -> "NOT ENOUGH VC — top up in the Wallet first."
                        it.message?.contains("already") == true -> "Already registered, hunter."
                        it.message?.contains("status") == true || it.message?.contains("not_open") == true -> "Registration is closed for this one."
                        else -> "ENTRY FAILED — ${it.message?.take(100) ?: "retry"}"
                    })
                },
            )
            _state.value = _state.value.copy(joining = false)
        }
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}

@Composable
fun TournamentsScreen(nav: NavHostController, vm: TournamentsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    SystemBackground(wallpaperAlpha = 0.08f) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    IconButton(onClick = { if (s.active != null) vm.close() else nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                    Text(
                        if (s.active != null) "TOURNAMENT" else "TOURNAMENTS",
                        style = MaterialTheme.typography.titleLarge, color = ElectricBlue,
                    )
                }
                val active = s.active
                when {
                    s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ElectricBlue) }
                    active != null -> TournamentDetail(s, active, vm, nav)
                    s.tournaments.isEmpty() -> EmptyState("No tournaments published yet. The System is preparing the arena.")
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
                        items(s.tournaments, key = { it.id }) { t ->
                            TournamentCard(t, registered = s.myEntries.containsKey(t.id)) { vm.open(t) }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snack) }
        }
    }
}

@Composable
private fun TournamentCard(t: TournamentDto, registered: Boolean, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(LineSoft.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.title, color = PaperWhite, fontWeight = FontWeight.Bold, fontFamily = SystemMono, fontSize = 15.sp)
                Text("${t.type} · bracket up to ${t.maxParticipants}", style = MonoLabel, color = LabelGray)
            }
            Text(t.status, style = MonoLabel, color = tournamentStatusColor(t.status))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text("ENTRY ${t.entryFeeVc} VC", style = MonoLabel, color = HunterGold)
            Spacer(Modifier.width(14.dp))
            Text("PRIZE ${t.prizePoolVc} VC", style = MonoLabel, color = SkyBlue)
            if (registered) { Spacer(Modifier.width(14.dp)); Text("● REGISTERED", style = MonoLabel, color = ElectricBlue) }
        }
        t.startsAt?.let {
            Spacer(Modifier.height(3.dp))
            Text("Starts ${it.take(16).replace('T', ' ')}", style = MonoLabel, color = FaintGray)
        }
    }
}

@Composable
private fun TournamentDetail(s: TournamentsState, t: TournamentDto, vm: TournamentsViewModel, nav: NavHostController) {
    val registered = s.myEntries.containsKey(t.id)
    val joinable = t.status == "OPEN" && !registered
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(LineSoft.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(14.dp)) {
                Text(t.title, color = PaperWhite, fontWeight = FontWeight.Bold, fontFamily = SystemMono, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(t.status, style = MonoLabel, color = tournamentStatusColor(t.status))
                Spacer(Modifier.height(8.dp))
                Text("ENTRY ${t.entryFeeVc} VC · PRIZE POOL ${t.prizePoolVc} VC · ${t.type}", style = MonoLabel, color = HunterGold)
                t.startsAt?.let { Text("Starts ${it.take(16).replace('T', ' ')}", style = MonoLabel, color = LabelGray) }
                t.endsAt?.let { Text("Ends ${it.take(16).replace('T', ' ')}", style = MonoLabel, color = LabelGray) }
                Spacer(Modifier.height(10.dp))
                when {
                    joinable -> GhostButton(
                        if (s.joining) "ENTERING…" else "JOIN — STAKE ${t.entryFeeVc} VC",
                        { vm.join(t) }, Modifier.fillMaxWidth(),
                    )
                    registered -> Text("REGISTERED — entry fee staked. Fixtures below once the bracket draws.", style = MonoLabel, color = ElectricBlue)
                    else -> Text("Registration closed.", style = MonoLabel, color = FaintGray)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("Fixtures") }
                TextButton(onClick = vm::refreshNow) { Text("REFRESH", style = MonoLabel, color = LabelGray) }
            }
        }
        if (s.fixtures.isEmpty()) {
            item { Text("No fixtures yet — the bracket draws when the admin opens combat.", style = MonoLabel, color = FaintGray) }
        } else {
            items(s.fixtures, key = { it.id }) { b ->
                FixtureRow(b, myId = s.myId) {
                    // joining my own live/lobby fixture takes me to the battle room
                    if (b.status in listOf("LOBBY", "LIVE") && (b.playerA == s.myId || b.playerB == s.myId)) {
                        nav.navigate("battle/${b.id}")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

@Composable
private fun FixtureRow(b: BattleDto, myId: String, onTap: () -> Unit) {
    val nameA = b.playerAName ?: b.playerA.take(6)
    val nameB = b.playerBName ?: b.playerB.take(6)
    val mine = b.playerA == myId || b.playerB == myId
    val winnerLabel = when {
        b.status != "FINISHED" -> null
        b.winner == b.playerA -> nameA
        b.winner == b.playerB -> nameB
        else -> "DRAW"
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (mine) LineSoft.copy(alpha = 0.45f) else LineSoft.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .clickable(onClick = onTap)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$nameA  vs  $nameB",
                color = if (mine) PaperWhite else LabelGray,
                fontFamily = SystemMono, fontSize = 13.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
            )
            if (b.status == "FINISHED") {
                Text("${b.scoreA} — ${b.scoreB}" + (winnerLabel?.let { " · $it takes it" } ?: ""), style = MonoLabel, color = HunterGold)
            } else {
                Text(b.status + (b.scheduledAt?.let { " · ${it.take(16).replace('T', ' ')}" } ?: ""), style = MonoLabel, color = FaintGray)
            }
        }
        if (mine && b.status in listOf("LOBBY", "LIVE")) Text("ENTER →", style = MonoLabel, color = ElectricBlue)
    }
}

private fun tournamentStatusColor(status: String) = when (status) {
    "OPEN", "REGISTRATION_OPEN" -> ElectricBlue
    "IN_PROGRESS", "LIVE" -> HunterGold
    "COMPLETED" -> SkyBlue
    "CANCELLED" -> CrimsonRed
    else -> LabelGray
}
