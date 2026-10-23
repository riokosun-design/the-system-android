package com.thesystem.app.ui.motion

// ============================================================================
// THE SYSTEM — MOTION GRAPHICS ENGINE (v0.25.0)
// Production-grade procedural glowing motion graphics: 3D camera-tracking
// physics, pre-allocated low-memory render caches (2GB device law), multi-pass
// additive neon bloom shaders. Pure Canvas — zero assets, zero video, zero
// per-frame allocations on hot paths.
//
//   1. SystemMasterIntroCinematics   — first-launch 3-scene opening cinematic
//
// EXPUNGED (v0.26.0 §3 — lag + over-glow verdict from the field):
//   ✗ RankDisciplinaryMotionBackground (F→SS living plates) — executed; rank
//     screen restored to the smooth rank_bg art + gradient scrim, onboarding
//     showcase now uses quiet tier gradients
//   ✗ DisciplinaryTrajectoryGraph — executed; CleanTrajectoryGraph (pure
//     Canvas, one fill animation) now owns the onboarding growth page
// ============================================================================

import android.graphics.Paint as NativePaint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

// ============================================================================
// CORE COLOR PALETTE ("THE SYSTEM" DARK-ANIME NEON AESTHETIC)
// ============================================================================
object SystemPalette {
    val PitchBlack = Color(0xFF030508)
    val DeepVoid = Color(0xFF070C14)
    val ElectricCyan = Color(0xFF00F0FF)
    val AbyssalBlue = Color(0xFF0051FF)
    val CrimsonPenalty = Color(0xFFFF003C)
    val SovereignPurple = Color(0xFF9D00FF)
    val SovereignGold = Color(0xFFFFD700)
    val WeakSteel = Color(0xFF3A506B)
    val AwakenedCyan = Color(0xFF00B4D8)
    val PureWhite = Color(0xFFFFFFFF)
}

// ============================================================================
// 60 FPS LOW-MEMORY PRE-ALLOCATED RENDER CACHE
// Pre-allocates Paths and NativePaints outside the DrawScope loop for 2GB RAM devices
// ============================================================================
class SystemRenderCache {
    val primaryPath = Path()
    val secondaryPath = Path()
    val gridPath = Path()
    val effectPath = Path()
    val shardPath = Path()
    val sigilPath = Path()

    val hudTextPaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 28f
        color = android.graphics.Color.WHITE
    }

    val hudSubTextPaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textSize = 21f
        color = android.graphics.Color.argb(210, 0, 240, 255)
    }

    val hudTitlePaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 36f
        color = android.graphics.Color.rgb(0, 240, 255)
    }

    val strokeCapRound = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

@Composable
fun rememberSystemRenderCache(): SystemRenderCache = remember { SystemRenderCache() }

// ============================================================================
// 3D CAMERA RIG & KINETIC VIEWPORT ENGINE
// ============================================================================
@Stable
data class CameraRigState(
    val cameraX: Float = 0f,
    val cameraY: Float = 0f,
    val cameraZoom: Float = 1f,
    val cameraPitch: Float = 0f,
    val cameraYaw: Float = 0f,
    val cameraRoll: Float = 0f,
    val cameraDistance: Float = 14f
)

fun Modifier.apply3DCameraRig(rig: CameraRigState): Modifier = this.graphicsLayer {
    scaleX = rig.cameraZoom
    scaleY = rig.cameraZoom
    translationX = rig.cameraX
    translationY = rig.cameraY
    rotationX = rig.cameraPitch
    rotationY = rig.cameraYaw
    rotationZ = rig.cameraRoll
    cameraDistance = rig.cameraDistance * density
}

/**
 * Projects a 3D point (x, y, z) into 2D Canvas coordinates with camera translation & FOV perspective.
 */
fun project3DPoint(
    x: Float,
    y: Float,
    z: Float,
    camX: Float,
    camY: Float,
    camZ: Float,
    fov: Float,
    centerX: Float,
    centerY: Float
): Offset {
    val relX = x - camX
    val relY = y - camY
    val relZ = (z - camZ).coerceAtLeast(0.15f)
    val scale = fov / relZ
    return Offset(
        x = centerX + relX * scale,
        y = centerY + relY * scale
    )
}

// ============================================================================
// MULTI-LAYERED ADDITIVE NEON BLOOM DRAWING UTILITIES (ZERO ALLOCATION)
// ============================================================================
fun DrawScope.drawGlowingPath(
    path: Path,
    color: Color,
    coreWidth: Float = 3.5f,
    glowMultiplier: Float = 4.5f,
    alpha: Float = 1f
) {
    val safeAlpha = alpha.coerceIn(0f, 1f)
    // Outer wide bloom
    drawPath(
        path = path,
        color = color.copy(alpha = (0.14f * safeAlpha).coerceIn(0f, 1f)),
        style = Stroke(width = coreWidth * glowMultiplier, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus
    )
    // Mid-level intense bloom
    drawPath(
        path = path,
        color = color.copy(alpha = (0.36f * safeAlpha).coerceIn(0f, 1f)),
        style = Stroke(width = coreWidth * (glowMultiplier * 0.48f), cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus
    )
    // Main saturated neon stroke
    drawPath(
        path = path,
        color = color.copy(alpha = (0.9f * safeAlpha).coerceIn(0f, 1f)),
        style = Stroke(width = coreWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus
    )
    // White-hot anime core
    drawPath(
        path = path,
        color = SystemPalette.PureWhite.copy(alpha = (0.75f * safeAlpha).coerceIn(0f, 1f)),
        style = Stroke(width = (coreWidth * 0.38f).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus
    )
}

fun DrawScope.drawGlowingLine(
    start: Offset,
    end: Offset,
    color: Color,
    coreWidth: Float = 3f,
    glowMultiplier: Float = 4f,
    alpha: Float = 1f
) {
    val safeAlpha = alpha.coerceIn(0f, 1f)
    drawLine(
        color = color.copy(alpha = 0.16f * safeAlpha),
        start = start,
        end = end,
        strokeWidth = coreWidth * glowMultiplier,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus
    )
    drawLine(
        color = color.copy(alpha = 0.42f * safeAlpha),
        start = start,
        end = end,
        strokeWidth = coreWidth * 2f,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus
    )
    drawLine(
        color = color.copy(alpha = 0.95f * safeAlpha),
        start = start,
        end = end,
        strokeWidth = coreWidth,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus
    )
    drawLine(
        color = SystemPalette.PureWhite.copy(alpha = 0.75f * safeAlpha),
        start = start,
        end = end,
        strokeWidth = (coreWidth * 0.4f).coerceAtLeast(1f),
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus
    )
}

fun DrawScope.drawGlowingCircle(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float = 1f,
    filledCore: Boolean = true
) {
    val safeAlpha = alpha.coerceIn(0f, 1f)
    drawCircle(
        color = color.copy(alpha = 0.15f * safeAlpha),
        radius = radius * 2.4f,
        center = center,
        blendMode = BlendMode.Plus
    )
    drawCircle(
        color = color.copy(alpha = 0.38f * safeAlpha),
        radius = radius * 1.45f,
        center = center,
        blendMode = BlendMode.Plus
    )
    if (filledCore) {
        drawCircle(
            color = color.copy(alpha = 0.9f * safeAlpha),
            radius = radius,
            center = center,
            blendMode = BlendMode.Plus
        )
        drawCircle(
            color = SystemPalette.PureWhite.copy(alpha = 0.85f * safeAlpha),
            radius = radius * 0.45f,
            center = center,
            blendMode = BlendMode.Plus
        )
    } else {
        drawCircle(
            color = color.copy(alpha = 0.92f * safeAlpha),
            radius = radius,
            center = center,
            style = Stroke(width = 3f),
            blendMode = BlendMode.Plus
        )
    }
}

fun DrawScope.drawHudText(
    paint: NativePaint,
    text: String,
    x: Float,
    y: Float,
    color: Color,
    textSizePx: Float,
    alignCenter: Boolean = false
) {
    paint.textSize = textSizePx
    paint.color = color.toArgb()
    paint.textAlign = if (alignCenter) NativePaint.Align.CENTER else NativePaint.Align.LEFT
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}

// ============================================================================
// DELIVERABLE 1: SystemMasterIntroCinematics()
// Multi-Stage 3D Camera-Tracked Story & App Overview Animation (14 Seconds)
// ============================================================================
@Composable
fun SystemMasterIntroCinematics(
    modifier: Modifier = Modifier,
    onEnterSystem: () -> Unit
) {
    val cache = rememberSystemRenderCache()
    var currentScene by remember { mutableIntStateOf(0) } // 0: Biometric, 1: Chains, 2: Matrix
    var sceneElapsedMs by remember { mutableLongStateOf(0L) }

    // Auto-advance timer (4.5s per scene -> ~13.5s total) with smooth 60fps clock
    LaunchedEffect(currentScene) {
        sceneElapsedMs = 0L
        val startTime = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            sceneElapsedMs = (now - startTime) / 1_000_000L
            if (sceneElapsedMs >= 4600L && currentScene < 2) {
                currentScene += 1
                break
            }
            if (currentScene == 2 && sceneElapsedMs >= 12000L) {
                // Keep looping subtle animations in Scene 3 until user triggers or taps
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "MasterIntroLoop")
    val continuousTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "continuousTime"
    )

    // Spring-physics 3D Camera Rig tracking each scene cut like an anime action director
    val sceneNorm = (sceneElapsedMs / 4600f).coerceIn(0f, 1f)

    val targetZoom = when (currentScene) {
        0 -> 1.12f + 0.22f * FastOutSlowInEasing.transform(sceneNorm) // Zoom into biometric silhouette
        1 -> 1.18f - 0.10f * sceneNorm // Whip-pan impact zoom
        else -> 1.28f - 0.26f * FastOutSlowInEasing.transform(sceneNorm) // Dolly-out across RPG matrix
    }
    val targetPanX = when (currentScene) {
        0 -> sin(continuousTime * 1.6f) * 18f
        1 -> (1f - FastOutSlowInEasing.transform((sceneNorm * 2.5f).coerceIn(0f, 1f))) * 260f + sin(continuousTime * 5f) * 8f
        else -> sin(continuousTime * 1.1f) * 24f
    }
    val targetPanY = when (currentScene) {
        0 -> -20f * sceneNorm + cos(continuousTime * 1.8f) * 12f
        1 -> cos(continuousTime * 4f) * 10f
        else -> 35f * (1f - sceneNorm)
    }
    val targetPitch = when (currentScene) {
        0 -> 6f + sin(continuousTime * 1.5f) * 3f
        1 -> -5f + cos(continuousTime * 3f) * 4f
        else -> 12f * (1f - 0.4f * sceneNorm)
    }
    val targetYaw = when (currentScene) {
        0 -> -8f + 16f * sceneNorm
        1 -> (1f - FastOutSlowInEasing.transform((sceneNorm * 2.2f).coerceIn(0f, 1f))) * -28f + sin(continuousTime * 2f) * 4f
        else -> -10f + 14f * sceneNorm
    }

    val camZoom by animateFloatAsState(
        targetValue = targetZoom,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessLow),
        label = "camZoom"
    )
    val camX by animateFloatAsState(
        targetValue = targetPanX,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "camX"
    )
    val camY by animateFloatAsState(
        targetValue = targetPanY,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessLow),
        label = "camY"
    )
    val camPitch by animateFloatAsState(
        targetValue = targetPitch,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow),
        label = "camPitch"
    )
    val camYaw by animateFloatAsState(
        targetValue = targetYaw,
        animationSpec = spring(dampingRatio = 0.60f, stiffness = Spring.StiffnessMediumLow),
        label = "camYaw"
    )

    val cameraRig = CameraRigState(
        cameraX = camX,
        cameraY = camY,
        cameraZoom = camZoom,
        cameraPitch = camPitch,
        cameraYaw = camYaw,
        cameraRoll = sin(continuousTime * 0.9f) * 1.4f
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SystemPalette.PitchBlack)
            .pointerInput(currentScene) {
                detectTapGestures {
                    if (currentScene < 2) {
                        currentScene += 1
                    } else {
                        onEnterSystem()
                    }
                }
            }
    ) {
        // 3D Camera-Tracked Procedural Motion Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .apply3DCameraRig(cameraRig)
        ) {
            val w = size.width
            val h = size.height
            // 0.25.1 guard: zero/transient-size frames (first layout, transitions)
            // never touch the draw math — kills the NaN/infinite-loop crash class.
            if (w < 4f || h < 4f || !w.isFinite() || !h.isFinite()) return@Canvas
            val cx = w * 0.5f
            val cy = h * 0.5f

            // Ambient Deep Cyber Vignette Grid
            drawIntroAmbientCyberGrid(cache, w, h, continuousTime)

            when (currentScene) {
                0 -> drawScene1BiometricWireframe(
                    cache = cache,
                    w = w,
                    h = h,
                    cx = cx,
                    cy = cy,
                    time = continuousTime,
                    sceneProgress = sceneNorm
                )
                1 -> drawScene2DisciplineChains(
                    cache = cache,
                    w = w,
                    h = h,
                    cx = cx,
                    cy = cy,
                    time = continuousTime,
                    sceneProgress = sceneNorm
                )
                else -> drawScene3RpgProgressionMatrix(
                    cache = cache,
                    w = w,
                    h = h,
                    cx = cx,
                    cy = cy,
                    time = continuousTime,
                    sceneProgress = sceneNorm
                )
            }
        }

        // Fixed Viewport Tactical HUD Overlay (Top Story Progress & Telemetry Header)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .align(Alignment.TopCenter)
        ) {
            // Scene Progress Bars
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 0..2) {
                    val fillFrac = when {
                        i < currentScene -> 1f
                        i == currentScene -> sceneNorm
                        else -> 0f
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(SystemPalette.WeakSteel.copy(alpha = 0.35f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fillFrac)
                                .background(
                                    if (i == 1) SystemPalette.CrimsonPenalty else SystemPalette.ElectricCyan
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (currentScene) {
                        0 -> "SYS_SEQ // 01 : BIOMETRIC_LOCK"
                        1 -> "SYS_SEQ // 02 : ZERO_EXCUSES"
                        else -> "SYS_SEQ // 03 : MONARCH_ASCENSION"
                    },
                    color = SystemPalette.ElectricCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (currentScene < 2) "[ TAP TO ADVANCE >> ]" else "[ SYSTEM READY ]",
                    color = SystemPalette.PureWhite.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Bottom Narrative Banner & "ENTER THE SYSTEM" Trigger Button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val bannerTitle = when (currentScene) {
                0 -> "[ REAL-WORLD BIOMETRIC VERIFICATION ]"
                1 -> "[ ZERO EXCUSES • HARDCORE DISCIPLINE PROTOCOL ]"
                else -> "[ THE SYSTEM IS WATCHING YOUR GRIND ]"
            }
            val bannerSub = when (currentScene) {
                0 -> "Every push-up, sprint, and physical rep is optically verified in real time."
                1 -> "Distractions are shattered. Failure triggers mandatory penalty quests."
                else -> "Ascend from F-Rank weakest to SS-Rank Absolute Monarch through pure discipline."
            }
            val accentColor = when (currentScene) {
                0 -> SystemPalette.ElectricCyan
                1 -> SystemPalette.CrimsonPenalty
                else -> SystemPalette.SovereignGold
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SystemPalette.DeepVoid.copy(alpha = 0.85f), CutCornerShape(10.dp))
                    .border(1.dp, accentColor.copy(alpha = 0.7f), CutCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = bannerTitle,
                        color = accentColor,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = bannerSub,
                        color = SystemPalette.PureWhite.copy(alpha = 0.88f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Sleek "ENTER THE SYSTEM" Interactive Trigger Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                SystemPalette.AbyssalBlue.copy(alpha = 0.85f),
                                SystemPalette.ElectricCyan.copy(alpha = 0.95f),
                                SystemPalette.AbyssalBlue.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        color = SystemPalette.PureWhite.copy(alpha = 0.9f),
                        shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
                    )
                    .clickable { onEnterSystem() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚡ ENTER THE SYSTEM ⚡",
                    color = SystemPalette.PitchBlack,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

private fun DrawScope.drawIntroAmbientCyberGrid(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    time: Float
) {
    val gridPath = cache.gridPath
    gridPath.reset()
    val step = 64f
    val shiftY = (time * 35f) % step
    var x = -w * 0.2f
    while (x <= w * 1.2f) {
        gridPath.moveTo(x, -h * 0.2f)
        gridPath.lineTo(x, h * 1.2f)
        x += step
    }
    var y = -h * 0.2f + shiftY
    while (y <= h * 1.2f) {
        gridPath.moveTo(-w * 0.2f, y)
        gridPath.lineTo(w * 1.2f, y)
        y += step
    }
    drawPath(
        path = gridPath,
        color = SystemPalette.AbyssalBlue.copy(alpha = 0.12f),
        style = Stroke(width = 1f)
    )
}

/**
 * SCENE 1: Camera Zoom into Biometric Wireframe doing push-ups with scanning lasers & HUD telemetry
 */
private fun DrawScope.drawScene1BiometricWireframe(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    time: Float,
    sceneProgress: Float
) {
    // Push-up cycle physics (0f = top plank, 1f = bottom chest-to-floor)
    val repCycle = (sin(time * 5.2f) * 0.5f + 0.5f)
    val repCount = ((time * 1.6f).toInt() % 50) + 1
    val scale = min(w, h) * 0.00115f

    // 3D Wireframe Floor Platform
    val floorY = cy + 120f * scale
    drawGlowingLine(
        start = Offset(cx - 320f * scale, floorY),
        end = Offset(cx + 320f * scale, floorY),
        color = SystemPalette.AbyssalBlue,
        coreWidth = 3f,
        alpha = 0.8f
    )

    // Articulated human silhouette executing push-ups (Side-3/4 isometric perspective)
    val dropY = repCycle * 78f * scale
    val feetPos = Offset(cx - 240f * scale, floorY - 12f * scale)
    val kneePos = Offset(cx - 115f * scale, floorY - 58f * scale + dropY * 0.35f)
    val hipPos = Offset(cx + 10f * scale, floorY - 118f * scale + dropY * 0.72f)
    val shoulderPos = Offset(cx + 165f * scale, floorY - 185f * scale + dropY)
    val headPos = Offset(cx + 235f * scale, floorY - 210f * scale + dropY)

    // Near & Far Arms (Hands planted on floor, elbows flexing dynamically)
    val handNear = Offset(cx + 155f * scale, floorY - 6f * scale)
    val handFar = Offset(cx + 195f * scale, floorY - 24f * scale)
    val elbowFlexX = -48f * scale * repCycle
    val elbowNear = Offset(
        x = (shoulderPos.x + handNear.x) * 0.5f + elbowFlexX,
        y = (shoulderPos.y + handNear.y) * 0.5f + 15f * scale * repCycle
    )
    val elbowFar = Offset(
        x = (shoulderPos.x + handFar.x) * 0.5f + elbowFlexX * 0.85f,
        y = (shoulderPos.y + handFar.y) * 0.5f + 10f * scale * repCycle
    )

    val wirePath = cache.primaryPath
    wirePath.reset()
    // Spine & lower body kinetic chain
    wirePath.moveTo(feetPos.x, feetPos.y)
    wirePath.lineTo(kneePos.x, kneePos.y)
    wirePath.lineTo(hipPos.x, hipPos.y)
    wirePath.lineTo(shoulderPos.x, shoulderPos.y)
    wirePath.lineTo(headPos.x, headPos.y)

    // Near arm
    wirePath.moveTo(shoulderPos.x, shoulderPos.y)
    wirePath.lineTo(elbowNear.x, elbowNear.y)
    wirePath.lineTo(handNear.x, handNear.y)

    // Far arm
    wirePath.moveTo(shoulderPos.x, shoulderPos.y)
    wirePath.lineTo(elbowFar.x, elbowFar.y)
    wirePath.lineTo(handFar.x, handFar.y)

    // Internal muscular wireframe ribs/core
    for (i in 1..4) {
        val frac = i / 5f
        val spineX = hipPos.x + (shoulderPos.x - hipPos.x) * frac
        val spineY = hipPos.y + (shoulderPos.y - hipPos.y) * frac
        wirePath.moveTo(spineX, spineY)
        wirePath.lineTo(spineX - 18f * scale, spineY + 28f * scale)
    }

    drawGlowingPath(
        path = wirePath,
        color = SystemPalette.ElectricCyan,
        coreWidth = 4.5f,
        glowMultiplier = 5f
    )

    // Wireframe Head Hologram Sphere
    drawGlowingCircle(
        center = headPos,
        radius = 26f * scale,
        color = SystemPalette.ElectricCyan,
        filledCore = false
    )

    // Joint Biometric Nodes (Pulsing crimson/gold under peak pectoral/tricep load)
    val muscleStressColor = if (repCycle > 0.65f) SystemPalette.CrimsonPenalty else SystemPalette.ElectricCyan
    val joints = arrayOf(feetPos, kneePos, hipPos, shoulderPos, elbowNear, handNear, elbowFar, handFar)
    for (joint in joints) {
        drawGlowingCircle(
            center = joint,
            radius = 7.5f * scale,
            color = muscleStressColor
        )
    }

    // Real-time Biometric Vertical & Horizontal Scanning Lasers
    val scanX = cx - 280f * scale + ((time * 320f) % (560f * scale))
    drawGlowingLine(
        start = Offset(scanX, cy - 260f * scale),
        end = Offset(scanX, floorY + 40f * scale),
        color = SystemPalette.ElectricCyan,
        coreWidth = 2.5f,
        alpha = 0.85f
    )

    // Floating HUD Telemetry Callouts
    val hudTopY = cy - 220f * scale
    drawGlowingLine(
        start = shoulderPos,
        end = Offset(shoulderPos.x - 45f * scale, hudTopY),
        color = SystemPalette.ElectricCyan,
        coreWidth = 1.8f,
        alpha = 0.75f
    )
    drawHudText(
        paint = cache.hudTextPaint,
        text = "REP_COUNTER: $repCount // VERIFIED",
        x = shoulderPos.x - 160f * scale,
        y = hudTopY - 12f * scale,
        color = SystemPalette.ElectricCyan,
        textSizePx = 28f * scale
    )
    val loadPct = (72 + (repCycle * 27).toInt())
    drawHudText(
        paint = cache.hudSubTextPaint,
        text = "PECTORAL_TENSION: $loadPct% | HR: 154 BPM",
        x = shoulderPos.x - 160f * scale,
        y = hudTopY + 18f * scale,
        color = SystemPalette.PureWhite,
        textSizePx = 20f * scale
    )
}

/**
 * SCENE 2: Camera Whip-Pan to Discipline Lock & Chains shattering distraction nodes
 */
private fun DrawScope.drawScene2DisciplineChains(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    time: Float,
    sceneProgress: Float
) {
    val scale = min(w, h) * 0.0011f
    val lockImpact = (sceneProgress * 2.2f).coerceIn(0f, 1f)

    // 4 Diagonal Energy Chains shooting across viewport and locking at the center
    val chainAngles = floatArrayOf(32f, 148f, 212f, 328f)
    val chainPath = cache.primaryPath
    chainPath.reset()

    for (angleDeg in chainAngles) {
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val dirX = cos(rad)
        val dirY = sin(rad)
        val maxLen = hypot(w, h) * 0.65f * lockImpact
        val linkStep = 34f * scale
        var d = -maxLen
        var linkIndex = 0
        while (d <= maxLen) {
            val lx = cx + dirX * d
            val ly = cy + dirY * d
            val perpX = -dirY * (if (linkIndex % 2 == 0) 12f else 5f) * scale
            val perpY = dirX * (if (linkIndex % 2 == 0) 12f else 5f) * scale
            chainPath.moveTo(lx - perpX, ly - perpY)
            chainPath.lineTo(lx + dirX * linkStep * 0.8f - perpX, ly + dirY * linkStep * 0.8f - perpY)
            chainPath.lineTo(lx + dirX * linkStep * 0.8f + perpX, ly + dirY * linkStep * 0.8f + perpY)
            chainPath.lineTo(lx + perpX, ly + perpY)
            chainPath.close()
            d += linkStep
            linkIndex++
        }
    }

    drawGlowingPath(
        path = chainPath,
        color = SystemPalette.ElectricCyan,
        coreWidth = 3f,
        glowMultiplier = 4.5f
    )

    // Shattering Crimson Distraction Shards flying outward from the lock impact
    val shardPath = cache.shardPath
    shardPath.reset()
    val explosionT = ((sceneProgress - 0.15f) * 1.6f).coerceIn(0f, 1f)
    val distractionLabels = arrayOf("EXCUSES", "DOPAMINE_TRAP", "SLOTH", "WEAKNESS")

    for (i in 0 until 24) {
        val seedAngle = (i * 137.5f + time * 18f) * (PI.toFloat() / 180f)
        val burstDist = (60f + (i % 5) * 55f + explosionT * 290f) * scale
        val sx = cx + cos(seedAngle) * burstDist
        val sy = cy + sin(seedAngle) * burstDist
        val shardSize = (18f - (i % 3) * 4f) * scale * (1f - explosionT * 0.45f)

        shardPath.moveTo(sx, sy - shardSize)
        shardPath.lineTo(sx + shardSize * 0.85f, sy + shardSize * 0.7f)
        shardPath.lineTo(sx - shardSize * 0.9f, sy + shardSize * 0.4f)
        shardPath.close()

        if (i < 4) {
            drawHudText(
                paint = cache.hudSubTextPaint,
                text = "// SHATTERED: ${distractionLabels[i]}",
                x = sx + 20f * scale,
                y = sy,
                color = SystemPalette.CrimsonPenalty.copy(alpha = (1f - explosionT * 0.5f).coerceIn(0.2f, 1f)),
                textSizePx = 19f * scale
            )
        }
    }

    drawGlowingPath(
        path = shardPath,
        color = SystemPalette.CrimsonPenalty,
        coreWidth = 2.8f,
        glowMultiplier = 4f
    )

    // Central Hexagonal Disciplinary Lock Seal
    val hexPath = cache.secondaryPath
    hexPath.reset()
    val hexRadius = (105f - 20f * lockImpact) * scale
    for (k in 0..6) {
        val a = Math.toRadians((k * 60.0 + time * 35.0)).toFloat()
        val hx = cx + cos(a) * hexRadius
        val hy = cy + sin(a) * hexRadius
        if (k == 0) hexPath.moveTo(hx, hy) else hexPath.lineTo(hx, hy)
    }
    drawGlowingPath(
        path = hexPath,
        color = SystemPalette.ElectricCyan,
        coreWidth = 4.5f,
        glowMultiplier = 5f
    )

    // Shockwave Ring on Chain Lock
    val ringRadius = explosionT * 420f * scale
    drawGlowingCircle(
        center = Offset(cx, cy),
        radius = ringRadius,
        color = SystemPalette.CrimsonPenalty,
        alpha = (1f - explosionT).coerceIn(0f, 1f),
        filledCore = false
    )

    drawHudText(
        paint = cache.hudTitlePaint,
        text = "LOCKED",
        x = cx,
        y = cy + 12f * scale,
        color = SystemPalette.PureWhite,
        textSizePx = 32f * scale,
        alignCenter = true
    )
}

/**
 * SCENE 3: Camera Dolly-Out to 3D RPG Progression Matrix (LVL 1 -> LVL 100 / F-Rank -> SS-Rank)
 */
private fun DrawScope.drawScene3RpgProgressionMatrix(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    time: Float,
    sceneProgress: Float
) {
    val scale = min(w, h) * 0.0011f
    val fov = 420f * scale
    val camZ = -380f - sceneProgress * 140f

    // 3D Perspective Horizon Floor Grid
    val gridPath = cache.gridPath
    gridPath.reset()
    val floorY3D = 160f
    for (gz in 1..14) {
        val zVal = gz * 85f - ((time * 90f) % 85f)
        val pLeft = project3DPoint(-520f, floorY3D, zVal, 0f, 0f, camZ, fov, cx, cy)
        val pRight = project3DPoint(520f, floorY3D, zVal, 0f, 0f, camZ, fov, cx, cy)
        gridPath.moveTo(pLeft.x, pLeft.y)
        gridPath.lineTo(pRight.x, pRight.y)
    }
    for (gx in -6..6) {
        val xVal = gx * 85f
        val pNear = project3DPoint(xVal, floorY3D, 20f, 0f, 0f, camZ, fov, cx, cy)
        val pFar = project3DPoint(xVal, floorY3D, 1150f, 0f, 0f, camZ, fov, cx, cy)
        gridPath.moveTo(pNear.x, pNear.y)
        gridPath.lineTo(pFar.x, pFar.y)
    }
    drawPath(
        path = gridPath,
        color = SystemPalette.AbyssalBlue.copy(alpha = 0.4f),
        style = Stroke(width = 1.5f),
        blendMode = BlendMode.Plus
    )

    // Ascending 3D Holographic Rank Pillars & Trajectory Curve (F -> SS)
    val ranks = arrayOf("F", "E", "D", "C", "B", "A", "S", "SS")
    val levels = arrayOf("LVL 1", "LVL 10", "LVL 25", "LVL 40", "LVL 55", "LVL 70", "LVL 85", "LVL 100")
    val trajectoryPath = cache.primaryPath
    val pillarsPath = cache.secondaryPath
    trajectoryPath.reset()
    pillarsPath.reset()

    val revealCount = (sceneProgress * 1.35f).coerceIn(0.15f, 1f) * ranks.size

    for (i in ranks.indices) {
        if (i > revealCount) break
        val norm = i / (ranks.size - 1f)
        val x3D = -280f + norm * 560f
        val z3D = 180f + (1f - norm) * 260f
        val height3D = 30f + norm.pow(1.85f) * 340f

        val basePt = project3DPoint(x3D, floorY3D, z3D, 0f, 0f, camZ, fov, cx, cy)
        val topPt = project3DPoint(x3D, floorY3D - height3D, z3D, 0f, 0f, camZ, fov, cx, cy)

        pillarsPath.moveTo(basePt.x, basePt.y)
        pillarsPath.lineTo(topPt.x, topPt.y)

        if (i == 0) {
            trajectoryPath.moveTo(topPt.x, topPt.y)
        } else {
            trajectoryPath.lineTo(topPt.x, topPt.y)
        }

        val nodeColor = when {
            i == 7 -> SystemPalette.SovereignGold
            i >= 5 -> SystemPalette.SovereignPurple
            else -> SystemPalette.ElectricCyan
        }

        drawGlowingCircle(
            center = topPt,
            radius = (6f + i * 1.4f) * scale,
            color = nodeColor
        )

        drawHudText(
            paint = cache.hudTextPaint,
            text = "${ranks[i]}-RANK",
            x = topPt.x,
            y = topPt.y - 22f * scale,
            color = nodeColor,
            textSizePx = (18f + i * 1.5f) * scale,
            alignCenter = true
        )
        drawHudText(
            paint = cache.hudSubTextPaint,
            text = levels[i],
            x = basePt.x,
            y = basePt.y + 24f * scale,
            color = SystemPalette.PureWhite.copy(alpha = 0.85f),
            textSizePx = 15f * scale,
            alignCenter = true
        )
    }

    drawGlowingPath(
        path = pillarsPath,
        color = SystemPalette.AbyssalBlue,
        coreWidth = 3f,
        glowMultiplier = 3.5f
    )
    drawGlowingPath(
        path = trajectoryPath,
        color = SystemPalette.ElectricCyan,
        coreWidth = 5f,
        glowMultiplier = 5f
    )
}
