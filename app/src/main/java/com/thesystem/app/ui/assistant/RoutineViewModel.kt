package com.thesystem.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.ai.AIOrchestrator
import com.thesystem.app.ai.AiJson
import com.thesystem.app.ai.ContextEngine
import com.thesystem.app.ai.RoutineItem
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject

/**
 * SYSTEM ROUTINE (spec §14) — new first-class module. Drafts come from the
 * orchestrator; the hunter accepts / edits (toggle·remove) / rejects /
 * regenerates. A CONFIRMED routine is NEVER silently overwritten: regenerate
 * requires an armed double-tap, and edits demote CONFIRMED → DRAFT.
 */
@HiltViewModel
class RoutineViewModel @Inject constructor(
    private val system: SystemRepository,
    private val orchestrator: AIOrchestrator,
    private val contextEngine: ContextEngine,
) : ViewModel() {

    enum class RStatus { NONE, DRAFT, CONFIRMED }

    data class RoutineState(
        val loading: Boolean = true,
        val status: RStatus = RStatus.NONE,
        val items: List<RoutineItem> = emptyList(),
        val brain: String = "",
        val busy: Boolean = false,
        val regenArmed: Boolean = false,
        val notice: String? = null,
        val error: String? = null,
    ) {
        val doneCount: Int get() = items.count { it.status == "DONE" }
    }

    private val _state = MutableStateFlow(RoutineState())
    val state: StateFlow<RoutineState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val dto = system.routineToday()
        val items = dto?.let { d ->
            runCatching { AiJson.decodeFromJsonElement(ListSerializer(RoutineItem.serializer()), d.items) }.getOrNull()
        }.orEmpty()
        _state.value = _state.value.copy(
            loading = false,
            items = items,
            status = when (dto?.status) {
                "CONFIRMED" -> RStatus.CONFIRMED
                "DRAFT" -> if (items.isEmpty()) RStatus.NONE else RStatus.DRAFT
                else -> RStatus.NONE
            },
            regenArmed = false,
        )
    }

    fun generate() = viewModelScope.launch {
        val s = _state.value
        if (s.busy) return@launch
        if (s.status == RStatus.CONFIRMED && !s.regenArmed) {
            _state.value = s.copy(regenArmed = true, notice = "TAP AGAIN — current routine is CONFIRMED and will be replaced")
            return@launch
        }
        _state.value = s.copy(busy = true, notice = null, regenArmed = false)
        val snap = contextEngine.snapshot(
            setOf(
                ContextEngine.Need.PROFILE, ContextEngine.Need.QUESTS,
                ContextEngine.Need.TRAINING, ContextEngine.Need.ROUTINE,
            ),
        )
        val out = orchestrator.draftRoutine(snap)
        val draft = out.value
        _state.value = _state.value.copy(
            busy = false,
            items = draft?.items.orEmpty(),
            status = if (draft?.items.isNullOrEmpty()) _state.value.status else RStatus.DRAFT,
            brain = when (out.brain) {
                AIOrchestrator.Brain.LOCAL_LLM -> "LOCAL LLM"
                AIOrchestrator.Brain.DETERMINISTIC -> "RULES"
                AIOrchestrator.Brain.DISABLED -> out.note
            },
            notice = if (draft?.items.isNullOrEmpty()) "NO ROUTINE — ${out.note.ifBlank { "off-grid" }}" else null,
        )
    }

    /** Edit = toggle done / remove rows. Edits demote CONFIRMED → DRAFT (§14). */
    fun toggle(idx: Int) {
        val s = _state.value
        val it = s.items.getOrNull(idx) ?: return
        val next = s.items.toMutableList()
        next[idx] = it.copy(status = if (it.status == "DONE") "PENDING" else "DONE")
        _state.value = s.copy(items = next, status = if (s.status == RStatus.CONFIRMED) RStatus.DRAFT else s.status)
    }

    fun remove(idx: Int) {
        val s = _state.value
        if (s.status == RStatus.CONFIRMED) return // confirmed rows are locked
        if (idx !in s.items.indices) return
        _state.value = s.copy(items = s.items.toMutableList().also { it.removeAt(idx) })
    }

    fun accept() = viewModelScope.launch {
        val s = _state.value
        if (s.items.isEmpty() || s.busy) return@launch
        _state.value = s.copy(busy = true)
        system.saveRoutine(JsonArray(s.items.map { AiJson.encodeToJsonElement(RoutineItem.serializer(), it) }), "CONFIRMED")
            .onSuccess {
                _state.value = _state.value.copy(busy = false, status = RStatus.CONFIRMED, regenArmed = false, notice = "ROUTINE CONFIRMED — vault synced")
            }
            .onFailure { e ->
                _state.value = _state.value.copy(busy = false, error = e.message ?: "sync failed")
            }
    }

    fun reject() = viewModelScope.launch {
        val s = _state.value
        if (s.busy) return@launch
        _state.value = s.copy(busy = true)
        system.saveRoutine(JsonArray(emptyList()), "DRAFT")
            .onSuccess { load() }
            .onFailure { _state.value = _state.value.copy(busy = false, error = "off-grid") }
    }

    fun clearNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}
