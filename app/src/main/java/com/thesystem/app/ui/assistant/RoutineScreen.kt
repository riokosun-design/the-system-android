package com.thesystem.app.ui.assistant

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import java.time.LocalDate

/**
 * SYSTEM ROUTINE (spec §14) — the day as a battle order. AI drafts; the
 * hunter decides: accept (vault-sync), edit (toggle/remove → demotes to
 * DRAFT), reject, regenerate (armed double-tap on CONFIRMED — never silent).
 */
@Composable
fun RoutineScreen(
    onBack: () -> Unit,
    vm: RoutineViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            Row(Modifier.fillMaxWidth().padding(vertical = Grid.S16), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SYSTEM ROUTINE", color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(
                        "${LocalDate.now()} · ${s.status.name}" + if (s.brain.isNotBlank()) " · BY ${s.brain}" else "",
                        color = FaintGray, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = SystemMono,
                    )
                }
                if (s.items.isNotEmpty()) {
                    Text("${s.doneCount}/${s.items.size}", color = LabelGray, fontSize = 12.sp, fontFamily = SystemMono)
                }
            }

            (s.error ?: s.notice)?.let { msg ->
                LaunchedEffect(msg) { if (s.error != null) haptics.error() else haptics.success() }
                Text(
                    msg, color = if (s.error != null) PaperWhite else LabelGray,
                    fontSize = 10.sp, fontFamily = SystemMono,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            when {
                s.loading -> { SkeletonCards(3) }
                s.items.isEmpty() -> {
                    Column(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyState("NO ROUTINE DRAFTED FOR TODAY")
                        Spacer(Modifier.height(14.dp))
                        NeonButton(
                            "GENERATE TODAY'S ROUTINE",
                            onClick = { haptics.select(); vm.generate() },
                            enabled = !s.busy,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        itemsIndexed(s.items) { idx, item ->
                            val done = item.status == "DONE"
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, if (done) LineStrong else LineSoft, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(item.time, color = if (done) FaintGray else PaperWhite, fontSize = 12.sp, fontFamily = SystemMono)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        color = if (done) FaintGray else PaperWhite,
                                        fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                    )
                                    Text(
                                        "${item.category} · ${item.durationMin}m" + if (item.notes.isNotBlank()) " · ${item.notes}" else "",
                                        color = FaintGray, fontSize = 9.sp, fontFamily = SystemMono, maxLines = 1,
                                    )
                                }
                                Icon(
                                    Icons.Default.Check, "toggle done",
                                    tint = if (done) PaperWhite else FaintGray,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                                        ) { haptics.tick(); vm.toggle(idx) },
                                )
                                if (s.status != RoutineViewModel.RStatus.CONFIRMED) {
                                    Spacer(Modifier.width(10.dp))
                                    Icon(
                                        Icons.Default.Close, "remove",
                                        tint = FaintGray,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                            ) { haptics.tick(); vm.remove(idx) },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(
                            if (s.regenArmed) "CONFIRM REPLACE" else "REGENERATE",
                            onClick = { haptics.tick(); vm.generate() },
                            modifier = Modifier.weight(1f),
                            enabled = !s.busy,
                        )
                        GhostButton(
                            "REJECT",
                            onClick = { haptics.tick(); vm.reject() },
                            modifier = Modifier.weight(0.8f),
                            enabled = !s.busy,
                        )
                        NeonButton(
                            if (s.status == RoutineViewModel.RStatus.CONFIRMED) "SYNCED" else "ACCEPT",
                            onClick = { haptics.select(); vm.accept() },
                            modifier = Modifier.weight(1f),
                            enabled = !s.busy && s.status != RoutineViewModel.RStatus.CONFIRMED && s.items.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }
}
