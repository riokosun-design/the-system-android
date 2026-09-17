package com.thesystem.app.ui.arena

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/**
 * ARENA — three sections, in this order (spec):
 *   1 MATCHMAKING      — username search, live challenge, scheduled war, filters
 *   2 PREDICTION / SPECTATOR — free non-redeemable points, WATCH a live board
 *   3 CHALLENGES       — created / received / scheduled / completed / history
 */
data class ArenaState(
    val loading: Boolean = true,
    val myProfile: UserDto? = null,
    val live: List<ArenaLiveRow> = emptyList(),
    val hub: List<PredictionHubRow> = emptyList(),
    val myPredictions: List<PredictionBetDto> = emptyList(),
    val predictionPoints: Long = 0,
    val matches: List<ScheduledMatchDto> = emptyList(),
    val challenges: List<BattleChallengeDto> = emptyList(),
    val myBattles: List<BattleDto> = emptyList(),
    val recent: List<BattleDto> = emptyList(),
    val opponentQuery: String = "",
    val opponentResults: List<UserDto> = emptyList(),
    val inspecting: HunterStatsDto? = null,
    val inspectingName: String? = null,
    /** the duel the fighter is composing right now */
    val draftExercise: String = "PUSHUP",
    val draftDuration: Int = 60,
    val draftScheduledAt: String? = null,
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
        val profileD = async { system.profile() }
        val liveD = async { arena.arenaLive() }
        val hubD = async { arena.predictionHub() }
        val predsD = async { arena.myPredictions() }
        val matchesD = async { arena.scheduledMatches() }
        val challengesD = async { arena.dailyChallenges() }
        val openD = async { arena.openBattles() }
        val profile = profileD.await()
        _state.value = _state.value.copy(
            loading = false,
            myProfile = profile,
            live = liveD.await(),
            hub = hubD.await(),
            myPredictions = predsD.await(),
            predictionPoints = profile?.predictionPoints ?: 0,
            matches = matchesD.await(),
            challenges = challengesD.await(),
            myBattles = openD.await(),
            error = if (profile == null) "OFFLINE — the Arena needs the grid." else null,
        )
    }

    // ── SECTION 1 — matchmaking ──────────────────────────────────────────────
    fun onQuery(q: String) {
        _state.value = _state.value.copy(opponentQuery = q)
        if (q.trim().length < 2) { _state.value = _state.value.copy(opponentResults = emptyList()); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(opponentResults = social.searchUsers(q.trim()))
        }
    }

    /** Username → profile → challenge. The stats sheet is the last step. */
    fun inspect(user: UserDto) = viewModelScope.launch {
        val stats = arena.hunterStats(user.id)
        _state.value = _state.value.copy(
            inspecting = stats,
            inspectingName = user.displayName ?: user.username,
        )
    }

    fun dismissInspect() { _state.value = _state.value.copy(inspecting = null, inspectingName = null) }

    fun setExercise(kind: String) { _state.value = _state.value.copy(draftExercise = kind) }
    fun setDuration(sec: Int) { _state.value = _state.value.copy(draftDuration = sec) }
    fun setScheduledAt(iso: String?) { _state.value = _state.value.copy(draftScheduledAt = iso) }

    /** LIVE challenge — the war room opens the moment it is created. */
    fun challenge(opponentId: String, onBattle: (String) -> Unit) = viewModelScope.launch {
        val s = _state.value
        arena.challengeLive(opponentId, s.draftExercise, s.draftDuration)
            .onSuccess { battleId -> onBattle(battleId) }
            .onFailure { _state.value = _state.value.copy(error = friendly(it.message)) }
    }

    /** SCHEDULED war — the opponent gets a MATCH READY card and a reminder. */
    fun schedule(opponentId: String) = viewModelScope.launch {
        val s = _state.value
        val at = s.draftScheduledAt
        if (at == null) {
            _state.value = s.copy(error = "Pick a date and time for the scheduled war first.")
            return@launch
        }
        arena.scheduleDuel(opponentId, s.draftExercise, s.draftDuration, at)
            .onSuccess {
                _state.value = _state.value.copy(notice = "War scheduled. The opponent has to accept.")
                refresh()
            }
            .onFailure { _state.value = _state.value.copy(error = friendly(it.message)) }
    }

    fun respond(matchId: String, accept: Boolean) = viewModelScope.launch {
        arena.respondDuel(matchId, accept)
            .onSuccess { _state.value = _state.value.copy(notice = if (accept) "War accepted." else "War declined."); refresh() }
            .onFailure { _state.value = _state.value.copy(error = friendly(it.message)) }
    }

    fun cancelMatch(matchId: String) = viewModelScope.launch {
        arena.cancelScheduled(matchId)
            .onSuccess { _state.value = _state.value.copy(notice = "Scheduled war cancelled."); refresh() }
            .onFailure { _state.value = _state.value.copy(error = friendly(it.message)) }
    }

    // ── SECTION 2 — predictions on FREE points ──────────────────────────────
    fun claimAllowance() = viewModelScope.launch {
        arena.claimPredictionAllowance()
            .onSuccess { earned ->
                _state.value = _state.value.copy(notice = "+$earned free prediction points claimed.")
                refresh()
            }
            .onFailure { _state.value = _state.value.copy(error = "Allowance already claimed today.") }
    }

    fun predict(row: PredictionHubRow, side: String, points: Long) = viewModelScope.launch {
        val pool = row.poolId ?: run {
            _state.value = _state.value.copy(error = "This board has no open pool yet.")
            return@launch
        }
        arena.placePrediction(pool, side, points)
            .onSuccess { _state.value = _state.value.copy(notice = "Prediction locked: ${row.playerAName} vs ${row.playerBName}."); refresh() }
            .onFailure { _state.value = _state.value.copy(error = friendly(it.message)) }
    }

    // ── SECTION 3 — daily challenge blocks ──────────────────────────────────
    fun claimChallenge(kind: String) = viewModelScope.launch {
        arena.claimBattleChallenge(kind)
            .onSuccess { _state.value = _state.value.copy(notice = "$kind challenge claimed — +120 XP, +25 VC."); refresh() }
            .onFailure { _state.value = _state.value.copy(error = friendly(it.message)) }
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }

    private fun friendly(raw: String?): String = when {
        raw == null -> "Action failed. Try again."
        raw.contains("not_enough_points") -> "Not enough prediction points — claim the free daily allowance."
        raw.contains("min_backing_5") -> "Minimum prediction is 5 points."
        raw.contains("already_bet") -> "You already backed this board."
        raw.contains("no_self_bet") -> "You are in this war — no predictions on your own match."
        raw.contains("no_self_battle") -> "You cannot challenge yourself."
        raw.contains("bad_time") -> "That time has already passed."
        raw.contains("bad_duration") -> "Allowed war lengths: 30 / 60 / 120 seconds."
        raw.contains("pool_locked") -> "The pool closed before your prediction landed."
        raw.contains("challenge_incomplete") -> "Win the required battles first."
        raw.contains("already_claimed") -> "Already claimed today."
        else -> "Arena: $raw"
    }
}
