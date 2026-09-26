package com.thesystem.app.ui.chess

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.thesystem.app.chess.Board
import com.thesystem.app.chess.Move
import com.thesystem.app.chess.Puzzle
import com.thesystem.app.chess.PuzzlePack
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * PUZZLE TRAINING (§14) — adaptive difficulty: streaks climb the tier, misses
 * soften it. Daily challenge = one shot per calendar seed. Solved/failed both
 * report to the server; stats move ONLY from performance.
 */
@Composable
fun ChessPuzzleScreen(
    daily: Boolean,
    onBack: () -> Unit,
    vm: ChessViewModel = hiltViewModel(),
) {
    val haptics = rememberSystemHaptics()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("chess_puzzle_prefs", android.content.Context.MODE_PRIVATE) }

    val epochDay = remember { LocalDate.now().toEpochDay() }
    var diff by remember { mutableIntStateOf(prefs.getInt("diff", 1).coerceIn(1, 3)) }
    var streak by remember { mutableIntStateOf(0) }
    var solvedSession by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableIntStateOf(0) }

    val puzzle: Puzzle = remember(daily, diff, cursor, epochDay) {
        if (daily) PuzzlePack.daily(epochDay)
        else {
            val pool = PuzzlePack.byDiff(diff).ifEmpty { PuzzlePack.ALL }
            pool[cursor % pool.size]
        }
    }

    var fen by remember(puzzle) { mutableStateOf(puzzle.fen) }
    val board = remember(fen) { Board.fromFen(fen) }
    val legal = remember(fen) { board.legalMoves() }
    var ply by remember(puzzle) { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(-1) }
    var pendingPromo by remember { mutableStateOf<List<Move>>(emptyList()) }
    var outcome by remember(puzzle) { mutableStateOf<String?>(null) } // SOLVED | FAILED
    var dailyDone by remember { mutableStateOf(false) }
    var anim by remember { mutableStateOf<AnimMove?>(null) }

    LaunchedEffect(daily) {
        if (daily) dailyDone = vm.puzzleRepoTodayDailySolved()
    }

    fun advanceLine() {
        // opponent reply per scripted line — slides like a real move
        if (ply + 1 < puzzle.line.size) {
            val reply = puzzle.line[ply + 1]
            // fresh legal list on the LIVE board — the remembered `legal` is stale mid-event
            val preFen = fen
            val mv = board.legalMoves().firstOrNull { it.uci() == reply }
            if (mv != null) {
                val pre = Board.fromFen(preFen)
                anim = AnimMove(mv.from, mv.to, pre.sq[mv.from], pre.sq[mv.to])
                board.play(mv)
            } else {
                board.playUci(reply)
            }
            fen = board.toFen()
            ply += 2
        } else {
            ply += 1
        }
    }

    fun onUserMove(m: Move) {
        val expected = puzzle.line.getOrNull(ply) ?: return
        if (m.uci() == expected) {
            val pre = Board.fromFen(fen)
            anim = AnimMove(m.from, m.to, pre.sq[m.from], pre.sq[m.to])
            board.play(m)
            fen = board.toFen()
            haptics.tick()
            if (m.promo != 0 || ply + 1 >= puzzle.line.size) {
                ply += 1
            } else {
                advanceLine()
            }
            if (ply >= puzzle.line.size) {
                outcome = "SOLVED"
                haptics.slam()
                solvedSession++
                streak++
                if (streak >= 3 && diff < 3) {
                    diff++; streak = 0
                    prefs.edit().putInt("diff", diff).apply()
                }
                vm.reportPuzzle(
                    kind = if (daily) "DAILY" else "PUZZLE",
                    mode = if (daily) "DAILY_CHALLENGE" else "PUZZLE",
                    result = "SOLVED", motif = puzzle.motif, diff = diff,
                )
                vm.refresh()
                if (daily) dailyDone = true
            }
        } else {
            outcome = "FAILED"
            haptics.error()
            streak = 0
            if (diff > 1) { diff--; prefs.edit().putInt("diff", diff).apply() }
            vm.reportPuzzle(
                kind = if (daily) "DAILY" else "PUZZLE",
                mode = if (daily) "DAILY_CHALLENGE" else "PUZZLE",
                result = "FAILED", motif = puzzle.motif, diff = diff,
            )
            vm.refresh()
        }
        selected = -1
        pendingPromo = emptyList()
    }

    fun next() {
        cursor++
        if (daily) return
        val p = if (daily) PuzzlePack.daily(epochDay) else run {
            val pool = PuzzlePack.byDiff(diff).ifEmpty { PuzzlePack.ALL }
            pool[(cursor) % pool.size]
        }
        fen = p.fen; ply = 0; outcome = null; selected = -1; pendingPromo = emptyList(); anim = null
    }

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            Row(Modifier.fillMaxWidth().padding(vertical = Grid.S12), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (daily) "DAILY CHALLENGE" else "PUZZLE TRAINING", color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("TIER ${"I".repeat(diff)} · ${puzzle.motif}", style = MonoLabel, color = SkyBlue)
                }
                if (!daily) Text("$solvedSession today", style = MonoLabel, color = LabelGray)
            }

            if (daily && dailyDone && outcome == null) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CHALLENGE CLEARED", color = SkyBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Next seed unlocks tomorrow.", style = MonoLabel, color = LabelGray)
                    Spacer(Modifier.height(14.dp))
                    NeonButton("PUZZLE TRAINING", { next(); }, Modifier.fillMaxWidth())
                }
                return@Column
            }

            Spacer(Modifier.height(4.dp))
            Text(
                if (outcome == "SOLVED") "PUZZLE SOLVED" else if (outcome == "FAILED") "LINE BROKEN — STUDY THE ANSWER" else puzzle.hint.uppercase(),
                color = when (outcome) { "SOLVED" -> SkyBlue; "FAILED" -> PaperWhite; else -> LabelGray },
                style = MonoLabel, modifier = Modifier.padding(bottom = 6.dp),
            )

            ChessBoard(
                board = board,
                myColor = 0,
                selected = selected,
                targets = remember(selected, legal) { if (selected < 0) emptySet() else targetsFor(legal, selected) },
                lastMove = null,
                anim = anim,
                onAnimDone = { anim = null },
                onSquare = onSquare@{ sq ->
                    if (outcome != null) return@onSquare
                    val piece = board.sq[sq]
                    if (selected >= 0) {
                        val options = legal.filter { it.from == selected && it.to == sq }
                        if (options.isNotEmpty()) {
                            if (options.any { it.promo != 0 }) pendingPromo = options else onUserMove(options.first())
                            return@onSquare
                        }
                    }
                    selected = if (piece != com.thesystem.app.chess.EMPTY &&
                        com.thesystem.app.chess.colorOf(piece) == 0) sq else -1
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (pendingPromo.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("PROMOTE:", style = MonoLabel, color = SkyBlue)
                    pendingPromo.forEach { m ->
                        GhostButton(
                            when (m.promo) {
                                com.thesystem.app.chess.QUEEN -> "Q"; com.thesystem.app.chess.ROOK -> "R"
                                com.thesystem.app.chess.BISHOP -> "B"; else -> "N"
                            }, { onUserMove(m) },
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            when (outcome) {
                "SOLVED" -> Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("BACK", onBack, Modifier.weight(1f))
                    if (!daily) NeonButton("NEXT", { haptics.select(); next() }, Modifier.weight(1f), color = SkyBlue)
                    else NeonButton("DONE", onBack, Modifier.weight(1f), color = SkyBlue)
                }
                "FAILED" -> Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("RETRY", {
                        fen = puzzle.fen; ply = 0; outcome = null; selected = -1; anim = null
                    }, Modifier.weight(1f))
                    if (!daily) NeonButton("NEXT", { haptics.select(); next() }, Modifier.weight(1f))
                    else NeonButton("DONE", onBack, Modifier.weight(1f))
                }
                else -> {
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                        GhostButton("ABANDON", onBack, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
