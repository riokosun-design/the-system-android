package com.thesystem.app.ui.quest

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuestProofState(
    val questId: Long = 0,
    val mode: String = "PUSH", // PUSH | SQUAT | RUN
    val target: Int = 1,
    /** verified reps so far this session (push/squat) */
    val count: Int = 0,
    /** verified meters so far this session (run) */
    val meters: Int = 0,
    val submitting: Boolean = false,
    val verifiedComplete: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class QuestProofViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val system: SystemRepository,
) : ViewModel() {

    private val questId: Long = checkNotNull(savedStateHandle.get<String>("questId")).toLong()
    private val mode: String = savedStateHandle.get<String>("mode") ?: "PUSH"
    private val target: Int = (savedStateHandle.get<String>("target") ?: "1").toInt()

    private val _state = MutableStateFlow(QuestProofState(questId = questId, mode = mode, target = target))
    val state: StateFlow<QuestProofState> = _state

    private var startedAt = System.currentTimeMillis()

    fun setReps(reps: Int) {
        _state.value = _state.value.copy(count = reps)
        if (reps >= target && !_state.value.submitting && !_state.value.verifiedComplete) {
            submit(reps.coerceAtMost(target))
        }
    }

    fun setMeters(meters: Int) {
        _state.value = _state.value.copy(meters = meters)
        if (meters >= target && !_state.value.submitting && !_state.value.verifiedComplete) {
            submit(meters.coerceAtMost(target))
        }
    }

    /** Manually end the session and persist whatever was verified. */
    fun endAndSave(onResult: (Boolean) -> Unit) {
        val amount = if (mode == "RUN") _state.value.meters else _state.value.count
        submit(amount.coerceAtMost(target), onResult, partial = true)
    }

    /**
     * Atomic proof commit: the server writes the workouts evidence row and
     * advances progress; completion RPC refuses unless the target is met.
     */
    fun submit(amount: Int, onResult: (Boolean) -> Unit = {}, partial: Boolean = false) {
        val s = _state.value
        if (s.submitting || amount <= 0) { onResult(false); return }
        val kind = when (s.mode) {
            "SQUAT" -> "QUEST_SQUAT"
            "RUN" -> "QUEST_RUN"
            else -> "QUEST_PUSH"
        }
        val duration = ((System.currentTimeMillis() - startedAt) / 1000L).toInt().coerceAtLeast(1)
        _state.value = s.copy(submitting = true, error = null)
        viewModelScope.launch {
            val res = system.logQuestProof(s.questId, kind, amount, duration)
            val proof = res.getOrNull()
            if (proof == null) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = "Proof upload failed. Check the network and retry — the reps stay on screen.",
                )
                onResult(false)
                return@launch
            }
            if (proof.complete) {
                system.completeQuest(s.questId) // guarded server-side: progress must meet target
                _state.value = _state.value.copy(submitting = false, verifiedComplete = true)
                onResult(true)
            } else {
                _state.value = _state.value.copy(submitting = false)
                if (!partial) startedAt = System.currentTimeMillis()
                onResult(false)
            }
        }
    }

    fun consumeError() { _state.value = _state.value.copy(error = null) }
}
