package com.thesystem.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * SYSTEM PERMISSION LAIR (v1.0 §1) — the one honest oracle for the two
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
 *
 * v1.0 §1 LAW — DEVICE ADMIN IS GONE. Sideloaded APKs get auto-backed on
 * Android 13+/MIUI/Realme, so the anti-uninstall wall is PURELY the
 * AccessibilityService sentinel + the SYSTEM_ALERT_WINDOW Abandonment
 * Protocol overlay. No DevicePolicyManager prompt ever surfaces again.
 *
 * Both checks are cheap, synchronous and side-effect free — the lair is safe
 * to query on every ON_RESUME sync.
 */
object PermissionLair {

    /** The powers a ward can demand. */
    enum class Perm { ACCESSIBILITY, OVERLAY }

    // ── verdicts ────────────────────────────────────────────────────────────

    fun isAccessibilityOn(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        enabled.contains("SystemDisciplineService")
    }.getOrDefault(false) && SystemDisciplineService.alive

    fun isOverlayOn(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun isGranted(context: Context, perm: Perm): Boolean = when (perm) {
        Perm.ACCESSIBILITY -> isAccessibilityOn(context)
        Perm.OVERLAY -> isOverlayOn(context)
    }

    /** The subset of [wanted] not yet granted — the toggle gate's verdict. */
    fun missing(context: Context, wanted: Set<Perm>): Set<Perm> =
        wanted.filterNot { isGranted(context, it) }.toSet()

    /** Every ward and the powers it demands before its switch may stand ON. */
    val CONTENT_WARD_PERMS: Set<Perm> = setOf(Perm.ACCESSIBILITY)
    val UNINSTALL_GUARD_PERMS: Set<Perm> =
        setOf(Perm.ACCESSIBILITY, Perm.OVERLAY)

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

    /** One call opens whichever door a missing permission needs. */
    fun openGrantFlow(context: Context, perm: Perm) = when (perm) {
        Perm.ACCESSIBILITY -> openAccessibilitySettings(context)
        Perm.OVERLAY -> openOverlaySettings(context)
    }
}
