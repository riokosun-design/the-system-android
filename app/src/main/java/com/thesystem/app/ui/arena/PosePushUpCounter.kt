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
 * Logic: track shoulder→elbow→wrist angle. DOWN when < 105°, rep counted on return
 * to UP (> 155°). Requires the phone in front-of-body position (front camera, floor tilt).
 */
class PosePushUpCounter(private val onRep: (Int) -> Unit) {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    var reps: Int = 0
        private set
    private var wasDown = false
    private var lastRepAt = 0L

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        val media = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { pose -> evaluate(pose) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun evaluate(pose: Pose) {
        val elbowAngle = elbowAngleDeg(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST)
            ?: elbowAngleDeg(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
            ?: return
        val now = System.currentTimeMillis()
        when {
            elbowAngle < 105 -> wasDown = true
            elbowAngle > 155 && wasDown && now - lastRepAt > 600 -> {
                wasDown = false
                lastRepAt = now
                reps += 1
                onRep(reps)
            }
        }
    }

    private fun elbowAngleDeg(p: Pose, a: Int, b: Int, c: Int): Double? {
        val la = p.getPoseLandmark(a) ?: return null
        val lb = p.getPoseLandmark(b) ?: return null
        val lc = p.getPoseLandmark(c) ?: return null
        if (la.inFrameLikelihood < 0.5f || lb.inFrameLikelihood < 0.5f || lc.inFrameLikelihood < 0.5f) return null
        val ang = atan2((lc.position.y - lb.position.y).toDouble(), (lc.position.x - lb.position.x).toDouble()) -
            atan2((la.position.y - lb.position.y).toDouble(), (la.position.x - lb.position.x).toDouble())
        var deg = Math.toDegrees(abs(ang))
        if (deg > 180) deg = 360 - deg
        return deg
    }

    fun close() = detector.close()
}
