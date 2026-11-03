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
 * HUNTER VIDEO BACKDROP (vC50 LEGIBILITY LAW) — the faded cinematic anime
 * underlay bound to the splash ritual, awakening gates AND the RANK chamber.
 *
 * FIELD LAW (this order): the video was legible-hostile. The scrim stack is
 * now FIXED by directive, not tunable per call site:
 *   L1 — permanent veil: Color(0xFF030508) at alpha 0.72 over the whole frame
 *   L2 — crown-to-floor gradient: Black 0.85 → Transparent (mid-band, where
 *        the hunter silhouette breathes) → Black 0.90
 *   L3 — (floorFade / rank chamber only) an extra bottom weight 0.65 so the
 *        ladder's data rows never fight the footage.
 * The video reads as a living aura in the mid-band; typography always wins.
 *
 * CRITICAL RENDER LAW (vC48, still binding): a VideoView's pixels live in a
 * SEPARATE SurfaceFlinger layer behind the app window — Compose graphicsLayer
 * alpha on the AndroidView does NOT fade the video on budget ROMs. The fade
 * lives ONLY in these in-window scrims, which always blend over the hole.
 *
 *  • ZERO new dependencies — framework VideoView (no ExoPlayer/Media3 bloat);
 *    one 720p H.264 decoder alive at a time, ~8–15MB RSS — 2GB-RAM safe.
 *  • REPEAT forever + audio hard-muted. Lifecycle: pause ON_PAUSE / resume
 *    ON_RESUME / stopPlayback on unmount — the player never outlives its screen.
 *  • Codec failure ⇒ unmount to scrims only: never a crash, never a black slab.
 *
 * ASSET SEAM — if `onboarding_bg_video` is absent from res/raw, only the
 * scrim stack renders (no crash, no fake footage).
 */
@Composable
fun OnboardingVideoBackdrop(
    modifier: Modifier = Modifier,
    /** rank chamber: adds the L3 bottom weight behind scrollable data rows. */
    floorFade: Boolean = false,
    /** @deprecated kept for source-compat; the vC50 law stack owns the fade. */
    @Suppress("UNUSED_PARAMETER") tint: Float = 0f,
) {
    val context = LocalContext.current
    // Resource presence is resolved once — zero is a legal answer (asset seam).
    val rawResId = remember {
        context.resources.getIdentifier("onboarding_bg_video", "raw", context.packageName)
    }
    // A decoder hiccup on cheap silicon flips this once; the veil outlives it.
    val playerDead = remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(modifier.background(Color(0xFF030508))) {
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

        // ── THE vC50 LAW STACK (in-window, always blended over the hole) ──
        // L1 — permanent 72% veil
        Box(Modifier.fillMaxSize().background(Color(0xFF030508).copy(alpha = 0.72f)))
        // L2 — crown→mid→floor gradient
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.85f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.90f),
            ),
        )))
        // L3 — rank chamber floor weight (scrollable data rows anchor on dark)
        if (floorFade) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to Color.Black.copy(alpha = 0.35f),
                1f to Color.Black.copy(alpha = 0.65f),
            )))
        }
    }
}
