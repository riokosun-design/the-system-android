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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * HUNTER VIDEO BACKDROP (vC48) — the faded cinematic anime underlay, bound to
 * the splash ritual, the awakening gates AND the main RANK chamber.
 *
 * CRITICAL RENDER LAW (field fix): a VideoView's pixels live in a SEPARATE
 * SurfaceFlinger layer behind the app window. A Compose `graphicsLayer` alpha
 * on the AndroidView does NOT fade the video on budget ROMs — the surface
 * either full-blasts through the punched hole or vanishes entirely (the
 * "video never renders" field report). The fade is therefore applied ONLY via
 * in-window Compose scrims below, which SurfaceFlinger always blends over the
 * hole. The AndroidView itself renders untouched at full opacity.
 *
 * Implementation law:
 *  • ZERO new dependencies — framework VideoView on its own SurfaceView
 *    (no ExoPlayer/Media3 bloat; one 720p H.264 decoder ≈ 8–15MB RSS, only
 *    one backdrop is ever composed at a time: splash → gates → rank).
 *  • REPEAT forever + audio hard-muted — the System speaks in silence.
 *  • Lifecycle-aware: pause on ON_PAUSE, resume on ON_RESUME, stopPlayback on
 *    unmount — the player NEVER outlives its screen (2GB RAM safety).
 *  • Codec failure ⇒ the player unmounts itself and the scrim stays: never a
 *    crash, never a frozen black slab.
 *  • FADED by law: uniform dark veil [tint] + soft edge vignette, or the
 *    top-to-bottom gradient ([floorFade]) for the rank chamber. Foreground
 *    typography always wins; the video is an aura, never content.
 *
 * ASSET SEAM — if `onboarding_bg_video` is absent from res/raw this renders
 * ONLY the static scrim (no crash, no fake footage). Dropping the mp4 into
 * res/raw is the entire activation ritual.
 */
@Composable
fun OnboardingVideoBackdrop(
    modifier: Modifier = Modifier,
    /** Uniform dark veil over the live video (0.25f–0.32f per field law). */
    tint: Float = 0.30f,
    /** TRUE = rank chamber: dark top-to-bottom gradient instead of vignette. */
    floorFade: Boolean = false,
) {
    val context = LocalContext.current
    // Resource presence is resolved once — zero is a legal answer (asset seam).
    val rawResId = remember {
        context.resources.getIdentifier("onboarding_bg_video", "raw", context.packageName)
    }
    // A decoder hiccup on cheap silicon flips this once; the veil outlives it.
    val playerDead = remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(modifier.background(Color.Black)) {
        if (rawResId != 0 && !playerDead.value) {
            val lifecycleOwner = LocalLifecycleOwner.current
            val player = remember(rawResId) {
                VideoView(context).apply {
                    setVideoURI(Uri.parse("android.resource://${context.packageName}/$rawResId"))
                    setOnPreparedListener { mp ->
                        mp.isLooping = true          // REPEAT_MODE_ALL, framework style
                        mp.setVolume(0f, 0f)          // forever muted
                        start()
                    }
                    setOnErrorListener { _, _, _ ->
                        playerDead.value = true       // swallow → scrim-only, never crash
                        true
                    }
                }
            }
            // NO graphicsLayer alpha here — see the header law. The surface is
            // untouchable; only the in-window scrims beneath the UI dim it.
            AndroidView(factory = { player }, modifier = Modifier.fillMaxSize())
            DisposableEffect(lifecycleOwner, player) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> runCatching { if (player.isPlaying) player.pause() }
                        Lifecycle.Event.ON_RESUME -> runCatching { if (!playerDead.value && !player.isPlaying) player.start() }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    runCatching { player.stopPlayback() }   // decoder freed with the screen
                }
            }
        }

        // ── THE FADE LIVES HERE (in-window, always blended over the hole) ──
        val t = tint.coerceIn(0f, 0.85f)
        if (floorFade) {
            // RANK LAW: dark top-to-bottom gradient — header readable up top,
            // ladder cards anchored on the deep floor.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = t),
                0.5f to Color.Black.copy(alpha = (t + 0.15f).coerceAtMost(0.9f)),
                1f to Color.Black.copy(alpha = (t + 0.35f).coerceAtMost(0.9f)),
            )))
        } else {
            // Splash / awakening: uniform veil + soft edge vignette — the
            // monochrome hunter stays VISIBLE behind the slides, type crisp.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = t)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.30f),
                0.5f to Color.Black.copy(alpha = 0.12f),
                1f to Color.Black.copy(alpha = 0.42f),
            )))
        }
    }
}
