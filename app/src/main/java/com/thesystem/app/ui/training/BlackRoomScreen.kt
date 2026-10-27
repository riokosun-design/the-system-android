package com.thesystem.app.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
 * · §4 (v0.24.0): the manual reference-number payment rail is REMOVED.
 *   Eligibility you earned is banked forever; enrollment reopens through the
 *   official Razorpay gateway. Approved hunters keep their program below.
 */
data class BlackRoomState(
    val loading: Boolean = true,
    val course: CourseDto? = null,
    val eligibility: BlackRoomEligibilityDto? = null,
    val program: List<BlackRoomProgramDto> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class BlackRoomViewModel @Inject constructor(
    private val training: TrainingRepository,
    private val system: com.thesystem.app.data.repo.SystemRepository,
) : ViewModel() {

    /** STEP 10: the hunter's chosen rail (NULL country = India default, honest). */
    private var country: String? = null

    private val _state = MutableStateFlow(BlackRoomState())
    val state: StateFlow<BlackRoomState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val course = training.courses("FORBIDDEN").firstOrNull()
        country = system.profile()?.country
        val elig = training.blackRoomEligibility(country ?: "IN")
        val program = if (elig?.eligible == true) training.blackRoomProgram() else emptyList()
        _state.value = _state.value.copy(loading = false, course = course, eligibility = elig, program = program)
    }

    fun claimNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}

@Composable
fun BlackRoomScreen(onBack: () -> Unit, vm: BlackRoomViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { androidx.compose.material3.SnackbarHostState() }

    LaunchedEffect(s.notice, s.error) {
        s.error?.let { snack.showSnackbar(it); vm.claimNotice() }
        s.notice?.let { snack.showSnackbar(it); vm.claimNotice() }
    }

    SystemBackground {
        Box(Modifier
                .fillMaxSize()
                .navigationBarsPadding()) {
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
                            model = s.course?.cover ?: "file:///android_asset/courses/the-black-room.webp",
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
                        // REASON LAW (mig 024) — exact gaps, in the server's own words
                        s.eligibility?.reasons?.filter { it.contains("MISSING") || it.contains("MET (") }?.forEach { r ->
                            Text(r, style = MonoLabel, color = if (r.contains("MISSING")) PaperWhite else FaintGray)
                        }
                        Spacer(Modifier.height(Grid.S8))
                        Text(
                            "Ledger: ${checks?.get("missed_days")?.int() ?: 0} missed days · " +
                                "${checks?.get("specials_completed")?.int() ?: 0} tracks completed · " +
                                "muscle ${checks?.get("muscle_percent")?.toString()?.trim('\"') ?: "0"}%",
                            style = MaterialTheme.typography.bodySmall, color = LabelGray,
                        )
                    }
                }

                // ── enrollment status — §4: manual rail removed, Razorpay pending ──
                item {
                    GlowCard {
                        SectionTitle("ENROLLMENT", PaperWhite)
                        Spacer(Modifier.height(Grid.S8))
                        if (s.eligibility?.eligible == true) {
                            Text(
                                "GATE SATISFIED — your eligibility is banked forever.",
                                style = MonoLabel, color = SkyBlue,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            com.thesystem.app.data.gateway.PaymentGateways.OFFLINE_NOTICE,
                            style = MaterialTheme.typography.bodySmall, color = LabelGray, lineHeight = 18.sp,
                        )
                        if (s.eligibility?.eligible != true) {
                            Spacer(Modifier.height(Grid.S8))
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
