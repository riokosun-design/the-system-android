package com.thesystem.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.InkBlack
import com.thesystem.app.core.theme.LineSoft
import com.thesystem.app.core.theme.LineStrong
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.PanelGray
import com.thesystem.app.core.theme.SystemMono
import java.time.LocalDate
import kotlin.math.abs

/**
 * THE SYSTEM — global notification card.
 * Dark rounded panel, hairline border, inner inset box, giveway spinner, one CTA.
 */
data class SystemNotice(
    val title: String,
    val body: String,
    val cta: String = "ENTER SYSTEM",
    val spinning: Boolean = true,
)

@Composable
fun SystemNoticeOverlay(notice: SystemNotice?, onDismiss: () -> Unit) {
    if (notice == null) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .background(PanelGray, RoundedCornerShape(22.dp))
                .border(1.5.dp, LineStrong, RoundedCornerShape(22.dp))
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info, contentDescription = null,
                    Modifier.size(15.dp), tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    notice.title, color = PaperWhite, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontFamily = SystemMono,
                )
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(InkBlack.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .border(1.dp, LineSoft, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        notice.body,
                        color = PaperWhite, fontSize = 13.sp, lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal, textAlign = TextAlign.Center,
                    )
                    if (notice.spinning) {
                        Spacer(Modifier.height(18.dp))
                        SystemProcessing("THE SYSTEM IS WATCHING", compact = true)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth()) {
                NeonButton(text = notice.cta, onClick = onDismiss, enabled = true)
            }
        }
    }
}

/** Rotating copy banks — day-seeded so every login feels fresh but never random-noisy. */
object NoticeTemplates {

    private val LOGIN = listOf(
        "Discipline isn't built when you're motivated.\nIt's built when you show up anyway.",
        "Another day on record.\nThe System is watching — make it count.",
        "Yesterday's effort is done.\nToday is a fresh ledger. Fill it.",
        "No speeches today.\nOne quest, one focus, one win at a time.",
        "You showed up. That is 90% of the battle.\nNow finish the remaining 10%.",
        "The System does not celebrate attendance.\nIt celebrates execution. Begin.",
        "Small wins, stacked daily,\nbecome an unstoppable rank climb.",
        "SYSTEM ONLINE.\nBody in the arena. Mind on the board. Build.",
        "MENTAL QUEST AVAILABLE.\nYour mind is your second weapon — draw it daily.",
    )

    private val MISSED = listOf(
        "⚡ You missed a day. No dramatics —\ntoday's quest is ready. Close the gap.",
        "🎯 A missed day is data, not defeat.\nToday's protocol is already queued.",
        "⚔️ Streaks break. Discipline doesn't.\nStart today's quest and rebuild.",
        "🫡 The System logged one gap.\nErase it with today's session.",
    )

    private val ARENA_LOSS = listOf(
        "You lost the war, hunter. 🫡\nTrain harder and come back stronger.",
        "The arena keeps receipts. ⚔️\nThis one is theirs — the next one is yours.",
        "💀 Defeat logged. No excuses filed.\nTrain. Rematch. Reclaim.",
        "You fell short this time. 🎯\nRefine the grind — the arena is waiting.",
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
