package com.thesystem.app.ui.territory

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.Geohash
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** Tab 2 — TERRITORY: pitch-black field terminal, white geohash grid, 1KM zones. */
@Composable
fun TerritoryScreen(vm: TerritoryViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }
    LaunchedEffect(s.captureSignal) { if (s.captureSignal > 0) haptics.slam() }
    LaunchedEffect(s.claimSignal) { if (s.claimSignal > 0) haptics.success() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> vm.onPermissionResult(grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    SystemBackground(wallpaperAlpha = 0.10f) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(horizontal = Grid.Margin)) {
                Spacer(Modifier.height(Grid.S8))
                Text("TERRITORY", style = MaterialTheme.typography.headlineMedium, color = PaperWhite)
                Spacer(Modifier.height(Grid.S4))
                Text("1KM geohash zones. Train inside one to capture it. Top capturer leads; guilds raise shields.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Grid.S16))

                when {
                    !s.hasPermission -> GlowCard {
                        Text("GPS ACCESS REQUIRED", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                        Text("Territory capture is physical. THE SYSTEM must verify you're actually there.",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        NeonButton("GRANT GPS", {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        val ctx = LocalContext.current
                        GhostButton("OPEN APP SETTINGS", {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", ctx.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }, Modifier.fillMaxWidth())
                    }
                    else -> {
                        Box(Modifier.enterAnim(0)) {
                            ZoneGridMap(s, Modifier.fillMaxWidth().height(300.dp))
                        }
                        Spacer(Modifier.height(Grid.S12))
                        Box(Modifier.enterAnim(1)) { ZoneStatusCard(s, vm) }
                        Spacer(Modifier.height(Grid.S12))
                        Box(Modifier.enterAnim(2)) { LeaderboardCard(s) }
                    }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Black void map; territory is a hairline geohash GRID. My cell is solid white. */
@Composable
private fun ZoneGridMap(s: TerritoryState, modifier: Modifier = Modifier) {
    val meLat = s.lat; val meLon = s.lon
    var didCenter by remember { mutableStateOf(false) }
    val haptics = rememberSystemHaptics()
    Box(modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, LineStrong, RoundedCornerShape(12.dp))) {
        val map = SystemMapView(
            modifier = Modifier.matchParentSize(),
            configure = { controller.setZoom(15.0) },
            update = { m ->
                if (meLat != null && meLon != null) {
                    val me = GeoPoint(meLat, meLon)
                    if (!didCenter) { m.controller.animateTo(me); didCenter = true }
                    val overlays = m.overlays
                    overlays.removeAll { it is Polygon || it is Marker }

                    s.zones.forEach { zone ->
                        val b = Geohash.decodeBounds(zone) // latMin, lonMin, latMax, lonMax
                        val clan = s.clanZones.firstOrNull { it.zone == zone }
                        val isMine = zone == s.myZone
                        val corners = listOf(
                            GeoPoint(b[0], b[1]), GeoPoint(b[2], b[1]),
                            GeoPoint(b[2], b[3]), GeoPoint(b[0], b[3]),
                            GeoPoint(b[0], b[1]),
                        )
                        val poly = Polygon(m).apply {
                            points = corners
                            // grayscale only: shielded = densest fill + thickest hairline
                            fillPaint.color = when {
                                clan != null && clan.shieldActive -> 0x26FFFFFF
                                isMine -> 0x1AFFFFFF
                                else -> 0x00000000
                            }
                            outlinePaint.color = when {
                                clan != null && clan.shieldActive -> 0xFFFFFFFF.toInt()
                                isMine -> 0xCCFFFFFF.toInt()
                                else -> 0x40FFFFFF
                            }
                            outlinePaint.strokeWidth = when {
                                clan != null && clan.shieldActive -> 4f
                                isMine -> 3f
                                else -> 1.5f
                            }
                        }
                        overlays.add(poly)
                    }

                    // own position: clean white dot with a dark ring, no blue Google marker
                    locationDot(m)?.let { dot ->
                        dot.position = me
                        dot.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        if (overlays.none { it is Marker }) overlays.add(dot)
                    }
                    m.invalidate()
                }
            },
        )
        Column(
            Modifier.align(Alignment.CenterEnd).padding(Grid.S12),
            verticalArrangement = Arrangement.spacedBy(Grid.S8),
        ) {
            FloatingIconButton(Icons.Default.Add, "Zoom in") {
                haptics.tick()
                map.controller.setZoom((map.zoomLevelDouble + 1).coerceAtMost(SystemMapEngine.MAX_ZOOM))
            }
            FloatingIconButton(Icons.Default.Remove, "Zoom out") {
                haptics.tick()
                map.controller.setZoom((map.zoomLevelDouble - 1).coerceAtLeast(SystemMapEngine.MIN_ZOOM))
            }
            FloatingIconButton(Icons.Default.MyLocation, "Center on my position") {
                haptics.tick()
                if (meLat != null && meLon != null) {
                    didCenter = true
                    map.controller.animateTo(GeoPoint(meLat, meLon))
                    map.controller.setZoom(16.0)
                }
            }
        }
    }
}

/** Cached white-hairline dot marker built once per MapView. */
private val dotCache = LruCache<MapView, Marker>(4)
private fun locationDot(m: MapView): Marker? = runCatching {
    dotCache.get(m) ?: run {
        val bmp = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.BLACK; style = Paint.Style.FILL }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; style = Paint.Style.FILL }
        c.drawCircle(14f, 14f, 10f, ring)
        c.drawCircle(14f, 14f, 7f, core)
        val marker = Marker(m).apply {
            icon = BitmapDrawable(m.context.resources, bmp)
            setOnMarkerClickListener { _, _ -> true }
        }
        dotCache.put(m, marker)
        marker
    }
}.getOrNull()

@Composable
private fun ZoneStatusCard(s: TerritoryState, vm: TerritoryViewModel) {
    val zone = s.myZone
    val leader = s.myZoneLeader
    val clan = s.myZoneClan
    val haptics = rememberSystemHaptics()
    GlowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CURRENT ZONE", style = MonoLabel)
                Text(zone ?: (if (s.locating) "acquiring GPS…" else "—"),
                    style = MonoTitle, color = PaperWhite)
                Text(
                    when {
                        clan != null && clan.shieldActive -> "GUILD SHIELD: ${clan.clanTag ?: "active"} · ${clan.taxBps / 100}% tribute"
                        leader != null -> "Area leader: @${leader.username} (${leader.captures} captures)"
                        else -> "Unclaimed territory. Be the first."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                NeonButton(if (s.capturing) "…" else "CAPTURE", { haptics.select(); vm.captureCurrentZone() },
                    enabled = zone != null && !s.capturing)
                if (s.membership != null && clan == null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { haptics.select(); vm.claimForClan() }) {
                        Text("CLAIM FOR GUILD", style = MonoLabel, color = PaperWhite)
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardCard(s: TerritoryState) {
    GlowCard {
        Text("AREA LEADERSHIP", style = MaterialTheme.typography.labelLarge, color = PaperWhite)
        Spacer(Modifier.height(6.dp))
        if (s.leaders.isEmpty()) Text("No hunters have trained here yet.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            items(s.leaders.sortedWith(compareBy({ it.zone }, { -it.captures })).take(20)) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.zone, style = MonoLabel, modifier = Modifier.width(90.dp))
                    Text("@${row.username}", style = MaterialTheme.typography.bodyMedium,
                        color = PaperWhite, modifier = Modifier.weight(1f))
                    row.clanTag?.let { Text("[$it]", color = NeonPurple, style = MonoLabel) }
                    Text("${row.captures} CAP", color = PaperWhite, style = MonoLabel)
                }
            }
        }
    }
}
