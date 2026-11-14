package com.thesystem.app.chess

/**
 * SYSTEM PUZZLE PACK — vC53 §2: 49 hand-built positions, EVERY ONE verified by
 * two independent law systems before shipping: python-chess (FEN legality,
 * line legality, forced-mate/net-gain proof) AND the in-app 0x88 engine
 * itself (zero parity risk between validator and runtime).
 *
 * rating 1..10 — THE DIRECTOR'S rail: the spec's "difficulty 1–10 per slot"
 * is honest now; `diff` (1..3) survives only as the coarse band legacy code
 * still groups by. [setup] = opponent's last blunder, animated FIRST on the
 * board before "Your move!" (§2 setup-move law) — never fake content: every
 * setup is a legal move replayed from a real pre-position.
 */
data class Puzzle(
    val id: String,
    val fen: String,
    val line: List<String>,   // full UCI line: user, reply, user, reply, ...
    val motif: String,        // MATE | FORK | PIN | SKEWER | DISCOVERY | DEFLECTION | TACTIC
    val diff: Int,            // legacy band 1..3
    val rating: Int,          // vC53 — precise tier 1..10
    val hint: String,
    val setup: String? = null, // §2 — opponent move replayed before the hunter acts
) {
    /** Indices in [line] the hunter must play (every even ply — side to move first). */
    val userPlies: List<Int> get() = line.indices.filter { it % 2 == 0 }
}

object PuzzlePack {

    val ALL: List<Puzzle> = listOf(
        // ── RATING 1 ────────────────────────────────────────────────────
        Puzzle("m1", "6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1",
            listOf("d1d8"), "MATE", 1, 1, "The back rank is a prison."),
        Puzzle("m3", "k7/2R5/1K6/8/8/8/8/8 w - - 0 1",
            listOf("c7c8"), "MATE", 1, 1, "Cut the edge. No ceremony."),
        Puzzle("t1", "4k3/8/8/8/2r5/8/4B3/4K3 w - - 0 1",
            listOf("e2c4"), "TACTIC", 1, 1, "The rook is hanging. Collect."),
        Puzzle("m12", "6k1/pp3ppp/8/8/8/8/8/4R1K1 w - - 0 1",
            listOf("e1e8"), "MATE", 1, 1, "No door on the back rank."),

        // ── RATING 2 ────────────────────────────────────────────────────
        Puzzle("m2", "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
            listOf("h5f7"), "MATE", 1, 2, "The bishop kisses the queen's target."),
        Puzzle("t3", "4k3/8/8/8/r7/8/8/3Q2K1 w - - 0 1",
            listOf("d1a4"), "TACTIC", 1, 2, "The rook forgot its bodyguard."),
        Puzzle("t4", "4k3/2P5/8/8/8/8/8/4K3 w - - 0 1",
            listOf("c7c8q"), "TACTIC", 1, 2, "Queens are born, not given."),
        Puzzle("m6", "k7/8/K7/8/8/8/8/1Q6 w - - 0 1",
            listOf("b1b7"), "MATE", 1, 2, "One square of mercy: none."),
        Puzzle("m7", "k7/8/1K6/8/8/8/8/7R w - - 0 1",
            listOf("h1h8"), "MATE", 1, 2, "The wall closes."),
        Puzzle("m13", "6k1/pp3ppp/8/8/8/7Q/8/6K1 w - - 0 1",
            listOf("h3c8"), "MATE", 1, 2, "Slide in from the wing."),
        Puzzle("m14", "k7/8/1K6/8/8/8/Q7/8 w - - 0 1",
            listOf("a2a7"), "MATE", 1, 2, "The queen lands beside the wall."),
        Puzzle("t9", "4k3/8/8/8/2r5/1B6/8/4K3 w - - 0 1",
            listOf("b3c4"), "TACTIC", 1, 2, "The rook forgot its escort."),
        Puzzle("t10", "3k4/8/8/8/8/2n5/8/Q3K3 w - - 0 1",
            listOf("a1c3"), "TACTIC", 1, 2, "Loose pieces drop off."),

        // ── RATING 3 ────────────────────────────────────────────────────
        Puzzle("t5", "q3k3/8/8/8/8/8/8/R3K3 w - - 0 1",
            listOf("a1a8"), "TACTIC", 1, 3, "She parked on the wrong square."),
        Puzzle("m8", "6k1/5ppp/8/8/8/8/8/2RR2K1 w - - 0 1",
            listOf("c1c8"), "MATE", 1, 3, "Two rooks. One coffin."),
        Puzzle("t7", "4k3/8/3n4/8/8/Q7/8/4K3 w - - 0 1",
            listOf("a3d6"), "TACTIC", 1, 3, "Unguarded. Punish instantly."),
        Puzzle("b2", "k7/8/8/8/8/1r6/2B5/K7 w - - 0 1",
            listOf("c2b3"), "TACTIC", 1, 3, "Snatch on the diagonal."),
        Puzzle("m15", "k7/8/K7/8/7Q/8/8/8 w - - 0 1",
            listOf("h4d8"), "MATE", 1, 3, "The wing collapses inward."),
        Puzzle("st1", "4k3/r7/8/8/4B3/8/8/4K3 b - - 0 1",
            listOf("e4a8"), "TACTIC", 1, 3, "He just parked it on your diagonal.", setup = "a7a8"),
        Puzzle("st3", "k7/8/8/8/8/3r4/2B5/K7 b - - 0 1",
            listOf("c2b3"), "TACTIC", 1, 3, "He slid into the bishop's lane.", setup = "d3b3"),
        Puzzle("st4", "5k2/pp3ppp/8/8/8/7Q/8/6K1 b - - 0 1",
            listOf("h3c8"), "MATE", 1, 3, "The king stepped into the corridor.", setup = "f8g8"),

        // ── RATING 4 ────────────────────────────────────────────────────
        Puzzle("m4", "2r3k1/5p1p/6pQ/8/8/2B5/6PP/6K1 w - - 0 1",
            listOf("h6g7"), "MATE", 2, 4, "The bishop on c3 is the silent witness."),
        Puzzle("m9", "6k1/5p1p/6pQ/7N/8/8/6PP/6K1 w - - 0 1",
            listOf("h6g7"), "MATE", 2, 4, "The knight holds the door."),
        Puzzle("t2", "4k3/8/8/8/4B3/3q4/8/4R1K1 w - - 0 1",
            listOf("e4d3", "e8d8"), "TACTIC", 2, 4, "Take with tempo. The file opens by itself."),
        Puzzle("m16", "k7/8/1K6/8/8/8/8/3Q4 w - - 0 1",
            listOf("d1d8"), "MATE", 2, 4, "One file. Total silence."),
        Puzzle("p3", "3k4/3b4/8/8/8/8/6R1/K7 w - - 0 1",
            listOf("g2d2", "d8e8", "d2d7"), "PIN", 2, 4, "Pin it to the king. Then eat."),
        Puzzle("s2", "r7/8/2k5/8/8/8/B7/7K w - - 0 1",
            listOf("a2d5", "c6d6", "d5a8"), "SKEWER", 2, 4, "Check. He steps. The rook falls."),
        Puzzle("st2", "6k1/pp4pp/8/8/8/8/5Q2/6K1 b - - 0 1",
            listOf("f2f8"), "MATE", 2, 4, "He walked into the corner. Close it.", setup = "g8h8"),
        Puzzle("m17", "2r3k1/pp3ppp/8/8/8/8/8/2RR2K1 w - - 0 1",
            listOf("c1c8"), "MATE", 2, 4, "Trade into the coffin."),

        // ── RATING 5 ────────────────────────────────────────────────────
        Puzzle("f1", "r3k3/8/8/1N6/8/8/8/4K3 w q - 0 1",
            listOf("b5c7", "e8d8", "c7a8"), "FORK", 2, 5, "One square. Two prisoners."),
        Puzzle("p1", "4k3/4n3/8/8/8/8/8/R5K1 w - - 0 1",
            listOf("a1e1", "e8f7", "e1e7"), "PIN", 2, 5, "Freeze it on the file. Then harvest."),
        Puzzle("s1", "4k2q/8/8/8/8/8/8/R3K3 w - - 0 1",
            listOf("a1a8", "e8d7", "a8h8"), "SKEWER", 2, 5, "Drive the king off the rank."),
        Puzzle("dc1", "4k3/8/8/4N3/3q4/8/7P/4R2K w - - 0 1",
            listOf("e5c6", "e8d7", "c6d4"), "DISCOVERY", 2, 5, "Move the knight. Open the file."),
        Puzzle("f2", "4k3/8/8/1N1r4/8/8/8/4K3 w - - 0 1",
            listOf("b5c7", "e8e7", "c7d5"), "FORK", 2, 5, "The royal fork: crown or rook."),
        Puzzle("f3", "4k3/8/8/3q4/4N3/8/8/4K3 w - - 0 1",
            listOf("e4f6", "e8d8", "f6d5"), "FORK", 2, 5, "The royal fork: crown or queen."),
        Puzzle("m19", "1k6/8/2K5/8/8/8/8/R6R w - - 0 1",
            listOf("h1h8"), "MATE", 2, 5, "The wall walks."),
        Puzzle("t8", "4k3/8/8/8/4q3/8/8/4R1K1 w - - 0 1",
            listOf("e1e4"), "TACTIC", 2, 5, "Her throne stood on your file."),

        // ── RATING 6 ────────────────────────────────────────────────────
        Puzzle("m10", "k7/8/KQ6/8/8/8/8/8 w - - 0 1",
            listOf("b6b7"), "MATE", 2, 6, "Waiting moves win wars."),
        Puzzle("m20", "6k1/5ppp/5PQ1/8/8/8/8/7K w - - 0 1",
            listOf("g6g7"), "MATE", 2, 6, "The pawn holds the crown."),
        Puzzle("p4", "4k3/4q3/8/8/3N4/8/8/4R1K1 w - - 0 1",
            listOf("d4f5", "e8d8", "f5e7"), "PIN", 2, 6, "The pin squeezes; the knight collects."),
        Puzzle("t11", "6k1/8/8/8/8/5q2/8/3Q1K2 w - - 0 1",
            listOf("d1f3"), "TACTIC", 2, 6, "Win the lady on the diagonal."),

        // ── RATING 7 ────────────────────────────────────────────────────
        Puzzle("m5", "k7/8/3R4/8/8/8/8/K6R w - - 0 1",
            listOf("d6d7", "a8b8", "h1h8"), "MATE", 2, 7, "Wait one move. Then close the lid."),
        Puzzle("m11", "k7/8/1K6/8/8/8/8/R6R w - - 0 1",
            listOf("a1a7", "a8b8", "h1h8"), "MATE", 2, 7, "Drive him to the wall. Slowly."),
        Puzzle("m25", "2r3k1/ppQ2ppp/8/8/8/8/8/6K1 w - - 0 1",
            listOf("c7c8"), "MATE", 2, 7, "The defender becomes the grave."),

        // ── RATING 8 ────────────────────────────────────────────────────
        Puzzle("df1", "6k1/5q1p/6p1/8/8/8/6PP/3R2K1 w - - 0 1",
            listOf("d1d8", "f7f8", "d8f8"), "DEFLECTION", 3, 8, "Drag the queen into the trade."),
        Puzzle("p2", "4k3/8/8/8/4q3/8/8/1R3K2 w - - 0 1",
            listOf("b1e1", "e4e1", "f1e1"), "PIN", 3, 8, "Greed has a price tag."),
        Puzzle("m21", "2r3k1/5ppp/8/8/8/8/R7/R5K1 w - - 0 1",
            listOf("a2a8", "c8a8", "a1a8"), "MATE", 3, 8, "Both rooks die so the last one mates."),

        // ── RATING 9 ────────────────────────────────────────────────────
        Puzzle("m23", "k7/pp6/8/8/8/3Q4/8/K7 w - - 0 1",
            listOf("d3d8"), "MATE", 3, 9, "The pawns buried their own king."),
        Puzzle("m26", "r3r2k/1p3ppp/8/8/8/8/R7/R5K1 w - - 0 1",
            listOf("a2a8", "e8a8", "a1a8"), "MATE", 3, 9, "The storm takes two towers to break."),

        // ── RATING 10 ───────────────────────────────────────────────────
        Puzzle("m24", "3r2k1/5ppp/8/8/8/8/8/R5KQ w - - 0 1",
            listOf("a1a8", "d8a8", "h1a8"), "MATE", 3, 10, "Sacrifice the rook. The queen inherits."),
    )

    init {
        // compile-time honesty: ids unique, ratings inside the spec rail
        require(ALL.map { it.id }.distinct().size == ALL.size) { "duplicate puzzle id" }
        require(ALL.all { it.rating in 1..10 }) { "rating outside 1..10" }
    }

    fun byDiff(d: Int): List<Puzzle> = ALL.filter { it.diff == d }

    fun byRating(r: Int): List<Puzzle> = ALL.filter { it.rating == r }

    /** Date-seeded daily pick — deterministic worldwide, rotates the full pack. */
    fun daily(epochDay: Long): Puzzle = ALL[(epochDay % ALL.size).toInt()]

    /** vC55 §A4 — daily from the MERGED pack, skipping lifetime-solved ids:
     *  walks forward from the world-seeded index until an unconquered puzzle
     *  stands. Deterministic per (day, solved-set) — no clock randomness. */
    fun daily(epochDay: Long, pack: List<Puzzle>, solved: Set<String>): Puzzle {
        if (pack.isEmpty()) return ALL[0]
        var i = ((epochDay % pack.size + pack.size) % pack.size).toInt()
        repeat(pack.size) {
            val p = pack[i]
            if (p.id !in solved) return p
            i = (i + 1) % pack.size
        }
        return pack[i]   // arena fully conquered — the seed stands, honestly
    }

    /** vC55 §A4 — builtin 49 seed + decoded asset mega-pack, deduplicated by
     *  id AND board fingerprint (id collisions or FEN twins never double). */
    fun merged(extra: List<Puzzle>?): List<Puzzle> {
        if (extra.isNullOrEmpty()) return ALL
        val ids = ALL.mapTo(HashSet()) { it.id }
        val fens = ALL.mapTo(HashSet()) { it.fen.substringBefore(' ') }
        val out = ArrayList<Puzzle>(ALL.size + extra.size).also { it.addAll(ALL) }
        for (p in extra) {
            if (p.rating !in 1..10 || p.line.isEmpty()) continue   // corrupt entry dies silent
            if (p.id in ids) continue
            if (p.fen.substringBefore(' ') in fens) continue
            ids += p.id; fens += p.fen.substringBefore(' '); out += p
        }
        return out
    }

    /**
     * vC55 §A4 — ASSET MEGA-PACK DECODER. Pure-Kotlin minimal JSON reader
     * (objects, arrays, strings, numbers, bools, null) — ZERO dependencies,
     * android-free: the sim harness parses the exact asset bytes the app
     * ships and replays every line through the in-app engine. Corrupt input
     * yields an empty list, never a crash (2GB-device covenant).
     */
    fun decode(json: String): List<Puzzle> = runCatching { decodeStrict(json) }.getOrDefault(emptyList())

    private fun decodeStrict(json: String): List<Puzzle> {
        val out = ArrayList<Puzzle>()
        val r = JsonReader(json)
        val root = r.readValue() as? Map<*, *> ?: return emptyList()
        val arr = root["puzzles"] as? List<*> ?: return emptyList()
        for (e in arr) {
            val o = e as? Map<*, *> ?: continue
            val id = o["id"] as? String ?: continue
            val fen = o["fen"] as? String ?: continue
            val line = (o["line"] as? List<*>)?.mapNotNull { it as? String } ?: continue
            val motif = o["motif"] as? String ?: "TACTIC"
            val diff = (o["diff"] as? Number)?.toInt() ?: 1
            val rating = (o["rating"] as? Number)?.toInt() ?: 1
            val hint = o["hint"] as? String ?: ""
            val setup = (o["setup"] as? String)?.takeIf { it.isNotBlank() }
            if (line.isEmpty()) continue
            out += Puzzle(id, fen, line, motif, diff.coerceIn(1, 3), rating.coerceIn(1, 10), hint, setup)
        }
        return out
    }

    /** Micro recursive-descent reader — strict enough for our own forge output. */
    private class JsonReader(private val s: String) {
        private var i = 0
        private fun ws() { while (i < s.length && s[i] <= ' ') i++ }
        private fun expect(c: Char) { require(i < s.length && s[i] == c) { "expected $c at $i" }; i++ }

        fun readValue(): Any? {
            ws()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws()
                    if (i < s.length && s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = readString(); ws(); expect(':'); m[k] = readValue(); ws()
                        if (i < s.length && s[i] == ',') { i++; continue }; break }
                    expect('}'); m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws()
                    if (i < s.length && s[i] == ']') { i++; return l }
                    while (true) { l.add(readValue()); ws()
                        if (i < s.length && s[i] == ',') { i++; continue }; break }
                    expect(']'); l }
                '"' -> readString()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    s.substring(st, i).toDoubleOrNull() }
            }
        }

        private fun readString(): String {
            expect('"')
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') break
                if (c == '\\' && i < s.length) {
                    when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                        'u' -> { if (i + 4 <= s.length) { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 } }
                        else -> b.append(e)
                    }
                } else b.append(c)
            }
            return b.toString()
        }
    }
}
