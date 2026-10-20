package com.thesystem.app.core.discipline

import android.content.Context
import java.time.LocalDate

/**
 * DISCIPLINE ENGINE — local verdict cache (v0.24.0, spec §5–§7).
 *
 * The accessibility guard cannot (and must not) hit the network on every
 * window event, so the app-side sync path (dashboard refresh, quest
 * completion) stamps the *server-confirmed* daily-quest verdict here.
 * Reading it is synchronous, offline-safe, and never client-authoritative
 * for XP/VC — it only gates the entertainment blocker.
 *
 * Default posture = DENIED: if the app was never opened today, quests are
 * treated as incomplete and social media stays locked. Weak hunters do not
 * get entertainment.
 */
object DisciplinePrefs {

    private const val FILE = "system_discipline"
    private const val KEY_QUESTS_DATE = "quests_date"          // ISO yyyy-MM-dd (client calendar day of last verdict)
    private const val KEY_QUESTS_DONE = "quests_done"          // all mandatory daily quests complete (stamped by app)
    private const val KEY_RELEASE_UNTIL = "uninstall_release_until" // §7: confession accepted → 60s amnesty
    private const val KEY_PORN_SHIELD = "porn_shield"          // §6: always-on adult interception
    private const val KEY_SOCIAL_LOCK = "social_lock"          // §5: quest-locked social blocker
    private const val KEY_DNS_GUARD = "dns_guard"              // §6.3: opt-in DNS family filter

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Stamped by the app after every server-confirmed quest sync.
     *  @param todayIso server-shape "yyyy-MM-dd"; @param allDone mandatory (non-trial) quests all complete. */
    fun setQuestsDone(context: Context, todayIso: String, allDone: Boolean) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_QUESTS_DATE, todayIso)
                .putBoolean(KEY_QUESTS_DONE, allDone)
                .apply()
        }
    }

    /** True only when today's mandatory daily quests are verifiably complete. */
    fun isQuestLockLifted(context: Context): Boolean {
        val p = prefs(context)
        return p.getString(KEY_QUESTS_DATE, null) == LocalDate.now().toString() &&
            p.getBoolean(KEY_QUESTS_DONE, false)
    }

    /** §7 — confession accepted: the anti-uninstall guard sleeps for [ms]. */
    fun releaseUninstallGuard(context: Context, ms: Long) {
        runCatching {
            prefs(context).edit()
                .putLong(KEY_RELEASE_UNTIL, System.currentTimeMillis() + ms)
                .apply()
        }
    }

    fun uninstallGuardReleased(context: Context): Boolean =
        prefs(context).getLong(KEY_RELEASE_UNTIL, 0L) > System.currentTimeMillis()

    fun isPornShieldOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PORN_SHIELD, true)
    fun setPornShield(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_PORN_SHIELD, on).apply()

    fun isSocialLockOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOCIAL_LOCK, true)
    fun setSocialLock(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_SOCIAL_LOCK, on).apply()

    fun isDnsGuardOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DNS_GUARD, false)
    fun setDnsGuard(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_DNS_GUARD, on).apply()
}
