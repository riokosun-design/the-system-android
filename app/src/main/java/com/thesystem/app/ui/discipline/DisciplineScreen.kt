package com.thesystem.app.ui.discipline

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
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
import com.thesystem.app.service.SystemDeviceAdminReceiver
import com.thesystem.app.service.SystemDisciplineService
import com.thesystem.app.service.SystemDnsGuardService

/**
 * DISCIPLINE ENGINE — control room (v0.24.0, spec §5/§6/§7).
 *
 * One screen, four wards: quest-locked social blocker, always-on porn shield,
 * the optional DNS family filter, and the anti-uninstall guard. Status is
 * read live from Android itself — never from our own bravado.
 */
@Composable
fun DisciplineScreen(nav: NavHostController) {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(isDisciplineAccessibilityOn(context)) }
    var deviceAdminOn by remember { mutableStateOf(isDeviceGuardOn(context)) }
    var dnsOn by remember { mutableStateOf(DisciplinePrefs.isDnsGuardOn(context) && SystemDnsGuardService.running) }
    var socialLock by remember { mutableStateOf(DisciplinePrefs.isSocialLockOn(context)) }
    var pornShield by remember { mutableStateOf(DisciplinePrefs.isPornShieldOn(context)) }

    // re-audit every time the hunter returns from a system settings screen
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = isDisciplineAccessibilityOn(context)
                deviceAdminOn = isDeviceGuardOn(context)
                dnsOn = DisciplinePrefs.isDnsGuardOn(context) && SystemDnsGuardService.running
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
                                "The accessibility sentinel that powers the quest lock, the porn " +
                                    "shield and the anti-uninstall guard.",
                                style = MaterialTheme.typography.bodySmall, color = LabelGray,
                            )
                        }
                        StatusPill(accessibilityOn)
                    }
                    Spacer(Modifier.height(Grid.S8))
                    if (!accessibilityOn) {
                        GhostButton("ENABLE IN ACCESSIBILITY SETTINGS", {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }, Modifier.fillMaxWidth())
                    } else {
                        Text(
                            "[ ACTIVE — the System is watching over you ]",
                            style = MonoLabel, color = SkyBlue,
                        )
                    }
                }
            }

            // ── §5 quest-locked social blocker ──────────────────────────────
            item {
                GlowCard {
                    WardRow(
                        title = "QUEST LOCK — SOCIAL MEDIA",
                        body = "Instagram, YouTube, Facebook and X stay sealed with an un-dismissible " +
                            "System lock until every mandatory Daily Quest is done. Finish training, " +
                            "the seal lifts until midnight.",
                        checked = socialLock && accessibilityOn,
                        enabled = accessibilityOn,
                        onToggle = {
                            socialLock = it; DisciplinePrefs.setSocialLock(context, it)
                        },
                    )
                }
            }

            // ── §6 porn shield ──────────────────────────────────────────────
            item {
                GlowCard {
                    WardRow(
                        title = "PORN SHIELD — ALWAYS ON",
                        body = "Every major browser is patrolled for adult domains, TLDs and explicit " +
                            "searches. A corrupted impulse slams a red System Intervention and seals " +
                            "the page. Zero network calls — the blocklist lives on-device.",
                        checked = pornShield && accessibilityOn,
                        enabled = accessibilityOn,
                        onToggle = {
                            pornShield = it; DisciplinePrefs.setPornShield(context, it)
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

            // ── §7 device guard ─────────────────────────────────────────────
            item {
                GlowCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("DEVICE GUARD — ANTI-UNINSTALL", style = MonoLabel, color = PaperWhite)
                            Text(
                                "While active, Android refuses to uninstall THE SYSTEM outright — " +
                                    "and any attempt to strip this permission wakes the System " +
                                    "Abandonment Protocol.",
                                style = MaterialTheme.typography.bodySmall, color = LabelGray,
                            )
                        }
                        StatusPill(deviceAdminOn)
                    }
                    Spacer(Modifier.height(Grid.S8))
                    if (!deviceAdminOn) {
                        GhostButton("ACTIVATE DEVICE GUARD", { requestDeviceAdmin(context) }, Modifier.fillMaxWidth())
                    } else {
                        Text("[ ACTIVE — quitting now requires the confession ]", style = MonoLabel, color = SkyBlue)
                    }
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

private fun isDisciplineAccessibilityOn(context: Context): Boolean = runCatching {
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    enabled.contains("SystemDisciplineService")
}.getOrDefault(false) && SystemDisciplineService.alive

private fun isDeviceGuardOn(context: Context): Boolean = runCatching {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    dpm.isAdminActive(ComponentName(context, SystemDeviceAdminReceiver::class.java))
}.getOrDefault(false)

private fun requestDeviceAdmin(context: Context) {
    val component = ComponentName(context, SystemDeviceAdminReceiver::class.java)
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "The Device Guard anchors the anti-uninstall law. Deactivating it summons the " +
                "System Abandonment Protocol.",
        )
    context.startActivity(intent)
}
