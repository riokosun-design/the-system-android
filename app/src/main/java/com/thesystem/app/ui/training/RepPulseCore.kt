package com.thesystem.app.ui.training

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * REP PULSE CORE v5 — the geometry-agnostic rep counter.
 *
 * WHY IT EXISTS (field report, 4th recurrence): v2→v4 + gate all assumed ONE
 * geometry (phone propped, side view, full body incl. ankles) and any missing
 * landmark family stranded counting forever ("doesn't even track ONE rep").
 * Real hunters put the phone FLAT ON THE FLOOR under their chest (front cam),
 * or film squats from wherever fits a small room. No single joint graph
 * survives all of that.
 *
 * v5 inverts the contract: frame the person however the room allows; the
 * engine evaluates EVERY motion channel that the visible joints can honestly
 * provide this frame (elbow angle, shoulder span scale, shoulder/hip vertical
 * travel, nose→shoulder depth), measures a short warmup window, picks the
 * strongest-living channel, and counts full cycles against ADAPTIVE bands
 * learned from THAT channel's own range. If nothing measurable is moving, it
 * says exactly what it sees and needs — never a silent zero.
 *
 * Purity law: this file is android-free — unit/sim harnesses drive
 * [advance] with synthetic joint arrays and synthetic clocks.
 *
 * Anti-fraud for STANDARD quests stays honest-light: full-cycle law, half-rep
 * rejection, tempo bounds, relocation slew guard, pose-gap abort. STRICT adds
 * angle-channel whitelisting + tighter depth for ranked surfaces.
 */
class RepPulseCore(
    val exercise: Exercise,
    val strictness: Strictness = Strictness.STANDARD,
    private val onRep: (Int, Quality) -> Unit,
    private val onPhase: (Phase, Float) -> Unit = { _, _ -> },
    private val onStatus: (Status) -> Unit = {},
    private val onTelemetry: (Telemetry) -> Unit = {},
) {

    enum class Exercise { PUSHUP, SQUAT }
    enum class Strictness { STANDARD, STRICT }
    enum class Phase { SEARCH, TOP, DESCENDING, BOTTOM, ASCENDING }
    enum class Status {
        SEARCHING, READY, DESCENDING, ASCENDING, VERIFYING,
        REJECT_DEPTH, REJECT_FORM, REJECT_TEMPO, REJECT_POSE,
        CAMERA_MOVED, SETTLE, DISPUTED,
    }

    data class Quality(
        val troughValue: Float,      // degrees for angle channels, else normalized
        val troughKind: String,      // channel that judged the rep
        val tempoMs: Long,
        val depthScore: Float,       // 0..1 of the learned band
        val engineVersion: String = "v5.0",
    )

    data class Telemetry(
        val personSeen: Boolean,
        val bestRangeFrac: Float,    // 0..1 — how close to a countable signal
        val activeChannel: String,
        val warmupLeftMs: Long,
        val reps: Int,
        val phase: Phase,
        val depthFrac: Float,        // live 0..1 toward the bottom band
        val hint: String,
    )

    var reps: Int = 0
        private set

    /** Sim/bench-only breadcrumb; stays null in production. */
    var probe: ((String) -> Unit)? = null

    // canonical joint slots — mirror of estimate.LandmarkFrame (cross-checked)
    companion object {
        const val J_NOSE = 0
        const val J_L_SH = 11
        const val J_R_SH = 12
        const val J_L_EL = 13
        const val J_R_EL = 14
        const val J_L_WR = 15
        const val J_R_WR = 16
        const val J_L_HIP = 23
        const val J_R_HIP = 24
        const val J_L_KNEE = 25
        const val J_R_KNEE = 26
        const val J_L_ANK = 27
        const val J_R_ANK = 28

        const val AVAIL_FLOOR = 0.36f          // per-sample usability
        const val WIN_MS = 6000L               // rolling sample window
        const val WARMUP_MS = 2400L            // initial learning window (STANDARD)
        const val EVAL_MS = 300L               // channel re-scoring cadence
        const val MIN_REP_MS = 450L
        const val MAX_REP_MS = 6000L
        const val REP_COOLDOWN_MS = 380L
        const val POSE_GAP_ABORT_MS = 600L
        const val TOP_HOLD_MS = 250L
        const val SWITCH_HOLD_MS = 400L        // primary switch stability guard
        const val SLEW_CONFIRM_FRAMES = 4      // consecutive big jumps = relocation, not glitch
        const val CHAN_STALE_MS = 500L         // a channel with no fresh sample cannot rank
    }

    /** Candidate channel. Descent is ALWAYS the positive direction after polarity fix. */
    private enum class Chan(val label: String, val angle: Boolean, val rangeFloorUnits: Float) {
        ELBOW("ELBOW-ANGLE", true, 22f),         // deg
        KNEE("KNEE-ANGLE", true, 30f),           // deg
        SPAN("SHOULDER-SPAN", false, 0.09f),     // fraction of its midline
        HEAD_DEPTH("HEAD-DEPTH", false, 0.07f),  // |nose−shoulder|, fraction of midline
        SHOULDER_Y("SHOULDER-TRAVEL", false, 0.045f), // fraction of imgH
        HIP_Y("HIP-TRAVEL", false, 0.05f),       // fraction of imgH
    }

    private class Window {
        val t = ArrayList<Long>(256)
        val v = ArrayList<Float>(256)
        fun add(now: Long, value: Float) {
            t.add(now); v.add(value)
            while (t.isNotEmpty() && now - t.first() > WIN_MS) { t.removeAt(0); v.removeAt(0) }
            if (t.size > 300) { t.removeAt(0); v.removeAt(0) }
        }
        fun size() = t.size
        fun spanMs() = if (t.size < 2) 0L else t.last() - t.first()
        fun lastT(): Long = if (t.isEmpty()) -1L else t.last()
        fun values(): List<Float> = v
        fun clear() { t.clear(); v.clear() }
    }

    private val win = HashMap<Chan, Window>().also { m -> Chan.entries.forEach { m[it] = Window() } }
    private var primary: Chan? = null
    private var primarySince = 0L
    private var candidateSwitch: Chan? = null
    private var candidateSwitchSince = 0L
    private var lastEvalAt = 0L
    private var warmupStartAt = -1L
    private var lastFrameAt = 0L
    private var noPoseMs = 0L

    // adaptive bands on the primary signal (descent-positive)
    private var bandLo = 0f      // "top" side of the signal
    private var bandMid = 0f
    private var bandRange = 0f
    private var bandsValid = false

    // state machine
    private enum class St { SEARCH, ARMED, DESCENDING, ASCENDING }
    private var st = St.SEARCH
    private var topHoldMs = 0L
    private var attemptStartAt = 0L
    private var attemptStartSig = 0f
    private var trough = 0f
    private var troughAt = 0L
    private var riseStreak = 0
    private var fallStreak = 0
    private var prevSig = 0f
    private var prevDelta = 0f
    private var slewStreak = 0
    private var slewLockUntil = 0L
    private var apprenticeUntilMs = Long.MAX_VALUE   // fast re-learn until first honest rep
    private var lastRepAt = 0L
    private var bounceCount = 0

    private var reported: Status = Status.SEARCHING
    private var phaseRep: Phase = Phase.SEARCH
    private var depthFracRep = 0f

    fun reset() {
        reps = 0; st = St.SEARCH
        win.values.forEach { it.clear() }
        primary = null; primarySince = 0; candidateSwitch = null
        bandsValid = false
        warmupStartAt = -1; lastRepAt = 0; riseStreak = 0; fallStreak = 0
        slewStreak = 0; slewLockUntil = 0
        report(Status.SEARCHING)
        emitPhase()
    }

    // ═══ frame ingestion ═══════════════════════════════════════════════════
    /**
     * @param xs/ys/sc canonical 33-slot joint arrays (slots 0..32), pixels +
     * @param camStable IMU witness verdict for this frame
     */
    fun advance(
        xs: FloatArray?, ys: FloatArray?, sc: FloatArray?,
        imgW: Float, imgH: Float,
        nowMs: Long, camStable: Boolean,
    ) {
        val dt = if (lastFrameAt == 0L) 33L else (nowMs - lastFrameAt).coerceIn(1L, 250L)
        lastFrameAt = nowMs

        // ── person probe: shoulders OR shoulder+nose are the minimum witness ──
        fun sh(): Boolean = sc != null && (sc[J_L_SH] >= AVAIL_FLOOR && sc[J_R_SH] >= AVAIL_FLOOR)
        val person = xs != null && ys != null && sc != null &&
            (sh() || (sc[J_NOSE] >= AVAIL_FLOOR && (sc[J_L_SH] >= AVAIL_FLOOR || sc[J_R_SH] >= AVAIL_FLOOR)))

        if (!person) {
            noPoseMs += dt
            if ((st == St.DESCENDING || st == St.ASCENDING) && noPoseMs > POSE_GAP_ABORT_MS) {
                report(Status.REJECT_POSE)
                abortAttempt()
            }
            if (noPoseMs > 500 && reported != Status.REJECT_POSE) report(Status.SEARCHING)
            emitTelemetry(personSeen = false)
            emitPhase()
            return
        }
        noPoseMs = 0L

        if (warmupStartAt < 0) warmupStartAt = nowMs

        // ── IMU veto: freeze the machine (no punishment, no ghost motion) ────
        if (!camStable) {
            if (st == St.DESCENDING || st == St.ASCENDING) {
                // a bump mid-rep voids THE ATTEMPT ONLY — never the session
                abortAttempt()
            }
            report(Status.CAMERA_MOVED)
            topHoldMs = 0L
            emitTelemetry(personSeen = true)
            return
        }
        xs!!; ys!!; sc!!

        // ── channel samples (descent-positive semantics per channel) ─────────
        val shL = Triple(xs[J_L_SH], ys[J_L_SH], sc[J_L_SH])
        val shR = Triple(xs[J_R_SH], ys[J_R_SH], sc[J_R_SH])
        val shMidX = (shL.first + shR.first) / 2f
        val shMidY = (shL.second + shR.second) / 2f
        val spanPx = abs(shL.first - shR.first)

        fun elbowOf(shI: Int, elI: Int, wrI: Int): Float? {
            if (!(sc[elI] >= AVAIL_FLOOR && sc[wrI] >= AVAIL_FLOOR && sc[shI] >= 0.30f)) return null
            return angle(xs[shI], ys[shI], xs[elI], ys[elI], xs[wrI], ys[wrI])
        }
        val elL = elbowOf(J_L_SH, J_L_EL, J_L_WR)
        val elR = elbowOf(J_R_SH, J_R_EL, J_R_WR)
        val elbow = best(elL, av(elL, scAngle(sc, J_L_SH, J_L_EL, J_L_WR)), elR, av(elR, scAngle(sc, J_R_SH, J_R_EL, J_R_WR)))

        fun kneeOf(hipI: Int, knI: Int, anI: Int): Float? {
            if (!(sc[hipI] >= AVAIL_FLOOR && sc[knI] >= AVAIL_FLOOR && sc[anI] >= AVAIL_FLOOR)) return null
            return angle(xs[hipI], ys[hipI], xs[knI], ys[knI], xs[anI], ys[anI])
        }
        val knL = kneeOf(J_L_HIP, J_L_KNEE, J_L_ANK)
        val knR = kneeOf(J_R_HIP, J_R_KNEE, J_R_ANK)
        val knee = best(knL, av(knL, scAngle(sc, J_L_HIP, J_L_KNEE, J_L_ANK)), knR, av(knR, scAngle(sc, J_R_HIP, J_R_KNEE, J_R_ANK)))

        val noseOk = sc[J_NOSE] >= AVAIL_FLOOR
        val headDepthPx = if (noseOk && sh()) dist(xs[J_NOSE], ys[J_NOSE], shMidX, shMidY) else null
        val hipOk = sc[J_L_HIP] >= AVAIL_FLOOR && sc[J_R_HIP] >= AVAIL_FLOOR

        // add samples (availability-weighted)
        if (elbow != null) win[Chan.ELBOW]!!.add(nowMs, -elbow)              // descent = angle shrinks
        if (knee != null) win[Chan.KNEE]!!.add(nowMs, -knee)
        if (sh()) win[Chan.SPAN]!!.add(nowMs, spanPx)                        // grows toward camera on descent
        if (sh()) win[Chan.SHOULDER_Y]!!.add(nowMs, shMidY / imgH)           // body drops ⇒ y grows
        if (headDepthPx != null) win[Chan.HEAD_DEPTH]!!.add(nowMs, headDepthPx)
        if (hipOk) win[Chan.HIP_Y]!!.add(nowMs, (ys[J_L_HIP] + ys[J_R_HIP]) / 2f / imgH)

        // ── evaluate channels + primary selection (cadenced) ─────────────────
        if (nowMs - lastEvalAt >= EVAL_MS) {
            lastEvalAt = nowMs
            evaluate(nowMs)
        }

        val sig = primarySignal()
        if (sig == null || !bandsValid) {
            if (reported != Status.SEARCHING) report(Status.SEARCHING)
            emitTelemetry(personSeen = true)
            emitPhase()
            return
        }

        // svm: slew guard v3 — only ever arms in TRUSTED mode (after the first
        // honest rep). During apprenticeship the bands are still learning, so a
        // "big jump" there is usually just the real range revealing itself —
        // per the field autopsy, noise-vs-toy-band false positives froze prevSig
        // and confirmed themselves: the classic self-fulfilling slew cascade.
        if (nowMs < slewLockUntil) {
            prevSig = sig          // keep tracking during lock: no re-fire on expiry
            prevDelta = 0f
            emitTelemetry(true); emitPhase()
            return
        }
        if (reps > 0 && nowMs >= apprenticeUntilMs && bandRange > 0) {
            // dt-scaled: a hitch frame carries proportionally more honest motion
            val slewThresh = 0.8f * bandRange * (dt.toFloat() / 83f).coerceIn(1f, 2.5f)
            if (abs(sig - prevSig) > slewThresh) {
                slewStreak++
                probe?.invoke("t=$nowMs SLEW x$slewStreak sig=$sig prev=$prevSig r=$bandRange thr=$slewThresh")
                if (slewStreak >= SLEW_CONFIRM_FRAMES) {
                    // genuine relocation (phone/user moved) — void THE ATTEMPT
                    // ONLY; with no attempt in flight there is nothing to
                    // protect, so skip the lock entirely and let the machine
                    // re-arm the moment the scene settles (field autopsy: the
                    // blind-lock used to burn the NEXT rep too — the
                    // "blackout shockwave")
                    slewStreak = 0
                    val hadAttempt = st == St.DESCENDING || st == St.ASCENDING
                    if (hadAttempt) {
                        abortAttempt()
                        slewLockUntil = nowMs + 400
                    }
                    buildBands(nowMs)
                    reseedSignal()
                    apprenticeUntilMs = nowMs + 2000
                    report(Status.SETTLE)
                }
                // spike frames are dropped either way — never poison prevSig,
                // never let the state machine read a teleport as muscle
                emitTelemetry(personSeen = true)
                emitPhase()
                return
            }
            slewStreak = 0
        }
        // per-frame motion delta MUST be captured before prevSig advances —
        // the first shipped build overwrote prevSig before the state machine
        // read it, so the armed state never saw a single "falling" frame and
        // counting sat at zero forever (the exact user-visible bug).
        val sigDelta = sig - prevSig
        prevDelta = sigDelta
        prevSig = sig

        // Gate law v5.1 — anchor EVERYTHING to bandLo (q15, the top), never the
        // median. Field autopsy: slow grind reps skew the window sample mass
        // toward the bottom, dragging the median (and with it the mid-anchored
        // startDrop) so deep that clean reps looked shallow — honest athletes
        // ate REJECT_DEPTH for simply resting between sets in a slow tempo.
        val hiGate = bandLo + 0.85f * bandRange      // telemetry "deep enough" reference
        val loGate = bandLo + 0.16f * bandRange      // "back to top" line
        val startDrop = bandLo + 0.25f * bandRange   // leaving-top line (attempt arm)

        // state machine
        when (st) {
            St.SEARCH -> {
                if (sig <= loGate) {
                    topHoldMs += dt
                    if (topHoldMs >= TOP_HOLD_MS) {
                        st = St.ARMED
                        riseStreak = 0; fallStreak = 0
                        report(Status.READY)
                    }
                } else {
                    probe?.invoke("t=$nowMs SEARCH-WAIT sig=$sig loGate=$loGate lo=$bandLo r=$bandRange topHold=$topHoldMs")
                    if (reported != Status.READY) report(Status.SEARCHING)
                }
            }
            St.ARMED -> {
                if (sigDelta > 0f) riseStreak++ else if (sigDelta < -0.02f * bandRange) riseStreak = 0
                if (sig >= startDrop && riseStreak >= 1) {
                    st = St.DESCENDING
                    attemptStartAt = nowMs
                    attemptStartSig = sig
                    trough = sig; troughAt = nowMs
                    bounceCount = 0
                    report(Status.DESCENDING)
                } else if (sig <= loGate) {
                    topHoldMs = 0
                    report(Status.READY)
                }
            }
            St.DESCENDING -> {
                if (sig > trough) { trough = sig; troughAt = nowMs; riseStreak = 0 }
                if (sig < trough - 0.06f * bandRange) fallStreak++ else fallStreak = 0
                val bottomDwelled = nowMs - troughAt in 90..1800
                if (fallStreak >= 2 && bottomDwelled) {
                    st = St.ASCENDING
                    report(Status.ASCENDING)
                }
            }
            St.ASCENDING -> {
                if (sig > trough + 0.12f * bandRange) {
                    bounceCount++
                    if (bounceCount > 2) {
                        report(Status.REJECT_TEMPO)
                        abortAttempt()
                    } else {
                        st = St.DESCENDING
                        report(Status.DESCENDING)
                    }
                } else if (sig <= loGate) {
                    val tempo = nowMs - attemptStartAt
                    val depth = (trough - attemptStartSig).coerceAtLeast(0f)
                    val need = (if (strictness == Strictness.STRICT) 0.55f else 0.40f) * bandRange
                    when {
                        tempo < MIN_REP_MS || tempo > MAX_REP_MS -> { report(Status.REJECT_TEMPO); abortAttempt() }
                        depth < need -> { report(Status.REJECT_DEPTH); abortAttempt() }
                        nowMs - lastRepAt < REP_COOLDOWN_MS -> abortAttempt()
                        else -> commit(nowMs, tempo, depth, bandRange)
                    }
                }
            }
        }

        // adaptive band refresh (only OUTSIDE attempts — real motion teaches)
        if (st == St.SEARCH || st == St.ARMED) refreshBands()

        emitTelemetry(personSeen = true, sig = sig, loGate = loGate, hiGate = hiGate)
        emitPhase()
    }

    private fun prevSigUp(nowMs: Long): Float = prevSig   // readability at ARMED

    private fun commit(nowMs: Long, tempo: Long, depth: Float, range: Float) {
        lastRepAt = nowMs
        reps += 1
        apprenticeUntilMs = 0L             // first honest rep ends the apprenticeship
        val ch = primary ?: Chan.SPAN
        val displayTrough = if (ch.angle) -trough else trough
        onRep(reps, Quality(displayTrough, ch.label, tempo, (depth / range).coerceIn(0f, 1.2f)))
        abortAttempt(rearmTop = true)
        report(Status.READY)
    }

    private fun abortAttempt(rearmTop: Boolean = false) {
        st = St.SEARCH
        topHoldMs = if (rearmTop) TOP_HOLD_MS / 2 else 0
        riseStreak = 0; fallStreak = 0; bounceCount = 0
    }

    // ═══ channel evaluation ════════════════════════════════════════════════
    private data class Rank(val chan: Chan, val score: Float, val rangeUnitsOfFloor: Float, val range: Float)

    private fun evaluate(nowMs: Long) {
        val ranks = ArrayList<Rank>(6)
        var bestFrac = 0f
        for (c in Chan.entries) {
            if (strictness == Strictness.STRICT) {
                // ranked law: push-ups judged by arms, squats by the knee chain
                if (exercise == Exercise.PUSHUP && c != Chan.ELBOW) continue
                if (exercise == Exercise.SQUAT && c != Chan.KNEE) continue
            } else {
                if (exercise == Exercise.PUSHUP && c == Chan.KNEE) continue
            }
            val w = win[c]!!
            // a channel whose last sample is stale is DEAD, not voting — the
            // field autopsy: a killed elbow chain kept ranking on its stale
            // 6s window and froze counting for two full reps
            if (nowMs - w.lastT() > CHAN_STALE_MS) continue
            if (w.spanMs() < 700 || w.size() < 8) continue
            val vals = w.values().sorted()
            val q15 = percentile(vals, 0.15)
            val q85 = percentile(vals, 0.85)
            val med = percentile(vals, 0.50)
            // coherence law — a window brewed across TWO geometries (user moved
            // mid-window, or a feeder channel's skeleton changed shape) shows
            // a dead zone: a big internal gap nobody traverses. That gap is
            // not range-of-motion, so it cannot be allowed to inflate the
            // ranking (field autopsy: a bimodal span window won a mid-set
            // election on 113px of "range" that was really 89px of empty air,
            // and three honest reps died in the garbage bands)
            val i15 = ((vals.size - 1) * 0.15).toInt().coerceIn(0, vals.size - 1)
            val i85 = ((vals.size - 1) * 0.85).toInt().coerceIn(0, vals.size - 1)
            var maxGap = 0f
            for (k in i15 + 1..i85) {
                val g = vals[k] - vals[k - 1]
                if (g > maxGap) maxGap = g
            }
            val range = (q85 - q15 - maxGap).coerceAtLeast(0f)
            if (!c.angle && abs(med) < 1e-3f) continue
            val rangeUnits = if (c.angle) range / c.rangeFloorUnits
            else range / (abs(med) * c.rangeFloorUnits)
            if (rangeUnits > bestFrac) bestFrac = rangeUnits
            if (rangeUnits < 1f) continue
            val availQ = (w.size().toFloat() / (w.spanMs() / EVAL_MS).coerceAtLeast(1)).coerceIn(0f, 1f)
            val bias = when (c) {
                Chan.ELBOW, Chan.KNEE -> 1.30f
                Chan.SPAN -> 1.10f
                Chan.HEAD_DEPTH -> 1.05f
                else -> 1.0f
            }
            ranks += Rank(c, rangeUnits * (0.55f + 0.45f * availQ) * bias, rangeUnits, range)
        }
        telemetryBestFrac = bestFrac.coerceIn(0f, 1f)

        if (ranks.isEmpty()) {
            // nothing countable yet: keep the search live, never fake a lock
            if (primary != null && nowMs - primarySince > 2600) { primary = null; bandsValid = false }
            return
        }
        ranks.sortByDescending { it.score }
        val top = ranks.first()
        val cur = primary
        probe?.invoke("t=$nowMs EVAL cur=${cur?.label} top=${top.chan.label} score=${top.score} ranks=${ranks.joinToString { "${it.chan.label}:${it.score}" }}")
        if (cur == null || cur !in ranks.map { it.chan }) {
            primary = top.chan; primarySince = nowMs
            candidateSwitch = null
            buildBands(nowMs)
            reseedSignal()
        } else if (top.chan != cur) {
            // switch only with a sustained lead — no mid-set oscillation
            val curScore = ranks.firstOrNull { it.chan == cur }?.score ?: 0f
            if (top.score > curScore * 1.15f) {
                if (candidateSwitch == top.chan) {
                    if (nowMs - candidateSwitchSince > SWITCH_HOLD_MS) {
                        primary = top.chan; primarySince = nowMs
                        candidateSwitch = null
                        buildBands(nowMs)
                        reseedSignal()
                        if (st == St.DESCENDING || st == St.ASCENDING) abortAttempt()
                    }
                } else {
                    candidateSwitch = top.chan; candidateSwitchSince = nowMs
                }
            } else candidateSwitch = null
        } else candidateSwitch = null

        // apprenticeship: until the first honest rep (or right after a
        // relocation), the bands are a SKETCH — recompute them fully every
        // eval instead of crawling the 0.02 EMA. This is what stops the
        // "missed the first two reps" disease: the machine is born mid-motion
        // (reps start the moment the camera sees you), and percentile bands
        // over the growing window converge inside one cycle.
        if ((reps == 0 || nowMs < apprenticeUntilMs) && primary != null) buildBands(nowMs)
    }

    /** Fresh primary ⇒ old prevSig belongs to a dead scale; reseed or the slew
     *  guard reads the switch itself as a teleport and burns another rep. */
    private fun reseedSignal() {
        prevSig = primarySignal() ?: 0f
        prevDelta = 0f
        slewStreak = 0
        riseStreak = 0; fallStreak = 0
        probe?.invoke("t=${lastFrameAt} RESEED prevSig=$prevSig")
    }

    private fun buildBands(nowMs: Long) {
        val c = primary ?: return
        val w = win[c]!!
        val vals = w.values().sorted()
        if (vals.size < 6) { bandsValid = false; return }
        val q15 = percentile(vals, 0.15)
        val q85 = percentile(vals, 0.85)
        bandLo = q15
        bandMid = percentile(vals, 0.5)
        bandRange = (q85 - q15).coerceAtLeast(1e-3f)
        bandsValid = true
        probe?.invoke("t=$nowMs BANDS ${c.label} lo=$bandLo mid=$bandMid r=$bandRange n=${vals.size}")
    }

    /** Slow band drift while idle-at-top — ROM waves across sets must not rot thresholds. */
    private fun refreshBands() {
        val c = primary ?: return
        val w = win[c]!!
        val vals = w.values().sorted()
        if (vals.size < 12) return
        val q15 = percentile(vals, 0.15)
        val q85 = percentile(vals, 0.85)
        bandLo += 0.02f * (q15 - bandLo)
        val newMid = percentile(vals, 0.5)
        bandMid += 0.02f * (newMid - bandMid)
        val newRange = (q85 - q15).coerceAtLeast(1e-3f)
        bandRange += 0.02f * (newRange - bandRange)
        bandRange = bandRange.coerceAtLeast(minRangeFor(c))
    }

    private fun minRangeFor(c: Chan): Float =
        if (c.angle) c.rangeFloorUnits else bandRange  // px channels floor lives in evaluation

    private fun primarySignal(): Float? {
        val c = primary ?: return null
        val w = win[c]!!
        return w.values().lastOrNull()
    }

    // ═══ reports ═══════════════════════════════════════════════════════════
    private var telemetryBestFrac = 0f

    private fun emitTelemetry(personSeen: Boolean, sig: Float? = null, loGate: Float = 0f, hiGate: Float = 0f) {
        val depthFrac = if (sig != null && bandsValid && bandRange > 0) {
            ((sig - bandLo) / (hiGate - bandLo).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
        } else 0f
        depthFracRep = depthFrac
        val warmupLeft = if (bandsValid) 0L
        else ((warmupStartAt + WARMUP_MS - lastFrameAt).coerceAtLeast(0L))
        val hint = when {
            !personSeen -> "NO BODY SIGNAL — STEP INTO FRAME"
            primary == null && telemetryBestFrac < 0.35f -> "I CAN SEE YOU — MOVE THROUGH ONE FULL REP SO I CAN LEARN YOUR RANGE"
            primary == null -> "ALMOST THERE — KEEP THE MOTION GOING, FULL RANGE"
            st == St.SEARCH -> "START AT THE TOP — ARMS LOCKED / STAND TALL"
            st == St.ARMED -> "ARMED — CONTROLLED REPS"
            else -> ""
        }
        onTelemetry(
            Telemetry(
                personSeen = personSeen,
                bestRangeFrac = telemetryBestFrac,
                activeChannel = primary?.label ?: "NONE",
                warmupLeftMs = warmupLeft,
                reps = reps,
                phase = phaseRep,
                depthFrac = depthFrac,
                hint = hint,
            ),
        )
    }

    private fun report(s: Status) {
        if (s != reported) { reported = s; onStatus(s) }
    }

    private fun emitPhase() {
        val ph = when (st) {
            St.SEARCH -> Phase.SEARCH
            St.ARMED -> Phase.TOP
            St.DESCENDING -> {
                // honest bottom: settled into the trough 90+ ms and not still sinking
                val dwell = lastFrameAt - troughAt
                if (dwell >= 90 && bandsValid && prevSig >= trough - 0.03f * bandRange) Phase.BOTTOM else Phase.DESCENDING
            }
            St.ASCENDING -> Phase.ASCENDING
        }
        phaseRep = ph
        onPhase(ph, depthFracRep)
    }

    // ═══ geometry helpers ══════════════════════════════════════════════════
    private fun angle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
        val bax = ax - bx; val bay = ay - by
        val bcx = cx - bx; val bcy = cy - by
        val dot = bax * bcx + bay * bcy
        val m1 = sqrt(max(bax * bax + bay * bay, 1e-6f))
        val m2 = sqrt(max(bcx * bcx + bcy * bcy, 1e-6f))
        return Math.toDegrees(acos((dot / (m1 * m2)).toDouble().coerceIn(-1.0, 1.0))).toFloat()
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun av(v: Float?, confidence: Float): Float = if (v == null) -1f else confidence

    private fun scAngle(sc: FloatArray, a: Int, b: Int, c: Int): Float =
        (sc[a] + sc[b] + sc[c]) / 3f

    private fun best(vL: Float?, aL: Float, vR: Float?, aR: Float): Float? = when {
        vL != null && vR != null -> if (aL >= aR) vL else vR
        vL != null -> vL
        else -> vR
    }

    private fun percentile(sorted: List<Float>, q: Double): Float {
        if (sorted.isEmpty()) return 0f
        val i = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[i]
    }
}
