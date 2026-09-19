package com.thesystem.app.ui.rank

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.model.VerifiedBestsDto
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════════════
// RANK — the F → SS progression ladder (0.6.0).
// One glance answers: WHERE DO I STAND · WHAT IS NEXT · WHAT HAVE I PROVEN.
// The ladder is a pure function of level; degradation (GARBAGE/LOSER) is an
// overlay discipline, never a rewrite of earned progress. Performance tiles
// read from immutable verified proofs — nothing here is client-estimated.
// ═══════════════════════════════════════════════════════════════════════════

data class RankState(
    val loading: Boolean = true,
    val profile: UserDto? = null,
    val bests: VerifiedBestsDto? = null,
)

@HiltViewModel
class RankViewModel @Inject constructor(
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RankState())
    val state: StateFlow<RankState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val profile = system.profile()
        val bests = system.verifiedBests()
        _state.value = RankState(loading = false, profile = profile, bests = bests)
    }
}

@Composable
fun RankScreen(vm: RankViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val level = s.profile?.level ?: 1
    val tier = SystemMath.tierFor(level)
    val penaltyRank = s.profile?.rank?.value?.takeIf { it.isPenaltyRank }

    SystemBackground {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin),
            verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
            contentPadding = PaddingValues(vertical = Grid.S16),
        ) {
            // header
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SYSTEM", style = MonoLabel, color = SkyBlue)
                        Text("RANK", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp)
                    }
                    penaltyRank?.let { Text(it.title, style = MonoLabel, color = LabelGray) }
                }
            }

            // central emblem
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TierEmblem(tier)
                    Spacer(Modifier.height(Grid.S12))
                    Text(tier.title, color = SkyBlue, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
                    Text("LEVEL $level", style = MonoLabel, color = LabelGray)
                    if (penaltyRank != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "DEGRADED: ${penaltyRank.title} — skipped days decay XP, never the ladder.",
                            style = MaterialTheme.typography.bodySmall, color = LabelGray, textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // the ladder — every tier visible, the current one commands
            item { LadderStrip(current = tier) }

            // next gate
            item {
                val next = SystemMath.nextTier(level)
                GlowCard(modifier = Modifier.fillMaxWidth()) {
                    if (next == null) {
                        Text("APEX REACHED", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                        Text("SS-RANK — the ladder ends here. The protocol does not.", style = MaterialTheme.typography.bodySmall, color = LabelGray)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("NEXT RANK", style = MonoLabel, color = LabelGray)
                            Spacer(Modifier.weight(1f))
                            Text("${next.title} · LV ${next.minLevel}", color = PaperWhite, fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(Grid.S8))
                        val frac by androidx.compose.animation.core.animateFloatAsState(
                            SystemMath.tierProgress(level), tween(700), label = "tierCharge",
                        )
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(TrackGray)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(SkyBlue))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${next.minLevel - level} LEVELS TO GO", style = MonoLabel, color = SkyBlue)
                    }
                }
            }

            // verified record — proof-only feed
            item {
                Column {
                    SectionTitle("VERIFIED PERFORMANCE")
                    Spacer(Modifier.height(Grid.S8))
                    val b = s.bests
                    when {
                        s.loading -> Text("READING THE RECORD…", style = MonoLabel, color = LabelGray)
                        b == null || b.sessions == 0 -> EmptyState(
                            "No verified sessions yet — clear today's protocol and your record starts here."
                        )
                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(Grid.S8)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                                    StatTile("PUSH-UP", "${b.pushReps} REPS", PaperWhite, Modifier.weight(1f))
                                    StatTile("SQUAT", "${b.squatReps} REPS", PaperWhite, Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                                    StatTile("RUN", "%.2f KM".format(b.runMeters / 1000.0), PaperWhite, Modifier.weight(1f))
                                    StatTile("WARS WON", "${b.battleWins}", SkyBlue, Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(Grid.S8))
                            Text(
                                "${b.sessions} verified sessions · camera · radar · step sensor. Manual logs never touch this record.",
                                style = MaterialTheme.typography.bodySmall, color = FaintGray,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

/** Central tier emblem — diamond HUD frame, pulse ring, the letter commands. */
@Composable
private fun TierEmblem(tier: SystemMath.HunterTier) {
    val pulse by rememberInfiniteTransition(label = "rankPulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "p",
    )
    Box(Modifier.size(216.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            // outer pulse ring
            drawCircle(SkyBlue.copy(alpha = (1f - pulse) * 0.25f), radius = r * (0.72f + 0.28f * pulse), center = c, style = Stroke(1.5.dp.toPx()))
            // diamond frame
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y - r * 0.78f)
                lineTo(c.x + r * 0.78f, c.y)
                lineTo(c.x, c.y + r * 0.78f)
                lineTo(c.x - r * 0.78f, c.y)
                close()
            }
            drawPath(path, PaperWhite.copy(alpha = 0.9f), style = Stroke(2.dp.toPx()))
            val inner = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y - r * 0.66f)
                lineTo(c.x + r * 0.66f, c.y)
                lineTo(c.x, c.y + r * 0.66f)
                lineTo(c.x - r * 0.66f, c.y)
                close()
            }
            drawPath(inner, LineStrong, style = Stroke(1.dp.toPx()))
            // corner ticks
            val t = r * 0.10f
            val o = r * 0.90f
            listOf(Offset(-o, -o), Offset(o, -o), Offset(o, o), Offset(-o, o)).forEach { p0 ->
                val sx = if (p0.x > 0) -1f else 1f
                val sy = if (p0.y > 0) -1f else 1f
                drawLine(PaperWhite, Offset(c.x + p0.x, c.y + p0.y), Offset(c.x + p0.x + t * sx, c.y + p0.y), strokeWidth = 2.dp.toPx())
                drawLine(PaperWhite, Offset(c.x + p0.x, c.y + p0.y), Offset(c.x + p0.x, c.y + p0.y + t * sy), strokeWidth = 2.dp.toPx())
            }
        }
        Text(
            tier.letter,
            color = SkyBlue, fontFamily = SystemMono, fontWeight = FontWeight.Black,
            fontSize = if (tier.letter.length > 1) 64.sp else 86.sp,
        )
    }
}

/** F → SS strip: past subdued, current commanding, future dormant. */
@Composable
private fun LadderStrip(current: SystemMath.HunterTier) {
    val tiers = SystemMath.HunterTier.entries
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tiers.forEach { t ->
                val isCurrent = t == current
                val isPast = t.ordinal < current.ordinal
                val border = when {
                    isCurrent -> SkyBlue
                    isPast -> LineStrong
                    else -> LineSoft
                }
                val bg = if (isCurrent) SkyBlue.copy(alpha = 0.14f) else PanelGray
                val fg = when {
                    isCurrent -> SkyBlue
                    isPast -> LabelGray
                    else -> FaintGray.copy(alpha = 0.5f)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .border(if (isCurrent) 1.5.dp else 1.dp, border, RoundedCornerShape(8.dp))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.letter, color = fg, fontFamily = SystemMono,
                        fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Medium,
                        fontSize = if (isCurrent) 17.sp else 14.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("F · LV 1", style = MonoLabel, color = FaintGray)
            Spacer(Modifier.weight(1f))
            Text("SS · LV 90", style = MonoLabel, color = FaintGray)
        }
    }
}
