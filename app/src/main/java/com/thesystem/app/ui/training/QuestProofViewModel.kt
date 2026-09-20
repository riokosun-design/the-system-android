package com.thesystem.app.ui.training

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.repo.SystemRepository
import com.thesystem.app.service.RecoveryTracker
import com.thesystem.app.ui.training.tcn.FeatureHarvester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Which proof channel the server asked for. The client never decides:
 * `daily_quests.verification` / `course_quests.verification` drives it, and it
 * is admin-editable content.
 */
enum class ProofMode {
    CAMERA,   // push-up / squat — ML Kit pose rep counting
    STEPS,    // run / walk — hardware step counter (foreground HEALTH service)
    TIMER,    // time-based holds (plank, mobility) — client timer, server duration
    MANUAL;   // admin-enabled only

    companion object {
        fun of(raw: String?): ProofMode = when (raw?.uppercase()) {
            "STEPS", "RUN", "WALK" -> STEPS
            "TIMER" -> TIMER
            "MANUAL" -> MANUAL
            else -> CAMERA
        }
    }
}

data class QuestProofState(
    val questId: Long = 0,
    val seq: Int = 1,
    val title: String = "",
    val mode: ProofMode = ProofMode.CAMERA,
    val exercise: PoseRepCounter.RepExercise = PoseRepCounter.RepExercise.PUSHUP,
    val target: Int = 1,
    val unit: String = "REPS",
    val restSec: Int = 150,
    val xp: Int = 35,
    /** verified so far this session */
    val count: Int = 0,
    val meters: Int = 0,
    val activeSec: Int = 0,
    val poseStatus: PoseRepCounter.PoseStatus = PoseRepCounter.PoseStatus.WAITING,
    /** PHASE 0 envelope — gate verdict line + live engine v4 status (push-ups). */
    val calibNote: String? = null,
    val engineStatus: PushupEngineV4.EngineStatus? = null,
    /** Phase 2 corpus: null = loading, false = not consented (card may offer). */
    val harvestConsent: Boolean? = null,
    val engineConfig: JsonObject? = null,
    val repFlash: Float = 0f,
    val lastQuality: PoseRepCounter.RepQuality? = null,
    val submitting: Boolean = false,
    val verifiedComplete: Boolean = false,
    val timerRunning: Boolean = false,
    val error: String? = null,
) {
    val progress: Int get() = if (mode == ProofMode.STEPS) meters else if (mode == ProofMode.CAMERA) count else activeSec
}

/**
 * Verified-session controller. Nothing here trusts a tap: the amount is
 * produced by camera frames, the hardware step counter or a running clock, and
 * the server refuses the completion unless progress meets the target.
 */
@HiltViewModel
class QuestProofViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val system: SystemRepository,
    orchestrator: com.thesystem.app.ai.AIOrchestrator,
) : ViewModel() {

    init {
        // §4 AI ENGINE: vision owns the device now — any loaded LLM sheds load.
        orchestrator.visionActiveHint(true)
    }

    private val questId: Long = (savedStateHandle.get<String>("questId") ?: "0").toLong()
    private val rawMode: String = savedStateHandle.get<String>("mode") ?: "CAMERA"
    private val exerciseRaw: String = savedStateHandle.get<String>("exercise") ?: "PUSHUP"
    private val targetArg: Int = (savedStateHandle.get<String>("target") ?: "1").toInt()
    private val seqArg: Int = (savedStateHandle.get<String>("seq") ?: "1").toInt()
    // Navigation may hand the path segment back still percent-encoded
    private val titleArg: String = runCatching {
        java.net.URLDecoder.decode(savedStateHandle.get<String>("title") ?: "DAILY PROTOCOL", "UTF-8")
    }.getOrDefault(savedStateHandle.get<String>("title") ?: "DAILY PROTOCOL")
    private val unitArg: String = savedStateHandle.get<String>("unit") ?: "REPS"
    private val restArg: Int = (savedStateHandle.get<String>("rest") ?: "150").toInt()
    private val xpArg: Int = (savedStateHandle.get<String>("xp") ?: "35").toInt()

    private val _state = MutableStateFlow(
        QuestProofState(
            questId = questId,
            seq = seqArg,
            title = titleArg,
            mode = ProofMode.of(rawMode),
            exercise = if (exerciseRaw.uppercase().contains("SQUAT")) PoseRepCounter.RepExercise.SQUAT
            else PoseRepCounter.RepExercise.PUSHUP,
            target = targetArg,
            unit = unitArg,
            restSec = restArg,
            xp = xpArg,
        )
    )
    val state: StateFlow<QuestProofState> = _state

    private var startedAt = System.currentTimeMillis()

    /** Phase 2 corpus channel — decisions become training data ONLY with consent. */
    val harvester = FeatureHarvester(system, viewModelScope)

    fun loadSession() = viewModelScope.launch {
        val consent = system.harvestConsent()
        val cfg = system.engineConfig()
        _state.value = _state.value.copy(harvestConsent = consent, engineConfig = cfg)
        applyHarvestGate()
    }

    fun acceptHarvest() = viewModelScope.launch {
        system.setHarvestConsent(true)
        _state.value = _state.value.copy(harvestConsent = true)
        applyHarvestGate()
    }

    private fun applyHarvestGate() {
        val s = _state.value
        harvester.enabled = s.harvestConsent == true &&
            s.engineConfig?.get("harvest_enabled")?.jsonPrimitive?.booleanOrNull != false
    }

    fun flushHarvest() = harvester.flush()

    // ── camera channel (push-up / squat) ─────────────────────────────────────
    fun onRep(count: Int, quality: PoseRepCounter.RepQuality) {
        _state.value = _state.value.copy(count = count, lastQuality = quality, repFlash = 1f)
        maybeAutoSubmit(count)
    }

    fun onPoseStatus(status: PoseRepCounter.PoseStatus) {
        _state.value = _state.value.copy(poseStatus = status)
    }

    // ── PHASE 0 gate + engine v4 (push-up channel) ───────────────────────────
    fun onCalibrated(profile: CalibProfile) {
        _state.value = _state.value.copy(
            calibNote = if (profile.level == CalibLevel.GREEN) {
                "ENVELOPE GREEN · SIDE VIEW LOCKED"
            } else {
                "ENVELOPE YELLOW · THREE-QUARTER — REDUCED STRICTNESS"
            },
        )
    }

    fun onEngineStatus(status: PushupEngineV4.EngineStatus) {
        _state.value = _state.value.copy(engineStatus = status)
    }

    fun clearFlash() { _state.value = _state.value.copy(repFlash = 0f) }

    // ── step channel (run / walk) ────────────────────────────────────────────
    fun onMeters(meters: Int) {
        _state.value = _state.value.copy(meters = meters)
        maybeAutoSubmit(meters)
    }

    // ── timer channel (holds) ────────────────────────────────────────────────
    fun timerTick() {
        val s = _state.value
        if (!s.timerRunning || s.verifiedComplete) return
        _state.value = s.copy(activeSec = s.activeSec + 1)
        maybeAutoSubmit(s.activeSec + 1)
    }

    fun toggleTimer() {
        val now = !_state.value.timerRunning
        if (now) startedAt = System.currentTimeMillis()
        _state.value = _state.value.copy(timerRunning = now)
    }

    private fun maybeAutoSubmit(amount: Int) {
        val s = _state.value
        if (amount >= s.target && !s.submitting && !s.verifiedComplete) submit(amount)
    }

    /** Manual / partial save: whatever was verified is persisted as evidence. */
    fun endAndSave() = submit(_state.value.progress, partial = true)

    private fun submit(amount: Int, partial: Boolean = false) {
        val s = _state.value
        if (s.submitting || amount <= 0) return
        val kind = when {
            s.mode == ProofMode.STEPS -> "QUEST_RUN"
            s.mode == ProofMode.TIMER -> "QUEST_TIMER"
            s.mode == ProofMode.MANUAL -> "QUEST_MANUAL"
            s.exercise == PoseRepCounter.RepExercise.SQUAT -> "QUEST_SQUAT"
            else -> "QUEST_PUSH"
        }
        val duration = ((System.currentTimeMillis() - startedAt) / 1000L).toInt().coerceAtLeast(1)
        _state.value = s.copy(submitting = true, error = null)
        viewModelScope.launch {
            val proof = system.logQuestProof(s.questId, kind, amount, duration).getOrNull()
            if (proof == null) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = "Proof upload failed — the session stays on screen, retry when back online.",
                )
                return@launch
            }
            if (proof.complete) {
                system.completeQuest(s.questId) // server re-checks: progress must meet target
                _state.value = _state.value.copy(submitting = false, verifiedComplete = true)
            } else {
                // partial progress is saved; the block stays ACTIVE and unlockable later
                _state.value = _state.value.copy(
                    submitting = false,
                    count = if (s.mode == ProofMode.CAMERA) 0 else _state.value.count,
                    error = if (partial) "Partial session saved — ${proof.progress}/${proof.target}." else null,
                )
                if (!partial) startedAt = System.currentTimeMillis()
            }
        }
    }

    /** Called after a verified completion to arm the 2–5 min recovery window. */
    fun armRecovery(context: android.content.Context) {
        val st = _state.value
        if (st.restSec > 0) RecoveryTracker.markBlockDone(context, RecoveryTracker.today(), st.seq, st.restSec)
    }

    fun consumeError() { _state.value = _state.value.copy(error = null) }
}
