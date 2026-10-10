package com.thesystem.app.ui.chat

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.MessageDto

/** Chat hub: private Shadow Guild room + global DM inbox/search. */
@Composable
fun ChatHomeScreen(nav: NavHostController, vm: ChatViewModel = hiltViewModel()) {
    val s by vm.home.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(if (s.membership != null) 0 else 1) }

    SystemBackground(wallpaperAlpha = 0.08f) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary) }
                Text("MESSAGES", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
            }
            SystemTabBar(tabs = listOf("GUILD", "GLOBAL DM"), selected = tab, onSelect = { tab = it })
            if (tab == 0) {
                if (s.membership == null) EmptyState("You belong to no Shadow Guild. Join one in PROTOCOL → GUILDS.")
                else Column(Modifier.fillMaxSize()) {
                    Text(
                        "[${s.clan?.tag ?: "…"}] ${s.clan?.name ?: ""} — private channel, realtime",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    MessageList(s.clanMessages, s.myId, Modifier.weight(1f))
                    Composer(onSend = vm::sendClan, accent = NeonPurple)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = Grid.Margin)) {
                    OutlinedTextField(
                        value = s.searchQuery, onValueChange = vm::onSearch,
                        label = { Text("Message any hunter by @username") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ElectricBlue, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                        items(s.searchResults, key = { "s-" + it.id }) { u ->
                            GlowCard(glow = ElectricBlue) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(u.displayName ?: "Hunter", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                                        Text("@${u.username} · LV ${u.level}", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    NeonButton("DM", { nav.navigate(Routes.dm(u.id, u.username)) })
                                }
                            }
                        }
                        if (s.searchResults.isEmpty()) {
                            items(s.inbox, key = { "i-" + it.first.id }) { (user, last) ->
                                GlowCard(glow = SurfaceHigh) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(user.displayName ?: "Hunter", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                                            Text("@${user.username} · ${last.body.take(38)}", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        NeonButton("OPEN", { nav.navigate(Routes.dm(user.id, user.username)) })
                                    }
                                }
                            }
                            if (s.inbox.isEmpty()) item { EmptyState("No conversations. Search a @handle and fire the first shot.") }
                        }
                    }
                }
            }
        }
    }
}

/** 1-on-1 realtime conversation — WhatsApp rail on the System HUD. */
@Composable
fun ConversationScreen(otherId: String, otherName: String, onBack: () -> Unit, vm: ConversationViewModel = hiltViewModel()) {
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val myId by vm.myId.collectAsStateWithLifecycle()
    val sendFailed by vm.sendFailed.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(sendFailed) {
        if (sendFailed) { snack.showSnackbar("TRANSMISSION FAILED — the channel dropped it. Retry."); vm.clearSendFailed() }
    }

    SystemBackground(wallpaperAlpha = 0.06f) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary) }
                    Text("@$otherName", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                }
                MessageList(msgs, myId, Modifier.weight(1f), receipts = true)
                Composer(onSend = vm::send, accent = SkyBlue)
            }
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(snack)
            }
        }
    }
}

private val BubbleMineBg = androidx.compose.ui.graphics.Color(0xFF12324A)
private val BubbleTheirsBg = androidx.compose.ui.graphics.Color(0xFF161A1E)

private fun chatTime(iso: String?): String = iso?.let {
    runCatching {
        java.time.OffsetDateTime.parse(it)
            .format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ROOT))
    }.getOrNull()
} ?: ""

@Composable
private fun MessageList(
    messages: List<MessageDto>,
    myId: String?,
    modifier: Modifier = Modifier,
    receipts: Boolean = false,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            val mine = m.senderId == myId
            Row(
                Modifier.fillMaxWidth().animateItem(),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            ) {
                // WHATSAPP RAIL: mine → right, tinted System-blue; theirs → left, charcoal.
                Column(
                    Modifier
                        .widthIn(max = 300.dp)
                        .background(
                            if (mine) BubbleMineBg else BubbleTheirsBg,
                            RoundedCornerShape(
                                topStart = 14.dp, topEnd = 14.dp,
                                bottomStart = if (mine) 14.dp else 4.dp,
                                bottomEnd = if (mine) 4.dp else 14.dp,
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    if (!mine) m.senderUsername?.let {
                        Text("@$it", style = MaterialTheme.typography.labelSmall, color = SkyBlue)
                    }
                    Text(m.body, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            chatTime(m.createdAt),
                            color = TextMuted, fontSize = 10.sp,
                        )
                        // read receipts — ✓ delivered (row exists), ✓✓ blue once the
                        // recipient's screen stamps read_at (mig 029). Never faked.
                        if (receipts && mine) {
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
private fun Composer(onSend: (String) -> Unit, accent: androidx.compose.ui.graphics.Color) {
    var text by remember { mutableStateOf("") }
    val haptics = rememberSystemHaptics()
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(1000) },
            placeholder = { Text("Message…", color = TextMuted) },
            modifier = Modifier.weight(1f),
            maxLines = 5, // auto-expanding box; keyboard avoidance via imePadding above
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { if (text.isNotBlank()) { haptics.tick(); onSend(text); text = "" } },
            enabled = text.isNotBlank(),
            modifier = Modifier.pressScale(0.78f), // send button squishes satisfyingly
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                tint = if (text.isNotBlank()) accent else TextMuted,
            )
        }
    }
}
