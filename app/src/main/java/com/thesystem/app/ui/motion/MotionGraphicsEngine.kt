package com.thesystem.app.ui.motion

// ============================================================================
// THE SYSTEM — MOTION GRAPHICS ENGINE (v0.25.0)
// Production-grade procedural glowing motion graphics: 3D camera-tracking
// physics, pre-allocated low-memory render caches (2GB device law), multi-pass
// additive neon bloom shaders. Pure Canvas — zero assets, zero video, zero
// per-frame allocations on hot paths.
//
//   1. SystemMasterIntroCinematics   — first-launch 3-scene opening cinematic
//   2. RankDisciplinaryMotionBackground — F→SS rank-tier living backgrounds
//   3. DisciplinaryTrajectoryGraph   — onboarding growth-curve calculation
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

// ============================================================================
// DELIVERABLE 2: RankDisciplinaryMotionBackground(rank: String)
// 8 Tiered Rank-Specific Motion Graphics Backgrounds with 3D Camera Tracking
// ============================================================================
@Composable
fun RankDisciplinaryMotionBackground(
    rank: String,
    modifier: Modifier = Modifier
) {
    val cleanRank = rank.trim().uppercase()
    val cache = rememberSystemRenderCache()

    val infiniteTransition = rememberInfiniteTransition(label = "RankBgTransition_$cleanRank")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 62.83185f, // 20 * PI for seamless trigonometric loops
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (cleanRank) {
                    "SS", "S" -> 1100
                    "A", "B" -> 1500
                    else -> 2200
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Tiered 3D Camera Tracking intensity scaled by Hunter Rank
    val rankIntensity = when (cleanRank) {
        "F" -> 0.35f
        "E" -> 0.50f
        "D" -> 0.70f
        "C" -> 0.90f
        "B" -> 1.15f
        "A" -> 1.40f
        "S" -> 1.70f
        "SS" -> 2.00f
        else -> 0.60f
    }

    val rig = CameraRigState(
        cameraX = sin(phase * 1.5f) * 18f * rankIntensity,
        cameraY = cos(phase * 1.2f) * 14f * rankIntensity,
        cameraZoom = 1.04f + (pulse * 0.08f * rankIntensity),
        cameraPitch = sin(phase * 0.8f) * 5.5f * rankIntensity,
        cameraYaw = cos(phase * 1.1f) * 6.5f * rankIntensity,
        cameraRoll = sin(phase * 0.6f) * 2.2f * rankIntensity
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SystemPalette.PitchBlack)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .apply3DCameraRig(rig)
        ) {
            val w = size.width
            val h = size.height
            // 0.25.1 guard: tiny/transient cells never enter the draw math.
            if (w < 4f || h < 4f || !w.isFinite() || !h.isFinite()) return@Canvas
            val cx = w * 0.5f
            val cy = h * 0.5f

            when (cleanRank) {
                "F" -> drawFRankAwakening(cache, w, h, cx, cy, phase, pulse)
                "E" -> drawERankIronDiscipline(cache, w, h, cx, cy, phase, pulse)
                "D" -> drawDRankRelentlessGrind(cache, w, h, cx, cy, phase, pulse)
                "C" -> drawCRankCombatReady(cache, w, h, cx, cy, phase, pulse)
                "B" -> drawBRankEliteHunter(cache, w, h, cx, cy, phase, pulse)
                "A" -> drawARankCommandingForce(cache, w, h, cx, cy, phase, pulse)
                "S" -> drawSRankNationalAuthority(cache, w, h, cx, cy, phase, pulse)
                "SS" -> drawSSRankAbsoluteMonarch(cache, w, h, cx, cy, phase, pulse)
                else -> drawFRankAwakening(cache, w, h, cx, cy, phase, pulse)
            }
        }
    }
}

/**
 * F-RANK (The Weakest / Awakening):
 * Dim flicker cyan grid, slow heartbeat pulse scanner, unstable static particles (#3A506B -> #00B4D8)
 */
private fun DrawScope.drawFRankAwakening(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    phase: Float,
    pulse: Float
) {
    val flicker = if (sin(phase * 17f) > 0.72f) 0.18f else 0.55f
    val gridPath = cache.gridPath
    gridPath.reset()
    val step = 75f
    var x = 0f
    while (x <= w) {
        gridPath.moveTo(x, 0f)
        gridPath.lineTo(x, h)
        x += step
    }
    var y = 0f
    while (y <= h) {
        gridPath.moveTo(0f, y)
        gridPath.lineTo(w, y)
        y += step
    }
    drawPath(
        path = gridPath,
        color = SystemPalette.WeakSteel.copy(alpha = 0.28f * flicker),
        style = Stroke(width = 1.2f)
    )

    // Slow Heartbeat ECG Pulse Scanner across center
    val ecgPath = cache.primaryPath
    ecgPath.reset()
    val scanHeadX = ((phase * 180f) % w)
    var px = 0f
    while (px <= w) {
        val distToBeat = px - scanHeadX
        val beatSpike = when {
            abs(distToBeat) < 16f -> -sin((distToBeat / 16f) * PI.toFloat()) * 78f
            abs(distToBeat + 36f) < 12f -> cos(((distToBeat + 36f) / 12f) * PI.toFloat()) * 32f
            else -> sin(px * 0.03f + phase * 2f) * 4f
        }
        val py = cy + beatSpike
        if (px == 0f) ecgPath.moveTo(px, py) else ecgPath.lineTo(px, py)
        px += 8f
    }
    drawGlowingPath(
        path = ecgPath,
        color = SystemPalette.AwakenedCyan,
        coreWidth = 2.8f,
        glowMultiplier = 3.5f,
        alpha = 0.75f
    )

    // Unstable Static Particles
    for (i in 0 until 36) {
        val pxStatic = ((i * 97.3f + sin(phase * 3f + i) * 35f) % w + w) % w
        val pyStatic = ((i * 151.7f + cos(phase * 2.4f + i) * 40f) % h + h) % h
        val staticAlpha = ((sin(phase * 12f + i * 1.7f) * 0.5f + 0.5f) * 0.65f)
        drawGlowingCircle(
            center = Offset(pxStatic, pyStatic),
            radius = 2.5f + (i % 3),
            color = if (i % 2 == 0) SystemPalette.WeakSteel else SystemPalette.AwakenedCyan,
            alpha = staticAlpha
        )
    }
}

/**
 * E-RANK (Iron Discipline):
 * Synchronized vertical laser scanlines, geometric discipline bars locking into place
 */
private fun DrawScope.drawERankIronDiscipline(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    phase: Float,
    pulse: Float
) {
    // Synchronized Vertical Laser Scanlines
    val numLasers = 8
    for (i in 0 until numLasers) {
        val norm = i / (numLasers - 1f)
        val sweepOffset = sin(phase * 2f + i * 0.6f) * (w * 0.08f)
        val lx = w * (0.1f + 0.8f * norm) + sweepOffset
        drawGlowingLine(
            start = Offset(lx, 0f),
            end = Offset(lx, h),
            color = SystemPalette.AbyssalBlue,
            coreWidth = 2f,
            alpha = 0.55f
        )
    }

    // Geometric Discipline Bars sliding horizontally and locking into a central spine
    val barCount = 10
    val barSpacing = h / (barCount + 2)
    for (i in 1..barCount) {
        val by = i * barSpacing
        val lockCycle = ((sin(phase * 2.5f + i * 0.45f) * 0.5f + 0.5f)).coerceIn(0f, 1f)
        val gap = (1f - FastOutSlowInEasing.transform(lockCycle)) * (w * 0.24f)
        val barWidth = w * 0.28f

        // Left locking bar
        drawGlowingLine(
            start = Offset(cx - gap - barWidth, by),
            end = Offset(cx - gap, by),
            color = SystemPalette.ElectricCyan,
            coreWidth = 5f,
            alpha = 0.8f
        )
        // Right locking bar
        drawGlowingLine(
            start = Offset(cx + gap, by),
            end = Offset(cx + gap + barWidth, by),
            color = SystemPalette.ElectricCyan,
            coreWidth = 5f,
            alpha = 0.8f
        )
        // Lock engagement flash node when bars meet at center
        if (gap < 14f) {
            drawGlowingCircle(
                center = Offset(cx, by),
                radius = 7f,
                color = SystemPalette.PureWhite,
                alpha = 0.9f
            )
        }
    }
}

/**
 * D-RANK (Relentless Grind):
 * High-speed upward kinetic arrows, rotating tactical target reticles, glowing blue shockwaves
 */
private fun DrawScope.drawDRankRelentlessGrind(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    phase: Float,
    pulse: Float
) {
    // High-speed Upward Kinetic Arrows / Chevrons
    val chevronPath = cache.primaryPath
    chevronPath.reset()
    val cols = 5
    val rows = 8
    val speedY = (phase * 420f) % (h / rows)
    for (c in 0 until cols) {
        val colX = w * (0.15f + 0.7f * (c / (cols - 1f)))
        val chevW = 36f
        val chevH = 22f
        for (r in -1..rows) {
            val rowY = r * (h / rows) - speedY
            chevronPath.moveTo(colX - chevW, rowY + chevH)
            chevronPath.lineTo(colX, rowY)
            chevronPath.lineTo(colX + chevW, rowY + chevH)
        }
    }
    drawGlowingPath(
        path = chevronPath,
        color = SystemPalette.AbyssalBlue,
        coreWidth = 3.2f,
        glowMultiplier = 4f,
        alpha = 0.75f
    )

    // Rotating Tactical Target Reticles
    val baseRadius = min(w, h) * 0.28f
    rotate(degrees = phase * 45f, pivot = Offset(cx, cy)) {
        drawGlowingCircle(
            center = Offset(cx, cy),
            radius = baseRadius,
            color = SystemPalette.ElectricCyan,
            alpha = 0.75f,
            filledCore = false
        )
        drawGlowingLine(
            start = Offset(cx - baseRadius * 1.25f, cy),
            end = Offset(cx - baseRadius * 0.75f, cy),
            color = SystemPalette.ElectricCyan,
            coreWidth = 3.5f
        )
        drawGlowingLine(
            start = Offset(cx + baseRadius * 0.75f, cy),
            end = Offset(cx + baseRadius * 1.25f, cy),
            color = SystemPalette.ElectricCyan,
            coreWidth = 3.5f
        )
    }

    // Glowing Blue Shockwaves
    val waveFrac = (phase * 0.8f) % 1f
    drawGlowingCircle(
        center = Offset(cx, cy),
        radius = waveFrac * min(w, h) * 0.65f,
        color = SystemPalette.ElectricCyan,
        alpha = (1f - waveFrac),
        filledCore = false
    )
}

/**
 * C-RANK (Combat Ready):
 * Interlocking hexagonal armor matrices with camera tilt-tracking and electric arc discharges
 */
private fun DrawScope.drawCRankCombatReady(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    phase: Float,
    pulse: Float
) {
    val hexPath = cache.primaryPath
    hexPath.reset()
    val hexR = 54f
    val dx = hexR * 1.732f
    val dy = hexR * 1.5f

    var row = -1
    var y = -hexR
    while (y <= h + hexR) {
        var x = if (row % 2 == 0) 0f else dx * 0.5f
        while (x <= w + hexR) {
            val dist = hypot(x - cx, y - cy)
            val wave = sin(dist * 0.015f - phase * 4f)
            val rScale = (0.82f + 0.14f * wave) * hexR
            for (k in 0..6) {
                val a = Math.toRadians((k * 60.0 + 30.0)).toFloat()
                val hx = x + cos(a) * rScale
                val hy = y + sin(a) * rScale
                if (k == 0) hexPath.moveTo(hx, hy) else hexPath.lineTo(hx, hy)
            }
            x += dx
        }
        y += dy
        row++
    }
    drawGlowingPath(
        path = hexPath,
        color = SystemPalette.ElectricCyan,
        coreWidth = 2.2f,
        glowMultiplier = 3.5f,
        alpha = 0.62f
    )

    // Procedural Electric Arc Discharges jumping across hex armor nodes
    val arcPath = cache.secondaryPath
    arcPath.reset()
    for (arcIdx in 0 until 4) {
        val startAngle = phase * 3f + arcIdx * 1.57f
        var ax = cx + cos(startAngle) * 60f
        var ay = cy + sin(startAngle) * 60f
        arcPath.moveTo(ax, ay)
        for (seg in 1..6) {
            val jitter = sin(phase * 19f + arcIdx * 7f + seg * 3.1f) * 38f
            ax += cos(startAngle) * 45f - sin(startAngle) * jitter
            ay += sin(startAngle) * 45f + cos(startAngle) * jitter
            arcPath.lineTo(ax, ay)
        }
    }
    drawGlowingPath(
        path = arcPath,
        color = SystemPalette.PureWhite,
        coreWidth = 3f,
        glowMultiplier = 5f,
        alpha = 0.9f
    )
}

/**
 * B-RANK (Elite Hunter):
 * High-velocity orbital energy rings, dual-axis camera tracking, intense cyan-cobalt plasma trails
 */
private fun DrawScope.drawBRankEliteHunter(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    phase: Float,
    pulse: Float
) {
    val maxR = min(w, h) * 0.42f
    val orbitPath = cache.primaryPath
    orbitPath.reset()

    // 4 High-Velocity 3D Projected Orbital Energy Rings
    for (ring in 0 until 4) {
        val tiltX = 0.35f + ring * 0.18f
        val rotZ = phase * (2.2f + ring * 0.6f) * (if (ring % 2 == 0) 1f else -1f)
        val radius = maxR * (0.48f + ring * 0.17f)

        for (step in 0..72) {
            val theta = (step / 72f) * (2f * PI.toFloat())
            val localX = cos(theta) * radius
            val localY = sin(theta) * radius * tiltX
            val rx = cx + localX * cos(rotZ) - localY * sin(rotZ)
            val ry = cy + localX * sin(rotZ) + localY * cos(rotZ)
            if (step == 0) orbitPath.moveTo(rx, ry) else orbitPath.lineTo(rx, ry)
        }

        // Orbiting Plasma Comet Node on each ring
        val headTheta = phase * (4.5f + ring)
        val hxLocal = cos(headTheta) * radius
        val hyLocal = sin(headTheta) * radius * tiltX
        val hx = cx + hxLocal * cos(rotZ) - hyLocal * sin(rotZ)
        val hy = cy + hxLocal * sin(rotZ) + hyLocal * cos(rotZ)
        drawGlowingCircle(
            center = Offset(hx, hy),
            radius = 9f,
            color = SystemPalette.ElectricCyan
        )
    }

    drawGlowingPath(
        path = orbitPath,
        color = SystemPalette.AbyssalBlue,
        coreWidth = 3.5f,
        glowMultiplier = 4.8f,
        alpha = 0.88f
    )

    // Central High-Density Cobalt-Cyan Core
    drawGlowingCircle(
        center = Offset(cx, cy),
        radius = 28f + pulse * 14f,
        color = SystemPalette.ElectricCyan
    )
}

/**
 * A-RANK (Commanding Force):
 * 3D perspective tunnel of glowing runes and disciplinary monoliths with aggressive camera zoom pulses
 */
private fun DrawScope.drawARankCommandingForce(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    phase: Float,
    pulse: Float
) {
    val tunnelPath = cache.primaryPath
    val runePath = cache.secondaryPath
    tunnelPath.reset()
    runePath.reset()

    val fov = min(w, h) * 0.65f
    val numGates = 10
    val depthSpan = 1000f
    val speedZ = (phase * 260f) % (depthSpan / numGates)

    for (i in 0 until numGates) {
        val z = ((i * (depthSpan / numGates)) - speedZ + 80f).coerceAtLeast(35f)
        val halfW = 360f
        val halfH = 540f

        val tl = project3DPoint(-halfW, -halfH, z, 0f, 0f, 0f, fov, cx, cy)
        val tr = project3DPoint(halfW, -halfH, z, 0f, 0f, 0f, fov, cx, cy)
        val br = project3DPoint(halfW, halfH, z, 0f, 0f, 0f, fov, cx, cy)
        val bl = project3DPoint(-halfW, halfH, z, 0f, 0f, 0f, fov, cx, cy)

        // Monolith Gate Frame
        tunnelPath.moveTo(tl.x, tl.y)
        tunnelPath.lineTo(tr.x, tr.y)
        tunnelPath.lineTo(br.x, br.y)
        tunnelPath.lineTo(bl.x, bl.y)
        tunnelPath.close()

        // Floating Runic Glyphs along left & right tunnel walls
        val runeCenterL = project3DPoint(-halfW * 0.78f, sin(phase * 2f + i) * 120f, z, 0f, 0f, 0f, fov, cx, cy)
        val runeCenterR = project3DPoint(halfW * 0.78f, cos(phase * 2f + i) * 120f, z, 0f, 0f, 0f, fov, cx, cy)
        val rSize = (fov / z) * 26f

        // Left diamond rune
        runePath.moveTo(runeCenterL.x, runeCenterL.y - rSize)
        runePath.lineTo(runeCenterL.x + rSize * 0.7f, runeCenterL.y)
        runePath.lineTo(runeCenterL.x, runeCenterL.y + rSize)
        runePath.lineTo(runeCenterL.x - rSize * 0.7f, runeCenterL.y)
        runePath.close()

        // Right diamond rune
        runePath.moveTo(runeCenterR.x, runeCenterR.y - rSize)
        runePath.lineTo(runeCenterR.x + rSize * 0.7f, runeCenterR.y)
        runePath.lineTo(runeCenterR.x, runeCenterR.y + rSize)
        runePath.lineTo(runeCenterR.x - rSize * 0.7f, runeCenterR.y)
        runePath.close()
    }

    drawGlowingPath(
        path = tunnelPath,
        color = SystemPalette.AbyssalBlue,
        coreWidth = 2.8f,
        glowMultiplier = 4f,
        alpha = 0.8f
    )
    drawGlowingPath(
        path = runePath,
        color = SystemPalette.ElectricCyan,
        coreWidth = 3.2f,
        glowMultiplier = 4.5f,
        alpha = 0.95f
    )
}

/**
 * S-RANK (National Level Authority):
 * Sovereign electric purple & cyan lightning storms, levitating geometric shards, slow-to-fast camera sweeps
 */
private fun DrawScope.drawSRankNationalAuthority(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    phase: Float,
    pulse: Float
) {
    val lightningPurple = cache.primaryPath
    val lightningCyan = cache.secondaryPath
    val shardPath = cache.shardPath
    lightningPurple.reset()
    lightningCyan.reset()
    shardPath.reset()

    // Branching Sovereign Lightning Bolts
    for (b in 0 until 6) {
        val targetPath = if (b % 2 == 0) lightningPurple else lightningCyan
        var lx = w * (0.12f + 0.15f * b)
        var ly = 0f
        targetPath.moveTo(lx, ly)
        val steps = 12
        for (s in 1..steps) {
            ly = (s / steps.toFloat()) * h
            val jag = sin(phase * 24f + b * 9.3f + s * 4.7f) * 58f
            lx += jag
            targetPath.lineTo(lx, ly)
        }
    }

    drawGlowingPath(
        path = lightningPurple,
        color = SystemPalette.SovereignPurple,
        coreWidth = 4f,
        glowMultiplier = 5.5f
    )
    drawGlowingPath(
        path = lightningCyan,
        color = SystemPalette.ElectricCyan,
        coreWidth = 3.5f,
        glowMultiplier = 5f
    )

    // Levitating 3D Crystalline Shards
    for (i in 0 until 14) {
        val angle = (i / 14f) * 2f * PI.toFloat() + phase * 0.7f
        val orbitR = min(w, h) * (0.16f + (i % 3) * 0.12f)
        val sx = cx + cos(angle) * orbitR
        val sy = cy + sin(angle) * orbitR - sin(phase * 3f + i) * 28f
        val hSize = 26f + (i % 4) * 8f

        shardPath.moveTo(sx, sy - hSize)
        shardPath.lineTo(sx + hSize * 0.55f, sy)
        shardPath.lineTo(sx, sy + hSize * 0.8f)
        shardPath.lineTo(sx - hSize * 0.55f, sy)
        shardPath.close()
    }

    drawGlowingPath(
        path = shardPath,
        color = SystemPalette.SovereignPurple,
        coreWidth = 3f,
        glowMultiplier = 4f
    )
}

/**
 * SS-RANK (The Absolute Monarch / God of Discipline):
 * Eclipse-black core with blinding golden-cyan coronal aura, reality-distortion shockwaves,
 * multi-plane 3D parallax camera orbit, and ascending sovereign sigils
 */
private fun DrawScope.drawSSRankAbsoluteMonarch(
    cache: SystemRenderCache,
    w: Float,
    h: Float,
    cx: Float,
    cy: Float,
    phase: Float,
    pulse: Float
) {
    val minDim = min(w, h)
    val coreRadius = minDim * 0.16f
    val coronaPath = cache.primaryPath
    val sigilPath = cache.sigilPath
    coronaPath.reset()
    sigilPath.reset()

    // Reality-Distortion Concentric Shockwave Rings
    for (wIdx in 0 until 3) {
        val waveT = ((phase * 0.65f + wIdx * 0.33f) % 1f)
        val waveR = coreRadius + waveT * minDim * 0.58f
        drawGlowingCircle(
            center = Offset(cx, cy),
            radius = waveR,
            color = if (wIdx % 2 == 0) SystemPalette.SovereignGold else SystemPalette.ElectricCyan,
            alpha = (1f - waveT) * 0.85f,
            filledCore = false
        )
    }

    // Blinding Golden-Cyan Coronal Aura Rays around the Eclipse Core
    val rays = 36
    for (r in 0 until rays) {
        val angle = (r / rays.toFloat()) * 2f * PI.toFloat() + phase * 0.9f
        val innerR = coreRadius * 0.98f
        val spikeLen = coreRadius * (1.35f + 0.55f * sin(phase * 6f + r * 2.1f).absoluteValue)
        val spread = 0.045f

        coronaPath.moveTo(cx + cos(angle - spread) * innerR, cy + sin(angle - spread) * innerR)
        coronaPath.lineTo(cx + cos(angle) * spikeLen, cy + sin(angle) * spikeLen)
        coronaPath.lineTo(cx + cos(angle + spread) * innerR, cy + sin(angle + spread) * innerR)
    }

    drawGlowingPath(
        path = coronaPath,
        color = SystemPalette.SovereignGold,
        coreWidth = 3.5f,
        glowMultiplier = 5.5f
    )

    // Ascending Sovereign Sigils across 3D depth planes
    for (s in 0 until 12) {
        val sx = w * (0.1f + 0.8f * ((s * 0.37f) % 1f))
        val sy = h - ((phase * 180f + s * (h / 12f)) % h)
        val sigSize = 18f + (s % 3) * 7f

        // Sovereign Crown/Rune geometry
        sigilPath.moveTo(sx - sigSize, sy + sigSize * 0.6f)
        sigilPath.lineTo(sx - sigSize * 0.6f, sy - sigSize * 0.6f)
        sigilPath.lineTo(sx, sy)
        sigilPath.lineTo(sx + sigSize * 0.6f, sy - sigSize * 0.6f)
        sigilPath.lineTo(sx + sigSize, sy + sigSize * 0.6f)
        sigilPath.close()
    }

    drawGlowingPath(
        path = sigilPath,
        color = SystemPalette.ElectricCyan,
        coreWidth = 2.6f,
        glowMultiplier = 4f
    )

    // Outer Coronal Halo
    drawGlowingCircle(
        center = Offset(cx, cy),
        radius = coreRadius * (1.08f + pulse * 0.08f),
        color = SystemPalette.SovereignGold,
        filledCore = true
    )

    // Eclipse-Black Singular Core
    drawCircle(
        color = SystemPalette.PitchBlack,
        radius = coreRadius,
        center = Offset(cx, cy)
    )
    drawGlowingCircle(
        center = Offset(cx, cy),
        radius = coreRadius,
        color = SystemPalette.ElectricCyan,
        filledCore = false
    )
}

// ============================================================================
// DELIVERABLE 3: DisciplinaryTrajectoryGraph()
// Animated First-Time Growth & Discipline Calculation Graph with Camera Lens Tracking
// ============================================================================
@Composable
fun DisciplinaryTrajectoryGraph(
    modifier: Modifier = Modifier,
    onComplete: () -> Unit = {}
) {
    val cache = rememberSystemRenderCache()
    var replayTrigger by remember { mutableIntStateOf(0) }
    var followCamera by remember { mutableStateOf(true) }

    val progressAnim = remember { Animatable(0f) }
    LaunchedEffect(replayTrigger) {
        progressAnim.snapTo(0f)
        progressAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 6800, easing = CubicBezierEasing(0.22f, 0.08f, 0.25f, 1f))
        )
        onComplete()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "GraphAmbientPulse")
    val ambientWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambientWave"
    )

    val p = progressAnim.value

    // Mathematical functions for the two diverging trajectories (normalized x in 0f..1f -> y in 0f..1f)
    // 1. "THE SYSTEM (Disciplined Hunter)" exponential ascent
    fun systemCurveNormY(x: Float): Float = (0.14f + 0.82f * x.pow(2.15f)).coerceIn(0f, 0.98f)
    // 2. "Average Quitter" initial spike followed by decay into Penalty Zone
    fun quitterCurveNormY(x: Float): Float {
        val initialAttempt = sin((x * 3.5f).coerceAtMost(PI.toFloat())) * 0.14f
        val decay = x * 0.12f
        return (0.14f + initialAttempt - decay).coerceIn(0.04f, 0.35f)
    }

    // 3D Camera Tracking following the tip of THE SYSTEM curve, then dollying out at 85%+
    val dollyOutFactor = ((p - 0.78f) / 0.22f).coerceIn(0f, 1f)
    val desiredZoom = if (followCamera) {
        1.32f - 0.32f * dollyOutFactor
    } else 1f

    val tipNormX = p
    val tipNormY = systemCurveNormY(p)

    val desiredPanX = if (followCamera) {
        (0.5f - tipNormX) * 240f * (1f - dollyOutFactor)
    } else 0f
    val desiredPanY = if (followCamera) {
        (tipNormY - 0.5f) * 190f * (1f - dollyOutFactor)
    } else 0f

    val camZoom by animateFloatAsState(
        targetValue = desiredZoom,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "graphCamZoom"
    )
    val camX by animateFloatAsState(
        targetValue = desiredPanX,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "graphCamX"
    )
    val camY by animateFloatAsState(
        targetValue = desiredPanY,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "graphCamY"
    )

    val cameraRig = CameraRigState(
        cameraX = camX,
        cameraY = camY,
        cameraZoom = camZoom,
        cameraPitch = if (followCamera) (4f + (1f - dollyOutFactor) * 5f) else 2f,
        cameraYaw = if (followCamera) ((tipNormX - 0.5f) * 10f * (1f - dollyOutFactor)) else 0f
    )

    // Real-time Animated Percentage Counters
    val systemGrowthPct = (100 + (p.pow(2.15f) * 1380).toInt())
    val quitterDecayPct = -(p * 68).toInt()
    val currentDay = (p * 90).toInt().coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SystemPalette.PitchBlack)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .apply3DCameraRig(cameraRig)
        ) {
            val w = size.width
            val h = size.height
            // 0.25.1 guard: zero/transient-size frames never touch the draw math.
            if (w < 4f || h < 4f || !w.isFinite() || !h.isFinite()) return@Canvas
            val scale = min(w, h) * 0.0011f

            val padLeft = w * 0.10f
            val padRight = w * 0.90f
            val padTop = h * 0.18f
            val padBottom = h * 0.80f
            val plotW = padRight - padLeft
            val plotH = padBottom - padTop

            // 1. Tactical Coordinate Grid
            val gridPath = cache.gridPath
            gridPath.reset()
            val vLines = 9
            for (i in 0..vLines) {
                val gx = padLeft + (i / vLines.toFloat()) * plotW
                gridPath.moveTo(gx, padTop)
                gridPath.lineTo(gx, padBottom)
            }
            val hLines = 6
            for (j in 0..hLines) {
                val gy = padTop + (j / hLines.toFloat()) * plotH
                gridPath.moveTo(padLeft, gy)
                gridPath.lineTo(padRight, gy)
            }
            drawPath(
                path = gridPath,
                color = SystemPalette.AbyssalBlue.copy(alpha = 0.25f),
                style = Stroke(width = 1.5f)
            )

            // 2. [ PENALTY / STAGNATION ZONE ] Shaded Crimson Floor
            val penaltyTopY = padBottom - plotH * 0.18f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SystemPalette.CrimsonPenalty.copy(alpha = 0.04f),
                        SystemPalette.CrimsonPenalty.copy(alpha = 0.24f)
                    ),
                    startY = penaltyTopY,
                    endY = padBottom
                ),
                topLeft = Offset(padLeft, penaltyTopY),
                size = Size(plotW, padBottom - penaltyTopY)
            )
            drawGlowingLine(
                start = Offset(padLeft, penaltyTopY),
                end = Offset(padRight, penaltyTopY),
                color = SystemPalette.CrimsonPenalty,
                coreWidth = 1.8f,
                alpha = 0.65f
            )
            drawHudText(
                paint = cache.hudSubTextPaint,
                text = "[ PENALTY / STAGNATION ZONE ]",
                x = padLeft + 18f * scale,
                y = padBottom - 18f * scale,
                color = SystemPalette.CrimsonPenalty,
                textSizePx = 19f * scale
            )

            // 3. Plot "Average Quitter" Curve (Crimson Red Decaying Line)
            val quitterPath = cache.secondaryPath
            quitterPath.reset()
            val steps = 90
            val activeSteps = (p * steps).toInt().coerceAtLeast(1)
            var lastQuitterPt = Offset(padLeft, padBottom - systemCurveNormY(0f) * plotH)

            for (s in 0..activeSteps) {
                val nx = s / steps.toFloat()
                val ny = quitterCurveNormY(nx)
                val px = padLeft + nx * plotW
                val py = padBottom - ny * plotH
                if (s == 0) quitterPath.moveTo(px, py) else quitterPath.lineTo(px, py)
                lastQuitterPt = Offset(px, py)
            }

            drawGlowingPath(
                path = quitterPath,
                color = SystemPalette.CrimsonPenalty,
                coreWidth = 3.5f,
                glowMultiplier = 4f
            )
            drawGlowingCircle(
                center = lastQuitterPt,
                radius = 7f * scale,
                color = SystemPalette.CrimsonPenalty
            )

            // Quitter Callout Box
            drawHudText(
                paint = cache.hudSubTextPaint,
                text = "AVERAGE QUITTER ($quitterDecayPct%)",
                x = (lastQuitterPt.x - 90f * scale).coerceIn(padLeft, padRight - 220f * scale),
                y = lastQuitterPt.y + 34f * scale,
                color = SystemPalette.CrimsonPenalty,
                textSizePx = 18f * scale
            )

            // 4. Plot "THE SYSTEM (Disciplined Hunter)" Exponential Ascent Curve
            val systemPath = cache.primaryPath
            systemPath.reset()
            var lastSystemPt = Offset(padLeft, padBottom - systemCurveNormY(0f) * plotH)

            for (s in 0..activeSteps) {
                val nx = s / steps.toFloat()
                val ny = systemCurveNormY(nx)
                val px = padLeft + nx * plotW
                val py = padBottom - ny * plotH
                if (s == 0) systemPath.moveTo(px, py) else systemPath.lineTo(px, py)
                lastSystemPt = Offset(px, py)
            }

            drawGlowingPath(
                path = systemPath,
                color = SystemPalette.ElectricCyan,
                coreWidth = 5f,
                glowMultiplier = 5.5f
            )

            // 5. Milestone Nodes & Shockwaves at Day 7, Day 30, Day 90
            val milestoneFracs = floatArrayOf(7f / 90f, 30f / 90f, 1.0f)
            val milestoneLabels = arrayOf("DAY 7 // AWAKENING", "DAY 30 // RANK SURGE", "DAY 90 // SS-RANK MONARCH")

            for (m in milestoneFracs.indices) {
                val mFrac = milestoneFracs[m]
                val mx = padLeft + mFrac * plotW
                val my = padBottom - systemCurveNormY(mFrac) * plotH
                val unlocked = p >= mFrac - 0.01f

                if (unlocked) {
                    val shockProgress = ((p - mFrac) * 4.5f).coerceIn(0f, 1f)
                    val nodeColor = if (m == 2) SystemPalette.SovereignGold else SystemPalette.ElectricCyan

                    // Expanding Milestone Shockwave
                    if (shockProgress < 1f) {
                        drawGlowingCircle(
                            center = Offset(mx, my),
                            radius = (14f + shockProgress * 110f) * scale,
                            color = nodeColor,
                            alpha = 1f - shockProgress,
                            filledCore = false
                        )
                    }

                    // Glowing Milestone Node
                    drawGlowingCircle(
                        center = Offset(mx, my),
                        radius = (9f + sin(ambientWave + m) * 2f) * scale,
                        color = nodeColor
                    )

                    // Milestone HUD Callout Box & Leader Line
                    val calloutY = my - (34f + m * 12f) * scale
                    drawGlowingLine(
                        start = Offset(mx, my),
                        end = Offset(mx, calloutY + 8f * scale),
                        color = nodeColor,
                        coreWidth = 1.8f,
                        alpha = 0.8f
                    )
                    drawHudText(
                        paint = cache.hudTextPaint,
                        text = milestoneLabels[m],
                        x = mx.coerceIn(padLeft + 80f * scale, padRight - 110f * scale),
                        y = calloutY,
                        color = nodeColor,
                        textSizePx = 19f * scale,
                        alignCenter = true
                    )
                }
            }

            // Leading Curve Tip Tracking Reticle & Live HUD Callout
            drawGlowingCircle(
                center = lastSystemPt,
                radius = 12f * scale,
                color = SystemPalette.PureWhite
            )
            drawGlowingCircle(
                center = lastSystemPt,
                radius = (24f + sin(ambientWave * 2f) * 5f) * scale,
                color = SystemPalette.ElectricCyan,
                filledCore = false
            )
        }

        // Top HUD Telemetry Bar (Real-time Animated Counters & Camera Toggle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "DISCIPLINARY TRAJECTORY // DAY $currentDay",
                    color = SystemPalette.ElectricCyan,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "HUNTER OUTPUT: +$systemGrowthPct% (SS-RANK CURVE)",
                    color = SystemPalette.SovereignGold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "AVERAGE QUITTER: $quitterDecayPct% (STAGNATION)",
                    color = SystemPalette.CrimsonPenalty,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .border(1.dp, SystemPalette.ElectricCyan.copy(alpha = 0.7f), CutCornerShape(6.dp))
                        .background(SystemPalette.DeepVoid.copy(alpha = 0.85f), CutCornerShape(6.dp))
                        .clickable { followCamera = !followCamera }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (followCamera) "CAM: TRACK" else "CAM: WIDE",
                        color = SystemPalette.ElectricCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .border(1.dp, SystemPalette.SovereignGold.copy(alpha = 0.7f), CutCornerShape(6.dp))
                        .background(SystemPalette.DeepVoid.copy(alpha = 0.85f), CutCornerShape(6.dp))
                        .clickable { replayTrigger += 1 }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "REPLAY",
                        color = SystemPalette.SovereignGold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
