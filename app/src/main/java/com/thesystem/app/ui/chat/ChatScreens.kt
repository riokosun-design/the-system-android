package com.thesystem.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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

/** 1-on-1 realtime conversation. */
@Composable
fun ConversationScreen(otherId: String, otherName: String, onBack: () -> Unit, vm: ConversationViewModel = hiltViewModel()) {
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val myId by vm.myId.collectAsStateWithLifecycle()

    SystemBackground(wallpaperAlpha = 0.06f) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary) }
                Column {
                    Text("@$otherName", style = MaterialTheme.typography.titleLarge, color = ElectricBlue)
                    Text("direct channel · realtime", style = MaterialTheme.typography.labelSmall)
                }
            }
            MessageList(msgs, myId, Modifier.weight(1f))
            Composer(onSend = vm::send, accent = ElectricBlue)
        }
    }
}

@Composable
private fun MessageList(messages: List<MessageDto>, myId: String?, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            val mine = m.senderId == myId
            // ROUND 3: every bubble springs into place (my messages pop from the right, theirs from the left)
            val springEntrance = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) { springEntrance.animateTo(1f, SystemMotion.springPop) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .graphicsLayer {
                        val dir = if (mine) 1f else -1f
                        translationX = (1f - springEntrance.value) * 140f * dir
                        alpha = springEntrance.value
                        val sc = 0.85f + 0.15f * springEntrance.value
                        scaleX = sc; scaleY = sc
                    },
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            ) {
                GlowCard(
                    glow = if (mine) ElectricBlue else NeonPurple,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    if (!mine) m.senderUsername?.let { Text("@$it", style = MaterialTheme.typography.labelSmall, color = NeonPurple) }
                    Text(m.body, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(m.createdAt?.takeLast(8)?.take(5) ?: "", style = MaterialTheme.typography.labelSmall)
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
            placeholder = { Text("Transmit…", color = TextMuted) },
            modifier = Modifier.weight(1f),
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { if (text.isNotBlank()) { haptics.tick(); onSend(text); text = "" } },
            modifier = Modifier.pressScale(0.78f), // send button squishes satisfyingly
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = accent)
        }
    }
}
