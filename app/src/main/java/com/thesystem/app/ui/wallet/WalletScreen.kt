package com.thesystem.app.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.thesystem.app.data.model.VcTxnDto
import com.thesystem.app.data.repo.CommerceRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * VAULT CREDITS (v0.24.0, spec §4).
 *
 * The manual personal-payment rail is dead — no QR codes, no reference
 * numbers, no screenshot uploads, anywhere. This screen is now a clean vault:
 * the server-stamped VC balance, held funds, and the immutable ledger.
 * Money movement returns ONLY through the official Razorpay gateway
 * ([PaymentGateways]) once its wiring ships.
 */
data class WalletState(
    val loading: Boolean = true,
    val vcBalance: Long = 0,
    val vcHeld: Long = 0,
    val ledger: List<VcTxnDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val commerce: CommerceRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val me = system.profile()
        _state.value = WalletState(
            loading = false,
            vcBalance = me?.vcBalance ?: 0,
            vcHeld = me?.vcHeld ?: 0,
            ledger = commerce.walletLedger(),
            error = if (me == null) "OFFLINE — balances need the grid." else null,
        )
    }
}

@Composable
fun WalletScreen(nav: NavHostController, vm: WalletViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()

    SystemBackground {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Grid.Margin),
            verticalArrangement = Arrangement.spacedBy(Grid.S16),
            contentPadding = PaddingValues(vertical = Grid.S16),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("BACK", { haptics.tick(); nav.popBackStack() })
                    Spacer(Modifier.weight(1f))
                    Text("VAULT CREDITS", style = MonoLabel, color = SkyBlue)
                }
            }

            // ── balances — read-only, the server is the only authority ────
            item {
                GlowCard {
                    Text("VAULT BALANCE", style = MonoLabel, color = LabelGray)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%,d VC".format(java.util.Locale.US, s.vcBalance),
                        color = PaperWhite, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, fontFamily = SystemMono,
                    )
                    if (s.vcHeld > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "HELD: %,d VC".format(java.util.Locale.US, s.vcHeld),
                            style = MonoData, color = LabelGray,
                        )
                    }
                    s.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MonoLabel, color = FaintGray)
                    }
                }
            }

            // ── gateway seam — Razorpay arrives here, nothing else does ────
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, SkyBlue.copy(alpha = 0.45f), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                        .background(SkyBlueDim.copy(alpha = 0.4f))
                        .padding(14.dp),
                ) {
                    Text(
                        "[ GATEWAY ]",
                        color = SkyBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        fontFamily = SystemMono, letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        PaymentGateways.OFFLINE_NOTICE,
                        color = PaperWhite.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
            }

            // ── immutable ledger (read-only history — the record stands) ────
            item { SectionTitle("LEDGER — IMMUTABLE HISTORY", PaperWhite) }
            if (s.ledger.isEmpty()) {
                item {
                    Text(
                        if (s.loading) "Reading the vault…" else "No ledger entries yet. Earn VC in the arena and the arc.",
                        style = MonoLabel, color = FaintGray,
                    )
                }
            } else {
                items(s.ledger, key = { it.id }) { txn ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(LineSoft.copy(alpha = 0.22f), CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(txn.reason, color = PaperWhite, style = MaterialTheme.typography.titleSmall)
                            txn.reference?.let {
                                Text(it, style = MonoLabel, color = FaintGray, maxLines = 1)
                            }
                            Text(txn.createdAt?.take(16)?.replace('T', ' ') ?: "", style = MonoLabel, color = LabelGray)
                        }
                        Text(
                            (if (txn.amount >= 0) "+" else "") + "%,d".format(java.util.Locale.US, txn.amount),
                            color = if (txn.amount >= 0) SkyBlue else PaperWhite,
                            style = MonoData,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}
