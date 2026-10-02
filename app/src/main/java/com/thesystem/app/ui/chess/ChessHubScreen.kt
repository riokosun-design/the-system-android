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
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("chess_prefs", android.content.Context.MODE_PRIVATE) }
    var settingsOpen by remember { mutableStateOf(false) }
    var defaultDiff by remember { mutableIntStateOf(prefs.getInt("default_diff", 1).coerceIn(0, 3)) }
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

                // ── COMMAND DECK (spec: 6 doors) — every door is either real
                //    navigation or an HONEST sealed state. No fake matchmaking,
                //    no fake names, no dead buttons. ─────────────────────────
                item {
                    Box(Modifier.enterAnim(1)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Grid.S8)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                            // PLAY ONLINE — sealed until the squad network ships
                            SealedDoor("PLAY ONLINE", "NEURAL LINK OFFLINE — SHIPS WITH THE SQUAD NETWORK", Modifier.weight(1f))
                            DoorCard("PLAY vs AI", "FOUR ENGINE LEVELS · PRACTICE ELO TRACK", Modifier.weight(1f)) {
                                haptics.select(); nav.navigate(Routes.chessGame(ChessMode.AI_TRAINING.name))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                            DoorCard("PLAY WITH FRIENDS", "ONE BOARD · TWO HUNTERS · NO RATING", Modifier.weight(1f)) {
                                haptics.select(); nav.navigate(Routes.chessGame(ChessMode.FRIEND_MATCH.name))
                            }
                            DoorCard("CHESS PUZZLES", "DIRECTOR-PICKED · ADAPTS TO YOUR FORM", Modifier.weight(1f)) {
                                haptics.select(); nav.navigate(Routes.chessPuzzles(daily = false))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                            // RANKINGS — your real number only; the public ladder
                            // is sealed until season one. No invented names, ever.
                            Box(
                                Modifier
                                    .weight(1f)
                                    .border(1.dp, LineSoft, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                Column {
                                    Text("RANKINGS", color = FaintGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
                                    Text("YOUR COMPETITIVE RATING ${s.profile?.rating ?: 400}", style = MonoLabel, color = LabelGray)
                                    Text("LADDER SEALED — FIRST WAR SEASON PENDING · CLIMB VIA MENTAL WAR", style = MonoLabel, color = FaintGray)
                                }
                            }
                            DoorCard("SETTINGS", if (settingsOpen) "CLOSE ENGINE DEFAULTS" else "ENGINE DEFAULTS · IDENTITY", Modifier.weight(1f)) {
                                haptics.select(); settingsOpen = !settingsOpen
                            }
                        }
                    }
                    }
                }

                if (settingsOpen) {
                    item {
                        GlowCard(glow = SkyBlue) {
                            Text("ENGINE DEFAULTS", style = MonoLabel, color = SkyBlue)
                            Spacer(Modifier.height(6.dp))
                            Text("AI ENGINE — SYSTEM ENGINE α-β v1 · ON-DEVICE SEARCH · NEGAMAX + PST EVAL · NO CLOUD MODEL", style = MonoLabel, color = LabelGray)
                            Text("COACH — SYSTEM CORE RULES · NO LLM CONNECTED", style = MonoLabel, color = LabelGray)
                            Spacer(Modifier.height(10.dp))
                            Text("DEFAULT ENGINE LEVEL (PLAY vs AI)", style = MonoLabel, color = LabelGray)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                com.thesystem.app.chess.ChessAI.Difficulty.entries.forEachIndexed { i, d ->
                                    Text(
                                        d.label,
                                        color = if (i == defaultDiff) SkyBlue else LabelGray,
                                        fontSize = 10.sp, fontFamily = SystemMono,
                                        fontWeight = if (i == defaultDiff) FontWeight.Bold else FontWeight.Normal,
                                        letterSpacing = 1.2.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(1.dp, if (i == defaultDiff) SkyBlue else LineSoft, RoundedCornerShape(6.dp))
                                            .clickable {
                                                defaultDiff = i
                                                prefs.edit().putInt("default_diff", i).apply()
                                            }
                                            .padding(vertical = 9.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── MENTAL RANK (§6) ─────────────────────────────────────────
                item { Box(Modifier.enterAnim(2)) { MentalRankCard(s) } }

                // ── MENTAL POWER (§7) ────────────────────────────────────────
                item { Box(Modifier.enterAnim(3)) { MentalPowerCard(s) } }

                // ── DAILY CHALLENGE banner ───────────────────────────────────
                item {
                    Box(Modifier.enterAnim(4)) {
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

                // ── TRAINING MODES (§4) — doors already on the command deck
                //    (AI_TRAINING / FRIEND_MATCH / PUZZLE_TRAINING) stay out
                //    of the grid; every other real mode lives here. ──────────
                item { SectionTitle("TRAINING MODES", SkyBlue) }
                items(
                    ChessMode.entries.filter {
                        !it.soon && it !in setOf(
                            ChessMode.AI_TRAINING, ChessMode.FRIEND_MATCH, ChessMode.PUZZLE_TRAINING,
                        )
                    }.chunked(2),
                ) { row ->
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

/** Command-deck door — REAL navigation only. */
@Composable
private fun DoorCard(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .border(1.dp, SkyBlue.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .background(PanelGray, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column {
            Text(title, color = PaperWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
            Text(sub, style = MonoLabel, color = LabelGray, lineHeight = 13.sp)
        }
    }
}

/** Command-deck door that is HONESTLY sealed — states why, promises no date. */
@Composable
private fun SealedDoor(title: String, sub: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, LineSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = FaintGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono, modifier = Modifier.weight(1f))
                Text("SEALED", style = MonoLabel, color = FaintGray)
            }
            Text(sub, style = MonoLabel, color = FaintGray, lineHeight = 13.sp)
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
