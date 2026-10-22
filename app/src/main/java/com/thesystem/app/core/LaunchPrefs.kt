package com.thesystem.app.core

import android.content.Context

/**
 * LAUNCH PREFS — first-frame ritual memory (v0.25.0, spec §2).
 *
 * The master intro cinematic is a ONCE-EVER ceremony: it plays only on the
 * very first app open, above every other gate. After the hunter enters (or
 * cuts through with the final tap), the flag seals and the normal
 * splash → gate chain owns every future launch.
 */
object LaunchPrefs {

    private const val FILE = "system_launch"
    private const val KEY_MASTER_INTRO_SEEN = "master_intro_seen"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True while the first-ever master cinematic still owes this vessel a viewing. */
    fun masterIntroPending(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_MASTER_INTRO_SEEN, false)

    /** The hunter entered THE SYSTEM through the master cinematic — never plays again. */
    fun markMasterIntroSeen(context: Context) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_MASTER_INTRO_SEEN, true).apply()
        }
    }
}
