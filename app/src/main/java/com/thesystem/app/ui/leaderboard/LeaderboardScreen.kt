package com.thesystem.app.ui.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.MedalBoardRowDto
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.repo.LeaderboardRepository
import com.thesystem.app.data.repo.SocialRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LeaderboardState(
    val loading: Boolean = true,
    val board: Board = Board.OVERALL,
    val geo: LeaderboardRepository.Geo = LeaderboardRepository.Geo.GLOBAL,
    val hunters: List<UserDto> = emptyList(),
    val medalRows: List<MedalBoardRowDto> = emptyList(),
    val myCity: String? = null,
    val myDistrict: String? = null,
    val myId: String? = null,
    val rallySentTo: Set<String> = emptySet(),
    val notice: String? = null,
) {
    enum class Board(val label: String) { OVERALL("OVERALL HUNTER"), TRIALS("MEDAL TRIALS") }
}

@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val boards: LeaderboardRepository,
    private val system: SystemRepository,
    private val social: SocialRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LeaderboardState())
    val state: StateFlow<LeaderboardState> = _state

    init {
        viewModelScope.launch {
            val me = system.profile()
            _state.value = _state.value.copy(
                myCity = me?.city, myDistrict = me?.district, myId = me?.id,
            )
            refresh()
            // LIVE rail: re-sync every 20s while the screen stands
            while (true) { delay(20_000); refresh() }
        }
    }

    fun setBoard(b: LeaderboardState.Board) { _state.value = _state.value.copy(board = b); viewModelScope.launch { refresh() } }
    fun setGeo(g: LeaderboardRepository.Geo) { _state.value = _state.value.copy(geo = g); viewModelScope.launch { refresh() } }

    suspend fun refresh() {
        val s = _state.value
        when (s.board) {
            LeaderboardState.Board.OVERALL ->
                _state.value = _state.value.copy(
                    loading = false,
                    hunters = boards.overallBoard(s.geo, s.myCity, s.myDistrict),
                )
            LeaderboardState.Board.TRIALS ->
                _state.value = _state.value.copy(loading = false, medalRows = boards.medalBoard())
        }
    }

    /** §8 — extend a hand to a struggling hunter (server: 1/pair/24h). */
    fun extendHand(userId: String) = viewModelScope.launch {
        social.extendAHand(userId)
            .onSuccess {
                _state.value = _state.value.copy(
                    rallySentTo = _state.value.rallySentTo + userId,
                    notice = "HAND EXTENDED — the Brotherhood signal rides.",
                )
            }
            .onFailure { _state.value = _state.value.copy(notice = it.message?.take(120)) }
    }
}

@Composable
fun LeaderboardScreen(nav: NavHostController, vm: LeaderboardViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("LIVE LEADERBOARDS", style = MaterialTheme.typography.headlineMedium, color = PaperWhite)
        Text("SYNCED WITH THE SYSTEM · 20s", style = MonoLabel, color = SkyBlue)
        Spacer(Modifier.height(14.dp))

        // board selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LeaderboardState.Board.entries.forEach { b ->
                NeonButton(
                    b.label, { vm.setBoard(b) }, Modifier.weight(1f),
                    color = if (s.board == b) SkyBlue else TextMuted,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // geo selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LeaderboardRepository.Geo.entries.forEach { g ->
                GhostButton(
                    g.name, { vm.setGeo(g) }, Modifier.weight(1f),
                )
            }
        }
        if (s.geo == LeaderboardRepository.Geo.LOCAL && s.myCity.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Declare your LOCAL GROUND in Profile → Edit Identity to join the city board.",
                style = MaterialTheme.typography.bodySmall, color = FaintGray,
            )
        }
        s.notice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = VenomGreen)
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
            when (s.board) {
                LeaderboardState.Board.OVERALL -> {
                    itemsIndexed(s.hunters, key = { _, h -> h.id }) { i, h ->
                        HunterRow(i + 1, h, isMe = h.id == s.myId, rallied = h.id in s.rallySentTo,
                            onExtend = { vm.extendHand(h.id) })
                    }
                    if (s.hunters.isEmpty() && !s.loading) item { EmptyState("No hunters stand on this board yet.") }
                }
                LeaderboardState.Board.TRIALS -> {
                    itemsIndexed(s.medalRows) { i, r -> MedalRow(i + 1, r) }
                    if (s.medalRows.isEmpty() && !s.loading) item { EmptyState("No medal trials in motion yet.") }
                }
            }
        }
    }
}

@Composable
private fun HunterRow(rank: Int, h: UserDto, isMe: Boolean, rallied: Boolean, onExtend: () -> Unit) {
    val atRisk = h.missedDays >= 1 || h.penaltyState != "CLEAR"
    Row(
        Modifier
            .fillMaxWidth()
            .border(0.8.dp, if (isMe) SkyBlue else Color(0xFF22313D), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#$rank", style = MonoLabel, color = if (rank <= 3) HunterGold else FaintGray, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text("@${h.username}", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "LV ${h.level} · ${SystemMath.formatXp(h.xp)} · STREAK ${h.streakDays}d" +
                    (if (atRisk) " · AT RISK" else ""),
                style = MonoLabel, color = if (atRisk) WarningAmber else LabelGray,
            )
        }
        // §8 EXTEND A HAND — at-risk hunters on the board can be rallied
        if (atRisk && !isMe) {
            TextButton(onClick = onExtend, enabled = !rallied) {
                Text(if (rallied) "EXTENDED ✓" else "EXTEND A HAND", color = SkyBlue, fontSize = 10.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun MedalRow(rank: Int, r: MedalBoardRowDto) {
    val done = r.status == "COMPLETED"
    Row(
        Modifier
            .fillMaxWidth()
            .border(0.8.dp, if (done) HunterGold else Color(0xFF22313D), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#$rank", style = MonoLabel, color = if (rank <= 3) HunterGold else FaintGray, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text("@${r.username ?: "hunter"}", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "${r.trialId} · ${r.repsDone} reps · ${r.kmDone}km" + (r.city?.let { " · $it" } ?: ""),
                style = MonoLabel, color = LabelGray,
            )
        }
        Text(
            if (done) "MEDAL ✓" else "ACTIVE",
            style = MonoLabel, color = if (done) HunterGold else VenomGreen,
        )
    }
}
