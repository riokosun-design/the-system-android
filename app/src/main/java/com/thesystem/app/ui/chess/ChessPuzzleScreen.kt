package com.thesystem.app.ui.chess

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Solve-green — functional color for the §2 success verdict (chat precedent). */
private val SolveGreen = Color(0xFF4ADE80)

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

/**
 * PUZZLE TRAINING — vC53 §2: the PuzzleDIRECTOR owns selection on the honest
 * 1–10 rating rail (49-engine-verified positions, persistent per-user
 * no-repeat via recorded history). Every slot: the opponent's last move
 * replays FIRST (setup animation), then "Your move!" + a live clock + a Hint
 * door. A wrong move never shows the old LINE BROKEN banner (DELETED by spec)
 * — the centered "There is a better move!" overlay governs retry-or-advance;
 * success lands the "Good moves!" overlay with the rating rail animation.
 */
@Composable
fun ChessPuzzleScreen(
    daily: Boolean,
    onBack: () -> Unit,
    vm: ChessViewModel = hiltViewModel(),
    onToggleDaily: (Boolean) -> Unit = {},
) {
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("chess_puzzle_prefs", Context.MODE_PRIVATE) }

    // vC55 §A4 — 500+ mega-pack (assets, decoded once per process) + the
    // lifetime never-repeat blacklist (DataStore local rail, server merges in)
    var pack by remember { mutableStateOf(PuzzlePack.ALL) }
    var solvedIds by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        pack = PuzzlePackCache.load(context)
        solvedIds = SolvedPuzzleVault.local(context)
        solvedIds = SolvedPuzzleVault.mergeRemote(context, vm.repoSolvedPuzzleIds())
    }
    val director = remember(pack, solvedIds) {
        PuzzleDirector(store = PrefsPuzzleStore(prefs), pack = pack, lifetimeSolved = solvedIds)
    }
    var sfxMuted by remember { mutableStateOf(ChessSfx.isMuted()) }

    val chessUi by vm.state.collectAsStateWithLifecycle()
    val practiceElo = chessUi.profile?.practiceElo ?: 400
    val epochDay = remember { LocalDate.now().toEpochDay() }

    // §3.2 — every slot draws a random difficulty 1–10 straight onto the pack's
    // honest rating rail; the DIRECTOR still owns anti-repeat inside the grade.
    var tierDraw by remember { mutableIntStateOf(0) }

    // The verdict is served ONCE per slot — never recomposed out from under an
    // in-progress attempt (profile arriving late must not swap the position).
    var verdict by remember { mutableStateOf<PuzzleDirector.Verdict?>(null) }
    LaunchedEffect(daily, practiceElo, pack, solvedIds) {
        if (verdict == null) {
            verdict = if (daily) {
                val p = PuzzlePack.daily(epochDay, pack, solvedIds)
                PuzzleDirector.Verdict(p, p.rating, "DAILY SEED · ONE SHOT PER DAY", 1, 1, false,
                    arenaCleared = pack.isNotEmpty() && pack.all { it.id in solvedIds })
            } else {
                tierDraw = kotlin.random.Random.nextInt(1, 11)
                director.nextInTier(tierDraw, currentId = null, reason = "DIFFICULTY $tierDraw/10")
            }
        }
    }

    fun next() {
        haptics.select()
        tierDraw = kotlin.random.Random.nextInt(1, 11)
        verdict = director.nextInTier(tierDraw, currentId = verdict?.puzzle?.id, reason = "DIFFICULTY $tierDraw/10")
    }

    var dailyDone by remember { mutableStateOf(false) }
    var playedThisSession by remember { mutableStateOf(false) }
    LaunchedEffect(daily) { if (daily) dailyDone = vm.puzzleRepoTodayDailySolved() }

    val puzzle = verdict?.puzzle

    SystemBackground {
        Column(Modifier
                .fillMaxSize()
                .navigationBarsPadding().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            Row(Modifier.fillMaxWidth().padding(vertical = Grid.S12), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (daily) "DAILY CHALLENGE" else "PUZZLE TRAINING", color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        verdict?.let { "RATING ${it.tier}/10 · ${it.puzzle.motif}" } ?: "LOADING",
                        style = MonoLabel, color = SkyBlue,
                    )
                }
                // §3 — sound covenant, same chip as every chess surface
                Text(
                    if (sfxMuted) "🔇" else "🔊",
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { sfxMuted = !sfxMuted; ChessSfx.setMuted(context, sfxMuted) }
                        .padding(horizontal = 8.dp),
                )
                // daily ↔ training switch (the hub no longer carries a daily
                // banner per the seven-card spec — the door lives here now)
                GhostButton(if (daily) "TRAINING" else "DAILY", { haptics.select(); onToggleDaily(!daily) })
            }

            if (daily && dailyDone && !playedThisSession) {
                // one shot per seed — an honest wall, never a looped replay
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CHALLENGE CLEARED", color = SkyBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Next seed unlocks tomorrow. The training pool stays open.", style = MonoLabel, color = LabelGray)
                    Spacer(Modifier.height(14.dp))
                    NeonButton("TRAINING POOL", { onToggleDaily(false) }, Modifier.fillMaxWidth(), color = SkyBlue)
                    Spacer(Modifier.height(8.dp))
                    GhostButton("BACK TO HUB", onBack, Modifier.fillMaxWidth())
                }
                return@Column
            }

            if (verdict?.arenaCleared == true) {
                // §A4 — the whole pack stands conquered FOR LIFE. An honest
                // wall: the vault never serves a corpse, not even once.
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ARENA CLEARED", color = SolveGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "All ${solvedIds.size} puzzles conquered for life.\nA solved puzzle never returns — on any device, ever.",
                        style = MonoLabel, color = LabelGray,
                    )
                    Spacer(Modifier.height(14.dp))
                    GhostButton("BACK TO HUB", onBack, Modifier.fillMaxWidth())
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
                practiceElo = practiceElo,
                header = {
                    // honest progression readout: WHY this rating + cycle position
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
                    if (solved && puzzle.id !in solvedIds) {
                        // §A4 — lifetime blacklist, dual rail: local DataStore
                        // NOW (instant), Supabase stamp behind it (durable)
                        solvedIds = solvedIds + puzzle.id
                        scope.launch { SolvedPuzzleVault.stamp(context, puzzle.id) }
                        vm.stampSolvedPuzzle(puzzle.id, solveMs)
                    }
                    vm.reportPuzzle(
                        kind = if (daily) "DAILY" else "PUZZLE",
                        mode = if (daily) "DAILY_CHALLENGE" else "PUZZLE",
                        result = if (solved) "SOLVED" else "FAILED",
                        motif = puzzle.motif, diff = verdict?.tier ?: puzzle.rating,
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

private enum class PuzzlePhase { SETUP, USER }

/**
 * One puzzle slot — vC53 §2 flow: setup replay → "Your move!" + live clock +
 * Hint door → strict-line verdict. State keys to [key]; a NEW puzzle id
 * resets everything (NEXT), RETRY replays the same slot from the top.
 */
@Composable
private fun PuzzleBoard(
    key: Puzzle,
    practiceElo: Int,
    header: @Composable () -> Unit,
    onOutcome: (solved: Boolean, solveMs: Long) -> Unit,
    onBack: () -> Unit,
    onNext: (() -> Unit)?,
    daily: Boolean,
) {
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    val puzzle = key

    var fen by remember(puzzle) { mutableStateOf(puzzle.fen) }
    val board = remember(fen) { Board.fromFen(fen) }
    val legal = remember(fen) { board.legalMoves() }
    var ply by remember(puzzle) { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(-1) }
    var pendingPromo by remember { mutableStateOf<List<Move>>(emptyList()) }
    var outcome by remember(puzzle) { mutableStateOf<String?>(null) } // SOLVED | FAILED
    var anim by remember { mutableStateOf<AnimMove?>(null) }
    var attempt by remember(puzzle) { mutableIntStateOf(0) }
    var phase by remember(puzzle) { mutableStateOf(if (puzzle.setup == null) PuzzlePhase.USER else PuzzlePhase.SETUP) }
    var shownAt by remember(puzzle) { mutableLongStateOf(0L) }
    var elapsedSec by remember(puzzle) { mutableLongStateOf(0L) }
    var hintShown by remember(puzzle) { mutableStateOf(false) }

    // §2 — the opponent's last move (the blunder/setup) replays FIRST with
    // the same 190ms slide as real play; only then does the hunter clock in.
    LaunchedEffect(puzzle, attempt) {
        val s = puzzle.setup
        if (s != null) {
            delay(620)
            val mv = board.legalMoves().firstOrNull { it.uci() == s }
            if (mv != null) {
                val pre = Board.fromFen(board.toFen())
                anim = AnimMove.of(mv.from, mv.to, pre.sq[mv.from], pre.sq[mv.to])
                board.make(mv)
                fen = board.toFen()              // board lands; the 190ms slide rides on top
                ChessSfx.play(if (pre.sq[mv.to] != com.thesystem.app.chess.EMPTY) ChessSfx.Sfx.CAPTURE else ChessSfx.Sfx.MOVE)
                delay(340)                       // prompt only after the slide has landed
            }
        }
        shownAt = System.currentTimeMillis()
        elapsedSec = 0L
        phase = PuzzlePhase.USER
    }

    // §2 — live timer, honest: counts the hunter's own thinking window.
    LaunchedEffect(phase, outcome) {
        if (phase != PuzzlePhase.USER || outcome != null) return@LaunchedEffect
        while (true) {
            delay(1000)
            if (shownAt > 0) elapsedSec = (System.currentTimeMillis() - shownAt) / 1000
        }
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
                anim = AnimMove.of(mv.from, mv.to, pre.sq[mv.from], pre.sq[mv.to])
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
        hintShown = false
        phase = if (puzzle.setup == null) PuzzlePhase.USER else PuzzlePhase.SETUP
        attempt += 1                   // re-arms the setup replay + clock
    }

    fun onUserMove(m: Move) {
        val expected = puzzle.line.getOrNull(ply) ?: return
        if (m.uci() == expected) {
            val pre = Board.fromFen(fen)
            anim = AnimMove.of(m.from, m.to, pre.sq[m.from], pre.sq[m.to])
            board.play(m)
            fen = board.toFen()
            haptics.tick()
            ChessSfx.play(if (pre.sq[m.to] != com.thesystem.app.chess.EMPTY) ChessSfx.Sfx.CAPTURE else ChessSfx.Sfx.MOVE)
            if (m.promo != 0 || ply + 1 >= puzzle.line.size) {
                ply += 1
            } else {
                advanceLine()
            }
            if (ply >= puzzle.line.size) {
                outcome = "SOLVED"
                haptics.slam()
                ChessSfx.play(ChessSfx.Sfx.VICTORY)
                ChessSfx.buzz(context, 90, 190)
                onOutcome(true, System.currentTimeMillis() - shownAt)
            }
        } else {
            // §2 strict line law: any deviation fails — verdict rides the overlay
            outcome = "FAILED"
            haptics.error()
            ChessSfx.play(ChessSfx.Sfx.PUZZLE_FAIL)
            ChessSfx.buzz(context, 130, 200)
            onOutcome(false, System.currentTimeMillis() - shownAt)
        }
        selected = -1
        pendingPromo = emptyList()
    }

    val expectedFrom: Int = puzzle.line.getOrNull(ply)?.let { uci ->
        legal.firstOrNull { it.uci() == uci }?.from ?: -1
    } ?: -1

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            header()
            // §2 status ribbon — prompt + live clock + hint door
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        phase == PuzzlePhase.SETUP -> "WATCH THE LAST MOVE…"
                        outcome == "SOLVED" -> "PUZZLE SOLVED"
                        outcome == "FAILED" -> "NOT THE LINE"
                        else -> if (hintShown) puzzle.hint.uppercase() else "Your move!"
                    },
                    color = when (outcome) { "SOLVED" -> SolveGreen; "FAILED" -> PaperWhite; else -> if (phase == PuzzlePhase.SETUP) LabelGray else SkyBlue },
                    style = MonoLabel,
                )
                Spacer(Modifier.weight(1f))
                if (phase == PuzzlePhase.USER && outcome == null) {
                    Text(
                        "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60),
                        style = MonoLabel, color = LabelGray,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Hint",
                        style = MonoLabel, color = if (hintShown) FaintGray else SkyBlue,
                        modifier = Modifier
                            .border(1.dp, if (hintShown) LineSoft else SkyBlue, RoundedCornerShape(6.dp))
                            .clickable(enabled = !hintShown) { haptics.select(); hintShown = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            // same optical-center law as the duel screen: board floats mid-viewport
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val boardSide = minOf(maxWidth, maxHeight, 400.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ChessBoard(
                        board = board,
                        myColor = 0,
                        selected = selected,
                        targets = remember(selected, legal) { if (selected < 0) emptySet() else targetsFor(legal, selected) },
                        lastMove = null,
                        // §2 — the Hint glows the intended origin square, never the target
                        squareTints = if (hintShown && expectedFrom >= 0 && outcome == null) mapOf(expectedFrom to SkyBlueDim) else emptyMap(),
                        anim = anim,
                        onAnimDone = { anim = null },
                        draggable = if (phase == PuzzlePhase.USER && outcome == null) legal.map { it.from }.toSet() else emptySet(),
                        onDrop = drop@{ from, to ->
                            if (phase != PuzzlePhase.USER || outcome != null) return@drop
                            val options = legal.filter { it.from == from && it.to == to }
                            if (options.isEmpty()) return@drop
                            if (options.any { it.promo != 0 }) pendingPromo = options else onUserMove(options.first())
                        },
                        onSquare = onSquare@{ sq ->
                            if (phase != PuzzlePhase.USER || outcome != null) return@onSquare
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
                        modifier = Modifier.size(boardSide),
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
                }
            }

            if (outcome == null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    GhostButton("ABANDON", onBack, Modifier.fillMaxWidth())
                }
            } else {
                // verdict pills live INSIDE the overlay — bottom row yields space
                Spacer(Modifier.height(14.dp))
            }
        }

        // ── §2 VERDICT OVERLAYS (the LINE BROKEN banner is DEAD by spec) ──────
        if (outcome == "FAILED") {
            OverlayScrim {
                VerdictCard {
                    Text(
                        "There is a better move!",
                        color = PaperWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Repeat or move on to the next puzzle.",
                        style = MonoLabel, color = LabelGray,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("↻ Repeat", { retry() })
                        NeonButton("Next →", { onNext?.invoke() ?: onBack() }, color = SkyBlue)
                    }
                }
            }
        } else if (outcome == "SOLVED") {
            OverlayScrim {
                VerdictCard {
                    SolveFlourish(practiceElo)
                    Spacer(Modifier.height(14.dp))
                    NeonButton("Next →", { onNext?.invoke() ?: onBack() }, Modifier.fillMaxWidth(), color = SolveGreen)
                }
            }
        }
    }
}

/** Absorbing dim — taps die here while a verdict is on deck. */
@Composable
private fun OverlayScrim(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(                        // swallow taps; pills own control
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun VerdictCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth(0.86f)
            .border(1.dp, LineStrong, RoundedCornerShape(14.dp))
            .background(Color(0xF20A0A0F), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** §2 "Good moves!" — green ✓ bloom, floating "+1 SOLVE" gain, animated
 *  practice-rating rail toward the next 100-Elo milestone (honest: reads the
 *  server-stamped Elo, server still owns every point granted). */
@Composable
private fun SolveFlourish(practiceElo: Int) {
    val tick = remember { Animatable(0f) }
    val floaty = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        tick.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        floaty.animateTo(1f, tween(1150, easing = FastOutSlowInEasing))
    }
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(54.dp)) {
            drawCircle(SolveGreen.copy(alpha = 0.16f * tick.value))
            drawCircle(SolveGreen.copy(alpha = 0.9f), radius = size.minDimension / 2 * 0.82f * tick.value, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            // the ✓
            val s = size.minDimension
            val p = tick.value
            if (p > 0.35f) {
                drawLine(
                    SolveGreen,
                    androidx.compose.ui.geometry.Offset(s * 0.28f, s * 0.52f),
                    androidx.compose.ui.geometry.Offset(s * 0.28f + s * 0.16f * ((p - 0.35f) / 0.3f).coerceIn(0f, 1f), s * 0.52f + s * 0.16f * ((p - 0.35f) / 0.3f).coerceIn(0f, 1f)),
                    strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            if (p > 0.6f) {
                val q = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
                drawLine(
                    SolveGreen,
                    androidx.compose.ui.geometry.Offset(s * 0.44f, s * 0.68f),
                    androidx.compose.ui.geometry.Offset(s * 0.44f + s * 0.30f * q, s * 0.68f - s * 0.34f * q),
                    strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
        Text(
            "+1 SOLVE LOGGED",
            style = MonoLabel, color = SolveGreen,
            modifier = Modifier.graphicsLayer {
                translationY = -floaty.value * 110f
                alpha = if (floaty.value > 0.7f) (1f - floaty.value) / 0.3f else 1f
            },
        )
    }
    Spacer(Modifier.height(10.dp))
    Text("Good moves!", color = PaperWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(10.dp))
    // rating rail — animated progress toward the next milestone
    val milestone = (practiceElo / 100 + 1) * 100
    val frac = ((practiceElo % 100) / 100f).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(LineSoft, RoundedCornerShape(3.dp)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(frac * tick.value)
                .background(SolveGreen, RoundedCornerShape(3.dp)),
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "PRACTICE ELO $practiceElo → NEXT MARK $milestone · POINTS STAMPED SERVER-SIDE",
        style = MonoLabel, color = LabelGray,
    )
}
