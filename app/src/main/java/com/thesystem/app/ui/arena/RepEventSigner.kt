package com.thesystem.app.ui.arena

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * REP EVENT SIGNER — CV-BATTLE-ARCHITECTURE §8.
 *
 * No raw video ever leaves the device; the rep's TRAJECTORY is the evidence.
 * Every counted rep becomes a signed event, hash-chained to the previous one,
 * keyed by a device-session secret derived from the server-minted battle nonce:
 *
 *   key    = SHA256(nonce | "|" | user_id)          (shared secret client⇄server)
 *   canon  = battle|user|seq|t|theta|line|drop|tempo|conf|cheat|engine|prev_hash
 *   hash   = SHA256_hex(canon)                      (tamper-evident chain)
 *   sig    = HMAC_SHA256_hex(key, hash)
 *
 * The server re-derives everything in submit_rep_events: forged hashes, broken
 * chains, <500 ms physics gaps and short tempos land as flags — the referee
 * never trusts, it verifies. Numbers are formatted ONCE here and sent as JSON
 * strings, so the canonical bytes match byte-for-byte on both sides.
 */
class RepEventSigner(
    private val battleId: String,
    private val userId: String,
    nonce: String,
) {

    private val key: ByteArray =
        MessageDigest.getInstance("SHA-256").digest("$nonce|$userId".toByteArray(Charsets.UTF_8))
    private var prevHash = "GENESIS"
    private var seq = 0
    private val pending = ArrayDeque<JsonObject>()

    val signed: Int get() = seq
    val buffered: Int get() = pending.size

    fun onRep(
        tDeviceMs: Long,
        thetaMin: Double,
        lineDev: Float,
        shoulderDrop: Float,    // −1 = not measured by this engine lineage (honest)
        tempoMs: Long,
        confidence: Float,
        cheatScore: Float,
        engineVersion: String,
    ) {
        seq += 1
        val th = fmt(thetaMin)
        val ln = fmt(lineDev.toDouble())
        val dr = fmt(shoulderDrop.toDouble())
        val cf = fmt(confidence.toDouble())
        val ch = fmt(cheatScore.toDouble())
        val canon = "$battleId|$userId|$seq|$tDeviceMs|$th|$ln|$dr|$tempoMs|$cf|$ch|$engineVersion|$prevHash"
        val hash = sha256Hex(canon)
        val sig = hmacHex(hash)

        pending.addLast(
            buildJsonObject {
                put("seq_no", seq)
                put("t_device_ms", tDeviceMs)
                put("quality", buildJsonObject {
                    put("theta_min", th)
                    put("line_dev", ln)
                    put("shoulder_drop", dr)
                    put("tempo_ms", tempoMs)
                })
                put("confidence", cf)
                put("cheat_score", ch)
                put("engine_version", engineVersion)
                put("prev_hash", prevHash)
                put("hash", hash)
                put("sig", sig)
            }
        )
        prevHash = hash
    }

    /** Drain up to [max] events as a JSON array for submit_rep_events. */
    fun drain(max: Int = 8): JsonArray? {
        if (pending.isEmpty()) return null
        return buildJsonArray {
            var n = 0
            while (n < max && pending.isNotEmpty()) {
                add(pending.removeFirst())
                n++
            }
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hmacHex(msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
