package com.thesystem.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for background step data.
 *
 * The foreground HEALTH service feeds [onHardwareTotal]; every screen reads
 * [snapshot]. Hardware totals are monotonic since boot, so the first reading
 * after (re)start becomes the baseline — that is what makes process restarts
 * and counter resets safe instead of double-counting.
 */
object StepTracking {

    data class StepSnapshot(
        val steps: Int = 0,
        val meters: Int = 0,
        val activeSec: Int = 0,
        val sensorPresent: Boolean = true,
        val tracking: Boolean = false,
        /** quest progress mirror, filled by the proof session on sync */
        val questProgress: Int = 0,
        val questTarget: Int = 0,
    ) {
        val km: Double get() = meters / 1000.0
    }

    private val _snapshot = MutableStateFlow(StepSnapshot())
    val snapshot: StateFlow<StepSnapshot> = _snapshot

    /** average stride — conservative; the server verifies the meter target. */
    var strideM: Double = 0.74

    private var baseline: Long? = null
    private var lastTotal: Long = -1L

    /** Hardware counter reads the sensor's own total; convert to session steps. */
    fun onHardwareTotal(total: Long) {
        if (total <= 0) return
        if (baseline == null || total < (lastTotal.takeIf { it > 0 } ?: 0L)) {
            // first reading OR the counter went backwards (device reboot / reset)
            baseline = total
        }
        lastTotal = total
        val steps = (total - (baseline ?: total)).coerceAtLeast(0L).toInt()
        _snapshot.value = _snapshot.value.copy(
            steps = steps,
            meters = (steps * strideM).toInt(),
            tracking = true,
        )
    }

    fun sensorPresent(present: Boolean) {
        _snapshot.value = _snapshot.value.copy(sensorPresent = present)
    }

    fun tickActiveSecond() {
        _snapshot.value = _snapshot.value.copy(activeSec = _snapshot.value.activeSec + 1)
    }

    fun questMirror(progress: Int, target: Int) {
        _snapshot.value = _snapshot.value.copy(questProgress = progress, questTarget = target)
    }

    /** Fresh session (new run/walk block). */
    fun reset() {
        baseline = null
        lastTotal = -1L
        _snapshot.value = StepSnapshot(sensorPresent = _snapshot.value.sensorPresent)
    }

    fun stopped() {
        _snapshot.value = _snapshot.value.copy(tracking = false)
    }
}
