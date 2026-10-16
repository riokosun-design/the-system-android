package com.thesystem.app.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.thesystem.app.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * §7 — ANTI-MOD PERIMETER. Multi-layered runtime integrity law for THE SYSTEM:
 *
 *   1. DEBUGGER     — Debug.isDebuggerConnected / waiting-for-debugger flag
 *   2. ROOT         — su binaries, test-keys builds, rw /system mounts
 *   3. EMULATOR     — goldfish/ranchu/qemu hardware fingerprints
 *   4. HOOKS        — Frida (files/ports/maps), Xposed/LSPosed bridge classes
 *   5. REPACK       — LuckyPatcher & known patcher package signatures
 *   6. SIGNATURE    — signing-cert SHA-256 vs BuildConfig.EXPECTED_CERT_SHA256
 *                     (empty constant = nightly/dev builds skip; the release CI
 *                      injects the keystore hash when the release lane opens)
 *
 * Any hard failure terminates the process via System-denial — no dialog, no
 * retry: a modded client never reaches the economy's front door. Every check
 * is cheap and runs cold at application start; no network, no permissions.
 */
object AntiTamper {

    private var ran = false

    /** One-shot cold-start perimeter sweep. Throws TorishmentError on violation. */
    fun enforce(context: Context) {
        if (ran) return
        ran = true
        val verdict = sweep(context)
        if (verdict != null) throw TorishmentError(verdict)
    }

    /** Non-fatal sweep for diagnostics — returns the violation or null. */
    fun sweep(context: Context): String? {
        // 1 — debugger
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return "DEBUGGER_ATTACHED"
        // 2 — root
        rootSignal()?.let { return it }
        // 3 — emulator
        if (isEmulator()) return "EMULATOR_DENYLIST"
        // 4 — hooks
        hookSignal()?.let { return it }
        // 5 — tamper tooling
        patcherSignal(context)?.let { return it }
        // 6 — signature law (only when a hash has been pinned into the build)
        signatureSignal(context)?.let { return it }
        return null
    }

    // ── 2. ROOT ───────────────────────────────────────────────────────────────

    private val SU_PATHS = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su",
        "/system/bin/.ext/.su", "/system/usw/bin/su", "/vendor/bin/su",
    )

    private fun rootSignal(): String? {
        for (p in SU_PATHS) if (File(p).exists()) return "ROOT_SU_BINARY"
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return "ROOT_TEST_KEYS"
        // /system mounted read-write — a rooted/remounted ROM signature
        runCatching {
            val mounts = File("/proc/mounts").readText()
            mounts.lineSequence().forEach { line ->
                val parts = line.split(" ")
                if (parts.size >= 4 && (parts[1] == "/system" || parts[1] == "/system_root") && parts[3].startsWith("rw")) {
                    return "ROOT_SYSTEM_RW"
                }
            }
        }
        return null
    }

    // ── 3. EMULATOR ───────────────────────────────────────────────────────────

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hw = Build.HARDWARE.lowercase()
        val brand = Build.BRAND.lowercase()
        return fp.startsWith("generic") || fp.contains("vbox") || fp.contains("test-keys") && hw.contains("goldfish") ||
            model.contains("emulator") || model.contains("android sdk built for") ||
            Build.MANUFACTURER.lowercase().contains("genymotion") ||
            hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("vbox86") ||
            product.contains("sdk_gphone") || product.contains("emulator") || product.contains("simulator") ||
            (brand.startsWith("generic") && Build.DEVICE.lowercase().startsWith("generic"))
    }

    // ── 4. HOOK FRAMEWORKS ────────────────────────────────────────────────────

    private val FRIDA_FILES = arrayOf(
        "/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server",
        "/data/local/tmp/fs-server", "/sdcard/frida-server",
    )

    private val HOOK_CLASSES = arrayOf(
        "de.robv.android.xposed.XposedBridge",          // Xposed / LSPosed legacy
        "de.robv.android.xposed.XC_MethodHook",
        "com.saurik.substrate.MS$MethodHooker",          // Substrate
        "org.lsposed.manager.LSPosedManager",            // LSPosed
        "re.frida.Entry",                                // Frida gadget binding
    )

    private fun hookSignal(): String? {
        for (p in FRIDA_FILES) if (File(p).exists()) return "FRIDA_SERVER_FILE"
        // frida's default attach port
        runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", 27042), 90)
                return "FRIDA_PORT_OPEN"
            }
        }
        for (c in HOOK_CLASSES) {
            runCatching { Class.forName(c) }.onSuccess { return "HOOK_FRAMEWORK:${c.substringAfterLast('.')}" }
        }
        // frida-agent / gadget mapped into our memory space
        runCatching {
            val maps = File("/proc/self/maps").readText().lowercase()
            if (maps.contains("frida") || maps.contains("gadget") || maps.contains("xposed")) {
                return "HOOK_LIBRARY_MAPPED"
            }
        }
        return null
    }

    // ── 5. PATCHER PACKAGES ───────────────────────────────────────────────────

    private val PATCHER_PACKAGES = arrayOf(
        "com.android.vending.billing.InAppBillingService.LUCK", // LuckyPatcher
        "com.chelpus.lackypatch", "com.dimonvideo.luckypatcher",
        "com.forpda.lp", "com.android.vending.billing.InAppBillingService.CLON",
        "cc.madkite.freedom", "com.android.cheat", "org.creeplays.hack",
    )

    private fun patcherSignal(context: Context): String? {
        val pm = context.packageManager
        for (p in PATCHER_PACKAGES) {
            val hit = runCatching { pm.getPackageInfo(p, 0) }.isSuccess
            if (hit) return "PATCHER_PACKAGE:${p.substringAfterLast('.')}"
        }
        return null
    }

    // ── 6. SIGNATURE LAW ──────────────────────────────────────────────────────

    private fun signatureSignal(context: Context): String? {
        val expected = BuildConfig.EXPECTED_CERT_SHA256.replace(":", "").lowercase()
        if (expected.isBlank()) return null     // unpinned build (nightly) — law sleeps
        val actual = certSha256(context) ?: return "SIGNATURE_UNREADABLE"
        return if (actual.lowercase() == expected) null else "SIGNATURE_MISMATCH"
    }

    private fun certSha256(context: Context): String? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        } ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(info.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Thrown on perimeter breach — the System's execution warrant. */
    class TorishmentError(violation: String) : Error(
        "[SYSTEM DENIAL]\nIntegrity verdict: $violation\nThis build will not run in this state.",
    )
}
