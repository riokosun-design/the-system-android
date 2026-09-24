package com.thesystem.app.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.ai.CommitmentBlock
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.service.RoutineAlarmScheduler
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
    var showSetup by remember { mutableStateOf(false) }
    var alarmFor by remember { mutableStateOf<CommitmentBlock?>(null) }

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
                LaunchedEffect(msg) { if (s.error != null) haptics.error() else haptics.success(); vm.clearNotice() }
                Text(
                    msg, color = if (s.error != null) PaperWhite else LabelGray,
                    fontSize = 10.sp, fontFamily = SystemMono,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // ── daily protocol anchors — fixed life blocks the AI plans around ──
            if (!s.loading) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("DAILY PROTOCOL", color = PaperWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = SystemMono, modifier = Modifier.weight(1f))
                    TextButton(onClick = { haptics.tick(); showSetup = true }) {
                        Text(if (s.needsSetup) "SET UP" else "EDIT", color = LabelGray, fontSize = 10.sp, fontFamily = SystemMono)
                    }
                }
                if (s.needsSetup) {
                    GlowCard {
                        Text("BUILD YOUR SYSTEM ROUTINE", color = PaperWhite, style = MaterialTheme.typography.labelLarge)
                        Text("Mark your fixed hours — college, training, sleep. The System plans quests around them. No alarms fire unless you arm them.",
                            style = MaterialTheme.typography.bodyMedium, color = LabelGray)
                        Spacer(Modifier.height(10.dp))
                        NeonButton("DEFINE MY DAY", { haptics.select(); showSetup = true }, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Column(
                        Modifier.fillMaxWidth().border(1.dp, LineSoft, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        s.commitments.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.title.uppercase(), color = PaperWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                                    Text(
                                        c.start + (if (c.end.isNotBlank()) "–${c.end}" else "") + " · ${c.days}",
                                        color = FaintGray, fontSize = 9.sp, fontFamily = SystemMono,
                                    )
                                }
                                Icon(
                                    Icons.Default.Notifications, "alarm",
                                    tint = LabelGray,
                                    modifier = Modifier.size(16.dp).clickable(
                                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    ) { haptics.tick(); alarmFor = c },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
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
                                    .enterAnim(idx)
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

    if (showSetup) {
        CommitmentSetupSheet(
            initial = s.commitments,
            onDismiss = { showSetup = false },
            onSave = { blocks -> vm.saveCommitments(blocks); showSetup = false },
        )
    }
    alarmFor?.let { block ->
        AlarmEditSheet(block = block, onDismiss = { alarmFor = null })
    }
}

// ════════════════════════════════════════════════════════════════════════════
// DAILY PROTOCOL — commitments setup sheet
// ════════════════════════════════════════════════════════════════════════════

private val QUICK_BLOCKS = listOf(
    CommitmentBlock("School", "08:00", "14:00"),
    CommitmentBlock("College", "09:00", "16:00"),
    CommitmentBlock("Tuition", "17:00", "18:30"),
    CommitmentBlock("Work", "09:00", "17:00"),
    CommitmentBlock("Football", "06:00", "07:30"),
    CommitmentBlock("Martial Arts", "19:00", "20:30"),
    CommitmentBlock("Training", "17:00", "18:30"),
    CommitmentBlock("Study", "20:00", "22:00"),
    CommitmentBlock("Sleep", "22:30", "06:30"),
    CommitmentBlock("Other", "12:00", "13:00"),
)

private val TIME_RE = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")

private fun nextDays(current: String) = when (current) {
    "DAILY" -> "MON-FRI"
    "MON-FRI" -> "WEEKEND"
    else -> "DAILY"
}

@Composable
private fun CommitmentSetupSheet(
    initial: List<CommitmentBlock>,
    onDismiss: () -> Unit,
    onSave: (List<CommitmentBlock>) -> Unit,
) {
    val haptics = rememberSystemHaptics()
    var blocks by remember { mutableStateOf(if (initial.isEmpty()) listOf(CommitmentBlock("College", "09:00", "16:00")) else initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .background(PanelGray, RoundedCornerShape(18.dp))
                .border(1.dp, LineStrong, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("BUILD YOUR SYSTEM ROUTINE", color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = SystemMono)
            Text("Fixed hours only. Everything else stays free for quests.",
                color = FaintGray, fontSize = 10.sp, fontFamily = SystemMono, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))

            Text("QUICK ADD", color = LabelGray, fontSize = 9.sp, fontFamily = SystemMono, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QUICK_BLOCKS.forEach { preset ->
                    Box(
                        Modifier
                            .border(1.dp, LineSoft, RoundedCornerShape(6.dp))
                            .background(InkBlack.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) { haptics.tick(); blocks = blocks + preset }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(preset.title.uppercase(), color = LabelGray, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            blocks.forEachIndexed { idx, c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title.uppercase(), color = PaperWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TimeField(c.start, Modifier.width(64.dp)) { v -> blocks = blocks.toMutableList().also { it[idx] = c.copy(start = v) } }
                            Text(" – ", color = FaintGray, fontSize = 11.sp, fontFamily = SystemMono)
                            TimeField(c.end, Modifier.width(64.dp)) { v -> blocks = blocks.toMutableList().also { it[idx] = c.copy(end = v) } }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .border(1.dp, LineSoft, RoundedCornerShape(5.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    ) { haptics.tick(); blocks = blocks.toMutableList().also { it[idx] = c.copy(days = nextDays(c.days)) } }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text(c.days, color = LabelGray, fontSize = 8.sp, fontFamily = SystemMono)
                            }
                        }
                    }
                    Icon(
                        Icons.Default.Close, "remove", tint = FaintGray,
                        modifier = Modifier.size(15.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { haptics.tick(); blocks = blocks.toMutableList().also { it.removeAt(idx) } },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("CANCEL", onDismiss, Modifier.weight(1f))
                NeonButton("SAVE", {
                    haptics.select()
                    onSave(blocks.filter { TIME_RE.matches(it.start.trim()) && (it.end.isBlank() || TIME_RE.matches(it.end.trim())) }
                        .map { it.copy(start = it.start.trim(), end = it.end.trim()) })
                }, Modifier.weight(1f), enabled = blocks.isNotEmpty())
            }
        }
    }
}

@Composable
private fun TimeField(value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(5)) },
        modifier = modifier,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = PaperWhite, fontSize = 11.sp, fontFamily = SystemMono),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PaperWhite, unfocusedBorderColor = LineSoft,
            focusedContainerColor = InkBlack.copy(alpha = 0.4f), unfocusedContainerColor = InkBlack.copy(alpha = 0.4f),
            cursorColor = PaperWhite,
        ),
        shape = RoundedCornerShape(6.dp),
    )
}

// ════════════════════════════════════════════════════════════════════════════
// ROUTINE ALARM — hunter-controlled, never forced (spec §7)
// ════════════════════════════════════════════════════════════════════════════

private fun alarmIdFor(label: String) = ("block#${label.trim().lowercase()}").hashCode() and 0x7FFFFFFF

private fun maskFor(days: String) = when (days) {
    "MON-FRI" -> 0b0011111
    "WEEKEND" -> 0b1100000
    else -> 0b1111111
}

@Composable
private fun AlarmEditSheet(block: CommitmentBlock, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberSystemHaptics()
    val existing = remember(block.title) { RoutineAlarmScheduler.alarms(context).firstOrNull { it.id == alarmIdFor(block.title) } }
    var minutes by remember { mutableStateOf(existing?.minutesOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: block.start) }
    var days by remember { mutableStateOf(if (existing != null) when (existing.daysMask) { 0b0011111 -> "MON-FRI"; 0b1100000 -> "WEEKEND"; else -> "DAILY" } else "DAILY") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth(0.9f)
                .background(PanelGray, RoundedCornerShape(18.dp))
                .border(1.dp, LineStrong, RoundedCornerShape(18.dp))
                .padding(20.dp),
        ) {
            Text("ALARM — ${block.title.uppercase()}", color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = SystemMono)
            Text("Optional. The System never forces an alarm on you.",
                color = FaintGray, fontSize = 9.sp, fontFamily = SystemMono, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeField(minutes, Modifier.width(72.dp)) { minutes = it }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .border(1.dp, LineSoft, RoundedCornerShape(5.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { haptics.tick(); days = nextDays(days) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(days, color = LabelGray, fontSize = 9.sp, fontFamily = SystemMono)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .border(1.dp, if (enabled) PaperWhite else LineSoft, RoundedCornerShape(5.dp))
                        .background(if (enabled) PaperWhite else PanelGray, RoundedCornerShape(5.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { haptics.select(); enabled = !enabled }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(if (enabled) "ON" else "OFF", color = if (enabled) InkBlack else LabelGray, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    GhostButton("DELETE", {
                        haptics.error(); RoutineAlarmScheduler.delete(context, existing.id); onDismiss()
                    }, Modifier.weight(0.8f))
                }
                NeonButton("SAVE", {
                    if (!TIME_RE.matches(minutes.trim())) { onDismiss(); return@NeonButton }
                    val (h, m) = minutes.trim().split(":").map { it.toInt() }
                    if (enabled && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    RoutineAlarmScheduler.save(
                        context,
                        RoutineAlarmScheduler.RoutineAlarm(
                            id = alarmIdFor(block.title),
                            label = block.title,
                            minutesOfDay = h * 60 + m,
                            daysMask = maskFor(days),
                            enabled = enabled,
                        ),
                    )
                    haptics.success(); onDismiss()
                }, Modifier.weight(1f))
            }
        }
    }
}
