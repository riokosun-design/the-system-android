package com.thesystem.app.ui.training.estimate

import android.app.ActivityManager
import android.content.Context
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * POSE ESTIMATION LAYER — RTMPose era (vC58).
 *
 * ONE canonical landmark space for every estimator: the 33-slot layout
 * (indices 0..32) so the pose mesh, RepPulseCore and the HUD never care which
 * model produced the points. RTMPose-M (NCNN, fp16, bundled asset) is the
 * PRIMARY witness; RTMPose-S is the low-RAM/failure fallback. ML Kit and
 * MoveNet are RETIRED — deleted, not deprecated (law: ML Kit strictly
 * forbidden; no duplicate systems).
 *
 * All estimators answer in UPRIGHT pixel coordinates of the rotation-corrected
 * frame — the same space RepPulseCore computes in.
 */
data class LandmarkFrame(
    /** 33 slots × (x, y) in upright px. */
    val px: FloatArray,
    /** 33 slots × per-point confidence 0..1. */
    val score: FloatArray,
    val source: String,          // "rtmpose_m" | "rtmpose_s"
    val imgW: Float,
    val imgH: Float,
    val inferMs: Long,
) {
    fun x(j: Int) = px[j * 2]
    fun y(j: Int) = px[j * 2 + 1]
    fun ok(j: Int, floor: Float) = score[j] >= floor
    fun strong(): Int = (CORE_JOINTS + FACE_JOINTS).count { score[it] >= 0.3f }

    companion object {
        // canonical slots (ML Kit PoseLandmark numbering)
        const val NOSE = 0
        const val L_EYE = 2
        const val R_EYE = 5
        const val L_EAR = 7
        const val R_EAR = 8
        const val L_SHOULDER = 11
        const val R_SHOULDER = 12
        const val L_ELBOW = 13
        const val R_ELBOW = 14
        const val L_WRIST = 15
        const val R_WRIST = 16
        const val L_INDEX = 19
        const val R_INDEX = 20
        const val L_HIP = 23
        const val R_HIP = 24
        const val L_KNEE = 25
        const val R_KNEE = 26
        const val L_ANKLE = 27
        const val R_ANKLE = 28
        val CORE_JOINTS = intArrayOf(L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW, L_WRIST, R_WRIST, L_HIP, R_HIP, L_KNEE, R_KNEE, L_ANKLE, R_ANKLE)
        val FACE_JOINTS = intArrayOf(NOSE)
    }
}

interface PoseEstimator {
    val name: String
    /** Synchronous — the analyzer thread blocks here. NEVER closes imageProxy. */
    fun estimate(imageProxy: ImageProxy, rot: Int, upW: Float, upH: Float): LandmarkFrame?
    fun close()
}

// ═══ YUV → RGB region sampler (nearest, rotation-aware) ═════════════════════
internal object YuvSampler {

    /**
     * Fills [outW]×[outH] BGR bytes by sampling the upright frame through the
     * model affine: model texel (tx,ty) ← upright (u,v) where
     *   u = (tx+0.5)·scaleW/outW + cx − scaleW/2
     *   v = (ty+0.5)·scaleH/outH + cy − scaleH/2
     * BGR byte order = the official RTMPose OpenCV contract (channel 0 = B).
     */
    fun sampleAffine(
        proxy: ImageProxy, rot: Int,
        upW: Float, upH: Float,
        cx: Float, cy: Float, scaleW: Float, scaleH: Float,
        outW: Int, outH: Int,
        out: ByteBuffer,
    ) {
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val bufW = proxy.width
        val bufH = proxy.height

        val ax = scaleW / outW
        val ay = scaleH / outH
        val ox = cx - scaleW / 2f
        val oy = cy - scaleH / 2f

        out.rewind()
        for (ty in 0 until outH) {
            val uy = (ty + 0.5f) * ay + oy
            for (tx in 0 until outW) {
                val ux = (tx + 0.5f) * ax + ox
                // upright → buffer transform (rotation-corrected round trip)
                val (bx, by) = when (rot) {
                    90 -> uy to (upW - 1f - ux)
                    180 -> (upW - 1f - ux) to (upH - 1f - uy)
                    270 -> (upH - 1f - uy) to ux
                    else -> ux to uy
                }
                val xi = bx.toInt().coerceIn(0, bufW - 1)
                val yi = by.toInt().coerceIn(0, bufH - 1)
                val yv = yBuf.get(yi * yPlane.rowStride + xi * yPlane.pixelStride).toInt() and 0xFF
                val uvi = (yi / 2) * uPlane.rowStride + (xi / 2) * uPlane.pixelStride
                val uv = uBuf.get(uvi).toInt() and 0xFF
                val vv = vBuf.get(uvi).toInt() and 0xFF
                // BT.601 full-range → RGB
                val c = yv - 16
                val d = uv - 128
                val e = vv - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                out.put(b.toByte()); out.put(g.toByte()); out.put(r.toByte())
            }
        }
        out.rewind()
    }
}

// ═══ ROUTER — RTMPose-M primary, RTMPose-S fallback, RAM-aware, fail-flip ═══
class PoseEstimatorRouter(private val context: Context) : PoseEstimator {

    override val name = "router"

    /** Low-RAM hunters (2–4GB devices) start on RTMPose-S — honest speed over size. */
    private val primaryVariant: RtmposeNcnnEstimator.Variant =
        if (isLowRam(context)) RtmposeNcnnEstimator.Variant.S else RtmposeNcnnEstimator.Variant.M

    private var active: RtmposeNcnnEstimator? = construct(primaryVariant)
    private var activeVariant: RtmposeNcnnEstimator.Variant = primaryVariant
    private var failStreak = 0

    // parity probe (harvest diagnostics): the rival model is built lazily, only
    // while probing is enabled — never two 27MB networks resident on a 2GB phone.
    private var probePeer: RtmposeNcnnEstimator? = null
    private var parityFrames = 0

    var parityEnabled = false
        set(value) {
            field = value
            if (!value) {
                probePeer?.close()
                probePeer = null
            }
        }
    var onParity: ((Float, String) -> Unit)? = null

    val activeSource: String get() = active?.name ?: "none"

    private fun construct(v: RtmposeNcnnEstimator.Variant): RtmposeNcnnEstimator? =
        runCatching { RtmposeNcnnEstimator(context, v) }.getOrNull()?.takeIf { it.alive }

    /** Sustained failure → retire the current model, promote the rival. */
    private fun flip() {
        val other = if (activeVariant == RtmposeNcnnEstimator.Variant.M)
            RtmposeNcnnEstimator.Variant.S else RtmposeNcnnEstimator.Variant.M
        active?.close()
        active = construct(other)
        activeVariant = other
        failStreak = 0
    }

    override fun estimate(imageProxy: ImageProxy, rot: Int, upW: Float, upH: Float): LandmarkFrame? {
        val cur = active
        val frame = if (cur != null) runCatching { cur.estimate(imageProxy, rot, upW, upH) }.getOrNull() else null
        if (frame == null) {
            failStreak++
            if (failStreak >= 8) flip()   // sustained failure → the other model takes the watch
        } else {
            failStreak = 0
        }

        // parity sample: every 30th good frame, judge M-vs-S agreement
        if (parityEnabled && frame != null) {
            parityFrames++
            if (parityFrames >= 30) {
                parityFrames = 0
                val peerVariant = if (activeVariant == RtmposeNcnnEstimator.Variant.M)
                    RtmposeNcnnEstimator.Variant.S else RtmposeNcnnEstimator.Variant.M
                if (probePeer == null) probePeer = construct(peerVariant)
                val other = probePeer?.let { runCatching { it.estimate(imageProxy, rot, upW, upH) }.getOrNull() }
                if (other != null) {
                    val diag = kotlin.math.sqrt(upW * upW + upH * upH)
                    var d = 0f
                    var n = 0
                    for (j in LandmarkFrame.CORE_JOINTS) {
                        d += (abs(frame.x(j) - other.x(j)) + abs(frame.y(j) - other.y(j))) / (2f * diag)
                        n++
                    }
                    if (n > 0) onParity?.invoke(d / n, frame.source)
                }
            }
        }
        return frame
    }

    override fun close() {
        active?.close()
        active = null
        probePeer?.close()
        probePeer = null
    }

    companion object {
        fun isLowRam(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return info.totalMem <= 4L * 1024 * 1024 * 1024
        }
    }
}
