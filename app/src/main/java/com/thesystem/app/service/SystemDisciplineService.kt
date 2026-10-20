package com.thesystem.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.thesystem.app.MainActivity
import com.thesystem.app.core.discipline.AdultBlocklist
import com.thesystem.app.core.discipline.DisciplinePrefs
import com.thesystem.app.ui.discipline.AntiUninstallActivity

/**
 * SYSTEM DISCIPLINE SERVICE (v0.24.0, spec §5/§6/§7) — one accessibility
 * sentinel, three laws:
 *
 *  §5 QUEST LOCK   — Instagram / YouTube / Facebook / X stay sealed behind an
 *                    un-dismissible Solo Leveling lock until the day's quests
 *                    are verifiably complete (server truth, locally cached).
 *  §6 PORN SHIELD  — browser address bars (and, failing that, the visible
 *                    window) are scanned against the adult blocklist; a hit
 *                    navigates back and slams the red System Intervention.
 *  §7 GUARDIAN     — Settings / package-installer screens trying to uninstall
 *                    THE SYSTEM or strip its permissions trigger the
 *                    System Abandonment Protocol (confession typing ritual).
 *
 * This service performs no network calls, keeps no user data, and answers
 * every verdict from local, deterministic state.
 */
class SystemDisciplineService : AccessibilityService() {

    companion object {
        @Volatile var alive: Boolean = false
            private set

        /** §5 targets — the dopamine tax (plus lite/clone variants). */
        val SOCIAL_BLOCKED: Map<String, String> = mapOf(
            "com.instagram.android" to "Instagram",
            "com.instagram.lite" to "Instagram Lite",
            "com.google.android.youtube" to "YouTube",
            "com.facebook.katana" to "Facebook",
            "com.facebook.lite" to "Facebook Lite",
            "com.twitter.android" to "X",
            "com.twitter.android.lite" to "X Lite",
        )

        /** §6 browsers we patrol. */
        private val BROWSERS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title",
            ),
            "com.opera.browser" to listOf("com.opera.browser:id/url_bar", "com.opera.browser:id/url_field"),
            "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.duckduckgo.mobile.android" to listOf(
                "com.duckduckgo.mobile.android:id/omnibarTextInput",
                "com.duckduckgo.mobile.android:id/omnibar_text_input",
            ),
            "com.UCMobile.intl" to listOf("com.UCMobile.intl:id/url_bar"),
            "com.uc.browser.en" to listOf("com.uc.browser.en:id/url_bar"),
            "com.kiwibrowser.browser" to listOf("com.kiwibrowser.browser:id/url_bar"),
            "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
        )

        /** §7 — where uninstall / permission surgery happens. */
        private val SETTINGS_PKGS = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.coloros.sauhelper",
        )

        private val SETTINGS_DANGER_TERMS = listOf(
            "uninstall", "force stop", "force quit", "device admin",
            "deactivate", "accessibility", "app info",
        )
    }

    private val ui = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager
        get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlay: SystemLockOverlayView? = null
    private var overlayKey: String? = null          // "social:<pkg>" | "porn"
    private var overlayPkg: String? = null
    private var lastBrowserScanAt = 0L
    private var lastInterceptLaunchAt = 0L

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = true
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 80
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        alive = false
        removeOverlay()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EVENT LOOP
    // ═══════════════════════════════════════════════════════════════════════

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // §5 — social quest lock
                val socialLabel = SOCIAL_BLOCKED[pkg]
                val mustLock = socialLabel != null && DisciplinePrefs.isSocialLockOn(this) &&
                    !DisciplinePrefs.isQuestLockLifted(this)
                if (mustLock) {
                    showSocialLock(pkg, socialLabel!!)
                } else if (overlayPkg != null && pkg != packageName && !AntiUninstallActivity.visible) {
                    if (pkg != overlayPkg || overlayKey == "social:$pkg") {
                        // the blocked app backgrounded — or the seal legitimately lifted mid-session
                        removeOverlay()
                    }
                }

                // §7 — anti-uninstall guardian
                if (pkg in SETTINGS_PKGS && !DisciplinePrefs.uninstallGuardReleased(this)) {
                    auditSettingsScreen()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg in BROWSERS.keys && DisciplinePrefs.isPornShieldOn(this)) {
                    val now = System.currentTimeMillis()
                    if (now - lastBrowserScanAt >= 700) {
                        lastBrowserScanAt = now
                        scanBrowserUrl(pkg)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §5 — QUEST-LOCKED SOCIAL BLOCKER
    // ═══════════════════════════════════════════════════════════════════════

    private fun showSocialLock(pkg: String, label: String) {
        if (overlayKey == "social:$pkg") return
        removeOverlay()
        val view = SystemLockOverlayView.socialLock(this, label) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            ui.postDelayed({ launchSelf("quests") }, 260)
        }
        attach(view, key = "social:$pkg", pkg = pkg)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §6 — PORN SHIELD
    // ═══════════════════════════════════════════════════════════════════════

    private fun scanBrowserUrl(pkg: String) {
        val root = rootInActiveWindow ?: return
        val candidates = StringBuilder()
        var foundUrlBar = false
        for (id in BROWSERS[pkg].orEmpty()) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
            nodes?.forEach { n ->
                runCatching {
                    val t = n.text ?: n.contentDescription
                    if (!t.isNullOrBlank()) { foundUrlBar = true; candidates.append(t).append(' ') }
                    n.recycle()
                }
            }
        }
        if (!foundUrlBar) {
            // hostile fallback: no readable address bar → judge the visible page
            collectTexts(root, candidates, budget = 300)
        }
        root.recycle()

        val probe = candidates.toString()
        if (probe.isBlank()) return
        val adult = AdultBlocklist.isAdultUrl(probe.take(800))
        if (adult) {
            // block: navigate away, then slam the red intervention
            performGlobalAction(GLOBAL_ACTION_BACK)
            showPornViolation(pkg)
        } else if (overlayKey == "porn") {
            removeOverlay() // page is clean again — lift the intervention
        }
    }

    private fun showPornViolation(pkg: String) {
        if (overlayKey == "porn") return
        removeOverlay()
        val view = SystemLockOverlayView.pornViolation(this) {
            removeOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
            ui.postDelayed({ launchSelf("quests") }, 260)
        }
        attach(view, key = "porn", pkg = pkg)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §7 — ANTI-UNINSTALL GUARDIAN
    // ═══════════════════════════════════════════════════════════════════════

    private fun auditSettingsScreen() {
        if (AntiUninstallActivity.visible) return
        val now = System.currentTimeMillis()
        if (now - lastInterceptLaunchAt < 4_000) return
        val root = rootInActiveWindow ?: return
        val texts = StringBuilder()
        collectTexts(root, texts, budget = 400)
        root.recycle()
        val flat = texts.toString().lowercase()
        if (flat.isEmpty()) return
        val mentionsUs = flat.contains("the system") || flat.contains("com.thesystem.app")
        val danger = SETTINGS_DANGER_TERMS.any { flat.contains(it) }
        if (mentionsUs && danger) {
            lastInterceptLaunchAt = now
            AntiUninstallActivity.launch(this)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MACHINERY
    // ═══════════════════════════════════════════════════════════════════════

    private fun collectTexts(root: AccessibilityNodeInfo, out: StringBuilder, budget: Int) {
        var remaining = budget
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && remaining > 0) {
            val n = stack.removeLast()
            remaining--
            runCatching {
                n.text?.let { out.append(it).append(' ') }
                n.contentDescription?.let { out.append(it).append(' ') }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { child -> stack.addLast(child) }
                }
            }
            if (n != root) n.recycle()
        }
    }

    private fun attach(view: SystemLockOverlayView, key: String, pkg: String) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                overlay = view
                overlayKey = key
                overlayPkg = pkg
            }
    }

    private fun removeOverlay() {
        overlay?.let { v -> try { windowManager.removeView(v) } catch (_: Exception) { } }
        overlay = null
        overlayKey = null
        overlayPkg = null
    }

    private fun launchSelf(open: String) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, open)
        runCatching { startActivity(i) }
    }
}
