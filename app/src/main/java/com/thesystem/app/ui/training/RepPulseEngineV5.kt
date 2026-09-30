package com.thesystem.app.ui.training

import androidx.camera.core.ImageProxy
import com.thesystem.app.core.sensors.ImuStabilityWitness
import com.thesystem.app.ui.training.estimate.LandmarkFrame
import com.thesystem.app.ui.training.estimate.PoseEstimator
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * REP PULSE ENGINE v5 — the android shell around [RepPulseCore].
 *
 * Replaces the v4 fixed-geometry pipeline (calibration gate + elbow/side-view
 * state machine) on the quest-proof surface. The v4 era demanded ONE camera
 * geometry (side view, full body incl. ankles); the field reality — phone flat
 * on the floor under the hunter's chest — starved it into a permanent
 * SEARCHING ("doesn't even track ONE rep", 4 field reports). v5 is
 * geometry-agnostic: it evaluates every motion channel the visible joints can
 * honestly provide, learns the hunter's own range, and counts full cycles
 * against adaptive bands.
 *
 * This shell only owns IO: rotation correction, estimator routing, the IMU
 * witness, and translation into the existing ViewModel/HUD contracts
 * ([PoseRepCounter.RepQuality], [PushupEngineV4.EngineStatus] for the push-up
 * ribbon, [PoseRepCounter.PoseStatus] for the squat ribbon). All counting
 * law lives in the pure core, where the 12-scenario field sim gates every
 * release (3 noise seeds, all green before this file was written).
 */
class RepPulseEngineV5(
    exercise: RepPulseCore.Exercise,
    strictness: RepPulseCore.Strictness,
    private val witness: ImuStabilityWitness,
    private val estimator: PoseEstimator,
    private val estimatorTag: () -> String,
    private val onRep: (Int, PoseRepCounter.RepQuality) -> Unit,
    private val onPhase: (PoseRepCounter.RepPhase, Float) -> Unit = { _, _ -> },
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
    private val onEngineStatus: (PushupEngineV4.EngineStatus) -> Unit = {},
    private val onPoseStatus: (PoseRepCounter.PoseStatus) -> Unit = {},
    private val onFrame12: (FloatArray) -> Unit = {},
    private val onDecision: (String) -> Unit = {},
    private val onTelemetry: (RepPulseCore.Telemetry) -> Unit = {},
) {

    var reps: Int = 0
        private set

    private var imgW = 1f
    private var imgH = 1f
    private var lastTelemetry: RepPulseCore.Telemetry? = null
    private var lastDtMs = 33L
    private var lastFrameAt = 0L

    private val core = RepPulseCore(
        exercise = exercise,
        strictness = strictness,
        onRep = ::coreRep,
        onPhase = ::corePhase,
        onStatus = ::coreStatus,
        onTelemetry = ::coreTelemetry,
    )

    // reusable scratch — the analyzer thread must not allocate per frame
    private val xs = FloatArray(33)
    private val ys = FloatArray(33)
    private val sc = FloatArray(33)

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        val rot = imageProxy.imageInfo.rotationDegrees
        if (rot == 90 || rot == 270) {
            imgW = imageProxy.height.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.width.toFloat().coerceAtLeast(1f)
        } else {
            imgW = imageProxy.width.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.height.toFloat().coerceAtLeast(1f)
        }
        val frame = estimator.estimate(imageProxy, rot, imgW, imgH)
        imageProxy.close()
        frameAdvance(frame)
    }

    fun reset() {
        reps = 0
        core.reset()
    }

    fun close() = Unit   // estimator is owned (and closed) by the screen's router

    private fun frameAdvance(frame: LandmarkFrame?) {
        val now = System.currentTimeMillis()
        lastDtMs = if (lastFrameAt == 0L) 33L else (now - lastFrameAt).coerceIn(1L, 400L)
        lastFrameAt = now

        if (frame != null) {
            for (j in 0 until 33) {
                xs[j] = frame.x(j); ys[j] = frame.y(j); sc[j] = frame.score[j]
            }
            onLandmarks((0 until 33).map { (frame.x(it) / imgW) to (frame.y(it) / imgH) })
            streamFrame12(frame)
            core.advance(xs, ys, sc, imgW, imgH, now, camStable = witness.stable)
        } else {
            core.advance(null, null, null, imgW, imgH, now, camStable = witness.stable)
        }
        reps = core.reps
    }

    // ── core → contracts ────────────────────────────────────────────────────
    private fun coreRep(n: Int, q: RepPulseCore.Quality) {
        val isAngle = q.troughKind.endsWith("ANGLE")
        onRep(
            n,
            PoseRepCounter.RepQuality(
                bottomDeg = if (isAngle) q.troughValue.toDouble() else -1.0,   // honest: only angle channels carry degrees
                topDeg = -1.0,                                                   // v5 learns bands, not absolute tops
                tempoMs = q.tempoMs,
                depthScore = q.depthScore.coerceIn(0f, 1f),
                symmetry = -1f,                                                  // v5 does not split L/R — says so
                cheatScore = 0f,
                engineVersion = "v5.0-${estimatorTag()}",
                lineDev = -1f,
                shoulderDropTorsos = -1f,
            ),
        )
        onDecision("COMMIT")
    }

    private fun corePhase(p: RepPulseCore.Phase, depthFrac: Float) {
        onPhase(PoseRepCounter.RepPhase.valueOf(p.name), depthFrac)
    }

    private fun coreStatus(s: RepPulseCore.Status) {
        // push-up ribbon shares the exact enum vocabulary — pass straight through
        onEngineStatus(PushupEngineV4.EngineStatus.valueOf(s.name))
        // squat ribbon speaks "what should the hunter do with the frame"
        val t = lastTelemetry
        onPoseStatus(
            when (s) {
                RepPulseCore.Status.SEARCHING -> when {
                    t == null || !t.personSeen -> PoseRepCounter.PoseStatus.POSE_NOT_DETECTED
                    t.bestRangeFrac < 0.35f -> PoseRepCounter.PoseStatus.FULL_BODY_NOT_VISIBLE
                    else -> PoseRepCounter.PoseStatus.TRACKING
                }
                RepPulseCore.Status.REJECT_POSE -> PoseRepCounter.PoseStatus.POSE_NOT_DETECTED
                RepPulseCore.Status.REJECT_DEPTH, RepPulseCore.Status.REJECT_FORM -> PoseRepCounter.PoseStatus.BAD_ANGLE
                RepPulseCore.Status.CAMERA_MOVED, RepPulseCore.Status.SETTLE -> PoseRepCounter.PoseStatus.WAITING
                else -> PoseRepCounter.PoseStatus.TRACKING
            },
        )
        when (s) {
            RepPulseCore.Status.CAMERA_MOVED -> onDecision("ABORT_IMU")
            RepPulseCore.Status.SETTLE -> onDecision("RELOCATE_SETTLE")
            RepPulseCore.Status.REJECT_DEPTH -> onDecision("REJECT_DEPTH")
            RepPulseCore.Status.REJECT_TEMPO -> onDecision("REJECT_TEMPO")
            RepPulseCore.Status.REJECT_POSE -> onDecision("ABORT_POSE")
            RepPulseCore.Status.REJECT_FORM -> onDecision("REJECT_FORM")
            else -> Unit
        }
    }

    private fun coreTelemetry(t: RepPulseCore.Telemetry) {
        lastTelemetry = t
        onTelemetry(t)
    }

    // ── shadow-corpus stream (Phase 2/3) — versioned layout, engine-tagged ──
    private fun streamFrame12(f: LandmarkFrame) {
        val elL = angleIf(f, LandmarkFrame.L_SHOULDER, LandmarkFrame.L_ELBOW, LandmarkFrame.L_WRIST)
        val elR = angleIf(f, LandmarkFrame.R_SHOULDER, LandmarkFrame.R_ELBOW, LandmarkFrame.R_WRIST)
        val knL = angleIf(f, LandmarkFrame.L_HIP, LandmarkFrame.L_KNEE, LandmarkFrame.L_ANKLE)
        val knR = angleIf(f, LandmarkFrame.R_HIP, LandmarkFrame.R_KNEE, LandmarkFrame.R_ANKLE)
        val t = lastTelemetry
        val chanOrd = when (t?.activeChannel) {
            "ELBOW-ANGLE" -> 0f; "KNEE-ANGLE" -> 1f; "SHOULDER-SPAN" -> 2f
            "HEAD-DEPTH" -> 3f; "SHOULDER-TRAVEL" -> 4f; "HIP-TRAVEL" -> 5f
            else -> 6f
        }
        onFrame12(
            floatArrayOf(
                (elL?.div(180f) ?: -2f),
                (elR?.div(180f) ?: -2f),
                (knL?.div(180f) ?: -2f),
                (knR?.div(180f) ?: -2f),
                t?.depthFrac ?: -1f,
                t?.bestRangeFrac ?: 0f,
                chanOrd / 6f,
                when (t?.phase) {
                    RepPulseCore.Phase.TOP -> 0.25f
                    RepPulseCore.Phase.DESCENDING -> 0.5f
                    RepPulseCore.Phase.BOTTOM -> 0.75f
                    RepPulseCore.Phase.ASCENDING -> 1f
                    else -> 0f
                },
                (lastDtMs.toFloat() / 100f).coerceIn(0f, 2f),
                if (t != null && t.warmupLeftMs > 0) t.warmupLeftMs / 2400f else 0f,
                if (t?.personSeen == true) 1f else 0f,
                -2f,   // reserved
            ),
        )
    }

    private fun angleIf(f: LandmarkFrame, a: Int, b: Int, c: Int): Float? {
        val floor = RepPulseCore.AVAIL_FLOOR
        if (!(f.score[a] >= floor && f.score[b] >= floor && f.score[c] >= floor)) return null
        val bax = f.x(a) - f.x(b); val bay = f.y(a) - f.y(b)
        val bcx = f.x(c) - f.x(b); val bcy = f.y(c) - f.y(b)
        val dot = bax * bcx + bay * bcy
        val m1 = sqrt(max(bax * bax + bay * bay, 1e-6f))
        val m2 = sqrt(max(bcx * bcx + bcy * bcy, 1e-6f))
        return Math.toDegrees(acos((dot / (m1 * m2)).toDouble().coerceIn(-1.0, 1.0))).toFloat()
    }
}
