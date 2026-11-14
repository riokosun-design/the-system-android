package com.thesystem.app.ui.chess

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.thesystem.app.R

/**
 * §3.3 + vC55 §A3 — SYSTEM COMBAT AUDIO: BUNDLED WOODEN FOLEY.
 *
 * The old oscillator sine-beep synthesizer is EXECUTED — every cue is
 * now a physically-modeled wooden knock (offline procedural foley shipped as
 * 16-bit PCM WAV in res/raw): soft thud for moves, crisp wood-on-wood tap for
 * captures, warm chime-knock for check, deep bass thump for King Crush.
 * Playback routes through SoundPool at a normalized, non-piercing 0.50f gain.
 *
 * The sound covenant is unchanged: persisted mute chips across every chess
 * surface honor one shared flag.
 */
object ChessSfx {

    enum class Sfx(val res: Int) {
        MOVE(R.raw.chess_move),          // soft low-frequency wooden thud
        CAPTURE(R.raw.chess_capture),    // crisp wood-on-wood tap + clatter
        CHECK(R.raw.chess_check),        // soft warm chime-knock
        CHECKMATE(R.raw.chess_mate),     // falling triple knock
        VICTORY(R.raw.chess_victory),    // rising warm marimba knocks
        DEFEAT(R.raw.chess_defeat),      // two soft low descending knocks
        PUZZLE_FAIL(R.raw.chess_defeat), // failure shares the defeat foley
        CRUSH(R.raw.chess_crush),        // deep low-bass thump
    }

    /** vC55 §A3 — the 0.50f comfort law: nothing above half gain, ever. */
    private const val GAIN = 0.50f

    // ── vC53 §3.4 — SOUND COVENANT: persisted mute, honored by EVERY cue ─────
    @Volatile private var appCtx: Context? = null
    @Volatile private var muted: Boolean = false
    @Volatile private var pool: SoundPool? = null
    private val soundIds = HashMap<Sfx, Int>()
    private val loaded = HashSet<Sfx>()

    /** Call once from Application.onCreate — reads the stored covenant and arms
     *  the SoundPool with the bundled wooden bank (decode failures disable the
     *  cue silently; the covenant must never crash a 2GB phone). */
    fun attach(context: Context) {
        appCtx = context.applicationContext
        muted = prefsMuted(context)
        if (pool != null) return
        runCatching {
            val sp = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            // track load state per cue; only fully-decoded samples ever play
            sp.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    val cue = soundIds.entries.firstOrNull { it.value == sampleId }?.key
                    if (cue != null) synchronized(loaded) { loaded.add(cue) }
                }
            }
            Sfx.entries.forEach { cue ->
                soundIds[cue] = sp.load(context.applicationContext, cue.res, 1)
            }
            pool = sp
        }
    }

    private fun prefsMuted(c: Context) =
        c.getSharedPreferences("system_chess", Context.MODE_PRIVATE).getBoolean("sfx_muted", false)

    fun isMuted(): Boolean = muted
    fun setMuted(context: Context, value: Boolean) {
        muted = value
        if (appCtx == null) attach(context)
        context.getSharedPreferences("system_chess", Context.MODE_PRIVATE)
            .edit().putBoolean("sfx_muted", value).apply()
    }

    /** Fire-and-forget cue at the 0.50f comfort gain; no-op while muted or if
     *  the sample has not decoded yet (first frames after cold start). */
    fun play(s: Sfx) {
        if (muted) return
        val sp = pool ?: run { appCtx?.let { attach(it) }; pool ?: return }
        val id = soundIds[s] ?: return
        if (s !in synchronized(loaded) { HashSet(loaded) }) return
        runCatching { sp.play(id, GAIN, GAIN, 1, 0, 1f) }
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
