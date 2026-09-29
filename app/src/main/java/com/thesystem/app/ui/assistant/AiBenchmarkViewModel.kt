package com.thesystem.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.ai.AIObservability
import com.thesystem.app.ai.AIOrchestrator
import com.thesystem.app.ai.AIRemoteConfig
import com.thesystem.app.ai.AiFlags
import com.thesystem.app.ai.DeviceCapabilityManager
import com.thesystem.app.ai.LocalModelManager
import com.thesystem.app.ai.ModelMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AI BENCHMARK + MODEL BAY (spec §22, master §13/§18 STEP 15) — the AI status
 * page. Capability probes, remote flags, catalog states, counters AND the
 * explicit bundle manager: GET (progress bar), DELETE, zero background fetch.
 * Technical metrics only; no personal content beyond honest engine labels.
 */
@HiltViewModel
class AiBenchmarkViewModel @Inject constructor(
    private val capability: DeviceCapabilityManager,
    private val config: AIRemoteConfig,
    private val models: LocalModelManager,
    private val orchestrator: AIOrchestrator,
    private val obs: AIObservability,
) : ViewModel() {

    data class ModelRow(
        val meta: ModelMeta,
        val state: LocalModelManager.ModelState,
        val eligible: Boolean,               // device tier can actually run it
        val downloading: Boolean = false,
        val progress: Float = -1f,           // 0..1 while fetching
        val note: String? = null,            // "NEEDS WI-FI" / "LOW STORAGE" ...
    )

    data class BenchState(
        val loading: Boolean = true,
        val report: DeviceCapabilityManager.Report? = null,
        val flags: AiFlags = AiFlags(),
        val rows: List<ModelRow> = emptyList(),
        val metrics: Map<String, String> = emptyMap(),
        val lastEvent: String = "",
        val brainLine: String = "",
        val onUnmeteredNet: Boolean = true,
        val modelsBytes: Long = 0L,
    )

    private val _state = MutableStateFlow(BenchState())
    val state: StateFlow<BenchState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val flags = config.refresh()
        val tier = capability.tier(flags.minFreeStorageMb)
        val prev = _state.value
        _state.value = BenchState(
            loading = false,
            report = capability.report(flags.minFreeStorageMb),
            flags = flags,
            rows = flags.models.map { m ->
                val keep = prev.rows.firstOrNull { it.meta.id == m.id }
                ModelRow(
                    meta = m,
                    state = models.state(m),
                    eligible = tier != com.thesystem.app.ai.ModelTier.NONE && eligibleFor(tier, m),
                    downloading = keep?.downloading ?: false,
                    progress = keep?.progress ?: -1f,
                    note = keep?.note,
                )
            },
            metrics = obs.snapshot(),
            lastEvent = obs.lastEvent,
            brainLine = orchestrator.activeBrainLine(),
            onUnmeteredNet = capability.isUnmetered(),
            modelsBytes = models.totalBytes(),
        )
    }

    /** Tier ladder law: a model tiers BELOW the device class may run, never above. */
    private fun eligibleFor(tier: com.thesystem.app.ai.ModelTier, m: ModelMeta): Boolean {
        val order = listOf("TINY", "SMALL", "MID", "PLUS")
        val devIdx = order.indexOf(tier.name)
        val mIdx = order.indexOf(m.tier)
        return mIdx >= 0 && mIdx <= devIdx
    }

    fun download(modelId: String) = viewModelScope.launch {
        if (_state.value.rows.any { it.downloading }) return@launch // one at a time
        fun row(rowNote: String? = null, progress: Float = -1f, dl: Boolean = false) {
            _state.value = _state.value.copy(
                rows = _state.value.rows.map { if (it.meta.id == modelId) it.copy(note = rowNote, progress = progress, downloading = dl) else it },
            )
        }
        row(dl = true, progress = 0f, rowNote = null)
        val result = orchestrator.downloadModel(modelId) { got, total ->
            if (total > 0) row(dl = true, progress = (got.toFloat() / total).coerceIn(0f, 1f))
        }
        when (result) {
            is AIOrchestrator.DownloadResult.Done ->
                _state.value = _state.value.copy(
                    brainLine = orchestrator.activeBrainLine(),
                    modelsBytes = models.totalBytes(),
                ).also {
                    row(rowNote = if (result.ok) "VERIFIED · READY" else "FETCH FAILED — RULES ENGINE REMAINS", dl = false)
                }
            AIOrchestrator.DownloadResult.NeedsWifi -> row(rowNote = "NEEDS UNMETERED WI-FI — bundle respects your data plan", dl = false)
            AIOrchestrator.DownloadResult.NoStorage -> row(rowNote = "NOT ENOUGH FREE STORAGE", dl = false)
            AIOrchestrator.DownloadResult.NoArtifact -> row(rowNote = "NO PUBLISHED ARTIFACT — catalog entry preserved", dl = false)
            AIOrchestrator.DownloadResult.NoSuchModel -> row(rowNote = "NOT IN CATALOG", dl = false)
        }
        _state.value = _state.value.copy(brainLine = orchestrator.activeBrainLine(), modelsBytes = models.totalBytes())
    }

    fun delete(modelId: String) = viewModelScope.launch {
        orchestrator.deleteModel(modelId)
        _state.value = _state.value.copy(modelsBytes = models.totalBytes(), brainLine = orchestrator.activeBrainLine())
        refresh()
    }
}
