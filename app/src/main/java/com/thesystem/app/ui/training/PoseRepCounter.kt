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

    // ── FRONT-VIEW push-up window (phone flat on floor, camera up) ───────────
    // From this geometry the elbow's 2D angle barely changes (elbows travel
    // toward/away from the lens) — the OLD angle engine could never re-enter
    // TOP, which is why hunters saw 0 reps forever. The honest observable from
    // below is APPARENT SHOULDER WIDTH: get low → closer to the lens → span
    // grows; push up → farther → span shrinks. Session-adaptive lo/hi window
    // calibrates itself inside the first rep, no fixed thresholds needed.
    private var emaSpan: Float? = null
    private var spanLo = Float.MAX_VALUE
    private var spanHi = 0f
    private var bottomSpan = 0f

    private companion object {
        const val EMA_ALPHA = 0.45
        const val CONFIRM_FRAMES = 2
        const val REP_COOLDOWN_MS = 450L
        const val LIKELIHOOD_FLOOR = 0.35f
        const val MIN_REP_MS = 350L          // faster than this = camera noise, not a rep

        // front-span push-up latching bands (fraction of observed span window)
        const val SPAN_EMA = 0.5f
        const val SPAN_BOTTOM = 0.66  // this close to the lens = chest down
        const val SPAN_TOP = 0.34     // this far = locked out
        const val MIN_SPAN_RANGE_PX = 24f  // below this the user hasn't actually moved
    }

    // ── thresholds ───────────────────────────────────────────────────────────
    private val topDeg: Double get() = if (exercise == RepExercise.PUSHUP) 158.0 else 162.0
    private val descendDeg: Double get() = if (exercise == RepExercise.PUSHUP) 140.0 else 145.0
    private val depthDeg: Double get() = if (exercise == RepExercise.PUSHUP) 95.0 else 105.0

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        val media = imageProxy.image ?: run { imageProxy.close(); return }
        // Landmark coordinates live in the ROTATED image space — swap buffer
        // dims for 90/270 or every span/threshold/mesh normalization lies.
        val rot = imageProxy.imageInfo.rotationDegrees
        if (rot == 90 || rot == 270) {
            imgW = imageProxy.height.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.width.toFloat().coerceAtLeast(1f)
        } else {
            imgW = imageProxy.width.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.height.toFloat().coerceAtLeast(1f)
        }
        val image = InputImage.fromMediaImage(media, rot)
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
        emaSpan = null
        spanLo = Float.MAX_VALUE
        spanHi = 0f
        bottomSpan = 0f
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

        // ── PUSH-UP = front-span cycle (floor + front camera geometry) ──────
        if (exercise == RepExercise.PUSHUP) {
            val lS = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
            val rS = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
            if (lS == null || rS == null ||
                lS.inFrameLikelihood < LIKELIHOOD_FLOOR || rS.inFrameLikelihood < LIKELIHOOD_FLOOR
            ) return
            val span = abs(lS.position.x - rS.position.x)
            val v = emaSpan?.let { it + SPAN_EMA * (span - it) } ?: span
            emaSpan = v
            if (v < spanLo) spanLo = v
            if (v > spanHi) spanHi = v
            val range = spanHi - spanLo
            if (range < MIN_SPAN_RANGE_PX) return  // window not calibrated yet — keep watching
            val frac = ((v - spanLo) / range).coerceIn(0f, 1f).toDouble()
            stepSpan(frac, v)
            return
        }

        // ── SQUAT = hip·knee·ankle angle cycle (back camera, side/full body) ─
        val joints = Triple(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE) to
            Triple(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
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

    /** Span-cycle machine for floor/front push-ups: BOTTOM = close (span big),
     *  TOP = far (span small). Same full-cycle discipline as [step]. */
    private fun stepSpan(frac: Double, span: Float) {
        val now = System.currentTimeMillis()
        val wanted = when {
            frac >= SPAN_BOTTOM -> RepPhase.BOTTOM
            frac <= SPAN_TOP -> RepPhase.TOP
            phase == RepPhase.BOTTOM || phase == RepPhase.ASCENDING -> RepPhase.ASCENDING
            else -> RepPhase.DESCENDING
        }
        if (wanted == candidate) candidateStreak++ else { candidate = wanted; candidateStreak = 1 }

        if (candidateStreak >= CONFIRM_FRAMES && wanted != phase) {
            val previous = phase
            phase = wanted
            when (wanted) {
                RepPhase.BOTTOM -> {
                    reachedBottom = true
                    bottomSpan = span
                    if (previous == RepPhase.TOP || previous == RepPhase.DESCENDING) descentStartAt = now
                }
                RepPhase.TOP -> {
                    if (reachedBottom && previous == RepPhase.ASCENDING &&
                        now - lastRepAt > REP_COOLDOWN_MS && now - descentStartAt > MIN_REP_MS
                    ) {
                        lastRepAt = now
                        reps += 1
                        onRep(
                            reps,
                            RepQuality(
                                bottomDeg = bottomSpan.toDouble(),
                                topDeg = span.toDouble(),
                                tempoMs = now - descentStartAt,
                                depthScore = frac.toFloat(),
                                symmetry = 1f,
                            )
                        )
                    }
                    reachedBottom = false
                    descentStartAt = 0L
                }
                else -> Unit
            }
        }
        onPhase(phase, frac.toFloat())
    }

    // ── frame-quality gate: the "why is nothing counting" answers ───────────
    private fun visibility(pose: Pose): PoseStatus {
        // PUSH-UP (front-span): shoulders + nose is all the engine asks for —
        // elbows are NOT required from the floor view (the old list demanded
        // them and then measured nothing, one of the zero-rep roots).
        val need = if (exercise == RepExercise.PUSHUP) {
            listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.NOSE)
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
        if (exercise == RepExercise.PUSHUP && spanX < imgW * 0.20f) return PoseStatus.MOVE_BACK

        // NOTE: the old "hip-line straightness" gate is gone for PUSH-UP — from
        // a floor/front camera the shoulder·hip·ankle angle foreshortens below
        // 130° on nearly every frame, which hard-blocked ALL counting.
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
