package com.thesystem.app.ui.admin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.*
import kotlinx.serialization.json.jsonPrimitive

/** Super Admin CMS. Every mutation is double-locked server-side by RLS + is_admin(). */
@Composable
fun AdminScreen(onBack: () -> Unit, vm: AdminViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    val sections = listOf("OVERVIEW", "LEGAL", "STORE", "TOURNAMENTS", "PAYMENTS", "POOLS", "ASSETS")
    var tab by remember { mutableIntStateOf(0) }

    SystemBackground(wallpaperAlpha = 0.06f) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = HunterGold) }
                Text("ADMIN CONTROL", style = MaterialTheme.typography.headlineMedium, color = HunterGold)
            }
            // monochrome scrollable rail — white hairline marks the active section
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sections.forEachIndexed { i, label ->
                    val active = tab == i
                    Column(
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { haptics.select(); tab = i }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            label,
                            color = if (active) PaperWhite else LabelGray,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.height(2.dp).width(24.dp)
                                .background(if (active) PaperWhite else androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
            when (tab) {
                0 -> OverviewTab(s)
                1 -> LegalTab(s, vm)
                2 -> StoreCmsTab(s, vm)
                3 -> TournamentTab(s, vm)
                4 -> PaymentsTab(s, vm)
                5 -> PoolsTab(s, vm)
                6 -> AssetsTab(s, vm)
            }
        }
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snack) }
    }
}

@Composable
private fun OverviewTab(s: AdminState) {
    val o = s.overview
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp)) {
        item {
            Box(Modifier.enterAnim(0)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedStatTile("Hunters", o?.get("users")?.jsonPrimitive?.content?.toLongOrNull() ?: 0, ElectricBlue, Modifier.weight(1f))
                    AnimatedStatTile("Guilds", o?.get("clans")?.jsonPrimitive?.content?.toLongOrNull() ?: 0, NeonPurple, Modifier.weight(1f))
                    AnimatedStatTile("Pending ₹", o?.get("pending_payments")?.jsonPrimitive?.content?.toLongOrNull() ?: 0, CrimsonRed, Modifier.weight(1f))
                }
            }
        }
        item {
            Box(Modifier.enterAnim(1)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedStatTile("Open tournaments", o?.get("open_tournaments")?.jsonPrimitive?.content?.toLongOrNull() ?: 0, HunterGold, Modifier.weight(1f))
                    AnimatedStatTile("VC in flight", o?.get("pool_vc")?.jsonPrimitive?.content?.toLongOrNull() ?: 0, VenomGreen, Modifier.weight(1f))
                }
            }
        }
        item { Box(Modifier.enterAnim(2)) { Text("First 3 registered hunters are SUPER_ADMIN (SQL trigger). All mutations below are RLS-locked to your role.", style = MaterialTheme.typography.bodyMedium) } }
    }
}

// ── Dynamic Legal Text Editor ────────────────────────────────────────────────
@Composable
private fun LegalTab(s: AdminState, vm: AdminViewModel) {
    val haptics = rememberSystemHaptics()
    var docType by remember { mutableStateOf("PRIVACY_POLICY") }
    val doc = if (docType == "PRIVACY_POLICY") s.privacy else s.terms
    var title by remember(doc?.id) { mutableStateOf(doc?.title ?: "") }
    var body by remember(doc?.id) { mutableStateOf(doc?.contentMarkdown ?: "") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("PRIVACY", { docType = "PRIVACY_POLICY" }, Modifier.weight(1f), color = if (docType == "PRIVACY_POLICY") HunterGold else TextMuted)
                NeonButton("TERMS", { docType = "TERMS_OF_SERVICE" }, Modifier.weight(1f), color = if (docType == "TERMS_OF_SERVICE") HunterGold else TextMuted)
            }
        }
        item { Text("Live version v${doc?.version ?: 0} — publishing bumps the version instantly; apps refetch on next open.", style = MaterialTheme.typography.labelSmall) }
        item {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Document title") }, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
        }
        item {
            OutlinedTextField(
                value = body, onValueChange = { body = it }, label = { Text("Markdown body (#, ##, -, paragraphs)") },
                modifier = Modifier.fillMaxWidth().height(360.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
            )
        }
        item {
            NeonButton("PUBLISH v${(doc?.version ?: 0) + 1} LIVE", {
                haptics.slam();
                if (docType == "PRIVACY_POLICY") vm.publishPrivacy(body, title) else vm.publishTerms(body, title)
            }, Modifier.fillMaxWidth(), color = HunterGold, enabled = !s.busy && body.isNotBlank())
        }
    }
}

// ── E-Commerce & Affiliate CMS ───────────────────────────────────────────────
@Composable
private fun StoreCmsTab(s: AdminState, vm: AdminViewModel) {
    val haptics = rememberSystemHaptics()
    var editing by remember { mutableStateOf<ProductDto?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { NeonButton("＋ ADD PRODUCT", { haptics.tick(); editing = ProductDto(id = "", name = "", outboundUrl = "") }, Modifier.fillMaxWidth(), color = HunterGold) }
        items(s.products, key = { it.id }) { p ->
            GlowCard(glow = if (p.active) HunterGold else SurfaceHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.name} — ₹${p.priceInr.toInt()}", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("${p.category} · min rank ${p.minRankRequired} · affiliate ${p.affiliateCommissionPct.toInt()}% · ${if (p.active) "LIVE" else "HIDDEN"}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { editing = p }) { Text("Edit", color = HunterGold) }
                    TextButton(onClick = { haptics.error(); vm.deleteProduct(p.id) }) { Text("Delete", color = CrimsonRed) }
                }
            }
        }
    }
    editing?.let { ProductEditor(it, vm, onClose = { editing = null }) }
}

@Composable
private fun ProductEditor(p: ProductDto, vm: AdminViewModel, onClose: () -> Unit) {
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    var name by remember { mutableStateOf(p.name) }
    var desc by remember { mutableStateOf(p.description ?: "") }
    var price by remember { mutableStateOf(p.priceInr.toString()) }
    var url by remember { mutableStateOf(p.outboundUrl) }
    var image by remember { mutableStateOf(p.imageUrl ?: "") }
    var category by remember { mutableStateOf(p.category) }
    var minRank by remember { mutableStateOf(p.minRankRequired) }
    var commission by remember { mutableStateOf(p.affiliateCommissionPct.toString()) }
    var active by remember { mutableStateOf(p.active) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.uploadProductImage(it, context) { uploaded -> uploaded?.let { u -> image = u } } }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = SurfaceDark,
        title = { Text(if (p.id.isBlank()) "ADD PRODUCT" else "EDIT PRODUCT", color = HunterGold) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).let { it }.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                @Composable fun field(v: String, on: (String) -> Unit, label: String) =
                    OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                field(name, { name = it }, "Name"); field(desc, { desc = it }, "Description")
                field(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, "Price (INR)")
                field(url, { url = it }, "Outbound / affiliate URL")
                field(commission, { commission = it.filter { c -> c.isDigit() || c == '.' } }, "Affiliate % (10–15)")
                field(image, { image = it }, "Image URL (or upload →)")
                NeonButton("UPLOAD IMAGE TO STORAGE", { haptics.tick(); picker.launch("image/*") }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    listOf("MERCH", "SUPPLEMENT", "DIGITAL").forEach { c ->
                        NeonButton(c, { category = c }, color = if (category == c) HunterGold else TextMuted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SystemMath.HunterRank.storeRanks.forEach { r ->
                        NeonButton(r.title, { minRank = r.title }, color = if (minRank == r.title) rankColor(r) else TextMuted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(active, { active = it }, colors = CheckboxDefaults.colors(checkedColor = HunterGold))
                    Text("Live in store", color = TextPrimary)
                }
            }
        },
        confirmButton = {
            NeonButton("SAVE", {
                haptics.success();
                vm.upsertProduct(
                    p.copy(name = name, description = desc, priceInr = price.toDoubleOrNull() ?: 0.0, outboundUrl = url,
                        imageUrl = image.ifBlank { null }, category = category, minRankRequired = minRank,
                        affiliateCommissionPct = commission.toDoubleOrNull() ?: 0.0, active = active)
                ); onClose()
            }, color = HunterGold, enabled = name.isNotBlank() && url.isNotBlank())
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = TextMuted) } },
    )
}

// ── Tournament management (dynamic entry fees) ───────────────────────────────
@Composable
private fun TournamentTab(s: AdminState, vm: AdminViewModel) {
    val haptics = rememberSystemHaptics()
    var title by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("250") }
    var type by remember { mutableStateOf("SOLO") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GlowCard(glow = HunterGold) {
                Text("OPEN A TOURNAMENT", style = MaterialTheme.typography.titleMedium, color = HunterGold)
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(fee, { fee = it.filter(Char::isDigit).take(9) }, label = { Text("Entry fee (VC)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("SOLO 1v1", { type = "SOLO" }, Modifier.weight(1f), color = if (type == "SOLO") HunterGold else TextMuted)
                    NeonButton("CLAN WAR", { type = "CLAN" }, Modifier.weight(1f), color = if (type == "CLAN") NeonPurple else TextMuted)
                }
                Spacer(Modifier.height(8.dp))
                NeonButton("CREATE & OPEN REGISTRATION", { haptics.success(); vm.createTournament(title, type, fee.toLongOrNull() ?: 0L); title = "" },
                    Modifier.fillMaxWidth(), color = HunterGold, enabled = title.length >= 4)
            }
        }
        items(s.tournaments, key = { it.id }) { t ->
            GlowCard(glow = if (t.status == "OPEN") VenomGreen else SurfaceHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("${t.type} · ${t.status} · entry ${SystemMath.formatVc(t.entryFeeVc)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    when (t.status) {
                        "OPEN" -> NeonButton("LOCK", { haptics.select(); vm.setTournamentStatus(t.id, "LOCKED") }, color = WarningAmber)
                        // ROUND 6: bracket engine owns the lifecycle from here
                        "LOCKED" -> NeonButton("SEED BRACKET", { haptics.slam(); vm.generateBracket(t.id) })
                        "IN_PROGRESS" -> NeonButton("ADVANCE ▶", { haptics.success(); vm.advanceBracket(t.id) }, color = VenomGreen)
                        else -> Text(t.status, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ── Manual payment approvals (UPI QR → UTR → screenshot) ────────────────────
@Composable
private fun PaymentsTab(s: AdminState, vm: AdminViewModel) {
    val haptics = rememberSystemHaptics()
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.payments.isEmpty()) item { EmptyState("No pending UPI payments. The treasury sleeps.") }
        items(s.payments, key = { it.id }) { p ->
            GlowCard(glow = WarningAmber, pulse = true) {
                Text("@${p.username ?: p.userId.take(8)} — ${p.itemType}", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text("₹${p.amountInr.toInt()} · UTR ${p.upiUtr}", style = MaterialTheme.typography.bodyMedium)
                p.screenshotPath?.let { path ->
                    vm.proofUrl(path)?.let { url -> TextButton(onClick = { uriHandler.openUri(url) }) { Text("View screenshot", color = ElectricBlue) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("APPROVE", { haptics.success(); vm.reviewPayment(p.id, true) }, Modifier.weight(1f), color = VenomGreen, enabled = !s.busy)
                    NeonButton("REJECT", { haptics.error(); vm.reviewPayment(p.id, false) }, Modifier.weight(1f), color = CrimsonRed, enabled = !s.busy)
                }
            }
        }
    }
}

// ── Prediction pool settlement (solo + clan use the same engine) ─────────────
@Composable
private fun PoolsTab(s: AdminState, vm: AdminViewModel) {
    val haptics = rememberSystemHaptics()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.pools.isEmpty()) item { EmptyState("No unsettled pools.") }
        items(s.pools, key = { it.id }) { pool ->
            GlowCard(glow = ElectricBlue) {
                Text("Pool ${SystemMath.formatVc(pool.totalPoolVc)} · A ${SystemMath.formatVc(pool.totalAVc)} / B ${SystemMath.formatVc(pool.totalBVc)} · ${pool.status}",
                    color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text("House cut: ${pool.platformCutBps / 100}% taken before distribution.", style = MaterialTheme.typography.labelSmall)
                if (pool.status != "SETTLED") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    NeonButton("A WON", { haptics.slam(); vm.settlePool(pool.id, "A") }, Modifier.weight(1f), enabled = !s.busy)
                    NeonButton("B WON", { haptics.slam(); vm.settlePool(pool.id, "B") }, Modifier.weight(1f), color = CrimsonRed, enabled = !s.busy)
                }
            }
        }
    }
}

// ── Dynamic Theme Asset CMS ──────────────────────────────────────────────────
@Composable
private fun AssetsTab(s: AdminState, vm: AdminViewModel) {
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    var key by remember { mutableStateOf("DASHBOARD_HERO") }
    var type by remember { mutableStateOf("CHARACTER_WALLPAPER") }
    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.uploadAsset(key, type, it, context, fade = 0.22) }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GlowCard(glow = HunterGold) {
                Text("PUSH A NEW THEME ASSET", style = MaterialTheme.typography.titleMedium, color = HunterGold)
                OutlinedTextField(key, { key = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' } }, label = { Text("Slot key (e.g. DASHBOARD_HERO)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    listOf("CHARACTER_WALLPAPER", "AMBIENT_BACKGROUND", "UI_OVERLAY").forEach { t ->
                        NeonButton(t.replace("_", " "), { type = t }, color = if (type == t) HunterGold else TextMuted)
                    }
                }
                NeonButton("UPLOAD & GO LIVE", { haptics.tick(); uploader.launch("image/*") }, Modifier.fillMaxWidth(), color = HunterGold)
                Text("Instant re-theme: clients fetch enabled assets from the system_assets bucket on cold start.", style = MaterialTheme.typography.labelSmall)
            }
        }
        items(s.assets, key = { it.id }) { a ->
            GlowCard(glow = if (a.enabled) ElectricBlue else SurfaceHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.key, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("${a.type} · fade ${a.fadeOpacity} · ${a.storagePath}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = a.enabled, onCheckedChange = { haptics.tick(); vm.toggleAsset(a.id, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = ElectricBlue))
                }
            }
        }
    }
}
