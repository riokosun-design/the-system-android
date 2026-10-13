package com.thesystem.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.*
import com.thesystem.app.data.repo.AdminRepository
import com.thesystem.app.data.repo.SocialRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/** 0.22.0 — OPS COMMAND state: metrics, finance queues, support inbox, config, roles. */
data class AdminOpsState(
    val loading: Boolean = true,
    val metrics: JsonObject? = null,
    val metricsDenied: Boolean = false,
    val audit: List<AdminAuditDto> = emptyList(),
    val orders: List<PaymentOrderDto> = emptyList(),
    val orderFilter: String = "SUBMITTED",
    val withdrawals: List<WithdrawalDto> = emptyList(),
    val wdFilter: String = "PENDING_REVIEW",
    val supportQueue: List<SupportConversationDto> = emptyList(),
    val supportFilter: String = "WAITING_FOR_SUPPORT",
    val activeConv: String? = null,
    val activeMessages: List<SupportMessageDto> = emptyList(),
    val config: JsonObject? = null,
    val perms: List<AdminPermDto> = emptyList(),
    val userQuery: String = "",
    val userResults: List<UserDto> = emptyList(),
    val proofUrl: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class AdminOpsViewModel @Inject constructor(
    private val admin: AdminRepository,
    private val system: SystemRepository,
    private val social: SocialRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminOpsState())
    val state: StateFlow<AdminOpsState> = _state

    init { refreshAll() }

    fun refreshAll() = viewModelScope.launch {
        val m = admin.opsMetrics()
        val o = async { admin.paymentOrdersQueue(_state.value.orderFilter) }
        val w = async { admin.withdrawalsQueue(_state.value.wdFilter) }
        val sq = async { admin.supportQueue(_state.value.supportFilter) }
        val c = async { admin.getConfig("monetization") }
        val p = async { admin.adminPerms() }
        val a = async { admin.auditRecent() }
        _state.value = _state.value.copy(
            loading = false, metrics = m, metricsDenied = m == null,
            orders = o.await(), withdrawals = w.await(), supportQueue = sq.await(),
            config = c.await(), perms = p.await(), audit = a.await(),
        )
    }

    // ── ORDERS ──
    fun setOrderFilter(f: String) = viewModelScope.launch {
        _state.value = _state.value.copy(orderFilter = f)
        _state.value = _state.value.copy(orders = admin.paymentOrdersQueue(f))
    }

    fun reviewOrder(order: PaymentOrderDto, approve: Boolean, note: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        admin.reviewVcOrder(order.id, approve, note).fold(
            onSuccess = {
                _state.value = _state.value.copy(
                    notice = if (approve) "CREDITED — ${order.vcAmount} VC landed in the hunter's wallet."
                    else "Order REJECTED — hunter notified.",
                )
                _state.value = _state.value.copy(orders = admin.paymentOrdersQueue(_state.value.orderFilter))
                refreshMetricsAudit()
            },
            onFailure = { _state.value = _state.value.copy(error = cleanErr(it.message)) },
        )
        _state.value = _state.value.copy(busy = false)
    }

    fun viewProof(path: String) = viewModelScope.launch {
        val url = admin.signedPaymentProofUrl(path)
        _state.value = url?.let { _state.value.copy(proofUrl = it) }
            ?: _state.value.copy(error = "Proof not found — it may have been removed from storage.")
    }

    fun closeProof() { _state.value = _state.value.copy(proofUrl = null) }

    // ── WITHDRAWALS ──
    fun setWdFilter(f: String) = viewModelScope.launch {
        _state.value = _state.value.copy(wdFilter = f)
        _state.value = _state.value.copy(withdrawals = admin.withdrawalsQueue(f))
    }

    fun reviewWithdrawal(w: WithdrawalDto, action: String, ref: String? = null, note: String? = null) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        admin.reviewWithdrawal(w.id, action, ref, note).fold(
            onSuccess = { newStatus ->
                _state.value = _state.value.copy(notice = "Withdrawal → $newStatus")
                _state.value = _state.value.copy(withdrawals = admin.withdrawalsQueue(_state.value.wdFilter))
                refreshMetricsAudit()
            },
            onFailure = { _state.value = _state.value.copy(error = cleanErr(it.message)) },
        )
        _state.value = _state.value.copy(busy = false)
    }

    // ── SUPPORT ──
    fun setSupportFilter(f: String) = viewModelScope.launch {
        _state.value = _state.value.copy(supportFilter = f)
        _state.value = _state.value.copy(supportQueue = admin.supportQueue(f))
    }

    fun openConversation(id: String) = viewModelScope.launch {
        _state.value = _state.value.copy(activeConv = id, activeMessages = system.supportMessages(id))
        system.supportMarkRead(id)
    }

    fun closeConversation() { _state.value = _state.value.copy(activeConv = null, activeMessages = emptyList()) }

    fun refreshActiveConv() = viewModelScope.launch {
        val id = _state.value.activeConv ?: return@launch
        _state.value = _state.value.copy(activeMessages = system.supportMessages(id))
    }

    fun supportReply(body: String) = viewModelScope.launch {
        val id = _state.value.activeConv ?: return@launch
        admin.supportReply(id, body.trim()).fold(
            onSuccess = {
                _state.value = _state.value.copy(activeMessages = system.supportMessages(id), notice = "Reply sent — hunter notified.")
                _state.value = _state.value.copy(supportQueue = admin.supportQueue(_state.value.supportFilter))
            },
            onFailure = { _state.value = _state.value.copy(error = cleanErr(it.message)) },
        )
    }

    fun supportStatus(status: String, reason: String = "") = viewModelScope.launch {
        val id = _state.value.activeConv ?: return@launch
        admin.supportSetStatus(id, status, reason).fold(
            onSuccess = {
                _state.value = _state.value.copy(notice = "Ticket → $status")
                closeConversation()
                _state.value = _state.value.copy(supportQueue = admin.supportQueue(_state.value.supportFilter))
            },
            onFailure = { _state.value = _state.value.copy(error = cleanErr(it.message)) },
        )
    }

    // ── CONFIG ──
    fun saveConfig(cfg: JsonObject) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        admin.setConfig("monetization", cfg).fold(
            onSuccess = {
                _state.value = _state.value.copy(notice = "MONETIZATION CONFIG SAVED — audited.", config = admin.getConfig("monetization"))
                refreshMetricsAudit()
            },
            onFailure = { _state.value = _state.value.copy(error = cleanErr(it.message)) },
        )
        _state.value = _state.value.copy(busy = false)
    }

    // ── ROLES ──
    fun onUserQuery(q: String) = viewModelScope.launch {
        _state.value = _state.value.copy(userQuery = q)
        _state.value = _state.value.copy(userResults = if (q.length >= 2) social.searchUsers(q) else emptyList())
    }

    fun grant(userId: String, perm: String) = viewModelScope.launch {
        admin.grantPerm(userId, perm).fold(
            onSuccess = { _state.value = _state.value.copy(notice = "$perm GRANTED — audited.", perms = admin.adminPerms()) },
            onFailure = { _state.value = _state.value.copy(error = cleanErr(it.message)) },
        )
    }

    fun revoke(userId: String, perm: String) = viewModelScope.launch {
        admin.revokePerm(userId, perm).fold(
            onSuccess = { _state.value = _state.value.copy(notice = "$perm REVOKED — audited.", perms = admin.adminPerms()) },
            onFailure = { _state.value = _state.value.copy(error = cleanErr(it.message)) },
        )
    }

    private fun refreshMetricsAudit() = viewModelScope.launch {
        _state.value = _state.value.copy(metrics = admin.opsMetrics() ?: _state.value.metrics, audit = admin.auditRecent())
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }

    private fun cleanErr(msg: String?): String = when {
        msg == null -> "Action failed."
        msg.contains("forbidden") || msg.contains("unauthorized") -> "DENIED — your admin role lacks this permission."
        msg.contains("bad_transition") -> "Stale state — this item was already handled. Refreshing."
        msg.contains("rejection_reason_required") -> "A reason is REQUIRED to reject."
        msg.contains("payout_ref_required") -> "Payout reference (UTR/txn id) is REQUIRED to mark PAID."
        msg.contains("already") || msg.contains("ux_payment_orders_utr") -> "This UTR is already linked to another order."
        msg.contains("resolution_reason_required") -> "Resolution reason is REQUIRED."
        else -> "Action failed: ${msg.take(120)}"
    }
}

// ════════════════════════════════════════════════════════════════════════════
// OPS TAB — live metrics grid + audit feed
// ════════════════════════════════════════════════════════════════════════════

private fun JsonObject?.num(key: String): Long? = this?.get(key)?.jsonPrimitive?.longOrNull

@Composable
fun OpsTab(ops: AdminOpsViewModel = hiltViewModel()) {
    val s by ops.state.collectAsStateWithLifecycle()
    val m = s.metrics
    if (s.metricsDenied && !s.loading) {
        Column(Modifier.padding(20.dp)) {
            Text("OPS METRICS — ACCESS CONTROLLED", style = MonoLabel, color = CrimsonRed)
            Spacer(Modifier.height(6.dp))
            Text(
                "Your admin role lacks the ANALYTICS permission. Ask a SUPER_ADMIN for a grant — every grant is audited.",
                color = LabelGray, style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
        item { SectionTitle("Hunters") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedStatTile("Total", m.num("users_total") ?: 0, PaperWhite, Modifier.weight(1f))
                AnimatedStatTile("New 7d", m.num("users_7d") ?: 0, SkyBlue, Modifier.weight(1f))
                AnimatedStatTile("New 30d", m.num("users_30d") ?: 0, ElectricBlue, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedStatTile("DAU", m.num("active_today") ?: 0, HunterGold, Modifier.weight(1f))
                AnimatedStatTile("WAU", m.num("active_7d") ?: 0, HunterGold, Modifier.weight(1f))
                AnimatedStatTile("MAU", m.num("active_30d") ?: 0, HunterGold, Modifier.weight(1f))
            }
        }
        item {
            Text(
                "ONLINE NOW: not collected — no heartbeat rail exists. Only honest sources above (users.updated_at).",
                style = MonoLabel, color = FaintGray,
            )
        }
        item { SectionTitle("Vault Economy") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedStatTile("VC Issued", m.num("vc_issued") ?: 0, ElectricBlue, Modifier.weight(1f))
                AnimatedStatTile("VC Spent", m.num("vc_spent") ?: 0, CrimsonRed, Modifier.weight(1f))
                AnimatedStatTile("Outstanding", m.num("vc_outstanding") ?: 0, PaperWhite, Modifier.weight(1f))
                AnimatedStatTile("Held", m.num("vc_held") ?: 0, HunterGold, Modifier.weight(1f))
            }
        }
        item { SectionTitle("Money Rail") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedStatTile("Orders Paid", m.num("orders_credited") ?: 0, ElectricBlue, Modifier.weight(1f))
                AnimatedStatTile("Gross ₹", m.num("orders_gross_inr") ?: 0, HunterGold, Modifier.weight(1f))
                AnimatedStatTile("Review Q", m.num("orders_pending") ?: 0, CrimsonRed, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedStatTile("WD Pending", m.num("withdrawals_pending") ?: 0, CrimsonRed, Modifier.weight(1f))
                AnimatedStatTile("WD ₹ Pend", m.num("withdrawals_pending_inr") ?: 0, HunterGold, Modifier.weight(1f))
                AnimatedStatTile("WD ₹ Paid", m.num("withdrawals_paid_inr") ?: 0, ElectricBlue, Modifier.weight(1f))
            }
        }
        item { SectionTitle("Arena + Support") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedStatTile("Tourneys", m.num("tournaments_active") ?: 0, NeonPurple, Modifier.weight(1f))
                AnimatedStatTile("Pools Open", m.num("predictions_unsettled") ?: 0, NeonPurple, Modifier.weight(1f))
                AnimatedStatTile("Tickets", m.num("support_open") ?: 0, SkyBlue, Modifier.weight(1f))
                AnimatedStatTile("Waiting", m.num("support_waiting") ?: 0, CrimsonRed, Modifier.weight(1f))
            }
        }
        if (s.audit.isNotEmpty()) {
            item { SectionTitle("Recent Decisions (audited)") }
            items(s.audit, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().background(LineSoft.copy(alpha = 0.25f), RoundedCornerShape(6.dp)).padding(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(a.action, style = MonoLabel, color = PaperWhite)
                        a.target?.let { Text("→ ${it.take(8)}…", style = MonoLabel, color = FaintGray) }
                    }
                    Text(a.createdAt?.take(16)?.replace('T', ' ') ?: "", style = MonoLabel, color = LabelGray)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// ORDERS TAB — VC purchase review queue (FINANCE perm, server-enforced)
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun OrdersTab(ops: AdminOpsViewModel = hiltViewModel()) {
    val s by ops.state.collectAsStateWithLifecycle()
    val filters = listOf("SUBMITTED", "CREDITED", "REJECTED", "EXPIRED")
    var rejectTarget by remember { mutableStateOf<PaymentOrderDto?>(null) }
    var approveTarget by remember { mutableStateOf<PaymentOrderDto?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { f ->
                FilterChip(selected = s.orderFilter == f, onClick = { ops.setOrderFilter(f) }, label = { Text(f) })
            }
        }
        if (s.orders.isEmpty()) EmptyState("No ${s.orderFilter} orders.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
            items(s.orders, key = { it.id }) { o ->
                OrderCard(
                    order = o,
                    busy = s.busy,
                    onProof = { o.screenshotPath?.let(ops::viewProof) },
                    onApprove = { approveTarget = o },
                    onReject = { rejectTarget = o },
                )
            }
        }
    }

    // approve confirm — server re-checks everything; client is just a trigger
    approveTarget?.let { o ->
        AlertDialog(
            onDismissRequest = { approveTarget = null },
            title = { Text("CREDIT ${o.vcAmount} VC?", color = PaperWhite) },
            text = {
                Text(
                    "Verify first:\n· UTR %s matches your bank/UPI statement\n· Amount ₹%.2f & timestamp line up\n\nThe server re-validates before the ledger moves. This is a REAL credit."
                        .format(java.util.Locale.US, o.upiUtr ?: "—", o.priceInr),
                    color = LabelGray,
                )
            },
            confirmButton = { TextButton(onClick = { ops.reviewOrder(o, approve = true, note = ""); approveTarget = null }) { Text("VERIFY & CREDIT", color = ElectricBlue) } },
            dismissButton = { TextButton(onClick = { approveTarget = null }) { Text("BACK", color = LabelGray) } },
        )
    }
    rejectTarget?.let { o ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectTarget = null },
            title = { Text("REJECT ${o.orderRef}?", color = PaperWhite) },
            text = {
                Column {
                    Text("Reason is REQUIRED and goes to the hunter.", color = LabelGray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it.take(200) },
                        placeholder = { Text("e.g. UTR not found in statement", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CrimsonRed, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (reason.isNotBlank()) { ops.reviewOrder(o, approve = false, note = reason.trim()); rejectTarget = null } },
                    enabled = reason.isNotBlank(),
                ) { Text("REJECT", color = CrimsonRed) }
            },
            dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("BACK", color = LabelGray) } },
        )
    }

    // proof viewer — the UNTRUSTED screenshot (evidence, never proof of settlement)
    s.proofUrl?.let { url ->
        Dialog(onDismissRequest = ops::closeProof) {
            Column(Modifier.background(VoidBlack, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text("SUBMITTED SCREENSHOT — EVIDENCE ONLY, NOT PROOF OF SETTLEMENT", style = MonoLabel, color = HunterGold)
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = url, contentDescription = "Payment proof",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = ops::closeProof, modifier = Modifier.align(Alignment.End)) { Text("CLOSE", color = LabelGray) }
            }
        }
    }
}

@Composable
private fun OrderCard(order: PaymentOrderDto, busy: Boolean, onProof: () -> Unit, onApprove: () -> Unit, onReject: () -> Unit) {
    val ocr = order.ocr
    Column(Modifier.fillMaxWidth().background(LineSoft.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(order.orderRef, color = PaperWhite, fontWeight = FontWeight.Bold, fontFamily = SystemMono, fontSize = 14.sp)
                Text("${order.vcAmount} VC · ₹%.2f".format(java.util.Locale.US, order.priceInr), style = MonoLabel, color = HunterGold)
            }
            Text(order.status, style = MonoLabel, color = statusColor(order.status))
        }
        Spacer(Modifier.height(6.dp))
        Text("UTR: ${order.upiUtr ?: "—"}", style = MonoLabel, color = LabelGray)
        Text("Filed: ${order.createdAt?.take(16)?.replace('T', ' ') ?: "—"}", style = MonoLabel, color = FaintGray)
        ocr?.let { hint ->
            val amt = hint["amount"]?.jsonPrimitive?.doubleOrNull
            val utr = hint["utr"]?.jsonPrimitive?.contentOrNull
            val status = hint["status"]?.jsonPrimitive?.contentOrNull
            val date = hint["date"]?.jsonPrimitive?.contentOrNull
            val time = hint["time"]?.jsonPrimitive?.contentOrNull
            Spacer(Modifier.height(4.dp))
            Text(
                "OCR HINT (untrusted): " + listOfNotNull(
                    amt?.let { "₹$it" }, utr?.let { "UTR≈$it" }, status,
                    listOfNotNull(date, time).joinToString(" ").ifBlank { null },
                ).joinToString(" · ").ifBlank { "no readable fields" },
                style = MonoLabel, color = FaintGray,
            )
        }
        if (order.status == "SUBMITTED") {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (order.screenshotPath != null) {
                    OutlinedButton(onClick = onProof, enabled = !busy) {
                        Icon(Icons.Default.Image, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("PROOF")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReject, enabled = !busy) { Text("REJECT", color = CrimsonRed) }
                TextButton(onClick = onApprove, enabled = !busy) { Text("VERIFY & CREDIT", color = ElectricBlue) }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// WITHDRAWALS TAB — held-balance payout queue (FINANCE perm, server-enforced)
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun WithdrawalsTab(ops: AdminOpsViewModel = hiltViewModel()) {
    val s by ops.state.collectAsStateWithLifecycle()
    val filters = listOf("PENDING_REVIEW", "APPROVED", "PROCESSING", "PAID", "REJECTED", "FAILED")
    var paidTarget by remember { mutableStateOf<WithdrawalDto?>(null) }
    var rejectTarget by remember { mutableStateOf<WithdrawalDto?>(null) }
    var approveTarget by remember { mutableStateOf<WithdrawalDto?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { f -> FilterChip(selected = s.wdFilter == f, onClick = { ops.setWdFilter(f) }, label = { Text(f) }) }
        }
        if (s.withdrawals.isEmpty()) EmptyState("No ${s.wdFilter} withdrawals.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
            items(s.withdrawals, key = { it.id }) { w ->
                WithdrawalCard(
                    w = w, busy = s.busy,
                    onApprove = { approveTarget = w },
                    onProcess = { ops.reviewWithdrawal(w, "process") },
                    onPaid = { paidTarget = w },
                    onReject = { rejectTarget = w },
                    onFail = { rejectTarget = w },
                )
            }
        }
    }

    // APPROVE — must NOT imply money moved (spec law)
    approveTarget?.let { w ->
        AlertDialog(
            onDismissRequest = { approveTarget = null },
            title = { Text("APPROVE REQUEST?", color = PaperWhite) },
            text = {
                Text(
                    "Approving only marks this request as OK-to-pay. NO MONEY HAS MOVED.\n\nNext: send ₹%.2f to %s from your own UPI app, then MARK PAID with the real UTR."
                        .format(java.util.Locale.US, w.payoutInr, w.maskedUpi),
                    color = LabelGray,
                )
            },
            confirmButton = { TextButton(onClick = { ops.reviewWithdrawal(w, "approve"); approveTarget = null }) { Text("APPROVE", color = ElectricBlue) } },
            dismissButton = { TextButton(onClick = { approveTarget = null }) { Text("BACK", color = LabelGray) } },
        )
    }
    paidTarget?.let { w ->
        var ref by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { paidTarget = null },
            title = { Text("MARK PAID — ₹%.2f".format(java.util.Locale.US, w.payoutInr), color = PaperWhite) },
            text = {
                Column {
                    Text("Enter the REAL UPI transaction reference (UTR) from YOUR payout. The hold is consumed — the VC leaves the system permanently.", color = LabelGray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ref, onValueChange = { ref = it.take(32) },
                        placeholder = { Text("Payout UTR / txn ref", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (ref.isNotBlank()) { ops.reviewWithdrawal(w, "paid", ref = ref.trim()); paidTarget = null } },
                    enabled = ref.isNotBlank(),
                ) { Text("CONFIRM PAID", color = HunterGold) }
            },
            dismissButton = { TextButton(onClick = { paidTarget = null }) { Text("BACK", color = LabelGray) } },
        )
    }
    rejectTarget?.let { w ->
        var reason by remember { mutableStateOf("") }
        val failing = w.status !in listOf("PENDING_REVIEW")
        AlertDialog(
            onDismissRequest = { rejectTarget = null },
            title = { Text(if (failing) "MARK FAILED?" else "REJECT WITHDRAWAL?", color = PaperWhite) },
            text = {
                Column {
                    Text("The held ${w.vcAmount} VC returns to the hunter's balance. Reason is REQUIRED and goes to the hunter.", color = LabelGray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it.take(200) },
                        placeholder = { Text("e.g. UPI handle rejected the transfer", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CrimsonRed, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (reason.isNotBlank()) { ops.reviewWithdrawal(w, if (failing) "fail" else "reject", note = reason.trim()); rejectTarget = null } },
                    enabled = reason.isNotBlank(),
                ) { Text(if (failing) "MARK FAILED" else "REJECT", color = CrimsonRed) }
            },
            dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("BACK", color = LabelGray) } },
        )
    }
}

@Composable
private fun WithdrawalCard(w: WithdrawalDto, busy: Boolean, onApprove: () -> Unit, onProcess: () -> Unit, onPaid: () -> Unit, onReject: () -> Unit, onFail: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(LineSoft.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("@${w.hunterLabel}", color = PaperWhite, fontWeight = FontWeight.Bold, fontFamily = SystemMono, fontSize = 14.sp)
                Text("${w.vcAmount} VC → ₹%.2f".format(java.util.Locale.US, w.payoutInr), style = MonoLabel, color = HunterGold)
            }
            Text(w.status, style = MonoLabel, color = statusColor(w.status))
        }
        Spacer(Modifier.height(6.dp))
        Text("UPI: ${w.maskedUpi}", style = MonoLabel, color = LabelGray)
        w.payoutRef?.let { Text("Paid ref: $it", style = MonoLabel, color = ElectricBlue) }
        w.reviewNote?.let { Text("Note: $it", style = MonoLabel, color = FaintGray) }
        Text("Filed: ${w.createdAt?.take(16)?.replace('T', ' ') ?: "—"}", style = MonoLabel, color = FaintGray)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            when (w.status) {
                "PENDING_REVIEW" -> {
                    TextButton(onClick = onReject, enabled = !busy) { Text("REJECT", color = CrimsonRed) }
                    TextButton(onClick = onApprove, enabled = !busy) { Text("APPROVE", color = ElectricBlue) }
                }
                "APPROVED" -> {
                    TextButton(onClick = onFail, enabled = !busy) { Text("FAILED", color = CrimsonRed) }
                    TextButton(onClick = onProcess, enabled = !busy) { Text("PROCESSING", color = LabelGray) }
                    TextButton(onClick = onPaid, enabled = !busy) { Text("MARK PAID", color = HunterGold) }
                }
                "PROCESSING" -> {
                    TextButton(onClick = onFail, enabled = !busy) { Text("FAILED", color = CrimsonRed) }
                    TextButton(onClick = onPaid, enabled = !busy) { Text("MARK PAID", color = HunterGold) }
                }
                else -> Unit // terminal states carry no actions
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SUPPORT TAB — admin side of the ticket rail (SUPPORT perm, server-enforced)
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun AdminSupportTab(ops: AdminOpsViewModel = hiltViewModel()) {
    val s by ops.state.collectAsStateWithLifecycle()
    val filters = listOf("WAITING_FOR_SUPPORT", "WAITING_FOR_USER", "")
    var resolveTarget by remember { mutableStateOf(false) }

    val activeId = s.activeConv
    if (activeId == null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { f -> FilterChip(selected = s.supportFilter == f, onClick = { ops.setSupportFilter(f) }, label = { Text(if (f.isBlank()) "ALL OPEN" else f) }) }
            }
            if (s.supportQueue.isEmpty()) EmptyState("Inbox zero. The System rests.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
                items(s.supportQueue, key = { it.id }) { c ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(LineSoft.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                            .clickable { ops.openConversation(c.id) }
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.subject ?: "(no subject)", color = PaperWhite, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(c.status, style = MonoLabel, color = statusColor(c.status))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("@${c.hunterLabel} · updated ${c.updatedAt?.take(16)?.replace('T', ' ') ?: "—"}", style = MonoLabel, color = LabelGray)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(onClick = ops::closeConversation) { Text("← BACK", color = LabelGray) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = ops::refreshActiveConv) { Text("REFRESH", color = LabelGray) }
                TextButton(onClick = { resolveTarget = true }) { Text("RESOLVE", color = ElectricBlue) }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(s.activeMessages, key = { it.id }) { m ->
                    val staff = m.staff
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (staff) Arrangement.End else Arrangement.Start) {
                        Column(
                            Modifier.widthIn(max = 300.dp)
                                .background(if (staff) androidx.compose.ui.graphics.Color(0xFF12324A) else androidx.compose.ui.graphics.Color(0xFF161A1E), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(if (staff) "YOU (STAFF)" else "HUNTER", style = MonoLabel, color = if (staff) SkyBlue else HunterGold)
                            Text(m.body, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                            Text(m.createdAt?.take(16)?.replace('T', ' ') ?: "", color = TextMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
                        }
                    }
                }
            }
            var reply by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = reply, onValueChange = { reply = it.take(1000) },
                    placeholder = { Text("Reply as The System…", color = TextMuted) },
                    modifier = Modifier.weight(1f), maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ElectricBlue, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { if (reply.isNotBlank()) { ops.supportReply(reply); reply = "" } }, enabled = reply.isNotBlank()) { Text("SEND", color = ElectricBlue) }
            }
        }
    }

    if (resolveTarget) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resolveTarget = false },
            title = { Text("RESOLVE TICKET?", color = PaperWhite) },
            text = {
                Column {
                    Text("Resolution reason is REQUIRED — it stays in the audit log.", color = LabelGray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it.take(200) },
                        placeholder = { Text("e.g. Refund issued via order reversal", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ElectricBlue, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (reason.isNotBlank()) { ops.supportStatus("RESOLVED", reason.trim()); resolveTarget = false } },
                    enabled = reason.isNotBlank(),
                ) { Text("RESOLVE", color = ElectricBlue) }
            },
            dismissButton = { TextButton(onClick = { resolveTarget = false }) { Text("BACK", color = LabelGray) } },
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
// CONFIG TAB — monetization knobs (FINANCE perm, server-enforced + audited)
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun ConfigTab(ops: AdminOpsViewModel = hiltViewModel()) {
    val s by ops.state.collectAsStateWithLifecycle()
    val cfg = s.config
    if (cfg == null) {
        Column(Modifier.padding(20.dp)) {
            Text("MONETIZATION CONFIG — " + if (s.loading) "LOADING…" else "ACCESS CONTROLLED", style = MonoLabel, color = if (s.loading) LabelGray else CrimsonRed)
            if (!s.loading) {
                Spacer(Modifier.height(6.dp))
                Text("Requires the FINANCE permission. Ask a SUPER_ADMIN — grants are audited.", color = LabelGray, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    var upiId by remember(cfg) { mutableStateOf(cfg["upi_id"]?.jsonPrimitive?.contentOrNull ?: "") }
    var payee by remember(cfg) { mutableStateOf(cfg["payee_name"]?.jsonPrimitive?.contentOrNull ?: "") }
    var instructions by remember(cfg) { mutableStateOf(cfg["instructions"]?.jsonPrimitive?.contentOrNull ?: "") }
    var minVc by remember(cfg) { mutableStateOf((cfg["withdrawal_min_vc"]?.jsonPrimitive?.longOrNull ?: 1000).toString()) }
    var maxVc by remember(cfg) { mutableStateOf((cfg["withdrawal_max_vc"]?.jsonPrimitive?.longOrNull ?: 100000).toString()) }
    var vcPerInr by remember(cfg) { mutableStateOf((cfg["vc_per_inr"]?.jsonPrimitive?.longOrNull ?: 2).toString()) }
    var expiry by remember(cfg) { mutableStateOf((cfg["order_expiry_min"]?.jsonPrimitive?.longOrNull ?: 30).toString()) }
    var purchaseOn by remember(cfg) { mutableStateOf(cfg["purchase_enabled"]?.jsonPrimitive?.booleanOrNull ?: true) }
    var withdrawOn by remember(cfg) { mutableStateOf(cfg["withdrawal_enabled"]?.jsonPrimitive?.booleanOrNull ?: true) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("MONETIZATION SWITCHBOARD", style = MonoLabel, color = HunterGold)
            Text("Every save is audited with old + new values. Client reads these live — server enforces them.", style = MonoLabel, color = FaintGray)
        }
        item { CfgField("DESTINATION UPI ID", upiId) { upiId = it } }
        item { CfgField("PAYEE NAME (shown in QR)", payee) { payee = it } }
        item { CfgField("WITHDRAWAL MIN VC", minVc) { minVc = it.filter(Char::isDigit).take(9) } }
        item { CfgField("WITHDRAWAL MAX VC", maxVc) { maxVc = it.filter(Char::isDigit).take(9) } }
        item { CfgField("VC PER ₹1 (display rate)", vcPerInr) { vcPerInr = it.filter(Char::isDigit).take(4) } }
        item { CfgField("ORDER EXPIRY (minutes)", expiry) { expiry = it.filter(Char::isDigit).take(4) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PURCHASES ENABLED", style = MonoLabel, color = PaperWhite, modifier = Modifier.weight(1f))
                Switch(checked = purchaseOn, onCheckedChange = { purchaseOn = it })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WITHDRAWALS ENABLED", style = MonoLabel, color = PaperWhite, modifier = Modifier.weight(1f))
                Switch(checked = withdrawOn, onCheckedChange = { withdrawOn = it })
            }
        }
        item { CfgField("PAYMENT INSTRUCTIONS (shown at order)", instructions, singleLine = false) { instructions = it.take(500) } }
        item {
            GhostButton("SAVE & AUDIT", {
                ops.saveConfig(buildJsonObject {
                    put("upi_id", upiId.trim())
                    put("payee_name", payee.trim().ifBlank { "THE SYSTEM" })
                    put("purchase_enabled", purchaseOn)
                    put("withdrawal_enabled", withdrawOn)
                    put("withdrawal_min_vc", minVc.toLongOrNull() ?: 1000)
                    put("withdrawal_max_vc", maxVc.toLongOrNull() ?: 100000)
                    put("vc_per_inr", vcPerInr.toLongOrNull() ?: 2)
                    put("order_expiry_min", expiry.toLongOrNull() ?: 30)
                    put("instructions", instructions.trim())
                })
            }, Modifier.fillMaxWidth())
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

@Composable
private fun CfgField(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
// ROLES TAB — permission grants (SUPER_ADMIN only, server-enforced + audited)
// ════════════════════════════════════════════════════════════════════════════

private val PERMS = listOf("FINANCE", "SUPPORT", "CONTENT", "TOURNAMENT", "ANALYTICS", "PEOPLE")

@Composable
fun RolesTab(ops: AdminOpsViewModel = hiltViewModel()) {
    val s by ops.state.collectAsStateWithLifecycle()
    val grouped = s.perms.groupBy { it.userId }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("PERMISSION MAP", style = MonoLabel, color = HunterGold)
            Text(
                "SUPER_ADMIN holds every permission implicitly. Grants/revokes below are server-enforced and audited. SUPER_ADMIN role itself can only be changed in the database — never from the app.",
                style = MonoLabel, color = FaintGray,
            )
        }
        item {
            OutlinedTextField(
                value = s.userQuery, onValueChange = ops::onUserQuery,
                placeholder = { Text("Search hunter by @username to grant…", color = TextMuted) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
            )
        }
        items(s.userResults, key = { "u-" + it.id }) { u ->
            Column(Modifier.fillMaxWidth().background(LineSoft.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text("@${u.username} — ${u.role}", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = SystemMono)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PERMS.forEach { p ->
                        OutlinedButton(onClick = { ops.grant(u.id, p) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("+$p", fontSize = 11.sp, color = ElectricBlue)
                        }
                    }
                }
            }
        }
        if (grouped.isNotEmpty()) {
            item { SectionTitle("Active Grants") }
            grouped.forEach { (uid, grants) ->
                item(key = "g-$uid") {
                    Column(Modifier.fillMaxWidth().background(LineSoft.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(10.dp)) {
                        Text(uid.take(8) + "…", style = MonoLabel, color = LabelGray)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            grants.forEach { g ->
                                OutlinedButton(onClick = { ops.revoke(g.userId, g.perm) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text(g.perm + " ✕", fontSize = 11.sp, color = CrimsonRed)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

// shared status→color rail
private fun statusColor(status: String) = when (status) {
    "CREDITED", "PAID", "RESOLVED" -> ElectricBlue
    "REJECTED", "FAILED", "EXPIRED" -> CrimsonRed
    "SUBMITTED", "PENDING_REVIEW", "WAITING_FOR_SUPPORT" -> HunterGold
    else -> LabelGray
}
