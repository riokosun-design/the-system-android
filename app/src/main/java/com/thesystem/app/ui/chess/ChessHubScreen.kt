package com.thesystem.app.ui.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*

private val MENTAL_STATS = listOf(
    "tactics" to "TACTICS",
    "focus" to "FOCUS",
    "memory" to "MEMORY",
    "calculation" to "CALCULATION",
    "adaptability" to "ADAPTABILITY",
    "decision" to "DECISION",
    "composure" to "COMPOSURE",
)

/** Tab 4 — SYSTEM CHESS (spec §3): mind training as first-class discipline. */
@Composable
fun ChessHubScreen(nav: NavHostController, vm: ChessViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error() else haptics.success()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.clearNotice() }
    }

    SystemBackground(wallpaperAlpha = 0.15f) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin),
                verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                contentPadding = PaddingValues(vertical = Grid.S16),
            ) {
                item {
                    Box(Modifier.enterAnim(0)) {
                        Column {
                            Text("SYSTEM CHESS", color = PaperWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Text("MIND IS THE SECOND WEAPON", style = MonoLabel, color = SkyBlue)
                        }
                    }
                }

                // ── MENTAL RANK (§6) ─────────────────────────────────────────
                item { Box(Modifier.enterAnim(1)) { MentalRankCard(s) } }

                // ── MENTAL POWER (§7) ────────────────────────────────────────
                item { Box(Modifier.enterAnim(2)) { MentalPowerCard(s) } }

                // ── DAILY CHALLENGE banner ───────────────────────────────────
                item {
                    Box(Modifier.enterAnim(3)) {
                        GlowCard(glow = SkyBlue) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("DAILY CHALLENGE", style = MonoLabel, color = SkyBlue)
                                    Text("ONE SEED · ONE SHOT", color = PaperWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                                }
                                GhostButton("ENTER", { haptics.select(); nav.navigate(Routes.chessPuzzles(daily = true)) })
                            }
                        }
                    }
                }

                // ── TRAINING MODES (§4) ──────────────────────────────────────
                item { SectionTitle("TRAINING MODES", SkyBlue) }
                items(ChessMode.entries.filter { !it.soon }.chunked(2)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                        row.forEach { mode ->
                            ModeTile(mode, Modifier.weight(1f)) {
                                haptics.select()
                                when (mode) {
                                    ChessMode.PUZZLE_TRAINING,
                                    ChessMode.TACTICAL_TRAINING,
                                    ChessMode.BLUNDER_TRAINING -> nav.navigate(Routes.chessPuzzles(daily = false))
                                    ChessMode.DAILY_CHALLENGE -> nav.navigate(Routes.chessPuzzles(daily = true))
                                    else -> nav.navigate(Routes.chessGame(mode.name))
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                // ── NEURAL LINK — incubation, never a fake promise ───────────
                item { SectionTitle("NEURAL LINK") }
                items(ChessMode.entries.filter { it.soon }.chunked(2)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                        row.forEach { mode ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .border(1.dp, LineSoft, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                Column {
                                    Text(mode.label, color = FaintGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                                    Text(mode.sub.uppercase(), style = MonoLabel, color = FaintGray)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                item { Spacer(Modifier.height(96.dp)) }
            }
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(snack)
            }
        }
    }
}

@Composable
private fun MentalRankCard(s: ChessViewModel.ChessState) {
    val p = s.profile
    GlowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MENTAL RANK", style = MonoLabel, color = SkyBlue)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p?.mentalRank ?: "F",
                        color = PaperWhite, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("RANK · LV ${p?.mentalLevel ?: 1}", color = PaperWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                        Text("RATING ${p?.rating ?: 400} · MENTAL XP ${p?.mentalXp ?: 0}", style = MonoLabel, color = LabelGray)
                        Text("PRACTICE ELO ${p?.practiceElo ?: 400} · WAR ${p?.wins ?: 0}W/${p?.losses ?: 0}L/${p?.draws ?: 0}D", style = MonoLabel, color = LabelGray)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${p?.wins ?: 0}W", color = PaperWhite, fontSize = 12.sp, fontFamily = SystemMono)
                Text("${p?.draws ?: 0}D · ${p?.losses ?: 0}L", style = MonoLabel, color = LabelGray)
                Text("${p?.puzzlesSolved ?: 0} PUZZLES", style = MonoLabel, color = SkyBlue)
            }
        }
        if (p != null && p.games == 0 && p.puzzlesAttempted == 0) {
            Spacer(Modifier.height(6.dp))
            Text("No mental sessions yet. The ladder starts at F — it only climbs with proof.",
                style = MonoLabel, color = LabelGray)
        }
    }
}

@Composable
private fun MentalPowerCard(s: ChessViewModel.ChessState) {
    val p = s.profile
    GlowCard {
        Text("MENTAL POWER", style = MonoLabel, color = SkyBlue)
        Spacer(Modifier.height(8.dp))
        val allZero = p == null || MENTAL_STATS.all { p.stat(it.first) == 0 }
        MENTAL_STATS.forEach { (key, label) ->
            val v = p?.stat(key) ?: 0
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MonoLabel, color = LabelGray, modifier = Modifier.width(110.dp))
                Box(
                    Modifier
                        .weight(1f).height(6.dp)
                        .background(TrackGray, RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((v / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(if (v > 0) SkyBlue else TrackGray, RoundedCornerShape(3.dp))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(if (allZero) "—" else "$v", style = MonoLabel, color = PaperWhite, modifier = Modifier.width(28.dp))
            }
        }
        if (allZero) {
            Spacer(Modifier.height(6.dp))
            Text("Stats evolve FROM PERFORMANCE ONLY — play games, solve puzzles.", style = MonoLabel, color = FaintGray)
        }
    }
}

@Composable
private fun ModeTile(mode: ChessMode, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .border(1.dp, LineSoft, RoundedCornerShape(10.dp))
            .background(PanelGray, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column {
            Text(mode.label, color = PaperWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
            Text(mode.sub.uppercase(), style = MonoLabel, color = LabelGray)
        }
    }
}
