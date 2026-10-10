package com.thesystem.app.ui.training

import androidx.camera.core.ImageProxy
import com.thesystem.app.core.sensors.ImuStabilityWitness
import com.thesystem.app.ui.training.estimate.PoseEstimatorRouter

/**
 * REP COUNTER SERVICE (spec Phase 3 — unified tracking hook/service) — the
 * SINGLE doorway to the sim-proven v5 pulse core for every rep-counting
 * surface. Daily quests AND ranked 1v1 wars row STANDARD: the STRICT row is
 * reserved for fully-visible side geometry (sim scenario K proves STRICT
 * REFUSES floor posture by design — the exact posture a battle phone lives
 * in), so STANDARD's laws (full-cycle, half-rep rejection, tempo bounds,
 * relocation slew guard, pose-gap abort) guard the war instead. The legacy
 * v4 calibration-gate / angle-machine pipeline is retired for good.
 *
 * Ranked integrity does NOT thin out with the gate gone: IMU witness +
 * STANDARD bands stay on-device, and §8's signed rep events + server
 * plausibility referee keep the final word over every score.
 */
interface RepTracker {
    fun process(proxy: ImageProxy)

    /** Engine shell close — the estimator/router stay owned by the screen. */
    fun close()
}

object RepCounterService {

    fun start(
        exercise: PoseRepCounter.RepExercise,
        strictness: RepPulseCore.Strictness,
        witness: ImuStabilityWitness,
        router: PoseEstimatorRouter,
        onRep: (Int, PoseRepCounter.RepQuality) -> Unit,
        onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
    ): RepTracker {
        val engine = RepPulseEngineV5(
            exercise = when (exercise) {
                PoseRepCounter.RepExercise.SQUAT -> RepPulseCore.Exercise.SQUAT
                PoseRepCounter.RepExercise.PUSHUP -> RepPulseCore.Exercise.PUSHUP
            },
            strictness = strictness,
            witness = witness,
            estimator = router,
            estimatorTag = { router.activeSource },
            onRep = onRep,
            onLandmarks = onLandmarks,
        )
        return object : RepTracker {
            override fun process(proxy: ImageProxy) = engine.process(proxy)
            override fun close() = engine.close()
        }
    }
}
