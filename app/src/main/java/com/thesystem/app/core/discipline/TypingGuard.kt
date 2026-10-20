package com.thesystem.app.core.discipline

/**
 * §7 BRUTAL ANTI-UNINSTALL — the confession typing state machine.
 *
 * Pure Kotlin, zero Android deps: the UI feeds it raw text-field values and
 * wall-clock milliseconds; it owns the law.
 *
 * LAW:
 *  · Progress = the longest CORRECT prefix. One wrong character and every-
 *    thing after it is dead weight — progression stops until corrected.
 *  · Case-sensitive, punctuation-exact. No autocomplete charity.
 *  · More than [timeoutMs] between keystrokes, or leaving the screen → the
 *    confession resets to 0%. You do it in one honest sitting, or not at all.
 *  · Completion requires character-perfect equality with [target].
 */
class TypingGuard(
    val target: String,
    private val timeoutMs: Long = IDLE_RESET_MS,
) {

    /** Current raw field contents (may contain errors beyond the prefix). */
    var typed: String = ""
        private set

    /** Wall-clock of the last accepted keystroke; 0 = nothing typed yet. */
    var lastInputAt: Long = 0L
        private set

    /** True after [reset] logic ran at least once — UI can taunt accordingly. */
    var resets: Int = 0
        private set

    val isComplete: Boolean get() = typed == target

    /** Longest prefix of [typed] that matches [target] exactly. */
    fun correctPrefixLen(): Int {
        val n = minOf(typed.length, target.length)
        var i = 0
        while (i < n && typed[i] == target[i]) i++
        return i
    }

    /** Index of the first wrong character, or null when everything typed is correct. */
    fun firstErrorIndex(): Int? {
        val p = correctPrefixLen()
        return if (p < typed.length) p else null
    }

    /** 0f..1f — only the verified prefix counts. */
    fun progress01(): Float =
        if (target.isEmpty()) 1f else correctPrefixLen().toFloat() / target.length

    /**
     * Feed the new text-field value.
     * If the hunter went silent for longer than [timeoutMs] with an unfinished
     * confession, the ritual restarts from 0% before this keystroke lands.
     */
    fun onInput(newText: String, nowMs: Long) {
        if (!isComplete && typed.isNotEmpty() && nowMs - lastInputAt > timeoutMs) reset()
        typed = newText
        lastInputAt = nowMs
    }

    /**
     * Heartbeat (the UI ticks every second). Returns true when an idle reset
     * fired, so the screen can flash the punishment.
     */
    fun expireIfIdle(nowMs: Long): Boolean {
        if (isComplete || typed.isEmpty() || lastInputAt == 0L) return false
        if (nowMs - lastInputAt <= timeoutMs) return false
        reset()
        return true
    }

    /** The hunter left the screen — same law as going silent. */
    fun reset() {
        typed = ""
        lastInputAt = 0L
        resets += 1
    }

    companion object {
        const val IDLE_RESET_MS = 15_000L
        /** The release window granted for a perfect confession. */
        const val AMNESTY_MS = 60_000L

        /**
         * THE CONFESSION — word-for-word, case-sensitive (spec §7.4).
         * Editing this text is editing the law.
         */
        const val CONFESSION: String =
            "I am a weak, pathetic coward with zero self-control. While stronger people are out " +
                "there grinding, sweating, and building a life of power and respect, I am running " +
                "away like a loser because doing basic push-ups and staying off cheap distractions " +
                "is too hard for me. I would rather rot in bed, scroll mindless garbage, watch " +
                "filth, and stay trapped in a weak body with a broken mind than keep a single " +
                "promise to myself. By deleting THE SYSTEM, I openly confess that my laziness owns " +
                "me, my willpower is a joke, and I am disgusted by how easily I give up on my own " +
                "future."
    }
}
