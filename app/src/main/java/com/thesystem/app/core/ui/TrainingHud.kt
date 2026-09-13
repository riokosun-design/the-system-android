package com.thesystem.app.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.*
import java.util.Calendar

// ═══════════════════════════════════════════════════════════════════════════
// PHASE-2 TRAINING HUD
// Sky-blue (#38BDF8) is deliberately scoped to these training surfaces only;
// the rest of the app stays monochrome. Frosted glass, glowing hairline frame,
// anime HUD typography.
// ═══════════════════════════════════════════════════════════════════════════

/** A selectable training course in the horizontal carousel. */
enum class TrainingCourse(
    val title: String,
    val tagline: String,
    val focus: QuestFocus,
    val build: SilhouetteBuild,
) {
    ADAPTIVE("Adaptive Fighter Physique", "Three-day routine: weighted dips, pull-ups, pistols",
        QuestFocus.PUSH, SilhouetteBuild.LEAN),
    SWORDSMAN("Black Swordsman Strength", "Full-body barbell work — deadlifts and rows",
        QuestFocus.SQUAT, SilhouetteBuild.BROAD),
    MONARCH("Shadow Compound · Monarch Core", "Push / squat / run engine — the daily protocol",
        QuestFocus.RUN, SilhouetteBuild.MONARCH),
}

enum class QuestFocus { PUSH, SQUAT, RUN }

enum class SilhouetteBuild { LEAN, BROAD, MONARCH }

/**
 * Horizontal course rail — tapping a course loads that focus below in the
 * daily quest window. Card art is a vector silhouette, no image downloads.
 */
@Composable
fun CourseCarousel(
    selected: TrainingCourse,
    onSelect: (TrainingCourse) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(TrainingCourse.entries.size) { i ->
            val course = TrainingCourse.entries[i]
            val active = course == selected
            CourseCard(course, active) { onSelect(course) }
        }
    }
}

@Composable
private fun CourseCard(course: TrainingCourse, active: Boolean, onClick: () -> Unit) {
    val frame = if (active) SkyBlue else LineStrong
    Box(
        Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelGray)
            .border(if (active) 1.5.dp else 1.dp, frame, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(Modifier.padding(14.dp)) {
            FighterSilhouette(
                build = course.build,
                active = active,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                course.title.uppercase(),
                color = if (active) SkyBlue else PaperWhite,
                fontFamily = SystemSans,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(course.tagline, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Minimal geometric fighter silhouette in blue hairline / gray. */
@Composable
private fun FighterSilhouette(build: SilhouetteBuild, active: Boolean, modifier: Modifier = Modifier) {
    val line = if (active) SkyBlue else LabelGray
    val fill = if (active) SkyBlueDim else RaisedGray
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2
        val shoulderW = when (build) {
            SilhouetteBuild.LEAN -> w * 0.30f
            SilhouetteBuild.BROAD -> w * 0.42f
            SilhouetteBuild.MONARCH -> w * 0.36f
        }
        val headR = w * 0.09f
        val headY = h * 0.16f
        val shoulderY = h * 0.34f
        val waistY = h * 0.58f
        val kneeY = h * 0.80f
        val feetY = h * 0.97f

        // head
        drawCircle(fill, radius = headR * 1.6f, center = Offset(cx, headY))
        drawCircle(line, radius = headR * 1.6f, center = Offset(cx, headY), style = Stroke(2f))
        drawCircle(fill, radius = headR, center = Offset(cx, headY))

        // torso path (neck → shoulders → taper → waist)
        val torso = Path().apply {
            moveTo(cx, shoulderY - headR * 0.7f)
            lineTo(cx - shoulderW, shoulderY)
            lineTo(cx - shoulderW * 0.55f, waistY)
            lineTo(cx + shoulderW * 0.55f, waistY)
            lineTo(cx + shoulderW, shoulderY)
            close()
        }
        drawPath(torso, fill)
        drawPath(torso, line, style = Stroke(2f))

        // abs hints
        drawLine(line.copy(alpha = 0.5f), Offset(cx, shoulderY + 10f), Offset(cx, waistY - 8f), 1.5f)

        // arms
        drawLine(line, Offset(cx - shoulderW, shoulderY), Offset(cx - shoulderW * 1.15f, waistY + 18f), 3f, cap = StrokeCap.Round)
        drawLine(line, Offset(cx + shoulderW, shoulderY), Offset(cx + shoulderW * 1.15f, waistY + 18f), 3f, cap = StrokeCap.Round)

        // legs — stance width follows the build
        val stance = when (build) {
            SilhouetteBuild.LEAN -> w * 0.16f
            SilhouetteBuild.BROAD -> w * 0.22f
            SilhouetteBuild.MONARCH -> w * 0.19f
        }
        // thighs
        drawLine(line, Offset(cx - shoulderW * 0.32f, waistY), Offset(cx - stance, kneeY), 4f, cap = StrokeCap.Round)
        drawLine(line, Offset(cx + shoulderW * 0.32f, waistY), Offset(cx + stance, kneeY), 4f, cap = StrokeCap.Round)
        // shins
        drawLine(line, Offset(cx - stance, kneeY), Offset(cx - stance * 1.1f, feetY), 4f, cap = StrokeCap.Round)
        drawLine(line, Offset(cx + stance, kneeY), Offset(cx + stance * 1.1f, feetY), 4f, cap = StrokeCap.Round)
    }
}

// ── Daily quest window ──────────────────────────────────────────────────────

/** One parsed goal row of the daily protocol. */
data class QuestGoal(val label: String, val progress: Int, val target: Int, val log: () -> Unit)

/**
 * Frosted-blue QUEST INFO window. Itemized goals ([x/y]), live midnight reset
 * countdown, LOG action per goal. Modeled on the spec's system quest card.
 */
@Composable
fun QuestWindowCard(
    goals: List<QuestGoal>,
    modifier: Modifier = Modifier,
    title: String = "MAIN CHARACTER TRAINING ARC",
) {
    val nowMs = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMs.value = System.currentTimeMillis()
        }
    }
    val midnight = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
    val remain = (midnight - nowMs.value).coerceAtLeast(0L)
    val hh = remain / 3_600_000
    val mm = (remain % 3_600_000) / 60_000
    val ss = (remain % 60_000) / 1000
    val resetText = "%02d:%02d:%02d".format(hh, mm, ss)

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SkyBlue.copy(alpha = 0.08f))
            .border(1.5.dp, SkyBlue.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .padding(Grid.S16),
    ) {
        Column {
            // glowing badge
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SkyBlue.copy(alpha = 0.16f))
                    .border(1.dp, SkyBlue, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("[DAILY QUEST] — $title", color = SkyBlue,
                    fontFamily = SystemMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(12.dp))

            goals.forEach { g ->
                val done = g.progress >= g.target
                QuestGoalRow(g, done)
                Spacer(Modifier.height(8.dp))
            }

            NeonDivider(SkyBlue.copy(alpha = 0.35f))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WARNING — failure before reset incurs the protocol penalty.",
                    style = MaterialTheme.typography.labelSmall, color = LabelGray,
                    modifier = Modifier.weight(1f))
                Text("RESET $resetText", color = SkyBlue, fontFamily = SystemMono,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun QuestGoalRow(g: QuestGoal, done: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GOAL", style = MonoLabel, modifier = Modifier.width(46.dp))
            Text(g.label.uppercase(), color = PaperWhite, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            Text("[${g.progress}/${g.target}]",
                color = if (done) LabelGray else SkyBlue,
                fontFamily = SystemMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // clean progress hairline
            Box(
                Modifier
                    .weight(1f).height(2.dp)
                    .clip(RoundedCornerShape(1.dp)).background(TrackGray)
            ) {
                val frac = if (g.target > 0) (g.progress.toFloat() / g.target).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .fillMaxHeight().fillMaxWidth(frac)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (done) LabelGray else SkyBlue)
                )
            }
            Spacer(Modifier.width(10.dp))
            if (done) {
                Text("CLEARED", color = LabelGray, style = MaterialTheme.typography.labelLarge)
            } else {
                NeonButton("LOG", { g.log() }, color = SkyBlue)
            }
        }
    }
}

// ── System notification modal ───────────────────────────────────────────────

/** Minimalist HUD popup: dark glass, blue hairline, ⓘ NOTIFICATION, one action. */
@Composable
fun SystemNotificationModal(
    visible: Boolean,
    body: String,
    actionLabel: String = "[ ACCEPT ]",
    onAccept: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    AnimatedVisibility(visible) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onDismiss?.invoke() ?: onAccept() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(Grid.Margin)
                    .clip(RoundedCornerShape(12.dp))
                    .background(RaisedGray.copy(alpha = 0.97f))
                    .border(1.5.dp, SkyBlue, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}, // swallow taps on the panel
                    )
                    .padding(Grid.S20),
            ) {
                Text("ⓘ NOTIFICATION", color = SkyBlue,
                    fontFamily = SystemMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(12.dp))
                Text(body, color = PaperWhite, style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Start)
                Spacer(Modifier.height(20.dp))
                NeonButton(actionLabel, onAccept, Modifier.fillMaxWidth(), color = SkyBlue)
            }
        }
    }
}
