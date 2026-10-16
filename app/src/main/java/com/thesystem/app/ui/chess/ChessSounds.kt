package com.thesystem.app.ui.chess

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.exp
import kotlin.math.sin

/**
 * §3.3 — SYSTEM COMBAT AUDIO: synthesized chess SFX, zero bundled assets.
 * Every cue is a short PCM burst written straight to an AudioTrack off the
 * main thread — no asset pipeline, no network, safe on 2GB devices.
 * Vibration goes through the VibratorManager (API 31+) with legacy fallback.
 */
object ChessSfx {

    enum class Sfx { MOVE, CAPTURE, CHECK, CHECKMATE, VICTORY, DEFEAT, PUZZLE_FAIL }

    private const val SR = 22050

    /** One enveloped sine (or band noise) segment: freq sweep a→b over durMs. */
    private fun seg(freqA: Double, freqB: Double, durMs: Int, vol: Float, noise: Boolean = false): ShortArray {
        val n = SR * durMs / 1000
        val out = ShortArray(n)
        var phase = 0.0
        var rng = 0x2545F4914F6CDD1DL
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val v = if (noise) {
                rng = rng xor (rng shl 13); rng = rng xor (rng ushr 7); rng = rng xor (rng shl 17)
                (rng.toDouble() / Long.MAX_VALUE) * exp(-5.0 * t)
            } else {
                val f = freqA + (freqB - freqA) * t
                phase += 2 * Math.PI * f / SR
                sin(phase) * exp(-3.2 * t) * (1.0 - exp(-40.0 * t)) // soft attack, decaying tail
            }
            out[i] = (v * vol * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun silence(durMs: Int) = ShortArray(SR * durMs / 1000)

    /** Concatenate segments. */
    private fun after(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var p = 0
        for (a in parts) { a.copyInto(out, p); p += a.size }
        return out
    }

    /** Overlay segments (soft-clipped). */
    private fun mix(vararg parts: ShortArray): ShortArray {
        val n = parts.maxOf { it.size }
        val acc = IntArray(n)
        for (p in parts) {
            for (i in p.indices) {
                acc[i] = acc[i] + p[i]
            }
        }
        return ShortArray(n) { acc[it].coerceIn(-32768, 32767).toShort() }
    }

    private fun pcm(s: Sfx): ShortArray = when (s) {
        // crisp wooden click: descending snap + noise tick
        Sfx.MOVE -> mix(seg(1250.0, 820.0, 40, 0.30f), seg(0.0, 0.0, 26, 0.10f, noise = true))
        // capture: noise crack + low body thump
        Sfx.CAPTURE -> mix(seg(0.0, 0.0, 55, 0.20f, noise = true), seg(320.0, 140.0, 70, 0.28f))
        // check: urgent double chime
        Sfx.CHECK -> after(seg(880.0, 880.0, 65, 0.25f), silence(60), seg(1318.0, 1318.0, 110, 0.26f))
        // checkmate: falling fifth, then the low gong
        Sfx.CHECKMATE -> after(
            seg(784.0, 784.0, 110, 0.24f), silence(70),
            seg(587.0, 587.0, 130, 0.24f), silence(90),
            seg(392.0, 392.0, 280, 0.27f),
        )
        // victory: rising major arpeggio
        Sfx.VICTORY -> after(
            seg(659.0, 659.0, 90, 0.22f), silence(50),
            seg(784.0, 784.0, 90, 0.22f), silence(50),
            seg(988.0, 988.0, 110, 0.22f), silence(60),
            seg(1318.0, 1318.0, 220, 0.24f),
        )
        // defeat / puzzle fail: descending buzz
        Sfx.DEFEAT, Sfx.PUZZLE_FAIL -> mix(seg(240.0, 150.0, 240, 0.26f), seg(120.0, 80.0, 240, 0.16f))
    }

    /** Fire-and-forget cue; the buffer write happens off the main thread. */
    fun play(s: Sfx) {
        Thread {
            runCatching {
                val data = pcm(s)
                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    data.size * 2,
                    AudioTrack.MODE_STATIC,
                    0,
                )
                track.write(data, 0, data.size)
                track.play()
                Thread.sleep((data.size * 1000L / SR) + 120)
                runCatching { track.stop() }
                track.release()
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** §3.3 vibration — check = short snap, mate/victory = long thud. */
    fun buzz(context: Context, ms: Long, amplitude: Int = 170) {
        runCatching {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vib.hasVibrator()) return@runCatching
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(ms)
            }
        }
    }
}
