package com.thesystem.app.ui.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.SupportConversationDto
import com.thesystem.app.data.model.SupportMessageDto
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 0.22.0 — Support inbox state: one live conversation, DM-style thread. */
data class SupportState(
    val loading: Boolean = true,
    val conv: SupportConversationDto? = null,
    val messages: List<SupportMessageDto> = emptyList(),
    val myId: String = "",
    val sending: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class SupportViewModel @Inject constructor(
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SupportState())
    val state: StateFlow<SupportState> = _state

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(myId = system.profile()?.id ?: "")
            refresh()
            // light poll — support threads are low-frequency, 6s is plenty without hammering PostgREST
            while (true) {
                delay(6000)
                refresh(silent = true)
            }
        }
    }

    suspend fun refresh(silent: Boolean = false) {
        val conv = system.mySupportConversation()
        val msgs = if (conv != null) system.supportMessages(conv.id) else emptyList()
        _state.value = _state.value.copy(
            loading = if (silent) _state.value.loading else false,
            conv = conv, messages = msgs,
        )
        if (conv != null) system.supportMarkRead(conv.id)
    }

    /** First message → support_open (creates or reuses the live conversation). */
    fun send(body: String) {
        val text = body.trim()
        if (text.isEmpty() || _state.value.sending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            val convId = _state.value.conv?.id
            val result = if (convId == null) {
                system.supportOpen(subject = text.take(60), body = text).map { }
            } else {
                system.supportUserReply(convId, text)
            }
            result.fold(
                onSuccess = { refresh(silent = true) },
                onFailure = { _state.value = _state.value.copy(error = friendlyError(it.message)) },
            )
            _state.value = _state.value.copy(sending = false)
        }
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }

    private fun friendlyError(msg: String?): String = when {
        msg == null -> "TRANSMISSION FAILED — retry."
        msg.contains("conversation_closed") -> "This ticket is RESOLVED. Send a new message to open a fresh one."
        msg.contains("empty_message") -> "Message cannot be empty."
        else -> "TRANSMISSION FAILED — the channel dropped it. Retry."
    }
}

private val SupportMineBg = Color(0xFF12324A)   // same rail as DM bubbles — mine, System-blue
private val SupportStaffBg = Color(0xFF161A1E)  // staff, charcoal

/** 0.22.0 — SUPPORT: DM-style ticket thread with the admin team.
 *  One live conversation per hunter (server law); replies land as notifications. */
@Composable
fun SupportScreen(nav: NavHostController, vm: SupportViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.error, s.notice) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    SystemBackground(wallpaperAlpha = 0.06f) {
        Box(Modifier
                .fillMaxSize()
                .navigationBarsPadding()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary) }
                    Column(Modifier.weight(1f)) {
                        Text("SUPPORT", style = MaterialTheme.typography.titleLarge, color = ElectricBlue)
                        s.conv?.let {
                            Text(
                                "TICKET ${it.id.take(8).uppercase()} · ${it.status}",
                                style = MonoLabel, color = LabelGray,
                            )
                        } ?: Text("NO OPEN TICKET — write below to start one", style = MonoLabel, color = LabelGray)
                    }
                }
                if (s.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ElectricBlue) }
                } else {
                    SupportThread(s.messages, s.myId, Modifier.weight(1f))
                    Composer(
                        sending = s.sending,
                        placeholder = if (s.conv == null) "Describe your issue — a ticket opens with your first message…" else "Message support…",
                        onSend = { haptics.tick(); vm.send(it) },
                    )
                }
            }
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(snack)
            }
        }
    }
}

@Composable
private fun SupportThread(messages: List<SupportMessageDto>, myId: String, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    if (messages.isEmpty()) {
        Column(modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("THE SYSTEM LISTENS.", color = PaperWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
            Spacer(Modifier.height(8.dp))
            Text(
                "Payments, withdrawals, bugs, penalties — one ticket, straight to the admin team. " +
                    "Replies land here and in your notification bell.",
                color = LabelGray, style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            val mine = m.senderId == myId && !m.staff
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 300.dp)
                        .background(
                            if (mine) SupportMineBg else SupportStaffBg,
                            RoundedCornerShape(
                                topStart = 14.dp, topEnd = 14.dp,
                                bottomStart = if (mine) 14.dp else 4.dp,
                                bottomEnd = if (mine) 4.dp else 14.dp,
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    if (!mine) Text("THE SYSTEM", style = MonoLabel, color = SkyBlue)
                    Text(m.body, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        Text(chatTimeSupport(m.createdAt), color = TextMuted, fontSize = 10.sp)
                        if (mine) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (m.readAt != null) "✓✓" else "✓",
                                color = if (m.readAt != null) SkyBlue else TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(sending: Boolean, placeholder: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(1000) },
            placeholder = { Text(placeholder, color = TextMuted) },
            modifier = Modifier.weight(1f),
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ElectricBlue, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { if (text.isNotBlank() && !sending) { onSend(text); text = "" } },
            enabled = text.isNotBlank() && !sending,
            modifier = Modifier.pressScale(0.78f),
        ) {
            if (sending) CircularProgressIndicator(color = ElectricBlue, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(
                Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                tint = if (text.isNotBlank()) ElectricBlue else TextMuted,
            )
        }
    }
}

private fun chatTimeSupport(iso: String?): String = iso?.let {
    runCatching {
        java.time.OffsetDateTime.parse(it)
            .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM · hh:mm a", java.util.Locale.ROOT))
    }.getOrNull()
} ?: ""
