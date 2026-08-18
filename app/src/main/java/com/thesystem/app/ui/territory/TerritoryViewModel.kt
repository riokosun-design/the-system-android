package com.thesystem.app.ui.territory

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.thesystem.app.core.Geohash
import com.thesystem.app.core.SystemMath
import com.thesystem.app.data.model.ClanMemberDto
import com.thesystem.app.data.model.ClanTerritoryDto
import com.thesystem.app.data.model.ZoneLeaderboardDto
import com.thesystem.app.data.repo.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class TerritoryState(
    val hasPermission: Boolean = false,
    val locating: Boolean = true,
    val lat: Double? = null,
    val lon: Double? = null,
    val myZone: String? = null,
    val zones: List<String> = emptyList(),
    val leaders: List<ZoneLeaderboardDto> = emptyList(),
    val clanZones: List<ClanTerritoryDto> = emptyList(),
    val membership: ClanMemberDto? = null,
    val capturing: Boolean = false,
    val captureSignal: Int = 0,   // bump → screen fires the electric shockwave
    val claimSignal: Int = 0,     // bump → screen fires the purple guild shockwave
    val notice: String? = null,
    val error: String? = null,
) {
    val myZoneLeader: ZoneLeaderboardDto? get() = leaders.filter { it.zone == myZone }.maxByOrNull { it.captures }
    val myZoneClan: ClanTerritoryDto? get() = clanZones.firstOrNull { it.zone == myZone }
}

@HiltViewModel
class TerritoryViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val social: SocialRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TerritoryState())
    val state: StateFlow<TerritoryState> = _state

    init {
        viewModelScope.launch { _state.value = _state.value.copy(membership = social.myMembership()) }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(hasPermission = granted)
        if (granted) locate()
    }

    @SuppressLint("MissingPermission") // gated by hasPermission
    fun locate() = viewModelScope.launch {
        if (!_state.value.hasPermission) return@launch
        _state.value = _state.value.copy(locating = true)
        val fused = LocationServices.getFusedLocationProviderClient(app)
        val loc = runCatching {
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
        }.getOrNull()
        if (loc != null) {
            // ROUND 6 anti-spoof: mock providers can't hold territory. Fair play is law.
            if (isMockLocation(loc)) {
                _state.value = _state.value.copy(locating = false, error = "MOCK LOCATION DETECTED — THE SYSTEM only respects real movement.")
                return@launch
            }
            val zone = Geohash.encode(loc.latitude, loc.longitude)
            val grid = Geohash.gridAround(zone)
            val leaders = social.leaderboardsFor(grid)
            val clanZones = social.clanTerritories(grid)
            _state.value = _state.value.copy(
                locating = false, lat = loc.latitude, lon = loc.longitude,
                myZone = zone, zones = grid, leaders = leaders, clanZones = clanZones,
            )
        } else {
            _state.value = _state.value.copy(locating = false, error = "GPS fix failed. Move outdoors and retry.")
        }
    }

    /** Capture the 1KM zone you're standing in: a workout is logged and bound to the geohash cell. */
    fun captureCurrentZone(reps: Int = 20, durationSec: Int = 60) {
        val zone = _state.value.myZone ?: return
        if (_state.value.capturing) return
        _state.value = _state.value.copy(capturing = true)
        viewModelScope.launch {
            social.captureZone(zone, reps, durationSec)
                .onSuccess {
                    _state.value = _state.value.copy(
                        capturing = false,
                        captureSignal = _state.value.captureSignal + 1,
                        notice = "ZONE SECURED · ${_state.value.myZone} · +10 XP",
                    )
                    refreshBoard()
                }
                .onFailure { _state.value = _state.value.copy(capturing = false, error = it.message) }
        }
    }

    /** Claim your current zone for your Shadow Guild → Guild Shield + 5% tax on non-members. */
    fun claimForClan() {
        val zone = _state.value.myZone ?: return
        viewModelScope.launch {
            social.claimTerritoryForClan(zone)
                .onSuccess {
                    _state.value = _state.value.copy(
                        claimSignal = _state.value.claimSignal + 1,
                        notice = "GUILD SHIELD ACTIVE over $zone — 5% tribute routed to treasury.",
                    )
                    refreshBoard()
                }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    private fun refreshBoard() = viewModelScope.launch {
        val grid = _state.value.zones.ifEmpty { return@launch }
        _state.value = _state.value.copy(
            leaders = social.leaderboardsFor(grid),
            clanZones = social.clanTerritories(grid),
        )
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }

    /** Mock-provider check across API levels (isMock is the modern, spill-proof API). */
    private fun isMockLocation(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock
        else @Suppress("DEPRECATION") l.isFromMockProvider

    companion object { val ZONE_RADIUS = SystemMath.TERRITORY_ZONE_RADIUS_METERS }
}
