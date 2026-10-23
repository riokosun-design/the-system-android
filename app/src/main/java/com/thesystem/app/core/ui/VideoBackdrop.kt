package com.thesystem.app.core.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * ONBOARDING VIDEO BACKDROP — the hunter's cinematic underlay (0.26.0, directive §4).
 *
 * Loops `res/raw/onboarding_bg_video.mp4` muted behind the splash and the
 * onboarding ritual. Implementation law:
 *
 *  • ZERO new dependencies — framework VideoView (no ExoPlayer bloat).
 *  • REPEAT forever + audio hard-muted, the System speaks in silence.
 *  • Lifecycle-aware: pause on background, resume on return, stopPlayback on
 *    unmount — the player never outlives its screen.
 *  • FADED by law: the video renders at [visibility] alpha over pure black
 *    (0.25f–0.35f per spec), then a top/bottom dark vignette sits on top so
 *    foreground typography always wins. The video is an aura, never content.
 *
 * ASSET SEAM — if `onboarding_bg_video` is absent from res/raw, this renders
 * ONLY the static vignette (no crash, no fake footage, no thrown frame).
 * Dropping the mp4 into res/raw is the entire activation ritual.
 */
@Composable
fun OnboardingVideoBackdrop(
    modifier: Modifier = Modifier,
    visibility: Float = 0.30f,
) {
    val context = LocalContext.current
    // Resource presence is resolved once — zero is a legal answer (asset seam).
    val rawResId = remember {
        context.resources.getIdentifier("onboarding_bg_video", "raw", context.packageName)
    }

    Box(modifier) {
        if (rawResId != 0) {
            val lifecycleOwner = LocalLifecycleOwner.current
            val player = remember(rawResId) {
                VideoView(context).apply {
                    setVideoURI(Uri.parse("android.resource://${context.packageName}/$rawResId"))
                    setOnPreparedListener { mp ->
                        mp.isLooping = true          // REPEAT_MODE_ALL, framework style
                        mp.setVolume(0f, 0f)          // forever muted
                        start()
                    }
                }
            }
            AndroidView(
                factory = { player },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = visibility.coerceIn(0f, 1f) },
            )
            DisposableEffect(lifecycleOwner, player) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> runCatching { if (player.isPlaying) player.pause() }
                        Lifecycle.Event.ON_RESUME -> runCatching { if (!player.isPlaying) player.start() }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    runCatching { player.stopPlayback() }
                }
            }
        }
        // The vignette law — dark crown above, deeper floor below: every glyph
        // over the aura stays 100% legible, video or not.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.62f),
                    0.5f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Black.copy(alpha = 0.78f),
                ),
            ),
        )
    }
}
