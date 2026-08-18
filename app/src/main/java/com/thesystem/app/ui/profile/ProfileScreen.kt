package com.thesystem.app.ui.profile

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.thesystem.app.Routes
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.ProductDto
import com.thesystem.app.data.model.UserDto
import java.util.Locale

/** Tab 5 — Profile & Vault: stats, forms, wallet, referrals + CPA offerwall, level-gated store, chat, admin. */
@Composable
fun ProfileScreen(profile: UserDto, nav: NavHostController, onSignOut: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val me = s.profile ?: profile
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    SystemBackground(wallpaperAlpha = 0.18f) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            item { Box(Modifier.enterAnim(0)) { ProfileHeader(me, onOpenChat = { haptics.tick(); nav.navigate(Routes.CHAT) }, onSignOut = { haptics.select(); onSignOut() }) } }
            if (me.isAdmin) {
                item { NeonButton("⚙ ADMIN CONTROL PANEL", { nav.navigate(Routes.ADMIN) }, Modifier.fillMaxWidth(), color = HunterGold) }
            }
            item { Box(Modifier.enterAnim(1)) { BodyStatsCard(me, vm) } }
            item { Box(Modifier.enterAnim(2)) { FormsCard(s, me) } }
            item { Box(Modifier.enterAnim(3)) { WalletCard(s) } }
            item { Box(Modifier.enterAnim(4)) { OfferwallCard(s.offerwallUrl) } }
            item { Box(Modifier.enterAnim(5)) { ReferralCard(me, s) } }
            item { SectionTitle("Shadow Merch — level-gated drops", HunterGold) }
            if (s.merch.isEmpty()) item { EmptyState("The merch forge is cold. Admins can add drops from the panel.") }
            items(s.merch, key = { it.id }) { p -> ProductCard(p, me, gated = true) }
            item { SectionTitle("Supplement Arsenal — affiliate", VenomGreen) }
            items(s.supplements, key = { it.id }) { p -> ProductCard(p, me, gated = false) }
            item { Spacer(Modifier.height(90.dp)) }
        }
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snack) }
    }
}

@Composable
private fun ProfileHeader(me: UserDto, onOpenChat: () -> Unit, onSignOut: () -> Unit) {
    val rank = me.rank.value
    GlowCard(glow = rankColor(rank)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XpRing(xp = me.xp, level = me.level, modifier = Modifier.size(92.dp), color = rankColor(rank))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(me.displayName ?: "Hunter", style = MaterialTheme.typography.titleLarge)
                Text("@${me.username}", color = ElectricBlue, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RankBadge(rank); VcChip(me.vcBalance)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("MESSAGES", onOpenChat, Modifier.weight(1f))
            NeonButton("SIGN OUT", onSignOut, Modifier.weight(1f), color = CrimsonRed)
        }
    }
}

@Composable
private fun BodyStatsCard(me: UserDto, vm: ProfileViewModel) {
    var editing by remember { mutableStateOf(false) }
    var age by remember { mutableIntStateOf(me.age ?: 20) }
    var height by remember { mutableFloatStateOf((me.heightCm ?: 170.0).toFloat()) }
    var weight by remember { mutableFloatStateOf((me.weightKg ?: 65.0).toFloat()) }
    GlowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BODY STATS", style = MaterialTheme.typography.labelLarge, color = ElectricBlue, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (editing) { vm.saveBodyStats(age, height.toDouble(), weight.toDouble()) }
                editing = !editing
            }) { Text(if (editing) "SAVE" else "EDIT", color = ElectricBlue) }
        }
        if (!editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Age", "${me.age ?: "—"}", modifier = Modifier.weight(1f))
                StatTile("Height", "${me.heightCm?.toInt() ?: "—"} cm", modifier = Modifier.weight(1f))
                StatTile("Weight", "%.1f".format(me.weightKg ?: 0.0) + " kg", modifier = Modifier.weight(1f))
            }
        } else {
            Text("Age: $age", style = MaterialTheme.typography.bodyMedium)
            Slider(value = age.toFloat(), onValueChange = { age = it.toInt() }, valueRange = 13f..80f)
            Text("Height: ${height.toInt()} cm", style = MaterialTheme.typography.bodyMedium)
            Slider(value = height, onValueChange = { height = it }, valueRange = 120f..220f)
            Text("Weight: %.1f kg".format(weight), style = MaterialTheme.typography.bodyMedium)
            Slider(value = weight, onValueChange = { weight = it }, valueRange = 35f..180f)
        }
    }
}

@Composable
private fun FormsCard(s: ProfileState, me: UserDto) {
    GlowCard(glow = NeonPurple) {
        Text("MYSTERY POWER — FORM EVOLUTION", style = MaterialTheme.typography.labelLarge, color = NeonPurple)
        Spacer(Modifier.height(6.dp))
        if (s.forms.isEmpty()) {
            Text("███████ ██ ████████. The mechanics awaken at level ${SystemMath.FORM_UNLOCK_LEVELS[0]}. Until then: train.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            s.forms.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("FORM ${f.formIndex}", color = HunterGold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(80.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("${f.combatStyle} · hard-work ×%.2f".format(f.hardWorkMultiplier), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("${f.computedPower}", color = NeonPurple, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                }
                NeonDivider(NeonPurple)
            }
            Text("Power = (form base + XP) × combat style × hard work. A lazy Form 5 loses to a relentless Form 1. Stay relentless.",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun WalletCard(s: ProfileState) {
    GlowCard(glow = HunterGold) {
        Text("VC WALLET", style = MaterialTheme.typography.labelLarge, color = HunterGold)
        Spacer(Modifier.height(6.dp))
        if (s.ledger.isEmpty()) Text("No transactions yet. Grind quests, win battles, pull the CPA wall.", style = MaterialTheme.typography.bodyMedium)
        s.ledger.take(15).forEach { tx ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(tx.reason, style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.width(140.dp))
                Text(
                    (if (tx.amount >= 0) "+" else "") + SystemMath.formatVc(tx.amount),
                    color = if (tx.amount >= 0) VenomGreen else CrimsonRed,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OfferwallCard(url: String?) {
    val uriHandler = LocalUriHandler.current
    GlowCard(glow = VenomGreen) {
        Text("CPA OFFERWALL", style = MaterialTheme.typography.labelLarge, color = VenomGreen)
        Text("Complete partner offers — VC lands in your wallet via postback. Referrers earn a 2% lifetime cut of your CPA VC.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        NeonButton("OPEN OFFERWALL", { url?.let(uriHandler::openUri) }, Modifier.fillMaxWidth(), color = VenomGreen, enabled = url != null)
    }
}

@Composable
private fun ReferralCard(me: UserDto, s: ProfileState) {
    val context = LocalContext.current
    val haptics = rememberSystemHaptics()
    // tiers are stored big→small; next goal = smallest tier still above your score
    val nextTier = REFERRAL_TIERS.filter { (count, _) -> s.myReferrals < count }.minByOrNull { (count, _) -> count }
    GlowCard(glow = HunterGold, pulse = nextTier != null) {
        Text("REFERRAL HALL OF FAME", style = MaterialTheme.typography.labelLarge, color = HunterGold)
        Text("Your code: ${me.referralCode} · ${s.myReferrals} hunters summoned", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Text("Lifetime cuts: 5–10% merch · 10% Black Room · 5% tournament wins · 2% CPA VC.", style = MaterialTheme.typography.labelSmall)
        nextTier?.let { (count, label) ->
            Text("NEXT: $label — ${count - s.myReferrals} summons away.", color = HunterGold, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
        NeonButton("SUMMON HUNTERS (SHARE)", {
            haptics.success()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Enter THE SYSTEM with my code ${me.referralCode} — level up or decay. @${me.username}")
            }
            context.startActivity(Intent.createChooser(send, "Summon hunters"))
        }, Modifier.fillMaxWidth(), color = HunterGold)
        Spacer(Modifier.height(10.dp))
        REFERRAL_TIERS.forEach { (count, label) ->
            val hit = s.myReferrals >= count
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (hit) "✓" else "○", color = if (hit) VenomGreen else TextMuted)
                Spacer(Modifier.width(8.dp))
                Text("$count refs — $label", style = MaterialTheme.typography.bodyMedium, color = if (hit) HunterGold else TextMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        s.hall.take(5).forEachIndexed { i, h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("#${i + 1}", color = HunterGold, modifier = Modifier.width(34.dp), style = MaterialTheme.typography.labelLarge)
                Text("@${h.username}", color = TextPrimary, modifier = Modifier.weight(1f))
                Text("${h.referralCount}", color = HunterGold)
            }
        }
    }
}

/** Level-gated product card: rank check against min_rank_required (client gate; server returns 403 on abuse). */
@Composable
private fun ProductCard(p: ProductDto, me: UserDto, gated: Boolean) {
    val uriHandler = LocalUriHandler.current
    val myRank = me.rank.value
    val required = SystemMath.HunterRank.fromTitle(p.minRankRequired)
    val locked = gated && myRank.order < required.order
    GlowCard(glow = if (locked) SurfaceHigh else rankColor(required)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            p.imageUrl?.let {
                AsyncImage(model = it, contentDescription = p.name, modifier = Modifier.size(64.dp), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(p.name, color = if (locked) TextMuted else TextPrimary, style = MaterialTheme.typography.titleMedium)
                p.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("₹${String.format(Locale.US, "%.0f", p.priceInr)}", color = HunterGold, style = MaterialTheme.typography.labelLarge)
                    if (p.affiliateCommissionPct > 0) Text("affiliate ${p.affiliateCommissionPct.toInt()}%", color = VenomGreen, style = MaterialTheme.typography.labelSmall)
                    if (gated) RankBadge(required)
                }
            }
            if (locked) Text("🔒", style = MaterialTheme.typography.titleLarge)
            else NeonButton("BUY", { uriHandler.openUri(p.outboundUrl) }, color = if (p.category == "SUPPLEMENT") VenomGreen else ElectricBlue)
        }
        if (locked) Text("Requires ${required.title} rank. Earn it.", color = CrimsonRed, style = MaterialTheme.typography.labelSmall)
    }
}
