package com.thesystem.app.ui.chess

import androidx.compose.foundation.background
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
import com.thesystem.app.chess.ChessAI
import com.thesystem.app.chess.Move
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * CHESS GAME — engine duel under the System HUD (§3/§4/§5). Deterministic
 * clocks + engine; MENTAL WAR runs the full post-game analysis and hands
 * every number to the server (mig 018) — client never invents XP or rating.
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

    val history = remember { mutableStateListOf(startFen) }
    val playedMoves = remember { mutableStateListOf<Move>() }
    val thinkMs = remember { mutableStateListOf<Long>() }
    var userMoveStartedAt by remember { mutableStateOf(0L) }

    var whiteMs by remember { mutableLongStateOf(mode.clockSec * 1000L) }
    var blackMs by remember { mutableLongStateOf(mode.clockSec * 1000L) }

    var result by remember { mutableStateOf<String?>(null) } // WIN | LOSS | DRAW (hunter view)
    var resultReason by remember { mutableStateOf("") }
    var logged by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<GameAnalysis?>(null) }
    var logOutcome by remember { mutableStateOf<com.thesystem.app.data.repo.ChessRepository.LogResult?>(null) }
    val scope = rememberCoroutineScope()

    val userColorIdx = if (userWhite) 0 else 1
    val myMs = if (userWhite) whiteMs else blackMs
    val oppMs = if (userWhite) blackMs else whiteMs

    fun finish(res: String, reason: String) {
        if (result != null) return
        result = res; resultReason = reason
        if (res == "WIN") haptics.slam() else if (res == "LOSS") haptics.error()
    }

    fun pushMove(m: Move) {
        board.play(m)
        playedMoves.add(m)
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

    // engine turn
    LaunchedEffect(fen, result, aiTrainingUnlocked) {
        if (!aiTrainingUnlocked || result != null) return@LaunchedEffect
        val engineTurn = (if (board.whiteToMove) 0 else 1) != userColorIdx
        if (!engineTurn) {
            userMoveStartedAt = System.currentTimeMillis()
            return@LaunchedEffect
        }
        if (board.status().first != Board.Status.ONGOING) return@LaunchedEffect
        delay(350)
        val mv = withContext(Dispatchers.Default) { ChessAI.bestMove(board, diff) }
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
        val acc = a?.accuracy
        vm.repoLog(
            kind = "GAME", mode = mode.name, result = res,
            accuracy = acc, blunders = a?.blunders ?: 0,
            thinkMs = a?.avgThinkMs ?: 0,
            stats = a?.toStatJson(), analysis = a?.toAnalysisJson(),
        ).onSuccess { logOutcome = it }
        vm.refresh()
    }

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
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
                    Modifier.fillMaxWidth().weight(1f),
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

            // opponent strip
            PlayerTag("SYSTEM ENGINE · ${diff.name}", oppMs, mode.clockSec > 0)

            Spacer(Modifier.height(8.dp))

            // ── board ────────────────────────────────────────────────────
            ChessBoard(
                board = board,
                myColor = userColorIdx,
                selected = selected,
                targets = remember(selected, legal) { if (selected < 0) emptySet() else targetsFor(legal, selected) },
                lastMove = lastMove,
                onSquare = onSquare@{ sq ->
                    if (result != null) return@onSquare
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
                    selected = if (piece != com.thesystem.app.chess.EMPTY &&
                        com.thesystem.app.chess.colorOf(piece) == userColorIdx) sq else -1
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (pendingPromo.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PROMOTE:", style = MonoLabel, color = SkyBlue, modifier = Modifier.align(Alignment.CenterVertically))
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
            PlayerTag("YOU · ${if (userWhite) "WHITE" else "BLACK"}", myMs, mode.clockSec > 0)

            // status ribbon
            val inCheck = board.inCheck(board.whiteToMove)
            if (result == null && inCheck) {
                Text("CHECK", color = SkyBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    fontFamily = SystemMono, modifier = Modifier.padding(vertical = 4.dp))
            }

            Spacer(Modifier.weight(1f))

            if (result == null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    onRematch = {
                        fen = Board.fromFen(startFen).toFen()
                        history.clear(); history.add(startFen)
                        playedMoves.clear(); thinkMs.clear()
                        selected = -1; pendingPromo = emptyList(); lastMove = null
                        whiteMs = mode.clockSec * 1000L; blackMs = mode.clockSec * 1000L
                        result = null; resultReason = ""; logged = false
                        analysis = null; logOutcome = null
                        aiTrainingUnlocked = mode != ChessMode.AI_TRAINING
                    },
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun PlayerTag(label: String, ms: Long, timed: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MonoLabel, color = LabelGray, modifier = Modifier.weight(1f))
        if (timed) Text(formatClock(ms), color = PaperWhite, fontSize = 15.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold)
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
    fun toStatJson() = kotlinx.serialization.json.buildJsonObject {
        scores.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
    }
    fun toAnalysisJson() = kotlinx.serialization.json.buildJsonObject {
        put("best", best); put("worst", worst)
        put("accuracy", kotlinx.serialization.json.JsonPrimitive(accuracy))
        put("blunders", kotlinx.serialization.json.JsonPrimitive(blunders))
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
    var userIdx = -1
    for (i in moves.indices) {
        val moverWhite = i % 2 == 0
        val isUser = (moverWhite && userColor == 0) || (!moverWhite && userColor == 1)
        if (!isUser) continue
        userIdx++
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
            Text("NEXT TARGET: ${if (analysis.blunders > 0) "ELIMINATE ${analysis.worst.uppercase()} LINES" else "DEEPER CALCULATION"}",
                style = MonoLabel, color = SkyBlue)
        } else if (mode.war && analysis == null) {
            Spacer(Modifier.height(10.dp))
            Text("SYSTEM ANALYSIS COMPILING…", style = MonoLabel, color = LabelGray)
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
