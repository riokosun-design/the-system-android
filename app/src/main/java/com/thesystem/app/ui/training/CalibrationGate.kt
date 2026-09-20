package com.thesystem.app.ui.training

import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.thesystem.app.core.sensors.ImuStabilityWitness
import com.thesystem.app.core.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CALIBRATION GATE — CV-BATTLE-ARCHITECTURE §7. The envelope is ENFORCED here:
 * no profile, no counting. Five stages, each with a named fix-it reason:
 *
 *   STABILITY  IMU quiet ≥ 700 ms                      "SET THE PHONE DOWN"
 *   PERSON     full body + margins, hold 1.2 s         "SHOW YOUR FULL BODY"
 *   VIEW       side / three-quarter / front            front = REFUSED
 *   LIGHT      luma mean ∈ [40,220], σ ≥ 12            "MORE LIGHT / WIPE LENS"
 *   TOP_LOCK   plank top held 1.5 s → learns θ_top,
 *              torso length, shoulder baseline
 *
 * Output: a [CalibProfile] the PushupEngineV4 counts against. GREEN = clean
 * side view (ranked battles require it); YELLOW = three-quarter view
 * (quests may proceed with reduced strictness); the gate simply refuses to
 * lock while a stage is RED.
 */
data class CalibProfile(
    val thetaTop: Double,       // adaptive lockout threshold (deg)
    val thetaDepth: Double,     // adaptive bottom threshold (deg)
    val torsoLenPx: Float,      // shoulder→hip at calibration, px
    val shoulderY0: Float,      // shoulder-mid Y at lockout, px
    val viewQuality: Float,     // 1.0 = side · 0.7 = three-quarter
    val level: CalibLevel,
    val lumaMean: Int,
)

enum class CalibLevel { GREEN, YELLOW }

enum class GateStage { STABILITY, PERSON, VIEW, LIGHT, TOP_LOCK, LOCKED }

data class GateUi(
    val stage: GateStage = GateStage.STABILITY,
    val stageIndex: Int = 0,             // 0..4 — chips before LOCKED
    val reason: String = "PROP THE PHONE 30–60 CM HIGH · SIDE VIEW OF YOUR BODY",
    val holdFrac: Float = 0f,            // progress inside the current stage
    val locked: Boolean = false,
    val profileLevel: CalibLevel? = null,
) {
    val passedCount: Int get() = stageIndex
}

class CalibrationGate(
    private val witness: ImuStabilityWitness,
    private val onProfile: (CalibProfile) -> Unit,
    private val onLandmarks: (List<Pair<Float, Float>>) -> Unit = {},
) {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val _ui = MutableStateFlow(GateUi())
    val ui: StateFlow<GateUi> = _ui

    var profile: CalibProfile? = null
        private set

    private var imgW = 1f
    private var imgH = 1f
    private var stage = GateStage.STABILITY

    // stage accumulators (cumulative-while-true with a short grace)
    private var holdMs = 0L
    private var lastGoodAt = 0L
    private var lastFrameAt = 0L
    private var reason = "PROP THE PHONE 30–60 CM HIGH · SIDE VIEW OF YOUR BODY"

    // VIEW evidence
    private var ratioEma: Float? = null
    private var viewQuality = 1f

    // LIGHT evidence
    private var lumaMean = 0
    private var lumaClean = true

    // TOP_LOCK samples
    private val thetaSamples = ArrayList<Double>(64)
    private val torsoSamples = ArrayList<Float>(64)
    private val shoulderYSamples = ArrayList<Float>(64)

    @androidx.camera.core.ExperimentalGetImage
    fun process(imageProxy: ImageProxy) {
        if (stage == GateStage.LOCKED) { imageProxy.close(); return }
        val now = System.currentTimeMillis()
        val dt = if (lastFrameAt == 0L) 33L else (now - lastFrameAt).coerceIn(1L, 200L)
        lastFrameAt = now

        val media = imageProxy.image ?: run { imageProxy.close(); return }
        val rot = imageProxy.imageInfo.rotationDegrees
        if (rot == 90 || rot == 270) {
            imgW = imageProxy.height.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.width.toFloat().coerceAtLeast(1f)
        } else {
            imgW = imageProxy.width.toFloat().coerceAtLeast(1f)
            imgH = imageProxy.height.toFloat().coerceAtLeast(1f)
        }
        // frame-quality probe on the Y plane BEFORE pose (LIGHT stage needs it)
        probeLuma(imageProxy)

        val image = InputImage.fromMediaImage(media, rot)
        detector.process(image)
            .addOnSuccessListener { pose -> advance(pose, dt, now) }
            .addOnFailureListener { advance(null, dt, now) }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun close() = detector.close()

    // ── stage machine ────────────────────────────────────────────────────────
    private fun advance(pose: Pose?, dt: Long, now: Long) {
        val marks = pose?.allPoseLandmarks.orEmpty()
        onLandmarks(marks.map { (it.position.x / imgW) to (it.position.y / imgH) })

        fun hold(ok: Boolean, needMs: Long, failReason: String, graceMs: Long = 260L): Boolean {
            if (ok) {
                if (lastGoodAt != 0L && now - lastGoodAt > graceMs) holdMs = 0L
                lastGoodAt = now
                holdMs += dt
            } else {
                if (now - lastGoodAt > graceMs) holdMs = 0L
                reason = failReason
            }
            return holdMs >= needMs
        }

        when (stage) {
            GateStage.STABILITY -> {
                if (!witness.available || hold(witness.stable, 700, "PHONE MOVING — WEDGE IT SOLID AND HOLD STILL")) {
                    enter(GateStage.PERSON, "STAND IN FRAME — HEAD TO FEET VISIBLE")
                }
            }

            GateStage.PERSON -> {
                val ok = personOk(pose)
                if (hold(ok, 1200, personReason(pose))) {
                    enter(GateStage.VIEW, "TURN SIDEWAYS — SHOULDER LINE TOWARD THE PHONE")
                }
            }

            GateStage.VIEW -> {
                val core = coreMarks(pose)
                if (core != null) {
                    val torso = dist(core.shMidX, core.shMidY, core.hipMidX, core.hipMidY).coerceAtLeast(1f)
                    // Side view ⇒ the two shoulders project onto each other, so the
                    // shoulder SPAN collapses vs the torso. Front view ⇒ span ≈ wide.
                    // span/torso ≤ 0.45 = side · ≤ 0.75 = three-quarter · else front.
                    val ratio = abs(core.lShX - core.rShX) / torso
                    ratioEma = ratioEma?.let { it + 0.25f * (ratio - it) } ?: ratio
                }
                val r = ratioEma ?: 1f
                when {
                    r <= 0.45f -> { viewQuality = 1f; enter(GateStage.LIGHT, "CHECKING LIGHT") }
                    r <= 0.75f -> {
                        // three-quarter: passable for quests, never for ranked
                        viewQuality = 0.7f
                        if (hold(true, 300, "")) enter(GateStage.LIGHT, "CHECKING LIGHT")
                        reason = "ANGLE ACCEPTED — TURN MORE SIDEWAYS FOR FULL STRICTNESS"
                    }
                    else -> {
                        holdMs = 0L
                        reason = "FRONT VIEW COUNTS NOTHING — PUT THE PHONE BESIDE YOU"
                    }
                }
            }

            GateStage.LIGHT -> {
                val ok = lumaMean in 40..220 && lumaClean
                if (hold(ok, 500, lightReason())) {
                    enter(GateStage.TOP_LOCK, "HOLD THE TOP POSITION — ARMS LOCKED, BODY STRAIGHT")
                }
            }

            GateStage.TOP_LOCK -> {
                val core = coreMarks(pose)
                var ok = false
                if (core != null && witness.stable) {
                    val theta = bestElbow(core)
                    val line = lineDev(core)
                    if (theta != null && theta >= 150.0 && line <= 0.14) {
                        ok = true
                        thetaSamples.add(theta)
                        torsoSamples.add(dist(core.shMidX, core.shMidY, core.hipMidX, core.hipMidY))
                        shoulderYSamples.add(core.shMidY)
                    } else if (theta != null && theta < 150.0) {
                        reason = "LOCK YOUR ELBOWS — FULL PLANK TOP"
                    } else {
                        reason = "BODY LINE BROKEN — STRAIGHTEN HEAD TO HEELS"
                    }
                } else if (core == null) {
                    reason = "STAY IN FRAME"
                } else {
                    reason = "PHONE MOVING — FREEZE"
                }
                if (hold(ok, 1500, reason)) lockProfile()
            }

            GateStage.LOCKED -> Unit
        }

        _ui.value = GateUi(
            stage = stage,
            stageIndex = stage.ordinal.coerceAtMost(4),
            reason = reason,
            holdFrac = (holdMs.toFloat() / stageNeedMs(stage)).coerceIn(0f, 1f),
            locked = stage == GateStage.LOCKED,
            profileLevel = profile?.level,
        )
    }

    private fun enter(next: GateStage, nextReason: String) {
        stage = next
        holdMs = 0L
        lastGoodAt = 0L
        reason = nextReason
    }

    private fun stageNeedMs(s: GateStage): Long = when (s) {
        GateStage.STABILITY -> 700; GateStage.PERSON -> 1200; GateStage.VIEW -> 300
        GateStage.LIGHT -> 500; GateStage.TOP_LOCK -> 1500; GateStage.LOCKED -> 1
    }

    private fun lockProfile() {
        val thetaTopCalib = percentile(thetaSamples, 0.10)
        val torsoPx = median(torsoSamples).coerceAtLeast(24f)
        val shoulderY0 = median(shoulderYSamples)
        // §5 adaptive thresholds — learned from THIS hunter's lockout, not the world
        val thetaTop = (thetaTopCalib - 10.0).coerceIn(140.0, 162.0)
        // §5: θ_depth = θ_top − clamp(0.55 · ROM_prior, 70°, 95°)
        val romPrior = 88.0
        val dropDeg = (0.55 * romPrior).coerceIn(70.0, 95.0)
        val thetaDepth = thetaTop - dropDeg
        val level = if (viewQuality >= 1f && lumaClean) CalibLevel.GREEN else CalibLevel.YELLOW
        val p = CalibProfile(
            thetaTop = thetaTop,
            thetaDepth = thetaDepth,
            torsoLenPx = torsoPx,
            shoulderY0 = shoulderY0,
            viewQuality = viewQuality,
            level = level,
            lumaMean = lumaMean,
        )
        profile = p
        stage = GateStage.LOCKED
        onProfile(p)
    }

    // ── probes ───────────────────────────────────────────────────────────────
    private fun probeLuma(imageProxy: ImageProxy) {
        val y = imageProxy.planes[0]
        val buf = y.buffer
        val rowStride = y.rowStride
        val pixStride = y.pixelStride
        val w = imageProxy.width
        val h = imageProxy.height
        var sum = 0L
        var sumSq = 0L
        var n = 0
        var row = h / 8
        while (row < h - h / 8) {
            var col = w / 8
            while (col < w - w / 8) {
                val v = buf.get(row * rowStride + col * pixStride).toInt() and 0xFF
                sum += v; sumSq += v.toLong() * v; n++
                col += 24
            }
            row += 24
        }
        if (n <= 0) return
        val mean = (sum / n).toInt()
        val var0 = (sumSq / n - mean.toLong() * mean).coerceAtLeast(0)
        lumaMean = mean
        lumaClean = sqrt(var0.toDouble()) >= 12.0
    }

    private fun lightReason(): String = when {
        lumaMean < 40 -> "TOO DARK — ADD LIGHT OR FACE A WINDOW"
        lumaMean > 220 -> "GLARE — KILL THE BACKLIGHT"
        !lumaClean -> "LENS FOGGED — WIPE IT"
        else -> "CHECKING LIGHT"
    }

    // ── pose geometry ────────────────────────────────────────────────────────
    private class Core(
        val shMidX: Float, val shMidY: Float, val hipMidX: Float, val hipMidY: Float,
        val ankMidX: Float, val ankMidY: Float,
        val lShX: Float, val rShX: Float,
        val lSh: PoseLandmark, val rSh: PoseLandmark,
        val lEl: PoseLandmark?, val rEl: PoseLandmark?,
        val lWr: PoseLandmark?, val rWr: PoseLandmark?,
        val lHip: PoseLandmark, val rHip: PoseLandmark,
        val lAnk: PoseLandmark, val rAnk: PoseLandmark,
    )

    private fun lm(pose: Pose, id: Int): PoseLandmark? =
        pose.getPoseLandmark(id)?.takeIf { it.inFrameLikelihood >= LIKELIHOOD }

    private fun coreMarks(pose: Pose?): Core? {
        pose ?: return null
        val lSh = lm(pose, PoseLandmark.LEFT_SHOULDER) ?: return null
        val rSh = lm(pose, PoseLandmark.RIGHT_SHOULDER) ?: return null
        val lHip = lm(pose, PoseLandmark.LEFT_HIP) ?: return null
        val rHip = lm(pose, PoseLandmark.RIGHT_HIP) ?: return null
        val lAnk = lm(pose, PoseLandmark.LEFT_ANKLE) ?: return null
        val rAnk = lm(pose, PoseLandmark.RIGHT_ANKLE) ?: return null
        return Core(
            shMidX = (lSh.position.x + rSh.position.x) / 2f,
            shMidY = (lSh.position.y + rSh.position.y) / 2f,
            hipMidX = (lHip.position.x + rHip.position.x) / 2f,
            hipMidY = (lHip.position.y + rHip.position.y) / 2f,
            ankMidX = (lAnk.position.x + rAnk.position.x) / 2f,
            ankMidY = (lAnk.position.y + rAnk.position.y) / 2f,
            lShX = lSh.position.x, rShX = rSh.position.x,
            lSh = lSh, rSh = rSh,
            lEl = lm(pose, PoseLandmark.LEFT_ELBOW),
            rEl = lm(pose, PoseLandmark.RIGHT_ELBOW),
            lWr = lm(pose, PoseLandmark.LEFT_WRIST),
            rWr = lm(pose, PoseLandmark.RIGHT_WRIST),
            lHip = lHip, rHip = rHip, lAnk = lAnk, rAnk = rAnk,
        )
    }

    private fun bestElbow(core: Core): Double? {
        val left = if (core.lEl != null && core.lWr != null) {
            Triple(core.lSh, core.lEl, core.lWr)
        } else null
        val right = if (core.rEl != null && core.rWr != null) {
            Triple(core.rSh, core.rEl, core.rWr)
        } else null
        // the better-witnessed arm wins (side view: the near arm)
        val pick = when {
            left != null && right != null -> {
                val lv = minOf(left.first.inFrameLikelihood, left.second.inFrameLikelihood, left.third.inFrameLikelihood)
                val rv = minOf(right.first.inFrameLikelihood, right.second.inFrameLikelihood, right.third.inFrameLikelihood)
                if (lv >= rv) left else right
            }
            left != null -> left
            right != null -> right
            else -> return null
        }
        return angleBetween(
            pick.first.position.x, pick.first.position.y,
            pick.second.position.x, pick.second.position.y,
            pick.third.position.x, pick.third.position.y,
        )
    }

    private fun lineDev(core: Core): Float {
        // perpendicular distance of hip-mid from the shoulder→ankle line, in torsos
        val ax = core.shMidX; val ay = core.shMidY
        val bx = core.ankMidX; val by = core.ankMidY
        val dx = bx - ax; val dy = by - ay
        val len = sqrt(max(dx * dx + dy * dy, 1e-6f))
        val perp = abs(dx * (core.hipMidY - ay) - (core.hipMidX - ax) * dy) / len
        val torso = dist(core.shMidX, core.shMidY, core.hipMidX, core.hipMidY).coerceAtLeast(1f)
        return perp / torso
    }

    private fun personOk(pose: Pose?): Boolean {
        val core = coreMarks(pose) ?: return false
        val xs = listOf(core.lShX, core.rShX, core.hipMidX, core.ankMidX)
        val ys = listOf(core.shMidY, core.hipMidY, core.ankMidY)
        val spanX = xs.max() - xs.min()
        val spanY = ys.max() - ys.min()
        if (spanX < imgW * 0.38f) return false                       // body too small / too far
        if (spanY < imgH * 0.08f) return false                       // plank barely occupies frame
        val clipped = xs.any { it <= imgW * 0.03f || it >= imgW * 0.97f } ||
            ys.any { it <= imgH * 0.03f || it >= imgH * 0.97f }
        return !clipped
    }

    private fun personReason(pose: Pose?): String {
        if (pose == null) return "NO BODY DETECTED — STEP INTO FRAME"
        val m = listOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
        ).count { (pose.getPoseLandmark(it)?.inFrameLikelihood ?: 0f) >= LIKELIHOOD }
        return if (m < 6) "FULL BODY NOT VISIBLE — FEET TO SHOULDERS" else "MOVE BACK — FIT THE WHOLE BODY"
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double {
        val bax = ax - bx; val bay = ay - by
        val bcx = cx - bx; val bcy = cy - by
        val dot = bax * bcx + bay * bcy
        val m1 = sqrt(max(bax * bax + bay * bay, 1e-6f))
        val m2 = sqrt(max(bcx * bcx + bcy * bcy, 1e-6f))
        return Math.toDegrees(acos((dot / (m1 * m2)).toDouble().coerceIn(-1.0, 1.0)))
    }

    private fun percentile(xs: List<Double>, q: Double): Double {
        if (xs.isEmpty()) return 170.0
        val s = xs.sorted()
        val i = ((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)
        return s[i]
    }

    private fun median(xs: List<Float>): Float {
        if (xs.isEmpty()) return 0f
        val s = xs.sorted()
        return s[s.size / 2]
    }

    companion object {
        const val LIKELIHOOD = 0.42f
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// GATE HUD — shared by quest proof and the war room. Monochrome discipline:
// SkyBlue = passed/armed (training surface), PaperWhite = in progress,
// FaintGray = locked out. The traffic semantics survive without hue.
// ═══════════════════════════════════════════════════════════════════════════
@Composable
fun BoxScope.GateOverlay(ui: GateUi, ranked: Boolean = false) {
    val stages = listOf("STABILITY", "BODY", "VIEW", "LIGHT", "TOP HOLD")

    Column(
        Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (ranked) "ENVELOPE CALIBRATION · RANKED" else "ENVELOPE CALIBRATION",
            style = MonoLabel, color = SkyBlue,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stages.forEachIndexed { i, name ->
                val passed = i < ui.passedCount
                val active = i == ui.stageIndex && !ui.locked
                val col = when {
                    passed || ui.locked -> SkyBlue
                    active -> PaperWhite
                    else -> FaintGray
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (passed || ui.locked) SkyBlue.copy(alpha = 0.18f) else Color.Transparent)
                        .border(1.dp, col.copy(alpha = if (passed || active || ui.locked) 0.9f else 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(name, color = col, fontFamily = SystemMono, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .border(1.dp, PaperWhite.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                ui.reason,
                color = PaperWhite,
                fontFamily = SystemMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(TrackGray)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(ui.holdFrac).background(SkyBlue))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "1 — PHONE 30–60 CM HIGH · 2 — SIDE VIEW OF THE BODY · 3 — FULL BODY IN FRAME · 4 — HOLD PLANK TOP",
            style = MaterialTheme.typography.labelSmall,
            color = LabelGray,
            textAlign = TextAlign.Center,
        )
    }
}
