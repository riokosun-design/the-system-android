package com.thesystem.app.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * SYSTEM PERMISSION LAIR (vC56 §1/§3) — the one honest oracle for the three
 * Android-level powers the discipline wards stand on.
 *
 * vC56 §1 LAW — MANDATORY PERMISSION REDIRECT: a ward toggle may never flip
 * ON out of hope. Every ward reads its real grant state from Android itself
 * (never from our own SharedPreferences), and the UI routes the hunter to the
 * exact system screen when a grant is missing:
 *
 *   ACCESSIBILITY — Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES must name
 *                   SystemDisciplineService AND the service must be alive; the
 *                   guide line says: "Find 'THE SYSTEM' in Installed Services
 *                   and turn it ON".
 *   OVERLAY       — Settings.canDrawOverlays; granted from the per-app
 *                   "Display over other apps" screen (ACTION_MANAGE_OVERLAY_
 *                   PERMISSION with a package: Uri).
 *   DEVICE_ADMIN  — DevicePolicyManager.isAdminActive for the System guard;
 *                   requested via ACTION_ADD_DEVICE_ADMIN with the full
 *                   explanation extra.
 *
 * All three checks are cheap, synchronous and side-effect free — the lair is
 * safe to query on every ON_RESUME sync.
 */
object PermissionLair {

    /** The powers a ward can demand. */
    enum class Perm { ACCESSIBILITY, OVERLAY, DEVICE_ADMIN }

    // ── verdicts ────────────────────────────────────────────────────────────

    fun isAccessibilityOn(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        enabled.contains("SystemDisciplineService")
    }.getOrDefault(false) && SystemDisciplineService.alive

    fun isOverlayOn(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun isDeviceAdminOn(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(ComponentName(context, SystemDeviceAdminReceiver::class.java))
    }.getOrDefault(false)

    fun isGranted(context: Context, perm: Perm): Boolean = when (perm) {
        Perm.ACCESSIBILITY -> isAccessibilityOn(context)
        Perm.OVERLAY -> isOverlayOn(context)
        Perm.DEVICE_ADMIN -> isDeviceAdminOn(context)
    }

    /** The subset of [wanted] not yet granted — the toggle gate's verdict. */
    fun missing(context: Context, wanted: Set<Perm>): Set<Perm> =
        wanted.filterNot { isGranted(context, it) }.toSet()

    /** Every ward and the powers it demands before its switch may stand ON. */
    val CONTENT_WARD_PERMS: Set<Perm> = setOf(Perm.ACCESSIBILITY)
    val UNINSTALL_GUARD_PERMS: Set<Perm> =
        setOf(Perm.ACCESSIBILITY, Perm.OVERLAY, Perm.DEVICE_ADMIN)

    // ── doors ───────────────────────────────────────────────────────────────

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun requestDeviceAdmin(context: Context) {
        runCatching {
            val component = ComponentName(context, SystemDeviceAdminReceiver::class.java)
            context.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "The Device Guard anchors the anti-uninstall law. Deactivating it summons " +
                            "the System Abandonment Protocol.",
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** One call opens whichever door a missing permission needs. */
    fun openGrantFlow(context: Context, perm: Perm) = when (perm) {
        Perm.ACCESSIBILITY -> openAccessibilitySettings(context)
        Perm.OVERLAY -> openOverlaySettings(context)
        Perm.DEVICE_ADMIN -> requestDeviceAdmin(context)
    }
}
