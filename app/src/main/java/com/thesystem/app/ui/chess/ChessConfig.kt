package com.thesystem.app.ui.chess

import com.thesystem.app.chess.Board
import com.thesystem.app.chess.ChessAI
import java.time.DayOfWeek

/** CHESS MODE CONTRACT (spec §4/§5) — online modes are honest-locked until the
 *  neural link ships. Every listed local mode resolves to a deterministic
 *  engine game / puzzle flow; no fake matchmaking, ever. */
enum class ChessMode(
    val label: String,
    val sub: String,
    val diff: ChessAI.Difficulty?,
    val clockSec: Int,          // 0 = untimed
    val incSec: Int = 0,
    val war: Boolean = false,   // full SYSTEM ANALYSIS after the game (§5)
    val soon: Boolean = false,
) {
    AI_TRAINING("AI TRAINING", "choose engine level", null, 0),
    MENTAL_WAR("MENTAL WAR", "rated · full system analysis", ChessAI.Difficulty.HARD, 600, 0, war = true),
    PUZZLE_TRAINING("PUZZLE TRAINING", "adaptive solver", null, 0),
    BLITZ("BLITZ", "5+0 · engine MEDIUM", ChessAI.Difficulty.MEDIUM, 300),
    RAPID("RAPID", "15+10 · engine MEDIUM", ChessAI.Difficulty.MEDIUM, 900, 10),
    CLASSICAL("CLASSICAL", "untimed · engine HARD", ChessAI.Difficulty.HARD, 0),
    DAILY_CHALLENGE("DAILY CHALLENGE", "one seed per day", null, 0),
    ENDGAME_TRAINING("ENDGAME TRAINING", "convert the advantage", ChessAI.Difficulty.MEDIUM, 0),
    TACTICAL_TRAINING("TACTICAL TRAINING", "tactic pool", null, 0),
    OPENING_TRAINING("OPENING TRAINING", "italian · spanish lines", ChessAI.Difficulty.MEDIUM, 0),
    BLUNDER_TRAINING("BLUNDER TRAINING", "punish the mistake", null, 0),
    TIME_PRESSURE("TIME PRESSURE", "2+0 · decide now", ChessAI.Difficulty.MEDIUM, 120),

    // ── neural link incubation (honest: not shipped yet) ──────────────
    QUICK_MATCH("QUICK MATCH", "neural link — soon", null, 0, soon = true),
    RANKED("RANKED", "neural link — soon", null, 0, soon = true),
    RANDOM_OPPONENT("RANDOM OPPONENT", "neural link — soon", null, 0, soon = true),
    FRIEND_MATCH("FRIEND MATCH", "neural link — soon", null, 0, soon = true),
}

/** Endgame set — hunter always plays the winning side (educational conversion). */
val ENDGAMES = listOf(
    "8/8/1k6/8/8/1K6/1P6/8 w - - 0 1",   // KP vs K — opposition craft
    "6k1/8/6K1/8/8/8/8/R7 w - - 0 1",   // KR mate net
    "8/6k1/8/6KP/8/8/8/8 w - - 0 1",    // outside passer race
)

/** Opening set — date-rotated, labeled honestly. */
data class OpeningLine(val name: String, val fen: String, val userWhite: Boolean)
val OPENINGS = listOf(
    OpeningLine("ITALIAN GAME", "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", true),
    OpeningLine("RUY LOPEZ", "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3", false),
)

/** Deterministic daily mental quest (spec §9 MIND row). */
data class MentalQuest(val title: String, val kind: String, val result: String?, val target: Int, val xp: Int)

fun mentalQuestOf(day: DayOfWeek): MentalQuest = when (day) {
    DayOfWeek.MONDAY -> MentalQuest("10 CHESS PUZZLES", "PUZZLE", "SOLVED", 10, 30)
    DayOfWeek.TUESDAY -> MentalQuest("1 ENGINE GAME", "GAME", null, 1, 25)
    DayOfWeek.WEDNESDAY -> MentalQuest("DAILY CHALLENGE", "DAILY", "SOLVED", 1, 20)
    DayOfWeek.THURSDAY -> MentalQuest("8 CHESS PUZZLES", "PUZZLE", "SOLVED", 8, 25)
    DayOfWeek.FRIDAY -> MentalQuest("1 TIME-PRESSURE GAME", "GAME", null, 1, 25)
    DayOfWeek.SATURDAY -> MentalQuest("5 CHESS PUZZLES", "PUZZLE", "SOLVED", 5, 20)
    DayOfWeek.SUNDAY -> MentalQuest("1 ENGINE GAME", "GAME", null, 1, 25)
}

fun modeStartFen(mode: ChessMode, epochDay: Long): Pair<String, Boolean> = when (mode) {
    ChessMode.ENDGAME_TRAINING -> ENDGAMES[(epochDay % ENDGAMES.size).toInt()] to true
    ChessMode.OPENING_TRAINING -> { val o = OPENINGS[(epochDay % OPENINGS.size).toInt()]; o.fen to o.userWhite }
    else -> Board.START_FEN to true
}
