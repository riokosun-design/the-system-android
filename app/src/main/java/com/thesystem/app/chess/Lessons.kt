package com.thesystem.app.chess

/**
 * vC53 §1 — THE CURRICULUM. 64 lessons, nine gates, every single step
 * interactive and ENGINE-CHECKED at build time:
 *
 *   GATE I    1–10  The Board & Pieces
 *   GATE II  11–20  The Goal & Special Rules
 *   GATE III 21–24  First Checkmates
 *   GATE IV  25–29  Material & Value
 *   GATE V   30–34  Openings
 *   GATE VI  35–48  Tactics (14)
 *   GATE VII 49–54  Mate Patterns
 *   GATE VIII 55–58 Planning
 *   GATE IX  59–64  Endgames
 *
 * Honesty law: no fake content. Every MoveTask/Auto/Test move is re-verified
 * against the in-app 0x88 engine by rep-sim/validate_lessons.kt, and Choice
 * steps may carry a [Truth] the validator proves with board status. Test
 * items reuse the 49-puzzle pack (double-verified) or inline scripts which
 * the same harness walks move by move.
 */

// ── square + fen helpers (0x88 law: idx = (rank shl 4) or file) ─────────────

fun sq8(name: String): Int {
    val f = name[0] - 'a'; val r = name[1] - '1'
    require(f in 0..7 && r in 0..7) { "bad square $name" }
    return (r shl 4) or f
}

private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
const val EMPTY_FEN = "8/8/8/8/8/8/8/8 w - - 0 1"

/** Build a honest diamond-stable FEN from compact placements: "wKe4", "bka8". */
fun fenOf(vararg pcs: String, side: Char = 'w', castling: String = "-", ep: String = "-"): String {
    val grid = Array(8) { CharArray(8) { ' ' } }
    for (p in pcs) {
        val white = p[0] == 'w'
        val t = when (p[1]) { 'K' -> 'K'; 'Q' -> 'Q'; 'R' -> 'R'; 'B' -> 'B'; 'N' -> 'N'; 'P' -> 'P'; else -> error("bad piece $p") }
        grid[p[3] - '1'][p[2] - 'a'] = if (white) t else t.lowercaseChar()
    }
    return buildString {
        for (r in 7 downTo 0) {
            var e = 0
            for (f in 0..7) {
                val ch = grid[r][f]
                if (ch == ' ') e++ else { if (e > 0) { append(e); e = 0 }; append(ch) }
            }
            if (e > 0) append(e)
            if (r > 0) append('/')
        }
        append(' '); append(side); append(' '); append(castling); append(' '); append(ep); append(" 0 1")
    }
}

/** PLACE steps rewrite the REAL board — place [piece] (engine const) on [sq]. */
fun placePiece(fen: String, sq: Int, piece: Int): String {
    val parts = fen.split(" ")
    val grid = Array(8) { CharArray(8) { ' ' } }
    val rows = parts[0].split("/")
    for (r in 0..7) {
        var f = 0
        for (ch in rows[r]) {
            if (ch.isDigit()) f += ch - '0' else { grid[7 - r][f] = ch; f++ }
        }
    }
    val ch = when (typeOf(piece)) {
        KING -> 'K'; QUEEN -> 'Q'; ROOK -> 'R'; BISHOP -> 'B'; KNIGHT -> 'N'; else -> 'P'
    }
    grid[sq shr 4][sq and 7] = if (colorOf(piece) == 0) ch else ch.lowercaseChar()
    return buildString {
        for (r in 7 downTo 0) {
            var e = 0
            for (f in 0..7) {
                val c = grid[r][f]
                if (c == ' ') e++ else { if (e > 0) { append(e); e = 0 }; append(c) }
            }
            if (e > 0) append(e)
            if (r > 0) append('/')
        }
        for (i in 1 until parts.size) { append(' '); append(parts[i]) }
    }
}

private val ROOK_DIRS = intArrayOf(0x10, -0x10, 1, -1)
private val BISHOP_DIRS = intArrayOf(0x11, -0x11, 0x0F, -0x0F)
private val QUEEN_DIRS = intArrayOf(0x10, -0x10, 1, -1, 0x11, -0x11, 0x0F, -0x0F)
private val KNIGHT_OFF = intArrayOf(0x21, 0x1F, 0x12, -0x0E, -0x21, -0x1F, -0x12, 0x0E)

/** Squares a slider reaches on THIS position — engine-truthful tap targets. */
fun slideSquares(fen: String, from: Int, dirs: IntArray): Set<Int> {
    val b = Board.fromFen(fen)
    val me = colorOf(b.sq[from])
    val out = LinkedHashSet<Int>()
    for (d in dirs) {
        var s = from
        while (true) {
            s += d
            if (s and 0x88 != 0) break
            if (b.sq[s] == EMPTY) out += s
            else { if (colorOf(b.sq[s]) != me) out += s; break }
        }
    }
    return out
}

fun knightJumps(s: Int): Set<Int> = KNIGHT_OFF.map { s + it }.filter { it and 0x88 == 0 }.toSet()
fun kingSteps(s: Int): Set<Int> = QUEEN_DIRS.map { s + it }.filter { it and 0x88 == 0 }.toSet()

// ── the step language ───────────────────────────────────────────────────────

/** Ground-truth a validator can PROVE about a Choice's board position. */
enum class Truth { NONE, MATE, STALEMATE, IN_CHECK }

sealed class LStep {
    abstract val say: String
    abstract val fen: String

    /** Instructor speaks; optional tints (sky), attack arrows (red), burning kings. */
    data class Talk(
        override val say: String,
        override val fen: String = START_FEN,
        val tints: List<Int> = emptyList(),
        val arrows: List<Pair<Int, Int>> = emptyList(),
        val checks: List<Int> = emptyList(),
    ) : LStep()

    /** Tap the square(s). [all] = every target must be collected. */
    data class Tap(
        override val say: String,
        override val fen: String = START_FEN,
        val targets: Set<Int>,
        val all: Boolean = false,
    ) : LStep()

    /** "Place this piece:" — drag the law onto the board with one tap. */
    data class Place(
        override val say: String,
        override val fen: String,
        val piece: Int,
        val target: Int,
    ) : LStep()

    /** The hunter plays [uci]; scripted [reply] answers with the slide rail. */
    data class MoveTask(
        override val say: String,
        override val fen: String,
        val uci: String,
        val reply: String? = null,
        val postArrows: List<Pair<Int, Int>> = emptyList(),
        val postChecks: List<Int> = emptyList(),
    ) : LStep()

    /** Multiple choice — Yes/No, Checkmate/Stalemate/Neither, or custom law. */
    data class Choice(
        override val say: String,
        override val fen: String = START_FEN,
        val options: List<String>,
        val correct: Int,
        val wrong: String = "Not this one. Scan the board again.",
        val truth: Truth = Truth.NONE,
    ) : LStep()

    /** Watch it happen: every move in [moves] replays with slide + audio. */
    data class Auto(
        override val say: String,
        override val fen: String,
        val moves: List<String>,
        val doneNote: String = "",
    ) : LStep()

    /** LESSON TEST — find the shot in three fresh positions. Moves: X/3. */
    data class Test(
        override val say: String,
        val items: List<TestItem>,
    ) : LStep() {
        override val fen: String get() = items.first().fen
    }
}

data class TestItem(val fen: String, val uci: String, val caption: String = "")

data class Lesson(val id: Int, val cat: Gate, val title: String, val steps: List<LStep>)

enum class Gate(val range: IntRange, val title: String) {
    BOARD(1..10, "THE BOARD & PIECES"),
    RULES(11..20, "THE GOAL & SPECIAL RULES"),
    FIRST_MATES(21..24, "FIRST CHECKMATES"),
    MATERIAL(25..29, "MATERIAL & VALUE"),
    OPENINGS(30..34, "OPENINGS"),
    TACTICS(35..48, "TACTICS"),
    MATE_PATTERNS(49..54, "MATE PATTERNS"),
    PLANNING(55..58, "PLANNING"),
    ENDGAMES(59..64, "ENDGAMES"),
}

private fun pack(pid: String): Puzzle = PuzzlePack.ALL.first { it.id == pid }
private fun packItem(pid: String, caption: String = ""): TestItem {
    val p = pack(pid)
    return TestItem(p.fen, p.line.first(), caption.ifBlank { p.motif })
}

object LessonCurriculum {

    private val CENTER4 = setOf(sq8("d4"), sq8("e4"), sq8("d5"), sq8("e5"))

    val LESSONS: List<Lesson> = buildList {

        // ── GATE I — THE BOARD & PIECES (1–10) ──────────────────────────────
        add(Lesson(1, Gate.BOARD, "The 64 Squares", listOf(
            LStep.Talk("Welcome to the arena, hunter. 64 squares — 8 files, 8 ranks. Nothing here is decoration.", EMPTY_FEN),
            LStep.Tap("Tap the a1 square. Bottom-left from White's throne. It is a DARK square.", EMPTY_FEN, setOf(sq8("a1"))),
            LStep.Tap("Now h8 — the far corner. Also dark.", EMPTY_FEN, setOf(sq8("h8"))),
            LStep.Choice("Is the h1 square light or dark?", EMPTY_FEN, listOf("Light", "Dark"), 0),
            LStep.Tap("The four center squares own the board's gravity. Tap ALL of them.", EMPTY_FEN, CENTER4, all = true),
            LStep.Choice("How many squares does the arena hold?", EMPTY_FEN, listOf("32", "64", "96"), 1),
            LStep.Talk("Light and dark alternate without exception. A bishop born on light NEVER touches dark. Remember that.", EMPTY_FEN, tints = CENTER4.toList()),
        )))
        add(Lesson(2, Gate.BOARD, "Files — the Verticals", listOf(
            LStep.Talk("Files run bottom to top through your screen: the a-file to the h-file.", EMPTY_FEN, arrows = listOf(sq8("a1") to sq8("a8"))),
            LStep.Tap("Tap any square on the e-file — the royal highway.", EMPTY_FEN, (0..7).map { sq8("e${it + 1}") }.toSet()),
            LStep.Tap("Tap any square on the h-file — the edge of the world.", EMPTY_FEN, (0..7).map { sq8("h${it + 1}") }.toSet()),
            LStep.Choice("Files are named…", EMPTY_FEN, listOf("a through h", "1 through 8"), 0),
            LStep.Choice("Every file contains how many squares?", EMPTY_FEN, listOf("Eight", "Seven", "It varies"), 0),
            LStep.Talk("Rooks and queens patrol files. Open files are highways — close them to enemies.", EMPTY_FEN, arrows = listOf(sq8("d1") to sq8("d8"))),
        )))
        add(Lesson(3, Gate.BOARD, "Ranks — the Horizontals", listOf(
            LStep.Talk("Ranks run left to right. White camps on ranks 1–2, black on 7–8.", EMPTY_FEN, arrows = listOf(sq8("a4") to sq8("h4"))),
            LStep.Tap("Tap any square on the 7th rank — where black's pawns stand watch.", EMPTY_FEN, (0..7).map { sq8("${'a' + it}7") }.toSet()),
            LStep.Tap("Tap any square on the 2nd rank — White's shield wall.", EMPTY_FEN, (0..7).map { sq8("${'a' + it}2") }.toSet()),
            LStep.Choice("White's back rank is…", EMPTY_FEN, listOf("Rank 1", "Rank 8"), 0),
            LStep.Choice("A pawn reaching the last rank…", EMPTY_FEN, listOf("Promotes", "Dies", "Wins instantly"), 0),
            LStep.Talk("The 7th rank invasion is a real tactical theme — a rook there feeds on pawns.", EMPTY_FEN),
        )))
        add(Lesson(4, Gate.BOARD, "The Center", listOf(
            LStep.Talk("d4, d5, e4, e5 — the center. Whoever owns it drives the game.", EMPTY_FEN, tints = CENTER4.toList()),
            LStep.Tap("Tap all four center squares. Burn the map into your thumb.", EMPTY_FEN, CENTER4, all = true),
            LStep.Talk("A knight in the center radiates eight attacks. Watch.", fenOf("wNd4", "wKe1", "bKe8"), arrows = knightJumps(sq8("d4")).map { sq8("d4") to it }),
            LStep.Talk("The same knight on the rim commands only two. The rim is where attacks go to die.", fenOf("wNa1", "wKe3", "bKe8"), arrows = knightJumps(sq8("a1")).map { sq8("a1") to it }),
            LStep.Choice("A knight in the center attacks how many squares?", EMPTY_FEN, listOf("Four", "Eight", "Six"), 1),
            LStep.Choice("'A knight on the rim is…'", EMPTY_FEN, listOf("dim", "mighty"), 0),
        )))
        add(Lesson(5, Gate.BOARD, "The Armies Assemble", listOf(
            LStep.Talk("Sixteen souls each: one king, one queen, two rooks, two bishops, two knights, eight pawns."),
            LStep.Tap("Tap the white king. He starts on e1.", START_FEN, setOf(sq8("e1"))),
            LStep.Tap("Tap the white queen. d1 — she starts on her own color.", START_FEN, setOf(sq8("d1"))),
            LStep.Tap("Tap any black pawn. Rank 7, black's first shield.", START_FEN, (0..7).map { sq8("${'a' + it}7") }.toSet()),
            LStep.Tap("Tap either white rook. The corners belong to the towers.", START_FEN, setOf(sq8("a1"), sq8("h1"))),
            LStep.Choice("How many pieces (including pawns) does each side command at start?", START_FEN, listOf("16", "18", "20"), 0),
            LStep.Talk("White moves first. Farming tempo from move one — that is the game.", fen = START_FEN),
        )))
        add(Lesson(6, Gate.BOARD, "The King", listOf(
            LStep.Talk("The king moves one square in any direction. Slow — and absolutely priceless.", fenOf("wKd4", "bKe8"), arrows = kingSteps(sq8("d4")).map { sq8("d4") to it }),
            LStep.Tap("Tap any square this king can step to.", fenOf("wKd4", "bKe8"), kingSteps(sq8("d4"))),
            LStep.Choice("The king may move two squares at will.", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Choice("Two kings may stand side by side.", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Talk("In this arena the king CAN be taken — observational law. Leave him hanging and watch the finisher. Guard him.", fenOf("wKd4", "bKe8")),
            LStep.Choice("If the enemy captures your king in THE SYSTEM…", EMPTY_FEN, listOf("KING CRUSH — the war ends instantly", "You get a warning"), 0),
        )))
        add(Lesson(7, Gate.BOARD, "The Queen", listOf(
            LStep.Talk("The queen: rook lines and bishop lines fused into one weapon. Nine points of thunder.", fenOf("wQd4", "wKe1", "bKe8"), arrows = slideSquares(fenOf("wQd4", "wKe1", "bKe8"), sq8("d4"), QUEEN_DIRS).map { sq8("d4") to it }),
            LStep.Tap("Tap any square this queen can strike on.", fenOf("wQd4", "wKe1", "bKe8"), slideSquares(fenOf("wQd4", "wKe1", "bKe8"), sq8("d4"), QUEEN_DIRS)),
            LStep.Choice("The queen moves like…", EMPTY_FEN, listOf("Rook and bishop combined", "A bigger knight"), 0),
            LStep.Choice("A queen is worth about how many pawns?", EMPTY_FEN, listOf("Nine", "Five", "Seven"), 0),
            LStep.Tap("From d4 the queen sees e8 instantly. Tap e8 — the enemy's royal square.", fenOf("wQd4", "wKe1", "bKe8"), setOf(sq8("e8"))),
            LStep.Talk("She is your blade. Trade her only for her equal — never for firewood.", fenOf("wQd4", "wKe1", "bKe8")),
        )))
        add(Lesson(8, Gate.BOARD, "The Rook", listOf(
            LStep.Talk("The rook owns straight lines: files, ranks, nothing diagonal. Five points of iron.", fenOf("wRa1", "wKe3", "bKe8"), arrows = slideSquares(fenOf("wRa1", "wKe3", "bKe8"), sq8("a1"), ROOK_DIRS).map { sq8("a1") to it }),
            LStep.Tap("Tap any square the a1 rook can reach across the empty field.", fenOf("wRa1", "wKe3", "bKe8"), slideSquares(fenOf("wRa1", "wKe3", "bKe8"), sq8("a1"), ROOK_DIRS)),
            LStep.Choice("A rook can slide diagonally when unblocked.", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Choice("Rooks cannot jump over pieces.", EMPTY_FEN, listOf("True", "False"), 0),
            LStep.Talk("A rook's value doubles on an open file and on the seventh rank. Aim your towers there.", fenOf("wRa1", "wKe3", "bKe8")),
            LStep.Choice("The rook is worth…", EMPTY_FEN, listOf("Five pawns", "Three pawns", "Nine pawns"), 0),
        )))
        add(Lesson(9, Gate.BOARD, "The Bishop", listOf(
            LStep.Talk("Bishops live on diagonals and never leave their birth color. Three points, infinite reach when the lanes open.", fenOf("wBc1", "wKe1", "bKe8"), arrows = slideSquares(fenOf("wBc1", "wKe1", "bKe8"), sq8("c1"), BISHOP_DIRS).map { sq8("c1") to it }),
            LStep.Tap("Tap any square the c1 bishop commands.", fenOf("wBc1", "wKe1", "bKe8"), slideSquares(fenOf("wBc1", "wKe1", "bKe8"), sq8("c1"), BISHOP_DIRS)),
            LStep.Choice("Can a light-squared bishop ever capture on a dark square?", EMPTY_FEN, listOf("Yes", "Never"), 1),
            LStep.Choice("The bishop is worth…", EMPTY_FEN, listOf("Three pawns", "Five pawns"), 0),
            LStep.Tap("Two bishops cover every color as a pair. Tap the square the PAIR would cover that this one can't: d1 — the dark diagonal home.", fenOf("wBc1", "wKe1", "bKe8"), setOf(sq8("d1"))),
            LStep.Talk("Fianchetto, long diagonal, pin engine — the bishop is a sniper. Keep the glass clean.", fenOf("wBc1", "wKe1", "bKe8")),
        )))
        add(Lesson(10, Gate.BOARD, "Knight & Pawn", listOf(
            LStep.Talk("The knight jumps — over anything, in an L. The only piece that ignores walls. Three points.", fenOf("wNd4", "wKe1", "bKe8"), arrows = knightJumps(sq8("d4")).map { sq8("d4") to it }),
            LStep.Tap("Tap any landing square of this knight's jump.", fenOf("wNd4", "wKe1", "bKe8"), knightJumps(sq8("d4"))),
            LStep.Choice("The knight may jump over other pieces.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("The pawn marches forward one square — two from its home row — but it KILLS diagonally.", fenOf("wPe4", "wKe1", "bKe8", "bPd5"), arrows = listOf(sq8("e4") to sq8("e5"), sq8("e4") to sq8("d5"))),
            LStep.Tap("Tap the square the e4 pawn can CAPTURE on.", fenOf("wPe4", "wKe1", "bKe8", "bPd5"), setOf(sq8("d5"))),
            LStep.Choice("Pawns may move backward when attacked.", EMPTY_FEN, listOf("Yes", "No"), 1),
        )))

        // ── GATE II — THE GOAL & SPECIAL RULES (11–20) ──────────────────────
        add(Lesson(11, Gate.RULES, "Check", listOf(
            LStep.Talk("Check = the enemy king is under fire RIGHT NOW. It must be answered: run, block, or capture the attacker.", fenOf("wKe1", "bKe8", "wRe4"), tints = listOf(sq8("e8"))),
            LStep.MoveTask("Play it: slide the rook up the file and sound the alarm.", fenOf("wKe1", "bKe8", "wRe4"), "e4e6",
                postArrows = listOf(sq8("e6") to sq8("e8")), postChecks = listOf(sq8("e8"))),
            LStep.Talk("The red frame marks a king on fire. Under observational law you may IGNORE it — and lose him next move.", fenOf("wKe1", "bKe8", "wRe6"), checks = listOf(sq8("e8")), arrows = listOf(sq8("e6") to sq8("e8"))),
            LStep.Choice("Your king is checked. You must answer it immediately (or risk the crown).", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Choice("A king may step INTO check.", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Choice("Checkmate ends the game at once — unless the attacker blunders the crown first.", EMPTY_FEN, listOf("Yes", "No"), 0),
        )))
        add(Lesson(12, Gate.RULES, "Checkmate", listOf(
            LStep.Talk("Checkmate: the king burns and there is NO answer. The war is over. Study the coffin — rank sealed, pawns blocking the exits.", fenOf("wRd1", "wKg1", "bKg8", "bPf7", "bPg7", "bPh7"), arrows = listOf(sq8("d1") to sq8("d8"))),
            LStep.MoveTask("Execute: Rd8 — the back-rank guillotine.", fenOf("wRd1", "wKg1", "bKg8", "bPf7", "bPg7", "bPh7"), "d1d8"),
            LStep.Talk("After the strike the f8/h8 squares stay covered by the rook's rank; the pawn wall buries the seventh. Mate — total silence.", pack("m1").fen),
            LStep.Choice("Scan the final position: checkmate, stalemate, or neither?", "3R2k1/5ppp/8/8/8/8/8/6K1 b - - 1 1", listOf("Checkmate", "Stalemate", "Neither"), 0, truth = Truth.MATE),
            LStep.Choice("Checkmate beats ALL other results in chess — no points needed.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Every mate pattern you learn is a reusable blade. The System will drill them into reflex.", fenOf("wRd8", "wKg1", "bKg8", "bPf7", "bPg7", "bPh7")),
        )))
        add(Lesson(13, Gate.RULES, "Stalemate", listOf(
            LStep.Talk("Stalemate: the side to move has NO legal move AND is NOT in check. The war freezes — a draw.", "k7/8/1K1Q4/8/8/8/8/8 b - - 0 1"),
            LStep.Talk("Black's king has no squares (a7, b7, b8 all covered) — but nobody is attacking him. Frozen, not executed.", "k7/8/1K1Q4/8/8/8/8/8 b - - 0 1", tints = listOf(sq8("a7"), sq8("b7"), sq8("b8"))),
            LStep.Choice("This position is…", "k7/8/1K1Q4/8/8/8/8/8 b - - 0 1", listOf("Checkmate", "Stalemate", "Neither"), 1, truth = Truth.STALEMATE),
            LStep.Choice("Stalemate scores…", EMPTY_FEN, listOf("A draw — half a point each", "A win for the stronger side"), 0),
            LStep.Choice("When you are LOSING, stalemate can be a rescue rope.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Winning habit: when you are crushing, always gift the king ONE square. Mercy is efficient.", EMPTY_FEN),
        )))
        add(Lesson(14, Gate.RULES, "Draws of War", listOf(
            LStep.Talk("Stalemate is one road to peace. The others: repetition, the fifty-move cap, and dead material."),
            LStep.Talk("Same position THREE times with the same mover — either hunter may claim the draw.", EMPTY_FEN),
            LStep.Talk("Fifty moves without a pawn step or a capture — the clock itself declares peace.", EMPTY_FEN),
            LStep.Talk("King vs king, king-and-knight vs king — no mating net exists. Dead material = drawn instantly.", fenOf("wKe4", "bKe6", "wNb3")),
            LStep.Choice("King and knight against a bare king is…", fenOf("wKe4", "bKe6", "wNb3"), listOf("Forcible mate", "An automatic draw"), 1),
            LStep.Choice("Threefold repetition is claimable.", EMPTY_FEN, listOf("Yes", "No"), 0),
        )))
        add(Lesson(15, Gate.RULES, "Castling Kingside", listOf(
            LStep.Talk("The king's once-a-game sprint: two squares toward a rook while the rook vaults over him. Kingside: e1→g1, rook to f1.", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", arrows = listOf(sq8("e1") to sq8("g1"), sq8("h1") to sq8("f1"))),
            LStep.MoveTask("Do it: O-O. Play the king to g1.", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1g1", postArrows = listOf(sq8("h1") to sq8("f1"))),
            LStep.Tap("The king crossed two squares. Tap BOTH of them.", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", setOf(sq8("f1"), sq8("g1")), all = true),
            LStep.Choice("Castling is the only legal two-square king move.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Choice("You may castle while your king is in check.", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Talk("Why castle: the king leaves the center before the bullets arrive. Safety is tempo.", "5rk1/8/8/8/8/8/8/5RK1 w - - 0 1"),
        )))
        add(Lesson(16, Gate.RULES, "Castling — the Laws", listOf(
            LStep.Talk("Queenside: e1→c1, rook a1 lands on d1. Same law — but b1/c1/d1 must all be clear.", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", arrows = listOf(sq8("e1") to sq8("c1"), sq8("a1") to sq8("d1"))),
            LStep.MoveTask("O-O-O: slide the king all the way to c1.", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1c1", postArrows = listOf(sq8("a1") to sq8("d1"))),
            LStep.Talk("The firewall: a black rook fires down the f-file. f1 is COVERED by the enemy.", "r3kr2/8/8/8/8/8/8/R3K2R w KQkq - 0 1", arrows = listOf(sq8("f8") to sq8("f1"))),
            LStep.Choice("f1 is attacked right now. Is kingside castling legal here?", "r3kr2/8/8/8/8/8/8/R3K2R w KQkq - 0 1", listOf("Legal", "Illegal"), 1),
            LStep.Choice("Castling is still possible queenside on this board.", "r3kr2/8/8/8/8/8/8/R3K2R w KQkq - 0 1", listOf("Yes", "No"), 0),
            LStep.Choice("Once the king or rook has MOVED, the castle right on that side survives.", EMPTY_FEN, listOf("Yes", "No"), 1),
        )))
        add(Lesson(17, Gate.RULES, "En Passant", listOf(
            LStep.Talk("The ghost capture: when an enemy pawn sprints TWO squares and lands BESIDE your pawn, you may strike it as if it moved one.", "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2", arrows = listOf(sq8("e5") to sq8("d6"))),
            LStep.MoveTask("Take the ghost: exd6 en passant. The d5 pawn vanishes.", "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2", "e5d6"),
            LStep.Tap("Tap the square the captured pawn STOOD on.", "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2", setOf(sq8("d5"))),
            LStep.Choice("En passant is available only on the very next move — hesitate and it expires.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Choice("A pawn can capture en passant on its own file.", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Talk("It exists so cowards cannot sprint past a locked pawn chain. French law, 64-square justice.", EMPTY_FEN),
        )))
        add(Lesson(18, Gate.RULES, "Promotion", listOf(
            LStep.Talk("A pawn that reaches the far rank is reborn: queen, rook, bishop or knight. Always its owner's choice.", "4k3/2P5/8/8/8/8/8/4K3 w - - 0 1", arrows = listOf(sq8("c7") to sq8("c8"))),
            LStep.MoveTask("Promote: c8=QUEEN. The coronation.", "4k3/2P5/8/8/8/8/8/4K3 w - - 0 1", "c7c8q"),
            LStep.Choice("The promoted piece is chosen immediately and cannot stay a pawn.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Choice("Underpromotion to a KNIGHT is sometimes the killer (checks a queen can't give).", EMPTY_FEN, listOf("True", "Myth"), 0),
            LStep.Choice("A promoted queen may be taken back and demoted later.", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Talk("Pawn endgames hinge on this square math. Lesson 60 drills the sprint rule.", EMPTY_FEN),
        )))
        add(Lesson(19, Gate.RULES, "Reading the Scorecard", listOf(
            LStep.Talk("Algebraic notation: pieces by letter (N, B, R, Q, K), pawns by nothing. e4 = a pawn to e4; Nf3 = the knight lands on f3."),
            LStep.Tap("Nf3: tap the square named in that move.", START_FEN, setOf(sq8("f3"))),
            LStep.Talk("x marks the kill: exd5 = the e-pawn captures ON d5. + marks check, # marks mate.", fenOf("wPe4", "wKe1", "bKe8", "bPd5"), arrows = listOf(sq8("e4") to sq8("d5"))),
            LStep.Choice("O-O means…", EMPTY_FEN, listOf("Kingside castle", "Queenside castle", "Two pawns trade"), 0),
            LStep.Choice("Rxe8+ translates to…", EMPTY_FEN, listOf("A rook captures on e8, giving check", "The rook retreats to e8"), 0),
            LStep.Choice("Which letter does NOT exist in notation?", EMPTY_FEN, listOf("P for pawn", "N for knight", "K for king"), 0),
        )))
        add(Lesson(20, Gate.RULES, "The Goal — Arena Law", listOf(
            LStep.Talk("The crown is the whole game. The System plays observational law: hung kings die by KING CRUSH — instantly, cinematically.", fenOf("wKe4", "bKe6")),
            LStep.Choice("Under observational law you MAY play a move that leaves your own king in check.", EMPTY_FEN, listOf("Yes — but the enemy may crush him", "No — the board forbids it"), 0),
            LStep.Choice("A checked king glows what color in this arena?", EMPTY_FEN, listOf("Red frame", "Blue wash", "No signal"), 0),
            LStep.Choice("Checkmate and KING CRUSH both trigger…", EMPTY_FEN, listOf("The finisher cutscene", "A silent points toast"), 0),
            LStep.Choice("Resigning is always legal when a war is lost.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Learn the nets. Build them. When the crown falls in YOUR favor — enjoy the show.", fenOf("wKe4", "bKe6")),
        )))

        // ── GATE III — FIRST CHECKMATES (21–24) ─────────────────────────────
        add(Lesson(21, Gate.FIRST_MATES, "First Blood: the Back Rank", listOf(
            LStep.Talk("The classic coffin: a king sealed behind his own pawns dies to a rook or queen entering the back rank.", pack("m1").fen, arrows = listOf(sq8("d1") to sq8("d8"))),
            LStep.Auto("Watch the whole strike, slide by slide.", pack("m1").fen, pack("m1").line, "The rank sealed itself. Total silence."),
            LStep.Talk("Notice: f7, g7, h7 block the escape; the rook's rank erases f8 and h8. Coffins are BUILT, not found.", pack("m1").fen, tints = listOf(sq8("f7"), sq8("g7"), sq8("h7"))),
            LStep.Test("LESSON TEST — deliver the back-rank coffin in three positions.", listOf(
                packItem("m1"), packItem("m12"), packItem("m17"),
            )),
            LStep.Choice("The back-rank mate needs the enemy escape squares sealed — usually by his own pawns.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Defend it the same way: luft — push a pawn to make the king an escape window before the sniper arrives.", EMPTY_FEN),
        )))
        add(Lesson(22, Gate.FIRST_MATES, "The Queen Corridor", listOf(
            LStep.Talk("Queen + king shrink the box until the queen lands beside the enemy king — protected by her own monarch.", pack("m13").fen, arrows = listOf(sq8("h3") to sq8("c8"))),
            LStep.Auto("The corridor collapses in one slide.", pack("m13").fen, pack("m13").line, "The wing closed like a vault door."),
            LStep.Talk("The queen mates on the rank or file NEAREST the trapped king — always bodyguarded by your king. No bodyguard, no mate.", pack("m6").fen, tints = listOf(sq8("a6"))),
            LStep.Test("LESSON TEST — three corridor executions.", listOf(
                packItem("m13"), packItem("m15"), packItem("m16"),
            )),
            LStep.Choice("The mating queen must be defended when she lands next to the king.", EMPTY_FEN, listOf("True", "False"), 0),
            LStep.Talk("Cut first, approach later. The corridor method never rushes the queen alone into the edge.", EMPTY_FEN),
        )))
        add(Lesson(23, Gate.FIRST_MATES, "The Ladder Mate", listOf(
            LStep.Talk("Two rooks walk the enemy king off the board one rank at a time — the ladder. The simplest forced mate there is.", pack("m11").fen),
            LStep.Auto("Watch the ladder descend: check, king steps back, the second rook takes the next rank.", pack("m5").fen, pack("m5").line, "Rank by rank. Mechanical. Merciful."),
            LStep.Talk("The rooks NEVER check from adjacent ranks into capture range — the ladder stays one rank ahead.", pack("m5").fen),
            LStep.Test("LESSON TEST — close the ladder three times.", listOf(
                packItem("m11"), packItem("m19"), packItem("m8"),
            )),
            LStep.Choice("In the ladder mate the rooks alternate ranks, driving the king toward…", EMPTY_FEN, listOf("The board's edge", "The center"), 0),
            LStep.Talk("Your king can sip coffee — two rooks finish alone. But only if the ladder never breaks ranks.", EMPTY_FEN),
        )))
        add(Lesson(24, Gate.FIRST_MATES, "Scholar's Mate — Hunt & Defense", listOf(
            LStep.Talk("The cheap shot every predator tries once: queen and bishop stack on f7 beside a sleeping king.", "r1bqkbnr/pppppppp/2n5/4p3/2B1P3/8/PPPP1PPP/RNBQK2R b KQkq - 1 2", arrows = listOf(sq8("c4") to sq8("f7"), sq8("d1") to sq8("h5"))),
            LStep.Auto("The full ambush: e4 e5, Bc4 Nc6, Qh5 Nf6??, Qxf7#. Never fall for it again.", START_FEN, listOf("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7"), "Qxf7# — the classroom favorite."),
            LStep.Talk("Black's refutation is one move: ...g6, hitting the queen and sealing the diagonal.", "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3", arrows = listOf(sq8("g7") to sq8("g6"))),
            LStep.Test("LESSON TEST — hunt OR block, by the clock.", listOf(
                packItem("m2"),
                TestItem("r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3", "g7g6", "DEFEND: chase the queen"),
                packItem("m13"),
            )),
            LStep.Choice("Scholar's mate works because f7 is defended ONLY by the king at the start.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("You now know both sides of the ambush. Use it on the careless; snuff it on the careless you.", EMPTY_FEN),
        )))

        // ── GATE IV — MATERIAL & VALUE (25–29) ──────────────────────────────
        add(Lesson(25, Gate.MATERIAL, "Point Count", listOf(
            LStep.Talk("The ledger: pawn 1, knight 3, bishop 3, rook 5, queen 9, king — priceless.", fenOf("wQd4", "wNf3", "wBc4", "wRa1", "wPe4", "wKe2", "bKe8")),
            LStep.Tap("Tap the most valuable attacker on this board.", fenOf("wQd4", "wNf3", "wBc4", "wRa1", "wPe4", "wKe2", "bKe8"), setOf(sq8("d4"))),
            LStep.Choice("A knight is worth about…", EMPTY_FEN, listOf("Three pawns", "One pawn", "Five pawns"), 0),
            LStep.Choice("Queen + rook outweigh two queens.", EMPTY_FEN, listOf("True", "False"), 1),
            LStep.Choice("The king's point value is…", EMPTY_FEN, listOf("Infinite — his loss ends the war", "Ten"), 0),
            LStep.Talk("Points guide trades; positions guide points. The next four lessons turn counts into instinct.", EMPTY_FEN),
        )))
        add(Lesson(26, Gate.MATERIAL, "Counting the War", listOf(
            LStep.Talk("Count fast through clamps and forks: pieces first, pawns second, structure last. Speed wins clocks.", fenOf("wQe2", "wRd1", "wKg1", "bKe8", "bRa8", "bNb8")),
            LStep.Choice("Count the board: White queen+rook. Black rook+knight. Who leads, and by what?", fenOf("wQe2", "wRd1", "wKg1", "bKe8", "bRa8", "bNb8"), listOf("White +6", "Dead level", "Black +2"), 0),
            LStep.Talk("Same count, different weight: activity multiplies points. A pinned rook is half a rook.", fenOf("wQe2", "wRd1", "wKg1", "bKe8", "bRa8", "bNb8")),
            LStep.Choice("Two bishops usually out-value a bishop+knight pair in open play.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Choice("Counting ends the second you spot…", EMPTY_FEN, listOf("A forced mate — skip the ledger", "A pawn majority"), 0),
            LStep.Talk("Every capture asks: what does the board look like AFTER the dust? Count the dust, not the explosion.", EMPTY_FEN),
        )))
        add(Lesson(27, Gate.MATERIAL, "The Trade Law", listOf(
            LStep.Talk("Trade UP: your bishop for their rook buys two points. Trade equal ONLY when it cracks a structure open.", fenOf("wBc2", "wKg1", "bKe8", "bRc4")),
            LStep.MoveTask("Collect the exchange: bishop takes the rook.", fenOf("wBa2", "wKg1", "bKe8", "bRc4"), "a2c4"),
            LStep.Choice("Your queen for both enemy rooks (9 vs 10) is usually…", EMPTY_FEN, listOf("Good business", "Robbery against you"), 0),
            LStep.Choice("When AHEAD on material, trading pieces (not pawns) simplifies toward…", EMPTY_FEN, listOf("The full point", "A stalemate trap"), 0),
            LStep.Choice("When behind, you should trade pawns and keep pieces for counterplay.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Trades are negotiations with knives. Only sign when the exchange rate favors the System.", EMPTY_FEN),
        )))
        add(Lesson(28, Gate.MATERIAL, "Activity Beats Points", listOf(
            LStep.Talk("A skewer is worth a rook when it arrives with tempo — watch a bishop order a king aside and eat the tower behind him.", pack("s2").fen, arrows = listOf(sq8("a2") to sq8("d5"))),
            LStep.Auto("The whole skewer sequence.", pack("s2").fen, pack("s2").line, "Five points collected by three."),
            LStep.Choice("An active bishop can outgun a passive rook despite the point table.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Choice("Activity means…", EMPTY_FEN, listOf("Squares controlled and threats made", "Moves played quickly"), 0),
            LStep.Test("LESSON TEST — find the high-activity strike in three boards.", listOf(
                packItem("s2"), packItem("t11"), packItem("t8"),
            )),
            LStep.Talk("Points are salary; activity is cash flow. Never starve your own pieces to feed the count.", EMPTY_FEN),
        )))
        add(Lesson(29, Gate.MATERIAL, "The Exchange", listOf(
            LStep.Talk("Rook for minor piece = 'the exchange' (+2). Most decisive small edge in club chess.", fenOf("wRd1", "wKe1", "bKe8", "bNd5")),
            LStep.MoveTask("Take it: rook removes the knight, clean exchange in the bank.", fenOf("wRd1", "wKe1", "bKe8", "bNd5"), "d1d5"),
            LStep.Choice("Winning the exchange leaves you roughly…", EMPTY_FEN, listOf("Two pawns ahead in value", "Level"), 0),
            LStep.Choice("Quality players sometimes SACRIFICE the exchange for grip or dark-square control.", EMPTY_FEN, listOf("True", "Never correct"), 0),
            LStep.Choice("Exchange up, the correct plan is usually…", EMPTY_FEN, listOf("Open files and squeeze", "Hide the rook"), 0),
            LStep.Talk("Count, then plan. The exchange is money — money spends on attack.", EMPTY_FEN),
        )))

        // ── GATE V — OPENINGS (30–34) ───────────────────────────────────────
        add(Lesson(30, Gate.OPENINGS, "The Golden Rules", listOf(
            LStep.Talk("Rule one: center. Rule two: develop every minor piece. Rule three: castle before move ten.", START_FEN, tints = CENTER4.toList()),
            LStep.Tap("Tap the two squares most armies fight over first.", EMPTY_FEN, setOf(sq8("e4"), sq8("d4")), all = true),
            LStep.Choice("Move the same piece three times in the opening — good?", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Choice("The queen should charge out on move two.", EMPTY_FEN, listOf("Yes", "No — she gets chased, losing time"), 1),
            LStep.Choice("Knights before bishops, castle before greed.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("The next three lessons hand you three real battle plans — Italian, Sicilian, French.", START_FEN),
        )))
        add(Lesson(31, Gate.OPENINGS, "The Italian Way", listOf(
            LStep.Talk("The Italian: seize a center pawn, develop with tempo, bishop eyes f7. Four moves of pure logic — play them yourself.", START_FEN),
            LStep.MoveTask("One: claim the center.", START_FEN, "e2e4", reply = "e7e5"),
            LStep.MoveTask("Two: develop with pressure.", "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2", "g1f3", reply = "b8c6"),
            LStep.MoveTask("Three: the bishop arrives on its best diagonal, staring at f7.", "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", "f1c4", reply = "f8c5"),
            LStep.Talk("Position reached: knight centralized, bishop sniping the weakest point, castling one move away.", "r1bqk1nr/pppp1ppp/2n5/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4", arrows = listOf(sq8("c4") to sq8("f7"))),
            LStep.Choice("The Italian's bishop belongs on…", EMPTY_FEN, listOf("c4", "e2", "d3"), 0),
        )))
        add(Lesson(32, Gate.OPENINGS, "The Sicilian Gate", listOf(
            LStep.Talk("Black's sharpest answer: 1...c5. No symmetry — imbalance from move one.", START_FEN),
            LStep.MoveTask("White claims the center.", START_FEN, "e2e4", reply = "c7c5"),
            LStep.MoveTask("Develop and prepare the break.", "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2", "g1f3", reply = "d7d6"),
            LStep.MoveTask("Strike: d4 opens the position.", "rnbqkbnr/pp2pppp/3p4/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3", "d2d4", reply = "c5d4"),
            LStep.MoveTask("Recapture with the knight — pieces flood the center.", "rnbqkbnr/pp2pppp/3p4/8/3pP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 0 4", "f3d4"),
            LStep.Choice("The Sicilian trades the c-pawn for White's…", EMPTY_FEN, listOf("d-pawn — a center pawn", "f-pawn"), 0),
            LStep.Choice("Sicilian play is famous for…", EMPTY_FEN, listOf("Counterattack, never symmetry", "Boring draws"), 0),
        )))
        add(Lesson(33, Gate.OPENINGS, "The French Wall", listOf(
            LStep.Talk("The French: 1...e6 then ...d5 — a pawn wall d5/e6. Slow, solid, poisonous.", START_FEN),
            LStep.MoveTask("Advance the royal pawn.", START_FEN, "e2e4", reply = "e7e6"),
            LStep.MoveTask("Claim the whole center while they're polite.", "rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2", "d2d4", reply = "d7d5"),
            LStep.MoveTask("Defend e4 with development.", "rnbqkbnr/ppp2ppp/4p3/3p4/3PP3/8/PPP2PPP/RNBQKBNR w KQkq - 0 3", "b1c3", reply = "g8f6"),
            LStep.Talk("The wall stands: black will chip at d4 forever; white must expand or suffocate slowly.", "rnbqkb1r/ppp2ppp/4pn2/3p4/3PP3/2N5/PPP2PPP/R1BQKBNR w KQkq - 2 4", tints = listOf(sq8("d5"), sq8("e6"))),
            LStep.Choice("The French pawn chain's weakness is…", EMPTY_FEN, listOf("The light-square bishop behind the wall", "The queen"), 0),
            LStep.Choice("Patience suits the French — both sides prepare before they punch.", EMPTY_FEN, listOf("Yes", "No"), 0),
        )))
        add(Lesson(34, Gate.OPENINGS, "The f7 Corridor", listOf(
            LStep.Talk("f7 starts defended by the king alone — the softest door in the house. Half of amateur chess is f7 getting kicked in.", START_FEN, arrows = listOf(sq8("c4") to sq8("f7"))),
            LStep.Auto("Full Scholar demonstration — four moves to the crown. Then never lose to it again.", START_FEN, listOf("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7"), "Qxf7#. Brutal, legal, and dead forever for you."),
            LStep.MoveTask("Black's antidote: play ...g6 and chase the queen off the h5-e8 diagonal.", "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3", "g7g6"),
            LStep.Choice("After ...g6 the queen must leave — White spent two moves and gained…", EMPTY_FEN, listOf("Nothing but lost time", "A crushing attack"), 0),
            LStep.Choice("f2 is WHITE's corresponding soft door.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Openings are doors and corridors. Mark the weak doors; guard yours, kick theirs.", START_FEN),
        )))

        // ── GATE VI — TACTICS (35–48) ───────────────────────────────────────
        add(Lesson(35, Gate.TACTICS, "The Fork", listOf(
            LStep.Talk("One move, two victims, one answer impossible — the knight fork is the oldest terror in the book.", pack("f3").fen),
            LStep.Auto("Nf6+: the king runs, the queen dies.", pack("f3").fen, pack("f3").line, "Crown or queen — black chose the crown."),
            LStep.Talk("Fork squares are knight-jump circles around BOTH targets. Scan the circle, not the piece.", pack("f1").fen, tints = listOf(sq8("c7"))),
            LStep.Test("LESSON TEST — three forks to land.", listOf(
                packItem("f1"), packItem("f2"), packItem("f3"),
            )),
            LStep.Choice("Only knights can fork meaningfully.", EMPTY_FEN, listOf("True", "False — pawns and queens fork too"), 1),
            LStep.Talk("Defensive law: keep king and queen off the same knight-circle. Geometry saves crowns.", EMPTY_FEN),
        )))
        add(Lesson(36, Gate.TACTICS, "The Pin", listOf(
            LStep.Talk("A pinned piece is a frozen piece: behind it stands something it dare not abandon — usually the king.", pack("p1").fen),
            LStep.Auto("Rook to e1: the knight freezes; the rook harvests it next move.", pack("p1").fen, pack("p1").line, "Pinned to the king. Then eaten."),
            LStep.Talk("Absolute pins (to the king) are iron; relative pins (to the queen) are rope. Both strangle.", pack("p3").fen),
            LStep.Test("LESSON TEST — freeze them three times.", listOf(
                packItem("p1"), packItem("p3"), packItem("p4"),
            )),
            LStep.Choice("A pinned piece still DEFENDS squares — fever isn't freedom.", EMPTY_FEN, listOf("True", "False"), 1),
            LStep.Talk("Pin, then pile attackers on the pinned man. The pin is the leash; numbers are the dogs.", EMPTY_FEN),
        )))
        add(Lesson(37, Gate.TACTICS, "The Skewer", listOf(
            LStep.Talk("The skewer: strike the king THROUGH — he steps aside, and whatever stood behind him falls.", pack("s1").fen),
            LStep.Auto("Ra8+: king off the file, the loose queen behind him is collected.", pack("s1").fen, pack("s1").line, "Check, step, harvest."),
            LStep.Talk("File, rank or diagonal — any line piece can skewer. The geometry: VIP in front, cargo behind.", pack("s2").fen, arrows = listOf(sq8("a2") to sq8("d5"))),
            LStep.Test("LESSON TEST — run three skewers through.", listOf(
                packItem("s1"), packItem("s2"), packItem("t2"),
            )),
            LStep.Choice("Skewer targets must sit on one line with a heavier piece in front.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("When your king leads a column, straighten the column or lose the cargo.", EMPTY_FEN),
        )))
        add(Lesson(38, Gate.TACTICS, "The Discovered Attack", listOf(
            LStep.Talk("Two pieces on one line: the front piece MOVES, unveiling the back piece's beam. The mover wins tempo while the beam bites.", "4k3/8/8/4N3/3q4/8/7P/4R2K w - - 0 1"),
            LStep.Auto("The knight steps away with check — and the rook's file eats the queen.", "4k3/8/8/4N3/3q4/8/7P/4R2K w - - 0 1", listOf("e5c6", "e8d7", "c6d4"), "Discovery, tempo, collection."),
            LStep.Talk("The moving piece should grab ANYTHING — it's free tempo. The beam does the real damage.", pack("dc1").fen),
            LStep.Test("LESSON TEST — pull the curtain three times.", listOf(
                packItem("dc1"), packItem("t8"), packItem("t7"),
            )),
            LStep.Choice("A discovered CHECK plus a queen-grab by the mover is the richest version.", EMPTY_FEN, listOf("True", "False"), 0),
            LStep.Talk("Beware symmetric discovery — their batteries hurt exactly like yours.", EMPTY_FEN),
        )))
        add(Lesson(39, Gate.TACTICS, "The Double Attack", listOf(
            LStep.Talk("The queen fork: one slide, two undefended targets. The defender answers one — you take the other.", "2r1k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"),
            LStep.Auto("Qe5+: check the king, thread the rook. King moves; rook dies.", "2r1k3/8/8/8/8/8/4Q3/4K3 w - - 0 1", listOf("e2e5", "e8d8", "e5c5"), "One strike, two addresses."),
            LStep.Talk("A double attack NEEDS both targets loose, or one target royal. Count defenders twice here.", EMPTY_FEN),
            LStep.Test("LESSON TEST — hit two targets with one blow, thrice.", listOf(
                TestItem("2r1k3/8/8/8/8/8/4Q3/4K3 w - - 0 1", "e2e5", "DOUBLE: king + rook"),
                packItem("f3"), packItem("t3"),
            )),
            LStep.Choice("Queens are the best doublers because they see in eight directions.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Loose pieces drop off. Scan for loose pieces EVERY move — yours and theirs.", EMPTY_FEN),
        )))
        add(Lesson(40, Gate.TACTICS, "Deflection", listOf(
            LStep.Talk("Deflection drags a defender off its duty. Overloaded squares can't be two places at once.", pack("df1").fen),
            LStep.Auto("Rd8+ hauls the queen to f8 — where the rook eats her anyway. Forced march.", pack("df1").fen, pack("df1").line, "The guard was the meal."),
            LStep.Talk("Look for a single piece holding the whole line. Then price it. Then drag it off.", pack("df1").fen),
            LStep.Test("LESSON TEST — drag three guards off their posts.", listOf(
                packItem("df1"), packItem("m21"), packItem("m24"),
            )),
            LStep.Choice("Deflection often LOOKS like a blunder until the second move.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Ask on every defense: can I summon the defender elsewhere? That question wins games.", EMPTY_FEN),
        )))
        add(Lesson(41, Gate.TACTICS, "Removing the Defender", listOf(
            LStep.Talk("Every guarded piece is one capture away from hanging. Remove the guard FIRST — the back-rank rook was the whole wall.", pack("m25").fen),
            LStep.Auto("Qxc8+: the 'defender' was the load-bearing wall. Take the guard, the roof comes free.", pack("m25").fen, pack("m25").line, "The guard became the grave."),
            LStep.Talk("The calculation law: count attackers and defenders THROUGH every capture, not just the first.", EMPTY_FEN),
            LStep.Test("LESSON TEST — remove the defender three times.", listOf(
                TestItem(pack("m25").fen, "c7c8", "REMOVE: the guard IS the wall"),
                packItem("t1"), packItem("b2"),
            )),
            LStep.Choice("If defenders equal attackers, the target is…", EMPTY_FEN, listOf("Not safe enough — count the prices", "Safe forever"), 0),
            LStep.Talk("Removing a defender is quiet violence — the board notices one move late.", EMPTY_FEN),
        )))
        add(Lesson(42, Gate.TACTICS, "Overload", listOf(
            LStep.Talk("One piece, two jobs, impossible hours. Attack both posts in sequence and the worker collapses.", pack("m24").fen),
            LStep.Auto("The rook sac wave: Ra8+ forces Rxa8, then the queen inherits the a8 throne and the crown.", pack("m24").fen, pack("m24").line, "Two jobs. One body. Mate."),
            LStep.Talk("Find the piece that holds two squares. That piece is your real target — everything else is noise.", pack("m21").fen),
            LStep.Test("LESSON TEST — overload three defenders.", listOf(
                packItem("m24"), packItem("m21"), packItem("m26"),
            )),
            LStep.Choice("Overloads crack when you force BOTH duties at once.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Defense in depth dies to overload; defense in chain dies to deflection. Know which wall you're punching.", EMPTY_FEN),
        )))
        add(Lesson(43, Gate.TACTICS, "Zwischenzug — the In-Between", listOf(
            LStep.Talk("Before the 'obvious' recapture, slide in a check. Forcing replies reset the tempo — then take.", pack("m17").fen),
            LStep.Auto("The rook check IS the in-between move that makes the back-rank capture lethal.", pack("m17").fen, pack("m17").line, "Check first. Collect after."),
            LStep.Talk("Zwischenzug punishes autopilot counting. Never assume your opponent will recapture immediately.", EMPTY_FEN),
            LStep.Test("LESSON TEST — find the forcing in-between shot.", listOf(
                packItem("m17"), packItem("m19"), packItem("m12"),
            )),
            LStep.Choice("An in-between move gains its value from…", EMPTY_FEN, listOf("Being FORCING (check / capture / threat)", "Being fancy"), 0),
            LStep.Talk("The order of operations IS the tactic. Check, then collect.", EMPTY_FEN),
        )))
        add(Lesson(44, Gate.TACTICS, "Clearance", listOf(
            LStep.Talk("Clearance: vacate the square, the file, or the diagonal for a stronger friend — even by sacrifice.", pack("m23").fen),
            LStep.Talk("The d-file here is the highway. The queen's arrival on d8 seals the coffin behind the pawns' own backs.", pack("m23").fen, arrows = listOf(sq8("d3") to sq8("d8"))),
            LStep.MoveTask("Clear and mate: Qd8 — the file belongs to you.", pack("m23").fen, "d3d8"),
            LStep.Test("LESSON TEST — clear three lanes.", listOf(
                packItem("m23"), packItem("m3"), packItem("m7"),
            )),
            LStep.Choice("Clearance sacrifices are sound when the freed lane is worth more than the piece.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Sometimes the piece blocking your mate is YOURS. Clear your own road ruthlessly.", EMPTY_FEN),
        )))
        add(Lesson(45, Gate.TACTICS, "Perpetual Check", listOf(
            LStep.Talk("The emergency draw: checks that never stop. When losing, ask if their king can EVER hide. If not — half a point saved.", "6k1/5pp1/8/8/8/7Q/8/7K w - - 0 1"),
            LStep.Auto("The loop: Qc8+, Kh7, Qh3+... the king walks in circles forever.", "6k1/5pp1/8/8/8/7Q/8/7K w - - 0 1", listOf("h3c8", "g8h7", "c8h3", "h7g8"), "Round and round. Peace by exhaustion."),
            LStep.Choice("Perpetual check scores as…", EMPTY_FEN, listOf("A draw", "A win for the checker"), 0),
            LStep.Choice("A perpetual is a valid ESCAPE when materially lost.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Choice("With a winning position you should allow a perpetual.", EMPTY_FEN, listOf("Yes", "No"), 1),
            LStep.Talk("The System's engines track repetitions — bluffing draws gets counted honestly.", EMPTY_FEN),
        )))
        add(Lesson(46, Gate.TACTICS, "Desperado", listOf(
            LStep.Talk("Doomed pieces should die EXPENSIVE. The desperado grabs the best loot available before falling.", pack("t9").fen),
            LStep.MoveTask("The bishop is loose either way — make it pay for the rook.", pack("t9").fen, "b3c4"),
            LStep.Talk("Count the board after the desperado lands, not before. Deathbed captures change ledgers.", EMPTY_FEN),
            LStep.Test("LESSON TEST — three last-supper captures.", listOf(
                packItem("t9"), packItem("t3"), packItem("t10"),
            )),
            LStep.Choice("A desperado works because the piece was already…", EMPTY_FEN, listOf("Doomed — the loot is free", "Defended"), 0),
            LStep.Talk("Before ANY allow: check whether the doomed piece has a final appetite.", EMPTY_FEN),
        )))
        add(Lesson(47, Gate.TACTICS, "The Back-Rank Raid", listOf(
            LStep.Talk("Tactical back-rankers: no time for pawn luft, the strike lands NOW. Recognize the smell: sealed escape + open rank.", "6k1/pp4pp/8/8/8/8/5Q2/6K1 w - - 0 1", arrows = listOf(sq8("f2") to sq8("f8"))),
            LStep.MoveTask("Qf8 — the corridor closes.", "6k1/pp4pp/8/8/8/8/5Q2/6K1 w - - 0 1", "f2f8"),
            LStep.Talk("Notice the pattern again: g7/h7/f7 are the king's own bars. His prison, your guillotine.", "6k1/pp4pp/8/8/8/8/5Q2/6K1 w - - 0 1", tints = listOf(sq8("f7"), sq8("g7"), sq8("h7"))),
            LStep.Test("LESSON TEST — raid three back ranks.", listOf(
                TestItem("6k1/pp4pp/8/8/8/8/5Q2/6K1 w - - 0 1", "f2f8", "RAID the cornered king"),
                packItem("m14"), packItem("m13"),
            )),
            LStep.Choice("The raid works even when the attacker is rated weaker — it's pure geometry.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Scan enemy back ranks like a habit. One unlufted house loses castles.", EMPTY_FEN),
        )))
        add(Lesson(48, Gate.TACTICS, "The Decoy", listOf(
            LStep.Talk("Decoy: bait the prey onto YOUR square — a poisoned piece that drags the king into a net.", pack("s1").fen),
            LStep.Auto("The queen shadows the back rank; the king steps off, drawn into the skewer's lane.", pack("s1").fen, pack("s1").line, "Lured, then looted."),
            LStep.Talk("Every sacrifice asks: what does the capture STEP ONTO? The square you bait is the blade.", EMPTY_FEN),
            LStep.Test("LESSON TEST — set three lures.", listOf(
                packItem("s1"), packItem("s2"), packItem("m5"),
            )),
            LStep.Choice("Decoys work because the bait is usually…", EMPTY_FEN, listOf("Forcing — the reply isn't free", "Free material"), 0),
            LStep.Talk("Guard the lure: baited kings bite deep. Never chase a free piece onto checked water.", EMPTY_FEN),
        )))

        // ── GATE VII — MATE PATTERNS (49–54) ────────────────────────────────
        add(Lesson(49, Gate.MATE_PATTERNS, "Pattern: Coffin Ranks", listOf(
            LStep.Talk("Back-rank mastery means recognizing the sealed house at bullet speed.", pack("m17").fen, arrows = listOf(sq8("c1") to sq8("c8"))),
            LStep.Auto("The trade-through: Rxc8+ and the last rook mates on the vacated lane.", pack("m17").fen, pack("m17").line, "The coffin accepted a trade."),
            LStep.Talk("f7/g7/h7 are the bars. The raider never needs help — the prison is self-made.", pack("m13").fen),
            LStep.Test("LESSON TEST — three coffins, three different tools.", listOf(
                packItem("m17"), packItem("m19"), packItem("m25"),
            )),
            LStep.Choice("A luft pawn (h6/h3) kills most back-rank ideas.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Your own house first: luft when the middlegame smoke rises.", EMPTY_FEN),
        )))
        add(Lesson(50, Gate.MATE_PATTERNS, "Pattern: Queen Corridors", listOf(
            LStep.Talk("Queen + king hunt in corridors: cut, approach, mate beside the trapped king with your king as the wall.", pack("m15").fen),
            LStep.Auto("One slide into the vault.", pack("m23").fen, pack("m23").line, "The corridor never missed."),
            LStep.Talk("The corridor queen should end up ADJACENT and DEFENDED — both, or it's just a check.", pack("m6").fen),
            LStep.Test("LESSON TEST — collapse three corridors.", listOf(
                packItem("m15"), packItem("m16"), packItem("m23"),
            )),
            LStep.Choice("A corridor mate's queen lands next to the enemy king, guarded by…", EMPTY_FEN, listOf("Her own king (or a piece)", "Nothing"), 0),
            LStep.Talk("Chase perpendicularly, finish orthogonally. The queen only commits at the last rank.", EMPTY_FEN),
        )))
        add(Lesson(51, Gate.MATE_PATTERNS, "Pattern: The Arabian Net", listOf(
            LStep.Talk("Rook + knight: the knight guards the rook's landing square AND seals the corner exits. Old as the desert.", "6k1/8/5K2/6N1/8/8/8/7R w - - 0 1", arrows = listOf(sq8("h1") to sq8("h8"), sq8("g5") to sq8("h7"))),
            LStep.MoveTask("Rh8 — the knight on g5 covers h7's escape. Arabian mate.", "6k1/8/5K2/6N1/8/8/8/7R w - - 0 1", "h1h8"),
            LStep.Talk("The knight owns the corner squares the rook's rank can't see. Two pieces, zero exits.", "6k1/8/5K2/6N1/8/8/8/7R w - - 0 1", tints = listOf(sq8("g7"), sq8("h7"))),
            LStep.Test("LESSON TEST — close three nets.", listOf(
                TestItem("6k1/8/5K2/6N1/8/8/8/7R w - - 0 1", "h1h8", "ARABIAN: rook + knight"),
                packItem("m19"), packItem("m20"),
            )),
            LStep.Choice("The Arabian net needs the enemy king boxed near a corner.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("When a knight eyes the palace, the corner is a cage, not a home.", EMPTY_FEN),
        )))
        add(Lesson(52, Gate.MATE_PATTERNS, "Pattern: The Sacrifice Wave", listOf(
            LStep.Talk("Two rooks die so the last one mates — or a queen inherits the throne the rooks bought. Sacrifice waves drown exact defenders.", pack("m21").fen),
            LStep.Auto("Ra8+!! Rxa8, Ra8+!! — the storm takes two towers to break the house.", pack("m21").fen, pack("m21").line, "Both rooks spent. The ledger says mate."),
            LStep.Talk("The math: if the LAST piece mates, every earlier piece was free. Calculate to the END of the wave.", pack("m24").fen),
            LStep.Test("LESSON TEST — launch three sacrifice waves.", listOf(
                packItem("m21"), packItem("m24"), packItem("m26"),
            )),
            LStep.Choice("A sacrifice wave is sound only if calculated to a clear finish.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Count to the end. Half-calculated waves beach themselves.", EMPTY_FEN),
        )))
        add(Lesson(53, Gate.MATE_PATTERNS, "Pattern: Corner Webs", listOf(
            LStep.Talk("Kings hug g8/h8 with an f7-g7-h7 shell. Webs form when one shell square is pinned or opened.", pack("m20").fen),
            LStep.MoveTask("The pawn-supported coronation: Qg7#, with f6 as the web's anchor.", pack("m20").fen, "g6g7"),
            LStep.Talk("A pawn on f6/g6/h6 plus a queen is a full web team. Shell + anchor = no exits.", pack("m20").fen, tints = listOf(sq8("f6"))),
            LStep.Test("LESSON TEST — spin three corner webs.", listOf(
                packItem("m20"), packItem("m6"), packItem("m7"),
            )),
            LStep.Choice("A web needs an anchor (pawn/piece) guarding the queen's close-range landing.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Webs win even when you're down material — the finish squares are what count.", EMPTY_FEN),
        )))
        add(Lesson(54, Gate.MATE_PATTERNS, "Pattern Gauntlet", listOf(
            LStep.Talk("No teaching here — just pattern recall under the System's eye. Walk the deck.", pack("m1").fen),
            LStep.Auto("Warm-up replay: the guillotine slide.", pack("m1").fen, pack("m1").line, "You know this cold."),
            LStep.Test("GAUNTLET I — mate or win, three straight.", listOf(
                packItem("m1"), packItem("m10"), packItem("df1"),
            )),
            LStep.Test("GAUNTLET II — heavier steel.", listOf(
                packItem("m5"), packItem("m11"), packItem("m21"),
            )),
            LStep.Choice("You carry eight mate families now. They fire on…", EMPTY_FEN, listOf("Geometry, not hope", "Rating points"), 0),
            LStep.Talk("Gate VII cleared. The Pattern gate never truly closes — patterns decay without blood.", EMPTY_FEN),
        )))

        // ── GATE VIII — PLANNING (55–58) ────────────────────────────────────
        add(Lesson(55, Gate.PLANNING, "Planning: Own the Center", listOf(
            LStep.Talk("Plans not moves: who owns the center picks where the war happens.", START_FEN, tints = CENTER4.toList()),
            LStep.Tap("Tap the four squares a center plan fights for.", EMPTY_FEN, CENTER4, all = true),
            LStep.Choice("A central pawn duo (d4+e4) should be…", EMPTY_FEN, listOf("Protected, then advanced with tempo", "Traded instantly"), 0),
            LStep.Choice("With a cramped position, the plan is…", EMPTY_FEN, listOf("Breaks that free your pieces", "Passive shuffling"), 0),
            LStep.Choice("Center control usually converts into…", EMPTY_FEN, listOf("Better piece posts on BOTH flanks", "Auto-win"), 0),
            LStep.Talk("Plan hierarchy: mate ideas > central squeeze > slow buildup. Threats set the budget.", EMPTY_FEN),
        )))
        add(Lesson(56, Gate.PLANNING, "Planning: Develop with Tempo", listOf(
            LStep.Talk("Every developing move should also THREATEN something — development with tempo chains initiative.", START_FEN),
            LStep.Auto("The Italian chain again — read it now as tempo, not memorization.", START_FEN, listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5"), "Three threats in four moves."),
            LStep.Choice("Tempo means…", EMPTY_FEN, listOf("A move worth a threat, gained free", "Speed of the hands"), 0),
            LStep.Choice("Losing two tempi in the opening typically costs…", EMPTY_FEN, listOf("A full pawn's worth of pressure", "Nothing"), 0),
            LStep.Choice("Developing with CAPTURES of defended pieces is usually slow.", EMPTY_FEN, listOf("True", "False"), 0),
            LStep.Talk("If your move develops AND peers at f7/f2, you've played two moves in one.", EMPTY_FEN),
        )))
        add(Lesson(57, Gate.PLANNING, "Planning: Castle Early", listOf(
            LStep.Talk("The king in the center during a bulletstorm is a plan-destroyer. Castle is a move that buys BOTH safety and rook placement.", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"),
            LStep.MoveTask("Repetition of the key mechanic: O-O.", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1g1", postArrows = listOf(sq8("h1") to sq8("f1"))),
            LStep.Choice("Castling early usually aims to finish by move…", EMPTY_FEN, listOf("Ten", "Twenty-five"), 0),
            LStep.Choice("Opposite-side castling invites…", EMPTY_FEN, listOf("Pawn storms at each other's king", "Instant draws"), 0),
            LStep.Choice("Castling INTO a ready attack is fine because the king moved.", EMPTY_FEN, listOf("Yes", "No — read the board's direction first"), 1),
            LStep.Talk("Plan = pawn structure asks a question; castling direction answers it.", EMPTY_FEN),
        )))
        add(Lesson(58, Gate.PLANNING, "Planning: Weak Squares & Pawns", listOf(
            LStep.Talk("A square is WEAK when no pawn can ever defend it again. Strong pieces nest there permanently.", fenOf("wNd5", "wKe2", "bKe8", "bPe6", "bPf6"), tints = listOf(sq8("d5"))),
            LStep.Tap("The outpost knight squats on d5. Tap the square that feeds him.", fenOf("wNd5", "wKe2", "bKe8", "bPe6", "bPf6"), setOf(sq8("d5"))),
            LStep.Choice("Doubled pawns (two on one file) are weak mainly because…", EMPTY_FEN, listOf("They can't defend each other normally", "They move slower"), 0),
            LStep.Choice("An isolated pawn gives its owner…", EMPTY_FEN, listOf("Activity + a long-term target", "Pure strength"), 0),
            LStep.Choice("Weak squares matter most in the…", EMPTY_FEN, listOf("Endgame — when pieces trade off", "Opening only"), 0),
            LStep.Talk("Create their weakness; occupy it; transform the map. THAT is grown-up chess.", EMPTY_FEN),
        )))

        // ── GATE IX — ENDGAMES (59–64) ──────────────────────────────────────
        add(Lesson(59, Gate.ENDGAMES, "The Opposition", listOf(
            LStep.Talk("Opposition: kings face off with one square between; the one NOT to move owns the future.", fenOf("wKe4", "bKe6"),
                tints = listOf(sq8("e4"), sq8("e6"))),
            LStep.Talk("Whoever steps back concedes penetration squares. Stepping FORWARD forces their king to yield first.", fenOf("wKe4", "bKe6")),
            LStep.Tap("Tap the square where the white king seizes the opposition next: f5 waits.", fenOf("wKe4", "bKe6"), setOf(sq8("f5"))),
            LStep.Choice("The opposition belongs to the side…", EMPTY_FEN, listOf("NOT moving", "To move"), 0),
            LStep.Choice("Opposition decides pawn endings because kings escort pawns.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("King and pawn endings are king fights. The pawn just rides the victor.", EMPTY_FEN),
        )))
        add(Lesson(60, Gate.ENDGAMES, "The Square Rule", listOf(
            LStep.Talk("Can a king catch a sprinting pawn? The square rule: draw the square from pawn to promotion; if the king can step INSIDE it, he catches — else never.", "6k1/8/8/8/8/8/P7/K7 w - - 0 1"),
            LStep.Tap("Tap the promotion square of the a-pawn.", "6k1/8/8/8/8/8/P7/K7 w - - 0 1", setOf(sq8("a8"))),
            LStep.Choice("Pawn on a2 (home square), black king g8, white to move: can the king catch the sprint?", "6k1/8/8/8/8/8/P7/K7 w - - 0 1", listOf("Yes", "No — the square is too wide"), 1),
            LStep.MoveTask("Sprint: a2-a4 claims the home-row double step. The square already exceeds the king.", "6k1/8/8/8/8/8/P7/K7 w - - 0 1", "a2a4"),
            LStep.Choice("The square SHRINKS as the pawn advances.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Count squares, not hope. Endgame clocks are arithmetic.", EMPTY_FEN),
        )))
        add(Lesson(61, Gate.ENDGAMES, "Queen Corridors Close", listOf(
            LStep.Talk("KQ vs K: cut the field with the queen (no checks needed), push him to an edge, walk the king up, mate beside him.", pack("m10").fen),
            LStep.Auto("The patient finishing sequence — no rush, no stalemate.", pack("m5").fen, pack("m5").line, "Slow is certain."),
            LStep.Talk("Staleness kills wins: before EVERY approach move, ask if their king has a square.", pack("m13").fen),
            LStep.Test("LESSON TEST — close three corridors without stalemate.", listOf(
                packItem("m10"), packItem("m15"), packItem("m16"),
            )),
            LStep.Choice("The queen mates only with her own king's support nearby.", EMPTY_FEN, listOf("True", "False"), 0),
            LStep.Talk("Corridor play is slow surgery. The endgame punishes greed more than fear.", EMPTY_FEN),
        )))
        add(Lesson(62, Gate.ENDGAMES, "The Rook Waltz", listOf(
            LStep.Talk("KR vs K: the rook cuts ranks/files, the king walks the edge dance. The waltz always ends at the wall.", pack("m11").fen),
            LStep.Auto("Watch the alternation: rook check, king step, rook swing.", pack("m11").fen, pack("m11").line, "The wall closes."),
            LStep.Talk("Never chase checks that don't shrink the box — the waltz is compression, not noise.", pack("m11").fen),
            LStep.Test("LESSON TEST — three waltz finishes.", listOf(
                packItem("m11"), packItem("m19"), packItem("m8"),
            )),
            LStep.Choice("The rook mate needs the enemy king pushed to…", EMPTY_FEN, listOf("The edge of the board", "The center"), 0),
            LStep.Talk("Waltz tempo: rook waits, king advances, rook closes. Repeat in any order that works.", EMPTY_FEN),
        )))
        add(Lesson(63, Gate.ENDGAMES, "Rooks Behind Passers", listOf(
            LStep.Talk("The Tarrasch law: your rook goes BEHIND a passed pawn — yours or theirs. Behind it, every advance gains hook.", fenOf("wRa1", "wKg1", "bKe8", "bPa4"), arrows = listOf(sq8("a1") to sq8("a8"))),
            LStep.Tap("Tap the a8 square — the far end of the rook's duty file.", fenOf("wRa1", "wKg1", "bKe8", "bPa4"), setOf(sq8("a8"))),
            LStep.Choice("A passed pawn feeds a rook best from…", EMPTY_FEN, listOf("Behind it", "Beside it"), 0),
            LStep.Choice("Rooks hate stopped pawns they can never plow.", EMPTY_FEN, listOf("True", "False"), 0),
            LStep.Choice("In rook endgames, activity usually beats a pawn.", EMPTY_FEN, listOf("Yes", "No"), 0),
            LStep.Talk("Endgame rule of nerves: activate the rook BEFORE counting. Activity is collateral.", EMPTY_FEN),
        )))
        add(Lesson(64, Gate.ENDGAMES, "FINAL GAUNTLET", listOf(
            LStep.Talk("Sixty-four steps, hunter. The final gate asks for blood: winning strikes under quiet light.", pack("m1").fen),
            LStep.Test("GAUNTLET I — the openers.", listOf(
                packItem("m1"), packItem("f2"), packItem("p1"),
            )),
            LStep.Test("GAUNTLET II — strategy tools.", listOf(
                packItem("s2"), packItem("dc1"), packItem("df1"),
            )),
            LStep.Test("GAUNTLET III — the heavies.", listOf(
                packItem("m21"), packItem("m24"), packItem("m26"),
            )),
            LStep.Choice("The curriculum is complete. Patterns decay unless…", EMPTY_FEN, listOf("You drill them in the puzzle arena", "Nothing"), 0),
            LStep.Talk("Gate IX sealed. Return to the hub — the board is no longer a board. It's a ledger of threats.", EMPTY_FEN),
        )))
    }

    init {
        require(LESSONS.size == 64) { "curriculum holds ${LESSONS.size} lessons, not 64" }
        require(LESSONS.map { it.id } == (1..64).toList()) { "lesson ids must run 1..64 in order" }
        LESSONS.forEach { l ->
            require(l.steps.size in 6..10) { "lesson ${l.id} holds ${l.steps.size} steps (6..10 law)" }
            require(l.cat.range.contains(l.id)) { "lesson ${l.id} sits outside ${l.cat}" }
            l.steps.forEach { s ->
                if (s is LStep.Test) require(s.items.size == 3) { "LESSON TEST must carry exactly 3 items" }
                if (s is LStep.Choice) require(s.correct in s.options.indices) { "choice correct index out of range" }
            }
        }
    }
}
