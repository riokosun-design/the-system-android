package com.thesystem.app.ui.quest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.ui.arena.PoseMeshOverlay
import java.util.concurrent.Executors

/**
 * QUEST PROOF — LOG never trusts a tap.
 *
 * PUSH / SQUAT : camera + ML Kit rep counting (same engine as 1v1 wars).
 * RUN          : hardware step counter converted to meters.
 * When the verified amount reaches the target the session is uploaded and
 * the quest can only then complete (server-enforced).
 */
@Composable
fun QuestProofScreen(
    questId: Long,
    mode: String,
    target: Int,
    onExit: () -> Unit,
    vm: QuestProofViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    val permission = if (mode == "RUN") Manifest.permission.ACTIVITY_RECOGNITION else Manifest.permission.CAMERA

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; if (ok) haptics.tick() else haptics.error()
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(permission) }

    // success handshake → brief VERIFIED readout, then return
    LaunchedEffect(s.verifiedComplete) {
        if (s.verifiedComplete) {
            haptics.success()
            kotlinx.coroutines.delay(1400)
            onExit()
        }
    }

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PaperWhite)
                }
                Text(
                    when (mode) {
                        "SQUAT" -> "PROOF · SQUATS"
                        "RUN" -> "PROOF · RUN DISTANCE"
                        else -> "PROOF · PUSH-UPS"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = SkyBlue,
                )
            }

            if (!granted) {
                PermissionCard(permission, mode) { launcher.launch(permission) }
            } else when (mode) {
                "RUN" -> RunProofStage(target, s.meters, vm)
                else -> CameraProofStage(
                    mode = if (mode == "SQUAT") QuestPoseCounter.QuestMode.SQUAT else QuestPoseCounter.QuestMode.PUSH,
                    target = target,
                    current = s.count,
                    submitting = s.submitting,
                    onReps = vm::setReps,
                )
            }

            s.error?.let {
                Text(it, color = CrimsonRed, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.weight(1f))

            // bottom action rail
            if (granted && !s.verifiedComplete) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("CANCEL", onExit, Modifier.weight(1f))
                    val partial = if (mode == "RUN") s.meters else s.count
                    NeonButton(
                        if (s.submitting) "UPLOADING…" else "END & SAVE $partial",
                        { haptics.select(); vm.endAndSave { complete -> if (complete) Unit else onExit() } },
                        Modifier.weight(1.4f),
                        color = SkyBlue,
                    )
                }
            }

            if (s.verifiedComplete) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("VERIFIED — QUEST LOGGED", color = SkyBlue,
                        fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(permission: String, mode: String, onGrant: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text(
            if (mode == "RUN") "ACTIVITY ACCESS REQUIRED" else "CAMERA REQUIRED",
            color = PaperWhite, style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (mode == "RUN") "Run distance is proven with the hardware step counter. 100% on-device."
            else "Reps are counted on-device with pose tracking. No video is recorded or uploaded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        NeonButton("GRANT ACCESS", onGrant, Modifier.fillMaxWidth())
    }
}

/** Camera stage shared by PUSH and SQUAT proof. */
@Composable
private fun CameraProofStage(
    mode: QuestPoseCounter.QuestMode,
    target: Int,
    current: Int,
    submitting: Boolean,
    onReps: (Int) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var mesh by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var depth by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableStateOf(QuestPoseCounter.RepPhase.UP) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    // one detector drives both rep counting and the hologram mesh overlay
    val landmarkCounter = remember(mode) {
        QuestPoseCounter(mode, onRep = onReps,
            onDepth = { d, p -> depth = d; phase = p },
            onLandmarks = { mesh = it })
    }
    DisposableEffect(landmarkCounter) { onDispose { landmarkCounter.close() } }
    LaunchedEffect(preview) {
        val view = preview ?: return@LaunchedEffect
        val provider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        provider.unbindAll()
        val camPreview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor) { proxy -> landmarkCounter.process(proxy) } }
        val selector = if (mode == QuestPoseCounter.QuestMode.SQUAT) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        provider.bindToLifecycle(lifecycleOwner, selector, camPreview, analysis)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth().height(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        ) {
            AndroidView(factory = { ctx -> PreviewView(ctx).also { preview = it } }, modifier = Modifier.matchParentSize())
            if (!submitting) PoseMeshOverlay(points = mesh, repFlash = depth, modifier = Modifier.matchParentSize())
            Column(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (phase) {
                        QuestPoseCounter.RepPhase.DOWN -> if (mode == QuestPoseCounter.QuestMode.SQUAT) "DEEP — HOLD" else "BOTTOM"
                        QuestPoseCounter.RepPhase.UP -> "UP"
                        QuestPoseCounter.RepPhase.MIDDLE -> "MOVING"
                    },
                    color = if (phase == QuestPoseCounter.RepPhase.DOWN) SkyBlue else LabelGray,
                    style = MonoLabel,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ProofGauge(current, target, if (mode == QuestPoseCounter.QuestMode.SQUAT) "SQUATS" else "PUSH-UPS", "reps")
    }
}

/** Step-counter stage: delta from session baseline × stride length → meters. */
@Composable
private fun RunProofStage(target: Int, meters: Int, vm: QuestProofViewModel) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val stepSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    var started by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(started) {
        if (started && stepSensor != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val total = e.values[0].toInt()
                    if (baseline == null) baseline = total
                    val steps = (total - (baseline ?: total)).coerceAtLeast(0)
                    // average stride 0.72 m; conservative, verified against target meters
                    vm.setMeters((steps * 0.72).toInt())
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensorManager.unregisterListener(listener) }
        } else onDispose { }
    }

    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        if (stepSensor == null) {
            Text("NO STEP SENSOR ON THIS DEVICE", color = CrimsonRed, style = MaterialTheme.typography.titleMedium)
            Text("Run proof needs a hardware step counter. Territory zone capture still counts field training.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            ProofGauge(meters, target, "DISTANCE", "meters")
            Spacer(Modifier.height(24.dp))
            Text("Keep the phone on you while you run. Distance logs live; the quest completes at ${target}m.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (!started) NeonButton("START RUN PROOF", { started = true }, Modifier.fillMaxWidth(), color = SkyBlue)
            else Text("TRACKING…", color = SkyBlue, fontFamily = SystemMono, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProofGauge(value: Int, target: Int, label: String, unit: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MonoLabel)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", color = PaperWhite, fontFamily = SystemMono,
                fontWeight = FontWeight.Bold, fontSize = 48.sp)
            Text("/$target $unit", color = LabelGray, fontFamily = SystemMono, fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 10.dp, start = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(TrackGray)) {
            val frac = (value.toFloat() / target).coerceIn(0f, 1f)
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(2.dp)).background(SkyBlue))
        }
    }
}
