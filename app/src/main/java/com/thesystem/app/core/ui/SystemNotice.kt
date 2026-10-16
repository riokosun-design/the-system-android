package com.thesystem.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.InkBlack
import com.thesystem.app.core.theme.LineSoft
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.PanelGray
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.theme.SystemMono
import java.time.LocalDate
import kotlin.math.abs

/**
 * THE SYSTEM — global notification window (§2.2 redesign).
 * Authentic Solo Leveling System Window: sharp cut corners, electric-blue
 * frame, corner brackets, scanline header, cold authoritative directives.
 * Zero motivational fluff — the System commands, it does not cheer.
 */
data class SystemNotice(
    val title: String,
    val body: String,
    val cta: String = "ACKNOWLEDGE",
    val spinning: Boolean = false,
)

private val SystemCyan = Color(0xFF22D3EE)
private val SystemCyanDim = Color(0x6622D3EE)
private val WindowBg = Color(0xF20A0E14)

/** Geometric corner brackets drawn on the window frame — the System's seal. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrameBrackets(w: Float, h: Float) {
    val arm = 22.dp.toPx()
    val th = 2.dp.toPx()
    val s = Stroke(th)
    val path = Path()
    // ┌ ┐
    path.moveTo(0f, arm); path.lineTo(0f, 0f); path.lineTo(arm, 0f)
    path.moveTo(w - arm, 0f); path.lineTo(w, 0f); path.lineTo(w, arm)
    // └ ┘
    path.moveTo(0f, h - arm); path.lineTo(0f, h); path.lineTo(arm, h)
    path.moveTo(w - arm, h); path.lineTo(w, h); path.lineTo(w, h - arm)
    drawPath(path, SystemCyan, style = s)
}

/** Subtle horizontal scanlines — the holographic texture of the System window. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScanlines(w: Float, h: Float) {
    var y = 0f
    val step = 5.dp.toPx()
    while (y < h) {
        drawLine(SystemCyan.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        y += step
    }
}

@Composable
fun SystemNoticeOverlay(notice: SystemNotice?, onDismiss: () -> Unit) {
    if (notice == null) return
    val shape = remember { CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .border(1.5.dp, Brush.verticalGradient(listOf(SystemCyan, SystemCyanDim, SystemCyan)), shape)
                .background(WindowBg, shape)
                .drawBehind { drawScanlines(size.width, size.height) }
                .padding(3.dp)
                .drawBehind { drawFrameBrackets(size.width, size.height) }
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── header alarm bar ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(SystemCyanDim, Color.Transparent, SystemCyanDim)))
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "[ ! ]  ${notice.title.uppercase()}  [ ! ]",
                    color = SystemCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp, fontFamily = SystemMono,
                )
            }
            Spacer(Modifier.height(14.dp))
            // ── directive body — cold, authoritative, no quotes ──
            Text(
                notice.body,
                color = PaperWhite, fontSize = 13.sp, lineHeight = 21.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                fontFamily = SystemMono, letterSpacing = 0.3.sp,
            )
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth()) {
                NeonButton(text = "[ ${notice.cta.uppercase()} ]", onClick = onDismiss, enabled = true, color = SystemCyan)
            }
        }
    }
}

/** §2.2: cold System directives — zero motivational speeches. */
object NoticeTemplates {

    private val LOGIN = listOf(
        "[NOTICE: Daily Quest parameters initialized.\nComplete all objectives before 00:00 or the penalty protocol engages.]",
        "[DIRECTIVE: Objectives uploaded.\nExecution is mandatory. Excuses are not logged.]",
        "[NOTICE: Streak integrity verified.\nMaintain the chain. Failure resets the counter.]",
        "[DIRECTIVE: Today's workload assigned.\nRep count, mental training, arena conduct — all measured.]",
        "[NOTICE: The System does not negotiate.\nComplete. Verify. Advance.]",
        "[DIRECTIVE: Hunter status nominal.\nNew objectives queued. Deviation is penalized.]",
        "[NOTICE: Training window open.\nUnused slots decay at midnight. No extensions exist.]",
        "[DIRECTIVE: Two weapons sharpened daily — body and mind.\nBoth checked at 00:00.]",
    )

    private val MISSED = listOf(
        "[WARNING: Objective lapse detected.\nMissing another day increases the penalty tier. Resume immediately.]",
        "[ALERT: Streak broken at quest deadline.\nXP decay protocol is armed. Today's objectives remain mandatory.]",
        "[WARNING: The System logged your absence.\nFurther gaps trigger XP decay and rank degradation.]",
        "[ALERT: Inactivity threshold reached.\nPenalty count active. Close today's quests to reset the clock.]",
    )

    private val ARENA_LOSS = listOf(
        "[RESULT: Defeat recorded.\nRep differential archived. Train. Return.]",
        "[RESULT: Objective failed.\nThe arena keeps no excuses — only records.]",
        "[RESULT: Loss logged.\nAnalysis complete: output insufficient. RECOMMEND: training.]",
        "[RESULT: Combat concluded. You stand second.\nRematch window open.]",
    )

    /** Day-seeded: same message all day, rotates across days. */
    fun dailyLogin(): String {
        val seed = (LocalDate.now().toEpochDay() % 10000).toInt()
        return LOGIN[abs(seed) % LOGIN.size]
    }

    fun missedWorkout(): String {
        val seed = ((LocalDate.now().toEpochDay() + 1) % 10000).toInt()
        return MISSED[abs(seed) % MISSED.size]
    }

    fun arenaLoss(): String {
        val seed = (System.nanoTime() % 100000).toInt()
        return ARENA_LOSS[abs(seed) % ARENA_LOSS.size]
    }
}
