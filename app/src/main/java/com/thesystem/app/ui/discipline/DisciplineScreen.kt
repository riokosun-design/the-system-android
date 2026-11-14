package com.thesystem.app.ui.discipline

import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.thesystem.app.core.discipline.DisciplinePrefs
import com.thesystem.app.core.theme.FaintGray
import com.thesystem.app.core.theme.Grid
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.MonoLabel
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.ui.GhostButton
import com.thesystem.app.core.ui.GlowCard
import com.thesystem.app.core.ui.SectionTitle
import com.thesystem.app.core.ui.SystemBackground
import com.thesystem.app.service.PermissionLair
import com.thesystem.app.service.SystemDnsGuardService

/**
 * DISCIPLINE ENGINE — control room (v0.24.0 spec §5/§6/§7, vC56 §1/§3).
 *
 * One screen, four wards: quest-locked social blocker, always-on
 * adult-content shield, the optional DNS family filter, and the
 * anti-uninstall guard.
 *
 * vC56 §1 LAW — MANDATORY PERMISSION REDIRECT: no ward switch may flip ON
 * against missing Android-level powers. A forbidden flip summons the System
 * Permission Setup Dialog with the exact GRANT doors; the pending arming
 * completes itself on ON_RESUME only when [PermissionLair.missing] returns
 * empty. Status is read live from Android itself — never from our own
 * bravado. (vC54 §2 copy law: the UI never uses the word "Porn".)
 */
@Composable
fun DisciplineScreen(nav: NavHostController) {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(PermissionLair.isAccessibilityOn(context)) }
    var overlayOn by remember { mutableStateOf(PermissionLair.isOverlayOn(context)) }
    var deviceAdminOn by remember { mutableStateOf(PermissionLair.isDeviceAdminOn(context)) }
    var dnsOn by remember { mutableStateOf(DisciplinePrefs.isDnsGuardOn(context) && SystemDnsGuardService.running) }
    var socialLock by remember { mutableStateOf(DisciplinePrefs.isSocialLockOn(context)) }
    var pornShield by remember { mutableStateOf(DisciplinePrefs.isPornShieldOn(context)) }
    var uninstallGuard by remember { mutableStateOf(DisciplinePrefs.isUninstallGuardOn(context)) }

    // vC56 §1 — the pending arming + the powers it still lacks
    var permDialog by remember { mutableStateOf<Set<PermissionLair.Perm>?>(null) }
    var permDialogTitle by remember { mutableStateOf("") }
    var pendingOn by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** The toggle gate: OFF is always free; ON demands every granted power. */
    fun gate(on: Boolean, perms: Set<PermissionLair.Perm>, wardTitle: String, apply: () -> Unit) {
        if (!on) { apply(); return }
        val missing = PermissionLair.missing(context, perms)
        if (missing.isEmpty()) {
            apply()
        } else {
            pendingOn = apply
            permDialogTitle = wardTitle
            permDialog = missing
        }
    }

    // re-audit every time the hunter returns from a system settings screen —
    // and complete a pending arming the moment its grants all stand (vC56 §1)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = PermissionLair.isAccessibilityOn(context)
                overlayOn = PermissionLair.isOverlayOn(context)
                deviceAdminOn = PermissionLair.isDeviceAdminOn(context)
                dnsOn = DisciplinePrefs.isDnsGuardOn(context) && SystemDnsGuardService.running
                val pending = pendingOn
                val needed = permDialog
                if (pending != null && needed != null) {
                    val still = PermissionLair.missing(context, needed)
                    if (still.isEmpty()) {
                        pending()
                        pendingOn = null
                        permDialog = null
                    } else {
                        permDialog = still
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            context.startService(
                Intent(context, SystemDnsGuardService::class.java).setAction(SystemDnsGuardService.ACTION_START),
            )
            DisciplinePrefs.setDnsGuard(context, true)
            dnsOn = true
        } else {
            DisciplinePrefs.setDnsGuard(context, false)
            dnsOn = false
        }
    }

    SystemBackground {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding().fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Grid.S12),
            contentPadding = PaddingValues(horizontal = Grid.Margin, vertical = Grid.S16),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("BACK", { nav.popBackStack() })
                    Spacer(Modifier.weight(1f))
                    Text("DISCIPLINE ENGINE", style = MonoLabel, color = SkyBlue)
                }
            }

            item {
                Column {
                    SectionTitle("SELF-CONTROL PROTOCOL", PaperWhite)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The wards below enforce your own rules when willpower runs out. " +
                            "They are opt-in, live entirely on this device, and answer to no server.",
                        style = MaterialTheme.typography.bodySmall, color = LabelGray,
                    )
                }
            }

            // ── §5+§6 core sentinel ─────────────────────────────────────────
            item {
                GlowCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SYSTEM GUARDIAN", style = MonoLabel, color = PaperWhite)
                            Text(
                                "The accessibility sentinel that powers the quest lock, the adult-content " +
                                    "shield and the anti-uninstall guard.",
                                style = MaterialTheme.typography.bodySmall, color = LabelGray,
                            )
                        }
                        StatusPill(accessibilityOn)
                    }
                    Spacer(Modifier.height(Grid.S8))
                    if (!accessibilityOn) {
                        GhostButton("ENABLE IN ACCESSIBILITY SETTINGS", {
                            PermissionLair.openAccessibilitySettings(context)
                        }, Modifier.fillMaxWidth())
                    } else {
                        Text(
                            "[ ACTIVE — the System is watching over you ]",
                            style = MonoLabel, color = SkyBlue,
                        )
                    }
                }
            }

            // ── §5 quest-locked social blocker (vC56 §1 gated) ──────────────
            item {
                GlowCard {
                    WardRow(
                        title = "QUEST LOCK — SOCIAL MEDIA",
                        body = "Instagram, YouTube, Facebook and X stay sealed with an un-dismissible " +
                            "System lock until every mandatory Daily Quest is done. Finish training, " +
                            "the seal lifts until midnight.",
                        checked = socialLock && accessibilityOn,
                        enabled = true,
                        onToggle = { on ->
                            gate(on, PermissionLair.CONTENT_WARD_PERMS, "QUEST LOCK — SOCIAL MEDIA") {
                                socialLock = on; DisciplinePrefs.setSocialLock(context, on)
                            }
                        },
                    )
                }
            }

            // ── §6 adult-content shield (vC54 §2 copy law: the UI name is
            //    "Block Adult Contents", period; vC56 §1 gated) ───────────────
            item {
                GlowCard {
                    WardRow(
                        title = "BLOCK ADULT CONTENTS — ALWAYS ON",
                        body = "Every major browser is patrolled for adult domains, TLDs and explicit " +
                            "searches. A corrupted impulse slams the crimson System Violation and seals " +
                            "the page. Zero network calls — the blocklist lives on-device.",
                        checked = pornShield && accessibilityOn,
                        enabled = true,
                        onToggle = { on ->
                            gate(on, PermissionLair.CONTENT_WARD_PERMS, "BLOCK ADULT CONTENTS") {
                                pornShield = on; DisciplinePrefs.setPornShield(context, on)
                            }
                        },
                    )
                }
            }

            // ── §6.3 DNS guard (opt-in) ─────────────────────────────────────
            item {
                GlowCard {
                    WardRow(
                        title = "DNS GUARD — FAMILY FILTER",
                        body = "Optional system-wide layer: DNS resolves only through Cloudflare " +
                            "Family (1.1.1.3), so adult domains fail to resolve inside ANY app. " +
                            "Only resolver traffic crosses the tunnel; everything else is untouched.",
                        checked = dnsOn,
                        enabled = true,
                        onToggle = { want ->
                            if (want) {
                                val consent = VpnService.prepare(context)
                                if (consent == null) {
                                    context.startService(
                                        Intent(context, SystemDnsGuardService::class.java)
                                            .setAction(SystemDnsGuardService.ACTION_START),
                                    )
                                    DisciplinePrefs.setDnsGuard(context, true)
                                    dnsOn = true
                                } else {
                                    vpnLauncher.launch(consent)
                                }
                            } else {
                                context.startService(
                                    Intent(context, SystemDnsGuardService::class.java)
                                        .setAction(SystemDnsGuardService.ACTION_STOP),
                                )
                                DisciplinePrefs.setDnsGuard(context, false)
                                dnsOn = false
                            }
                        },
                    )
                }
            }

            // ── §7 + vC56 §3 anti-uninstall guard (triple-locked toggle) ────
            item {
                GlowCard {
                    WardRow(
                        title = "ANTI-UNINSTALL GUARD — COWARD'S PROTOCOL",
                        body = "While armed, uninstalling THE SYSTEM or stripping its accessibility, " +
                            "overlay or device-admin powers summons the System Abandonment Protocol: " +
                            "a full-screen, un-dismissible overlay over Settings. Escape costs either " +
                            "a perfect typed confession — or one honest oath.",
                        checked = uninstallGuard && accessibilityOn && overlayOn && deviceAdminOn,
                        enabled = true,
                        onToggle = { on ->
                            gate(on, PermissionLair.UNINSTALL_GUARD_PERMS, "ANTI-UNINSTALL GUARD") {
                                uninstallGuard = on; DisciplinePrefs.setUninstallGuard(context, on)
                            }
                        },
                    )
                    Spacer(Modifier.height(Grid.S8))
                    Text(
                        if (uninstallGuard && accessibilityOn && overlayOn && deviceAdminOn) {
                            "[ ACTIVE — quitting now requires the confession ]"
                        } else {
                            "[ DORMANT — the switch arms only behind all three powers ]"
                        },
                        style = MonoLabel,
                        color = if (uninstallGuard && accessibilityOn && overlayOn && deviceAdminOn) SkyBlue else FaintGray,
                    )
                }
            }

            item {
                Text(
                    "THE LAW: no quests, no entertainment. No discipline, no escape. " +
                        "The System protects the hunter from the weakest version of themselves.",
                    style = MonoLabel, color = FaintGray,
                )
            }

            item { Spacer(Modifier.height(60.dp)) }
        }

        // vC56 §1 — the mandatory redirect; stands until granted or dismissed
        val missingNow = permDialog
        if (missingNow != null) {
            SystemPermissionSetupDialog(
                title = permDialogTitle,
                missing = missingNow,
                onDismiss = {
                    permDialog = null
                    pendingOn = null
                },
            )
        }
    }
}

@Composable
private fun WardRow(title: String, body: String, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MonoLabel, color = PaperWhite, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = SkyBlue.copy(alpha = 0.5f),
                    checkedThumbColor = SkyBlue,
                ),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = LabelGray, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun StatusPill(active: Boolean) {
    Text(
        if (active) "ACTIVE" else "OFF",
        style = MonoLabel, color = if (active) SkyBlue else FaintGray,
    )
}
