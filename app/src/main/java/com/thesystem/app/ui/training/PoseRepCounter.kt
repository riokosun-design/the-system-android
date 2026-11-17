package com.thesystem.app.ui.training

/**
 * REP UI CONTRACTS — the type vocabulary shared by the engine shell,
 * ViewModels and HUD ribbons (vC58).
 *
 * This file once carried the ML Kit PoseRepCounter class. That era is over:
 * the class was dead code (never instantiated — RepPulseCore/RepPulseEngineV5
 * own counting law) and ML Kit is strictly forbidden in this app. What
 * remains is the pure type carrier every surface already speaks.
 */
class PoseRepCounter private constructor() {

    enum class RepExercise { PUSHUP, SQUAT }

    /** Full cycle, in order — this is what kills duplicate/half reps. */
    enum class RepPhase { SEARCH, TOP, DESCENDING, BOTTOM, ASCENDING }

    enum class PoseStatus {
        WAITING, POSE_NOT_DETECTED, MOVE_BACK, FULL_BODY_NOT_VISIBLE, BAD_ANGLE, TRACKING,
        /** Tracking lost mid-cycle — the rep is HELD, not cleared (TRACK-HOLD law). */
        LOST_HOLD,
    }

    data class RepQuality(
        val bottomDeg: Double,
        val topDeg: Double,
        val tempoMs: Long,
        val depthScore: Float,   // 0..1 — how deep the rep actually went
        val symmetry: Float,     // 0..1 — left/right agreement
        /** decaying cheat accumulator at commit (−1 = not reported). */
        val cheatScore: Float = -1f,
        /** engine lineage that judged this rep. */
        val engineVersion: String = "v5.0",
        /** body-line deviation at the deepest frame, torsos (−1 = not measured). */
        val lineDev: Float = -1f,
        /** max shoulder drop witnessed, torsos (−1 = not measured). */
        val shoulderDropTorsos: Float = -1f,
    )

    companion object {
        init {
            error("PoseRepCounter is a type carrier — counting law lives in RepPulseCore")
        }
    }
}
