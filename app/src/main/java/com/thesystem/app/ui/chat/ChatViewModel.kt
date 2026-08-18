package com.thesystem.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.model.ClanDto
import com.thesystem.app.data.model.ClanMemberDto
import com.thesystem.app.data.model.MessageDto
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.repo.SocialRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Home (clan room + DM inbox + global @username search) ────────────────────
data class ChatHomeState(
    val membership: ClanMemberDto? = null,
    val clan: ClanDto? = null,
    val clanMessages: List<MessageDto> = emptyList(),
    val inbox: List<Pair<UserDto, MessageDto>> = emptyList(),
    val myId: String? = null,
    val searchQuery: String = "",
    val searchResults: List<UserDto> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val social: SocialRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val _home = MutableStateFlow(ChatHomeState())
    val home: StateFlow<ChatHomeState> = _home

    init {
        viewModelScope.launch {
            val me = system.profile()
            val membership = social.myMembership()
            val clan = membership?.let { social.clan(it.clanId) }
            _home.value = _home.value.copy(myId = me?.id, membership = membership, clan = clan)
            membership?.let { m ->
                social.clanMessagesFlow(m.clanId).collect { msgs ->
                    _home.value = _home.value.copy(clanMessages = msgs)
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                _home.value = _home.value.copy(inbox = social.dmInbox())
                kotlinx.coroutines.delay(4000) // light inbox refresh; conversations themselves are realtime
            }
        }
    }

    fun sendClan(body: String) {
        val clanId = _home.value.membership?.clanId ?: return
        if (body.isBlank()) return
        viewModelScope.launch { social.sendClanMessage(clanId, body.trim()) }
    }

    fun onSearch(q: String) {
        _home.value = _home.value.copy(searchQuery = q)
        if (q.length < 2) { _home.value = _home.value.copy(searchResults = emptyList()); return }
        viewModelScope.launch {
            _home.value = _home.value.copy(
                searchResults = social.searchUsers(q).filter { it.id != _home.value.myId },
            )
        }
    }
}

// ── One-on-one conversation (global DM by @username) ─────────────────────────
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val social: SocialRepository,
    system: SystemRepository,
) : ViewModel() {

    private val otherId: String = checkNotNull(savedStateHandle["otherId"])

    private val _messages = MutableStateFlow<List<MessageDto>>(emptyList())
    val messages: StateFlow<List<MessageDto>> = _messages
    private val _myId = MutableStateFlow<String?>(null)
    val myId: StateFlow<String?> = _myId

    init {
        viewModelScope.launch {
            _myId.value = system.profile()?.id
            social.dmFlow(otherId).collect { _messages.value = it }
        }
    }

    fun send(body: String) {
        if (body.isBlank()) return
        viewModelScope.launch { social.sendDm(otherId, body.trim()) }
    }
}
