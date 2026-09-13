package com.thesystem.app.ui.quest

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * On-device rep counter for QUEST PROOF.
 *
 * Same cosine-vector state machine as the battle push-up counter, generalized
 * over the moving joint:
 *  • PUSH  — shoulder·elbow·wrist (front camera, phone on the floor)
 *  • SQUAT — hip·knee·ankle (front camera, full body standing)
 *
 * A rep counts only on a full UP -> DOWN -> UP cycle, with EMA smoothing,
 * 2-consecutive-frame confirmation and a 400ms debounce. Frames never leave
 * the phone; the server only receives the verified count as proof.
 */
class QuestPoseCounter(
    val mode: QuestMode,
    private val onRep: (Int) -> Unit,
    private val onDepth: (Float, RepPhase) -> Unit = { _, _ -> },
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
) {

    enum class QuestMode { PUSH, SQUAT }
    enum class RepPhase { UP, DOWN, MIDDLE }

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
        const val EMA_ALPHA = 0.45
        const val CONFIRM_FRAMES = 2
        const val REP_COOLDOWN_MS = 400L
        const val LIKELIHOOD_FLOOR = 0.35f
    }

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

    private val downDeg: Double get() = if (mode == QuestMode.PUSH) 90.0 else 100.0
    private val upDeg: Double get() = 160.0

    private fun evaluate(pose: Pose) {
        onLandmarks(pose.allPoseLandmarks.map { (it.position.x / imgW) to (it.position.y / imgH) })

        val raw = if (mode == QuestMode.PUSH) {
            fuse(pose,
                Triple(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST),
                Triple(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST))
        } else {
            fuse(pose,
                Triple(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
                Triple(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE))
        } ?: return

        val smoothed = emaAngle?.let { it + EMA_ALPHA * (raw - it) } ?: raw
        emaAngle = smoothed
        val now = System.currentTimeMillis()

        val phase = when {
            smoothed < downDeg -> { downStreak++; upStreak = 0; RepPhase.DOWN }
            smoothed > upDeg -> { upStreak++; downStreak = 0; RepPhase.UP }
            else -> { downStreak = 0; upStreak = 0; RepPhase.MIDDLE }
        }
        // depth hint for the reticle: 1f = deepest, 0f = locked out
        val depth = ((upDeg - smoothed) / (upDeg - downDeg)).coerceIn(0.0, 1.0).toFloat()
        onDepth(depth, phase)

        if (!wasDown && downStreak >= CONFIRM_FRAMES) wasDown = true
        if (wasDown && upStreak >= CONFIRM_FRAMES && now - lastRepAt > REP_COOLDOWN_MS) {
            wasDown = false
            upStreak = 0
            lastRepAt = now
            reps += 1
            onRep(reps)
        }
    }

    private fun fuse(pose: Pose, left: Triple<Int, Int, Int>, right: Triple<Int, Int, Int>): Double? {
        val l = angleAt(pose, left.first, left.second, left.third)
        val r = angleAt(pose, right.first, right.second, right.third)
        return when {
            l != null && r != null -> (l + r) / 2.0
            l != null -> l
            r != null -> r
            else -> null
        }
    }

    private fun angleAt(p: Pose, a: Int, b: Int, c: Int): Double? {
        val pa = p.getPoseLandmark(a) ?: return null
        val pb = p.getPoseLandmark(b) ?: return null
        val pc = p.getPoseLandmark(c) ?: return null
        if (pa.inFrameLikelihood < LIKELIHOOD_FLOOR ||
            pb.inFrameLikelihood < LIKELIHOOD_FLOOR ||
            pc.inFrameLikelihood < LIKELIHOOD_FLOOR
        ) return null
        val bax = pa.position.x - pb.position.x
        val bay = pa.position.y - pb.position.y
        val bcx = pc.position.x - pb.position.x
        val bcy = pc.position.y - pb.position.y
        val dot = bax * bcx + bay * bcy
        val m1 = sqrt(bax * bax + bay * bay)
        val m2 = sqrt(bcx * bcx + bcy * bcy)
        if (m1 < 1e-6f || m2 < 1e-6f) return null
        val cos = (dot / (m1 * m2)).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    fun close() = detector.close()
}
