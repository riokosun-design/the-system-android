package com.thesystem.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.ai.AIObservability
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
 * AI BENCHMARK (spec §22) — admin/debug-only. Reads capability probes, the
 * remote flags, catalog model states and the counters store. Renders no user
 * content beyond technical metrics.
 */
@HiltViewModel
class AiBenchmarkViewModel @Inject constructor(
    private val capability: DeviceCapabilityManager,
    private val config: AIRemoteConfig,
    private val models: LocalModelManager,
    private val obs: AIObservability,
) : ViewModel() {

    data class BenchState(
        val loading: Boolean = true,
        val report: DeviceCapabilityManager.Report? = null,
        val flags: AiFlags = AiFlags(),
        val modelStates: List<Pair<ModelMeta, LocalModelManager.ModelState>> = emptyList(),
        val metrics: Map<String, String> = emptyMap(),
        val lastEvent: String = "",
    )

    private val _state = MutableStateFlow(BenchState())
    val state: StateFlow<BenchState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val flags = config.refresh()
        _state.value = BenchState(
            loading = false,
            report = capability.report(flags.minFreeStorageMb),
            flags = flags,
            modelStates = flags.models.map { it to models.state(it) },
            metrics = obs.snapshot(),
            lastEvent = obs.lastEvent,
        )
    }
}
