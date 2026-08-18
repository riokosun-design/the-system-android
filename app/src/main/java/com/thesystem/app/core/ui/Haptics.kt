package com.thesystem.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * THE SYSTEM's haptic vocabulary. Touch feedback is half of "addictive feel" —
 * every tap, clear, and failure lands in the user's hand, not just their eyes.
 * All calls are wrapped in runCatching so no device/OEM quirk can ever crash a frame.
 */
class SystemHaptics internal constructor(private val hf: HapticFeedback) {
    /** Feather-light — pull to refresh, tiny toggles. */
    fun tick() = runCatching { hf.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    /** Selection — tab switches, quest LOG taps. */
    fun select() = runCatching { hf.performHapticFeedback(HapticFeedbackType.ContextClick) }
    /** Dopamine thump — quest cleared, VC earned, battle won. */
    fun success() = runCatching { hf.performHapticFeedback(HapticFeedbackType.ContextClick) }
        .also { runCatching { hf.performHapticFeedback(HapticFeedbackType.LongPress) } }
    /** Heavy slam reserved for LEVEL UP. */
    fun slam() = runCatching { hf.performHapticFeedback(HapticFeedbackType.LongPress) }
    /** Denied — insufficient VC, locked gate, decay armed. */
    fun error() = runCatching { hf.performHapticFeedback(HapticFeedbackType.Reject) }
}

@Composable
fun rememberSystemHaptics(): SystemHaptics {
    val hf = LocalHapticFeedback.current
    return remember(hf) { SystemHaptics(hf) }
}
