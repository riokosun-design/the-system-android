package com.thesystem.app.ui.training

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * POSE REP COUNTER v2 — one reusable engine for PUSH-UP and SQUAT (P0 fix).
 *
 * Reported bugs fixed here, in order:
 *  1. DUPLICATE REPS — a rep only counts on a full TOP → DESCENDING → BOTTOM →
 *     ASCENDING → TOP cycle; being "down" twice without standing back up can
 *     never count twice.
 *  2. HALF REPS — the bottom must reach [depthDeg] before the ascending phase
 *     is accepted at all.
 *  3. JITTER / NOISE — EMA smoothing (0.45) + 2-frame phase confirmation +
 *     a 450 ms cooldown; a single noisy frame cannot move the state machine.
 *  4. NO VISIBILITY FEEDBACK — [status] reports POSE NOT DETECTED / MOVE BACK /
 *     FULL BODY NOT VISIBLE with the concrete reason, so the hunter knows how
 *     to fix the frame instead of staring at a frozen 0.
 *  5. FORM QUALITY — bottom/top angles, tempo and rep cadence are returned per
 *     rep; the same numbers feed the adaptive difficulty engine.
 *
 * Joints used (spec): PUSH-UP = shoulder·elbow·wrist + hip line warning;
 * SQUAT = hip·knee·ankle. Everything runs on-device; no frame is uploaded.
 */
class PoseRepCounter(
    val exercise: RepExercise,
    private val onRep: (Int, RepQuality) -> Unit,
    private val onPhase: (RepPhase, Float) -> Unit = { _, _ -> },
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
    private val onStatus: (PoseStatus) -> Unit = {},
) {

    enum class RepExercise { PUSHUP, SQUAT }

    /** Full cycle, in order — this is what kills duplicate/half reps. */
    enum class RepPhase { SEARCH, TOP, DESCENDING, BOTTOM, ASCENDING }

    enum class PoseStatus {
        WAITING, POSE_NOT_DETECTED, MOVE_BACK, FULL_BODY_NOT_VISIBLE, BAD_ANGLE, TRACKING
    }

    data class RepQuality(
        val bottomDeg: Double,
        val topDeg: Double,
        val tempoMs: Long,
        val depthScore: Float,   // 0..1 — how deep the rep actually went
        val symmetry: Float,     // 0..1 — left/right agreement
    )

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    var reps: Int = 0
        private set

    private var phase = RepPhase.SEARCH
    private var emaAngle: Double? = null
    private var candidate: RepPhase? = null
    private var candidateStreak = 0
    private var reachedBottom = false
    private var bottomAngle = 180.0
    private var descentStartAt = 0L
    private var lastRepAt = 0L
    private var lastSymmetry = 1f
    private var framesWithoutPose = 0
    private var imgW = 1f
    private var imgH = 1f
    private var lastStatus = PoseStatus.WAITING

    private companion object {
        const val EMA_ALPHA = 0.45
        const val CONFIRM_FRAMES = 2
        const val REP_COOLDOWN_MS = 450L
        const val LIKELIHOOD_FLOOR = 0.35f
        const val MIN_REP_MS = 350L          // faster than this = camera noise, not a rep
    }

    // ── thresholds ───────────────────────────────────────────────────────────
    private val topDeg: Double get() = if (exercise == RepExercise.PUSHUP) 158.0 else 162.0
    private val descendDeg: Double get() = if (exercise == RepExercise.PUSHUP) 140.0 else 145.0
    private val depthDeg: Double get() = if (exercise == RepExercise.PUSHUP) 95.0 else 105.0

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        val media = imageProxy.image ?: run { imageProxy.close(); return }
        imgW = imageProxy.width.toFloat().coerceAtLeast(1f)
        imgH = imageProxy.height.toFloat().coerceAtLeast(1f)
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { pose -> evaluate(pose) }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Reset for a new session (also used by the "reset counter" test case). */
    fun reset() {
        reps = 0
        phase = RepPhase.SEARCH
        emaAngle = null
        candidate = null
        candidateStreak = 0
        reachedBottom = false
        bottomAngle = 180.0
    }

    fun close() = detector.close()

    // ── the state machine ────────────────────────────────────────────────────
    private fun evaluate(pose: Pose) {
        val marks = pose.allPoseLandmarks
        onLandmarks(marks.map { (it.position.x / imgW) to (it.position.y / imgH) })

        // ── visibility diagnostics ───────────────────────────────────────────
        val visible = visibility(pose)
        when {
            visible == PoseStatus.POSE_NOT_DETECTED || visible == PoseStatus.FULL_BODY_NOT_VISIBLE -> {
                framesWithoutPose++
                if (framesWithoutPose >= 3) report(visible)
                return
            }
            visible == PoseStatus.MOVE_BACK -> {
                framesWithoutPose++
                if (framesWithoutPose >= 5) report(PoseStatus.MOVE_BACK)
                return
            }
            visible == PoseStatus.BAD_ANGLE -> {
                framesWithoutPose++
                if (framesWithoutPose >= 5) report(PoseStatus.BAD_ANGLE)
                return
            }
            else -> {
                framesWithoutPose = 0
                report(PoseStatus.TRACKING)
            }
        }

        val joints = when (exercise) {
            RepExercise.PUSHUP -> Triple(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST) to
                Triple(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
            RepExercise.SQUAT -> Triple(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE) to
                Triple(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
        }
        val left = angleAt(pose, joints.first.first, joints.first.second, joints.first.third)
        val right = angleAt(pose, joints.second.first, joints.second.second, joints.second.third)
        val raw = when {
            left != null && right != null -> (left + right) / 2.0
            left != null -> left
            right != null -> right
            else -> return
        }
        lastSymmetry = if (left != null && right != null) {
            (1.0 - (abs(left - right) / 60.0)).coerceIn(0.0, 1.0).toFloat()
        } else 0.6f

        val smoothed = emaAngle?.let { it + EMA_ALPHA * (raw - it) } ?: raw
        emaAngle = smoothed
        step(smoothed)
    }

    private fun step(angle: Double) {
        val now = System.currentTimeMillis()

        // candidate phase from the smoothed angle, confirmed over N frames
        val wanted = when {
            angle >= topDeg -> RepPhase.TOP
            angle <= depthDeg -> RepPhase.BOTTOM
            angle >= descendDeg -> if (phase == RepPhase.BOTTOM || phase == RepPhase.ASCENDING) RepPhase.ASCENDING else RepPhase.DESCENDING
            else -> RepPhase.DESCENDING
        }
        if (wanted == candidate) candidateStreak++ else { candidate = wanted; candidateStreak = 1 }

        // depth hint 0..1 for the reticle (1 = deepest)
        val depth = ((topDeg - angle) / (topDeg - depthDeg)).coerceIn(0.0, 1.0).toFloat()

        if (candidateStreak >= CONFIRM_FRAMES && wanted != phase) {
            val previous = phase
            phase = wanted
            when (wanted) {
                RepPhase.BOTTOM -> {
                    reachedBottom = true
                    bottomAngle = angle
                    if (previous == RepPhase.TOP || previous == RepPhase.DESCENDING) descentStartAt = now
                }
                RepPhase.TOP -> {
                    // a rep exists only if we came up from a confirmed bottom
                    if (reachedBottom && previous == RepPhase.ASCENDING &&
                        now - lastRepAt > REP_COOLDOWN_MS && now - descentStartAt > MIN_REP_MS
                    ) {
                        lastRepAt = now
                        reps += 1
                        onRep(
                            reps,
                            RepQuality(
                                bottomDeg = bottomAngle,
                                topDeg = angle,
                                tempoMs = now - descentStartAt,
                                depthScore = ((topDeg - bottomAngle) / (topDeg - depthDeg)).coerceIn(0.0, 1.0).toFloat(),
                                symmetry = lastSymmetry,
                            )
                        )
                    }
                    reachedBottom = false
                    descentStartAt = 0L
                }
                else -> Unit
            }
        }
        onPhase(phase, if (phase == RepPhase.BOTTOM) 1f else depth)
    }

    // ── frame-quality gate: the "why is nothing counting" answers ───────────
    private fun visibility(pose: Pose): PoseStatus {
        val need = if (exercise == RepExercise.PUSHUP) {
            listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW)
        } else {
            listOf(
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
                PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
            )
        }
        val present = need.count { (pose.getPoseLandmark(it)?.inFrameLikelihood ?: 0f) >= LIKELIHOOD_FLOOR }
        if (present == 0) return PoseStatus.POSE_NOT_DETECTED
        if (present < need.size) return if (exercise == RepExercise.SQUAT) PoseStatus.FULL_BODY_NOT_VISIBLE else PoseStatus.MOVE_BACK

        // body inside the frame, not clipped at an edge
        val xs = need.mapNotNull { pose.getPoseLandmark(it)?.position?.x }
        val ys = need.mapNotNull { pose.getPoseLandmark(it)?.position?.y }
        if (xs.isEmpty() || ys.isEmpty()) return PoseStatus.POSE_NOT_DETECTED
        val clipped = xs.any { it <= 2f || it >= imgW - 2f } || ys.any { it <= 2f || it >= imgH - 2f }
        if (clipped) return if (exercise == RepExercise.SQUAT) PoseStatus.FULL_BODY_NOT_VISIBLE else PoseStatus.MOVE_BACK

        // too small in frame = phone too far / wrong angle
        val spanX = (xs.max() - xs.min())
        val spanY = (ys.max() - ys.min())
        if (exercise == RepExercise.SQUAT && spanY < imgH * 0.35f) return PoseStatus.MOVE_BACK
        if (exercise == RepExercise.PUSHUP && spanX < imgW * 0.22f) return PoseStatus.MOVE_BACK

        // PUSH-UP: the hip line must stay roughly straight — a sagging hip is a
        // bad camera angle (or a bad rep); we ask for a re-frame, we don't count.
        if (exercise == RepExercise.PUSHUP) {
            val sh = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
            val hip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
            val ank = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
            if (sh != null && hip != null && ank != null) {
                val bodyAngle = angleBetween(sh.position.x, sh.position.y, hip.position.x, hip.position.y, ank.position.x, ank.position.y)
                if (bodyAngle < 130.0) return PoseStatus.BAD_ANGLE
            }
        }
        return PoseStatus.TRACKING
    }

    private fun report(status: PoseStatus) {
        if (status != lastStatus) { lastStatus = status; onStatus(status) }
    }

    private fun angleAt(p: Pose, a: Int, b: Int, c: Int): Double? {
        val pa = p.getPoseLandmark(a) ?: return null
        val pb = p.getPoseLandmark(b) ?: return null
        val pc = p.getPoseLandmark(c) ?: return null
        if (pa.inFrameLikelihood < LIKELIHOOD_FLOOR ||
            pb.inFrameLikelihood < LIKELIHOOD_FLOOR ||
            pc.inFrameLikelihood < LIKELIHOOD_FLOOR
        ) return null
        return angleBetween(pa.position.x, pa.position.y, pb.position.x, pb.position.y, pc.position.x, pc.position.y)
    }

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double {
        val bax = ax - bx
        val bay = ay - by
        val bcx = cx - bx
        val bcy = cy - by
        val dot = bax * bcx + bay * bcy
        val m1 = sqrt(max(bax * bax + bay * bay, 1e-6f))
        val m2 = sqrt(max(bcx * bcx + bcy * bcy, 1e-6f))
        val cos = (dot / (m1 * m2)).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }
}
