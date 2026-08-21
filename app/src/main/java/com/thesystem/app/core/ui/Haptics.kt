package com.thesystem.app.core.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * THE SYSTEM's haptic vocabulary. Touch feedback is half of "addictive feel" —
 * every tap, clear, and failure lands in the user's hand, not just their eyes.
 *
 * Implemented on View.performHapticFeedback because Compose UI 1.7's
 * HapticFeedbackType only ships two constants (LongPress / TextHandleMove);
 * the platform constants give us the full palette. CONFIRM/REJECT need API 30+ —
 * older hunters get the LONG_PRESS fallback. Everything is wrapped in runCatching
 * so no OEM quirk can ever crash a frame.
 */
class SystemHaptics internal constructor(private val view: View?) {
    private fun fire(constant: Int) {
        runCatching { view?.performHapticFeedback(constant) }
    }

    private fun fireConfirmFamily(api30: Int, fallback: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fire(api30) else fire(fallback)
    }

    /** Feather-light — pull to refresh, tiny toggles. */
    fun tick() = fire(HapticFeedbackConstants.CLOCK_TICK)

    /** Selection — tab switches, quest LOG taps. */
    fun select() = fire(HapticFeedbackConstants.VIRTUAL_KEY)

    /** Dopamine thump — quest cleared, VC earned, battle won. */
    fun success() = fireConfirmFamily(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.LONG_PRESS)

    /** Heavy slam reserved for LEVEL UP. */
    fun slam() = fire(HapticFeedbackConstants.LONG_PRESS)

    /** Denied — insufficient VC, locked gate, decay armed. */
    fun error() = fireConfirmFamily(HapticFeedbackConstants.REJECT, HapticFeedbackConstants.LONG_PRESS)
}

@Composable
fun rememberSystemHaptics(): SystemHaptics {
    val view = LocalView.current
    return remember(view) { SystemHaptics(view) }
}
