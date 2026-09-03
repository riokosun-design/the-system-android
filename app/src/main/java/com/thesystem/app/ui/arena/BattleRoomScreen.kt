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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
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

    // DESIGN 2.5 — hologram mesh: ML Kit landmarks flow into the overlay canvas
    var meshPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    val counter = remember { PosePushUpCounter(onRep = vm::onRep, onLandmarks = { meshPoints = it }) }
    DisposableEffect(Unit) { onDispose { counter.close() } }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.onCameraPermission(it) }
    LaunchedEffect(Unit) { cameraPermission.launch(Manifest.permission.CAMERA) }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }
    LaunchedEffect(s.cameraGranted, s.counting) {
        val view = previewView ?: return@LaunchedEffect
        if (!s.cameraGranted) return@LaunchedEffect
        val provider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        provider.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        if (s.counting) {
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> counter.process(proxy) } }
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        } else {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
        }
    }

    SystemBackground(wallpaperAlpha = 0.08f) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            Text("PUSH-UP WAR", style = MaterialTheme.typography.headlineMedium, color = CrimsonRed)
            Text("60 seconds. Winner +${SystemMath.BATTLE_WIN_XP} XP · Loser +${SystemMath.BATTLE_LOSS_XP} XP. No mercy.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))

            if (!s.cameraGranted) {
                GlowCard(glow = CrimsonRed) {
                    Text("CAMERA REQUIRED — ML Kit counts your reps on-device.", color = CrimsonRed, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    NeonButton("GRANT CAMERA", { cameraPermission.launch(Manifest.permission.CAMERA) }, color = CrimsonRed)
                }
            } else {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    AndroidView(factory = { ctx -> PreviewView(ctx).also { previewView = it } }, modifier = Modifier.fillMaxSize())
                    if (s.counting) PoseMeshOverlay(points = meshPoints, repFlash = repPop.value, modifier = Modifier.matchParentSize())
                    if (!s.counting && s.battle?.status != "FINISHED") {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PHONE ON THE FLOOR — FRONT CAMERA FACING YOU", color = ElectricBlue, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (s.counting) {
                        Text(
                            "${s.secondsLeft}s",
                            color = if (s.secondsLeft <= 10) CrimsonRed else ElectricBlue,
                            fontSize = 42.sp, fontWeight = FontWeight.Black,
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
                                color = ElectricBlue, fontSize = 36.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.graphicsLayer { scaleX = repPop.value; scaleY = repPop.value },
                            )
                            Text("REPS", color = TextMuted, fontSize = 8.sp, letterSpacing = 3.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TugOfWarBar(s.tug, Modifier.fillMaxWidth().height(54.dp))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f).graphicsLayer { scaleX = repPop.value; scaleY = repPop.value }) {
                        StatTile("YOU", "${s.myCount} reps", ElectricBlue, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(10.dp))
                    StatTile("RIVAL", "${s.opponentCount} reps", CrimsonRed, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.weight(1f))

            when {
                s.finished -> GlowCard(glow = if (s.iWon == true) HunterGold else CrimsonRed, pulse = s.iWon == true) {
                    Text(
                        if (s.iWon == true) "VICTORY — +${SystemMath.BATTLE_WIN_XP} XP" else "DEFEAT — +${SystemMath.BATTLE_LOSS_XP} XP. Train harder.",
                        color = if (s.iWon == true) HunterGold else CrimsonRed,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("Final: ${s.battle?.scoreA} — ${s.battle?.scoreB}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    NeonButton("LEAVE THE ARENA", onExit, Modifier.fillMaxWidth())
                }
                s.battle?.status == "LOBBY" -> NeonButton(
                    "ENTER THE ARENA (BOTH HUNTERS GO LIVE)",
                    { haptics.select(); vm.goLive(); vm.startTimer() },
                    Modifier.fillMaxWidth().height(52.dp),
                    color = CrimsonRed,
                    enabled = s.cameraGranted,
                )
                !s.counting && s.battle?.status == "LIVE" -> NeonButton(
                    "START COUNTDOWN", { vm.startTimer() }, Modifier.fillMaxWidth().height(52.dp)
                )
            }
            s.error?.let { Spacer(Modifier.height(8.dp)); RestrictionBanner(it) }
        }

        // ── celebration overlays (State-driven, zero composition cost when idle) ═
        XpBurst(winBurst, color = HunterGold, accent = ElectricBlue)
        LevelUpShockwave(loseWave, color = CrimsonRed)
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
            if (mine) "⚡ MOMENTUM — YOU LEAD" else "⚠ MOMENTUM LOST — FIGHT BACK",
            color = if (mine) ElectricBlue else CrimsonRed,
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
        // end-zone danger glow — the closer the marker gets to a wall, the hotter that side burns
        val leftHeat = ((-animated).coerceIn(0f, 1f))
        val rightHeat = animated.coerceIn(0f, 1f)
        drawRoundRect(
            Brush.horizontalGradient(
                listOf(
                    CrimsonRed.copy(alpha = 0.35f + 0.4f * leftHeat),
                    Color(0x22FFFFFF),
                    ElectricBlue.copy(alpha = 0.35f + 0.4f * rightHeat),
                )
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f),
        )
        drawLine(Color.White.copy(alpha = 0.6f), Offset(w / 2, 0f), Offset(w / 2, h), 3f)
        val x = w / 2 + animated * (w / 2 - 30f)
        val markerColor = if (animated >= 0) ElectricBlue else CrimsonRed
        // pulsing halo + marker
        drawCircle(markerColor.copy(alpha = 0.30f), radius = 22f * halo, center = Offset(x, h / 2))
        drawCircle(Color.White, radius = 16f, center = Offset(x, h / 2))
        drawCircle(markerColor, radius = 9f, center = Offset(x, h / 2))
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
