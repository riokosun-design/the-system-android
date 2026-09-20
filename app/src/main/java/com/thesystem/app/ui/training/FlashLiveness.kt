package com.thesystem.app.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.InkBlack
import com.thesystem.app.core.theme.SystemMono
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Random

/**
 * FLASH LIVENESS — CV-BATTLE-ARCHITECTURE §6/§14 anti-replay challenge.
 *
 * The screen strobes a pseudo-random white pulse pattern the world has never
 * seen; the camera must WITNESS that exact pattern reflected off the hunter's
 * body (screen flash → skin/fabric bounce → luma lift). A phone pointed at a
 * recorded video cannot correlate with a pattern that did not exist when the
 * video was shot; a live relay arrives late and flunks the phase check.
 *
 * Verdict (fail-closed for ranked when the server knob requires it):
 *   contrast = mean(ON windows) / mean(OFF windows) must clear [minContrast]
 *   ≥6 of 7 windows must agree in sign with the strobed pattern
 * Ambient extremes get a named fix-it reason, never a pass.
 */
class FlashLivenessController(
    private val minContrast: Float = 1.35f,
    private val pulseMs: Long = 380L,
    private val pulses: Int = 7,
) {

    enum class Phase { IDLE, RUNNING, PASSED, FAILED }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val frac: Float = 0f,
        val strobeOn: Boolean = false,
        val reason: String = "FLASH CHALLENGE — FACE THE SCREEN",
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private var pattern = BooleanArray(pulses)
    private var startAt = 0L
    private var lastAt = 0L
    private val windows = Array(pulses) { ArrayList<Float>(24) }

    fun start(seed: Long) {
        val r = Random(seed)
        for (i in 0 until pulses) pattern[i] = r.nextFloat() < 0.5f
        // guarantee real signal: at least two ON and two OFF windows
        if (pattern.count { it } < 2) { pattern[1] = true; pattern[4] = true }
        if (pattern.count { !it } < 2) { pattern[0] = false; pattern[3] = false }
        startAt = 0L
        lastAt = 0L
        windows.forEach { it.clear() }
        _ui.value = Ui(Phase.RUNNING, 0f, false, "FLASH CHALLENGE — FACE THE SCREEN")
    }

    /** Fed by the analyzer's luma probe, one call per frame. */
    fun onLuma(luma: Float) {
        val now = System.currentTimeMillis()
        if (_ui.value.phase != Phase.RUNNING) return
        if (startAt == 0L) { startAt = now; lastAt = now }
        val elapsed = now - startAt
        val idx = (elapsed / pulseMs).toInt()
        if (idx >= pulses) { verdict(); return }
        windows[idx].add(luma)
        lastAt = now
        _ui.value = Ui(
            Phase.RUNNING,
            (elapsed.toFloat() / (pulseMs * pulses)).coerceIn(0f, 1f),
            pattern[idx],
            if (pattern[idx]) "FLASH …" else "…",
        )
    }

    private fun verdict() {
        fun mean(w: ArrayList<Float>): Float? = if (w.isEmpty()) null else w.sum() / w.size
        val on = ArrayList<Float>(); val off = ArrayList<Float>()
        var globalSum = 0f; var globalN = 0
        windows.forEach { w -> w.forEach { globalSum += it; globalN++ } }
        if (globalN < pulses * 3) {
            _ui.value = Ui(Phase.FAILED, 1f, false, "CAMERA STARVED — RETRY")
            return
        }
        val globalMean = globalSum / globalN
        var agree = 0
        for (i in 0 until pulses) {
            val m = mean(windows[i]) ?: continue
            if (pattern[i]) on.add(m) else off.add(m)
            if ((m > globalMean) == pattern[i]) agree++
        }
        if (globalMean < 8f) {
            _ui.value = Ui(Phase.FAILED, 1f, false, "LENS COVERED — CLEAR VIEW NEEDED")
            return
        }
        val onMean = if (on.isEmpty()) 0.01f else on.sum() / on.size
        val offMean = if (off.isEmpty()) 0.01f else off.sum() / off.size
        val contrast = onMean / offMean.coerceAtLeast(0.01f)
        when {
            agree >= pulses - 1 && contrast >= minContrast ->
                _ui.value = Ui(Phase.PASSED, 1f, false, "LIVE HUMAN CONFIRMED")
            contrast < minContrast && globalMean > 180f ->
                _ui.value = Ui(Phase.FAILED, 1f, false, "ROOM TOO BRIGHT — DIM IT FOR THE FLASH CHECK")
            else ->
                _ui.value = Ui(Phase.FAILED, 1f, false, "FLASH NOT SEEN — FACE THE SCREEN AND RETRY")
        }
    }

    fun reset() { _ui.value = Ui() }

    val passed: Boolean get() = _ui.value.phase == Phase.PASSED
    val failed: Boolean get() = _ui.value.phase == Phase.FAILED
    val running: Boolean get() = _ui.value.phase == Phase.RUNNING
}

/** Full-white strobe while the challenge runs — the light source IS the screen. */
@Composable
fun FlashStrobeOverlay(ui: FlashLivenessController.Ui) {
    if (ui.phase != FlashLivenessController.Phase.RUNNING) return
    if (ui.strobeOn) Box(Modifier.fillMaxSize().background(Color.White))
    if (!ui.strobeOn) {
        Box(Modifier.fillMaxSize().background(InkBlack.copy(alpha = 0.25f)))
    }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            ui.reason,
            color = if (ui.strobeOn) InkBlack else Color.White,
            fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
