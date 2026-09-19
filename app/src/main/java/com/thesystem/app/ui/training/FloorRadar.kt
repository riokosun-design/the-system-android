package com.thesystem.app.ui.training

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.InkBlack
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.LineStrong
import com.thesystem.app.core.theme.MonoLabel
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.theme.SystemMono
import com.thesystem.app.core.theme.TrackGray
import com.thesystem.app.core.ui.rememberSystemHaptics

/**
 * FLOOR RADAR REP COUNTER — proximity-sensor rep machine (0.5.1).
 *
 * Born from a field report the camera path could never answer: ML Kit loses
 * the pose exactly at the BOTTOM of a floor-level front-camera push-up (head
 * goes top-down into the lens, shoulders collapse), so every state machine
 * on earth starves at the moment it must validate depth. The proximity sensor
 * does not care about light, angles or silhouettes — it is a binary hardware
 * gate that fires when the hunter's chest/chin crosses ~5 cm above the phone.
 *
 * DOCTRINE (mirrors the camera engine's discipline):
 *  1. FULL CYCLE ONLY — a rep counts on NEAR → FAR (descend past the sensor,
 *     rise back out). Touching the sensor alone is never a rep.
 *  2. NO BOUNCE REPS — a NEAR dwell shorter than [MIN_DWELL_MS] is a hand
 *     wave / threshold flicker, rejected on rise.
 *  3. NO MACHINE-GUN REPS — two counted reps can never land closer than
 *     [MIN_REP_INTERVAL_MS].
 *  4. HONEST RECOVERY — lying on the phone 5 s then rising is ONE rep; a
 *     spurious FAR with no NEAR is ignored; duplicate NEAR events from
 *     continuous sensors collapse into one lock.
 *
 * Sensors are ON-CHANGE (one event per hardware flip), so the machine is
 * purely event-driven — validated 10/10 in the radar_sim.py harness.
 */
class ProximityRadarCounter(
    private val onRep: (reps: Int, tempoMs: Long) -> Unit,
    private val onLock: (locked: Boolean) -> Unit = {},
) {
    var reps = 0
        private set
    var locked = false
        private set
    private var nearAt = 0L
    private var lastRepAt = 0L

    fun onReading(rawNear: Boolean, nowMs: Long) {
        if (rawNear) {
            if (locked) return
            locked = true
            nearAt = nowMs
            onLock(true)
        } else {
            if (!locked) return
            locked = false
            onLock(false)
            val dwell = nowMs - nearAt
            val since = nowMs - lastRepAt
            if (dwell >= MIN_DWELL_MS && since >= MIN_REP_INTERVAL_MS) {
                val tempo = if (lastRepAt == 0L) 0L else since
                reps++
                lastRepAt = nowMs
                onRep(reps, tempo)
            }
        }
    }

    fun reset() {
        reps = 0
        locked = false
        nearAt = 0L
        lastRepAt = 0L
    }

    companion object {
        const val MIN_DWELL_MS = 120L
        const val MIN_REP_INTERVAL_MS = 500L
    }
}

/** Segmented AI CAMERA / FLOOR RADAR selector — shared by proof + war room. */
@Composable
fun RadarModeToggle(radar: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RadarPill("AI CAMERA", selected = !radar) { onChange(false) }
        RadarPill("FLOOR RADAR", selected = radar) { onChange(true) }
    }
}

@Composable
private fun RadarPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) PaperWhite else Color.Transparent)
            .border(1.dp, if (selected) PaperWhite else LineStrong, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            color = if (selected) InkBlack else PaperWhite,
            fontFamily = SystemMono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The radar stage: monochrome HUD, sonar pulse ring, lock tone + rep ACK
 * (audible from under the hunter's chest), internal rep flash. Self-contained —
 * the host only forwards the counted reps into its own quest/battle pipeline.
 */
@Composable
fun FloorRadarPanel(
    count: Int,
    onRep: (count: Int, tempoMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    showCount: Boolean = true,
    overlayText: String? = null,
) {
    val context = LocalContext.current
    val haptics = rememberSystemHaptics()
    var locked by remember { mutableStateOf(false) }
    var flash by remember { mutableFloatStateOf(0f) }
    var lastSeenCount by remember { mutableIntStateOf(count) }

    // audible from the floor: soft tick on target lock, ACK chirp on counted rep
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    val counter = remember {
        ProximityRadarCounter(
            onRep = { n, t ->
                runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 170) }
                haptics.success()
                onRep(n, t)
            },
            onLock = { l ->
                locked = l
                if (l) {
                    runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 45) }
                    haptics.tick()
                }
            },
        )
    }

    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) }
    DisposableEffect(sensor) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                counter.onReading(event.values[0] < event.sensor.maximumRange, event.timestamp / 1_000_000L)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // rep flash: the host's count moved → one expanding ring, decays in ~270ms
    LaunchedEffect(count) {
        if (count > lastSeenCount) flash = 1f
        lastSeenCount = count
        while (flash > 0f) {
            withFrameMillis { }
            flash = (flash - 0.06f).coerceAtLeast(0f)
        }
    }

    val pulse by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "pulse",
    )

    Box(modifier.background(Color.Black)) {
        if (sensor == null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "NO PROXIMITY SENSOR",
                    color = PaperWhite, fontFamily = SystemMono,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                )
                Text(
                    "This device has no proximity hardware — switch back to AI CAMERA.",
                    style = MaterialTheme.typography.bodySmall, color = LabelGray, textAlign = TextAlign.Center,
                )
            }
            return@Box
        }

        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val maxR = size.minDimension / 2f * 0.94f
            // static sonar rings — pure monochrome
            drawCircle(PaperWhite.copy(alpha = 0.07f), radius = maxR, center = c, style = Stroke(1.dp.toPx()))
            drawCircle(PaperWhite.copy(alpha = 0.12f), radius = maxR * 0.66f, center = c, style = Stroke(1.dp.toPx()))
            drawCircle(PaperWhite.copy(alpha = 0.18f), radius = maxR * 0.33f, center = c, style = Stroke(1.dp.toPx()))
            // outgoing pulse — SkyBlue only on this training surface, only when locked
            val pulseColor = if (locked) SkyBlue else PaperWhite
            drawCircle(
                pulseColor.copy(alpha = (1f - pulse) * 0.45f),
                radius = maxR * pulse.coerceAtLeast(0.05f), center = c, style = Stroke(1.5.dp.toPx()),
            )
            // rep flash ring
            if (flash > 0f) {
                drawCircle(
                    PaperWhite.copy(alpha = flash * 0.8f),
                    radius = maxR * (1.05f - flash * 0.2f), center = c, style = Stroke(3.dp.toPx()),
                )
            }
            // the target node
            drawCircle(if (locked) SkyBlue else TrackGray, radius = maxR * 0.15f, center = c)
            drawCircle(PaperWhite.copy(alpha = 0.85f), radius = maxR * 0.15f, center = c, style = Stroke(1.5.dp.toPx()))
        }

        if (showCount) {
            Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Text("REP", style = MonoLabel, color = SkyBlue)
                Text(
                    "$count", color = PaperWhite, fontFamily = SystemMono,
                    fontWeight = FontWeight.Bold, fontSize = 44.sp,
                )
            }
        }

        overlayText?.let {
            Text(
                it, color = PaperWhite, fontFamily = SystemMono,
                fontWeight = FontWeight.Black, fontSize = 34.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
            )
        }

        if (!locked && count == 0 && overlayText == null) {
            Text(
                "CHEST / CHIN OVER THE SENSOR",
                color = LabelGray, style = MonoLabel, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(14.dp),
            )
        }

        val statusText = when {
            locked -> "TARGET LOCKED — PUSH UP"
            count > 0 -> "LOCKED OUT — NEXT REP"
            else -> "SENSOR READY — FULL DESCENT = 1 REP"
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter).padding(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, SkyBlue.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(statusText, color = SkyBlue, fontFamily = SystemMono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
