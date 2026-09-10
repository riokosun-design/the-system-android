package com.thesystem.app.ui.territory

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.thesystem.app.BuildConfig
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView

/**
 * ── THE SYSTEM MAP ENGINE ────────────────────────────────────────────────────
 * MapTiler dark raster tiles through osmdroid with a strict QUOTA BUDGET:
 *
 *  1. AGGRESSIVE DISK CACHE — SqlTileWriter base + 150MB trim budget.
 *     Re-viewing a captured 1KM cell = ZERO API requests after first touch.
 *  2. ZOOM CLAMPED to 13.0–18.0 — nobody fans out to world-view tile spam.
 *  3. LAZY LIFECYCLE — MapView is only instantiated while the Territory tab is
 *     visible (Compose disposes it on tab switch), and tile downloads pause on
 *     ON_STOP via lifecycle observer.
 *  4. OFFLINE FALLBACK — the moment connectivity drops, dataConnection flips
 *     off and the provider serves cached tiles only. No crash, grey grid of cache.
 *
 * Free-tier budget (100k tiles/mo): a 13–18 zoom raid of one district ≈ 40–120
 * tiles per NEW area, 0 thereafter. Thousands of hunters fit inside the quota.
 */
object SystemMapEngine {

    const val MIN_ZOOM = 13.0
    const val MAX_ZOOM = 18.0

    // MapTiler dark raster — 256px PNGs (key sourced from BuildConfig, NOT hardcoded)
    // NOTE: if MapTiler flags ch-swisstopo-lbm-dark on your plan, swap the middle
    //       segment to "streets-v2-dark" — same dark theme, same quota mechanics.
    private fun tileUrl() =
        "https://api.maptiler.com/maps/ch-swisstopo-lbm-dark/{z}/{x}/{y}.png?key=${BuildConfig.MAPTILER_API_KEY}"

    val DarkTileSource: OnlineTileSourceBase by lazy {
        object : OnlineTileSourceBase(
            "SystemDarkMapTiler",
            MIN_ZOOM.toInt(), MAX_ZOOM.toInt(), 256, ".png",
            arrayOf("https://api.maptiler.com/maps/ch-swisstopo-lbm-dark/"),
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                baseUrl +
                    MapTileIndex.getZoom(pMapTileIndex) + "/" +
                    MapTileIndex.getX(pMapTileIndex) + "/" +
                    MapTileIndex.getY(pMapTileIndex) +
                    ".png?key=" + BuildConfig.MAPTILER_API_KEY
        }
    }

    /** Cache budget — call once from Application.onCreate (osmdroid config singleton). */
    fun configureDiskCache(context: Context) {
        val c = Configuration.getInstance()
        c.osmdroidTileCache = java.io.File(context.cacheDir, "system_tiles")
        c.tileFileSystemCacheMaxBytes = 150L * 1024 * 1024      // 150MB ceiling…
        c.tileFileSystemCacheTrimBytes = 100L * 1024 * 1024     // …trims DOWN to 100MB (the "min 100MB" you asked for)
        c.expirationOverrideDuration = 1000L * 60 * 60 * 24 * 30  // treat tiles fresh for 30 days — re-views = 0 requests
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * The Compose wrapper. Creates the MapView ONCE per tab-visit, wires
     * lifecycle pause/resume, watches connectivity for the cache-only fallback,
     * and fully detaches on tab switch (AnimatedContent disposal).
     */
    @Composable
    fun rememberSystemMap(configure: MapView.() -> Unit): MapView {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        val map = remember {
            MapView(context).apply {
                setTileSource(DarkTileSource)
                setMultiTouchControls(true)
                minZoomLevel = MIN_ZOOM
                maxZoomLevel = MAX_ZOOM
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                setUseDataConnection(isOnline(context)) // offline at birth → cache-only mode
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER) // System rail owns zoom (2.6)
                configure()
            }
        }

        // Lifecycle: pause tile threads the instant the app backgrounds
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> { map.setUseDataConnection(isOnline(context)); map.onResume() }
                    Lifecycle.Event.ON_STOP -> map.onPause()   // downloads halt; cached rendering stays
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Connectivity watch: offline → serve SQLite cache; online → quota-approved fetches
        DisposableEffect(context) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { map.post { map.setUseDataConnection(true); map.invalidate() } }
                override fun onLost(network: Network) { map.post { map.setUseDataConnection(false); map.invalidate() } }
            }
            runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb) }
            onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
        }

        // Tab switch (this composable leaves composition) → FULL detach, zero tile churn
        DisposableEffect(Unit) {
            onDispose { map.onDetach() }
        }

        return map
    }
}

/** Drop-in AndroidView bound to the quota-optimized engine.
 *  Returns the live MapView so callers can drive floating controls (zoom/recenter). */
@Composable
fun SystemMapView(
    modifier: Modifier = Modifier,
    configure: MapView.() -> Unit = {},
    update: (MapView) -> Unit = {},
): MapView {
    val map = SystemMapEngine.rememberSystemMap(configure)
    AndroidView(factory = { map }, modifier = modifier, update = update)
    return map
}
