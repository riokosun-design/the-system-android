package com.thesystem.app.ui.chess

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.thesystem.app.chess.BLACK_BIT
import com.thesystem.app.chess.Board
import com.thesystem.app.chess.ChessAI
import com.thesystem.app.chess.EMPTY
import com.thesystem.app.chess.Move
import com.thesystem.app.chess.PAWN
import com.thesystem.app.chess.colorOf
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** Modes that allow takeback — training grounds, never rated / timed combat. */
private val UNDO_MODES = setOf(
    ChessMode.AI_TRAINING, ChessMode.CLASSICAL, ChessMode.ENDGAME_TRAINING, ChessMode.OPENING_TRAINING,
)

/** Material values for the capture-strip advantage readout. */
private val PIECE_VALUE = mapOf(
    com.thesystem.app.chess.PAWN to 1, com.thesystem.app.chess.KNIGHT to 3,
    com.thesystem.app.chess.BISHOP to 3, com.thesystem.app.chess.ROOK to 5,
    com.thesystem.app.chess.QUEEN to 9, com.thesystem.app.chess.KING to 0,
)

/**
 * CHESS GAME — a real board on the System HUD: vector pieces, slide animation,
 * undo where the mode permits, difficulty control, thinking indicator, move
 * log, captured strips, deterministic clocks. MENTAL WAR still runs the full
 * post-game analysis and hands every number to the server (mig 018) — the
 * client never invents XP or rating.
 */
@Composable
fun ChessGameScreen(
    modeName: String?,
    onBack: () -> Unit,
    vm: ChessViewModel = hiltViewModel(),
) {
    val mode = remember(modeName) { ChessMode.entries.firstOrNull { it.name == modeName } ?: ChessMode.AI_TRAINING }
    val haptics = rememberSystemHaptics()
    val epochDay = remember { LocalDate.now().toEpochDay() }
    val (startFen, userWhite) = remember(mode) { modeStartFen(mode, epochDay) }

    var diff by remember(mode) { mutableStateOf(mode.diff ?: ChessAI.Difficulty.MEDIUM) }
    var aiTrainingUnlocked by remember(mode) { mutableStateOf(mode != ChessMode.AI_TRAINING) }

    var fen by remember { mutableStateOf(startFen) }
    val board = remember(fen) { Board.fromFen(fen) }
    val legal = remember(fen) { board.legalMoves() }

    var selected by remember { mutableStateOf(-1) }
    var pendingPromo by remember { mutableStateOf<List<Move>>(emptyList()) }
    var lastMove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var anim by remember { mutableStateOf<AnimMove?>(null) }
    var aiThinking by remember { mutableStateOf(false) }

    val history = remember { mutableStateListOf(startFen) }
    val playedMoves = remember { mutableStateListOf<Move>() }
    val undos = remember { mutableListOf<Pair<Move, Board.Undo>>() }
    val thinkMs = remember { mutableStateListOf<Long>() }
    val sanLog = remember { mutableStateListOf<String>() }
    val capsPerMove = remember { mutableListOf<Int>() }
    val userCaps = remember { mutableStateListOf<Int>() }
    val oppCaps = remember { mutableStateListOf<Int>() }
    var userMoveStartedAt by remember { mutableStateOf(0L) }

    var whiteMs by remember { mutableLongStateOf(mode.clockSec * 1000L) }
    var blackMs by remember { mutableLongStateOf(mode.clockSec * 1000L) }

    var result by remember { mutableStateOf<String?>(null) } // WIN | LOSS | DRAW (hunter view)
    var resultReason by remember { mutableStateOf("") }
    var logged by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<GameAnalysis?>(null) }
    var logOutcome by remember { mutableStateOf<com.thesystem.app.data.repo.ChessRepository.LogResult?>(null) }

    val userColorIdx = if (userWhite) 0 else 1
    val myMs = if (userWhite) whiteMs else blackMs
    val oppMs = if (userWhite) blackMs else whiteMs
    val undoAllowed = mode in UNDO_MODES

    fun finish(res: String, reason: String) {
        if (result != null) return
        result = res; resultReason = reason
        if (res == "WIN") haptics.slam() else if (res == "LOSS") haptics.error()
    }

    fun pushMove(m: Move) {
        val pre = Board.fromFen(fen)                    // for SAN + capture record
        val movingPiece = pre.sq[m.from]
        val mover = colorOf(movingPiece)
        val cap = if (m.flag == 1) PAWN or (if (mover == 0) BLACK_BIT else 0) else pre.sq[m.to]
        anim = AnimMove(m.from, m.to, movingPiece, cap)
        val u = board.make(m)
        undos.add(m to u)
        playedMoves.add(m)
        capsPerMove.add(cap)
        if (cap != EMPTY) (if (mover == userColorIdx) userCaps else oppCaps).add(cap)
        sanLog.add(sanText(pre, m, board))
        fen = board.toFen()
        history.add(fen)
        lastMove = m.from to m.to
        selected = -1
        pendingPromo = emptyList()
        val sideAfter = if (board.whiteToMove) 0 else 1
        if (mode.clockSec > 0 && mode.incSec > 0) {
            if (sideAfter == 1) whiteMs += mode.incSec * 1000L else blackMs += mode.incSec * 1000L
        }
        val (st, _) = board.status()
        when (st) {
            Board.Status.CHECKMATE -> finish(if (sideAfter == userColorIdx) "LOSS" else "WIN", "CHECKMATE")
            Board.Status.STALEMATE -> finish("DRAW", "STALEMATE")
            Board.Status.DRAW_50 -> finish("DRAW", "FIFTY-MOVE RULE")
            Board.Status.DRAW_MATERIAL -> finish("DRAW", "INSUFFICIENT MATERIAL")
            Board.Status.ONGOING -> {}
        }
    }

    /** ↶ UNDO — revert the hunter's last move (and the engine reply) where the mode allows. */
    fun undoTurn() {
        if (!undoAllowed || result != null || aiThinking) return
        val sideToMove = if (board.whiteToMove) 0 else 1
        if (sideToMove != userColorIdx) return
        var popped = 0
        while (undos.isNotEmpty() && popped < 2) {
            val (m, u) = undos.removeAt(undos.size - 1)
            board.unmake(m, u)
            playedMoves.removeAt(playedMoves.size - 1)
            history.removeAt(history.size - 1)
            sanLog.removeAt(sanLog.size - 1)
            val cap = capsPerMove.removeAt(capsPerMove.size - 1)
            val mover = colorOf(board.sq[m.from])          // piece restored to origin
            if (cap != EMPTY) {
                val bag = if (mover == userColorIdx) userCaps else oppCaps
                bag.removeAt(bag.lastIndexOf(cap))
            }
            if (mover == userColorIdx && thinkMs.isNotEmpty()) thinkMs.removeAt(thinkMs.size - 1)
            popped++
        }
        haptics.tick()
        selected = -1; pendingPromo = emptyList()
        anim = null                      // takeback snaps — never replays a stale slide
        lastMove = playedMoves.lastOrNull()?.let { it.from to it.to }
        fen = board.toFen()
    }

    fun resetGame(keepUnlock: Boolean) {
        fen = Board.fromFen(startFen).toFen()
        history.clear(); history.add(startFen)
        playedMoves.clear(); undos.clear(); thinkMs.clear()
        sanLog.clear(); capsPerMove.clear(); userCaps.clear(); oppCaps.clear()
        selected = -1; pendingPromo = emptyList(); lastMove = null; anim = null
        whiteMs = mode.clockSec * 1000L; blackMs = mode.clockSec * 1000L
        result = null; resultReason = ""; logged = false
        analysis = null; logOutcome = null
        aiThinking = false
        aiTrainingUnlocked = keepUnlock || mode != ChessMode.AI_TRAINING
    }

    // engine turn
    LaunchedEffect(fen, result, aiTrainingUnlocked) {
        if (!aiTrainingUnlocked || result != null) return@LaunchedEffect
        val engineTurn = (if (board.whiteToMove) 0 else 1) != userColorIdx
        if (!engineTurn) {
            userMoveStartedAt = System.currentTimeMillis()
            return@LaunchedEffect
        }
        if (board.status().first != Board.Status.ONGOING) return@LaunchedEffect
        aiThinking = true
        delay(350)
        val mv = withContext(Dispatchers.Default) { ChessAI.bestMove(board, diff) }
        aiThinking = false
        if (mv != null && result == null) pushMove(mv)
    }

    // deterministic clocks
    LaunchedEffect(result, aiTrainingUnlocked) {
        if (mode.clockSec <= 0 || !aiTrainingUnlocked) return@LaunchedEffect
        while (result == null) {
            delay(200)
            if (board.whiteToMove) whiteMs = (whiteMs - 200).coerceAtLeast(0) else blackMs = (blackMs - 200).coerceAtLeast(0)
            if (whiteMs <= 0L) finish(if (userWhite) "LOSS" else "WIN", "FLAG FALL")
            else if (blackMs <= 0L) finish(if (userWhite) "WIN" else "LOSS", "FLAG FALL")
        }
    }

    // post-game: analysis (war) → server log (every game)
    LaunchedEffect(result) {
        val res = result ?: return@LaunchedEffect
        if (logged) return@LaunchedEffect
        logged = true
        if (mode.war) {
            analysis = withContext(Dispatchers.Default) {
                analyzeGame(history, playedMoves, thinkMs, userColorIdx, userWon = res == "WIN", lost = res == "LOSS")
            }
        }
        val a = analysis
        vm.repoLog(
            kind = "GAME", mode = mode.name, result = res,
            accuracy = a?.accuracy, blunders = a?.blunders ?: 0,
            thinkMs = a?.avgThinkMs ?: 0,
            stats = a?.toStatJson(), analysis = a?.toAnalysisJson(),
        ).onSuccess { logOutcome = it }
        vm.refresh()
    }

    SystemBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Grid.Margin)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── HUD header ───────────────────────────────────────────────
            Row(Modifier.fillMaxWidth().padding(vertical = Grid.S12), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(mode.label, color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        if (mode == ChessMode.AI_TRAINING) "ENGINE ${diff.name}" else mode.sub.uppercase(),
                        style = MonoLabel, color = SkyBlue,
                    )
                }
                if (mode.clockSec > 0) ClockChip(oppMs, active = !board.whiteToMove == (if (userWhite) false else true) && result == null)
            }

            if (!aiTrainingUnlocked) {
                // AI TRAINING — level select gates the duel
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("CHOOSE ENGINE LEVEL", color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(14.dp))
                    ChessAI.Difficulty.entries.forEach { d ->
                        NeonButton(
                            d.name, {
                                haptics.select(); diff = d; aiTrainingUnlocked = true
                                whiteMs = mode.clockSec * 1000L; blackMs = mode.clockSec * 1000L
                            },
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = if (d == ChessAI.Difficulty.HARD) SkyBlue else PaperWhite,
                        )
                    }
                }
                return@Column
            }

            // ── engine strip: tag + its captures + clock ─────────────────
            PlayerTag(
                label = "SYSTEM ENGINE · ${diff.name}",
                ms = oppMs, timed = mode.clockSec > 0,
                captures = oppCaps,
                materialPlus = materialOf(oppCaps) - materialOf(userCaps),
            )

            // AI thinking indicator + difficulty control (training mode)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (aiThinking && result == null) {
                    SystemProcessing("SYSTEM COMPUTING", compact = true)
                } else if (mode == ChessMode.AI_TRAINING) {
                    DifficultyChips(current = diff, enabled = result == null && !aiThinking) { d -> diff = d }
                }
            }

            // ── board ────────────────────────────────────────────────────
            val inCheck = board.inCheck(board.whiteToMove)
            ChessBoard(
                board = board,
                myColor = userColorIdx,
                selected = selected,
                targets = remember(selected, legal) { if (selected < 0) emptySet() else targetsFor(legal, selected) },
                lastMove = lastMove,
                checkSquare = if (result == null && inCheck) (if (board.whiteToMove) board.wKing else board.bKing) else -1,
                anim = anim,
                onAnimDone = { anim = null },
                onSquare = onSquare@{ sq ->
                    if (result != null || aiThinking) return@onSquare
                    val myTurn = (if (board.whiteToMove) 0 else 1) == userColorIdx
                    if (!myTurn) return@onSquare
                    val piece = board.sq[sq]
                    if (selected >= 0) {
                        val options = legal.filter { it.from == selected && it.to == sq }
                        if (options.isNotEmpty()) {
                            if (options.any { it.promo != 0 }) {
                                pendingPromo = options
                            } else {
                                if (userMoveStartedAt > 0) thinkMs.add(System.currentTimeMillis() - userMoveStartedAt)
                                haptics.tick()
                                pushMove(options.first())
                            }
                            return@onSquare
                        }
                    }
                    selected = if (piece != EMPTY && colorOf(piece) == userColorIdx) sq else -1
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (pendingPromo.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("PROMOTE:", style = MonoLabel, color = SkyBlue)
                    pendingPromo.forEach { m ->
                        GhostButton(
                            when (m.promo) {
                                com.thesystem.app.chess.QUEEN -> "Q"; com.thesystem.app.chess.ROOK -> "R"
                                com.thesystem.app.chess.BISHOP -> "B"; else -> "N"
                            },
                            {
                                if (userMoveStartedAt > 0) thinkMs.add(System.currentTimeMillis() - userMoveStartedAt)
                                pushMove(m)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── hunter strip: tag + captures + clock ─────────────────────
            PlayerTag(
                label = "YOU · ${if (userWhite) "WHITE" else "BLACK"}",
                ms = myMs, timed = mode.clockSec > 0,
                captures = userCaps,
                materialPlus = materialOf(userCaps) - materialOf(oppCaps),
            )

            // status ribbon — turn indicator + check flag
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    result != null -> Text("GAME OVER · $resultReason", style = MonoLabel, color = PaperWhite)
                    inCheck && (if (board.whiteToMove) 0 else 1) == userColorIdx ->
                        Text("CHECK — YOUR MOVE", color = PaperWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = SystemMono)
                    inCheck -> Text("CHECK", color = SkyBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = SystemMono)
                    (if (board.whiteToMove) 0 else 1) == userColorIdx && !aiThinking ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(6.dp)) { drawRect(SkyBlue) }
                            Spacer(Modifier.width(6.dp))
                            Text("YOUR MOVE", style = MonoLabel, color = SkyBlue)
                        }
                    else -> Text("SYSTEM TO MOVE", style = MonoLabel, color = LabelGray)
                }
            }

            // ── move log ────────────────────────────────────────────────
            if (sanLog.isNotEmpty()) {
                val logScroll = rememberScrollState()
                LaunchedEffect(sanLog.size) { logScroll.scrollTo(logScroll.maxValue) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(logScroll)
                        .border(1.dp, LineSoft, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    var i = 0
                    while (i < sanLog.size) {
                        Text(
                            "${i / 2 + 1}. ${sanLog[i]}${if (i + 1 < sanLog.size) "  ${sanLog[i + 1]}" else ""}",
                            color = LabelGray, fontSize = 11.sp, fontFamily = SystemMono,
                        )
                        i += 2
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (result == null) {
                // ── controls: undo where permitted · new game · resign · back ──
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (undoAllowed) {
                        val canUndo = !aiThinking && undos.isNotEmpty() &&
                            (if (board.whiteToMove) 0 else 1) == userColorIdx
                        GhostButton("↶ UNDO", { undoTurn() }, Modifier.weight(1f), enabled = canUndo)
                    }
                    GhostButton("NEW", { resetGame(keepUnlock = true) }, Modifier.weight(1f))
                    GhostButton("RESIGN", { finish("LOSS", "RESIGNED") }, Modifier.weight(1f))
                    GhostButton("BACK", onBack, Modifier.weight(1f))
                }
            } else {
                ResultPanel(
                    mode = mode,
                    result = result!!,
                    reason = resultReason,
                    analysis = analysis,
                    logged = logOutcome,
                    onRematch = { resetGame(keepUnlock = false) },
                    onBack = onBack,
                )
            }
        }
    }
}

private fun materialOf(caps: List<Int>): Int = caps.sumOf { PIECE_VALUE[com.thesystem.app.chess.typeOf(it)] ?: 0 }

/** Mid-game engine level control — present, never dominant. */
@Composable
private fun DifficultyChips(current: ChessAI.Difficulty, enabled: Boolean, onPick: (ChessAI.Difficulty) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ENGINE LEVEL", style = MonoLabel, color = LabelGray)
        ChessAI.Difficulty.entries.forEach { d ->
            val on = d == current
            Text(
                d.name,
                color = if (on) SkyBlue else LabelGray,
                fontSize = 10.sp, fontFamily = SystemMono, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .pressScale(0.96f)
                    .border(1.dp, if (on) SkyBlue else LineSoft, RoundedCornerShape(5.dp))
                    .clickable(enabled = enabled) { onPick(d) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PlayerTag(label: String, ms: Long, timed: Boolean, captures: List<Int>, materialPlus: Int) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MonoLabel, color = LabelGray)
            if (materialPlus > 0) {
                Text(" +$materialPlus", color = SkyBlue, fontSize = 11.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            if (timed) Text(formatClock(ms), color = PaperWhite, fontSize = 15.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold)
        }
        if (captures.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                captures.take(8).forEach { p ->
                    Canvas(Modifier.size(15.dp)) { drawChessPiece(p, 0f, 0f, size.minDimension) }
                }
                if (captures.size > 8) {
                    Text(" +${captures.size - 8}", style = MonoLabel, color = LabelGray)
                }
            }
        }
    }
}

@Composable
private fun ClockChip(ms: Long, active: Boolean) {
    Text(
        formatClock(ms),
        color = if (active) SkyBlue else PaperWhite,
        fontSize = 16.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(1.dp, if (active) SkyBlue else LineSoft, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

fun formatClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** WAR ANALYSIS (§5) — every metric derived from the actual move record. */
data class GameAnalysis(
    val accuracy: Double,
    val blunders: Int,
    val avgThinkMs: Int,
    val best: String,
    val worst: String,
    val scores: Map<String, Int>,   // session readings 0..100
) {
    fun toStatJson() = buildJsonObject {
        scores.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }
    fun toAnalysisJson() = buildJsonObject {
        put("best", best); put("worst", worst)
        put("accuracy", JsonPrimitive(accuracy))
        put("blunders", JsonPrimitive(blunders))
    }
}

private fun analyzeGame(
    history: List<String>,
    moves: List<Move>,
    thinks: List<Long>,
    userColor: Int,
    userWon: Boolean,
    lost: Boolean,
): GameAnalysis {
    var bestSwing = Int.MIN_VALUE; var worstSwing = Int.MAX_VALUE
    var bestMove = "—"; var worstMove = "—"
    val cpls = ArrayList<Int>()
    for (i in moves.indices) {
        val moverWhite = i % 2 == 0
        val isUser = (moverWhite && userColor == 0) || (!moverWhite && userColor == 1)
        if (!isUser) continue
        val b = Board.fromFen(history[i])
        val swing = runCatching { ChessAI.moveSwing(b, moves[i]) }.getOrDefault(0)
        val cpl = (-swing).coerceIn(0, 1000)
        cpls.add(cpl)
        if (swing > bestSwing) { bestSwing = swing; bestMove = moves[i].uci() }
        if (swing < worstSwing) { worstSwing = swing; worstMove = moves[i].uci() }
    }
    val avgCpl = if (cpls.isEmpty()) 0.0 else cpls.average()
    val accuracy = (100.0 - avgCpl / 10.0).coerceIn(0.0, 100.0)
    val blunders = cpls.count { it >= 300 }
    val avgThink = if (thinks.isEmpty()) 0 else (thinks.average().toInt())

    val tactics = (100 - blunders * 18 - (100 - accuracy) * 0.3).toInt().coerceIn(5, 100)
    val decision = (accuracy * 0.65 + (if (userWon) 35 else if (lost) 10 else 22)).toInt().coerceIn(5, 100)
    val timeCtl = when {
        thinks.isEmpty() -> 55
        avgThink in 3000..30000 -> 78
        avgThink < 1500 -> 45
        else -> 62
    }
    val scores = mapOf(
        "tactics" to tactics,
        "focus" to accuracy.toInt().coerceIn(5, 100),
        "calculation" to ((accuracy + tactics) / 2).coerceIn(5.0, 100.0).toInt(),
        "decision" to decision,
        "composure" to timeCtl,
    )
    return GameAnalysis(accuracy, blunders, avgThink, bestMove, worstMove, scores)
}

/** Post-game panel — MENTAL WAR shows the §5 reading; others stay compact. */
@Composable
private fun ResultPanel(
    mode: ChessMode,
    result: String,
    reason: String,
    analysis: GameAnalysis?,
    logged: com.thesystem.app.data.repo.ChessRepository.LogResult?,
    onRematch: () -> Unit,
    onBack: () -> Unit,
) {
    GlowCard(glow = if (result == "WIN") SkyBlue else PaperWhite, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(
            when (result) { "WIN" -> "VICTORY"; "LOSS" -> "DEFEAT"; else -> "STALEMATE PROTOCOL" },
            color = if (result == "WIN") SkyBlue else PaperWhite,
            fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontFamily = SystemMono,
        )
        Text(reason, style = MonoLabel, color = LabelGray)

        if (mode.war && analysis != null) {
            Spacer(Modifier.height(10.dp))
            Text("SYSTEM ANALYSIS", style = MonoLabel, color = SkyBlue)
            Spacer(Modifier.height(6.dp))
            AnalysisLine("TACTICAL VISION", analysis.scores["tactics"] ?: 0)
            AnalysisLine("DECISION QUALITY", analysis.scores["decision"] ?: 0)
            AnalysisLine("TIME CONTROL", analysis.scores["composure"] ?: 0)
            AnalysisLine("BLUNDER RATE", 100 - analysis.blunders * 20, invert = true)
            AnalysisLine("COMPOSURE", analysis.scores["composure"] ?: 0)
            Spacer(Modifier.height(8.dp))
            Text(
                "ACCURACY ${"%.0f".format(analysis.accuracy)}% · BLUNDERS ${analysis.blunders} · BEST ${analysis.best.uppercase()} · WORST ${analysis.worst.uppercase()}",
                style = MonoLabel, color = LabelGray,
            )
            Text(
                "NEXT TARGET: ${if (analysis.blunders > 0) "ELIMINATE ${analysis.worst.uppercase()} LINES" else "DEEPER CALCULATION"}",
                style = MonoLabel, color = SkyBlue,
            )
        } else if (mode.war && analysis == null) {
            Spacer(Modifier.height(10.dp))
            SystemProcessing("SYSTEM ANALYSIS COMPILING", compact = true)
        }

        logged?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                "RATING ${it.rating} (${if (it.ratingDelta >= 0) "+" else ""}${it.ratingDelta}) · +${it.xpGained} MENTAL XP",
                color = PaperWhite, fontSize = 12.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("HUB", onBack, Modifier.weight(1f))
            NeonButton("REMATCH", onRematch, Modifier.weight(1f), color = SkyBlue)
        }
    }
}

@Composable
private fun AnalysisLine(label: String, value: Int, invert: Boolean = false) {
    val delta = ((value - 50) / 2).coerceIn(-25, 25)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MonoLabel, color = LabelGray, modifier = Modifier.weight(1f))
        Text(
            (if (delta >= 0) "+" else "") + delta,
            color = if (delta >= 0) SkyBlue else PaperWhite,
            fontSize = 13.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold,
        )
    }
}
