package com.thesystem.app.ui.discipline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thesystem.app.core.theme.FaintGray
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.MonoLabel
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.theme.SystemMono
import com.thesystem.app.core.ui.GhostButton
import com.thesystem.app.service.PermissionLair

/**
 * vC56 §1 — SYSTEM PERMISSION SETUP DIALOG.
 *
 * Summoned the instant a hunter tries to arm a ward whose Android-level
 * powers are missing. It lists each missing permission with its exact duty
 * and a GRANT door to the real system screen. The ward's switch stays OFF
 * until every grant stands; the caller re-syncs on ON_RESUME and completes
 * the pending toggle only when [PermissionLair.missing] comes back empty.
 */
@Composable
fun SystemPermissionSetupDialog(
    title: String,
    missing: Set<PermissionLair.Perm>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(Color(0xFF07070B), CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp))
                .border(1.2.dp, SkyBlue.copy(alpha = 0.45f), CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Text(
                "[ ⚠ ] SYSTEM PERMISSION SETUP [ ⚠ ]",
                style = MonoLabel, color = SkyBlue,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = PaperWhite, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, fontFamily = SystemMono,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "This ward answers to Android, not to promises. Grant every power below — the switch " +
                    "stays OFF until each one stands. No grant, no guard.",
                style = MaterialTheme.typography.bodySmall, color = LabelGray, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(14.dp))

            missing.forEachIndexed { i, perm ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                PermissionRow(
                    perm = perm,
                    onGrant = { PermissionLair.openGrantFlow(context, perm) },
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Return after granting — THE SYSTEM re-checks on its own.",
                style = MonoLabel, color = FaintGray, fontSize = 9.sp,
            )
            Spacer(Modifier.height(10.dp))
            GhostButton("NOT NOW", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PermissionRow(perm: PermissionLair.Perm, onGrant: () -> Unit) {
    val (name, duty, guide) = when (perm) {
        PermissionLair.Perm.ACCESSIBILITY -> Triple(
            "ACCESSIBILITY SENTINEL",
            "THE SYSTEM must read window content to catch Reels, Shorts, adult pages and " +
                "uninstall attempts the instant they open.",
            "Find 'THE SYSTEM' in Installed Services and turn it ON.",
        )
        PermissionLair.Perm.OVERLAY -> Triple(
            "DISPLAY OVER OTHER APPS",
            "Lets the un-dismissible System overlays stand on top of any screen — including " +
                "the Abandonment Protocol over Settings.",
            "Enable 'Allow display over other apps' for THE SYSTEM.",
        )
        PermissionLair.Perm.DEVICE_ADMIN -> Triple(
            "DEVICE ADMIN — ANTI-UNINSTALL",
            "Anchors the anti-uninstall law: Android refuses to delete THE SYSTEM while " +
                "this guard stands.",
            "Tap 'Activate' on the Device Admin screen.",
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .border(0.8.dp, Color(0xFF22313D), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(12.dp),
    ) {
        Text(name, style = MonoLabel, color = PaperWhite)
        Spacer(Modifier.height(3.dp))
        Text(duty, style = MaterialTheme.typography.bodySmall, color = LabelGray, lineHeight = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            guide,
            color = SkyBlue, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = SystemMono,
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            "GRANT — OPEN SETTINGS",
            onGrant,
            Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(),
        )
    }
}
