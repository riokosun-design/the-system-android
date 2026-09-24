package com.thesystem.app.ui.assistant

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.ai.AIOrchestrator
import com.thesystem.app.ai.ContextEngine
import com.thesystem.app.ai.Personality
import com.thesystem.app.data.model.AiNoteDto
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AssistantMsg(
    val fromUser: Boolean,
    val text: String,
    val brain: String = "",          // "LOCAL LLM" | "RULES" | "OFF"
)

data class AssistantState(
    val messages: List<AssistantMsg> = listOf(
        AssistantMsg(
            fromUser = false,
            text = "What are we working on? Pick a track below — or just ask.",
            brain = "RULES",
        ),
    ),
    val busy: Boolean = false,
    val personality: Personality = Personality.COACH,
    val notes: List<AiNoteDto> = emptyList(),
    val notice: String? = null,
)

/**
 * SYSTEM ASSISTANT (spec §12, §13, §15) — chat is in-memory ONLY; the only
 * persistence is notes the hunter explicitly saves (ai_notes, RLS own rows).
 * Tone comes from Personality; it can never touch XP/Rank/rules.
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val system: SystemRepository,
    private val orchestrator: AIOrchestrator,
    private val contextEngine: ContextEngine,
    @ApplicationContext app: Context,
) : ViewModel() {

    private val prefs = app.getSharedPreferences("assistant_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AssistantState(
            personality = runCatching { Personality.valueOf(prefs.getString("personality", "COACH") ?: "COACH") }
                .getOrDefault(Personality.COACH),
        ),
    )
    val state: StateFlow<AssistantState> = _state

    init { loadNotes() }

    fun loadNotes() = viewModelScope.launch {
        _state.value = _state.value.copy(notes = system.aiNotes())
    }

    fun setPersonality(p: Personality) {
        prefs.edit().putString("personality", p.name).apply()
        _state.value = _state.value.copy(personality = p)
    }

    fun send(raw: String) = viewModelScope.launch {
        val text = raw.trim().take(300)
        if (text.isEmpty() || _state.value.busy) return@launch
        val persona = _state.value.personality
        _state.value = _state.value.copy(
            messages = _state.value.messages + AssistantMsg(fromUser = true, text = text),
            busy = true,
            notice = null,
        )
        val snap = contextEngine.snapshot(
            setOf(
                ContextEngine.Need.PROFILE, ContextEngine.Need.QUESTS,
                ContextEngine.Need.PERFORMANCE, ContextEngine.Need.TRAINING,
                ContextEngine.Need.NOTES,
            ),
        )
        val out = orchestrator.ask(text, persona, snap)
        val tag = when (out.brain) {
            AIOrchestrator.Brain.LOCAL_LLM -> "LOCAL LLM"
            AIOrchestrator.Brain.DETERMINISTIC -> "RULES"
            AIOrchestrator.Brain.DISABLED -> "OFF"
        }
        _state.value = _state.value.copy(
            messages = _state.value.messages + AssistantMsg(
                fromUser = false,
                text = out.value?.text ?: "…",
                brain = tag,
            ),
            busy = false,
        )
    }

    /** Explicit memory save — the ONLY way anything from chat persists (§15). */
    fun saveMemory(note: String) = viewModelScope.launch {
        system.saveAiNote(note)
            .onSuccess {
                _state.value = _state.value.copy(notice = "MEMORY SAVED")
                loadNotes()
            }
            .onFailure { _state.value = _state.value.copy(notice = "SAVE FAILED — off-grid") }
    }

    fun deleteNote(id: Long) = viewModelScope.launch {
        system.deleteAiNote(id); loadNotes()
    }

    fun clearNotice() { _state.value = _state.value.copy(notice = null) }

    override fun onCleared() {
        orchestrator.release() // §4: model resources die with the screen
        super.onCleared()
    }
}
