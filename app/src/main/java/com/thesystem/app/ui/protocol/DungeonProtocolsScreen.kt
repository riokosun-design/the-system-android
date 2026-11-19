package com.thesystem.app.ui.protocol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.thesystem.app.data.model.DungeonProtocolDto
import com.thesystem.app.data.model.DungeonUnlockDto
import com.thesystem.app.data.repo.CommerceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §2 v1.0 — DUNGEON PROTOCOLS: goal-based DLC blueprints. One-time unlock
 * ₹149–₹299, PENDING_PAYMENT order minted server-side; the live Razorpay
 * webhook is the only settle path.
 */
data class DungeonState(
    val loading: Boolean = true,
    val protocols: List<DungeonProtocolDto> = emptyList(),
    val unlocks: List<DungeonUnlockDto> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DungeonProtocolsViewModel @Inject constructor(
    private val commerce: CommerceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DungeonState())
    val state: StateFlow<DungeonState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(
            loading = false,
            protocols = commerce.dungeonProtocols(),
            unlocks = commerce.myDungeonUnlocks(),
        )
    }

    fun unlock(p: DungeonProtocolDto) = viewModelScope.launch {
        _state.value = _state.value.copy(notice = null, error = null)
        commerce.dungeonUnlockRequest(p.id)
            .onSuccess { order ->
                if (!order.ok) { _state.value = _state.value.copy(error = order.err ?: "order_failed"); return@onSuccess }
                if (!PaymentGateways.current.isAvailable) {
                    _state.value = _state.value.copy(
                        notice = "ORDER ${order.orderRef} MINTED — ₹${order.amountInr}. " + PaymentGateways.OFFLINE_NOTICE,
                    )
                    refresh()
                } else {
                    _state.value = _state.value.copy(notice = "ORDER ${order.orderRef} — checkout opening…")
                }
            }
            .onFailure { _state.value = _state.value.copy(error = it.message?.take(120)) }
    }
}

@Composable
fun DungeonProtocolsScreen(nav: NavHostController, vm: DungeonProtocolsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("DUNGEON PROTOCOLS", style = MaterialTheme.typography.headlineMedium, color = PaperWhite)
        Text("GOAL-BUILT BLUEPRINTS · ONE-TIME UNLOCKS", style = MonoLabel, color = SkyBlue)
        Spacer(Modifier.height(16.dp))

        s.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VenomGreen); Spacer(Modifier.height(10.dp)) }
        s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CrimsonRed); Spacer(Modifier.height(8.dp)) }

        s.protocols.forEach { p ->
            val unlock = s.unlocks.firstOrNull { it.protocolId == p.id }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .border(0.8.dp, if (unlock?.status == "UNLOCKED") VenomGreen else Color(0xFF22313D), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${p.days} DAYS · ${p.focus}", style = MonoLabel, color = LabelGray)
                    }
                    Text("₹${p.priceInr}", color = PaperWhite, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
                NeonButton(
                    when (unlock?.status) {
                        "UNLOCKED" -> "PROTOCOL UNLOCKED ✓"
                        "PENDING_PAYMENT" -> "ORDER PENDING — ${unlock.orderRef}"
                        else -> "UNLOCK — ₹${p.priceInr}"
                    },
                    { if (unlock == null) vm.unlock(p) },
                    Modifier.fillMaxWidth(),
                    color = if (unlock == null) SkyBlue else LabelGray,
                    enabled = unlock == null,
                )
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}
