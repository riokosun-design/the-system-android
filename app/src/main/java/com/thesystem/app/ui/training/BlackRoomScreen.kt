package com.thesystem.app.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.BlackRoomEligibilityDto
import com.thesystem.app.data.model.BlackRoomProgramDto
import com.thesystem.app.data.model.CourseDto
import com.thesystem.app.data.repo.TrainingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject

/**
 * FORBIDDEN COURSE / BLACK ROOM.
 *
 * · Gate: level 50, ≥1 unlocked form, ≥5 historical missed/penalty events,
 *   primary muscle course ≥50%, 3 performance tracks completed — all live
 *   answers from `black_room_eligibility()`.
 * · Price: server-computed (₹199–599 India/low-price regions, $5–19 tier-1
 *   international). Never hardcoded in the client.
 * · APPLY: manual UPI rail — UTR + screenshot → Super Admin review.
 * · Approved hunters receive a program the admin built row by row.
 */
data class BlackRoomState(
    val loading: Boolean = true,
    val course: CourseDto? = null,
    val eligibility: BlackRoomEligibilityDto? = null,
    val program: List<BlackRoomProgramDto> = emptyList(),
    val utr: String = "",
    val screenshotPath: String? = null,
    val submitting: Boolean = false,
    val applied: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class BlackRoomViewModel @Inject constructor(
    private val training: TrainingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BlackRoomState())
    val state: StateFlow<BlackRoomState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val course = training.courses("FORBIDDEN").firstOrNull()
        val elig = training.blackRoomEligibility()
        val program = if (elig?.eligible == true) training.blackRoomProgram() else emptyList()
        _state.value = _state.value.copy(loading = false, course = course, eligibility = elig, program = program)
    }

    fun setUtr(v: String) { _state.value = _state.value.copy(utr = v) }
    fun setScreenshot(path: String?) { _state.value = _state.value.copy(screenshotPath = path) }

    fun apply() = viewModelScope.launch {
        val s = _state.value
        if (s.utr.trim().length < 8) {
            _state.value = s.copy(error = "Enter the UPI reference (UTR) — at least 8 characters.")
            return@launch
        }
        _state.value = s.copy(submitting = true, error = null)
        training.applyBlackRoom(s.utr.trim(), s.screenshotPath)
            .onSuccess {
                _state.value = _state.value.copy(
                    submitting = false, applied = true,
                    notice = "Application filed. The Super Admin reviews it and builds your program.",
                )
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    submitting = false,
                    error = when {
                        e.message?.contains("not_eligible") == true -> "The gate is not satisfied yet."
                        e.message?.contains("bad_utr") == true -> "That UTR looks too short."
                        else -> "Application failed: ${e.message}"
                    },
                )
            }
    }

    fun claimNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}

@Composable
fun BlackRoomScreen(onBack: () -> Unit, vm: BlackRoomViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val snack = remember { androidx.compose.material3.SnackbarHostState() }

    LaunchedEffect(s.notice, s.error) {
        s.error?.let { snack.showSnackbar(it); vm.claimNotice() }
        s.notice?.let { snack.showSnackbar(it); vm.claimNotice() }
    }

    SystemBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin),
                verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                contentPadding = PaddingValues(vertical = Grid.S16),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GhostButton("BACK", onBack)
                        Spacer(Modifier.width(Grid.S12))
                        SectionTitle("BLACK ROOM")
                    }
                }

                // ── hero plate ────────────────────────────────────────────────
                item {
                    Box(
                        Modifier
                            .fillMaxWidth().height(190.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PanelGray)
                            .border(1.dp, SkyBlue.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    ) {
                        AsyncImage(
                            model = s.course?.cover ?: "file:///android_asset/courses/black-room.webp",
                            contentDescription = "Black Room",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f,
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(Grid.S16)) {
                            Text("FORBIDDEN COURSE", style = MonoLabel, color = SkyBlue)
                            Text("BLACK ROOM", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                            Text(
                                "Two-year bespoke protocol · renewal every 6 months",
                                style = MaterialTheme.typography.bodySmall, color = LabelGray,
                            )
                        }
                    }
                }

                // ── gate ─────────────────────────────────────────────────────
                item {
                    GlowCard {
                        SectionTitle("ELIGIBILITY GATE", SkyBlue)
                        Spacer(Modifier.height(Grid.S8))
                        val checks = s.eligibility?.checks
                        GateRow("Level 50", checks?.get("level_50")?.bool())
                        GateRow("At least 1 unlocked form", checks?.get("form_unlocked")?.bool())
                        GateRow("≥5 historical missed / penalty events", checks?.get("penalty_events_min5")?.bool())
                        GateRow("Primary muscle course ≥ 50%", checks?.get("muscle_progress_ok")?.bool())
                        GateRow("3 performance tracks completed", checks?.get("specials_completed_ok")?.bool())
                        Spacer(Modifier.height(Grid.S8))
                        Text(
                            "Ledger: ${checks?.get("missed_days")?.int() ?: 0} missed days · " +
                                "${checks?.get("specials_completed")?.int() ?: 0} tracks completed · " +
                                "muscle ${checks?.get("muscle_percent")?.toString()?.trim('\"') ?: "0"}%",
                            style = MaterialTheme.typography.bodySmall, color = LabelGray,
                        )
                    }
                }

                // ── price + apply ────────────────────────────────────────────
                item {
                    GlowCard {
                        SectionTitle("PROGRAM FEE", PaperWhite)
                        Spacer(Modifier.height(4.dp))
                        val upi = s.eligibility?.upi
                        val usd = s.eligibility?.usd
                        Text(
                            if (upi != null) "₹%.0f".format(upi) else usd?.let { "$%.0f".format(it) } ?: "—",
                            color = PaperWhite, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 30.sp,
                        )
                        Text(
                            "Server-priced by your potential index. Manual UPI settlement: pay, then file the UTR below — " +
                                "an admin verifies and unlocks the room.",
                            style = MaterialTheme.typography.bodySmall, color = LabelGray,
                        )
                        Spacer(Modifier.height(Grid.S12))
                        if (s.eligibility?.eligible == true) {
                            OutlinedTextField(
                                value = s.utr,
                                onValueChange = vm::setUtr,
                                label = { Text("UPI REFERENCE / UTR", style = MonoLabel) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(Grid.S8))
                            NeonButton(
                                if (s.applied) "APPLICATION FILED" else if (s.submitting) "FILING…" else "APPLY TO THE BLACK ROOM",
                                { haptics.select(); vm.apply() },
                                Modifier.fillMaxWidth(),
                                color = SkyBlue,
                                enabled = !s.submitting && !s.applied,
                            )
                        } else {
                            RestrictionBanner(
                                "The gate is not satisfied. The Black Room opens only after the full ledger is earned.",
                                SkyBlue,
                            )
                        }
                    }
                }

                // ── personalized program (only when approved) ───────────────
                if (s.program.isNotEmpty()) {
                    item { SectionTitle("YOUR PROGRAM", SkyBlue) }
                    items(s.program, key = { it.id }) { row ->
                        GlowCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "W${row.week + 1}·D${row.day + 1}", style = MonoLabel,
                                    color = LabelGray, modifier = Modifier.width(56.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(row.title, style = MaterialTheme.typography.titleMedium, color = PaperWhite)
                                    Text(
                                        "${row.sets}×${row.reps} · ${row.exercise} · rest ${row.restSec / 60}m",
                                        style = MaterialTheme.typography.bodySmall, color = LabelGray,
                                    )
                                    if (row.notes.isNotBlank()) {
                                        Text(row.notes, style = MaterialTheme.typography.bodySmall, color = FaintGray)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
            androidx.compose.material3.SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun GateRow(label: String, pass: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            if (pass == true) "▲" else if (pass == false) "▼" else "·",
            color = if (pass == true) PaperWhite else LabelGray, fontSize = 12.sp, modifier = Modifier.width(20.dp),
        )
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (pass == true) PaperWhite else LabelGray,
            modifier = Modifier.weight(1f))
        Text(
            if (pass == true) "PASS" else if (pass == false) "LOCKED" else "…",
            style = MonoLabel, color = if (pass == true) SkyBlue else FaintGray,
        )
    }
}

private fun kotlinx.serialization.json.JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun kotlinx.serialization.json.JsonElement?.int(): Int? =
    (this as? JsonPrimitive)?.intOrNull ?: (this as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
