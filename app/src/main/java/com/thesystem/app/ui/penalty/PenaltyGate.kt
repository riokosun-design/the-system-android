package com.thesystem.app.ui.penalty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.ui.GhostButton
import com.thesystem.app.core.ui.NeonButton
import com.thesystem.app.data.model.QuestDto
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import javax.inject.Inject

/**
 * §8 — PENALTY ZONE: the whole app is under arrest.
 *
 * The server is the judge (sync_inactivity + penalty_status): missed days
 * decay XP, reset the streak, demote the rank — and mint a camera-verified
 * SURVIVAL TRIAL. While the trial stands, normal tabs do not exist; this
 * crimson gate is the entire UI. Clearing every trial rep flips the flags
 * server-side and the app falls open again. Admins never see this screen.
 */
data class PenaltyVerdict(
    val state: String = "CLEAR",          // CLEAR | WARNING | DECAY | GARBAGE | LOSER | LOCKDOWN
    val missed: Int = 0,
    val tier: Int = 0,                    // 1, 3, 7 — the brutality bands
    val trialActive: Boolean = false,
    val lockdown: Boolean = false,
    val xpLost: Long = 0,
)

data class PenaltyGateState(
    val loading: Boolean = true,
    val verdict: PenaltyVerdict = PenaltyVerdict(),
    val trials: List<QuestDto> = emptyList(),
    val cleared: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PenaltyGateViewModel @Inject constructor(
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PenaltyGateState())
    val state: StateFlow<PenaltyGateState> = _state

    init { refresh() }

    fun refresh(): kotlinx.coroutines.Job = viewModelScope.launch {
        val statusD = async { system.penaltyStatus() }
        val questsD = async { system.dailyQuests() }
        val verdict = parseVerdict(statusD.await())
        val today = LocalDate.now().toString()
        val trials = questsD.await()
            .filter { it.questDate == today && it.source == TRIAL_SOURCE }
            .sortedBy { it.seq }

        // server law: progress met → close the quest so the trial ledger moves
        val unclosed = trials.filter { !it.completed && it.progress >= it.targetValue }
        if (unclosed.isNotEmpty()) {
            unclosed.forEach { runCatching { system.completeQuest(it.id) } }
            refresh()
            return@launch
        }

        val remaining = trials.count { !it.completed }
        _state.value = PenaltyGateState(
            loading = false,
            verdict = verdict,
            trials = trials,
            cleared = !verdict.trialActive || (trials.isNotEmpty() && remaining == 0),
        )
    }

    private fun parseVerdict(j: JsonObject?): PenaltyVerdict = PenaltyVerdict(
        state = j?.get("state")?.jsonPrimitive?.content ?: "CLEAR",
        missed = j?.get("missed")?.jsonPrimitive?.intOrNull ?: 0,
        tier = j?.get("tier")?.jsonPrimitive?.intOrNull ?: 0,
        trialActive = j?.get("trial_active")?.jsonPrimitive?.booleanOrNull ?: false,
        lockdown = j?.get("lockdown")?.jsonPrimitive?.booleanOrNull ?: false,
        xpLost = j?.get("xp_lost")?.jsonPrimitive?.longOrNull ?: 0,
    )

    companion object { const val TRIAL_SOURCE = "PENALTY_TRIAL" }
}

val PenaltyRed = Color(0xFFFF1744)
val PenaltyDim = Color(0x33FF1744)

@Composable
fun PenaltyGateScreen(nav: NavHostController, onLifted: () -> Unit, vm: PenaltyGateViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    // returning from the execution chamber → re-read the server verdict
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    if (s.cleared && !s.loading) {
        // the flags are down — one tap returns the hunter to the living app
        PenaltyClearedLayout(onLifted)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        Text(
            "[ ! ] THE PENALTY ZONE [ ! ]",
            color = PenaltyRed, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                s.verdict.lockdown ->
                    "[ SYSTEM LOCKDOWN & STAT CORRUPTION: ${s.verdict.missed} consecutive missed days. " +
                        "Your XP was driven to the floor of its tier. Arena, chat and rewards are " +
                        "sealed. Only the Redemption Trial reopens the System. ]"
                s.verdict.tier >= 3 ->
                    "[ HUNTER RANK DEMOTION: ${s.verdict.missed} consecutive missed days. " +
                        "Heavy XP decay has dragged your level down — your rank followed. " +
                        "The System has no sympathy for absence. ]"
                else ->
                    "[ PENALTY DECLARED: you failed to report for duty yesterday. " +
                        "Streak reset to zero. XP decay applied. The survival trial below is " +
                        "the only door out. ]"
            },
            color = Color(0xFFD8D8D8), fontSize = 12.5.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PenaltyStat("DAYS MISSED", "${s.verdict.missed}", Modifier.weight(1f))
            PenaltyStat("XP LOST", "−${s.verdict.xpLost}", Modifier.weight(1f))
            PenaltyStat("STATE", s.verdict.state, Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))

        Text(
            "SURVIVAL TRIAL — CAMERA-VERIFIED, ZERO MERCY",
            color = PenaltyRed, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            if (s.trials.isEmpty()) {
                item {
                    Text(
                        if (s.loading) "Summoning your sentence…" else "No trial rows yet — re-syncing.",
                        color = Color(0xFF888888), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                if (!s.loading) item { GhostButton("RE-SYNC", { vm.refresh() }, Modifier.fillMaxWidth()) }
            } else {
                items(s.trials, key = { it.id }) { q ->
                    TrialRow(q) { nav.navigate(Routes.questProof(q)) }
                }
                item { GhostButton("RE-SYNC STATUS", { vm.refresh() }, Modifier.fillMaxWidth()) }
            }
        }

        Text(
            when {
                s.verdict.lockdown -> "Social media stays locked. The System does not negotiate."
                else -> "Complete every trial rep. The seal lifts the moment the ledger clears."
            },
            color = Color(0xFF666666), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PenaltyStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.border(1.dp, PenaltyDim).background(Color(0x12FF1744)).padding(10.dp),
    ) {
        Text(label, color = Color(0xFF9A9A9A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        Text(value, color = PenaltyRed, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TrialRow(q: QuestDto, onExecute: () -> Unit) {
    val done = q.completed || q.status == "COMPLETE"
    val pct = if (q.targetValue > 0) (q.progress.toFloat() / q.targetValue).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (done) Color(0xFF22D3EE) else PenaltyDim, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(Color(0x0CFF1744))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    q.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${q.progress}/${q.targetValue} ${q.targetUnit} · verified by camera only",
                    color = Color(0xFFAAAAAA), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                )
            }
            when {
                done -> Text("[ CLEARED ]", color = Color(0xFF22D3EE), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                else -> NeonButton("EXECUTE", onExecute, color = PenaltyRed)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF2A0509))) {
            Box(Modifier.fillMaxWidth(pct).height(3.dp).background(PenaltyRed))
        }
    }
}

@Composable
private fun PenaltyClearedLayout(onLifted: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding().background(Color.Black).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "[ TRIAL COMPLETE — PENALTY LIFTED ]",
            color = Color(0xFF22D3EE), fontSize = 15.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "The debt is paid in sweat, on camera, with witnesses in silicon. " +
                "Tomorrow the System expects you before it has to ask.",
            color = Color(0xFFB5B5B5), fontSize = 12.5.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(20.dp))
        NeonButton("RETURN TO THE SYSTEM", onLifted, Modifier.fillMaxWidth(), color = Color(0xFF22D3EE))
    }
}
