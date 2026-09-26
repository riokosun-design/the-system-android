package com.thesystem.app.ui.protocol

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.ArcDto
import com.thesystem.app.data.repo.SystemRepository
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Tab 4 — Protocol: Black Room (paywalled), White Room (brain training), Arcs, Shadow Guilds. */
@Composable
fun ProtocolScreen(nav: NavHostController, vm: ProtocolViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    var subTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("ROOMS", "ARCS", "GUILDS")

    SystemBackground(wallpaperAlpha = 0.12f) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            Text("PROTOCOL", style = MaterialTheme.typography.headlineMedium, color = PaperWhite)
            SystemTabBar(tabs = tabs, selected = subTab, onSelect = { i -> haptics.select(); subTab = i })
            // ROUND 3: skeleton shimmer while protocol resolves
            if (s.loading) SkeletonCards(3) else when (subTab) {
                0 -> RoomsTab(s, vm, nav)
                1 -> ArcsTab(s, vm)
                2 -> GuildsTab(s, vm, nav)
            }
        }
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snack) }
    }
}

// ── ROOMS: Black (paywalled) + White (brain training) ────────────────────────
@Composable
private fun RoomsTab(s: ProtocolState, vm: ProtocolViewModel, nav: NavHostController) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { BlackRoomCard(s, vm) }
        item { WhiteRoomCard() }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun BlackRoomCard(s: ProtocolState, vm: ProtocolViewModel) {
    var showPay by remember { mutableStateOf(false) }
    GlowCard(glow = CrimsonRed) {
        Text("THE BLACK ROOM", style = MaterialTheme.typography.titleLarge, color = PaperWhite)
        Text("Elite routines, dark arcs, shadow mentorship. Paywalled. Verified manually — no chargebacks, no mercy.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        if (s.blackRoomUnlocked) {
            Text("ACCESS GRANTED", color = VenomGreen, style = MaterialTheme.typography.labelLarge)
            Text("Black arc library unlocked — see ARCS tab for your shadow routines.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val pending = s.myPayments.any { it.status == "PENDING" && it.itemType == "BLACK_ROOM_PASS" }
            if (pending) Text("VERIFICATION PENDING — an admin is reviewing your UPI payment.", color = WarningAmber, style = MaterialTheme.typography.labelSmall)
            else NeonButton("UNLOCK ₹${s.blackRoomPrice.toInt()}", { showPay = true }, color = CrimsonRed)
        }
    }
    if (showPay) UpiPaymentSheet(s, onDismiss = { showPay = false }, onSubmit = { utr, uri, ctx -> vm.submitBlackRoomPayment(utr, uri, ctx); showPay = false })
}

/** Manual gateway: QR → pay → UTR ID → screenshot → submit for admin approval. */
@Composable
fun UpiPaymentSheet(s: ProtocolState, onDismiss: () -> Unit, onSubmit: (String, Uri?, android.content.Context) -> Unit) {
    var utr by remember { mutableStateOf("") }
    var screenshot by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { screenshot = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("MANUAL UPI GATEWAY", color = CrimsonRed) },
        text = {
            Column {
                Text("1. Pay ₹${s.blackRoomPrice.toInt()} to UPI ID:", style = MaterialTheme.typography.bodyMedium)
                Text(s.upiId, color = HunterGold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                s.upiQrUrl?.let { Text("QR available in the vault instructions.", style = MaterialTheme.typography.labelSmall) }
                Text("2. Paste the UTR / UPI reference ID from your payment app.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = utr, onValueChange = { utr = it.take(22) },
                    label = { Text("UTR ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CrimsonRed, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                )
                Spacer(Modifier.height(8.dp))
                Text("3. Attach the payment screenshot.", style = MaterialTheme.typography.bodyMedium)
                NeonButton(if (screenshot == null) "ATTACH SCREENSHOT" else "SCREENSHOT ATTACHED ✓", { picker.launch("image/*") }, color = if (screenshot == null) ElectricBlue else VenomGreen)
            }
        },
        confirmButton = { NeonButton("SUBMIT FOR REVIEW", { onSubmit(utr, screenshot, context) }, color = CrimsonRed, enabled = utr.length >= 8) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } },
    )
}

@Composable
private fun WhiteRoomCard() {
    GlowCard(glow = ElectricBlue) {
        Text("THE WHITE ROOM", style = MaterialTheme.typography.titleLarge, color = PaperWhite)
        Text("Brain training. Reflex discipline. Free forever.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        ReactionGame()
    }
}

/** Reflex trainer: random delay → tap; measures reaction in ms. Pure Compose state machine. */
@Composable
private fun ReactionGame() {
    var phase by remember { mutableIntStateOf(0) } // 0 idle, 1 waiting, 2 TAP, 3 done
    var startedAt by remember { mutableLongStateOf(0L) }
    var result by remember { mutableLongStateOf(0L) }
    var best by remember { mutableLongStateOf(Long.MAX_VALUE) }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(phase) {
        if (phase == 1) { delay(Random.nextLong(1200, 3200)); phase = 2; startedAt = System.nanoTime(); haptics.select() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        NeonButton(
            when (phase) {
                0 -> "START REFLEX TEST"; 1 -> "WAIT…"; 2 -> "TAP"; else -> "AGAIN"
            },
            {
                when (phase) {
                    0, 3 -> { haptics.tick(); phase = 1 }
                    1 -> { haptics.error(); phase = 0; result = -1 } // jumped the gun — The System saw that
                    2 -> {
                        result = (System.nanoTime() - startedAt) / 1_000_000
                        if (result < best) { best = result; haptics.slam() } else haptics.tick()
                        phase = 3
                    }
                }
            },
            Modifier.fillMaxWidth().height(64.dp),
            color = when (phase) { 2 -> VenomGreen; 1 -> WarningAmber; else -> ElectricBlue },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                phase == 3 && result > 0 -> "Reaction: ${result}ms" + if (result == best) " — NEW BEST" else ""
                phase == 0 && result == -1L -> "Too eager. The System saw that."
                else -> "Tap the instant the button turns white."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (phase == 3) PaperWhite else TextMuted,
        )
        if (best != Long.MAX_VALUE) Text("Best: ${best}ms", style = MonoLabel, color = PaperWhite)
    }
}

// ── ARCS: 4-month anime training arcs with progression locks ─────────────────
@Composable
private fun ArcsTab(s: ProtocolState, vm: ProtocolViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            RestrictionBanner(
                "BIO-MECHANICS DISCLAIMER — these are extreme fictional-inspired routines. Medical clearance recommended. " +
                    "THE SYSTEM adapts intensity but cannot feel your pain. You can. Stop when it says too.",
                color = WarningAmber,
            )
        }
        items(s.arcs, key = { it.id }) { arc ->
            val progress = s.arcProgress[arc.id]
            val unlocked = arc.unlockReqArc == null || s.arcProgress[arc.unlockReqArc]?.completed == true
            ArcCard(arc, progress, unlocked, s.profile?.level ?: 1, onStart = { vm.startArc(arc) })
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun ArcCard(arc: ArcDto, progress: com.thesystem.app.data.model.ArcProgressDto?, unlocked: Boolean, myLevel: Int, onStart: () -> Unit) {
    val gated = !unlocked || myLevel < arc.minLevel
    GlowCard(glow = if (progress?.completed == true) VenomGreen else if (gated) SurfaceHigh else NeonPurple) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${arc.hero} — ${arc.title}", style = MaterialTheme.typography.titleMedium,
                    color = if (gated) TextMuted else TextPrimary)
                Text("${arc.durationMonths}-month arc · min level ${arc.minLevel}", style = MaterialTheme.typography.bodyMedium)
                arc.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (progress != null) {
                    val totalDays = arc.durationMonths * 30 + progress.penaltyExtraDays
                    val frac = (progress.daysCompleted.toFloat() / totalDays.coerceAtLeast(1)).coerceIn(0f, 1f)
                    Spacer(Modifier.height(4.dp))
                    // SYSTEM hairline progress — deterministic fill, no Material ring
                    Box(Modifier.fillMaxWidth().height(6.dp).background(SurfaceHigh)) {
                        Box(Modifier.fillMaxWidth(frac).height(6.dp).background(NeonPurple))
                    }
                    Text("${progress.daysCompleted}/$totalDays days · ${(frac * 100).toInt()}%" +
                        if (progress.penaltyExtraDays > 0) " (+${progress.penaltyExtraDays}d penalty extension)" else "",
                        style = MaterialTheme.typography.labelSmall, color = NeonPurple)
                }
                if (gated) {
                    Text(
                        if (!unlocked) "LOCKED — COMPLETE THE PREREQUISITE ARC" else "LOCKED — REQUIRES LEVEL ${arc.minLevel}",
                        color = CrimsonRed, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            when {
                progress?.completed == true -> Text("ARISED", color = VenomGreen, style = MaterialTheme.typography.labelLarge)
                progress != null -> Text("ACTIVE", color = NeonPurple, style = MaterialTheme.typography.labelLarge)
                gated -> Text("LOCKED", color = TextMuted, style = MaterialTheme.typography.labelLarge)
                else -> NeonButton("BEGIN", onStart, color = NeonPurple)
            }
        }
    }
}

// ── GUILDS (Shadow Guild system) ─────────────────────────────────────────────
@Composable
private fun GuildsTab(s: ProtocolState, vm: ProtocolViewModel, nav: NavHostController) {
    var name by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    val me = s.profile
    val canCreate = (me?.level ?: 0) >= SystemMath.CLAN_MIN_LEVEL && (me?.vcBalance ?: 0) >= SystemMath.CLAN_CREATE_COST_VC && s.myMembership == null

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        if (s.myMembership == null) {
            item {
                GlowCard(glow = NeonPurple) {
                    Text("FOUND A SHADOW GUILD", style = MaterialTheme.typography.titleMedium, color = NeonPurple)
                    Text("Gate: Level ${SystemMath.CLAN_MIN_LEVEL}+ AND ${SystemMath.formatVc(SystemMath.CLAN_CREATE_COST_VC)}. Roles: GUILD_MASTER, VICE_CAPTAIN, ELITE_HUNTER, MEMBER.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(32) }, label = { Text("Guild name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonPurple, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = tag, onValueChange = { tag = it.uppercase().take(5) }, label = { Text("TAG (max 5)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonPurple, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                    Spacer(Modifier.height(8.dp))
                    if (!canCreate) RestrictionBanner("Requires LV ${SystemMath.CLAN_MIN_LEVEL} + ${SystemMath.formatVc(SystemMath.CLAN_CREATE_COST_VC)}")
                    else NeonButton("FORGE GUILD (−${SystemMath.formatVc(SystemMath.CLAN_CREATE_COST_VC)})", { vm.createClan(name, tag) },
                        Modifier.fillMaxWidth(), color = NeonPurple, enabled = name.length >= 3 && tag.length >= 2)
                }
            }
        } else s.myClan?.let { clan ->
            item {
                GlowCard(glow = NeonPurple) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("[${clan.tag}] ${clan.name}", style = MaterialTheme.typography.titleLarge, color = NeonPurple)
                            Text("Guild Level ${clan.level} · Treasury ${SystemMath.formatVc(clan.treasuryVc)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Your role: ${s.myMembership.role}", style = MaterialTheme.typography.labelSmall, color = HunterGold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            NeonButton("CLAN CHAT", { nav.navigate(Routes.CHAT) }, color = ElectricBlue)
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { vm.leaveClan() }) { Text("Leave", color = CrimsonRed) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("ROSTER (${s.clanMembers.size})", style = MaterialTheme.typography.labelSmall)
                    s.clanMembers.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("@${m.username ?: m.userId.take(8)}", color = TextPrimary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(m.role, color = when (m.role) { "GUILD_MASTER" -> HunterGold; "VICE_CAPTAIN" -> ElectricBlue; "ELITE_HUNTER" -> NeonPurple; else -> TextMuted },
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item { SectionTitle("Known Guilds", NeonPurple) }
        items(s.clans, key = { it.id }) { c ->
            GlowCard(glow = SurfaceHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("[${c.tag}] ${c.name}", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("${c.memberCount ?: "?"} hunters · treasury ${SystemMath.formatVc(c.treasuryVc)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (s.myMembership == null) NeonButton("JOIN", { vm.joinClan(c) }, color = NeonPurple)
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
