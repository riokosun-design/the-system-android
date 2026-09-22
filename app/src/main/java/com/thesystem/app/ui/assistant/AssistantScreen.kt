package com.thesystem.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.ai.Personality
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.FloatingIconButton
import com.thesystem.app.core.ui.SystemBackground
import com.thesystem.app.core.ui.enterAnim
import com.thesystem.app.core.ui.rememberSystemHaptics

/**
 * SYSTEM ASSISTANT (spec §12, §13, §26) — a HUD terminal, not a chatbot skin.
 * Answers come tagged with their brain (LOCAL LLM / RULES); memory persists
 * only via the explicit SAVE MEMORY action (§15). No raw chat ever leaves.
 */
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    vm: AssistantViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(s.messages.size, s.busy) {
        if (s.messages.isNotEmpty()) listState.animateScrollToItem(s.messages.size - 1)
    }

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            // ── header ───────────────────────────────────────────────────────
            Row(Modifier.enterAnim(0).fillMaxWidth().padding(vertical = Grid.S16), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SYSTEM ASSISTANT", color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("CONTEXT-SEALED · MEMORY OPT-IN ONLY", color = FaintGray, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = SystemMono)
                }
            }

            // ── personality rail (§13) ───────────────────────────────────────
            Row(Modifier.enterAnim(1).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Personality.entries.forEach { p ->
                    val sel = p == s.personality
                    Box(
                        Modifier
                            .border(1.dp, if (sel) PaperWhite else LineSoft, RoundedCornerShape(6.dp))
                            .background(if (sel) PaperWhite else PanelGray, RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { haptics.select(); vm.setPersonality(p) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(p.name, color = if (sel) InkBlack else LabelGray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }

            s.notice?.let {
                LaunchedEffect(it) { haptics.success(); vm.clearNotice() }
                Text(it, color = FaintGray, fontSize = 10.sp, fontFamily = SystemMono, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(10.dp))

            // ── transcript ───────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(s.messages) { m ->
                    if (m.fromUser) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.82f)
                                    .background(TrackGray, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                            ) {
                                Text(m.text, color = PaperWhite, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = SystemSans)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "SYSTEM · ${m.brain}",
                                    color = FaintGray, fontSize = 8.sp, letterSpacing = 1.sp, fontFamily = SystemMono,
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.Bookmark,
                                    contentDescription = "save memory",
                                    tint = FaintGray,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) { haptics.tick(); vm.saveMemory(m.text.take(180)) },
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth(0.92f)
                                    .border(1.dp, LineSoft, RoundedCornerShape(10.dp))
                                    .background(PanelGray, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                            ) {
                                Text(m.text, color = PaperWhite, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = SystemSans)
                            }
                        }
                    }
                }
                if (s.busy) {
                    item {
                        Text("SYSTEM THINKING…", color = FaintGray, fontSize = 10.sp, fontFamily = SystemMono, letterSpacing = 2.sp)
                    }
                }
            }

            // ── saved memory strip (§15 — only what the hunter pinned) ───────
            if (s.notes.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("MEMORY ${s.notes.size}", color = FaintGray, fontSize = 9.sp, fontFamily = SystemMono, letterSpacing = 1.sp)
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollStateSafe()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.notes.take(4).forEach { n ->
                            Row(
                                Modifier
                                    .border(1.dp, LineSoft, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(n.note.take(22) + if (n.note.length > 22) "…" else "", color = LabelGray, fontSize = 9.sp, maxLines = 1)
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Close, "forget", tint = FaintGray,
                                    modifier = Modifier.size(11.dp).clickable(
                                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    ) { haptics.tick(); vm.deleteNote(n.id) },
                                )
                            }
                        }
                    }
                }
            }

            // ── input rail ───────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(bottom = 14.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(300) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("ASK THE SYSTEM…", color = FaintGray, fontSize = 11.sp, fontFamily = SystemMono, letterSpacing = 1.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (input.isNotBlank()) { haptics.tick(); vm.send(input); input = "" }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PaperWhite,
                        unfocusedBorderColor = LineSoft,
                        focusedTextColor = PaperWhite,
                        unfocusedTextColor = PaperWhite,
                        cursorColor = PaperWhite,
                        focusedContainerColor = PanelGray,
                        unfocusedContainerColor = PanelGray,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = SystemMono),
                )
                Spacer(Modifier.width(8.dp))
                FloatingIconButton(
                    Icons.AutoMirrored.Filled.Send, "send",
                    tint = if (input.isBlank() || s.busy) FaintGray else PaperWhite,
                ) {
                    if (input.isNotBlank() && !s.busy) { haptics.select(); vm.send(input); input = "" }
                }
            }
        }
    }
}

@Composable
private fun rememberScrollStateSafe() = androidx.compose.foundation.rememberScrollState()
