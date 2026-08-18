package com.thesystem.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ── MOTION SYSTEM ────────────────────────────────────────────────────────────
// One place that owns every spring/tween in the app. Consistent physics = the
// "buttery" feel users can't articulate but instantly notice. Every animation
// is graphics-layer-only (scale/alpha/translation), which means ZERO relayout
// passes — frames cost microseconds and run on the RenderThread at 60/120fps.
object SystemMotion {
    /** iOS-style emphasize curve — fast attack, long silky settle. */
    val Emphasize = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    val snap    = tween<Float>(160, easing = Emphasize)   // micro-feedback
    val medium  = tween<Float>(320, easing = Emphasize)   // panel entrances
    val slow    = tween<Float>(560, easing = Emphasize)   // hero moments

    val springPop:  SpringSpec<Float> = spring(dampingRatio = 0.58f, stiffness = 480f) // press / icon bounce
    val springSoft: SpringSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 240f) // XP bar fill
    val springPunch: SpringSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 300f) // LEVEL UP slam

    /** Per-item entrance delay for staggered lists. Capped so deep lists stay snappy. */
    fun stagger(index: Int, stepMs: Int = 42, maxMs: Int = 320): Int = (index * stepMs).coerceAtMost(maxMs)
}

/**
 * Physical press feedback: squish down while finger is held, spring back on release.
 * Pure graphicsLayer — no ripple-heavy measurement, no relayout. Chain BEFORE
 * `clickable` so gestures are observed before consumption.
 */
fun Modifier.pressScale(target: Float = 0.94f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) target else 1f,
        animationSpec = SystemMotion.springPop,
        label = "pressScale",
    )
    this
        .pointerInput(target) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
        .graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * One-shot staggered entrance: fade in + rise 24.dp + settle. Runs ONCE per
 * composition (subsequent recompositions cost nothing). Index drives the
 * stagger delay so screens cascade like a system boot sequence.
 */
fun Modifier.enterAnim(index: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(SystemMotion.stagger(index).toLong())
        progress.animateTo(1f, SystemMotion.medium)
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
        val s = 0.96f + 0.04f * progress.value
        scaleX = s
        scaleY = s
    }
}
