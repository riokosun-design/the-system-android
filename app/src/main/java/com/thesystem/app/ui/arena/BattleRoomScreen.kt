package com.thesystem.app.ui.arena

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.sensors.ImuStabilityWitness
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.ui.training.CalibLevel
import com.thesystem.app.ui.training.CalibProfile
import com.thesystem.app.ui.training.CalibrationGate
import com.thesystem.app.ui.training.FlashLivenessController
import com.thesystem.app.ui.training.FlashStrobeOverlay
import com.thesystem.app.ui.training.GateOverlay
import com.thesystem.app.ui.training.GateUi
import com.thesystem.app.ui.training.PoseRepCounter
import com.thesystem.app.ui.training.PushupEngineV4
import com.thesystem.app.ui.training.estimate.PoseEstimatorRouter
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Realtime Push-up War room — ROUND 2 JUICED:
 * rep pop counters, momentum-shift haptics, last-10s heartbeat,
 * victory slam + gold burst, defeat thud + red shockwave.
 */
@Composable
fun BattleRoomScreen(battleId: String, onExit: () -> Unit, vm: BattleRoomViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberSystemHaptics()

    // ── effect signals ════════════════════════════════════════════════════════
    val (winBurst, fireWinBurst) = rememberEffectSignal()
    val (loseWave, fireLoseWave) = rememberEffectSignal()
    val (momentum, fireMomentum) = rememberEffectSignal()
    var momentumIsMine by remember { mutableStateOf(true) }
    var prevLead = remember { mutableIntStateOf(0) }

    // Lead-change detector with a dead zone so 1-rep ties don't spam
    val lead = when { s.tug > 0.15f -> 1; s.tug < -0.15f -> -1; else -> 0 }
    LaunchedEffect(lead) {
        if (lead != 0 && lead != prevLead.value) {
            momentumIsMine = lead > 0
            fireMomentum()
            haptics.select()
        }
        prevLead.value = lead
    }

    // Last-10-seconds heartbeat
    LaunchedEffect(s.secondsLeft) {
        if (s.counting && s.secondsLeft in 1..10) haptics.tick()
    }

    // Every rep you land = a micro reward tick
    LaunchedEffect(s.myCount) { if (s.myCount > 0) haptics.tick() }

    // Payday or pain
    LaunchedEffect(s.finished) {
        if (s.finished) {
            if (s.iWon == true) { haptics.slam(); fireWinBurst() }
            else { haptics.error(); fireLoseWave() }
        }
    }

    // Pop animations, driven by the same state (pure graphicsLayer)
    val repPop = remember { Animatable(1f) }
    LaunchedEffect(s.myCount) { if (s.myCount > 0) { repPop.snapTo(1.35f); repPop.animateTo(1f, SystemMotion.springPop) } }
    val timerPulse = remember { Animatable(1f) }
    LaunchedEffect(s.secondsLeft) { if (s.counting) { timerPulse.snapTo(1.22f); timerPulse.animateTo(1f, tween(280)) } }

    // SPECTATOR MODE — WATCH reuses this war room; watchers never open the camera
    val spectating = s.battle?.let { b -> s.myId != null && s.myId != b.playerA && s.myId != b.playerB } ?: false

    // DESIGN 2.5 — hologram mesh: ML Kit landmarks flow into the overlay canvas
    var meshPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    // Push-up wars are RANKED — they run inside the PHASE 0 envelope
    // (CV-BATTLE-ARCHITECTURE): the calibration gate must lock before READY can
    // be armed (GREEN required), then engine v4 STRICT judges. Squat wars keep
    // the proven angle machine.
    val warKind = if (s.battle?.exerciseType?.uppercase() == "SQUAT") {
        PoseRepCounter.RepExercise.SQUAT
    } else {
        PoseRepCounter.RepExercise.PUSHUP
    }
    val witness = remember(spectating) { if (spectating) null else ImuStabilityWitness(context) }
    DisposableEffect(witness) {
        witness?.start()
        onDispose { witness?.stop() }
    }

    // PHASE 1 pose ownership (router: MoveNet primary / ML Kit failover) +
    // PHASE 4 flash liveness — ranked wars demand a LIVE human, not a screen.
    val router = remember(spectating) { if (spectating) null else PoseEstimatorRouter(context) }
    DisposableEffect(router) { onDispose { router?.close() } }
    val flash = remember(warKind, spectating) {
        if (spectating || warKind != PoseRepCounter.RepExercise.PUSHUP) null else FlashLivenessController()
    }
    val flashUi = flash?.ui?.collectAsStateWithLifecycle()?.value ?: FlashLivenessController.Ui()
    val flashPassed = flashUi.phase == FlashLivenessController.Phase.PASSED

    var v4Profile by remember { mutableStateOf<CalibProfile?>(null) }
    // challenge fires automatically the moment the envelope locks GREEN
    LaunchedEffect(v4Profile?.level) {
        val f = flash
        if (v4Profile?.level == CalibLevel.GREEN && f != null &&
            f.ui.value.phase == FlashLivenessController.Phase.IDLE
        ) f.start(System.currentTimeMillis())
    }
    val gate = remember(warKind, spectating) {
        if (spectating || warKind != PoseRepCounter.RepExercise.PUSHUP) null
        else CalibrationGate(
            witness = witness!!,
            estimator = router!!,
            onProfile = { p -> v4Profile = p },
            onLandmarks = { meshPoints = it },
            onLuma = { m -> flash?.onLuma(m.toFloat()) },
        )
    }
    val counter: Any? = remember(warKind, spectating, v4Profile) {
        when {
            spectating -> null
            warKind == PoseRepCounter.RepExercise.PUSHUP -> v4Profile?.let { p ->
                PushupEngineV4(
                    profile = p,
                    strictness = PushupEngineV4.Strictness.STRICT,
                    witness = witness!!,
                    estimator = router!!,
                    engineVersion = "v4.1",
                    onRep = { n, q -> vm.onRep(n, q) },
                    onLandmarks = { meshPoints = it },
                )
            }
            else -> PoseRepCounter(
                exercise = warKind,
                onRep = { n, q -> vm.onRep(n, q) },
                onLandmarks = { meshPoints = it },
            )
        }
    }
    DisposableEffect(counter, gate) {
        onDispose {
            gate?.close()
            when (counter) {
                is PushupEngineV4 -> counter.close()
                is PoseRepCounter -> counter.close()
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.onCameraPermission(it) }
    LaunchedEffect(spectating) { if (!spectating) cameraPermission.launch(Manifest.permission.CAMERA) }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            runCatching { cameraProvider?.unbindAll() }
        }
    }
    // one analyzer, always attached: frames route through the gate (lobby time
    // IS calibration time) until the envelope locks, then into the war's engine
    // — but only while the clock runs. Engineers count nothing before LIVE.
    val gateState = rememberUpdatedState(gate)
    val counterState = rememberUpdatedState(counter)
    val countingState = rememberUpdatedState(s.counting)
    LaunchedEffect(s.cameraGranted, previewView != null) {
        val view = previewView ?: return@LaunchedEffect
        if (!s.cameraGranted) return@LaunchedEffect
        val provider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        cameraProvider = provider
        provider.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { proxy ->
                    val g = gateState.value
                    val c = counterState.value
                    when {
                        c is PushupEngineV4 && countingState.value -> c.process(proxy)
                        g != null && c == null -> g.process(proxy)
                        c is PoseRepCounter && countingState.value -> c.process(proxy)
                        else -> proxy.close()
                    }
                }
            }
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
    }

    SystemBackground(wallpaperAlpha = 0.08f) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            Text(
                (if (s.battle?.exerciseType == "SQUAT") "SQUAT WAR" else "PUSH-UP WAR") +
                    if (spectating) " · SPECTATOR" else "",
                style = MaterialTheme.typography.headlineMedium, color = PaperWhite,
            )
            Text(
                if (spectating) "Watching live. Your camera stays off — this is not your war."
                else "${s.durationSec} SECONDS · Winner +${SystemMath.BATTLE_WIN_XP} XP · Loser +${SystemMath.BATTLE_LOSS_XP} XP. No mercy." +
                    if (warKind == PoseRepCounter.RepExercise.PUSHUP) " SIDE-VIEW VERIFIED — THE GATE GUARDS THIS WAR." else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))

            if (spectating) {
                GlowCard {
                    Text("LIVE BOARD", style = MonoLabel, color = SkyBlue)
                    Spacer(Modifier.height(6.dp))
                    TugOfWarBar(s.tug, Modifier.fillMaxWidth().height(54.dp))
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatTile("PLAYER A", "${s.battle?.scoreA ?: 0}", PaperWhite, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        StatTile("PLAYER B", "${s.battle?.scoreB ?: 0}", PaperWhite, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Status ${s.battle?.status ?: "…"} · ${s.secondsLeft}s on the clock",
                        style = MaterialTheme.typography.bodySmall, color = LabelGray,
                    )
                }
            } else if (!s.cameraGranted) {
                GlowCard {
                    Text("CAMERA REQUIRED — ML Kit counts your reps on-device.", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    NeonButton("GRANT CAMERA", { cameraPermission.launch(Manifest.permission.CAMERA) })
                    Spacer(Modifier.height(6.dp))
                    NeonButton("OPEN APP SETTINGS", {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }, color = TextMuted)
                }
            } else {
                val gateUi = gate?.ui?.collectAsStateWithLifecycle()?.value ?: GateUi()
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    AndroidView(factory = { ctx -> PreviewView(ctx).also { previewView = it } }, modifier = Modifier.fillMaxSize())
                    // mesh rides the gate stream while calibrating, the engine while at war
                    if (s.counting || (gate != null && v4Profile == null)) {
                        PoseMeshOverlay(points = meshPoints, repFlash = repPop.value, modifier = Modifier.matchParentSize())
                    }
                    if (flash != null && v4Profile != null) {
                        // PHASE 4 strobe — the screen is the light source
                        FlashStrobeOverlay(flashUi)
                    }
                    if (gate != null && v4Profile == null && s.battle?.status != "FINISHED") {
                        // the gate IS the referee's front door
                        GateOverlay(gateUi, ranked = true)
                    } else if (!s.counting && s.battle?.status != "FINISHED") {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (gate != null && s.battle?.status == "LOBBY") "ENVELOPE LOCKED — ARM READY BELOW"
                                else "PHONE ON THE FLOOR — FRONT CAMERA FACING YOU",
                                color = PaperWhite, style = MonoLabel,
                            )
                        }
                    }
                    if (s.counting) {
                        Text(
                            "${s.secondsLeft}s",
                            color = PaperWhite,
                            fontSize = 42.sp, fontWeight = FontWeight.Black, fontFamily = SystemMono,
                            modifier = Modifier
                                .align(Alignment.TopCenter).padding(8.dp)
                                .graphicsLayer { scaleX = timerPulse.value; scaleY = timerPulse.value },
                        )
                        // high-contrast in-frame rep readout — readable mid-plank
                        Column(
                            Modifier.align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "${s.myCount}",
                                color = PaperWhite, fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = SystemMono,
                                modifier = Modifier.graphicsLayer { scaleX = repPop.value; scaleY = repPop.value },
                            )
                            Text("REPS", color = LabelGray, fontSize = 8.sp, fontFamily = SystemMono, letterSpacing = 3.sp)
                        }
                    }
                }
                // PHASE 4 verdict line — live human required before READY arms
                if (flash != null && v4Profile != null && s.battle?.status == "LOBBY") {
                    Spacer(Modifier.height(8.dp))
                    when (flashUi.phase) {
                        FlashLivenessController.Phase.PASSED ->
                            Text("LIVE HUMAN CONFIRMED", style = MonoLabel, color = SkyBlue)
                        FlashLivenessController.Phase.RUNNING ->
                            Text("FLASH CHALLENGE RUNNING — HOLD STILL", style = MonoLabel, color = LabelGray)
                        FlashLivenessController.Phase.FAILED -> {
                            Text(flashUi.reason, style = MonoLabel, color = LabelGray)
                            Spacer(Modifier.height(6.dp))
                            GhostButton("RETRY FLASH CHECK", {
                                flash.start(System.currentTimeMillis())
                            }, Modifier.fillMaxWidth())
                        }
                        FlashLivenessController.Phase.IDLE -> Unit
                    }
                }
                Spacer(Modifier.height(12.dp))
                TugOfWarBar(s.tug, Modifier.fillMaxWidth().height(54.dp))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f).graphicsLayer { scaleX = repPop.value; scaleY = repPop.value }) {
                        StatTile("YOU", "${s.myCount} reps", PaperWhite, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(10.dp))
                    StatTile("RIVAL", "${s.opponentCount} reps", LabelGray, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.weight(1f))

            when {
                s.finished -> GlowCard(pulse = s.iWon == true) {
                    Text(
                        if (s.iWon == true) "VICTORY — +${SystemMath.BATTLE_WIN_XP} XP" else "DEFEAT — +${SystemMath.BATTLE_LOSS_XP} XP. Train harder.",
                        color = if (s.iWon == true) PaperWhite else LabelGray,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("Final: ${s.battle?.scoreA} — ${s.battle?.scoreB}", style = MaterialTheme.typography.bodyMedium)
                    s.integrity?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it, style = MonoLabel,
                            color = if (it.contains("SUSPICIOUS") || it.contains("INVALID")) LabelGray else PaperWhite,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    NeonButton("LEAVE THE ARENA", onExit, Modifier.fillMaxWidth())
                }
                s.battle?.status == "LOBBY" -> LobbyCard(
                    s, vm, haptics, onExit,
                    gateRequired = warKind == PoseRepCounter.RepExercise.PUSHUP,
                    gateReady = v4Profile?.level == CalibLevel.GREEN &&
                        (s.engineConfig?.get("flash_liveness_required")?.jsonPrimitive?.booleanOrNull != true || flashPassed),
                )
                s.battle?.status == "CANCELLED" -> GlowCard {
                    Text("BATTLE CANCELLED", style = MaterialTheme.typography.titleLarge, color = PaperWhite)
                    Text("The lobby was dismissed. Any prediction stakes are refunded.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    NeonButton("LEAVE", onExit, Modifier.fillMaxWidth())
                }
            }
            s.error?.let { Spacer(Modifier.height(8.dp)); RestrictionBanner(it) }
        }

        // ── celebration overlays (State-driven, zero composition cost when idle) ═
        XpBurst(winBurst, color = PaperWhite)
        LevelUpShockwave(loseWave, color = PaperWhite)
        MomentumFlash(momentum, momentumIsMine)
    }
}

/** "MOMENTUM" flash stamped over the arena when the lead swaps hands. */
@Composable
private fun BoxScope.MomentumFlash(signal: Int, mine: Boolean) {
    if (signal <= 0) return
    key(signal) {
        val a = remember { Animatable(0f) }
        LaunchedEffect(Unit) { a.animateTo(1f, tween(850, easing = LinearEasing)) }
        val t = a.value
        Text(
            if (mine) "MOMENTUM — YOU LEAD" else "MOMENTUM LOST — FIGHT BACK",
            color = PaperWhite,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    alpha = sin(t * PI.toFloat()).coerceIn(0f, 1f)
                    val sc = 1.1f + 0.15f * (1f - t)
                    scaleX = sc; scaleY = sc
                },
        )
    }
}

/**
 * LOBBY WAIT STAGE — both hunters must arm READY before the war goes LIVE.
 * Quick-tap signal/taunt presets live here (no typing); spectators use this
 * window to inspect profiles and join the prediction pool.
 */
private val LOBBY_PRESETS = listOf(
    "Please wait, setting up phone.",
    "Ready when you are, Hunter.",
    "You are cooked.",
    "I am cooked.",
)

@Composable
private fun LobbyCard(
    s: BattleRoomState,
    vm: BattleRoomViewModel,
    haptics: SystemHaptics,
    onExit: () -> Unit,
    gateRequired: Boolean = false,
    gateReady: Boolean = false,
) {
    val b = s.battle
    val opponentReady = s.opponentReadyOf(b, s.myId)
    val amHost = b?.host == null || b.host == s.myId // older rows: player A hosts
    GlowCard {
        Text("LOBBY — ARM READY", style = MaterialTheme.typography.labelLarge, color = PaperWhite)
        Text(
            "Both hunters must be READY. The duel runs ${s.durationSec}s. Spectators are inspecting and joining the prediction pool.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (s.myReady) "YOU — READY" else "YOU — WAITING",
                    color = if (s.myReady) PaperWhite else LabelGray,
                    style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (opponentReady) "RIVAL — READY" else "RIVAL — WAITING",
                    color = if (opponentReady) PaperWhite else LabelGray,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(10.dp))

        // taunt/signal feed
        if (s.taunts.isNotEmpty()) {
            s.taunts.takeLast(3).forEach { line ->
                Text(
                    (if (line.fromMe) "YOU: " else "RIVAL: ") + line.text,
                    color = if (line.fromMe) PaperWhite else LabelGray,
                    style = MonoLabel,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        // one-tap presets, 2×2
        LOBBY_PRESETS.chunked(2).forEach { pair ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { msg ->
                    GhostButton(msg, { haptics.tick(); vm.sendTaunt(msg) }, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))

        NeonButton(
            when {
                s.myReady -> "STAND DOWN"
                !s.cameraGranted -> "GRANT CAMERA TO READY"
                gateRequired && !gateReady -> "PASS THE GATE TO READY"
                else -> "READY"
            },
            { haptics.select(); vm.toggleReady() },
            Modifier.fillMaxWidth().height(52.dp),
            enabled = s.myReady || (s.cameraGranted && (!gateRequired || gateReady)),
        )
        if (gateRequired && !gateReady && !s.myReady) {
            Spacer(Modifier.height(6.dp))
            Text(
                "RANKED LAW: no side-view GREEN, no war. The gate above decides when you fight.",
                style = MaterialTheme.typography.bodySmall, color = LabelGray,
            )
        }
        Spacer(Modifier.height(8.dp))
        GhostButton(if (amHost) "CANCEL — RIVAL NEVER SHOWED" else "LEAVE LOBBY", {
            haptics.select()
            if (amHost) vm.cancelLobby(onExit) else onExit()
        }, Modifier.fillMaxWidth())
    }
}

/**
 * Tug-of-war: marker SPRINGS toward the dominant hunter, pulses with a halo,
 * and the losing end dims as you approach their wall. Pure Canvas.
 */
@Composable
fun TugOfWarBar(tug: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(targetValue = tug, animationSpec = SystemMotion.springPop, label = "tug")
    val inf = rememberInfiniteTransition(label = "tugHalo")
    val halo by inf.animateFloat(0.8f, 1.3f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "tugHaloR")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // grayscale momentum: rival side darkens as they dominate, your side
        // brightens. The only allowed palette: black / gray / white.
        val leftHeat = ((-animated).coerceIn(0f, 1f))
        val rightHeat = animated.coerceIn(0f, 1f)
        drawRoundRect(
            Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = 0.10f + 0.28f * leftHeat),
                    Color.White.copy(alpha = 0.05f),
                    Color.White.copy(alpha = 0.12f + 0.40f * rightHeat),
                )
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f),
        )
        drawLine(Color.White.copy(alpha = 0.5f), Offset(w / 2, 0f), Offset(w / 2, h), 2f)
        val x = w / 2 + animated * (w / 2 - 30f)
        // pulsing halo + marker — always white
        drawCircle(Color.White.copy(alpha = 0.22f), radius = 22f * halo, center = Offset(x, h / 2))
        drawCircle(Color.White, radius = 15f, center = Offset(x, h / 2))
        drawCircle(Color.Black, radius = 8f, center = Offset(x, h / 2))
    }
}

// ══ DESIGN 2.5 MONARCH EDGE — HOLOGRAPHIC POSE MESH ═════════════════════════
// ML Kit skeleton drawn as an active scanning hologram: kinetic joint rings,
// a traveling scanline, and a rep-flash burst at the chest. Pure canvas,
// two infinite clocks, zero per-frame allocations.
private val SKELETON_LINKS = listOf(
    11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,      // arms
    15 to 19, 16 to 20,                                    // wrists→index
    11 to 23, 12 to 24, 23 to 24,                          // torso
    23 to 25, 25 to 27, 24 to 26, 26 to 28,                // legs
    27 to 29, 28 to 30, 29 to 31, 30 to 32,                // feet
)
private val TRACKED_JOINTS = listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

@Composable
fun PoseMeshOverlay(points: List<Pair<Float, Float>>, repFlash: Float, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "poseMesh")
    val clock by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "meshT")
    val scan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "meshScan")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun mapX(x: Float) = (1f - x) * w  // front camera mirrors the world
        fun mapY(y: Float) = y * h

        if (points.size <= 32) {
            // SEARCHING TARGET — corner reticle brackets, nothing else
            val l = 26f; val c = ElectricBlue.copy(alpha = 0.5f + 0.3f * scan)
            drawLine(c, Offset(12f, 12f), Offset(12f + l, 12f), 3f); drawLine(c, Offset(12f, 12f), Offset(12f, 12f + l), 3f)
            drawLine(c, Offset(w - 12f, 12f), Offset(w - 12f - l, 12f), 3f); drawLine(c, Offset(w - 12f, 12f), Offset(w - 12f, 12f + l), 3f)
            drawLine(c, Offset(12f, h - 12f), Offset(12f + l, h - 12f), 3f); drawLine(c, Offset(12f, h - 12f), Offset(12f, h - 12f - l), 3f)
            drawLine(c, Offset(w - 12f, h - 12f), Offset(w - 12f - l, h - 12f), 3f); drawLine(c, Offset(w - 12f, h - 12f), Offset(w - 12f, h - 12f - l), 3f)
            drawCircle(ElectricBlue, radius = 4f, center = Offset(w / 2f, h / 2f), alpha = 0.4f + 0.3f * scan, style = Stroke(2f))
            return@Canvas
        }

        // traveling scanline — the mesh is alive, not a static drawing
        val sy = h * scan
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, ElectricBlue.copy(alpha = 0.10f), Color.Transparent)),
            topLeft = Offset(0f, (sy - 24f).coerceAtLeast(0f)), size = androidx.compose.ui.geometry.Size(w, 48f),
        )
        drawLine(ElectricBlue.copy(alpha = 0.35f), Offset(0f, sy), Offset(w, sy), 1.5f)

        // bone links
        for ((a, b) in SKELETON_LINKS) {
            val pa = points[a]; val pb = points[b]
            drawLine(ElectricBlue, Offset(mapX(pa.first), mapY(pa.second)), Offset(mapX(pb.first), mapY(pb.second)), strokeWidth = 2.4f, alpha = 0.55f)
        }
        // kinetic joint rings — each joint breathes on its own phase
        for ((i, j) in TRACKED_JOINTS.withIndex()) {
            val p = points[j]
            val c = Offset(mapX(p.first), mapY(p.second))
            val phase = 0.5f + 0.5f * kotlin.math.sin(clock * 2f * Math.PI.toFloat() + i * 0.9f)
            drawCircle(ElectricBlue, radius = 9f + 4f * phase, center = c, alpha = 0.22f + 0.20f * phase, style = Stroke(1.6f))
            drawCircle(Color.White, radius = 2.4f, center = c, alpha = 0.9f)
        }
        // rep flash — burst from the chest centroid on every counted rep
        if (repFlash > 1.01f) {
            val chest = Offset((mapX(points[11].first) + mapX(points[12].first)) / 2f, (mapY(points[11].second) + mapY(points[12].second)) / 2f)
            val f = repFlash - 1f
            drawCircle(ElectricBlue, radius = 40f + 220f * f, center = chest, alpha = (1f - f / 0.35f).coerceIn(0f, 1f) * 0.55f, style = Stroke(4f))
        }
    }
}
