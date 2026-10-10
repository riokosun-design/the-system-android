package com.thesystem.app.ui.training

/**
 * RETAINED TYPE CONTRACTS — PushupEngineV4 still defines its EngineStatus
 * ribbon vocabulary here (the quest HUD translates v5 telemetry into it), and
 * its constructor references this profile. The gate machinery that USED to
 * mint these was retired with the v5 unification (spec Phase 3): no screen
 * creates a CalibProfile anymore; the types stay so the engine class compiles.
 */
data class CalibProfile(
    val thetaTop: Double,       // adaptive lockout threshold (deg)
    val thetaDepth: Double,     // adaptive bottom threshold (deg)
    val torsoLenPx: Float,      // shoulder→hip at calibration, px
    val shoulderY0: Float,      // shoulder-mid Y at lockout, px
    val viewQuality: Float,     // 1.0 = side · 0.7 = three-quarter
    val level: CalibLevel,
    val lumaMean: Int,
    val estimatorSource: String = "unknown",
)

enum class CalibLevel { GREEN, YELLOW }
