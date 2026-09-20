package com.thesystem.app.ui.training

import androidx.camera.core.ImageProxy
import com.thesystem.app.core.sensors.ImuStabilityWitness
import com.thesystem.app.ui.training.estimate.LandmarkFrame
import com.thesystem.app.ui.training.estimate.PoseEstimator
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PUSH-UP ENGINE v4.1 — the deterministic core of CV-BATTLE-ARCHITECTURE §5/§16,
 * now running on the estimation layer (Phase 1): MoveNet Thunder primary,
 * ML Kit failover, identical canonical landmarks either way.
 *
 * A rep exists only as a completed trajectory:
 *   SEARCH → ARMED → DESCENDING → ASCENDING → REVIEW → VALID_REP
 * Valid bottom ⇔ θ_elbow ≤ θ_depth AND shoulder drop ≥ depth floor (torsos).
 * Phase 2/3 wiring: [onFrame12] streams the 12-dim normalized feature vector
 * per frame (§3 window for the shadow TCN), [onDecision] names every finalized
 * verdict (the corpus labels for Phase 2), and each commit carries the live
 * cheat score + engine version into battle-signed events (§8).
 */
class PushupEngineV4(
    private val profile: CalibProfile,
    private val strictness: Strictness,
    private val witness: ImuStabilityWitness,
    private val estimator: PoseEstimator,
    private val engineVersion: String = "v4.1",
    private val onRep: (Int, PoseRepCounter.RepQuality) -> Unit,
    private val onPhase: (PoseRepCounter.RepPhase, Float) -> Unit = { _, _ -> },
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
    private val onStatus: (EngineStatus) -> Unit = {},
    private val onFrame12: (FloatArray) -> Unit = {},
    private val onDecision: (String) -> Unit = {},
) {

    enum class Strictness { STANDARD, STRICT }

    enum class EngineStatus {
        SEARCHING, READY, DESCENDING, ASCENDING,
        VERIFYING, REJECT_DEPTH, REJECT_FORM, REJECT_TEMPO, REJECT_POSE,
        CAMERA_MOVED, SETTLE, DISPUTED,
    }

    private enum class State { SEARCH, ARMED, DESCENDING, ASCENDING, REVIEW, FROZEN }

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
    private var baselineY = profile.shoulderY0
    private var romEst = 88.0

    // ── state ────────────────────────────────────────────────────────────────
    private var state = State.SEARCH
    private var topHoldMs = 0L
    private var searchLastGoodAt = 0L
    private var lastFrameAt = 0L
    private var lastDtMs = 33f
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

    private var cheat = 0.0

    private var imgW = 1f
    private var imgH = 1f
    private var wristRefX = 0f
    private var wristRefY = 0f
    private var wristRefSet = false

    private var reported: EngineStatus = EngineStatus.SEARCHING
    private var statusHoldUntil = 0L

    companion object {
        const val LIKELIHOOD = 0.42f
        const val VIS_MARGINAL = 0.55f

        const val EMA_TH = 0.5
        const val EMA_Y = 0.5f

        const val TOP_HOLD_MS = 450L
        const val REARM_HOLD_MS = 300L       // cumulative stable-top re-lock (grace 250)
        const val GAP_ABORT_MS = 400L        // LK-bridge limit (§3) — beyond: abort
        const val REVIEW_MS = 1500L
        const val REVIEW_HOLD_MS = 350L
        const val TOP_CONFIRM_MS = 190L
        const val TOP_MAX_WAIT_MS = 900L
        const val FALL_MIN_STREAK = 3
        const val RISE_MIN_STREAK = 3
        const val RISE_EXIT_DEG = 8.0
        const val DESC_MIN_DROP = 0.04f
        const val MIN_REP_MS = 480L
        const val MAX_REP_MS = 4200L
        const val BOTTOM_MIN_DWELL_MS = 90L
        const val REP_COOLDOWN_MS = 450L
        const val SLEW_TORSOS = 0.35f        // teleport guard (fastest real peak ≤0.25)
        const val BAIL_BOUNCES = 3
        const val CHEAT_LIMIT = 0.50
        const val CHEAT_DECAY = 0.95
    }

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        val rot = imageProxy.imageInfo.rotationDegrees
        if (rot == 90 || rot == 270) {
            imgW = imageProxy.height.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.width.toFloat().coerceAtLeast(1f)
        } else {
            imgW = imageProxy.width.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.height.toFloat().coerceAtLeast(1f)
        }
        val frame = estimator.estimate(imageProxy, rot, imgW, imgH)
        imageProxy.close()
        frameAdvance(frame)
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

    fun close() = Unit   // estimator is owned (and closed) by the screen's router

    // ── the frame loop — §16 pseudocode, field-hardened, sim-proven ──────────
    private fun frameAdvance(f0: LandmarkFrame?) {
        val now = System.currentTimeMillis()
        val dt = if (lastFrameAt == 0L) 33L else (now - lastFrameAt).coerceIn(1L, 200L)
        lastFrameAt = now
        lastDtMs = dt.toFloat()

        cheat *= CHEAT_DECAY.pow(dt.toDouble() / 1000.0)
        if (cheat >= 1.0 && state != State.FROZEN) hold(EngineStatus.DISPUTED)

        // ── IMU hard veto: a moving camera certifies nothing (§2) ───────────
        if (!witness.stable) {
            if (state == State.DESCENDING || state == State.ASCENDING || state == State.REVIEW) {
                cheat += 0.25
                onDecision("ABORT_IMU")
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
            // EMAs stay fed through the freeze (no ghost baselines), and the
            // re-arm is CUMULATIVE with grace — mid-set recovery must not
            // demand an unbroken top hold (sim scenario 5)
            if (f0 != null) feedEma(f0)
            if (witness.stable && f0 != null && topCondRaw(f0)) {
                rearmMs += dt
                rearmLastGoodAt = now
                hold(EngineStatus.SETTLE)
                if (rearmMs >= REARM_HOLD_MS) {
                    shoulderY?.let { baselineY = it }
                    state = State.SEARCH
                    topHoldMs = TOP_HOLD_MS / 2
                }
            } else if (now - rearmLastGoodAt > 250L) {
                rearmMs = 0L
            }
            emitPhase()
            return
        }

        // ── pose acquisition ─────────────────────────────────────────────────
        val f = features(f0)
        if (f == null) {
            noPoseMs += dt
            if (state == State.DESCENDING || state == State.ASCENDING || state == State.REVIEW) {
                if (noPoseMs > GAP_ABORT_MS) {
                    cheat += 0.20
                    onDecision("ABORT_POSE")
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

        // slew guard — relocation, not muscle (0.35 torsos/frame: teleports only)
        val py = shoulderY
        if (py != null && abs(f.y - py) > SLEW_TORSOS * profile.torsoLenPx && py != 0f) {
            shoulderY = f.y; shoulderX = f.x
            if (state == State.DESCENDING || state == State.ASCENDING || state == State.REVIEW) {
                cheat += 0.20
                onDecision("ABORT_SLEW")
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

        if (th < prevTh - 1.2) fallStreak++ else if (th > prevTh - 0.4) fallStreak = 0
        if (th > prevTh + 1.2) riseStreak++ else if (th < prevTh + 0.4) riseStreak = 0

        val drop = abs(sy - baselineY) / profile.torsoLenPx

        // Phase 3: stream the §3 12-dim window the shadow TCN scores
        onFrame12(
            floatArrayOf(
                (f.thetaL / 180.0).toFloat(),
                (f.thetaR / 180.0).toFloat(),
                (sy - baselineY) / profile.torsoLenPx,
                (f.hipY - baselineY) / profile.torsoLenPx,
                f.lineDev,
                f.wristFixL, f.wristFixR,
                f.vis,
                (lastDtMs / 100f).coerceIn(0f, 2f),
                f.torsoNow / profile.torsoLenPx,
                profile.viewQuality,
                cheat.toFloat().coerceIn(0f, 1.2f),
            )
        )

        when (state) {
            State.SEARCH -> {
                // CUMULATIVE top hold with grace (sim scenario 12): ML Kit/MoveNet
                // hip jitter flickers the body line; unbroken holds strand reps.
                val ok = th >= thetaTop && f.lineDev <= lineReady && wristPlanted(f)
                if (ok) {
                    topHoldMs += dt
                    searchLastGoodAt = now
                    baselineY += 0.10f * (sy - baselineY)   // refined ONLY at a proven top
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
                    if (fallStreak >= FALL_MIN_STREAK && drop >= DESC_MIN_DROP) beginDescent(f, sy, sx, now)
                } else {
                    held(sy)
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
                if (state != State.ASCENDING) { // abort already handled inside
                } else if (th <= thetaTop - 15 && fallStreak >= 2) {
                    // genuine re-descent before lockout — same attempt continues
                    bounceCount++
                    if (bounceCount > BAIL_BOUNCES) {
                        hold(EngineStatus.REJECT_TEMPO)
                        cheat += 0.10
                        onDecision("REJECT_TEMPO")
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
                            // arms locked but the body never came back — sag trick
                            cheat += 0.15
                            hold(EngineStatus.REJECT_FORM)
                            onDecision("REJECT_FORM")
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
                // §13 hold — CUMULATIVE with a 200 ms grace so starting the next
                // descent early doesn't void an honest borderline rep
                val clean = witness.stable && th >= thetaTop &&
                    abs(sy - startY) <= returnTol * profile.torsoLenPx &&
                    cheat < CHEAT_LIMIT
                if (clean) {
                    reviewHoldMs += dt
                    reviewLastCleanAt = now
                    if (reviewHoldMs >= REVIEW_HOLD_MS) {
                        onDecision("REVIEW_COMMIT")
                        commit(now)
                    }
                } else if (now - reviewLastCleanAt > 200L) {
                    reviewHoldMs = 0L
                }
                if (state == State.REVIEW && now >= reviewDeadline) {
                    hold(EngineStatus.REJECT_DEPTH)
                    onDecision("REVIEW_TIMEOUT")
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
        startY = baselineY   // refined TOP reference — never the EMA-lagged y
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
        if (strictness == Strictness.STRICT && abs(sx - startX) > 0.5f * profile.torsoLenPx) {
            cheat += 0.20
            onDecision("ABORT_LATERAL")
            hold(EngineStatus.REJECT_POSE)
            abortCycle()
            state = State.SEARCH
            topHoldMs = 0L
        }
    }

    private fun held(sy: Float) {
        baselineY += 0.06f * (sy - baselineY)
        armedLeftTopAt = 0L
    }

    private fun finalizeCandidate(now: Long) {
        val tempo = now - descentStartAt
        val dwell = if (tLeftBottom > 0 && tReachedBottom > 0) tLeftBottom - tReachedBottom else 0L

        val depthMarginal = maxDrop < depthFloor + reviewBand || trough > thetaDepthEff - 6.0
        val formBad = lineAtDeepest > lineBottom + 0.06f
        val formMarginal = lineAtDeepest > lineBottom
        val tempoBad = tempo < MIN_REP_MS || tempo > MAX_REP_MS || dwell < BOTTOM_MIN_DWELL_MS
        val visMarginal = minVis < VIS_MARGINAL
        val depthInsane = maxDrop > 1.35f   // stood-up excursion — silent, honest

        when {
            depthInsane -> { onDecision("EXCURSION"); endAttempt() }
            tempoBad -> {
                cheat += 0.10
                hold(EngineStatus.REJECT_TEMPO)
                onDecision("REJECT_TEMPO")
                endAttempt()
            }
            maxDrop < depthFloor - reviewBand || trough > thetaDepthEff + 6.0 -> {
                cheat += 0.10
                hold(EngineStatus.REJECT_DEPTH)
                onDecision("REJECT_DEPTH")
                endAttempt()
            }
            formBad -> {
                cheat += 0.15
                hold(EngineStatus.REJECT_FORM)
                onDecision("REJECT_FORM")
                endAttempt()
            }
            depthMarginal || formMarginal || visMarginal || cheat >= CHEAT_LIMIT - 0.05 -> {
                state = State.REVIEW
                reviewDeadline = now + REVIEW_MS
                reviewHoldMs = 0L
                reviewLastCleanAt = now
                reviewReasonIsCheat = cheat >= CHEAT_LIMIT - 0.05
                hold(EngineStatus.VERIFYING)
            }
            else -> {
                onDecision("COMMIT")
                commit(now)
            }
        }
    }

    private fun commit(now: Long) {
        if (now - lastRepAt < REP_COOLDOWN_MS) { endAttempt(); return }
        lastRepAt = now
        reps += 1
        cheat = (cheat - 0.05).coerceAtLeast(0.0)
        // ROM learns ONLY from witnessed-valid bottoms — contamination can never
        // raise the bar beyond a rep we actually counted
        romEst += 0.35 * ((thetaTop - trough) - romEst)
        onRep(
            reps,
            PoseRepCounter.RepQuality(
                bottomDeg = trough,
                topDeg = thetaEma ?: thetaTop,
                tempoMs = now - descentStartAt,
                depthScore = ((thetaTop - trough) / (thetaTop - thetaDepthEff)).coerceIn(0.0, 1.0).toFloat(),
                symmetry = lastSym,
                cheatScore = cheat.toFloat(),
                engineVersion = engineVersion,
                lineDev = lineAtDeepest,
                shoulderDropTorsos = maxDrop,
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

    // ── features from canonical landmarks ────────────────────────────────────
    private class Feat(
        val theta: Double, val thetaL: Double, val thetaR: Double,   // −2.0 = arm not witnessed
        val x: Float, val y: Float, val hipY: Float, val torsoNow: Float,
        val lineDev: Float, val vis: Float, val sym: Float,
        val wristX: Float, val wristY: Float, val wristSeen: Boolean,
        val wristFixL: Float, val wristFixR: Float,
    )

    /** EMA feed without status side effects (FROZEN recovery). */
    private fun feedEma(fr: LandmarkFrame) {
        val f = features(fr) ?: return
        prevThetaEma = thetaEma
        thetaEma = thetaEma?.let { it + EMA_TH * (f.theta - it) } ?: f.theta
        shoulderY = shoulderY?.let { it + EMA_Y * (f.y - it) } ?: f.y
        shoulderX = shoulderX?.let { it + EMA_Y * (f.x - it) } ?: f.x
    }

    private fun topCondRaw(fr: LandmarkFrame): Boolean {
        val f = features(fr) ?: return false
        return f.theta >= thetaTop && f.lineDev <= lineReady
    }

    private fun lm(fr: LandmarkFrame, j: Int) =
        if (fr.score[j] >= LIKELIHOOD) Triple(fr.x(j), fr.y(j), fr.score[j]) else null

    private fun features(fr: LandmarkFrame?): Feat? {
        fr ?: return null
        onLandmarks((0 until 33).map { (fr.x(it) / imgW) to (fr.y(it) / imgH) })

        val lSh = lm(fr, LandmarkFrame.L_SHOULDER) ?: return null
        val rSh = lm(fr, LandmarkFrame.R_SHOULDER) ?: return null
        val lHip = lm(fr, LandmarkFrame.L_HIP) ?: return null
        val rHip = lm(fr, LandmarkFrame.R_HIP) ?: return null
        val lAnk = lm(fr, LandmarkFrame.L_ANKLE) ?: return null
        val rAnk = lm(fr, LandmarkFrame.R_ANKLE) ?: return null

        val shX = (lSh.first + rSh.first) / 2f
        val shY = (lSh.second + rSh.second) / 2f
        val hipX = (lHip.first + rHip.first) / 2f
        val hipY = (lHip.second + rHip.second) / 2f
        val ankX = (lAnk.first + rAnk.first) / 2f
        val ankY = (lAnk.second + rAnk.second) / 2f

        val lEl = lm(fr, LandmarkFrame.L_ELBOW); val lWr = lm(fr, LandmarkFrame.L_WRIST)
        val rEl = lm(fr, LandmarkFrame.R_ELBOW); val rWr = lm(fr, LandmarkFrame.R_WRIST)
        val thetaL = if (lEl != null && lWr != null) angleBetween(lSh, lEl, lWr) else null
        val thetaR = if (rEl != null && rWr != null) angleBetween(rSh, rEl, rWr) else null
        val visL = if (lEl != null && lWr != null) minOf(lSh.third, lEl.third, lWr.third) else 0f
        val visR = if (rEl != null && rWr != null) minOf(rSh.third, rEl.third, rWr.third) else 0f
        val theta = when {
            thetaL != null && thetaR != null -> if (visL >= visR) thetaL else thetaR
            thetaL != null -> thetaL
            thetaR != null -> thetaR
            else -> return null
        }
        val sym = if (thetaL != null && thetaR != null)
            (1.0 - abs(thetaL - thetaR) / 60.0).coerceIn(0.0, 1.0).toFloat() else 0.7f

        val dx = ankX - shX; val dy = ankY - shY
        val len = sqrt(max(dx * dx + dy * dy, 1e-6f))
        val perp = abs(dx * (hipY - shY) - (hipX - shX) * dy) / len
        val torso = sqrt((shX - hipX) * (shX - hipX) + (shY - hipY) * (shY - hipY)).coerceAtLeast(1f)
        val lineDev = perp / torso

        val vis = minOf(lSh.third, rSh.third, lHip.third, rHip.third, lAnk.third, rAnk.third)
        val wr = lWr ?: rWr
        // wrist fixation channels for the §3 window (refs captured at ARMED)
        val fixL = if (lWr != null && wristRefSet)
            sqrt((lWr.first - wristRefX) * (lWr.first - wristRefX) + (lWr.second - wristRefY) * (lWr.second - wristRefY)) / profile.torsoLenPx else -2f
        val fixR = if (rWr != null && wristRefSet)
            sqrt((rWr.first - wristRefX) * (rWr.first - wristRefX) + (rWr.second - wristRefY) * (rWr.second - wristRefY)) / profile.torsoLenPx else -2f
        return Feat(
            theta = theta, thetaL = thetaL ?: -2.0, thetaR = thetaR ?: -2.0,
            x = shX, y = shY, hipY = hipY, torsoNow = torso,
            lineDev = lineDev, vis = vis, sym = sym,
            wristX = wr?.first ?: 0f, wristY = wr?.second ?: 0f, wristSeen = wr != null,
            wristFixL = fixL, wristFixR = fixR,
        )
    }

    private fun wristPlanted(f: Feat): Boolean {
        if (!f.wristSeen || !wristRefSet) return true
        val dxw = f.wristX - wristRefX; val dyw = f.wristY - wristRefY
        return sqrt(dxw * dxw + dyw * dyw) <= 0.10f * profile.torsoLenPx
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

    private fun angleBetween(
        a: Triple<Float, Float, Float>,
        b: Triple<Float, Float, Float>,
        c: Triple<Float, Float, Float>,
    ): Double {
        val bax = a.first - b.first; val bay = a.second - b.second
        val bcx = c.first - b.first; val bcy = c.second - b.second
        val dot = bax * bcx + bay * bcy
        val m1 = sqrt(max(bax * bax + bay * bay, 1e-6f))
        val m2 = sqrt(max(bcx * bcx + bcy * bcy, 1e-6f))
        return Math.toDegrees(acos((dot / (m1 * m2)).toDouble().coerceIn(-1.0, 1.0)))
    }
}
