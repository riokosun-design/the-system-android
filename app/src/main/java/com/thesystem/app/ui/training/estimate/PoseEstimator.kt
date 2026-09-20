package com.thesystem.app.ui.training.estimate

import android.content.Context
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * POSE ESTIMATION LAYER — CV-BATTLE-ARCHITECTURE Phase 1 (§14, §17).
 *
 * ONE canonical landmark space for every estimator: the ML Kit 33-slot layout
 * (indices 0..32) so the pose mesh, the calibration gate and engine v4 never
 * care which model produced the points. MoveNet Thunder (int8, bundled asset)
 * is the PRIMARY witness — we own normalization, ROI and the failure surface.
 * ML Kit Pose STREAM stays as the device-compat fallback (vendor fragmentation
 * is real); a live parity probe measures agreement between the two whenever
 * the harvest corpus is recording (Phase 2 meta).
 *
 * All estimators answer in UPRIGHT pixel coordinates of the rotation-corrected
 * frame — the same space [com.thesystem.app.ui.training.PushupEngineV4] and
 * [com.thesystem.app.ui.training.CalibrationGate] already compute in.
 */
data class LandmarkFrame(
    /** 33 slots × (x, y) in upright px. */
    val px: FloatArray,
    /** 33 slots × per-point confidence 0..1. */
    val score: FloatArray,
    val source: String,          // "movenet" | "mlkit"
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
    private val argb = IntArray(256 * 256)

    /** Fills [255-sized-square] RGB bytes for the tensor from an upright ROI. */
    fun sample(
        proxy: ImageProxy, rot: Int,
        roiX: Float, roiY: Float, roiSide: Float,   // upright-px space
        upW: Float, upH: Float,
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

        out.rewind()
        for (ty in 0 until 256) {
            val uy = (roiY + (ty + 0.5f) * roiSide / 256f)
            for (tx in 0 until 256) {
                val ux = (roiX + (tx + 0.5f) * roiSide / 256f)
                // upright → buffer transform (round-trip consistent with output mapping)
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
                out.put(r.toByte()); out.put(g.toByte()); out.put(b.toByte())
            }
        }
        out.rewind()
    }
}

// ═══ MOVENET THUNDER (int8) — the primary witness ════════════════════════════
class MoveNetEstimator(context: Context) : PoseEstimator {

    override val name = "movenet"

    private val interpreter: Interpreter
    private val input: ByteBuffer
    private val inputIsQuant: Boolean
    private var roiX = 0f
    private var roiY = 0f
    private var roiSide = 0f   // 0 → no ROI yet (full frame)
    private var lostStreak = 0

    init {
        val afd = context.assets.openFd("ml/movenet_thunder_int8.tflite")
        val mapped = FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength,
        )
        interpreter = Interpreter(mapped, Interpreter.Options().setNumThreads(4))
        input = ByteBuffer.allocateDirect(256 * 256 * 3).order(ByteOrder.nativeOrder())
        inputIsQuant = interpreter.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.UINT8
    }

    override fun estimate(imageProxy: ImageProxy, rot: Int, upW: Float, upH: Float): LandmarkFrame? {
        val t0 = System.nanoTime()
        // ROI locker — bbox EMA-locked around the last good pose (§3)
        val side = if (roiSide > 0f) roiSide else max(upW, upH)
        val cx = if (roiSide > 0f) roiX else (upW - side) / 2f
        val cy = if (roiSide > 0f) roiY else (upH - side) / 2f

        YuvSampler.sample(imageProxy, rot, cx, cy, side, upW, upH, input)

        val out: Any
        val quant = interpreter.getOutputTensor(0).let { it.quantizationParams() }
        if (interpreter.getOutputTensor(0).dataType() == org.tensorflow.lite.DataType.UINT8) {
            val raw = Array(1) { Array(1) { Array(17) { ByteArray(3) } } }
            interpreter.run(input, raw)
            out = FloatArray(51).also { f ->
                var i = 0
                for (k in 0 until 17) for (c in 0 until 3) {
                    f[i++] = ((raw[0][0][k][c].toInt() and 0xFF) - quant.zeroPoint) * quant.scale
                }
            }
        } else {
            val raw = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }
            interpreter.run(input, raw)
            out = FloatArray(51).also { f ->
                var i = 0
                for (k in 0 until 17) for (c in 0 until 3) f[i++] = raw[0][0][k][c]
            }
        }
        val flat = out as FloatArray

        // COCO-17 → canonical 33 slots (face/hand/foot detail approximated)
        val px = FloatArray(66)
        val sc = FloatArray(33)
        fun put(slot: Int, ty: Float, tx: Float, s: Float, damp: Float = 1f) {
            px[slot * 2] = (cx + tx * side).coerceIn(0f, upW)
            px[slot * 2 + 1] = (cy + ty * side).coerceIn(0f, upH)
            sc[slot] = s * damp
        }
        val map = intArrayOf(
            0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28,
        )
        for (coco in 0 until 17) {
            put(map[coco], flat[coco * 3], flat[coco * 3 + 1], flat[coco * 3 + 2])
        }
        // approximations: eyes inner, mouth, hand detail, feet detail
        put(1, flat[1 * 3], flat[1 * 3 + 1], flat[1 * 3 + 2], 0.7f)
        put(4, flat[2 * 3], flat[2 * 3 + 1], flat[2 * 3 + 2], 0.7f)
        put(9, flat[0], flat[1], flat[2], 0.5f); put(10, flat[0], flat[1], flat[2], 0.5f)
        put(19, flat[9 * 3], flat[9 * 3 + 1], flat[9 * 3 + 2], 0.8f)
        put(20, flat[10 * 3], flat[10 * 3 + 1], flat[10 * 3 + 2], 0.8f)
        put(29, flat[15 * 3], flat[15 * 3 + 1], flat[15 * 3 + 2], 0.8f)
        put(30, flat[16 * 3], flat[16 * 3 + 1], flat[16 * 3 + 2], 0.8f)
        put(31, flat[15 * 3], flat[15 * 3 + 1], flat[15 * 3 + 2], 0.8f)
        put(32, flat[16 * 3], flat[16 * 3 + 1], flat[16 * 3 + 2], 0.8f)

        val frame = LandmarkFrame(px, sc, name, upW, upH, (System.nanoTime() - t0) / 1_000_000)
        val strong = frame.strong()
        return if (strong >= 5) {
            lostStreak = 0
            // refit ROI: bbox of core joints + 30%, clamped, square, EMA-smoothed
            var minX = upW; var maxX = 0f; var minY = upH; var maxY = 0f
            for (j in LandmarkFrame.CORE_JOINTS) if (sc[j] >= 0.3f) {
                minX = min(minX, frame.x(j)); maxX = max(maxX, frame.x(j))
                minY = min(minY, frame.y(j)); maxY = max(maxY, frame.y(j))
            }
            if (maxX > minX) {
                val s = (max(maxX - minX, maxY - minY) * 1.35f).coerceIn(96f, max(upW, upH))
                val nx = (minX + maxX - s) / 2f
                val ny = (minY + maxY - s) / 2f
                if (roiSide > 0f) {   // EMA-lock an existing ROI; snap a fresh one
                    roiSide += 0.25f * (s - roiSide)
                    roiX = (roiX + 0.25f * (nx - roiX)).coerceIn(-0.2f * upW, 0.9f * upW)
                    roiY = (roiY + 0.25f * (ny - roiY)).coerceIn(-0.2f * upH, 0.9f * upH)
                } else {
                    roiSide = s
                    roiX = nx.coerceIn(-0.2f * upW, 0.9f * upW)
                    roiY = ny.coerceIn(-0.2f * upH, 0.9f * upH)
                }
            }
            frame
        } else {
            lostStreak++
            if (lostStreak > 8) roiSide = 0f   // unlock ROI — search the full frame again
            null
        }
    }

    override fun close() = interpreter.close()
}

// ═══ ML KIT (STREAM) — the device-compat fallback ════════════════════════════
class MlKitEstimator : PoseEstimator {

    override val name = "mlkit"

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    @androidx.camera.core.ExperimentalGetImage
    override fun estimate(imageProxy: ImageProxy, rot: Int, upW: Float, upH: Float): LandmarkFrame? {
        val media = imageProxy.image ?: return null
        val t0 = System.nanoTime()
        val pose = try {
            Tasks.await(detector.process(InputImage.fromMediaImage(media, rot)))
        } catch (_: Exception) {
            return null
        }
        val marks = pose.allPoseLandmarks
        if (marks.isEmpty()) return null
        val px = FloatArray(66)
        val sc = FloatArray(33)
        for (m in marks) {
            val i = m.landmarkType
            px[i * 2] = m.position.x; px[i * 2 + 1] = m.position.y
            sc[i] = m.inFrameLikelihood
        }
        return LandmarkFrame(px, sc, name, upW, upH, (System.nanoTime() - t0) / 1_000_000)
    }

    override fun close() = detector.close()
}

// ═══ ROUTER — MoveNet primary, ML Kit failover, live parity probe ═══════════
class PoseEstimatorRouter(context: Context) : PoseEstimator {

    override val name = "router"

    private val moveNet: MoveNetEstimator? = runCatching { MoveNetEstimator(context) }.getOrNull()
    private val mlKit = MlKitEstimator()
    private var netFailStreak = 0
    private var preferMlKit = false
    private var parityFrames = 0

    /** Parity probe (Phase 1 exit metric): fires with mean joint distance / frame diag. */
    var parityEnabled = false
    var onParity: ((Float, String) -> Unit)? = null

    val activeSource: String get() = if (!preferMlKit && moveNet != null) "movenet" else "mlkit"
    val moveNetAlive: Boolean get() = moveNet != null

    @androidx.camera.core.ExperimentalGetImage
    override fun estimate(imageProxy: ImageProxy, rot: Int, upW: Float, upH: Float): LandmarkFrame? {
        val primaryFirst = !preferMlKit && moveNet != null
        var frame = if (primaryFirst) runCatching { moveNet!!.estimate(imageProxy, rot, upW, upH) }.getOrNull() else null
        if (frame == null) {
            frame = runCatching { mlKit.estimate(imageProxy, rot, upW, upH) }.getOrNull()
            if (primaryFirst) {
                netFailStreak++
                if (netFailStreak >= 8) preferMlKit = true   // sustained failure → flip primacy
            }
        } else if (primaryFirst) {
            netFailStreak = 0
        }

        // parity sample: every 30th frame, judge agreement (crop-frame diagnosis)
        if (parityEnabled && frame != null) {
            parityFrames++
            if (parityFrames >= 30) {
                parityFrames = 0
                val other = (if (frame.source == "movenet") runCatching { mlKit.estimate(imageProxy, rot, upW, upH) }.getOrNull()
                else runCatching { moveNet?.estimate(imageProxy, rot, upW, upH) }.getOrNull())
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
        moveNet?.close()
        mlKit.close()
    }
}
