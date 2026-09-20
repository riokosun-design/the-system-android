package com.thesystem.app.ui.training

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * POSE REP COUNTER v2 — one reusable engine for PUSH-UP and SQUAT (P0 fix).
 *
 * Reported bugs fixed here, in order:
 *  1. DUPLICATE REPS — a rep only counts on a full TOP → DESCENDING → BOTTOM →
 *     ASCENDING → TOP cycle; being "down" twice without standing back up can
 *     never count twice.
 *  2. HALF REPS — the bottom must reach [depthDeg] before the ascending phase
 *     is accepted at all.
 *  3. JITTER / NOISE — EMA smoothing (0.45) + 2-frame phase confirmation +
 *     a 450 ms cooldown; a single noisy frame cannot move the state machine.
 *  4. NO VISIBILITY FEEDBACK — [status] reports POSE NOT DETECTED / MOVE BACK /
 *     FULL BODY NOT VISIBLE with the concrete reason, so the hunter knows how
 *     to fix the frame instead of staring at a frozen 0.
 *  5. FORM QUALITY — bottom/top angles, tempo and rep cadence are returned per
 *     rep; the same numbers feed the adaptive difficulty engine.
 *
 * Joints used (spec): PUSH-UP = shoulder·elbow·wrist + hip line warning;
 * SQUAT = hip·knee·ankle. Everything runs on-device; no frame is uploaded.
 */
class PoseRepCounter(
    val exercise: RepExercise,
    private val onRep: (Int, RepQuality) -> Unit,
    private val onPhase: (RepPhase, Float) -> Unit = { _, _ -> },
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
    private val onStatus: (PoseStatus) -> Unit = {},
) {

    enum class RepExercise { PUSHUP, SQUAT }

    /** Full cycle, in order — this is what kills duplicate/half reps. */
    enum class RepPhase { SEARCH, TOP, DESCENDING, BOTTOM, ASCENDING }

    enum class PoseStatus {
        WAITING, POSE_NOT_DETECTED, MOVE_BACK, FULL_BODY_NOT_VISIBLE, BAD_ANGLE, TRACKING
    }

    data class RepQuality(
        val bottomDeg: Double,
        val topDeg: Double,
        val tempoMs: Long,
        val depthScore: Float,   // 0..1 — how deep the rep actually went
        val symmetry: Float,     // 0..1 — left/right agreement
        /** engine v4+: decaying cheat accumulator at commit (−1 = not reported). */
        val cheatScore: Float = -1f,
        /** engine lineage that judged this rep ("v3-mlkit" lineage default). */
        val engineVersion: String = "v3-mlkit",
        /** body-line deviation at the deepest frame, torsos (−1 = not measured). */
        val lineDev: Float = -1f,
        /** max shoulder drop witnessed, torsos (−1 = not measured). */
        val shoulderDropTorsos: Float = -1f,
    )

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    var reps: Int = 0
        private set

    private var phase = RepPhase.SEARCH
    private var emaAngle: Double? = null
    private var candidate: RepPhase? = null
    private var candidateStreak = 0
    private var reachedBottom = false
    private var bottomAngle = 180.0
    private var descentStartAt = 0L
    private var lastRepAt = 0L
    private var lastSymmetry = 1f
    private var framesWithoutPose = 0
    private var imgW = 1f
    private var imgH = 1f
    private var lastStatus = PoseStatus.WAITING

    // ── FRONT-VIEW push-up machine v3 (phone flat on floor, camera up) ──────
    // Observable: APPARENT SHOULDER WIDTH (grows toward the lens on descent,
    // shrinks on lockout). The v2 design (session-wide ratchet min/max window
    // + fixed 66%/34% bands) is dead — one contaminated extreme (walking past
    // during setup, leaning in to check the screen mid-set) made a band
    // unreachable for the REST OF THE SESSION, exactly the field report
    // "counts 1 rep then sticks / shows UP but never counts". v3 instead:
    //
    //  BASELINE  — asymmetric leaky tracker of the lockout level: FAST toward
    //              the top side (span shrinking, k=0.35 — hunter repositioning
    //              away re-references immediately), SLOW toward the depth side
    //              (k=0.10 — converges across ~1s of settling, no calibration
    //              ritual, and descent frames can never poison a frozen trough).
    //  SLEW GUARD— one EMA frame jumping >19px = the hunter RELOCATED (lean-
    //              in/stand-up, phone bump): snap baseline, abort half-built
    //              cycles, block descent triggers for 500 ms while EMA settles.
    //  CYCLE     — every rep judged against ITS OWN frozen trough + peak:
    //              descent from baseline+dz, real depth vs [max(16px, 55% of
    //              learned ROM) .. 170% of learned ROM], >=4 frames / >=132ms
    //              down, >=3 frames spent actually deep (spike armour), then
    //              count only after climbing back >=62% of THAT rep's depth,
    //              once per arming, >=450ms since descent, >=450ms apart.
    //  VALVE     — a climb stalled 350ms while sitting at the baseline means
    //              the cycle armed before the top was learned (setup frames);
    //              abort cleanly, never count — the next descent self-heals.
    //  ROM       — learned ONLY from validated reps, so contamination can
    //              never raise the bar. Pose loss freezes the machine; a >1.5s
    //              gap re-anchors the baseline WITHOUT erasing the count.
    private var emaSpan: Float? = null
    private var baseline = 0f
    private var repTrough = 0f
    private var peak = 0f
    private var ascLo = 0f
    private var ascLoAt = 0L
    private var romEst: Float? = null
    private var armed = false
    private var bottomSpan = 0f
    private var bottomAt = 0L
    private var descFrames = 0
    private var sustain = 0
    private var prevV: Float? = null
    private var slewBlockUntil = 0L
    private var lastFrameAt = 0L

    private companion object {
        const val EMA_ALPHA = 0.45
        const val CONFIRM_FRAMES = 2
        const val REP_COOLDOWN_MS = 450L
        const val LIKELIHOOD_FLOOR = 0.35f
        const val MIN_REP_MS = 450L          // faster than this = camera noise, not a rep

        // v3 front-span constants (mirrors the 22/22 simulation harness)
        const val SPAN_EMA = 0.4f
        const val BASE_DOWN = 0.35f          // baseline pull toward the top side
        const val BASE_UP = 0.10f            // baseline pull toward the depth side
        const val SLEW_MAX_PX = 19f          // one EMA frame beyond this = relocation
        const val SLEW_SETTLE_MS = 500L      // no descent trigger while EMA settles
        const val MIN_RISE_FLOOR = 16f       // px — below this it's a nod / jitter
        const val MIN_RISE_FRAC = 0.55f      // of learned ROM
        const val ROM_BLEND = 0.35f
        const val ROM_OVER = 1.7f            // depth beyond 170% of ROM = lean-in
        const val DZ_FLOOR = 5f              // px hysteresis deadzone
        const val DZ_FRAC = 0.14f
        const val DZ_CAP = 8f
        const val COME_BACK = 0.62f          // climb fraction of own depth to count
        const val BOTTOM_HOLD_MS = 90L
        const val GAP_REANCHOR_MS = 1500L    // pose-loss recovery re-anchor
        const val MIN_DESC_FRAMES = 4        // >= ~132ms of actual descending
        const val MIN_DESC_MS = 132L
        const val MIN_SUSTAIN_FRAMES = 3     // frames spent deep — spike armour
        const val CLIMB_STALL_MS = 350L      // stale-trough recovery valve
    }

    // ── thresholds ───────────────────────────────────────────────────────────
    private val topDeg: Double get() = if (exercise == RepExercise.PUSHUP) 158.0 else 162.0
    private val descendDeg: Double get() = if (exercise == RepExercise.PUSHUP) 140.0 else 145.0
    private val depthDeg: Double get() = if (exercise == RepExercise.PUSHUP) 95.0 else 105.0

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        val media = imageProxy.image ?: run { imageProxy.close(); return }
        // Landmark coordinates live in the ROTATED image space — swap buffer
        // dims for 90/270 or every span/threshold/mesh normalization lies.
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
            .addOnSuccessListener { pose -> evaluate(pose) }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Reset for a new session (also used by the "reset counter" test case). */
    fun reset() {
        reps = 0
        phase = RepPhase.SEARCH
        emaAngle = null
        candidate = null
        candidateStreak = 0
        reachedBottom = false
        bottomAngle = 180.0
        descentStartAt = 0L
        lastRepAt = 0L
        framesWithoutPose = 0
        lastStatus = PoseStatus.WAITING
        // v3 front-span machine
        emaSpan = null
        baseline = 0f
        repTrough = 0f
        peak = 0f
        ascLo = 0f
        ascLoAt = 0L
        romEst = null
        armed = false
        bottomSpan = 0f
        bottomAt = 0L
        descFrames = 0
        sustain = 0
        prevV = null
        slewBlockUntil = 0L
        lastFrameAt = 0L
    }

    fun close() = detector.close()

    // ── the state machine ────────────────────────────────────────────────────
    private fun evaluate(pose: Pose) {
        val marks = pose.allPoseLandmarks
        onLandmarks(marks.map { (it.position.x / imgW) to (it.position.y / imgH) })

        // ── visibility diagnostics ───────────────────────────────────────────
        val visible = visibility(pose)
        when {
            visible == PoseStatus.POSE_NOT_DETECTED || visible == PoseStatus.FULL_BODY_NOT_VISIBLE -> {
                framesWithoutPose++
                if (framesWithoutPose >= 3) report(visible)
                return
            }
            visible == PoseStatus.MOVE_BACK -> {
                framesWithoutPose++
                if (framesWithoutPose >= 5) report(PoseStatus.MOVE_BACK)
                return
            }
            visible == PoseStatus.BAD_ANGLE -> {
                framesWithoutPose++
                if (framesWithoutPose >= 5) report(PoseStatus.BAD_ANGLE)
                return
            }
            else -> {
                framesWithoutPose = 0
                report(PoseStatus.TRACKING)
            }
        }

        // ── PUSH-UP = front-span cycle v3 (floor + front camera geometry) ────
        if (exercise == RepExercise.PUSHUP) {
            val lS = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
            val rS = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
            if (lS == null || rS == null ||
                lS.inFrameLikelihood < LIKELIHOOD_FLOOR || rS.inFrameLikelihood < LIKELIHOOD_FLOOR
            ) return
            val span = abs(lS.position.x - rS.position.x)
            val v = emaSpan?.let { it + SPAN_EMA * (span - it) } ?: span
            emaSpan = v
            stepPushSpan(v)
            return
        }

        // ── SQUAT = hip·knee·ankle angle cycle (back camera, side/full body) ─
        val joints = Triple(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE) to
            Triple(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
        val left = angleAt(pose, joints.first.first, joints.first.second, joints.first.third)
        val right = angleAt(pose, joints.second.first, joints.second.second, joints.second.third)
        val raw = when {
            left != null && right != null -> (left + right) / 2.0
            left != null -> left
            right != null -> right
            else -> return
        }
        lastSymmetry = if (left != null && right != null) {
            (1.0 - (abs(left - right) / 60.0)).coerceIn(0.0, 1.0).toFloat()
        } else 0.6f

        val smoothed = emaAngle?.let { it + EMA_ALPHA * (raw - it) } ?: raw
        emaAngle = smoothed
        step(smoothed)
    }

    private fun step(angle: Double) {
        val now = System.currentTimeMillis()

        // candidate phase from the smoothed angle, confirmed over N frames
        val wanted = when {
            angle >= topDeg -> RepPhase.TOP
            angle <= depthDeg -> RepPhase.BOTTOM
            angle >= descendDeg -> if (phase == RepPhase.BOTTOM || phase == RepPhase.ASCENDING) RepPhase.ASCENDING else RepPhase.DESCENDING
            else -> RepPhase.DESCENDING
        }
        if (wanted == candidate) candidateStreak++ else { candidate = wanted; candidateStreak = 1 }

        // depth hint 0..1 for the reticle (1 = deepest)
        val depth = ((topDeg - angle) / (topDeg - depthDeg)).coerceIn(0.0, 1.0).toFloat()

        if (candidateStreak >= CONFIRM_FRAMES && wanted != phase) {
            val previous = phase
            phase = wanted
            when (wanted) {
                RepPhase.BOTTOM -> {
                    reachedBottom = true
                    bottomAngle = angle
                    if (previous == RepPhase.TOP || previous == RepPhase.DESCENDING) descentStartAt = now
                }
                RepPhase.TOP -> {
                    // a rep exists only if we came up from a confirmed bottom
                    if (reachedBottom && previous == RepPhase.ASCENDING &&
                        now - lastRepAt > REP_COOLDOWN_MS && now - descentStartAt > MIN_REP_MS
                    ) {
                        lastRepAt = now
                        reps += 1
                        onRep(
                            reps,
                            RepQuality(
                                bottomDeg = bottomAngle,
                                topDeg = angle,
                                tempoMs = now - descentStartAt,
                                depthScore = ((topDeg - bottomAngle) / (topDeg - depthDeg)).coerceIn(0.0, 1.0).toFloat(),
                                symmetry = lastSymmetry,
                            )
                        )
                    }
                    reachedBottom = false
                    descentStartAt = 0L
                }
                else -> Unit
            }
        }
        onPhase(phase, if (phase == RepPhase.BOTTOM) 1f else depth)
    }

    /** v3 front-span machine for floor/front push-ups (see class header).
     *  TOP → DESCENDING → BOTTOM → ASCENDING → count; each rep measured
     *  against its OWN frozen trough and peak. Mirrors the 22/22 simulation. */
    private fun stepPushSpan(v: Float) {
        val now = System.currentTimeMillis()

        // long detection loss: the hunter moved — re-anchor, keep the count
        if (lastFrameAt != 0L && now - lastFrameAt > GAP_REANCHOR_MS) {
            phase = RepPhase.TOP
            armed = false
            baseline = v
            prevV = v
        }
        lastFrameAt = now

        // SLEW GUARD — relocation, not muscle
        val pv = prevV
        if (pv != null && abs(v - pv) > SLEW_MAX_PX) {
            baseline = v
            phase = RepPhase.TOP
            armed = false
            slewBlockUntil = now + SLEW_SETTLE_MS
            prevV = v
            onPhase(phase, 0f)
            return
        }
        prevV = v

        // asymmetric baseline: the lockout reference
        baseline = if (v < baseline) baseline + BASE_DOWN * (v - baseline)
        else baseline + BASE_UP * (v - baseline)

        val rom = romEst ?: (MIN_RISE_FLOOR / MIN_RISE_FRAC)
        val minRise = max(MIN_RISE_FLOOR, MIN_RISE_FRAC * rom)
        val dz = min(DZ_CAP, max(DZ_FLOOR, DZ_FRAC * rom))

        if (phase == RepPhase.SEARCH) {
            baseline = v
            phase = RepPhase.TOP
        }

        when (phase) {
            RepPhase.TOP -> {
                if (v >= baseline + dz && now >= slewBlockUntil) {
                    phase = RepPhase.DESCENDING
                    repTrough = baseline
                    peak = v
                    descentStartAt = now
                    descFrames = 1
                    sustain = 0
                }
            }
            RepPhase.DESCENDING -> {
                descFrames++
                if (v > peak) peak = v
                if (v >= repTrough + minRise) sustain++
                if (v <= peak - dz) {
                    // turned back up — validate the bottom
                    val depth = peak - repTrough
                    val tooDeep = romEst?.let { depth > ROM_OVER * it } ?: false
                    val valid = !tooDeep &&
                        depth >= minRise &&
                        descFrames >= MIN_DESC_FRAMES &&
                        now - descentStartAt >= MIN_DESC_MS &&
                        sustain >= MIN_SUSTAIN_FRAMES
                    if (valid) {
                        phase = RepPhase.BOTTOM
                        bottomAt = now
                        armed = true
                        bottomSpan = peak
                    } else {
                        phase = RepPhase.TOP  // nod / dip / lean excursion — no cycle
                    }
                }
            }
            RepPhase.BOTTOM -> {
                if (v > peak + dz) {
                    phase = RepPhase.DESCENDING
                    peak = v
                } else if (now - bottomAt >= BOTTOM_HOLD_MS) {
                    phase = RepPhase.ASCENDING
                    ascLo = v
                    ascLoAt = now
                }
            }
            RepPhase.ASCENDING -> {
                if (v < ascLo - 0.5f) {
                    ascLo = v
                    ascLoAt = now
                }
                val depth = peak - repTrough
                val climbed = peak - v
                if (armed && depth > 0f && climbed >= COME_BACK * depth) {
                    if (now - lastRepAt >= REP_COOLDOWN_MS && now - descentStartAt >= MIN_REP_MS) {
                        lastRepAt = now
                        reps += 1
                        romEst = romEst?.let { it + ROM_BLEND * (depth - it) } ?: depth
                        onRep(
                            reps,
                            RepQuality(
                                bottomDeg = bottomSpan.toDouble(),
                                topDeg = v.toDouble(),
                                tempoMs = now - descentStartAt,
                                depthScore = (depth / max(romEst ?: depth, 1f)).coerceIn(0f, 1f),
                                symmetry = 1f,
                            )
                        )
                    }
                    armed = false
                    phase = RepPhase.TOP
                } else if (armed && ascLoAt != 0L && now - ascLoAt >= CLIMB_STALL_MS && v <= baseline + dz * 2) {
                    // stale-trough recovery valve — armed before the top was
                    // learned; abort, the next descent self-heals. A climbing
                    // hunter keeps refreshing ascLoAt and never trips this.
                    armed = false
                    phase = RepPhase.TOP
                } else if (v >= ascLo + dz) {
                    phase = RepPhase.DESCENDING  // dipped back — same attempt continues
                }
            }
            RepPhase.SEARCH -> Unit
        }

        // depth hint for the reticle: 0 at lockout, 1 at valid depth
        val depthHint = when (phase) {
            RepPhase.DESCENDING -> ((v - repTrough) / max(minRise, 1f)).coerceIn(0f, 1f)
            RepPhase.BOTTOM -> 1f
            RepPhase.ASCENDING -> {
                val depth = peak - repTrough
                if (depth > 0f) ((peak - v) / depth).coerceIn(0f, 1f) else 0f
            }
            else -> 0f
        }
        onPhase(phase, depthHint)
    }

    // ── frame-quality gate: the "why is nothing counting" answers ───────────
    private fun visibility(pose: Pose): PoseStatus {
        // PUSH-UP (front-span): shoulders + nose is all the engine asks for —
        // elbows are NOT required from the floor view (the old list demanded
        // them and then measured nothing, one of the zero-rep roots).
        val need = if (exercise == RepExercise.PUSHUP) {
            listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.NOSE)
        } else {
            listOf(
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
                PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
            )
        }
        val present = need.count { (pose.getPoseLandmark(it)?.inFrameLikelihood ?: 0f) >= LIKELIHOOD_FLOOR }
        if (present == 0) return PoseStatus.POSE_NOT_DETECTED
        if (present < need.size) return if (exercise == RepExercise.SQUAT) PoseStatus.FULL_BODY_NOT_VISIBLE else PoseStatus.MOVE_BACK

        // body inside the frame, not clipped at an edge
        val xs = need.mapNotNull { pose.getPoseLandmark(it)?.position?.x }
        val ys = need.mapNotNull { pose.getPoseLandmark(it)?.position?.y }
        if (xs.isEmpty() || ys.isEmpty()) return PoseStatus.POSE_NOT_DETECTED
        val clipped = xs.any { it <= 2f || it >= imgW - 2f } || ys.any { it <= 2f || it >= imgH - 2f }
        if (clipped) return if (exercise == RepExercise.SQUAT) PoseStatus.FULL_BODY_NOT_VISIBLE else PoseStatus.MOVE_BACK

        // too small in frame = phone too far / wrong angle
        val spanX = (xs.max() - xs.min())
        val spanY = (ys.max() - ys.min())
        if (exercise == RepExercise.SQUAT && spanY < imgH * 0.35f) return PoseStatus.MOVE_BACK
        if (exercise == RepExercise.PUSHUP && spanX < imgW * 0.20f) return PoseStatus.MOVE_BACK

        // NOTE: the old "hip-line straightness" gate is gone for PUSH-UP — from
        // a floor/front camera the shoulder·hip·ankle angle foreshortens below
        // 130° on nearly every frame, which hard-blocked ALL counting.
        return PoseStatus.TRACKING
    }

    private fun report(status: PoseStatus) {
        if (status != lastStatus) { lastStatus = status; onStatus(status) }
    }

    private fun angleAt(p: Pose, a: Int, b: Int, c: Int): Double? {
        val pa = p.getPoseLandmark(a) ?: return null
        val pb = p.getPoseLandmark(b) ?: return null
        val pc = p.getPoseLandmark(c) ?: return null
        if (pa.inFrameLikelihood < LIKELIHOOD_FLOOR ||
            pb.inFrameLikelihood < LIKELIHOOD_FLOOR ||
            pc.inFrameLikelihood < LIKELIHOOD_FLOOR
        ) return null
        return angleBetween(pa.position.x, pa.position.y, pb.position.x, pb.position.y, pc.position.x, pc.position.y)
    }

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double {
        val bax = ax - bx
        val bay = ay - by
        val bcx = cx - bx
        val bcy = cy - by
        val dot = bax * bcx + bay * bcy
        val m1 = sqrt(max(bax * bax + bay * bay, 1e-6f))
        val m2 = sqrt(max(bcx * bcx + bcy * bcy, 1e-6f))
        val cos = (dot / (m1 * m2)).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }
}
