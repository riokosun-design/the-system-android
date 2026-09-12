package com.thesystem.app.ui.territory

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView

/**
 * ── THE SYSTEM MAP ENGINE — MONOCHROME FIELD TERMINAL ───────────────────────
 * No street tiles, no network, no quota. The map is a pitch-black plane;
 * territory is drawn by TerritoryScreen as thin white geohash GRID lines
 * (secret-military-system look). osmdroid is used purely as the geospatial
 * projection + zoom/pan engine, fully offline.
 *
 *  · zoom clamped 13.0–18.0
 *  · data connection permanently off — the void tile source never fetches
 *  · MapView paints its own black background where tiles would be
 *  · lazy lifecycle: created while the Map tab lives, detached on switch
 */
object SystemMapEngine {

    const val MIN_ZOOM = 13.0
    const val MAX_ZOOM = 18.0

    /** Network-less tile source: URL is never called (useDataConnection=false). */
    private val VoidTileSource: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "VoidGrid",
        MIN_ZOOM.toInt(), MAX_ZOOM.toInt(), 256, ".png",
        arrayOf("http://127.0.0.1/"),
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String = ""
    }

    /** Kept for the Application bootstrap; harmless with no network tiles. */
    fun configureDiskCache(context: Context) {
        val c = Configuration.getInstance()
        c.osmdroidTileCache = java.io.File(context.cacheDir, "system_tiles")
        c.tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
        c.tileFileSystemCacheTrimBytes = 32L * 1024 * 1024
    }

    @Composable
    fun rememberSystemMap(configure: MapView.() -> Unit): MapView {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        val map = remember {
            MapView(context).apply {
                setTileSource(VoidTileSource)
                setMultiTouchControls(true)
                minZoomLevel = MIN_ZOOM
                maxZoomLevel = MAX_ZOOM
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                // void plane — always, no connectivity handling required
                setUseDataConnection(false)
                setBackgroundColor(android.graphics.Color.BLACK)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                configure()
            }
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> map.onResume()
                    Lifecycle.Event.ON_STOP -> map.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        DisposableEffect(Unit) {
            onDispose { map.onDetach() }
        }

        return map
    }
}

/** Drop-in AndroidView bound to the void engine. */
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
