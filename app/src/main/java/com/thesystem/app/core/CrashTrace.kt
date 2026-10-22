package com.thesystem.app.core

import android.content.Context
import android.util.Log

/**
 * CRASH TRACE — forensic memory of the last fatal exception (v0.25.1).
 *
 * Blind surgery is not engineering: when the app dies, the next launch must be
 * able to SAY exactly why. The uncaught-exception handler persists the stack
 * trace synchronously (commit — the process is dying, async apply can be lost),
 * then delegates to the previous handler so the death itself is unchanged.
 *
 * The nightly (debug) build surfaces the captured trace on the next cold start
 * as a small bottom card — screenshot it, report it, clear it. Release builds
 * stay silent by construction (MainActivity gates on BuildConfig.DEBUG).
 */
object CrashTrace {

    private const val FILE = "system_crash_trace"
    private const val KEY_STACK = "last_fatal_stack"
    private const val KEY_AT = "last_fatal_at"
    private var installed = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Installs the capture hook once, AFTER the anti-tamper perimeter — system
     *  denials keep their silence; everything else gets written to memory. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                prefs(context).edit()
                    .putString(KEY_STACK, "THREAD:${thread.name}\n" + Log.getStackTraceString(error).take(5800))
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The captured previous fatal stack, or null when the last launch died clean. */
    fun peek(context: Context): String? = prefs(context).getString(KEY_STACK, null)

    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_STACK).remove(KEY_AT).apply() }
    }
}
