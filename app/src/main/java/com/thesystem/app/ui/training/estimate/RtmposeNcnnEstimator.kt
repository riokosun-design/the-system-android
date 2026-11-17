package com.thesystem.app.ui.training.estimate

import android.content.Context
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * RTMPOSE-NCNN ESTIMATOR — the offline pose backbone (vC58).
 *
 * RTMPose-M (primary) / RTMPose-S (low-end fallback), Tencent NCNN runtime,
 * SimCC decode. 100% on-device; not a byte of ML Kit. The native side
 * ([RtmposeNative]) runs the exact contract the offline gates proved:
 *   - pipeline_validate.py  — preprocess + decode vs official ONNX: GREEN
 *   - ncnn_parity_gate.py   — shipped fp16 payload vs ONNX fp32:
 *                             maxPosDelta 0.00px, maxScoreDelta 0.002: GREEN
 *
 * Frame contract: full-frame person box (padding 1.25, aspect-fixed to
 * 192:256) — the official top_down_affine contract. Coordinates answer in
 * upright-px of the rotation-corrected frame, same as every estimator.
 */
internal object RtmposeNative {
    init { System.loadLibrary("rtmpose_jni") }

    /** Returns net handle (0 = load failed). Assets: e.g. "ml/rtmpose_m.ncnn.param". */
    external fun init(assets: android.content.res.AssetManager, param: String, bin: String, threads: Int): Long

    /** bgrBuf: direct ByteBuffer, MW*MH*3 bytes, BGR byte order. Returns 17×3 (x,y model-space, score) or null. */
    external fun detect(handle: Long, bgrBuf: ByteBuffer): FloatArray?

    external fun release(handle: Long)
}

class RtmposeNcnnEstimator(context: Context, private val variant: Variant) : PoseEstimator {

    enum class Variant(val paramAsset: String, val binAsset: String, val tag: String, val threads: Int) {
        M("ml/rtmpose_m.ncnn.param", "ml/rtmpose_m.ncnn.bin", "rtmpose_m", 4),
        S("ml/rtmpose_s.ncnn.param", "ml/rtmpose_s.ncnn.bin", "rtmpose_s", 2),
    }

    override val name = variant.tag

    private var handle: Long = runCatching {
        RtmposeNative.init(context.assets, variant.paramAsset, variant.binAsset, variant.threads)
    }.getOrDefault(0L)

    private val bgr: ByteBuffer =
        ByteBuffer.allocateDirect(MW * MH * 3).order(ByteOrder.nativeOrder())

    val alive: Boolean get() = handle != 0L

    override fun estimate(imageProxy: ImageProxy, rot: Int, upW: Float, upH: Float): LandmarkFrame? {
        val h = handle
        if (h == 0L) return null
        val t0 = System.nanoTime()

        // official full-frame box: center/scale with padding 1.25, aspect-fixed to MW:MH
        var scaleW = upW * 1.25f
        var scaleH = upH * 1.25f
        val ar = MW.toFloat() / MH.toFloat()
        if (scaleW > scaleH * ar) scaleH = scaleW / ar else scaleW = scaleH * ar
        val cx = upW / 2f
        val cy = upH / 2f

        YuvSampler.sampleAffine(imageProxy, rot, upW, upH, cx, cy, scaleW, scaleH, MW, MH, bgr)

        val out = RtmposeNative.detect(h, bgr) ?: return null

        // unmap model-space → upright px:  u = m/M*scale + center − scale/2
        val kx = scaleW / MW
        val ky = scaleH / MH
        val ox = cx - scaleW / 2f
        val oy = cy - scaleH / 2f

        // COCO-17 → canonical 33 slots (same contract as the retired MoveNet feed)
        val px = FloatArray(66)
        val sc = FloatArray(33)
        fun put(slot: Int, coco: Int, damp: Float = 1f) {
            px[slot * 2] = (out[coco * 3] * kx + ox).coerceIn(0f, upW)
            px[slot * 2 + 1] = (out[coco * 3 + 1] * ky + oy).coerceIn(0f, upH)
            sc[slot] = out[coco * 3 + 2].coerceIn(0f, 1f) * damp
        }
        val map = intArrayOf(
            0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28,
        )
        for (coco in 0 until 17) put(map[coco], coco)
        // face/hand/foot approximations — flagged weaker, never faked as measured
        put(1, 1, 0.7f); put(4, 2, 0.7f)
        put(9, 0, 0.5f); put(10, 0, 0.5f)
        put(19, 9, 0.8f); put(20, 10, 0.8f)
        put(29, 15, 0.8f); put(30, 16, 0.8f)
        put(31, 15, 0.8f); put(32, 16, 0.8f)

        val frame = LandmarkFrame(px, sc, name, upW, upH, (System.nanoTime() - t0) / 1_000_000)
        return if (frame.strong() >= 5) frame else null
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { RtmposeNative.release(h) }
    }

    companion object {
        const val MW = 192
        const val MH = 256
    }
}
