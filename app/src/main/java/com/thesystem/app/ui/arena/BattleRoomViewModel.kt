package com.thesystem.app.ui.arena

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.model.BattleDto
import com.thesystem.app.data.repo.ArenaRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.realtime.RealtimeChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ephemeral lobby taunt line (broadcast only, never persisted). */
data class TauntLine(val fromMe: Boolean, val text: String, val at: Long)

data class BattleRoomState(
    val loading: Boolean = true,
    val battle: BattleDto? = null,
    val myId: String? = null,
    val myCount: Int = 0,
    val opponentCount: Int = 0,
    val durationSec: Int = 60,
    val secondsLeft: Int = 60,
    val cameraGranted: Boolean = false,
    val counting: Boolean = false,
    val finished: Boolean = false,
    val iWon: Boolean? = null,
    val myReady: Boolean = false,
    val taunts: List<TauntLine> = emptyList(),
    val error: String? = null,
) {
    /** Tug-of-war position in −1f (opponent dominating) .. +1f (you dominating). */
    val tug: Float get() {
        val diff = (myCount - opponentCount).coerceIn(-25, 25)
        return diff / 25f
    }

    /** My opponent's READY flag straight from the battle row. */
    fun opponentReadyOf(b: BattleDto?, myId: String?): Boolean = when {
        b == null || myId == null -> false
        myId == b.playerA -> b.playerBReady
        else -> b.playerAReady
    }
}

@HiltViewModel
class BattleRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val arena: ArenaRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val battleId: String = checkNotNull(savedStateHandle["battleId"])
    private val _state = MutableStateFlow(BattleRoomState())
    val state: StateFlow<BattleRoomState> = _state

    private var channel: RealtimeChannel? = null
    private var timerStarted = false

    init {
        viewModelScope.launch {
            val me = system.profile()?.id
            val battle = arena.battle(battleId)
            _state.value = _state.value.copy(
                loading = false, battle = battle, myId = me,
                durationSec = battle?.durationSec ?: 60,
                secondsLeft = battle?.durationSec ?: 60,
            )
            if (battle != null && me != null) wireRealtime(me)
        }
    }

    private fun wireRealtime(me: String) = viewModelScope.launch {
        // Row flow: authoritative READY flags, status, final scores
        launch {
            arena.battleFlow(battleId).collect { b ->
                if (b != null) {
                    val prev = _state.value.battle?.status
                    _state.value = _state.value.copy(
                        battle = b,
                        durationSec = b.durationSec,
                        myReady = if (me == b.playerA) b.playerAReady else b.playerBReady,
                    )
                    if (b.status == "LIVE" && prev != "LIVE") {
                        // late joiners inherit the elapsed server time
                        val elapsed = b.startedAt?.let {
                            try {
                                java.time.Duration.between(java.time.Instant.parse(it), java.time.Instant.now()).seconds
                            } catch (_: Exception) { 0L }
                        } ?: 0L
                        val remaining = (b.durationSec - elapsed).coerceIn(1L, b.durationSec.toLong()).toInt()
                        startTimer(remaining)
                    }
                    if (b.status == "FINISHED") onFinished(b)
                }
            }
        }
        // Broadcast channel carries live scores AND quick lobby taunts.
        val ch = arena.battleChannel(battleId)
        channel = ch
        launch {
            arena.opponentScoreFlow(ch, me).collect { theirs ->
                _state.value = _state.value.copy(opponentCount = theirs)
            }
        }
        launch {
            arena.tauntFlow(ch, me).collect { (_, msg) ->
                _state.value = _state.value.copy(
                    taunts = (_state.value.taunts + TauntLine(false, msg, System.currentTimeMillis())).takeLast(6),
                )
            }
        }
    }

    fun onCameraPermission(granted: Boolean) { _state.value = _state.value.copy(cameraGranted = granted) }

    // ── LOBBY: bilateral READY gate; the server flips LIVE once both arm up ──
    fun toggleReady() = viewModelScope.launch {
        val s = _state.value
        if (s.battle?.status != "LOBBY") return@launch
        val next = !s.myReady
        if (next && !s.cameraGranted) {
            _state.value = s.copy(error = "CAMERA REQUIRED — grant camera before arming READY.")
            return@launch
        }
        arena.setReady(battleId, next)
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }

    fun cancelLobby(onCancelled: () -> Unit) = viewModelScope.launch {
        arena.cancelBattle(battleId)
            .onSuccess { onCancelled() }
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }

    fun sendTaunt(text: String) = viewModelScope.launch {
        val me = _state.value.myId ?: return@launch
        val ch = channel ?: return@launch
        arena.sendTaunt(ch, me, text)
        _state.value = _state.value.copy(
            taunts = (_state.value.taunts + TauntLine(true, text, System.currentTimeMillis())).takeLast(6),
        )
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    private fun startTimer(durationSec: Int = 60) {
        if (timerStarted) return
        timerStarted = true
        _state.value = _state.value.copy(counting = true, secondsLeft = durationSec)
        viewModelScope.launch {
            while (_state.value.secondsLeft > 0 && !_state.value.finished) {
                delay(1000)
                _state.value = _state.value.copy(secondsLeft = _state.value.secondsLeft - 1)
            }
            finishIfPlayerA()
        }
    }

    fun onRep(count: Int) {
        _state.value = _state.value.copy(myCount = count)
        val me = _state.value.myId ?: return
        viewModelScope.launch { runCatching { channel?.let { arena.sendScore(it, me, count) } } }
    }

    /** Only player A commits the result; B's screen follows via Realtime. */
    private fun finishIfPlayerA() {
        val s = _state.value
        val b = s.battle ?: return
        if (s.finished) return
        val amA = s.myId == b.playerA
        val scoreA = if (amA) s.myCount else s.opponentCount
        val scoreB = if (amA) s.opponentCount else s.myCount
        if (amA) {
            viewModelScope.launch {
                arena.finishBattle(battleId, scoreA, scoreB)
                    .onFailure { _state.value = _state.value.copy(error = it.message) }
            }
        }
    }

    private fun onFinished(b: BattleDto) {
        val me = _state.value.myId
        _state.value = _state.value.copy(
            finished = true, counting = false, battle = b,
            iWon = b.winner == me,
        )
    }

    override fun onCleared() {
        // Channels are tied to the client scope; collection jobs die with viewModelScope.
        channel = null
        super.onCleared()
    }
}
