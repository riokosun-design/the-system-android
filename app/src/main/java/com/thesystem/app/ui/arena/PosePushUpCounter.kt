package com.thesystem.app.ui.arena

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ML Kit push-up counter (v3 — vector joint-angle state machine).
 *
 * 100% on-device: frames never leave the phone; only rep counts sync.
 *
 * Detection model
 * ---------------
 *  • Elbow angle via the cosine vector formula at the elbow joint B:
 *        BA = shoulder - elbow ; BC = wrist - elbow
 *        angle = acos((BA · BC) / (|BA| · |BC|))
 *    Both arms are measured and FUSED (mean when both visible, single arm
 *    otherwise) — floor angles occlude one arm constantly, a single-arm
 *    gate dropped reps.
 *  • Two-state machine, no counting inside the loop: a rep increments only
 *    on a full UP -> DOWN -> UP cycle.
 *        UP   : fused elbow angle > [UP_DEG] (locked-out top)
 *        DOWN : fused elbow angle < [DOWN_DEG] (chest toward the floor,
 *               shoulder depth collapsed toward the wrist line)
 *  • EMA smoothing + CONFIRM_FRAMES consecutive-frame confirmation kills
 *    ML Kit's ±10–15° frame jitter.
 *  • [REP_COOLDOWN_MS] debounce between reps prevents double-counts.
 *
 * Front-camera note: the elbow ANGLE is mirror-invariant, so no x inversion
 * is needed for counting; the hologram mesh overlay mirrors x itself.
 */
class PosePushUpCounter(
    private val onRep: (Int) -> Unit,
    // normalized landmarks (0f..1f image space) for the hologram mesh
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
) {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    var reps: Int = 0
        private set

    private var wasDown = false
    private var lastRepAt = 0L
    private var emaAngle: Double? = null
    private var downStreak = 0
    private var upStreak = 0
    private var imgW = 1f
    private var imgH = 1f

    private companion object {
        const val DOWN_DEG = 90.0     // chest-to-floor: elbow well past right angle
        const val UP_DEG = 160.0     // locked-out top
        const val EMA_ALPHA = 0.45
        const val CONFIRM_FRAMES = 2
        const val REP_COOLDOWN_MS = 400L
        const val LIKELIHOOD_FLOOR = 0.35f // low-light tolerant
    }

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        val media = imageProxy.image ?: run { imageProxy.close(); return }
        imgW = imageProxy.width.toFloat().coerceAtLeast(1f)
        imgH = imageProxy.height.toFloat().coerceAtLeast(1f)
        // rotationDegrees handles orientation; angles are mirror-invariant.
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { pose -> evaluate(pose) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun evaluate(pose: Pose) {
        // hologram mesh feed — normalized so the overlay is resolution-agnostic
        onLandmarks(pose.allPoseLandmarks.map { (it.position.x / imgW) to (it.position.y / imgH) })

        // Fuse both arms: mean when both visible, fallback to whichever arm
        // the floor camera can see.
        val left = elbowAngleDeg(
            pose,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_WRIST,
        )
        val right = elbowAngleDeg(
            pose,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_WRIST,
        )
        val raw = when {
            left != null && right != null -> (left + right) / 2.0
            left != null -> left
            right != null -> right
            else -> return // both limbs occluded this frame — hold state, resume next frame
        }

        val smoothed = emaAngle?.let { it + EMA_ALPHA * (raw - it) } ?: raw
        emaAngle = smoothed
        val now = System.currentTimeMillis()

        when {
            smoothed < DOWN_DEG -> { downStreak++; upStreak = 0 }
            smoothed > UP_DEG -> { upStreak++; downStreak = 0 }
            else -> { downStreak = 0; upStreak = 0 } // deadband — neither state may latch
        }

        // UP -> DOWN latch, then DOWN -> UP completes exactly one rep.
        if (!wasDown && downStreak >= CONFIRM_FRAMES) wasDown = true
        if (wasDown && upStreak >= CONFIRM_FRAMES && now - lastRepAt > REP_COOLDOWN_MS) {
            wasDown = false
            upStreak = 0
            lastRepAt = now
            reps += 1
            onRep(reps)
        }
    }

    /**
     * Interior elbow angle (0°–180°) using the cosine vector formula.
     * Returns null when any of the three landmarks is unreliable.
     */
    private fun elbowAngleDeg(p: Pose, shoulder: Int, elbow: Int, wrist: Int): Double? {
        val s = p.getPoseLandmark(shoulder) ?: return null
        val e = p.getPoseLandmark(elbow) ?: return null
        val w = p.getPoseLandmark(wrist) ?: return null
        if (s.inFrameLikelihood < LIKELIHOOD_FLOOR ||
            e.inFrameLikelihood < LIKELIHOOD_FLOOR ||
            w.inFrameLikelihood < LIKELIHOOD_FLOOR
        ) return null

        // Vectors out of the elbow.
        val bax = s.position.x - e.position.x
        val bay = s.position.y - e.position.y
        val bcx = w.position.x - e.position.x
        val bcy = w.position.y - e.position.y

        val dot = bax * bcx + bay * bcy
        val magBA = sqrt(bax * bax + bay * bay)
        val magBC = sqrt(bcx * bcx + bcy * bcy)
        if (magBA < 1e-6f || magBC < 1e-6f) return null

        val cos = (dot / (magBA * magBC)).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    fun close() = detector.close()
}
