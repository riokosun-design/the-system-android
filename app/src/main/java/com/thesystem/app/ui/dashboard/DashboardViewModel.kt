package com.thesystem.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.core.SystemMath
import com.thesystem.app.data.model.FormDto
import com.thesystem.app.data.model.QuestDto
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardState(
    val loading: Boolean = true,
    val profile: UserDto? = null,
    val quests: List<QuestDto> = emptyList(),
    val forms: List<FormDto> = emptyList(),
    val wallpaper: String? = null,
    val wallpaperAlpha: Float = 0.22f,
    val projectedDecay: Long = 0,
    val error: String? = null,
    val notice: String? = null,
) {
    val rank get() = profile?.rank?.value ?: SystemMath.HunterRank.AVERAGE
    val buffs: List<Pair<String, Boolean>> get() {
        val p = profile ?: return emptyList()
        return buildList {
            if (p.streakDays >= 3) add("STREAK ×${p.streakDays} — hard work amplified" to true)
            if (p.questsDoneToday(quests) >= 3) add("DAILY CLEAR — immunity to one decay tick" to true)
            when (p.missedDays) {
                1 -> add("WARNING — skipped yesterday. XP decay armed." to false)
                2 -> add("DECAY ACTIVE — losing XP daily" to false)
                in 3..4 -> add("DEGRADED: GARBAGE" to false)
                in 5..Int.MAX_VALUE -> add("DEGRADED: LOSER" to false)
            }
        }
    }
    private fun UserDto.questsDoneToday(q: List<QuestDto>) = q.count { it.completed }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(private val system: SystemRepository) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val profileD = async { system.profile() }
        val questsD = async { system.dailyQuests() }
        val formsD = async { system.myForms() }
        val wallD = async { system.wallpaperFor("DASHBOARD_HERO") }
        val profile = profileD.await()
        _state.value = DashboardState(
            loading = false,
            profile = profile,
            quests = questsD.await(),
            forms = formsD.await(),
            wallpaper = wallD.await()?.first,
            wallpaperAlpha = wallD.await()?.second ?: 0.22f,
            projectedDecay = profile?.let { SystemMath.xpDecayForMissedDay(it.xp, it.missedDays) } ?: 0,
            error = if (profile == null) "OFFLINE MODE — retry when back on the grid." else null,
        )
    }

    fun completeQuest(quest: QuestDto) = viewModelScope.launch {
        system.completeQuest(quest.id)
            .onSuccess {
                _state.value = _state.value.copy(notice = "+${quest.xpReward} XP — ${quest.title} cleared.")
                delay(600); refresh()
            }
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }

    fun clearNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}
