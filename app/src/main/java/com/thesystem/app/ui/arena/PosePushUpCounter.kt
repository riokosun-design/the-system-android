package com.thesystem.app.ui.arena

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.abs
import kotlin.math.atan2

/**
 * ML Kit push-up counter (Section 4-2): 100% on-device — zero upload, zero bandwidth,
 * works on low-end hardware. Scores are synced via broadcast; the image never leaves the phone.
 *
 * Logic: track shoulder→elbow→wrist angle with EMA smoothing + hysteresis.
 * DOWN when angle < 115° (2 consecutive frames), rep counted on return to
 * UP (> 145°, 2 frames). Requires the phone in front-of-body position (front camera, floor tilt).
 */
class PosePushUpCounter(
    private val onRep: (Int) -> Unit,
    // DESIGN 2.5: normalized landmarks (0f..1f in image space) for the hologram mesh overlay
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
) {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    var reps: Int = 0
        private set
    // v2 accuracy engine: ML Kit elbow angles jitter ±15° frame-to-frame. Raw
    // thresholds (105/155) made reps vanish — jitter dropped frames mid-state,
    // and 155° demanded full lockout many floor angles never show. Now:
    //   • EMA smoothing on the angle
    //   • hysteresis band (DOWN < 115°, UP > 145°), deadband between
    //   • 2-consecutive-frame confirmation per state transition
    //   • 500ms rep cooldown; inFrameLikelihood floor 0.35 (low-light tolerant)
    private var wasDown = false
    private var lastRepAt = 0L
    private var emaAngle: Double? = null
    private var downStreak = 0
    private var upStreak = 0
    private var imgW = 1f
    private var imgH = 1f

    private companion object {
        const val DOWN_DEG = 115.0
        const val UP_DEG = 145.0
        const val EMA_ALPHA = 0.45
        const val CONFIRM_FRAMES = 2
        const val REP_COOLDOWN_MS = 500L
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

    private fun evaluate(pose: Pose) {
        // hologram mesh feed — normalized so the overlay is image-size-agnostic
        val pts = pose.allPoseLandmarks.map { (it.position.x / imgW) to (it.position.y / imgH) }
        onLandmarks(pts)
        val raw = elbowAngleDeg(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST)
            ?: elbowAngleDeg(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
            ?: return // limb occluded this frame — keep EMA/state intact, resume next frame
        val smoothed = emaAngle?.let { it + EMA_ALPHA * (raw - it) } ?: raw
        emaAngle = smoothed
        val now = System.currentTimeMillis()
        when {
            smoothed < DOWN_DEG -> { downStreak++; upStreak = 0 }
            smoothed > UP_DEG -> { upStreak++; downStreak = 0 }
            else -> { downStreak = 0; upStreak = 0 } // deadband — neither state may latch
        }
        if (!wasDown && downStreak >= CONFIRM_FRAMES) wasDown = true
        if (wasDown && upStreak >= CONFIRM_FRAMES && now - lastRepAt > REP_COOLDOWN_MS) {
            wasDown = false
            upStreak = 0
            lastRepAt = now
            reps += 1
            onRep(reps)
        }
    }

    private fun elbowAngleDeg(p: Pose, a: Int, b: Int, c: Int): Double? {
        val la = p.getPoseLandmark(a) ?: return null
        val lb = p.getPoseLandmark(b) ?: return null
        val lc = p.getPoseLandmark(c) ?: return null
        if (la.inFrameLikelihood < LIKELIHOOD_FLOOR || lb.inFrameLikelihood < LIKELIHOOD_FLOOR || lc.inFrameLikelihood < LIKELIHOOD_FLOOR) return null
        val ang = atan2((lc.position.y - lb.position.y).toDouble(), (lc.position.x - lb.position.x).toDouble()) -
            atan2((la.position.y - lb.position.y).toDouble(), (la.position.x - lb.position.x).toDouble())
        var deg = Math.toDegrees(abs(ang))
        if (deg > 180) deg = 360 - deg
        return deg
    }

    fun close() = detector.close()
}
