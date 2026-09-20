package com.thesystem.app.ui.training

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.thesystem.app.core.sensors.ImuStabilityWitness
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PUSH-UP ENGINE v4 — the deterministic core of CV-BATTLE-ARCHITECTURE §5/§16.
 * Counts ONLY inside the envelope the CalibrationGate locked: side view, torso-
 * normalized features, elbow flexion as the primary witness.
 *
 * Why this beats the v3 floor-span machine: the side view makes the ELBOW
 * angle — the single most informative joint — observable, and every length is
 * expressed in TORSOS, so thresholds no longer depend on body size, camera
 * distance or resolution. The double depth gate kills the whole cheat family:
 *
 *   VALID BOTTOM ⇔ θ_elbow ≤ θ_depth  AND  shoulder drop ≥ 0.28 torsos
 *
 *   a head-nod moves the shoulder ≈ 0.03 torsos — short by a factor of ten.
 *   a hip-dip leaves the shoulder parked — θ never dips, no descent triggers.
 *   a half rep reaches neither gate.
 *
 * A rep exists only as a completed trajectory:
 *   SEARCH → ARMED → DESCENDING → ASCENDING → REVIEW → VALID_REP
 * with hard vetoes (camera moved / pose dropout / slew), a decaying CHEAT_SCORE,
 * and the §13 fail-safe: soft-marginal candidates hold in REP REVIEW (≤1.5 s)
 * and are only awarded when clean evidence completes. FN > FP, always.
 */
class PushupEngineV4(
    private val profile: CalibProfile,
    private val strictness: Strictness,
    private val witness: ImuStabilityWitness,
    private val onRep: (Int, PoseRepCounter.RepQuality) -> Unit,
    private val onPhase: (PoseRepCounter.RepPhase, Float) -> Unit = { _, _ -> },
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
    private val onStatus: (EngineStatus) -> Unit = {},
) {

    enum class Strictness { STANDARD, STRICT }

    enum class EngineStatus {
        SEARCHING,      // waiting for a clean top position
        READY,          // armed — controlled reps only
        DESCENDING,
        ASCENDING,
        VERIFYING,      // REP REVIEW — hold position
        REJECT_DEPTH,   // transient — chest never dropped
        REJECT_FORM,    // transient — body line broken at the bottom
        REJECT_TEMPO,   // transient — impossibly fast / never committed
        REJECT_POSE,    // transient — camera lost the body mid-rep
        CAMERA_MOVED,   // IMU veto — re-anchoring
        SETTLE,         // hold the top — re-learning the baseline
        DISPUTED,       // cheat score tripped — clean hold to decay
    }

    private enum class State { SEARCH, ARMED, DESCENDING, ASCENDING, REVIEW, FROZEN }

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    var reps: Int = 0
        private set

    // ── adaptive thresholds from the calibration profile ─────────────────────
    private val thetaTop = profile.thetaTop
    private val thetaDepthEff = profile.thetaDepth +
        (if (profile.viewQuality < 1f) 8.0 else 0.0) +       // three-quarter grace (§5)
        (if (strictness == Strictness.STANDARD) 6.0 else 0.0) // quest recall slack
    private val depthFloor = if (strictness == Strictness.STRICT) 0.28 else 0.24
    private val reviewBand = 0.04
    private val lineReady = if (strictness == Strictness.STRICT) 0.12f else 0.15f
    private val lineBottom = if (strictness == Strictness.STRICT) 0.15f else 0.18f
    private val returnTol = if (strictness == Strictness.STRICT) 0.08f else 0.12f

    // ── per-frame estimates (EMA-smoothed) ───────────────────────────────────
    private var thetaEma: Double? = null
    private var prevThetaEma: Double? = null
    private var shoulderY: Float? = null
    private var shoulderX: Float? = null
    private var baselineY = profile.shoulderY0      // refined while parked at the top
    private var romEst = 88.0                        // learned ONLY from committed reps

    // ── state ────────────────────────────────────────────────────────────────
    private var state = State.SEARCH
    private var topHoldMs = 0L
    private var searchLastGoodAt = 0L
    private var lastFrameAt = 0L
    private var noPoseMs = 0L
    private var fallStreak = 0
    private var riseStreak = 0
    private var armedLeftTopAt = 0L
    private var frozenAt = 0L
    private var rearmMs = 0L
    private var rearmLastGoodAt = 0L

    // per-attempt witnesses
    private var trough = 180.0
    private var maxDrop = 0f
    private var lineAtDeepest = 0f
    private var descentStartAt = 0L
    private var startY = 0f
    private var startX = 0f
    private var tReachedBottom = 0L
    private var tLeftBottom = 0L
    private var minVis = 1f
    private var bounceCount = 0
    private var topConfirmMs = 0L
    private var topWaitMs = 0L
    private var reviewDeadline = 0L
    private var reviewHoldMs = 0L
    private var reviewLastCleanAt = 0L
    private var reviewReasonIsCheat = false
    private var lastRepAt = 0L
    private var lastSym = 1f

    // cheat score — decaying accumulator (×0.95 per second, §6)
    private var cheat = 0.0

    private var imgW = 1f
    private var imgH = 1f
    private var wristRefX = 0f
    private var wristRefY = 0f
    private var wristRefSet = false

    // status cadence — transient rejects hold the ribbon ~1.1 s
    private var reported: EngineStatus = EngineStatus.SEARCHING
    private var statusHoldUntil = 0L

    companion object {
        const val LIKELIHOOD = 0.42f
        const val VIS_MARGINAL = 0.55f

        const val EMA_TH = 0.5     // Double math — elbow theta lives in Double
        const val EMA_Y = 0.5f     // Float math — shoulder coords live in Float

        const val TOP_HOLD_MS = 450L         // READY arming hold (§5 ~500 ms)
        const val REARM_HOLD_MS = 300L       // cumulative stable-top re-lock (grace 250)
        const val GAP_ABORT_MS = 400L        // LK-bridge limit (§3) — beyond: abort
        const val REVIEW_MS = 1500L          // §13 REP REVIEW window
        const val REVIEW_HOLD_MS = 350L      // clean evidence needed inside review
        const val TOP_CONFIRM_MS = 190L      // θ ≥ θ_top sustained to finalize
        const val TOP_MAX_WAIT_MS = 900L     // top reached but body never returned
        const val FALL_MIN_STREAK = 3
        const val RISE_MIN_STREAK = 3
        const val RISE_EXIT_DEG = 8.0        // θ must clear trough + this to ascend
        const val DESC_MIN_DROP = 0.04f      // descent arming — ignores breathing
        const val MIN_REP_MS = 480L          // physics gate (§6)
        const val MAX_REP_MS = 4200L
        const val BOTTOM_MIN_DWELL_MS = 90L  // spike armour — real bottoms dwell
        const val REP_COOLDOWN_MS = 450L
        const val SLEW_TORSOS = 0.35f        // one-frame jump = teleport; the fastest
                                             // real descent peak is ≤0.25 (EMA lag incl.)
        const val BAIL_BOUNCES = 3
        const val CHEAT_LIMIT = 0.50         // §6 — commits require score below
        const val CHEAT_DECAY = 0.95         // per second
    }

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        val media = imageProxy.image ?: run { imageProxy.close(); return }
        val rot = imageProxy.imageInfo.rotationDegrees
        if (rot == 90 || rot == 270) {
            imgW = imageProxy.height.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.width.toFloat().coerceAtLeast(1f)
        } else {
            imgW = imageProxy.width.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.height.toFloat().coerceAtLeast(1f)
        }
        val image = InputImage.fromMediaImage(media, rot)
        detector.process(image)
            .addOnSuccessListener { pose -> frame(pose) }
            .addOnFailureListener { frame(null) }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun reset() {
        reps = 0
        state = State.SEARCH
        thetaEma = null; prevThetaEma = null
        shoulderY = null; shoulderX = null
        topHoldMs = 0L; searchLastGoodAt = 0L; noPoseMs = 0L
        fallStreak = 0; riseStreak = 0
        wristRefSet = false
        cheat = 0.0
        reported = EngineStatus.SEARCHING
        statusHoldUntil = 0L
    }

    fun close() = detector.close()

    // ── the frame loop — §16 pseudocode, field-hardened ──────────────────────
    private fun frame(pose: Pose?) {
        val now = System.currentTimeMillis()
        val dt = if (lastFrameAt == 0L) 33L else (now - lastFrameAt).coerceIn(1L, 200L)
        lastFrameAt = now

        // cheat score decays on wall time — patterns convict, anomalies fade
        cheat *= CHEAT_DECAY.pow(dt.toDouble() / 1000.0)
        if (cheat >= 1.0 && state != State.FROZEN) hold(EngineStatus.DISPUTED)

        // ── IMU hard veto: a moving camera certifies nothing (§2) ───────────
        if (!witness.stable) {
            if (state == State.DESCENDING || state == State.ASCENDING || state == State.REVIEW) {
                cheat += 0.25                       // pending rep hard-vetoed
                hold(EngineStatus.CAMERA_MOVED)
                abortCycle()
            }
            if (state != State.FROZEN) {
                state = State.FROZEN
                frozenAt = now
                rearmMs = 0L
                if (reported != EngineStatus.CAMERA_MOVED) hold(EngineStatus.CAMERA_MOVED)
            }
        }
        if (state == State.FROZEN) {
            // keep the EMAs fed through the freeze — re-anchoring against a
            // stale pre-freeze position would re-lock onto a ghost baseline
            val ff = features(pose)
            if (ff != null) {
                prevThetaEma = thetaEma
                thetaEma = thetaEma?.let { it + EMA_TH * (ff.theta - it) } ?: ff.theta
                shoulderY = shoulderY?.let { it + EMA_Y * (ff.y - it) } ?: ff.y
                shoulderX = shoulderX?.let { it + EMA_Y * (ff.x - it) } ?: ff.x
            }
            // CUMULATIVE re-arm with grace — a hunter mid-set never holds the
            // top for an unbroken second; an uninterrupted-hold demand would
            // freeze the session forever after one bump (sim scenario 5).
            if (witness.stable && ff != null && ff.theta >= thetaTop && ff.lineDev <= lineReady) {
                rearmMs += dt
                rearmLastGoodAt = now
                hold(EngineStatus.SETTLE)
                if (rearmMs >= REARM_HOLD_MS) {
                    // mini-recalibration: re-anchor the baseline, keep the count
                    shoulderY?.let { baselineY = it }
                    state = State.SEARCH
                    topHoldMs = TOP_HOLD_MS / 2   // preloaded, same as post-rep
                }
            } else if (now - rearmLastGoodAt > 250L) {
                rearmMs = 0L
            }
            emitPhase()
            return
        }

        // ── pose acquisition ─────────────────────────────────────────────────
        val f = features(pose)
        if (f == null) {
            noPoseMs += dt
            if (state == State.DESCENDING || state == State.ASCENDING || state == State.REVIEW) {
                if (noPoseMs > GAP_ABORT_MS) {
                    cheat += 0.20                   // sequence abort — never imagine a bottom
                    hold(EngineStatus.REJECT_POSE)
                    abortCycle()
                    state = State.SEARCH
                    topHoldMs = 0L
                }
            } else if (noPoseMs > 500 && reported != EngineStatus.REJECT_POSE) {
                hold(EngineStatus.SEARCHING)
            }
            emitPhase()
            return
        }
        noPoseMs = 0L

        // slew guard — relocation, not muscle (v3 lesson, torso-normalized)
        val py = shoulderY
        if (py != null && abs(f.y - py) > SLEW_TORSOS * profile.torsoLenPx && py != 0f) {
            shoulderY = f.y; shoulderX = f.x
            if (state == State.DESCENDING || state == State.ASCENDING || state == State.REVIEW) {
                cheat += 0.20
                hold(EngineStatus.REJECT_POSE)
                abortCycle()
            }
            state = State.SEARCH
            topHoldMs = 0L
            emitPhase()
            return
        }

        // EMA updates
        prevThetaEma = thetaEma
        thetaEma = thetaEma?.let { it + EMA_TH * (f.theta - it) } ?: f.theta
        shoulderY = shoulderY?.let { it + EMA_Y * (f.y - it) } ?: f.y
        shoulderX = shoulderX?.let { it + EMA_Y * (f.x - it) } ?: f.x
        val th = thetaEma ?: f.theta
        val sy = shoulderY ?: f.y
        val sx = shoulderX ?: f.x
        val prevTh = prevThetaEma ?: th

        // theta falls/rises streaks (turn detectors)
        if (th < prevTh - 1.2) fallStreak++ else if (th > prevTh - 0.4) fallStreak = 0
        if (th > prevTh + 1.2) riseStreak++ else if (th < prevTh + 0.4) riseStreak = 0

        val drop = abs(sy - baselineY) / profile.torsoLenPx

        when (state) {
            State.SEARCH -> {
                // CUMULATIVE top hold with grace — ML Kit hip jitter makes the
                // body-line flicker; an unbroken-hold demand strands one rep per
                // long set (sim scenario 12). Baseline refines ONLY while parked
                // at a proven top — descent frames never touch it (v3 lesson).
                val ok = th >= thetaTop && f.lineDev <= lineReady && wristPlanted(f)
                if (ok) {
                    topHoldMs += dt
                    searchLastGoodAt = now
                    baselineY += 0.10f * (sy - baselineY)
                    if (topHoldMs >= TOP_HOLD_MS) {
                        state = State.ARMED
                        armedLeftTopAt = 0L
                        wristRefX = f.wristX; wristRefY = f.wristY; wristRefSet = f.wristSeen
                        hold(EngineStatus.READY)
                    } else if (reported != EngineStatus.READY && now >= statusHoldUntil) {
                        hold(EngineStatus.SEARCHING)
                    }
                } else {
                    if (now - searchLastGoodAt > 250L) topHoldMs = 0L
                    if (reported != EngineStatus.SEARCHING && now >= statusHoldUntil && reported != EngineStatus.READY) {
                        hold(EngineStatus.SEARCHING)
                    }
                }
            }

            State.ARMED -> {
                if (th < thetaTop - 12) {
                    // folding — confirm descent or dismiss as noise
                    if (fallStreak >= FALL_MIN_STREAK && drop >= DESC_MIN_DROP) beginDescent(f, sy, sx, now)
                } else {
                    held(f, sy, now)
                }
                if (state == State.ARMED && now >= statusHoldUntil) hold(EngineStatus.READY)
            }

            State.DESCENDING -> {
                witnessCycle(f, th, sy, sx, now)
                if (riseStreak >= RISE_MIN_STREAK && th >= trough + RISE_EXIT_DEG) {
                    state = State.ASCENDING
                    topConfirmMs = 0L
                    topWaitMs = 0L
                } else if (state == State.DESCENDING) {
                    holdPhaseStatus()
                }
            }

            State.ASCENDING -> {
                witnessCycle(f, th, sy, sx, now)
                if (state != State.ASCENDING) { // bounce/abort already handled inside
                } else if (th <= thetaTop - 15 && fallStreak >= 2) {
                    // genuinely dipped back down before locking — same attempt continues
                    // (fallStreak≥2 = a REAL re-descent; EMA plateau noise can't fake it)
                    bounceCount++
                    if (bounceCount > BAIL_BOUNCES) {
                        hold(EngineStatus.REJECT_TEMPO)
                        cheat += 0.10
                        abortCycle()
                        state = State.SEARCH
                        topHoldMs = TOP_HOLD_MS / 2
                    } else {
                        state = State.DESCENDING
                    }
                } else if (th >= thetaTop) {
                    topConfirmMs += dt
                    topWaitMs += dt
                    if (topConfirmMs >= TOP_CONFIRM_MS) {
                        if (abs(sy - startY) <= returnTol * profile.torsoLenPx) {
                            finalizeCandidate(now)
                        } else if (topWaitMs >= TOP_MAX_WAIT_MS) {
                            // arms locked but the body never came back up —
                            // the sag-extension trick. FORM, never depth.
                            cheat += 0.15
                            hold(EngineStatus.REJECT_FORM)
                            abortCycle()
                            state = State.SEARCH
                            topHoldMs = TOP_HOLD_MS / 2
                        }
                    }
                } else {
                    topConfirmMs = 0L
                    holdPhaseStatus()
                }
            }

            State.REVIEW -> {
                // §13: soft-marginal candidate — the machine holds, the evidence
                // completes. CUMULATIVE hold with a short grace: starting the next
                // descent a hair early must not void an honest borderline rep.
                val clean = witness.stable && th >= thetaTop &&
                    abs(sy - startY) <= returnTol * profile.torsoLenPx &&
                    cheat < CHEAT_LIMIT
                if (clean) {
                    reviewHoldMs += dt
                    reviewLastCleanAt = now
                    if (reviewHoldMs >= REVIEW_HOLD_MS) commit(now)
                } else if (now - reviewLastCleanAt > 200L) {
                    reviewHoldMs = 0L
                }
                if (state == State.REVIEW && now >= reviewDeadline) {
                    hold(EngineStatus.REJECT_DEPTH)   // silent reject — count does not move
                    abortCycle()
                    state = State.SEARCH
                    topHoldMs = TOP_HOLD_MS / 2
                }
            }

            State.FROZEN -> Unit
        }

        emitPhase()
    }

    // ── cycle helpers ────────────────────────────────────────────────────────
    private fun beginDescent(f: Feat, sy: Float, sx: Float, now: Long) {
        state = State.DESCENDING
        trough = thetaEma ?: f.theta
        maxDrop = 0f
        lineAtDeepest = f.lineDev
        descentStartAt = now
        startY = baselineY   // the refined TOP reference — never the EMA-lagged
                             // mid-descent y, or the return check rejects clean reps
        startX = sx
        tReachedBottom = 0L
        tLeftBottom = 0L
        minVis = f.vis
        bounceCount = 0
        lastSym = f.sym
    }

    private fun witnessCycle(f: Feat, th: Double, sy: Float, sx: Float, now: Long) {
        if (th < trough) trough = th
        val drop = abs(sy - baselineY) / profile.torsoLenPx
        if (drop > maxDrop) {
            maxDrop = drop
            lineAtDeepest = f.lineDev   // line judged AT the deepest frame (§5)
        }
        if (th <= thetaDepthEff + 8.0) {
            if (tReachedBottom == 0L) tReachedBottom = now
            tLeftBottom = now
        }
        if (f.vis < minVis) minVis = f.vis
        // strict rooms also prosecute lateral repositioning mid-cycle
        if (strictness == Strictness.STRICT && abs(sx - startX) > 0.5f * profile.torsoLenPx) {
            cheat += 0.20
            hold(EngineStatus.REJECT_POSE)
            abortCycle()
            state = State.SEARCH
            topHoldMs = 0L
        }
    }

    private fun held(f: Feat, sy: Float, now: Long) {
        // top refreshed — keep baseline honest while the hunter holds
        baselineY += 0.06f * (sy - baselineY)
        armedLeftTopAt = 0L
    }

    private fun finalizeCandidate(now: Long) {
        val tempo = now - descentStartAt
        val dwell = if (tLeftBottom > 0 && tReachedBottom > 0) tLeftBottom - tReachedBottom else 0L

        val depthShort = maxDrop < depthFloor || trough > thetaDepthEff
        val depthMarginal = maxDrop < depthFloor + reviewBand || trough > thetaDepthEff - 6.0
        val formBad = lineAtDeepest > lineBottom + 0.06f
        val formMarginal = lineAtDeepest > lineBottom
        val tempoBad = tempo < MIN_REP_MS || tempo > MAX_REP_MS || dwell < BOTTOM_MIN_DWELL_MS
        val visMarginal = minVis < VIS_MARGINAL
        // stood-up / walked-away excursion — shoulder traveled more than any
        // plank ever can (~arm length 1.2 torsos): not a rep, not a cheat, silent.
        val depthInsane = maxDrop > 1.35f

        when {
            depthInsane -> endAttempt()
            tempoBad -> {
                cheat += 0.10
                hold(EngineStatus.REJECT_TEMPO)
                endAttempt()
            }
            maxDrop < depthFloor - reviewBand || trough > thetaDepthEff + 6.0 -> {
                cheat += 0.10                    // half rep — the classic fake
                hold(EngineStatus.REJECT_DEPTH)
                endAttempt()
            }
            formBad -> {
                cheat += 0.15                    // sag / pike at the bottom
                hold(EngineStatus.REJECT_FORM)
                endAttempt()
            }
            depthMarginal || formMarginal || visMarginal || cheat >= CHEAT_LIMIT - 0.05 -> {
                // §13 REP REVIEW — hold ≤1.5 s, evidence completes or nothing counts
                state = State.REVIEW
                reviewDeadline = now + REVIEW_MS
                reviewHoldMs = 0L
                reviewLastCleanAt = now
                reviewReasonIsCheat = cheat >= CHEAT_LIMIT - 0.05
                hold(EngineStatus.VERIFYING)
            }
            else -> commit(now)
        }
    }

    private fun commit(now: Long) {
        if (now - lastRepAt < REP_COOLDOWN_MS) { endAttempt(); return }
        lastRepAt = now
        reps += 1
        cheat = (cheat - 0.05).coerceAtLeast(0.0)
        // the ROM only ever learns from WITNESSED-VALID bottoms (contamination
        // can never raise the bar beyond a rep we actually counted)
        val witnessedRom = thetaTop - trough
        romEst += 0.35 * (witnessedRom - romEst)
        onRep(
            reps,
            PoseRepCounter.RepQuality(
                bottomDeg = trough,
                topDeg = thetaEma ?: thetaTop,
                tempoMs = now - descentStartAt,
                depthScore = ((thetaTop - trough) / (thetaTop - thetaDepthEff)).coerceIn(0.0, 1.0).toFloat(),
                symmetry = lastSym,
            ),
        )
        hold(EngineStatus.READY)
        endAttempt()
    }

    private fun abortCycle() {
        trough = 180.0; maxDrop = 0f; bounceCount = 0
        armedLeftTopAt = 0L
        wristRefSet = false
    }

    private fun endAttempt() {
        abortCycle()
        state = State.SEARCH
        topHoldMs = TOP_HOLD_MS / 2
    }

    // ── features ─────────────────────────────────────────────────────────────
    private class Feat(
        val theta: Double, val x: Float, val y: Float,
        val lineDev: Float, val vis: Float, val sym: Float,
        val wristX: Float, val wristY: Float, val wristSeen: Boolean,
    )

    private fun features(pose: Pose?): Feat? {
        pose ?: return null
        onLandmarks(pose.allPoseLandmarks.map { (it.position.x / imgW) to (it.position.y / imgH) })
        fun lm(id: Int) = pose.getPoseLandmark(id)?.takeIf { it.inFrameLikelihood >= LIKELIHOOD }

        val lSh = lm(PoseLandmark.LEFT_SHOULDER) ?: return null
        val rSh = lm(PoseLandmark.RIGHT_SHOULDER) ?: return null
        val lHip = lm(PoseLandmark.LEFT_HIP) ?: return null
        val rHip = lm(PoseLandmark.RIGHT_HIP) ?: return null
        val lAnk = lm(PoseLandmark.LEFT_ANKLE) ?: return null
        val rAnk = lm(PoseLandmark.RIGHT_ANKLE) ?: return null

        val shX = (lSh.position.x + rSh.position.x) / 2f
        val shY = (lSh.position.y + rSh.position.y) / 2f
        val hipX = (lHip.position.x + rHip.position.x) / 2f
        val hipY = (lHip.position.y + rHip.position.y) / 2f
        val ankX = (lAnk.position.x + rAnk.position.x) / 2f
        val ankY = (lAnk.position.y + rAnk.position.y) / 2f

        // per-arm elbow angle where visible — the side-view gift
        val thetaL = elbow(lSh, lm(PoseLandmark.LEFT_ELBOW), lm(PoseLandmark.LEFT_WRIST))
        val thetaR = elbow(rSh, lm(PoseLandmark.RIGHT_ELBOW), lm(PoseLandmark.RIGHT_WRIST))
        val theta = when {
            thetaL != null && thetaR != null -> if (thetaL.first >= thetaR.first) thetaL.second else thetaR.second
            thetaL != null -> thetaL.second
            thetaR != null -> thetaR.second
            else -> return null
        }
        val sym = if (thetaL != null && thetaR != null)
            (1.0 - abs(thetaL.second - thetaR.second) / 60.0).coerceIn(0.0, 1.0).toFloat() else 0.7f

        // body-line: hip distance from the shoulder→ankle line, in torsos
        val dx = ankX - shX; val dy = ankY - shY
        val len = sqrt(max(dx * dx + dy * dy, 1e-6f))
        val perp = abs(dx * (hipY - shY) - (hipX - shX) * dy) / len
        val torso = sqrt((shX - hipX) * (shX - hipX) + (shY - hipY) * (shY - hipY)).coerceAtLeast(1f)
        val lineDev = perp / torso

        val vis = minOf(
            lSh.inFrameLikelihood, rSh.inFrameLikelihood,
            lHip.inFrameLikelihood, rHip.inFrameLikelihood,
            lAnk.inFrameLikelihood, rAnk.inFrameLikelihood,
        )
        val wr = lm(PoseLandmark.LEFT_WRIST) ?: lm(PoseLandmark.RIGHT_WRIST)
        return Feat(
            theta = theta, x = shX, y = shY,
            lineDev = lineDev, vis = vis, sym = sym,
            wristX = wr?.position?.x ?: 0f,
            wristY = wr?.position?.y ?: 0f,
            wristSeen = wr != null,
        )
    }

    /** (visibility, angle) so the best-witnessed arm wins — the near arm in profile. */
    private fun elbow(sh: PoseLandmark, el: PoseLandmark?, wr: PoseLandmark?): Pair<Float, Double>? {
        if (el == null || wr == null) return null
        val vis = minOf(sh.inFrameLikelihood, el.inFrameLikelihood, wr.inFrameLikelihood)
        return vis to angleBetween(
            sh.position.x, sh.position.y,
            el.position.x, el.position.y,
            wr.position.x, wr.position.y,
        )
    }

    private fun wristPlanted(f: Feat): Boolean {
        if (!f.wristSeen || !wristRefSet) return true   // can't judge what we can't see
        val d = sqrt((f.wristX - wristRefX).let { it * it } + (f.wristY - wristRefY).let { it * it })
        return d <= 0.10f * profile.torsoLenPx
    }

    // ── status / phase plumbing ──────────────────────────────────────────────
    private fun hold(s: EngineStatus) {
        val now = System.currentTimeMillis()
        val transient = s == EngineStatus.REJECT_DEPTH || s == EngineStatus.REJECT_FORM ||
            s == EngineStatus.REJECT_TEMPO || s == EngineStatus.REJECT_POSE
        if (transient) statusHoldUntil = now + 1100L
        if (s != reported) {
            reported = s
            onStatus(s)
        }
    }

    private fun holdPhaseStatus() {
        val now = System.currentTimeMillis()
        if (now < statusHoldUntil) return
        hold(
            when (state) {
                State.DESCENDING -> EngineStatus.DESCENDING
                State.ASCENDING -> EngineStatus.ASCENDING
                State.REVIEW -> EngineStatus.VERIFYING
                else -> EngineStatus.READY
            }
        )
    }

    private fun emitPhase() {
        val drop = shoulderY?.let { abs(it - baselineY) / profile.torsoLenPx } ?: 0f
        val hint = (drop / depthFloor.toFloat()).coerceIn(0f, 1f)
        val phase = when (state) {
            State.SEARCH, State.FROZEN -> PoseRepCounter.RepPhase.SEARCH
            State.ARMED -> PoseRepCounter.RepPhase.TOP
            State.DESCENDING -> PoseRepCounter.RepPhase.DESCENDING
            State.ASCENDING -> PoseRepCounter.RepPhase.ASCENDING
            State.REVIEW -> PoseRepCounter.RepPhase.BOTTOM
        }
        onPhase(phase, hint)
    }

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double {
        val bax = ax - bx; val bay = ay - by
        val bcx = cx - bx; val bcy = cy - by
        val dot = bax * bcx + bay * bcy
        val m1 = sqrt(max(bax * bax + bay * bay, 1e-6f))
        val m2 = sqrt(max(bcx * bcx + bcy * bcy, 1e-6f))
        return Math.toDegrees(acos((dot / (m1 * m2)).toDouble().coerceIn(-1.0, 1.0)))
    }
}
