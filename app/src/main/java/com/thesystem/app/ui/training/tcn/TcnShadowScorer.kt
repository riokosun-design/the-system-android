package com.thesystem.app.ui.training.tcn

import android.content.Context
import java.nio.ByteOrder

/**
 * TCN SHADOW SCORER — CV-BATTLE-ARCHITECTURE §4/§20 Phase 3, deployed exactly
 * as the doc demands: SHADOW FIRST. The causal TCN scores 48×12 feature windows
 * every stride (8 frames); the deterministic rules ALWAYS decide. Agreement
 * rides to the corpus in the harvest meta — promotion requires the real-data
 * retrain + ≥97% shadow agreement (§20).
 *
 * Runtime design: the net is ~90 k params (4 causal convs + GAP + dense), so a
 * hand-rolled f32 forward pass in pure Kotlin costs <1 ms and ZERO native deps
 * — no TFLite runtime roulette for a shadow model. weights.bin (raw f32,
 * little-endian) is exported by ml/tcn_train.py. v0 weights are
 * BOOTSTRAP-SYNTHETIC: useful prior, not yet earned — the corpus fixes that.
 */
class TcnShadowScorer private constructor(private val w: Params?) {

    val available: Boolean get() = w != null

    // ── weights ──────────────────────────────────────────────────────────────
    private class Conv(val w: FloatArray, val b: FloatArray, val inC: Int, val outC: Int, val dil: Int)

    private class Params(
        val c1: Conv, val c2: Conv, val c3: Conv, val c4: Conv,
        val denseW: FloatArray, val denseB: FloatArray,
    )

    private val ring = Array(48) { FloatArray(12) }
    private var head = 0
    private var count = 0
    private var sinceScore = 0

    @Volatile
    var latest: FloatArray? = null
        private set

    val topClass: Pair<String, Float>?
        get() = latest?.let { probs ->
            var bi = 0
            for (i in 1 until 7) if (probs[i] > probs[bi]) bi = i
            CLASSES[bi] to probs[bi]
        }

    fun push(v: FloatArray) {
        v.copyInto(ring[head], endIndex = 12)
        head = (head + 1) % 48
        if (count < 48) count++
        sinceScore++
        if (w != null && count >= 48 && sinceScore >= 8) {
            sinceScore = 0
            val input = Array(48) { t -> ring[(head + t) % 48] }
            latest = forward(w, input)
        }
    }

    /** Time-ordered 48×12 snapshot for the harvest corpus (576 floats). */
    fun snapshot(): FloatArray? {
        if (count < 48) return null
        val out = FloatArray(48 * 12)
        var i = 0
        for (t in 0 until 48) {
            val src = ring[(head + t) % 48]
            for (c in 0 until 12) out[i++] = src[c]
        }
        return out
    }

    fun close() = Unit

    // ── pure-Kotlin forward — 4 causal convs (k=3) + GAP + dense, GELU acts ──
    private fun gelu(x: Float): Float {
        // tanh-approximation GELU (training uses approximate='tanh' to match)
        val t = 0.7978845608f * (x + 0.044715f * x * x * x)
        val sig = 1f / (1f + kotlin.math.exp(-2f * kotlin.math.abs(t)))  // σ(2|t|)
        val tanhAbs = 2f * sig - 1f
        val tanh = if (t >= 0) tanhAbs else -tanhAbs
        return 0.5f * x * (1f + tanh)
    }

    private fun convFwd(x: Array<FloatArray>, conv: Conv, inLen: Int): Array<FloatArray> {
        // causal: output[t] sums input[t−2d .. t] with zero-pad on the left
        val out = Array(inLen) { FloatArray(conv.outC) }
        for (t in 0 until inLen) {
            val o = out[t]
            for (oc in 0 until conv.outC) o[oc] = conv.b[oc]
            for (k in 0 until 3) {
                val ti = t - (2 - k) * conv.dil
                if (ti < 0) continue
                val xi = x[ti]
                var base = k * conv.inC * conv.outC
                for (ic in 0 until conv.inC) {
                    val xv = xi[ic]
                    if (xv == 0f && ic >= 6) { base += conv.outC; continue }
                    for (oc in 0 until conv.outC) {
                        o[oc] += xv * conv.w[base + oc]
                    }
                    base += conv.outC
                }
            }
            for (oc in 0 until conv.outC) o[oc] = gelu(o[oc])
        }
        return out
    }

    private fun forward(p: Params, x48: Array<FloatArray>): FloatArray {
        var h = convFwd(x48, p.c1, 48)     // 12 → 64,  d=1
        h = convFwd(h, p.c2, 48)           // 64 → 64,  d=2
        h = convFwd(h, p.c3, 48)           // 64 → 128, d=4
        h = convFwd(h, p.c4, 48)           // 128 → 128, d=8
        val gap = FloatArray(128)
        for (t in 0 until 48) for (c in 0 until 128) gap[c] += h[t][c]
        for (c in 0 until 128) gap[c] /= 48f
        val logits = FloatArray(7)
        for (o in 0 until 7) {
            var s = p.denseB[o]
            val base = o * 128
            for (c in 0 until 128) s += gap[c] * p.denseW[base + c]
            logits[o] = s
        }
        var mx = logits[0]; for (v in logits) if (v > mx) mx = v
        var sum = 0f
        val probs = FloatArray(7)
        for (o in 0 until 7) { val e = kotlin.math.exp(logits[o] - mx); probs[o] = e; sum += e }
        for (o in 0 until 7) probs[o] /= sum
        return probs
    }

    companion object {
        val CLASSES = listOf(
            "VALID_PUSHUP", "INVALID_DEPTH", "INVALID_FORM", "PARTIAL_REP",
            "CHEAT_MOVEMENT", "CAMERA_MOVEMENT", "UNKNOWN",
        )

        private fun readConv(buf: java.nio.ByteBuffer, inC: Int, outC: Int, dil: Int): Conv {
            val w = FloatArray(inC * outC * 3)
            for (i in w.indices) w[i] = buf.float
            val b = FloatArray(outC)
            for (i in b.indices) b[i] = buf.float
            return Conv(w, b, inC, outC, dil)
        }

        fun create(context: Context): TcnShadowScorer = TcnShadowScorer(
            runCatching {
                context.assets.open("ml/pushup_tcn_synth_v0.weights.bin").use { ins ->
                    val bytes = ins.readBytes()
                    if (bytes.size < 4 || bytes[0] != 'T'.code.toByte() || bytes[1] != 'C'.code.toByte() ||
                        bytes[2] != 'N'.code.toByte() || bytes[3] != '1'.code.toByte()
                    ) return@use null
                    val buf = java.nio.ByteBuffer.wrap(bytes, 4, bytes.size - 4).order(ByteOrder.LITTLE_ENDIAN)
                    val c1 = readConv(buf, 12, 64, 1)
                    val c2 = readConv(buf, 64, 64, 2)
                    val c3 = readConv(buf, 64, 128, 4)
                    val c4 = readConv(buf, 128, 128, 8)
                    val denseW = FloatArray(128 * 7)
                    for (i in denseW.indices) denseW[i] = buf.float
                    val denseB = FloatArray(7)
                    for (i in denseB.indices) denseB[i] = buf.float
                    Params(c1, c2, c3, c4, denseW, denseB)
                }
            }.getOrNull(),
        )
    }
}
