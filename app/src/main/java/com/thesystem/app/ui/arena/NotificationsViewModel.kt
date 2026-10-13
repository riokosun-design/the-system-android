package com.thesystem.app.ui.arena

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.model.MatchChallengeDto
import com.thesystem.app.data.model.UserNotificationDto
import com.thesystem.app.data.repo.ArenaRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * NOTIFICATION HUB (spec Phase 4) — the bell's brain. Polls the
 * match_challenges rail (RLS-scoped rows) on a low-power 10s cadence: badge =
 * pending INCOMING invites; one-tap ACCEPT lets the server spawn the battle
 * (mig 028) and hands the caller a battle id to navigate into.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val arena: ArenaRepository,
    private val system: SystemRepository,
) : ViewModel() {

    data class HubState(
        val incoming: List<MatchChallengeDto> = emptyList(),
        /** mig 030 operational rail — payment/withdrawal/support decisions. */
        val notices: List<UserNotificationDto> = emptyList(),
        val busyId: String? = null,
        val error: String? = null,
        val answered: String? = null, // transient notice ("DECLINED", "INVITE EXPIRED")
    ) {
        /** Badge = unanswered war invites + unread system notices. Never faked. */
        val badgeCount: Int get() = incoming.size + notices.count { it.readAt == null }
    }

    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state

    init {
        viewModelScope.launch {
            while (true) {
                poll()
                delay(10_000)
            }
        }
    }

    private suspend fun poll() {
        val me = system.profile()?.id ?: return
        _state.value = _state.value.copy(
            incoming = arena.incomingMatchChallenges(me),
            notices = system.myNotifications(20),
        )
    }

    /** Stamp all system notices read (user opened the hub / tapped through). */
    fun markNoticesRead() = viewModelScope.launch {
        system.markNotificationsRead()
        poll()
    }

    /** B answers the invite. onBattle fires with the spawned battle id on ACCEPT. */
    fun respond(c: MatchChallengeDto, accept: Boolean, onBattle: (String) -> Unit) = viewModelScope.launch {
        if (_state.value.busyId != null) return@launch
        _state.value = _state.value.copy(busyId = c.id, error = null, answered = null)
        arena.respondMatchChallenge(c.id, accept)
            .onSuccess { bid ->
                _state.value = _state.value.copy(
                    busyId = null,
                    answered = if (accept) null else "DECLINED — invite answered.",
                )
                if (accept && bid != null) onBattle(bid) else poll()
            }
            .onFailure { e ->
                _state.value = _state.value.copy(busyId = null, error = e.message)
                poll()
            }
    }

    fun clearNotice() { _state.value = _state.value.copy(error = null, answered = null) }
}
