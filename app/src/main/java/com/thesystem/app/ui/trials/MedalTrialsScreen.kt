package com.thesystem.app.ui.trials

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.thesystem.app.data.gateway.PaymentGateways
import com.thesystem.app.data.model.MedalEnrollmentDto
import com.thesystem.app.data.model.MedalTrialDto
import com.thesystem.app.data.repo.CommerceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §2 v1.0 — MEDAL TRIALS: 30/60-day ordeals. Enroll → Razorpay settles the
 * order → the clock starts. 100% completion (server-judged) unlocks the
 * shipping form → physical laser-engraved dog-tag + digital certificate.
 */
data class TrialsState(
    val loading: Boolean = true,
    val trials: List<MedalTrialDto> = emptyList(),
    val mine: List<MedalEnrollmentDto> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
    val claimingFor: MedalEnrollmentDto? = null,
)

@HiltViewModel
class MedalTrialsViewModel @Inject constructor(
    private val commerce: CommerceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrialsState())
    val state: StateFlow<TrialsState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(
            loading = false,
            trials = commerce.medalTrials(),
            mine = commerce.myMedalEnrollments(),
        )
    }

    /** Mints the PENDING_PAYMENT order server-side, then hands the reference
     *  to the payment gateway seam (offline → honest pending notice). */
    fun enroll(t: MedalTrialDto) = viewModelScope.launch {
        _state.value = _state.value.copy(notice = null, error = null)
        commerce.medalTrialEnroll(t.id)
            .onSuccess { order ->
                if (!order.ok) { _state.value = _state.value.copy(error = order.err ?: "order_failed"); return@onSuccess }
                val gw = PaymentGateways.current
                if (!gw.isAvailable()) {
                    _state.value = _state.value.copy(
                        notice = "ORDER ${order.orderRef} MINTED — ₹${order.amountInr}. " +
                            PaymentGateways.OFFLINE_NOTICE,
                    )
                    refresh()
                } else {
                    // live rail: Razorpay checkout intent would fire here
                    _state.value = _state.value.copy(notice = "ORDER ${order.orderRef} — checkout opening…")
                }
            }
            .onFailure { _state.value = _state.value.copy(error = it.message?.take(120)) }
    }

    fun openClaim(e: MedalEnrollmentDto) { _state.value = _state.value.copy(claimingFor = e) }
    fun closeClaim() { _state.value = _state.value.copy(claimingFor = null) }

    fun claim(e: MedalEnrollmentDto, name: String, address: String, phone: String) = viewModelScope.launch {
        val shipping = """{"name":${name.jsonq()},"address":${address.jsonq()},"phone":${phone.jsonq()}}"""
        commerce.medalTrialClaimCompletion(e.id, shipping)
            .onSuccess { medal ->
                _state.value = _state.value.copy(claimingFor = null, notice = "TRIAL COMPLETE — $medal IS YOURS. The dog-tag ships to the address given.")
                refresh()
            }
            .onFailure {
                _state.value = _state.value.copy(claimingFor = null, error = when (it.message) {
                    "TRIAL_INCOMPLETE" -> "The System judges: the trial is NOT at 100%."
                    else -> it.message?.take(120)
                })
            }
    }

    private fun String.jsonq(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}

@Composable
fun MedalTrialsScreen(nav: NavHostController, vm: MedalTrialsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("MEDAL TRIALS", style = MaterialTheme.typography.headlineMedium, color = PaperWhite)
        Text("FINISH AT 100% · THE DOG-TAG SHIPS", style = MonoLabel, color = HunterGold)
        Spacer(Modifier.height(16.dp))

        s.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VenomGreen); Spacer(Modifier.height(10.dp)) }
        s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CrimsonRed); Spacer(Modifier.height(8.dp)) }

        // ── my trials ──────────────────────────────────────────────────────
        if (s.mine.isNotEmpty()) {
            Text("MY TRIALS", style = MonoLabel, color = SkyBlue)
            Spacer(Modifier.height(8.dp))
            s.mine.forEach { e ->
                val trial = s.trials.firstOrNull { it.id == e.trialId }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .border(0.8.dp, if (e.status == "COMPLETED") HunterGold else Color(0xFF22313D), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                        .padding(12.dp),
                ) {
                    Text(e.trialId, color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        when (e.status) {
                            "PENDING_PAYMENT" -> "ORDER ${e.orderRef} · AWAITING SETTLEMENT"
                            "ACTIVE" -> "ACTIVE · ${e.repsDone}/${trial?.targetReps ?: "?"} reps · ${e.kmDone}/${trial?.targetKm ?: "?"}km"
                            "COMPLETED" -> "COMPLETED — MEDAL EARNED"
                            else -> e.status
                        },
                        style = MonoLabel,
                        color = when (e.status) { "COMPLETED" -> HunterGold; "ACTIVE" -> VenomGreen; else -> WarningAmber },
                    )
                    if (e.status == "ACTIVE" && trial != null &&
                        e.repsDone >= trial.targetReps && e.kmDone >= trial.targetKm) {
                        Spacer(Modifier.height(8.dp))
                        NeonButton("CLAIM MEDAL — SHIPPING FORM", { vm.openClaim(e) }, Modifier.fillMaxWidth(), color = HunterGold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── the shelf ──────────────────────────────────────────────────────
        Text("OPEN TRIALS", style = MonoLabel, color = SkyBlue)
        Spacer(Modifier.height(8.dp))
        s.trials.forEach { t ->
            val mine = s.mine.firstOrNull { it.trialId == t.id && (it.status == "ACTIVE" || it.status == "PENDING_PAYMENT") }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .border(0.8.dp, Color(0xFF22313D), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${t.days} DAYS · ${t.targetReps} REPS · ${t.targetKm}KM", style = MonoLabel, color = LabelGray)
                        Text("→ ${t.medalName}", style = MonoLabel, color = HunterGold)
                    }
                    Text("₹${t.priceInr}", color = PaperWhite, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
                NeonButton(
                    when (mine?.status) {
                        "ACTIVE" -> "TRIAL IN MOTION"
                        "PENDING_PAYMENT" -> "ORDER PENDING — ${mine.orderRef}"
                        else -> "ENROLL — ₹${t.priceInr}"
                    },
                    { if (mine == null) vm.enroll(t) },
                    Modifier.fillMaxWidth(),
                    color = if (mine == null) SkyBlue else LabelGray,
                    enabled = mine == null,
                )
            }
        }
        Spacer(Modifier.height(80.dp))
    }

    // ── shipping form (100% completion only, server-judged) ────────────────
    s.claimingFor?.let { e ->
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.closeClaim() },
            containerColor = SurfaceHigh,
            title = { Text("MEDAL SHIPPING", color = HunterGold, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    @Composable fun f(v: String, on: (String) -> Unit, label: String) =
                        OutlinedTextField(
                            v, on, label = { Text(label) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                        )
                    f(name, { name = it.take(60) }, "Full name")
                    Spacer(Modifier.height(6.dp))
                    f(address, { address = it.take(240) }, "Full shipping address")
                    Spacer(Modifier.height(6.dp))
                    f(phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(16) }, "Phone")
                }
            },
            confirmButton = {
                NeonButton("SUBMIT", {
                    vm.claim(e, name.trim(), address.trim(), phone.trim())
                }, enabled = name.isNotBlank() && address.length >= 10 && phone.length >= 8, color = HunterGold)
            },
            dismissButton = { TextButton(onClick = { vm.closeClaim() }) { Text("CANCEL", color = LabelGray) } },
        )
    }
}
