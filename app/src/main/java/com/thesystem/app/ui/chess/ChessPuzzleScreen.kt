package com.thesystem.app.ui.chess

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.chess.Board
import com.thesystem.app.chess.Move
import com.thesystem.app.chess.Puzzle
import com.thesystem.app.chess.PuzzleDirector
import com.thesystem.app.chess.PuzzlePack
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import java.time.LocalDate

/** Tiny SharedPrefs adapter — the director stays android-free; the screen owns I/O. */
private class PrefsPuzzleStore(private val prefs: android.content.SharedPreferences) : PuzzleDirector.Store {
    override fun loadRecords(): List<PuzzleDirector.Record> =
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
            ?.split(";")
            ?.mapNotNull { tok ->
                val f = tok.split("|")
                if (f.size == 4) PuzzleDirector.Record(
                    id = f[0],
                    solved = f[1] == "1",
                    solveMs = f[2].toLongOrNull() ?: 0L,
                    day = f[3].toLongOrNull() ?: 0L,
                ) else null
            } ?: emptyList()

    override fun saveRecords(records: List<PuzzleDirector.Record>) {
        prefs.edit().putString(
            KEY,
            records.joinToString(";") { "${it.id}|${if (it.solved) "1" else "0"}|${it.solveMs}|${it.day}" },
        ).apply()
    }

    private companion object { const val KEY = "director_records" }
}

private fun roman(t: Int) = when (t) { 1 -> "I"; 2 -> "II"; else -> "III" }

/**
 * PUZZLE TRAINING (§14) — the PuzzleDIRECTOR owns selection: an anti-repeat
 * quarantine + cycle-exhaustion law makes the old "same puzzle over and over"
 * bug structurally impossible, and tier = f(practice Elo, rolling solve rate,
 * measured solve time). This screen only renders verdicts and records
 * attempts. Daily challenge = one date-seeded shot, also recorded so the
 * training pool never re-serves it back-to-back.
 */
@Composable
fun ChessPuzzleScreen(
    daily: Boolean,
    onBack: () -> Unit,
    vm: ChessViewModel = hiltViewModel(),
) {
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("chess_puzzle_prefs", Context.MODE_PRIVATE) }
    val director = remember { PuzzleDirector(store = PrefsPuzzleStore(prefs)) }

    val chessUi by vm.state.collectAsStateWithLifecycle()
    val practiceElo = chessUi.profile?.practiceElo ?: 400
    val epochDay = remember { LocalDate.now().toEpochDay() }

    // The verdict is served ONCE per slot — never recomposed out from under an
    // in-progress attempt (profile arriving late must not swap the position).
    var verdict by remember { mutableStateOf<PuzzleDirector.Verdict?>(null) }
    LaunchedEffect(daily, practiceElo) {
        if (verdict == null) {
            verdict = if (daily) {
                val p = PuzzlePack.daily(epochDay)
                PuzzleDirector.Verdict(p, p.diff, "DAILY SEED · ONE SHOT PER DAY", 1, 1, false)
            } else {
                director.next(currentId = null, practiceElo = practiceElo)
            }
        }
    }

    fun next() {
        haptics.select()
        verdict = director.next(currentId = verdict?.puzzle?.id, practiceElo = practiceElo)
    }

    var dailyDone by remember { mutableStateOf(false) }
    var playedThisSession by remember { mutableStateOf(false) }
    LaunchedEffect(daily) { if (daily) dailyDone = vm.puzzleRepoTodayDailySolved() }

    val puzzle = verdict?.puzzle

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            Row(Modifier.fillMaxWidth().padding(vertical = Grid.S12), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (daily) "DAILY CHALLENGE" else "PUZZLE TRAINING", color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        verdict?.let { "TIER ${roman(it.tier)} · ${it.puzzle.motif}" } ?: "LOADING",
                        style = MonoLabel, color = SkyBlue,
                    )
                }
            }

            if (daily && dailyDone && !playedThisSession) {
                // one shot per seed — an honest wall, never a looped replay
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CHALLENGE CLEARED", color = SkyBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Next seed unlocks tomorrow. The training pool stays open below the hub.", style = MonoLabel, color = LabelGray)
                    Spacer(Modifier.height(14.dp))
                    NeonButton("BACK TO HUB", onBack, Modifier.fillMaxWidth(), color = SkyBlue)
                }
                return@Column
            }

            if (puzzle == null) {
                // selection is local + instant — this frame exists at most once
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                    SystemProcessing("DIRECTOR SELECTING", compact = true)
                }
                return@Column
            }

            PuzzleBoard(
                key = puzzle,
                header = {
                    // honest progression readout: WHY this tier + cycle position
                    verdict?.let { v ->
                        if (!daily) {
                            Text(v.tierReason, style = MonoLabel, color = LabelGray)
                            Text(
                                "PUZZLE ${v.cyclePosition} OF ${v.cycleSize} IN CYCLE" + if (v.freshCycle) " · POOL CLEARED — NEW PASS" else "",
                                style = MonoLabel, color = LabelGray,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                },
                onOutcome = { solved, solveMs ->
                    playedThisSession = true
                    director.record(puzzle.id, solved, solveMs, epochDay)   // the single write path
                    vm.reportPuzzle(
                        kind = if (daily) "DAILY" else "PUZZLE",
                        mode = if (daily) "DAILY_CHALLENGE" else "PUZZLE",
                        result = if (solved) "SOLVED" else "FAILED",
                        motif = puzzle.motif, diff = verdict?.tier ?: puzzle.diff,
                    )
                    vm.refresh()
                    if (daily && solved) dailyDone = true
                },
                onBack = onBack,
                onNext = if (daily) null else ::next,
                daily = daily,
            )
        }
    }
}

/**
 * One puzzle slot — board, scripted replies, outcome flow. State is keyed to
 * [key]; a NEW puzzle id auto-resets everything (NEXT), the same id keeps
 * everything (RETRY composes around it).
 */
@Composable
private fun PuzzleBoard(
    key: Puzzle,
    header: @Composable () -> Unit,
    onOutcome: (solved: Boolean, solveMs: Long) -> Unit,
    onBack: () -> Unit,
    onNext: (() -> Unit)?,
    daily: Boolean,
) {
    val haptics = rememberSystemHaptics()
    val puzzle = key

    var fen by remember(puzzle) { mutableStateOf(puzzle.fen) }
    val board = remember(fen) { Board.fromFen(fen) }
    val legal = remember(fen) { board.legalMoves() }
    var ply by remember(puzzle) { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(-1) }
    var pendingPromo by remember { mutableStateOf<List<Move>>(emptyList()) }
    var outcome by remember(puzzle) { mutableStateOf<String?>(null) } // SOLVED | FAILED
    var anim by remember { mutableStateOf<AnimMove?>(null) }
    val shownAt = remember(puzzle) { System.currentTimeMillis() }

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

    fun retry() {
        fen = puzzle.fen; ply = 0; outcome = null; selected = -1; pendingPromo = emptyList(); anim = null
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
                onOutcome(true, System.currentTimeMillis() - shownAt)
            }
        } else {
            outcome = "FAILED"
            haptics.error()
            onOutcome(false, System.currentTimeMillis() - shownAt)
        }
        selected = -1
        pendingPromo = emptyList()
    }

    Column(Modifier.fillMaxWidth()) {
        header()
        Text(
            when (outcome) {
                "SOLVED" -> "PUZZLE SOLVED"
                "FAILED" -> "LINE BROKEN — STUDY THE ANSWER, THEN RETRY"
                else -> puzzle.hint.uppercase()
            },
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
                if (onNext != null) NeonButton("NEXT", onNext, Modifier.weight(1f), color = SkyBlue)
                else NeonButton("DONE", onBack, Modifier.weight(1f), color = SkyBlue)
            }
            "FAILED" -> Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // a miss is already recorded once — retry is free practice, not a re-roll
                GhostButton("RETRY", { retry() }, Modifier.weight(1f))
                if (onNext != null) NeonButton("NEXT", onNext, Modifier.weight(1f))
                else NeonButton("DONE", onBack, Modifier.weight(1f))
            }
            else -> Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                GhostButton("ABANDON", onBack, Modifier.fillMaxWidth())
            }
        }
    }
}
