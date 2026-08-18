package com.thesystem.app.ui.arena

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.core.SystemMath
import com.thesystem.app.data.model.*
import com.thesystem.app.data.repo.ArenaRepository
import com.thesystem.app.data.repo.SocialRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArenaState(
    val loading: Boolean = true,
    val profile: UserDto? = null,
    val tournaments: List<TournamentDto> = emptyList(),
    val myTournamentIds: Set<String> = emptySet(),
    val openBattles: List<BattleDto> = emptyList(),
    val pools: List<Pair<PoolDto, BattleDto?>> = emptyList(),
    val myBets: List<BetDto> = emptyList(),
    val leaders: List<UserDto> = emptyList(),
    val myClan: ClanMemberDto? = null,
    val searchingOpponent: Boolean = false,
    val opponentQuery: String = "",
    val opponentResults: List<UserDto> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ArenaViewModel @Inject constructor(
    private val arena: ArenaRepository,
    private val social: SocialRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ArenaState())
    val state: StateFlow<ArenaState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val p = async { system.profile() }
        val t = async { arena.tournaments() }
        val b = async { arena.openBattles() }
        val pools = async { arena.openPools() }
        val bets = async { arena.myBets() }
        val leads = async { arena.xpLeaderboard() }
        val joined = async { arena.myParticipations() }
        val clan = async { social.myMembership() }
        _state.value = _state.value.copy(
            loading = false,
            profile = p.await(), tournaments = t.await(), openBattles = b.await(),
            pools = pools.await(), myBets = bets.await(), leaders = leads.await(),
            myTournamentIds = joined.await().map { it.first }.toSet(), myClan = clan.await(),
        )
    }

    // ── Tournament entry-fee gate (admin-controlled pricing) ─────────────────
    fun joinTournament(t: TournamentDto) = viewModelScope.launch {
        arena.joinTournament(t.id, clanId = if (t.type == "CLAN") _state.value.myClan?.clanId else null)
            .onSuccess {
                _state.value = _state.value.copy(notice = "ENTRY PAID · −${SystemMath.formatVc(t.entryFeeVc)} · See you in the bracket.")
                refresh()
            }
            .onFailure { e ->
                _state.value = _state.value.copy(error = friendlyRpcError(e.message))
            }
    }

    // ── Challenges ───────────────────────────────────────────────────────────
    fun onOpponentQuery(q: String) {
        _state.value = _state.value.copy(opponentQuery = q)
        if (q.length < 3) { _state.value = _state.value.copy(opponentResults = emptyList()); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(opponentResults = social.searchUsers(q)
                .filter { it.id != _state.value.profile?.id })
        }
    }

    fun challenge(opponent: UserDto, onCreated: (String) -> Unit) = viewModelScope.launch {
        arena.challenge(opponent.id)
            .onSuccess { battleId -> onCreated(battleId) }
            .onFailure { _state.value = _state.value.copy(error = friendlyRpcError(it.message)) }
    }

    // ── Prediction betting ───────────────────────────────────────────────────
    fun placeBet(pool: PoolDto, side: String, amount: Long) = viewModelScope.launch {
        arena.placeBet(pool.id, side, amount)
            .onSuccess {
                _state.value = _state.value.copy(notice = "BET LOCKED · ${SystemMath.formatVc(amount)} on side $side · 15% house cut applies to the pool.")
                refresh()
            }
            .onFailure { _state.value = _state.value.copy(error = friendlyRpcError(it.message)) }
    }

    private fun friendlyRpcError(msg: String?): String = when {
        msg == null -> "Unknown error."
        msg.contains("insufficient_vc") -> "Insufficient VC. Grind quests, the CPA wall, or sell your glory."
        msg.contains("treasury_insufficient") -> "Guild treasury can't cover the entry fee. Fill the coffers via territory taxes first."
        msg.contains("already_joined") -> "You are already registered for this tournament."
        msg.contains("already_bet") -> "One bet per pool, hunter. Stand by your conviction."
        msg.contains("pool_locked") -> "This pool is locked — the battle has begun."
        msg.contains("not_member") -> "Your Shadow Guild must join clan tournaments as a unit."
        msg.contains("clan_required") -> "You need a Shadow Guild to enter clan tournaments."
        msg.contains("level_req") -> "Your level is below the gate requirement."
        else -> msg.take(160)
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}
