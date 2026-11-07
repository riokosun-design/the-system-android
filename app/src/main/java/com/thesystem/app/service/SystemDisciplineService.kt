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
 * SYSTEM DISCIPLINE SERVICE (vC54) — one accessibility sentinel, four laws:
 *
 *  §5 QUEST LOCK   — Instagram / YouTube / Facebook / X stay sealed behind an
 *                    un-dismissible Solo Leveling lock until the day's quests
 *                    are verifiably complete (server truth, locally cached).
 *  vC54 §1/§2      — GRANULAR WARDS: the Instagram Blocker and the YouTube &
 *                    Shorts Blocker are independent toggles; a LIVE quest
 *                    window (or the 15–30 min recovery break) hard-locks the
 *                    armed wards, and every YouTube Shorts reel is detected
 *                    (reel_watch_fragment_root) and killed on sight.
 *  §6 ADULT SHIELD — browser address bars (and, failing that, the visible
 *                    window) are scanned against the adult blocklist; a hit
 *                    navigates back and slams the red System Intervention.
 *                    (UI speaks only "Block Adult Contents" — vC54 copy law.)
 *  vC54 §3         — CRIMSON LOCKDOWN: while PenaltyLockdown stands,
 *                    Instagram / YouTube / Snapchat seal regardless of the
 *                    standing toggles until the redemption gauntlet clears.
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

        /** vC54 §3 — the crimson lockdown trio (Snapchat lives ONLY here). */
        val LOCKDOWN_SET: Map<String, String> = mapOf(
            "com.instagram.android" to "Instagram",
            "com.instagram.lite" to "Instagram Lite",
            "com.google.android.youtube" to "YouTube",
            "com.snapchat.android" to "Snapchat",
        )

        /** vC54 §1 — apps the live quest window can hard-lock. */
        private val WINDOW_SET: Map<String, String> = mapOf(
            "com.instagram.android" to "Instagram",
            "com.instagram.lite" to "Instagram Lite",
            "com.google.android.youtube" to "YouTube",
        )

        private const val YOUTUBE_PKG = "com.google.android.youtube"
        /** The Shorts player container — its presence alone is the verdict. */
        private const val REEL_ROOT_ID = "$YOUTUBE_PKG:id/reel_watch_fragment_root"

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
    private var overlayKey: String? = null          // "social:<pkg>" | "window:<pkg>" | "lockdown:<pkg>" | "adult" | "shorts"
    private var overlayPkg: String? = null
    private var lastBrowserScanAt = 0L
    private var lastShortsScanAt = 0L
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
                when (val seal = sealFor(pkg)) {
                    Seal.LOCKDOWN -> showLockdownLock(pkg, LOCKDOWN_SET[pkg] ?: pkg)
                    Seal.WINDOW -> showWindowLock(pkg, WINDOW_SET[pkg] ?: pkg)
                    Seal.SOCIAL -> showSocialLock(pkg, SOCIAL_BLOCKED[pkg] ?: pkg)
                    Seal.NONE -> if (overlayPkg != null && pkg != packageName && !AntiUninstallActivity.visible) {
                        // the sealed app backgrounded, or every ward legitimately lifted mid-session
                        removeOverlay()
                    }
                }

                // §7 — anti-uninstall guardian
                if (pkg in SETTINGS_PKGS && !DisciplinePrefs.uninstallGuardReleased(this)) {
                    auditSettingsScreen()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // §6 — adult-content interception (UI name: Block Adult Contents)
                if (pkg in BROWSERS.keys && DisciplinePrefs.isPornShieldOn(this)) {
                    val now = System.currentTimeMillis()
                    if (now - lastBrowserScanAt >= 700) {
                        lastBrowserScanAt = now
                        scanBrowserUrl(pkg)
                    }
                }
                // vC54 §2 — YouTube & Shorts Blocker: reels die on sight
                if (pkg == YOUTUBE_PKG && overlayKey == null && DisciplinePrefs.isYoutubeBlockOn(this)) {
                    val now = System.currentTimeMillis()
                    if (now - lastShortsScanAt >= 900) {
                        lastShortsScanAt = now
                        killReelIfPlaying()
                    }
                }
            }
        }
    }

    private enum class Seal { NONE, SOCIAL, WINDOW, LOCKDOWN }

    /** The verdict tree — strictest law wins. */
    private fun sealFor(pkg: String): Seal {
        // vC54 §3 — crimson lockdown overrides every toggle on the trio
        if (PenaltyLockdown.isActive(this) && pkg in LOCKDOWN_SET.keys) return Seal.LOCKDOWN

        // vC54 §1 — a live quest window (or the running recovery break) hard-locks armed wards
        if (pkg in WINDOW_SET.keys && windowWardOn(pkg) && QuestRhythm.isQuestWindowActive(this)) return Seal.WINDOW

        // §5 + vC54 §2 — standing quest lock: master ward or the app's own granular ward
        if (pkg in SOCIAL_BLOCKED.keys && wardOn(pkg) && !DisciplinePrefs.isQuestLockLifted(this)) return Seal.SOCIAL

        return Seal.NONE
    }

    /** The hunter's own switches: master social ward OR this app's granular ward. */
    private fun wardOn(pkg: String): Boolean {
        if (DisciplinePrefs.isSocialLockOn(this)) return true
        return when (pkg) {
            "com.instagram.android", "com.instagram.lite" -> DisciplinePrefs.isInstagramBlockOn(this)
            else -> false
        }
    }

    /** Window law honors the same wards (YouTube enters via & Shorts Blocker here). */
    private fun windowWardOn(pkg: String): Boolean {
        if (DisciplinePrefs.isSocialLockOn(this)) return true
        return when (pkg) {
            "com.instagram.android", "com.instagram.lite" -> DisciplinePrefs.isInstagramBlockOn(this)
            YOUTUBE_PKG -> DisciplinePrefs.isYoutubeBlockOn(this)
            else -> false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §5 — QUEST-LOCKED SOCIAL BLOCKER
    // ═══════════════════════════════════════════════════════════════════════

    private fun showSocialLock(pkg: String, label: String) {
        val key = "social:$pkg"
        if (overlayKey == key) return
        removeOverlay()
        val view = SystemLockOverlayView.socialLock(this, label) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            ui.postDelayed({ launchSelf("quests") }, 260)
        }
        attach(view, key = key, pkg = pkg)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // vC54 §1/§2 — QUEST WINDOW SEAL + GRANULAR WARDS
    // ═══════════════════════════════════════════════════════════════════════

    private fun showWindowLock(pkg: String, label: String) {
        val key = "window:$pkg"
        if (overlayKey == key) return
        removeOverlay()
        val windowTitle = QuestRhythm.activeWindow(this)?.title ?: "Daily protocol"
        val view = SystemLockOverlayView.questWindowLock(this, label, windowTitle) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            ui.postDelayed({ launchSelf("quests") }, 260)
        }
        attach(view, key = key, pkg = pkg)
    }

    /** The reel killer: the Shorts container id is the whole confession. */
    private fun killReelIfPlaying() {
        val root = rootInActiveWindow ?: return
        val reels = runCatching { root.findAccessibilityNodeInfosByViewId(REEL_ROOT_ID) }.getOrNull()
        val playing = !reels.isNullOrEmpty()
        reels?.forEach { runCatching { it.recycle() } }
        root.recycle()
        if (!playing) return
        // navigate away first, then flash the verdict — it auto-dissolves fast
        performGlobalAction(GLOBAL_ACTION_BACK)
        showShortsFlash()
    }

    private fun showShortsFlash() {
        if (overlayKey == "shorts") return
        removeOverlay()
        val view = SystemLockOverlayView.shortsKilled(this) {
            removeOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        attach(view, key = "shorts", pkg = YOUTUBE_PKG)
        ui.postDelayed({ if (overlayKey == "shorts") removeOverlay() }, 1800)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // vC54 §3 — CRIMSON LOCKDOWN
    // ═══════════════════════════════════════════════════════════════════════

    private fun showLockdownLock(pkg: String, label: String) {
        val key = "lockdown:$pkg"
        if (overlayKey == key) return
        removeOverlay()
        val view = SystemLockOverlayView.lockdownLock(this, label, PenaltyLockdown.bannerLine(this)) {
            removeOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
            ui.postDelayed({ launchSelf("quests") }, 260)
        }
        attach(view, key = key, pkg = pkg)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §6 — ADULT CONTENT SHIELD  (UI speaks only "Block Adult Contents")
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
            showAdultViolation(pkg)
        } else if (overlayKey == "adult") {
            removeOverlay() // page is clean again — lift the intervention
        }
    }

    private fun showAdultViolation(pkg: String) {
        if (overlayKey == "adult") return
        removeOverlay()
        val view = SystemLockOverlayView.pornViolation(this) {
            removeOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
            ui.postDelayed({ launchSelf("quests") }, 260)
        }
        attach(view, key = "adult", pkg = pkg)
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
