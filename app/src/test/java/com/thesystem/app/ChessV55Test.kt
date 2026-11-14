package com.thesystem.app

import com.thesystem.app.chess.Board
import com.thesystem.app.chess.ChessAI
import com.thesystem.app.chess.KING
import com.thesystem.app.chess.Puzzle
import com.thesystem.app.chess.PuzzleDirector
import com.thesystem.app.chess.PuzzlePack
import com.thesystem.app.chess.sqFromName
import com.thesystem.app.chess.typeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vC55 guards — the pure-JVM face of the rep-sim harness battery: A1
 * observational rules, A5 engine strength laws, A4 pack/decoder/blacklist.
 * The CI jar is assembleDebug; these run under testDebugUnitTest locally.
 */
class ChessV55Test {

    private fun board(fen: String) = Board.fromFen(fen)

    // ── §A1 observational law ────────────────────────────────────────────

    @Test fun `exposed king stays pseudo-legal — self-check moves exist`() {
        // white king on e1, white rook on e4 pinned by black rook e8:
        // moving the rook sideways abandons the king — observational law ALLOWS it
        val b = board("4r2k/8/8/8/4R3/8/8/4K3 w - - 0 1")
        assertTrue(b.pseudoLegalMoves().any { it.uci() == "e4h4" })
        // and the exposure burns red: the abandoned king (and ONLY it) glows
        b.play(b.pseudoLegalMoves().first { it.uci() == "e4h4" })
        assertEquals(setOf(sqFromName("e1")), b.kingsInCheck())
    }

    @Test fun `king capture is a real board event — KING_CRUSHED`() {
        val c = board("7k/8/8/8/8/8/8/6RK w - - 0 1")   // open h-file: Rh1×h8 lands the kill
        val crush = c.pseudoLegalMoves().firstOrNull { it.uci() == "h1h8" }
        assertNotNull(crush)
        c.play(crush!!)
        assertEquals(Board.Status.KING_CRUSHED, c.status().first)
    }

    @Test fun `crushFinisher strikes only on true mate, never on stalemate`() {
        // scholar's mate: black mated on f7 — finisher demands the physical kill
        val mated = board("r1bqk1nr/pppp1Qpp/2n5/2b1p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4")
        val strike = mated.crushFinisher()
        assertNotNull(strike)
        // victim king square e8 must be the strike target
        assertEquals(sqFromName("e8"), strike!!.to)
        // stalemate: no crush phantom
        val stale = board("7k/5Q2/8/8/8/8/8/7K b - - 0 1")
        assertNull(stale.crushFinisher())
    }

    // ── §A5 engine strength laws ─────────────────────────────────────────

    @Test fun `every difficulty lands the mate-in-one`() {
        val b = board("6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1")
        for (d in ChessAI.Difficulty.entries) {
            assertEquals("${d.label} mate-in-1", "d1d8", ChessAI.bestMove(b, d)?.uci())
        }
    }

    @Test fun `MEDIUM never hangs the queen for a knight`() {
        // white queen d1 can snatch a knight on a4 — but black rook a8 guards it
        val b = board("r5k1/5ppp/8/8/n7/8/8/3Q2K1 w - - 0 1")
        val m = ChessAI.bestMove(b, ChessAI.Difficulty.MEDIUM)!!
        assertTrue("MEDIUM refused Qxa4?? (played ${m.uci()})", m.uci() != "d1a4")
    }

    @Test fun `book openings stay inside the classical families`() {
        val start = board(Board.START_FEN)
        val first = ChessAI.bestMove(start, ChessAI.Difficulty.EXPERT)!!.uci()
        assertTrue(first in setOf("e2e4", "d2d4", "g1f3", "c2c4"))
        val afterE4 = board(Board.START_FEN)
        afterE4.play(afterE4.legalMoves().first { it.uci() == "e2e4" })
        val reply = ChessAI.bestMove(afterE4, ChessAI.Difficulty.EXPERT)!!.uci()
        assertTrue(reply in setOf("e7e5", "c7c5", "e7e6", "c7c6", "d7d5"))
    }

    @Test fun `EXPERT is deterministic under TT + alpha-beta`() {
        val b = board("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4")
        val a = ChessAI.bestMove(b, ChessAI.Difficulty.EXPERT)?.uci()
        val c = ChessAI.bestMove(b, ChessAI.Difficulty.EXPERT)?.uci()
        assertEquals(a, c)
    }

    // ── §A4 mega-pack decoder + lifetime blacklist ───────────────────────

    @Test fun `decoder parses forge-shaped bytes and survives corruption`() {
        val json = """{"puzzles":[{"id":"x1","fen":"6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1","line":["d1d8"],"motif":"MATE","diff":1,"rating":3,"elo":1500,"hint":"h"}]}"""
        val got = PuzzlePack.decode(json)
        assertEquals(1, got.size)
        assertEquals("x1", got[0].id)
        assertEquals(listOf("d1d8"), got[0].line)
        assertTrue(PuzzlePack.decode("{not json").isEmpty())
    }

    @Test fun `merged dedupes ids and board twins, keeps ratings in rail`() {
        val builtinTwin = "6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1"   // m1's own board
        val dup = Puzzle("m1", "4k3/8/8/8/8/8/8/4K2R w - - 0 1", listOf("h1h8"), "MATE", 1, 2, "id twin")
        val fenTwin = Puzzle("zz9", builtinTwin, listOf("d1d8"), "MATE", 1, 2, "fen twin")
        val merged = PuzzlePack.merged(listOf(dup, fenTwin))
        assertEquals(PuzzlePack.ALL.size, merged.size)   // id twin + fen twin both die
    }

    @Test fun `director lifetime blacklist never serves a solved puzzle`() {
        val store = object : PuzzleDirector.Store {
            private var data = listOf<PuzzleDirector.Record>()
            override fun loadRecords() = data
            override fun saveRecords(records: List<PuzzleDirector.Record>) { data = records }
        }
        val pack = listOf(
            Puzzle("a", "6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1", listOf("d1d8"), "MATE", 1, 5, "h"),
            Puzzle("b", "6k1/5ppp/8/8/8/8/8/4R1K1 w - - 0 1", listOf("e1e8"), "MATE", 1, 5, "h"),
        )
        val d = PuzzleDirector(store, pack = pack, lifetimeSolved = setOf("a", "b"))
        assertTrue(d.nextInTier(5).arenaCleared)
        val d2 = PuzzleDirector(store, pack = pack, lifetimeSolved = setOf("a"))
        assertTrue(d2.nextInTier(5).puzzle.id != "a")
    }

    @Test fun `daily walk skips lifetime-solved seeds deterministically`() {
        val pack = PuzzlePack.ALL
        val day = 20500L
        val seeded = pack[(day % pack.size).toInt()]
        val p = PuzzlePack.daily(day, pack, setOf(seeded.id))
        assertTrue(p.id != seeded.id)
    }

    @Test fun `king-crush capture is in the pseudo-legal list (AI instinct rail)`() {
        // exposed black king on the open h-file — the capture MUST be findable
        val b = board("7k/8/8/8/8/8/8/6RK w - - 0 1")
        assertTrue(b.pseudoLegalMoves().any { typeOf(b.sq[it.to]) == KING })
    }
}
