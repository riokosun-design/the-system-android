package com.thesystem.app.service

import android.content.Context

/**
 * PENALTY LOCKDOWN (vC54 §3) — the crimson device-side gauntlet.
 *
 * The server already judges missed days (XP decay, rank demotion, the camera
 * Survival Trial). This layer is different: it is the DEVICE's answer to a
 * watchdog breach. While it stands, Instagram / YouTube / Snapchat stay
 * sealed — regardless of the standing toggles — until the REDEMPTION GAUNTLET
 * is cleared:
 *
 *      1. 100% of pending Daily Quests completed.
 *      2. 1 Rated Chess game victory (MENTAL WAR).
 *      3. 4 Arena challenge wins (push-up / squat verdicts, server-judged).
 *
 * state lives in plain prefs so a killed process, a reboot or a dead battery
 * cannot wash the sentence. Redemption signals land from the parts of the app
 * that already talk to the server (quest sync stamps, chess finish, arena
 * verdict), so a wipe of the app's claims without doing the work frees
 * nothing: counters only advance through real events.
 */
object PenaltyLockdown {

    private const val PREFS = "system_lockdown"
    private const val KEY_ACTIVE = "active"
    private const val KEY_CAUSE = "cause"
    private const val KEY_SINCE = "since_ms"
    private const val KEY_QUEST_TOTAL = "quest_total"
    private const val KEY_QUEST_DONE = "quest_done"
    private const val KEY_QUESTS_REDEEMED = "quests_redeemed"
    private const val KEY_CHESS_WINS = "chess_wins"      // target: 1
    private const val KEY_ARENA_WINS = "arena_wins"      // target: 4

    const val CHESS_TARGET = 1
    const val ARENA_TARGET = 4

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    fun engage(context: Context, cause: String) {
        val p = prefs(context)
        if (p.getBoolean(KEY_ACTIVE, false)) return
        p.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_CAUSE, cause)
            .putLong(KEY_SINCE, System.currentTimeMillis())
            .putInt(KEY_QUEST_DONE, 0)
            .putBoolean(KEY_QUESTS_REDEEMED, false)
            .putInt(KEY_CHESS_WINS, 0)
            .putInt(KEY_ARENA_WINS, 0)
            // keep quest_total from the last sync if present — never zero a known count
            .apply()
        QuestNotifier.circadian(
            context,
            title = "CRIMSON LOCKDOWN ENGAGED",
            body = "$cause Redemption: all daily quests · 1 rated chess win · 4 arena wins. " +
                "Instagram, YouTube and Snapchat are sealed until then.",
            notifId = 6700,
        )
    }

    /** Fed by the dashboard's server-confirmed sync — client claims count nothing. */
    fun onQuestStamp(context: Context, openMandatory: Int, totalMandatory: Int) {
        if (!isActive(context)) return
        prefs(context).edit()
            .putInt(KEY_QUEST_TOTAL, totalMandatory)
            .putInt(KEY_QUEST_DONE, (totalMandatory - openMandatory).coerceAtLeast(0))
            .putBoolean(KEY_QUESTS_REDEEMED, totalMandatory > 0 && openMandatory == 0)
            .apply()
        auditRedemption(context)
    }

    /** A MENTAL WAR (rated) victory — the chess screen stamps it at the finish. */
    fun recordChessWin(context: Context) {
        if (!isActive(context)) return
        prefs(context).edit()
            .putInt(KEY_CHESS_WINS, (prefs(context).getInt(KEY_CHESS_WINS, 0) + 1).coerceAtMost(CHESS_TARGET))
            .apply()
        auditRedemption(context)
    }

    /** One server-judged arena victory (push-up / squat battle verdict). */
    fun recordArenaWin(context: Context) {
        if (!isActive(context)) return
        prefs(context).edit()
            .putInt(KEY_ARENA_WINS, (prefs(context).getInt(KEY_ARENA_WINS, 0) + 1).coerceAtMost(ARENA_TARGET))
            .apply()
        auditRedemption(context)
    }

    fun counts(context: Context): Triple<Int, Int, Int> {
        val p = prefs(context)
        return Triple(
            p.getInt(KEY_QUEST_DONE, 0),
            p.getInt(KEY_CHESS_WINS, 0).coerceAtMost(CHESS_TARGET),
            p.getInt(KEY_ARENA_WINS, 0).coerceAtMost(ARENA_TARGET),
        )
    }

    fun questTotal(context: Context): Int = prefs(context).getInt(KEY_QUEST_TOTAL, 0)

    /** The crimson banner body — exact ledger the hunter must close. */
    fun bannerLine(context: Context): String {
        val (q, c, a) = counts(context)
        val total = questTotal(context).coerceAtLeast(q)
        return "Quests: $q/$total • Chess Wins: $c/$CHESS_TARGET • Arena Wins: $a/$ARENA_TARGET"
    }

    private fun auditRedemption(context: Context) {
        val p = prefs(context)
        val questsOk = p.getBoolean(KEY_QUESTS_REDEEMED, false)
        val chessOk = p.getInt(KEY_CHESS_WINS, 0) >= CHESS_TARGET
        val arenaOk = p.getInt(KEY_ARENA_WINS, 0) >= ARENA_TARGET
        if (questsOk && chessOk && arenaOk) {
            p.edit().putBoolean(KEY_ACTIVE, false).apply()
            QuestNotifier.circadian(
                context,
                title = "REDEMPTION ACCEPTED",
                body = "The gauntlet is cleared. The crimson seal lifts — do not make the System return. 💀",
                notifId = 6701,
            )
        }
    }
}
