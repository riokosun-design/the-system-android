package com.thesystem.app.ui.chess

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.thesystem.app.chess.Gate
import com.thesystem.app.chess.LStep
import com.thesystem.app.chess.Board
import com.thesystem.app.chess.LessonCurriculum
import com.thesystem.app.chess.Move
import com.thesystem.app.chess.placePiece
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CorrectGreen = Color(0xFF4ADE80)
private val MistakeRed = Color(0xFFFF1744)

/** Per-step scratch — reset on every step change. */
private class StepState {
    var done by mutableStateOf(false)
    var wrongAt by mutableIntStateOf(-1)
    var rightAt by mutableIntStateOf(-1)
    var collected = mutableStateListOf<Int>()
    var mistake by mutableStateOf(false)          // shows "Not this square. Try again." + Show me
    var choicePicked by mutableIntStateOf(-1)
    var choiceMsg by mutableStateOf<String?>(null)
    var testIdx by mutableIntStateOf(0)
}

private fun pieceName(p: Int): String = when (com.thesystem.app.chess.typeOf(p)) {
    com.thesystem.app.chess.PAWN -> "PAWN"; com.thesystem.app.chess.KNIGHT -> "KNIGHT"
    com.thesystem.app.chess.BISHOP -> "BISHOP"; com.thesystem.app.chess.ROOK -> "ROOK"
    com.thesystem.app.chess.QUEEN -> "QUEEN"; else -> "KING"
}

/**
 * vC53 §1 — THE CURRICULUM shell. 64 engine-verified interactive lessons in
 * nine gates: instructor speech card, progress dots, < > steppers, tap-square
 * tasks (green right / red wrong + "Not this square. Try again." + Show me),
 * "Place this piece:" bars, Yes/No & Checkmate/Stalemate/Neither MCQs, and
 * LESSON TESTs with the Moves: X/3 counter. Every Step's seed content is
 * proven legal by rep-sim/validate_lessons.kt against the shipped engine.
 */
@Composable
fun ChessLessonsScreen(nav: NavHostController, vm: ChessViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("chess_lesson_prefs", Context.MODE_PRIVATE) }
    var openLesson by remember { mutableStateOf<Int?>(null) }
    val done = remember { mutableStateListOf<Int>().apply { addAll(loadDone(prefs)) } }

    if (openLesson == null) {
        LessonGateList(
            done = done.toSet(),
            muted = ChessSfx.isMuted(),
            onBack = { nav.popBackStack() },
            onOpen = { openLesson = it },
        )
    } else {
        val lesson = LessonCurriculum.LESSONS.first { it.id == openLesson }
        LessonRunner(
            lesson = lesson,
            onExit = { openLesson = null },
            onComplete = {
                if (lesson.id !in done) {
                    done.add(lesson.id)
                    prefs.edit().putString(KEY_DONE, done.joinToString(",")).apply()
                }
                vm.viewModelReportLesson(lesson.id, lesson.title)
                openLesson = null
            },
        )
    }
}

private const val KEY_DONE = "completed_lessons"
private fun loadDone(prefs: android.content.SharedPreferences): List<Int> =
    prefs.getString(KEY_DONE, "")?.takeIf { it.isNotBlank() }
        ?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()

// ── GATE LIST ────────────────────────────────────────────────────────────────

@Composable
private fun LessonGateList(
    done: Set<Int>,
    muted: Boolean,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    var sfxMuted by remember { mutableStateOf(muted) }
    SystemBackground {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding().statusBarsPadding().padding(horizontal = Grid.Margin),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Grid.S12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "THE CURRICULUM",
                        color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                    )
                    Text("64 LESSONS · EVERY STEP VERIFIED", style = MonoLabel, color = SkyBlue)
                }
                Text(
                    if (sfxMuted) "🔇" else "🔊",
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { sfxMuted = !sfxMuted; ChessSfx.setMuted(context, sfxMuted) }
                        .padding(horizontal = 8.dp),
                )
            }

            // progress rail
            val pct = done.size / 64f
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, ChessCardLine, ChessCardShape)
                    .padding(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CURRICULUM CLEARANCE", style = MonoLabel, color = LabelGray)
                    Spacer(Modifier.weight(1f))
                    Text("${done.size}/64", style = MonoLabel, color = SkyBlue)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth().height(5.dp)
                        .background(LineSoft, RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight().fillMaxWidth(pct.coerceIn(0f, 1f))
                            .background(SkyBlue, RoundedCornerShape(3.dp)),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(Grid.S8), contentPadding = PaddingValues(bottom = 20.dp)) {
                Gate.entries.forEach { gate ->
                    item {
                        SectionTitle("GATE · ${gate.title}", SkyBlue)
                    }
                    items(LessonCurriculum.LESSONS.filter { it.cat == gate }) { lesson ->
                        val cleared = lesson.id in done
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, if (cleared) SkyBlue.copy(alpha = 0.45f) else ChessCardLine, ChessCardShape)
                                .background(ChessCardSurface, ChessCardShape)
                                .clickable { haptics.select(); onOpen(lesson.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "%02d".format(lesson.id),
                                style = MonoLabel, color = if (cleared) SkyBlue else LabelGray,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(lesson.title, color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${lesson.steps.size} STEPS" + if (lesson.steps.any { it is LStep.Test }) " · LESSON TEST" else "",
                                    style = MonoLabel, color = FaintGray,
                                )
                            }
                            Text(if (cleared) "✓ CLEARED" else "OPEN", style = MonoLabel, color = if (cleared) CorrectGreen else SkyBlue)
                        }
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }
            }
        }
    }
}

// ── LESSON RUNNER ────────────────────────────────────────────────────────────

@Composable
private fun LessonRunner(
    lesson: com.thesystem.app.chess.Lesson,
    onExit: () -> Unit,
    onComplete: () -> Unit,
) {
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sfxMuted by remember { mutableStateOf(ChessSfx.isMuted()) }
    val pieceImgs = rememberPieceBitmaps()

    var stepIdx by remember(lesson.id) { mutableIntStateOf(0) }
    val step = lesson.steps[stepIdx]
    val st = remember(lesson.id, stepIdx) { StepState() }

    // live board for this step (MOVE/TEST/PLACE mutate it; AUTOPLAY drives it)
    val initialFen = when (step) {
        is LStep.Test -> step.items.first().fen
        else -> step.fen
    }
    var fen by remember(lesson.id, stepIdx) { mutableStateOf(initialFen) }
    val board = remember(fen) { Board.fromFen(fen) }
    val liveLegal = remember(fen) { board.legalMoves() }
    var selected by remember { mutableStateOf(-1) }
    var pendingPromo by remember { mutableStateOf<List<Move>>(emptyList()) }
    var anim by remember { mutableStateOf<AnimMove?>(null) }
    var lastMove by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // ── engine-backed move application with the slide rail ──
    fun applyUci(uci: String, onAfter: (() -> Unit) = {}) {
        val mv = board.legalMoves().firstOrNull { it.uci() == uci } ?: return
        val pre = Board.fromFen(fen)
        anim = AnimMove(mv.from, mv.to, pre.sq[mv.from], pre.sq[mv.to])
        board.make(mv)
        fen = board.toFen()
        lastMove = mv.from to mv.to
        ChessSfx.play(if (pre.sq[mv.to] != com.thesystem.app.chess.EMPTY) ChessSfx.Sfx.CAPTURE else ChessSfx.Sfx.MOVE)
        onAfter()
    }

    fun wrong(sq: Int = -1) {
        st.mistake = true
        st.wrongAt = sq
        haptics.error()
        ChessSfx.play(ChessSfx.Sfx.PUZZLE_FAIL)
        scope.launch { delay(650); st.wrongAt = -1 }
    }

    fun right(sq: Int) {
        st.rightAt = sq
        st.mistake = false
        haptics.select()
        scope.launch { delay(650); st.rightAt = -1 }
    }

    fun showMe() {
        when (val s = step) {
            is LStep.Tap -> s.targets.forEach { st.collected.add(it); st.rightAt = it }
            is LStep.Place -> st.rightAt = s.target
            is LStep.MoveTask -> {
                val mv = liveLegal.firstOrNull { it.uci() == s.uci }
                if (mv != null) { st.rightAt = mv.to }
            }
            is LStep.Test -> {
                val item = s.items.getOrNull(st.testIdx)
                val b = Board.fromFen(fen)
                val mv = item?.let { b.legalMoves().firstOrNull { m -> m.uci() == it.uci } }
                if (mv != null) st.rightAt = mv.to
                st.testIdx = s.items.size
            }
            else -> {}
        }
        st.done = true
        st.mistake = false
    }

    // AUTOPLAY: script plays itself on arrival
    LaunchedEffect(lesson.id, stepIdx) {
        val s = step as? LStep.Auto ?: return@LaunchedEffect
        delay(500)
        for (u in s.moves) {
            val mv = board.legalMoves().firstOrNull { it.uci() == u } ?: break
            val pre = Board.fromFen(fen)
            anim = AnimMove(mv.from, mv.to, pre.sq[mv.from], pre.sq[mv.to])
            board.make(mv)
            fen = board.toFen()
            lastMove = mv.from to mv.to
            ChessSfx.play(if (pre.sq[mv.to] != com.thesystem.app.chess.EMPTY) ChessSfx.Sfx.CAPTURE else ChessSfx.Sfx.MOVE)
            delay(780)
        }
        // check/verdict pings the chime when the script ends in check
        if (board.kingsInCheck().isNotEmpty()) ChessSfx.play(ChessSfx.Sfx.CHECK)
        st.done = true
    }

    val say = when (step) {
        is LStep.Auto -> if (st.done && step.doneNote.isNotBlank()) step.doneNote else step.say
        is LStep.Choice -> st.choiceMsg ?: step.say
        else -> if (st.mistake) "Not this square. Try again." else step.say
    }

    val arrows: List<Pair<Int, Int>> = when (step) {
        is LStep.Talk -> step.arrows
        is LStep.MoveTask -> if (st.done) step.postArrows else emptyList()
        else -> emptyList()
    }
    val checks: List<Int> = when (step) {
        is LStep.Talk -> step.checks
        is LStep.MoveTask -> if (st.done) step.postChecks else emptyList()
        else -> emptyList()
    }

    val canProceed = when (step) {
        is LStep.Talk -> true
        is LStep.Auto -> true
        else -> st.done
    }
    val isFinal = stepIdx == lesson.steps.size - 1

    // ── tap rail: one input handler, kind-routed ──
    fun onTap(sq: Int) {
        when (val s = step) {
            is LStep.Tap -> {
                if (st.done) return
                if (sq in s.targets && sq !in st.collected) {
                    st.collected.add(sq)
                    right(sq)
                    if (!s.all || st.collected.containsAll(s.targets)) { st.done = true; ChessSfx.play(ChessSfx.Sfx.CHECK) }
                } else if (sq in st.collected) {
                    // tapping an already-collected square is inert, never a mistake
                } else wrong(sq)
            }
            is LStep.Place -> {
                if (st.done) return
                val b = Board.fromFen(fen)
                if (sq == s.target && b.sq[sq] == com.thesystem.app.chess.EMPTY) {
                    fen = placePiece(fen, sq, s.piece)
                    right(sq)
                    st.done = true
                    ChessSfx.play(ChessSfx.Sfx.CHECK)
                } else wrong(sq)
            }
            is LStep.MoveTask -> {
                if (st.done) return
                if (selected >= 0) {
                    val mv = liveLegal.firstOrNull { it.from == selected && it.to == sq }
                    if (mv != null) {
                        if (mv.uci() == s.uci) {
                            st.done = true
                            applyUci(s.uci) {
                                st.mistake = false
                                s.reply?.let { r ->
                                    scope.launch {
                                        delay(430)
                                        applyUci(r)
                                    }
                                }
                                if (s.postChecks.isNotEmpty()) ChessSfx.play(ChessSfx.Sfx.CHECK)
                            }
                            // promo tasks apply the scripted piece directly — the lesson
                            // text says which promotion is expected; no chooser mid-drill.
                            selected = -1
                            return
                        } else { wrong(sq); selected = -1; return }
                    }
                }
                selected = if (board.sq[sq] != com.thesystem.app.chess.EMPTY) sq else -1
            }
            is LStep.Test -> {
                if (st.done) return
                val item = s.items.getOrNull(st.testIdx) ?: return
                if (selected >= 0) {
                    val mv = liveLegal.firstOrNull { it.from == selected && it.to == sq }
                    if (mv != null) {
                        if (mv.uci() == item.uci) {
                            val next = st.testIdx + 1
                            applyUci(item.uci)
                            selected = -1
                            ChessSfx.play(ChessSfx.Sfx.CHECK)
                            st.mistake = false
                            if (next >= s.items.size) {
                                st.done = true
                                ChessSfx.play(ChessSfx.Sfx.VICTORY)
                                ChessSfx.buzz(context, 90, 190)
                            } else {
                                scope.launch {
                                    delay(620)
                                    if (!st.done) {
                                        st.testIdx = next
                                        val ni = s.items[next]
                                        fen = ni.fen
                                        lastMove = null
                                    }
                                }
                            }
                            return
                        } else { wrong(sq); selected = -1; return }
                    }
                }
                selected = if (board.sq[sq] != com.thesystem.app.chess.EMPTY) sq else -1
            }
            else -> {}
        }
    }

    // ── scaffold ──
    SystemBackground {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding().statusBarsPadding().padding(horizontal = Grid.Margin),
        ) {
            // header: back · title · sound door
            Row(
                Modifier.fillMaxWidth().padding(vertical = Grid.S12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = { haptics.select(); onExit() })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "LESSON %02d".format(lesson.id),
                        color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    )
                    Text(lesson.title.uppercase(), style = MonoLabel, color = SkyBlue)
                }
                Text(
                    if (sfxMuted) "🔇" else "🔊",
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { sfxMuted = !sfxMuted; ChessSfx.setMuted(context, sfxMuted) }
                        .padding(horizontal = 8.dp),
                )
            }

            // instructor speech card
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, ChessCardLine, ChessCardShape)
                    .background(ChessCardSurface, ChessCardShape)
                    .padding(12.dp),
            ) {
                Text("INSTRUCTOR", style = MonoLabel, color = SkyBlue)
                Spacer(Modifier.height(6.dp))
                Text(say, color = PaperWhite, fontSize = 13.sp, lineHeight = 18.sp)
                if (st.mistake && step !is LStep.Choice && step !is LStep.Talk && step !is LStep.Auto) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Show me",
                        style = MonoLabel, color = SkyBlue,
                        modifier = Modifier
                            .border(1.dp, SkyBlue, RoundedCornerShape(6.dp))
                            .clickable { haptics.select(); showMe() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // progress dots
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                lesson.steps.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .weight(1f).height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    i < stepIdx -> SkyBlue
                                    i == stepIdx -> PaperWhite
                                    else -> LineSoft
                                },
                            ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // test counter / place bar
            when (val s = step) {
                is LStep.Test -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("LESSON TEST", style = MonoLabel, color = PaperWhite)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Moves: ${st.testIdx.coerceAtMost(s.items.size)} / ${s.items.size}",
                        style = MonoLabel, color = if (st.done) CorrectGreen else SkyBlue,
                    )
                }
                is LStep.Place -> Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, ChessCardLine, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pieceImgs[s.piece]?.let {
                        Image(it, contentDescription = pieceName(s.piece), modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Place this piece: ${pieceName(s.piece)}",
                        color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (st.done) Text("PLACED ✓", style = MonoLabel, color = CorrectGreen)
                }
                else -> {}
            }

            // board zone — optically centered like the duel screen
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val boardSide = minOf(maxWidth, maxHeight, 380.dp)
                ChessBoard(
                    board = board,
                    myColor = 0,
                    selected = selected,
                    targets = remember(selected, liveLegal, step) {
                        if (selected < 0) emptySet() else when (step) {
                            is LStep.MoveTask, is LStep.Test -> targetsFor(liveLegal, selected)
                            else -> emptySet()
                        }
                    },
                    lastMove = lastMove,
                    checkSquares = checks.toSet(),
                    squareTints = buildMap {
                        when (val s = step) {
                            is LStep.Talk -> s.tints.forEach { put(it, SkyBlueDim) }
                            is LStep.Tap -> st.collected.forEach { put(it, SkyBlueDim) }
                            else -> {}
                        }
                        if (st.rightAt >= 0) put(st.rightAt, CorrectGreen.copy(alpha = 0.45f))
                        if (st.wrongAt >= 0) put(st.wrongAt, MistakeRed.copy(alpha = 0.5f))
                    },
                    attackArrows = arrows,
                    anim = anim,
                    onAnimDone = { anim = null },
                    onSquare = { sq -> onTap(sq) },
                    modifier = Modifier.size(boardSide),
                )
            }

            // choice chips
            if (step is LStep.Choice && !st.done) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    step.options.forEachIndexed { i, opt ->
                        Text(
                            opt,
                            color = PaperWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    1.dp,
                                    when {
                                        st.choicePicked == i && i == step.correct -> CorrectGreen
                                        st.choicePicked == i -> MistakeRed
                                        else -> LineStrong
                                    },
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    haptics.select()
                                    if (i == step.correct) {
                                        st.choicePicked = i
                                        st.done = true
                                        st.mistake = false
                                        ChessSfx.play(ChessSfx.Sfx.CHECK)
                                    } else {
                                        st.choicePicked = i
                                        st.choiceMsg = step.wrong
                                        st.mistake = false
                                        haptics.error()
                                        ChessSfx.play(ChessSfx.Sfx.PUZZLE_FAIL)
                                    }
                                }
                                .padding(vertical = 11.dp, horizontal = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            if (step is LStep.Choice && st.done) {
                Text(
                    "CORRECT",
                    style = MonoLabel, color = CorrectGreen,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            // steppers
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    "<",
                    { if (stepIdx > 0) { haptics.select(); stepIdx-- } },
                    Modifier.width(64.dp), enabled = stepIdx > 0,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "STEP ${stepIdx + 1} / ${lesson.steps.size}",
                    style = MonoLabel, color = LabelGray,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                Spacer(Modifier.weight(1f))
                if (isFinal && canProceed) {
                    NeonButton(
                        "COMPLETE",
                        { haptics.slam(); onComplete() },
                        Modifier.width(150.dp), color = CorrectGreen,
                    )
                } else {
                    NeonButton(
                        ">",
                        { if (canProceed) { haptics.select(); stepIdx++ } },
                        Modifier.width(64.dp), color = SkyBlue, enabled = canProceed && !isFinal,
                    )
                }
            }
        }
    }
}
