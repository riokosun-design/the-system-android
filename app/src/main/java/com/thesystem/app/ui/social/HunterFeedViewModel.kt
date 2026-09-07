package com.thesystem.app.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.model.HunterPostDto
import com.thesystem.app.data.repo.ArenaRepository
import com.thesystem.app.data.repo.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FeedScope(val label: String) { GLOBAL("Global Broadcasts"), GUILD("Guild Only"), RIVALRY("Rivalry") }

data class FeedState(
    val loading: Boolean = true,
    val scope: FeedScope = FeedScope.GLOBAL,
    val posts: List<HunterPostDto> = emptyList(),     // every fetched row; threads resolved client-side
    val myId: String? = null,
    val myClanId: String? = null,
    val myMana: Set<String> = emptySet(),
    val myTransmits: Set<String> = emptySet(),
    val composerOpen: Boolean = false,
    val quoteTarget: HunterPostDto? = null,
    val replyTarget: HunterPostDto? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    /** Scope filter — the three lenses, resolved over one fetch. */
    val timeline: List<HunterPostDto>
        get() {
            val top = posts.filter { it.parentId == null }
            return when (scope) {
                FeedScope.GLOBAL -> top
                FeedScope.GUILD -> top.filter { it.authorClanId != null && it.authorClanId == myClanId }
                FeedScope.RIVALRY -> top.filter {
                    it.authorId != myId && (myClanId == null || it.authorClanId != myClanId)
                }
            }
        }

    fun threadOf(post: HunterPostDto): List<HunterPostDto> =
        posts.filter { it.parentId == post.id }.sortedBy { it.createdAt }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class HunterFeedViewModel @Inject constructor(
    private val social: SocialRepository,
    private val arena: ArenaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state

    init {
        refresh()
        // Realtime: posts + interactions changes → silent re-sync (debounced coalescing).
        viewModelScope.launch {
            runCatching {
                social.hunterFeedFlow().debounce(700).collect { refresh(silent = true) }
            }
        }
    }

    fun refresh(silent: Boolean = false) = viewModelScope.launch {
        if (!silent) _state.value = _state.value.copy(loading = true)
        val posts = social.hunterFeed()
        val me = social.myMembership()?.userId ?: _state.value.myId
        val myClan = social.myMembership()?.clanId
        val mine = social.myFeedInteractions()
        _state.value = _state.value.copy(
            loading = false,
            posts = posts,
            myId = me,
            myClanId = myClan,
            myMana = mine.filterValues { "mana_boost" in it }.keys,
            myTransmits = mine.filterValues { "transmit" in it }.keys,
        )
    }

    fun setScope(scope: FeedScope) { _state.value = _state.value.copy(scope = scope) }

    // ── Composer ─────────────────────────────────────────────────────────────
    fun openComposer() { _state.value = _state.value.copy(composerOpen = true) }
    fun closeComposer() { _state.value = _state.value.copy(composerOpen = false, quoteTarget = null) }
    fun openQuote(post: HunterPostDto) { _state.value = _state.value.copy(quoteTarget = post, composerOpen = true) }
    fun openReply(post: HunterPostDto) { _state.value = _state.value.copy(replyTarget = post) }
    fun closeReply() { _state.value = _state.value.copy(replyTarget = null) }

    fun dispatch(content: String, mediaUrl: String?) = viewModelScope.launch {
        val text = content.trim()
        if (text.isBlank() || _state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true)
        val quote = _state.value.quoteTarget
        val result = if (quote != null) social.retransmit(quote, text) else social.createPost(text, mediaUrl)
        result
            .onSuccess {
                _state.value = _state.value.copy(busy = false, composerOpen = false, quoteTarget = null,
                    notice = if (quote != null) "Re-dispatched to your timeline." else "Dispatch live on the wire.")
                refresh(silent = true)
            }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message ?: "Dispatch failed") }
    }

    fun sendReply(content: String) = viewModelScope.launch {
        val target = _state.value.replyTarget ?: return@launch
        val text = content.trim()
        if (text.isBlank() || _state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true)
        social.createPost(text, parentId = target.id)
            .onSuccess {
                _state.value = _state.value.copy(busy = false, notice = "Reply threaded.")
                refresh(silent = true)
            }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message ?: "Reply failed") }
    }

    /** Arise — optimistic toggle; server is the truth, realtime will confirm. */
    fun toggleMana(post: HunterPostDto) = viewModelScope.launch {
        val active = post.id in _state.value.myMana
        val delta = if (active) -1L else 1L
        _state.value = _state.value.copy(
            myMana = if (active) _state.value.myMana - post.id else _state.value.myMana + post.id,
            posts = _state.value.posts.map {
                if (it.id == post.id) it.copy(manaCount = (it.manaCount + delta).coerceAtLeast(0)) else it
            },
        )
        social.toggleMana(post.id, boost = !active)
            .onFailure { refresh(silent = true) } // roll back via truth
    }

    /** 1v1 CTA: spawn the duel via the Arena engine, then hand the caller the battle id. */
    fun challenge(post: HunterPostDto, onDispatched: (String) -> Unit) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        if (post.authorId == _state.value.myId) {
            _state.value = _state.value.copy(error = "You cannot duel your own reflection.")
            return@launch
        }
        _state.value = _state.value.copy(busy = true)
        arena.challenge(post.authorId)
            .onSuccess { battleId ->
                social.markChallenge(post.id)
                _state.value = _state.value.copy(busy = false, notice = "Duel dispatched — 60-second summons issued.")
                onDispatched(battleId)
            }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message ?: "Challenge failed") }
    }

    fun deletePost(post: HunterPostDto) = viewModelScope.launch {
        if (post.authorId != _state.value.myId) return@launch
        social.deletePost(post.id)
            .onSuccess { _state.value = _state.value.copy(notice = "Dispatch erased."); refresh(silent = true) }
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}
