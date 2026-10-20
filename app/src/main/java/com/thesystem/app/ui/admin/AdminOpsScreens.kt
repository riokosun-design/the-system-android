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
/** 0.22.0 → 0.24.0 — OPS COMMAND state: metrics, support inbox, roles.
 *  §4: finance queues & monetization config removed from the client. */
data class AdminOpsState(
    val loading: Boolean = true,
    val metrics: JsonObject? = null,
    val metricsDenied: Boolean = false,
    val audit: List<AdminAuditDto> = emptyList(),
    val supportQueue: List<SupportConversationDto> = emptyList(),
    val supportFilter: String = "WAITING_FOR_SUPPORT",
    val activeConv: String? = null,
    val activeMessages: List<SupportMessageDto> = emptyList(),
    val perms: List<AdminPermDto> = emptyList(),
    val userQuery: String = "",
    val userResults: List<UserDto> = emptyList(),
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
        val sq = async { admin.supportQueue(_state.value.supportFilter) }
        val p = async { admin.adminPerms() }
        val a = async { admin.auditRecent() }
        _state.value = _state.value.copy(
            loading = false, metrics = m, metricsDenied = m == null,
            supportQueue = sq.await(), perms = p.await(), audit = a.await(),
        )
    }

    // §4 (v0.24.0): orders / withdrawals / monetization-config client rails are
    // REMOVED. Server tables & audited functions remain for financial history.

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
        item { SectionTitle("Money Rail — ARCHIVE (manual rail removed 0.24.0)") }
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
// §4 (v0.24.0): ORDERS & WITHDRAWALS queues — REMOVED. The manual money rail
// is dead in the client; immutable history stays on the server (mig 033).
// ════════════════════════════════════════════════════════════════════════════

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
