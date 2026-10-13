package com.thesystem.app.ui.wallet

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.thesystem.app.data.model.PaymentOrderDto
import com.thesystem.app.data.model.VcPackageDto
import com.thesystem.app.data.model.WithdrawalDto
import com.thesystem.app.data.repo.CommerceRepository
import com.thesystem.app.data.repo.SystemRepository
import com.thesystem.app.ui.chess.ChessCardLine
import com.thesystem.app.ui.chess.ChessCardShape
import com.thesystem.app.ui.chess.ChessCardSurface
import com.thesystem.app.ui.chess.ChessGridCard
import com.thesystem.app.ui.chess.ChessRowCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

data class WalletState(
    val loading: Boolean = true,
    val vcBalance: Long = 0,
    val vcHeld: Long = 0,
    val packages: List<VcPackageDto> = emptyList(),
    val orders: List<PaymentOrderDto> = emptyList(),
    val withdrawals: List<WithdrawalDto> = emptyList(),
    val cfg: JsonObject? = null,
    // buy-flow state
    val activeOrder: JsonObject? = null,
    val utr: String = "",
    val shotUri: String? = null,
    val ocr: PaymentOcrResult? = null,
    val orderSubmitted: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
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
        _state.value = _state.value.copy(
            loading = false,
            vcBalance = me?.vcBalance ?: 0,
            vcHeld = me?.vcHeld ?: 0,
            packages = commerce.vcPackages(),
            orders = commerce.myPaymentOrders(),
            withdrawals = commerce.myWithdrawals(),
            cfg = commerce.monetizationPublic(),
        )
    }

    fun cfgLong(k: String, d: Long) =
        _state.value.cfg?.get(k)?.jsonPrimitive?.longOrNull ?: d
    fun cfgFlag(k: String, d: Boolean) =
        _state.value.cfg?.get(k)?.jsonPrimitive?.contentOrNull?.let { it == "true" } ?: d

    fun buy(pkg: VcPackageDto) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, error = null)
        commerce.createVcOrder(pkg.id)
            .onSuccess { o ->
                _state.value = _state.value.copy(
                    busy = false, activeOrder = o, utr = "", shotUri = null, ocr = null, orderSubmitted = false,
                    notice = "ORDER ${o["order_ref"]?.jsonPrimitive?.contentOrNull} CREATED — pay the exact amount.",
                )
            }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
    }

    fun cancelOrderView() { _state.value = _state.value.copy(activeOrder = null) }
    fun onUtr(v: String) { _state.value = _state.value.copy(utr = v.uppercase().take(22)) }

    /** photo picked → ML Kit OCR hint (untrusted) pre-fills what it can. */
    fun onProofPicked(context: Context, uri: Uri) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true, shotUri = uri.toString())
        val scan = scanPaymentProof(context, uri)
        _state.value = _state.value.copy(
            busy = false, ocr = scan,
            utr = if (scan.utrCandidate != null) scan.utrCandidate!! else _state.value.utr,
        )
    }

    /** submit = upload proof → bind UTR → server VERIFY gates the credit. */
    fun submitProof(context: Context) = viewModelScope.launch {
        val s = _state.value
        val o = s.activeOrder ?: return@launch
        val orderId = o["order_id"]?.jsonPrimitive?.contentOrNull ?: return@launch
        val orderRef = o["order_ref"]?.jsonPrimitive?.contentOrNull ?: return@launch
        if (s.utr.isBlank() || s.utr.length < 8) {
            _state.value = _state.value.copy(error = "UTR / reference number required (8–22 chars).")
            return@launch
        }
        if (s.shotUri == null) {
            _state.value = _state.value.copy(error = "Payment screenshot required — upload the proof.")
            return@launch
        }
        _state.value = _state.value.copy(busy = true, error = null)
        val bytes = withContext(Dispatchers.IO) { compressImage(context, Uri.parse(s.shotUri)) }
        val path = bytes?.let { commerce.uploadPaymentProof(orderRef, it) }
        if (path == null) {
            _state.value = _state.value.copy(busy = false, error = "PROOF UPLOAD FAILED — retry on a stable connection.")
            return@launch
        }
        commerce.submitVcOrderProof(orderId, s.utr.trim(), path, PaymentOcrParser.toJson(s.ocr ?: PaymentOcrResult()))
            .onSuccess {
                _state.value = _state.value.copy(
                    busy = false, orderSubmitted = true,
                    notice = "PROOF LOCKED — verification queued. VC lands only on a VERIFIED decision.",
                )
                refresh()
            }
            .onFailure { e -> _state.value = _state.value.copy(busy = false, error = e.message) }
    }

    fun requestWithdrawal(name: String, upi: String, vc: Long) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, error = null)
        commerce.requestWithdrawal(vc, name.trim(), upi.trim())
            .onSuccess {
                _state.value = _state.value.copy(busy = false, notice = "WITHDRAWAL QUEUED — $vc VC is now held. Finance reviews it.")
                refresh()
            }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
    }

    fun clearNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}

private suspend fun compressImage(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val src = android.graphics.BitmapFactory.decodeStream(stream) ?: return@use null
            val scale = minOf(1f, 1024f / maxOf(src.width, src.height))
            val bmp = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    src, (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1), true,
                )
            } else src
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }.getOrNull()
}

private fun orderHelp(status: String): Pair<String, androidx.compose.ui.graphics.Color> = when (status) {
    "CREDITED", "VERIFIED" -> "VC CREDITED" to SkyBlue
    "REJECTED" -> "REJECTED" to PaperWhite
    "EXPIRED" -> "EXPIRED" to FaintGray
    "SUBMITTED", "VERIFYING", "MANUAL_REVIEW" -> "IN VERIFICATION" to PaperWhite
    else -> "AWAITING PAYMENT" to LabelGray
}

/** VC WALLET — buy via UPI QR (server-priced), held-balance withdrawals, history. */
@Composable
fun WalletScreen(nav: NavHostController, vm: WalletViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.notice, s.error) {
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.clearNotice() }
    }
    // while a proof sits in verification, gently poll for the verdict
    LaunchedEffect(s.orderSubmitted) {
        while (s.orderSubmitted) { delay(8_000); vm.refresh() }
    }
    val pickProof = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) vm.onProofPicked(context, uri) }

    SystemBackground(wallpaperAlpha = 0.10f) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin),
                verticalArrangement = Arrangement.spacedBy(Grid.S12),
                contentPadding = PaddingValues(vertical = Grid.S16),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FloatingIconButton(Icons.AutoMirrored.Filled.ArrowBack, "back") { nav.popBackStack() }
                        Spacer(Modifier.width(10.dp))
                        Text("VC WALLET", color = PaperWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                }

                // ── BALANCE ───────────────────────────────────────────────
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(ChessCardShape)
                            .background(ChessCardSurface)
                            .border(1.dp, ChessCardLine, ChessCardShape)
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountBalanceWallet, null, tint = SkyBlue, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${s.vcBalance}", color = PaperWhite, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                                Text("AVAILABLE VC", style = MonoLabel, color = SkyBlue)
                            }
                            if (s.vcHeld > 0) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${s.vcHeld}", color = PaperWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                                    Text("HELD", style = MonoLabel, color = LabelGray)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "200 VC = ₹100 display rate · VC is a training currency, not a cash promise.",
                            color = FaintGray, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }
                }

                // ── ACTIVE ORDER PANEL eats the buy grid once minted ───────
                if (s.activeOrder != null && !s.orderSubmitted) {
                    item { ActiveOrderPanel(s, vm, onPickProof = { pickProof.launch("image/*") }) }
                } else {
                    // ── BUY VC ───────────────────────────────────────────
                    item { SectionTitle("BUY VC — UPI QR", SkyBlue) }
                    if (s.orderSubmitted) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, ChessCardLine, ChessCardShape)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.CheckCircle, null, tint = SkyBlue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Proof submitted. Verification can take a short while — the bell rings when VC lands.",
                                    color = LabelGray, fontSize = 12.sp, lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                    items(s.packages.chunked(2)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.S12)) {
                            row.forEach { pkg ->
                                ChessGridCard(
                                    title = pkg.label,
                                    sub = "₹${pkg.priceInr.toInt()}",
                                    icon = Icons.Default.CurrencyRupee,
                                    modifier = Modifier.weight(1f),
                                    enabled = !s.busy,
                                ) { haptics.select(); vm.buy(pkg) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }

                    // ── WITHDRAW ──────────────────────────────────────────
                    item { SectionTitle("WITHDRAW — UPI", SkyBlue) }
                    item { WithdrawPanel(s, vm) }

                    // ── HISTORY ───────────────────────────────────────────
                    item { SectionTitle("ORDERS", SkyBlue) }
                    if (s.orders.isEmpty()) {
                        item { Text("No orders yet.", color = LabelGray, fontSize = 12.sp) }
                    } else items(s.orders.take(10), key = { "o-" + it.id }) { o ->
                        val (label, color) = orderHelp(o.status)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(ChessCardShape)
                                .background(ChessCardSurface)
                                .border(1.dp, ChessCardLine, ChessCardShape)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${o.vcAmount} VC · ₹${o.priceInr.toInt()} — ${o.orderRef}", color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(o.createdAt?.take(10) ?: "", color = LabelGray, fontSize = 11.sp)
                                    o.reviewNote?.let { Text(it, color = LabelGray, fontSize = 11.sp, maxLines = 2) }
                                }
                                Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                            }
                        }
                    }
                    if (s.withdrawals.isNotEmpty()) {
                        item { SectionTitle("WITHDRAWALS", SkyBlue) }
                        items(s.withdrawals.take(8), key = { "w-" + it.id }) { w ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(ChessCardShape)
                                    .background(ChessCardSurface)
                                    .border(1.dp, ChessCardLine, ChessCardShape)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${w.vcAmount} VC → ₹${w.payoutInr.toInt()}", color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text(w.createdAt?.take(10) ?: "", color = LabelGray, fontSize = 11.sp)
                                    }
                                    Text(w.status.replace('_', ' '), color = if (w.status == "PAID") SkyBlue else PaperWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(snack)
            }
        }
    }
}

@Composable
private fun ActiveOrderPanel(s: WalletState, vm: WalletViewModel, onPickProof: () -> Unit) {
    val o = s.activeOrder ?: return
    val upi = o["upi_id"]?.jsonPrimitive?.contentOrNull ?: ""
    val payee = o["payee_name"]?.jsonPrimitive?.contentOrNull ?: "THE SYSTEM"
    val ref = o["order_ref"]?.jsonPrimitive?.contentOrNull ?: ""
    val amount = o["price_inr"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    val vc = o["vc_amount"]?.jsonPrimitive?.longOrNull ?: 0L
    val haptics = rememberSystemHaptics()
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ChessCardShape)
            .background(ChessCardSurface)
            .border(1.dp, ChessCardLine, ChessCardShape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ORDER $ref", color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("$vc VC → pay exactly ₹${amount.toInt()}", color = SkyBlue, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        QrImage(
            upiQrPayload(upi, payee, amount, ref),
            Modifier.size(190.dp).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.White).padding(8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(upi, color = PaperWhite, fontSize = 13.sp, fontFamily = SystemMono)
        Text(
            o["instructions"]?.jsonPrimitive?.contentOrNull ?: "",
            color = LabelGray, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = s.utr, onValueChange = vm::onUtr,
            label = { Text("UTR / UPI reference") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SkyBlue, focusedTextColor = PaperWhite, unfocusedTextColor = PaperWhite),
        )
        s.ocr?.let { scan ->
            if (scan.utrCandidate != null || scan.amountCandidate != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("SCANNED (verify before submit): ")
                        scan.amountCandidate?.let { append("₹${"%.2f".format(it)} · ") }
                        scan.utrCandidate?.let { append("UTR $it · ") }
                        scan.dateCandidate?.let { append("$it ") }
                        scan.timeCandidate?.let { append(it) }
                    },
                    color = SkyBlue, fontSize = 11.sp, lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        GhostButton(
            if (s.shotUri == null) "UPLOAD PAYMENT SCREENSHOT" else "SCREENSHOT ATTACHED — CHANGE",
            { haptics.select(); onPickProof() }, Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        NeonButton(
            if (s.busy) "PROCESSING…" else "SUBMIT FOR VERIFICATION",
            { haptics.select(); vm.submitProof(context = ctx) },
            Modifier.fillMaxWidth(), color = SkyBlue,
        )
        Spacer(Modifier.height(6.dp))
        GhostButton("ABANDON ORDER", { vm.cancelOrderView() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WithdrawPanel(s: WalletState, vm: WalletViewModel) {
    var name by remember { mutableStateOf("") }
    var upi by remember { mutableStateOf("") }
    var vcText by remember { mutableStateOf("") }
    val minVc = vm.cfgLong("withdrawal_min_vc", 1000)
    val maxVc = vm.cfgLong("withdrawal_max_vc", 100000)
    val enabled = vm.cfgFlag("withdrawal_enabled", true)
    val vcVal = vcText.toLongOrNull() ?: 0L
    val valid = enabled && name.trim().length >= 3 && upi.matches(Regex("^[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}$")) &&
        vcVal in minVc..maxVc && vcVal % 200L == 0L && vcVal <= s.vcBalance
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ChessCardShape)
            .background(ChessCardSurface)
            .border(1.dp, ChessCardLine, ChessCardShape)
            .padding(14.dp),
    ) {
        Text(
            if (enabled) "Limits: $minVc–$maxVc VC, multiples of 200 · $vcVal VC → ₹${"%.2f".format(vcVal / 2.0)}"
            else "Withdrawals are paused right now.",
            color = LabelGray, fontSize = 11.sp, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Account holder name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SkyBlue, focusedTextColor = PaperWhite, unfocusedTextColor = PaperWhite))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(upi, { upi = it.take(40) }, label = { Text("UPI ID (name@bank)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SkyBlue, focusedTextColor = PaperWhite, unfocusedTextColor = PaperWhite))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(vcText, { vcText = it.filter(Char::isDigit).take(7) }, label = { Text("VC amount") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SkyBlue, focusedTextColor = PaperWhite, unfocusedTextColor = PaperWhite))
        Spacer(Modifier.height(8.dp))
        NeonButton(
            if (s.busy) "QUEUING…" else "REQUEST WITHDRAWAL",
            { vm.requestWithdrawal(name, upi, vcVal) },
            Modifier.fillMaxWidth(), color = SkyBlue,
        )
        Text(
            "The VC is HELD the moment you request. Rejected/failed requests release the hold back to your balance. Payout is a controlled manual process.",
            color = FaintGray, fontSize = 10.sp, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
