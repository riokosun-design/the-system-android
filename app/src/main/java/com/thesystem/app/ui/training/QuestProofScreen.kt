package com.thesystem.app.ui.training

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.service.QuestNotifier
import com.thesystem.app.service.StepCounterService
import com.thesystem.app.service.StepTracking
import com.thesystem.app.ui.arena.PoseMeshOverlay
import com.thesystem.app.ui.training.estimate.PoseEstimatorRouter
import com.thesystem.app.ui.training.tcn.FeatureHarvester
import com.thesystem.app.ui.training.tcn.TcnShadowScorer
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/**
 * VERIFIED PROOF SESSION — LOG never trusts a tap.
 *
 *  CAMERA : push-ups pass through the PHASE 0 envelope (calibration gate +
 *           engine v4 side-view machine, IMU witness); squats use the ML Kit
 *           angle state machine; live skeleton overlay throughout.
 *  STEPS  : foreground HEALTH service keeps counting through music, app switch
 *           and lock screen, then syncs the meters back here.
 *  TIMER  : running clock for holds; the server receives the elapsed seconds.
 *  MANUAL : admin-enabled only.
 *
 * On completion the SYSTEM event fires (notification + overlay) instead of a
 * plain toast, and the next protocol block unlocks after its recovery window.
 */
@Composable
fun QuestProofScreen(
    onExit: () -> Unit,
    vm: QuestProofViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current

    val permission = when (s.mode) {
        ProofMode.STEPS -> Manifest.permission.ACTIVITY_RECOGNITION
        ProofMode.CAMERA -> Manifest.permission.CAMERA
        else -> null
    }
    var granted by remember(permission) {
        mutableStateOf(permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) haptics.tick() else haptics.error()
    }
    LaunchedEffect(permission) { if (permission != null && !granted) launcher.launch(permission) }

    // Phase 2/3/4 session config: harvest consent + server engine knobs (§13)
    LaunchedEffect(Unit) { vm.loadSession() }

    // proof sessions run hands-free with the phone propped — never let the
    // screen sleep mid-set (a sleeping screen also starves some OEM sensors).
    val activity = remember(context) {
        var ctx: Context? = context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
        ctx as? android.app.Activity
    }
    DisposableEffect(Unit) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // ── completion handshake: SYSTEM event + recovery window ────────────────
    LaunchedEffect(s.verifiedComplete) {
        if (!s.verifiedComplete) return@LaunchedEffect
        haptics.success()
        vm.armRecovery(context)
        QuestNotifier.questComplete(
            context = context,
            title = s.title,
            xp = s.xp,
            verifiedLabel = when (s.mode) {
                ProofMode.CAMERA -> "VERIFIED · ${s.count} ${s.unit.lowercase()}"
                ProofMode.STEPS -> "VERIFIED · ${"%.2f".format(s.meters / 1000.0)} KM"
                ProofMode.TIMER -> "VERIFIED · ${s.activeSec}s"
                ProofMode.MANUAL -> "LOGGED"
            },
            rankLine = "Block ${s.seq.toString().padStart(2, '0')} cleared · next unlocked",
        )
        delay(1600)
        onExit()
    }

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PaperWhite)
                }
                Column(Modifier.weight(1f)) {
                    Text(s.title.uppercase(), style = MaterialTheme.typography.titleMedium, color = PaperWhite)
                    Text(
                        "BLOCK ${s.seq.toString().padStart(2, '0')} · ${s.mode.name} PROOF",
                        style = MonoLabel, color = SkyBlue,
                    )
                }
            }

            when {
                !granted -> PermissionCard(s.mode) { permission?.let { launcher.launch(it) } }
                s.mode == ProofMode.CAMERA -> CameraProofStage(s, vm)
                s.mode == ProofMode.STEPS -> StepProofStage(s, vm)
                else -> TimerProofStage(s, vm)
            }

            s.error?.let {
                Text(
                    it, color = LabelGray, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))

            if (granted && !s.verifiedComplete) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("EXIT", onExit, Modifier.weight(1f))
                    NeonButton(
                        if (s.submitting) "UPLOADING…" else "END & SAVE ${s.progress}",
                        { haptics.select(); vm.endAndSave() },
                        Modifier.weight(1.4f),
                        color = SkyBlue,
                        enabled = !s.submitting,
                    )
                }
            }

            if (s.verifiedComplete) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("QUEST COMPLETE", color = SkyBlue, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("+${s.xp} XP · VERIFIED", color = LabelGray, style = MonoLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(mode: ProofMode, onGrant: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text(
            when (mode) {
                ProofMode.STEPS -> "ACTIVITY ACCESS REQUIRED"
                else -> "CAMERA REQUIRED"
            },
            color = PaperWhite, style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when (mode) {
                ProofMode.STEPS -> "Run and walk distance is proven with the hardware step counter. A foreground health service keeps counting while the screen is off."
                else -> "Reps are counted on-device with pose tracking. No video is recorded or uploaded."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        NeonButton("GRANT ACCESS", onGrant, Modifier.fillMaxWidth(), color = SkyBlue)
    }
}

// ── CAMERA STAGE ────────────────────────────────────────────────────────────

@Composable
private fun CameraProofStage(s: QuestProofState, vm: QuestProofViewModel) {
    val context = LocalContext.current
    // rep flash decays on its own — no layout thrash
    val flash by animateFloatAsState(targetValue = if (s.repFlash > 0) 1f else 0f, animationSpec = spring(), label = "flash")
    LaunchedEffect(s.repFlash) { if (s.repFlash > 0) { delay(220); vm.clearFlash() } }

    // Phase 2 corpus ask — persisted decline, never naggy, never video (§11)
    val harvestPrefs = remember(context) { context.getSharedPreferences("harvest_prefs", Context.MODE_PRIVATE) }
    var harvestDeclined by remember { mutableStateOf(harvestPrefs.getBoolean("declined", false)) }

    // no infinite loaders: if the lens takes >8s, admit it and offer RETRY
    var camRetry by remember { mutableIntStateOf(0) }
    var camReady by remember(camRetry) { mutableStateOf(false) }
    var camSlow by remember(camRetry) { mutableStateOf(false) }
    LaunchedEffect(camRetry) {
        delay(8000)
        if (!camReady) camSlow = true
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth().height(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.5.dp, SkyBlue.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
        ) {
            // PHASE 0: push-ups count inside the enforced envelope (gate + engine
            // v4, CV-BATTLE-ARCHITECTURE); squats keep the proven angle machine.
            key(camRetry) {
                if (s.exercise == PoseRepCounter.RepExercise.SQUAT) CameraBox(s, vm, flash, onReady = { camReady = true })
                else PushupV4Box(s, vm, flash, onReady = { camReady = true })
            }
            if (camSlow && !camReady) {
                Box(
                    Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CAMERA INITIALIZATION DELAYED", style = MonoLabel, color = PaperWhite)
                        Spacer(Modifier.height(10.dp))
                        GhostButton("RETRY", { camRetry++ })
                    }
                }
            }
        }

        if (s.exercise == PoseRepCounter.RepExercise.PUSHUP && s.harvestConsent == false && !harvestDeclined) {
            Spacer(Modifier.height(10.dp))
            HarvestConsentCard(
                onEnable = { vm.acceptHarvest() },
                onLater = {
                    harvestDeclined = true
                    harvestPrefs.edit().putBoolean("declined", true).apply()
                },
            )
        }

        Spacer(Modifier.height(14.dp))
        ProofGauge(s.count, s.target, if (s.exercise == PoseRepCounter.RepExercise.SQUAT) "SQUATS" else "PUSH-UPS", "reps")
        s.calibNote?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MonoLabel, color = SkyBlue)
        }
        s.lastQuality?.let { q ->
            Spacer(Modifier.height(8.dp))
            Text(
                "FORM %.0f%% · TEMPO %.1fs · DEPTH %.0f%%".format(q.symmetry * 100, q.tempoMs / 1000.0, q.depthScore * 100),
                style = MonoLabel, color = LabelGray,
            )
        }
    }
}

/** Legibility scrim over the live feed — bright rooms must never white-out the HUD. */
@Composable
private fun BoxScope.FeedScrim() {
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.52f),
                0.20f to Color.Black.copy(alpha = 0.08f),
                0.58f to Color.Black.copy(alpha = 0.10f),
                1f to Color.Black.copy(alpha = 0.64f),
            ),
        ),
    )
}

/** ML Kit stage — lives inside the proof Box so overlays keep BoxScope. */
@Composable
private fun BoxScope.CameraBox(s: QuestProofState, vm: QuestProofViewModel, flash: Float, onReady: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var mesh by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var phase by remember { mutableStateOf(PoseRepCounter.RepPhase.SEARCH) }
    var depth by remember { mutableFloatStateOf(0f) }
    val haptics = rememberSystemHaptics()

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    // one detector drives the counter, the reticle phase and the skeleton mesh
    val counter = remember(s.exercise) {
        PoseRepCounter(
            exercise = s.exercise,
            onRep = { n, q -> haptics.success(); vm.onRep(n, q) },
            onPhase = { p, d -> phase = p; depth = d },
            onLandmarks = { mesh = it },
            onStatus = { vm.onPoseStatus(it) },
        )
    }
    DisposableEffect(counter) { onDispose { counter.close() } }

    // leaving this stage (radar toggle, exit) must release the lens — a bound
    // camera would keep the analyzer, and its counter, alive in the dark.
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

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
            .also { it.setAnalyzer(executor) { proxy -> counter.process(proxy) } }
        // push-ups: front camera, phone on the floor. squats: back camera, full body.
        val selector = if (s.exercise == PoseRepCounter.RepExercise.SQUAT) CameraSelector.DEFAULT_BACK_CAMERA
        else CameraSelector.DEFAULT_FRONT_CAMERA
        provider.bindToLifecycle(lifecycleOwner, selector, camPreview, analysis)
        onReady()
    }

    AndroidView(factory = { ctx -> PreviewView(ctx).also { preview = it } }, modifier = Modifier.matchParentSize())
    FeedScrim()
    PoseMeshOverlay(points = mesh, repFlash = flash * depth, modifier = Modifier.matchParentSize())

    // live rep readout — giant charge counter against the target
    Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
        Text("REP", style = MonoLabel, color = SkyBlue)
        Text(
            "${s.count.toString().padStart(2, '0')} / ${s.target}", color = PaperWhite, fontFamily = SystemMono,
            fontWeight = FontWeight.Bold, fontSize = 40.sp,
        )
    }

    // status ribbon — the fix-it instruction the old build never gave
    val (statusText, statusColor) = when (s.poseStatus) {
        PoseRepCounter.PoseStatus.POSE_NOT_DETECTED -> "POSE NOT DETECTED" to PaperWhite
        PoseRepCounter.PoseStatus.MOVE_BACK -> "MOVE BACK" to PaperWhite
        PoseRepCounter.PoseStatus.FULL_BODY_NOT_VISIBLE -> "FULL BODY NOT VISIBLE" to PaperWhite
        PoseRepCounter.PoseStatus.BAD_ANGLE -> "BAD ANGLE — STRAIGHTEN THE LINE" to PaperWhite
        PoseRepCounter.PoseStatus.TRACKING -> when (phase) {
            PoseRepCounter.RepPhase.BOTTOM -> if (s.exercise == PoseRepCounter.RepExercise.SQUAT) "DEEP — DRIVE UP" else "BOTTOM — PUSH"
            PoseRepCounter.RepPhase.ASCENDING -> "UP"
            PoseRepCounter.RepPhase.DESCENDING -> "CONTROLLED DESCENT"
            else -> "LOCKED OUT — BEGIN"
        } to SkyBlue
        PoseRepCounter.PoseStatus.WAITING -> "ALIGN THE CAMERA" to LabelGray
    }
    Box(
        Modifier
            .align(Alignment.BottomCenter).padding(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(statusText, color = statusColor, fontFamily = SystemMono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * PHASE 0 PUSH-UP STAGE — the envelope, enforced. Frames route through the
 * CalibrationGate until a profile locks, then into engine v4. The hunter is
 * coached into a side view instead of being silently miscounted from the floor.
 * Front camera so the coaching HUD stays visible; IMU vetoes any bump.
 */
@Composable
private fun BoxScope.PushupV4Box(s: QuestProofState, vm: QuestProofViewModel, flash: Float, onReady: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var mesh by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var depth by remember { mutableFloatStateOf(0f) }
    val haptics = rememberSystemHaptics()

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    val witness = remember { com.thesystem.app.core.sensors.ImuStabilityWitness(context) }
    DisposableEffect(witness) {
        witness.start()
        onDispose { witness.stop() }
    }

    // PHASE 1 pose ownership: MoveNet Thunder primary, ML Kit failover, ROI
    // lock, live parity probe riding with the harvest corpus (Phase 2).
    val router = remember { PoseEstimatorRouter(context) }
    DisposableEffect(router) { onDispose { router.close() } }

    // PHASE 3 shadow TCN — scores sequences, rules ALWAYS decide (§20)
    val scorer = remember { TcnShadowScorer.create(context) }
    DisposableEffect(scorer) { onDispose { scorer.close() } }
    var lastParity by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(s.harvestConsent) {
        router.parityEnabled = s.harvestConsent == true
    }
    DisposableEffect(Unit) {
        router.onParity = { d, _ -> lastParity = d }
        onDispose { router.onParity = null; vm.flushHarvest() }
    }

    var profile by remember { mutableStateOf<CalibProfile?>(null) }
    val gate = remember {
        CalibrationGate(
            witness = witness,
            estimator = router,
            onProfile = { p -> profile = p; vm.onCalibrated(p); haptics.success() },
            onLandmarks = { mesh = it },
        )
    }
    val engine = remember(profile) {
        profile?.let { p ->
            PushupEngineV4(
                profile = p,
                strictness = PushupEngineV4.Strictness.STANDARD,
                witness = witness,
                estimator = router,
                engineVersion = "v4.1",
                onRep = { n, q -> haptics.success(); vm.onRep(n, q) },
                onPhase = { _, d -> depth = d },
                onLandmarks = { pts -> mesh = pts },
                onStatus = { st -> vm.onEngineStatus(st) },
                onFrame12 = { v -> scorer.push(v) },
                onDecision = { verdict ->
                    val top = scorer.topClass
                    vm.harvester.onDecision(
                        verdict, "QUEST_PUSH", "v4.1-${router.activeSource}", scorer.snapshot(),
                        buildJsonObject {
                            put("engine", "v4.1")
                            put("estimator", router.activeSource)
                            if (top != null) {
                                put("shadow_class", top.first)
                                put("shadow_conf", top.second)
                            }
                            lastParity?.let { put("parity_mean_dist", it) }
                            put("view_quality", p.viewQuality)
                        },
                    )
                },
            )
        }
    }
    DisposableEffect(gate, engine) { onDispose { gate.close(); engine?.close() } }
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }

    val gateUi by gate.ui.collectAsStateWithLifecycle()
    val engineState = rememberUpdatedState(engine)

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
            .also {
                it.setAnalyzer(executor) { proxy ->
                    // gate until the envelope locks; engine afterwards (gate idles in LOCKED)
                    engineState.value?.process(proxy) ?: gate.process(proxy)
                }
            }
        // side view works with either lens; front keeps the coaching HUD visible
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, camPreview, analysis)
        onReady()
    }

    AndroidView(factory = { ctx -> PreviewView(ctx).also { preview = it } }, modifier = Modifier.matchParentSize())
    FeedScrim()
    PoseMeshOverlay(points = mesh, repFlash = flash * depth, modifier = Modifier.matchParentSize())

    if (profile == null) {
        GateOverlay(
            gateUi,
            ranked = false,
            stuckMs = gateUi.stuckMs,
            onRelaxed = { haptics.tick(); gate.relaxedLock() },  // gate fires onProfile itself
        )
        return
    }

    Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
        Text("REP", style = MonoLabel, color = SkyBlue)
        Text(
            "${s.count.toString().padStart(2, '0')} / ${s.target}", color = PaperWhite, fontFamily = SystemMono,
            fontWeight = FontWeight.Bold, fontSize = 40.sp,
        )
    }

    val (statusText, statusColor) = when (s.engineStatus) {
        PushupEngineV4.EngineStatus.SEARCHING -> "FIND THE TOP — ARMS LOCKED, BODY STRAIGHT" to LabelGray
        PushupEngineV4.EngineStatus.READY -> "ARMED — CONTROLLED REPS ONLY" to SkyBlue
        PushupEngineV4.EngineStatus.DESCENDING -> "CONTROLLED DESCENT" to SkyBlue
        PushupEngineV4.EngineStatus.ASCENDING -> "DRIVE UP" to SkyBlue
        PushupEngineV4.EngineStatus.VERIFYING -> "VERIFYING REP — HOLD POSITION" to PaperWhite
        PushupEngineV4.EngineStatus.REJECT_DEPTH -> "NO COUNT — CHEST DIDN'T DROP" to PaperWhite
        PushupEngineV4.EngineStatus.REJECT_FORM -> "NO COUNT — BODY LINE BROKEN" to PaperWhite
        PushupEngineV4.EngineStatus.REJECT_TEMPO -> "NO COUNT — IMPOSSIBLE TEMPO" to PaperWhite
        PushupEngineV4.EngineStatus.REJECT_POSE -> "REP LOST — STAY IN FRAME" to PaperWhite
        PushupEngineV4.EngineStatus.CAMERA_MOVED -> "CAMERA MOVED — RE-LOCKING" to PaperWhite
        PushupEngineV4.EngineStatus.SETTLE -> "HOLD THE TOP — RE-ANCHORING" to PaperWhite
        PushupEngineV4.EngineStatus.DISPUTED -> "ANTI-CHEAT HOLD — CLEAN REPS ONLY" to PaperWhite
        null -> "ENVELOPE LOCKED" to SkyBlue
    }
    Box(
        Modifier
            .align(Alignment.BottomCenter).padding(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(statusText, color = statusColor, fontFamily = SystemMono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ── STEP STAGE (foreground service) ─────────────────────────────────────────

@Composable
private fun StepProofStage(s: QuestProofState, vm: QuestProofViewModel) {
    val context = LocalContext.current
    val snapshot by StepTracking.snapshot.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        StepCounterService.start(context)
        StepTracking.reset()
        StepTracking.questMirror(0, s.target)
        while (true) {
            delay(1000)
            vm.onMeters(StepTracking.snapshot.value.meters)
            StepTracking.questMirror(StepTracking.snapshot.value.meters, s.target)
        }
    }
    DisposableEffect(Unit) { onDispose { if (started) StepCounterService.stop(context) } }

    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        if (!snapshot.sensorPresent) {
            Text("NO STEP SENSOR ON THIS DEVICE", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
            Text(
                "Run proof needs a hardware step counter. Ask the admin to switch this block to TIMER verification.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            return@Column
        }
        ProofGauge(s.meters, s.target, "DISTANCE", "m")
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("STEPS", "${snapshot.steps}", PaperWhite, Modifier.weight(1f))
            StatTile("KM", "%.2f".format(snapshot.km), SkyBlue, Modifier.weight(1f))
            StatTile("ACTIVE", "${snapshot.activeSec / 60}m", PaperWhite, Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        if (!started) {
            NeonButton("START PROOF", { started = true }, Modifier.fillMaxWidth(), color = SkyBlue)
        } else {
            Text(
                if (snapshot.tracking) "TRACKING — screen off, music on, keep moving" else "STARTING SENSOR…",
                color = SkyBlue, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "The HEALTH service keeps counting through lock screen and app switches; distance syncs here the moment you return.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
    }
}

// ── TIMER STAGE (holds / mobility) ──────────────────────────────────────────

@Composable
private fun TimerProofStage(s: QuestProofState, vm: QuestProofViewModel) {
    LaunchedEffect(s.timerRunning) {
        while (s.timerRunning && !s.verifiedComplete) {
            delay(1000)
            vm.timerTick()
        }
    }
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Text("HOLD TIME", style = MonoLabel, color = LabelGray)
        Text(
            "%02d:%02d".format(s.activeSec / 60, s.activeSec % 60),
            color = PaperWhite, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 56.sp,
        )
        Text("TARGET ${s.target}s", style = MonoLabel, color = SkyBlue)
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(TrackGray)
        ) {
            val frac = (s.activeSec.toFloat() / s.target.coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(SkyBlue))
        }
        Spacer(Modifier.height(22.dp))
        NeonButton(
            if (s.timerRunning) "PAUSE" else "START TIMER",
            { vm.toggleTimer() },
            Modifier.fillMaxWidth(),
            color = SkyBlue,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (s.mode == ProofMode.MANUAL) "MANUAL LOGGING IS ENABLED FOR THIS BLOCK BY THE ADMIN."
            else "The server records the elapsed seconds as your evidence.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
    }
}

/**
 * Phase 2 opt-in card (§11): the hunter's feature DATA — never video — trains
 * the counter that failed them on the floor. Explicit consent, server-stored.
 */
@Composable
private fun HarvestConsentCard(onEnable: () -> Unit, onLater: () -> Unit) {
    GlowCard {
        Text("HELP BUILD THE ENGINE", style = MonoLabel, color = SkyBlue)
        Spacer(Modifier.height(6.dp))
        Text(
            "Opt in to share anonymous motion features from your proofs (joint angles + rep verdicts — NEVER images or video) so the counter learns every body and phone. This is how the TCN earns its stripes.",
            style = MaterialTheme.typography.bodySmall, color = LabelGray,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton("ENABLE", onEnable, Modifier.weight(1f), color = SkyBlue)
            GhostButton("NOT NOW", onLater, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProofGauge(value: Int, target: Int, label: String, unit: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MonoLabel, color = SkyBlue)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", color = PaperWhite, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 46.sp)
            Text(
                "/$target $unit", color = LabelGray, fontFamily = SystemMono, fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 10.dp, start = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(TrackGray)) {
            val frac = (value.toFloat() / target.coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(2.dp)).background(SkyBlue))
        }
    }
}

/** Shared by the training screens: metres from a step count. */
fun stepsToMeters(steps: Int, stride: Double = StepTracking.strideM): Int = (steps * stride).toInt()

/** Convenience for screens that only need a permission check. */
fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
