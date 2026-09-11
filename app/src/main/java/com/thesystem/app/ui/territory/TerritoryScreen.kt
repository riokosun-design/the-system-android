package com.thesystem.app.ui.territory

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.Geohash
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Tab 2 — Map & Territory: osmdroid dark canvas, 1KM geofenced zones, area leadership. */
@Composable
fun TerritoryScreen(vm: TerritoryViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }
    // ROUND 2: securing a zone SLAMS the hand; raising a guild shield confirms
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
                Text("TERRITORY PROTOCOL", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Spacer(Modifier.height(Grid.S4))
                Text("1KM geofenced zones. Train inside one to capture it. Top capturer leads; guilds raise shields.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Grid.S16))

                when {
                    !s.hasPermission -> GlowCard(glow = CrimsonRed) {
                        Text("GPS ACCESS REQUIRED", color = CrimsonRed, style = MaterialTheme.typography.titleMedium)
                        Text("Territory capture is physical. THE SYSTEM must verify you're actually there.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        NeonButton("GRANT GPS", {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, color = CrimsonRed)
                        Spacer(Modifier.height(6.dp))
                        // denied-forever escape hatch — the system dialog can't be re-shown, only settings can free the hunter
                        val ctx = LocalContext.current
                        NeonButton("OPEN APP SETTINGS", {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", ctx.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }, color = TextMuted)
                    }
                    else -> {
                        Box(Modifier.enterAnim(0)) {
                            DarkZoneMap(s, Modifier.fillMaxWidth().height(300.dp))
                            // ROUND 2: capture = electric ring tearing across the map; claim = purple guild ring
                            LevelUpShockwave(s.captureSignal, color = ElectricBlue)
                            LevelUpShockwave(s.claimSignal, color = NeonPurple)
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

/** Osmdroid + MapTiler DARK tiles (quota-optimized engine). Draws 1KM capture circles per geohash cell.
 *  Zoom/recenter controls float in contrast-safe shells — never bare ink over tiles. */
@Composable
private fun DarkZoneMap(s: TerritoryState, modifier: Modifier = Modifier) {
    val meLat = s.lat; val meLon = s.lon
    // ROUND 2 FIX: re-centering on every state change used to hijack the user's pan/zoom.
    var didCenter by remember { mutableStateOf(false) }
    val haptics = rememberSystemHaptics()
    Box(modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, GridLine, RoundedCornerShape(16.dp))) {
        val map = SystemMapView(
            modifier = Modifier.matchParentSize(),
            configure = { controller.setZoom(15.0) }, // inside the 13–18 clamp window
            update = { m ->
                if (meLat != null && meLon != null) {
                    val me = GeoPoint(meLat, meLon)
                    if (!didCenter) { m.controller.animateTo(me); didCenter = true }
                    val overlays = m.overlays
                    overlays.removeAll { it is Polygon || it is MyLocationNewOverlay }

                    s.zones.forEach { zone ->
                        val (zLat, zLon) = Geohash.center(zone)
                        val clan = s.clanZones.firstOrNull { it.zone == zone }
                        val isMine = zone == s.myZone
                        val poly = Polygon(m).apply {
                            points = Polygon.pointsAsCircle(GeoPoint(zLat, zLon), TerritoryViewModel.ZONE_RADIUS.toDouble())
                            fillPaint.color = when {
                                clan != null && clan.shieldActive -> 0x339D00FF // shielded = purple dome
                                isMine -> 0x2200F0FF
                                else -> 0x1100F0FF
                            }
                            outlinePaint.color = when {
                                clan != null && clan.shieldActive -> 0xCC9D00FF.toInt()
                                isMine -> 0xAA00F0FF.toInt()
                                else -> 0x3300F0FF
                            }
                            outlinePaint.strokeWidth = if (isMine) 5f else 2f
                            title = clan?.let { "⛨ ${it.clanTag ?: "GUILD"}" } ?: zone
                        }
                        overlays.add(poly)
                    }

                    // Hardware-rendered my-location dot (osmdroid manages invalidation)
                    m.overlayManager.add(MyLocationNewOverlay(m).apply { enableMyLocation(); enableFollowLocation() })
                    m.invalidate()
                }
            },
        )
        // contrast-isolated control rail (spec §2: 0xCC0E121B shell + 0x1AFFFFFF hairline)
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
            FloatingIconButton(Icons.Default.MyLocation, "Center on my position", tint = ElectricBlue) {
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

@Composable
private fun ZoneStatusCard(s: TerritoryState, vm: TerritoryViewModel) {
    val zone = s.myZone
    val leader = s.myZoneLeader
    val clan = s.myZoneClan
    val haptics = rememberSystemHaptics()
    // An unclaimed, located zone breathes — an open invitation to tap CAPTURE
    GlowCard(glow = if (clan != null) NeonPurple else ElectricBlue, pulse = zone != null && clan == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CURRENT ZONE", style = MaterialTheme.typography.labelSmall)
                Text(zone ?: (if (s.locating) "acquiring GPS…" else "—"), style = MaterialTheme.typography.titleLarge, color = ElectricBlue)
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
                NeonButton(if (s.capturing) "…" else "CAPTURE", { haptics.select(); vm.captureCurrentZone() }, enabled = zone != null && !s.capturing)
                if (s.membership != null && clan == null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { haptics.select(); vm.claimForClan() }) { Text("Claim for Guild", color = NeonPurple) }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardCard(s: TerritoryState) {
    GlowCard(glow = HunterGold) {
        Text("AREA LEADERSHIP", style = MaterialTheme.typography.labelLarge, color = HunterGold)
        Spacer(Modifier.height(6.dp))
        if (s.leaders.isEmpty()) Text("No hunters have bled here yet.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            items(s.leaders.sortedWith(compareBy({ it.zone }, { -it.captures })).take(20)) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.zone, style = MaterialTheme.typography.labelSmall, color = ElectricBlue, modifier = Modifier.width(90.dp))
                    Text("@${row.username}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                    row.clanTag?.let { Text("[$it]", color = NeonPurple, style = MaterialTheme.typography.labelSmall) }
                    Text("${row.captures}◈", color = HunterGold, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
