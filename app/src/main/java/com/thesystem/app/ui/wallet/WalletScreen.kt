package com.thesystem.app.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thesystem.app.core.ui.FaintGray
import com.thesystem.app.core.ui.LabelGray
import com.thesystem.app.core.ui.MonoLabel
import com.thesystem.app.core.ui.PaperWhite
import com.thesystem.app.core.ui.SkyBlue
import com.thesystem.app.core.ui.SystemMono
import com.thesystem.app.data.gateway.PaymentGateways

/**
 * v1.0-RELEASE §1/§2 — the VC wallet is DEAD. This screen is now THE GATE:
 * the monetization rail's honest status panel. No coin balance, no offerwall,
 * no ledger — purchases (Black Room, Medal Trials, Dungeon Protocols, store)
 * mint PENDING_PAYMENT orders server-side and settle only through
 * [PaymentGateways.current] once the live Razorpay credentials ship.
 */
@Composable
fun WalletScreen(nav: NavHostController) {
    val gateway = PaymentGateways.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("THE GATE", style = MaterialTheme.typography.headlineMedium, color = PaperWhite)
        Spacer(Modifier.height(6.dp))
        Text(
            "PURCHASE RAIL · RAZORPAY-READY",
            style = MonoLabel, color = SkyBlue,
        )
        Spacer(Modifier.height(22.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .border(0.8.dp, Color(0xFF22313D), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                .padding(16.dp),
        ) {
            Text("[ GATEWAY ]", style = MonoLabel, color = PaperWhite)
            Spacer(Modifier.height(8.dp))
            Text(PaymentGateways.OFFLINE_NOTICE, style = MaterialTheme.typography.bodyMedium, color = LabelGray)
            Spacer(Modifier.height(10.dp))
            Text(
                "provider: ${gateway.providerId} · available: ${if (gateway.isAvailable) "YES" else "NO"}",
                style = MonoLabel, color = FaintGray,
            )
        }

        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .border(0.8.dp, Color(0xFF22313D), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                .padding(16.dp),
        ) {
            Text("[ WHAT SHIPS THROUGH HERE ]", style = MonoLabel, color = PaperWhite)
            Spacer(Modifier.height(8.dp))
            listOf(
                "BLACK ROOM — 3-year arc · unlock + 6-month renewal, priced by your own consistency",
                "MEDAL TRIALS — 30/60-day ordeals · 100% completion earns the physical dog-tag",
                "DUNGEON PROTOCOLS — one-time training blueprints, goal-built",
                "MARKETPLACE — System merch + curated arsenal",
            ).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = LabelGray)
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Rewards are XP, rank and prestige. The System trades in proof, not coins.",
            style = MaterialTheme.typography.bodySmall,
            color = FaintGray,
            fontFamily = SystemMono,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
