package com.thesystem.app.ui.training

/**
 * ENGINE STATUS VOCABULARY (vC58).
 *
 * The v4 engine class is retired — RepPulseCore owns every counting law and
 * RepPulseEngineV5 owns the Android shell. This enum survives as the HUD
 * ribbon vocabulary: RepPulseEngineV5 mirrors RepPulseCore.Status into these
 * names 1:1 via valueOf, so the two enums must stay name-identical.
 */
class PushupEngineV4 private constructor() {

    enum class EngineStatus {
        SEARCHING, READY, DESCENDING, ASCENDING,
        VERIFYING, REJECT_DEPTH, REJECT_FORM, REJECT_TEMPO, REJECT_POSE,
        CAMERA_MOVED, SETTLE, DISPUTED,
        /** vC58 TRACK-HOLD law — blackout honoured as temporary, rep frozen. */
        TRACKING_LOST,
        /** Blackout ended inside the hold window — frozen cycle resumed. */
        TRACKING_RECOVERED,
    }

    companion object {
        init {
            error("PushupEngineV4 is a type carrier — counting law lives in RepPulseCore")
        }
    }
}
