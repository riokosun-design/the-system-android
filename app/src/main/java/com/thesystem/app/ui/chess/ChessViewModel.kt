package com.thesystem.app.ui.chess

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.model.ChessProfileDto
import com.thesystem.app.data.repo.ChessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * CHESS HUB state — profile (server numbers only), today's mental quest with
 * real progress, claims and the BODY × MIND SYNC handshake.
 */
@HiltViewModel
class ChessViewModel @Inject constructor(
    private val repo: ChessRepository,
) : ViewModel() {

    data class ChessState(
        val loading: Boolean = true,
        val profile: ChessProfileDto? = null,
        val quest: MentalQuest = mentalQuestOf(LocalDate.now().dayOfWeek),
        val questProgress: Int = 0,
        val questClaimed: Boolean = false,
        val busy: Boolean = false,
        val notice: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(ChessState())
    val state: StateFlow<ChessState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val quest = mentalQuestOf(LocalDate.now().dayOfWeek)
        val claims = repo.mentalClaimsToday()
        val progress = repo.todayCount(quest.kind, quest.result)
            .coerceAtMost(quest.target)
        _state.value = _state.value.copy(
            loading = false,
            profile = repo.profile(),
            quest = quest,
            questProgress = progress,
            questClaimed = quest.title in claims,
        )
    }

    fun claimQuest() = viewModelScope.launch {
        val s = _state.value
        if (s.busy || s.questClaimed || s.questProgress < s.quest.target) return@launch
        _state.value = s.copy(busy = true)
        repo.completeMentalQuest(s.quest.title, s.quest.xp)
            .onSuccess { xp ->
                _state.value = _state.value.copy(
                    busy = false, questClaimed = true,
                    notice = if (xp > 0) "MENTAL QUEST CLEAR — +$xp MENTAL XP" else "ALREADY CLAIMED",
                )
                refresh()
            }
            .onFailure { e -> _state.value = _state.value.copy(busy = false, error = e.message) }
    }

    fun claimSync() = viewModelScope.launch {
        val s = _state.value
        if (s.busy) return@launch
        _state.value = s.copy(busy = true)
        repo.claimSynergy()
            .onSuccess { msg -> _state.value = _state.value.copy(busy = false, notice = msg) }
            .onFailure { e -> _state.value = _state.value.copy(busy = false, error = e.message) }
    }

    /** Game/puzzle screens persist outcomes through here — server RPC owns the math. */
    suspend fun repoLog(
        kind: String,
        mode: String,
        result: String,
        accuracy: Double? = null,
        blunders: Int = 0,
        thinkMs: Int = 0,
        stats: kotlinx.serialization.json.JsonObject? = null,
        analysis: kotlinx.serialization.json.JsonObject? = null,
    ): Result<ChessRepository.LogResult> {
        val r = repo.logSession(
            kind = kind, mode = mode, result = result,
            accuracy = accuracy, blunders = blunders, thinkMs = thinkMs,
            stats = stats ?: kotlinx.serialization.json.buildJsonObject {},
            analysis = analysis ?: kotlinx.serialization.json.buildJsonObject {},
        )
        if (r.isSuccess) _state.value = _state.value.copy(profile = repo.profile())
        return r
    }

    /** Puzzle outcome → server log + focused stat signals (§7 evolves from play). */
    fun reportPuzzle(kind: String, mode: String, result: String, motif: String, diff: Int) =
        viewModelScope.launch {
            val solved = result == "SOLVED"
            repo.logSession(
                kind = kind, mode = mode, result = result,
                stats = kotlinx.serialization.json.buildJsonObject {
                    put("tactics", if (solved) 72 else 38)
                    put("decision", if (solved) 70 else 40)
                    if (motif in listOf("PIN", "SKEWER", "DISCOVERY", "DEFLECTION")) {
                        put("calculation", if (solved) 74 else 42)
                    }
                    if (solved && diff >= 2) put("focus", 70)
                },
            )
        }

    suspend fun puzzleRepoTodayDailySolved(): Boolean = repo.todayCount("DAILY", "SOLVED") > 0

    fun clearNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}
