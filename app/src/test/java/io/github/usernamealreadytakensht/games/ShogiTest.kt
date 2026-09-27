package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.game.shogi.Shogi
import io.github.usernamealreadytakensht.games.game.shogi.Shogi.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShogiTest {

    private fun perft(p: Position, depth: Int): Long {
        val moves = p.legalMoves()
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(p.play(it), depth - 1) }
    }

    private fun check(fen: String, vararg expected: Long) {
        val p = Position.fromFen(fen)
        expected.forEachIndexed { i, n -> assertEquals("$fen depth ${i + 1}", n, perft(p, i + 1)) }
    }

    // Reference counts from Fairy-Stockfish (UCI_Variant shogi, "go perft").

    @Test
    fun startPosition() = check(Shogi.START_FEN, 30, 900, 25470)

    @Test
    fun startPositionDepth4() = assertEquals(719731L, perft(Position.start(), 4))

    @Test
    fun busyPositionWithHands() =
        check("l6nl/5+P1gk/2np1S3/p1p4Pp/3P2Sp1/1PPb2P1P/P5GS1/R8/LN4bKL[RGgsnppppp] w - - 0 1", 146, 29020)

    private fun perftOf(fen: String, depth: Int) = perft(Position.fromFen(fen), depth)

    @Test
    fun pawnDropsAndPromotion() {
        val fen = "4k4/9/4P4/9/9/9/9/9/4K4[P] w - - 0 1"
        assertEquals(71L, perftOf(fen, 1))
        assertEquals(3918L, perftOf(fen, 3))
    }

    @Test
    fun pawnsNearTheCornerKing() {
        val fen = "8k/9/7PP/9/9/9/9/9/K8[Pp] w - - 0 1"
        assertEquals(62L, perftOf(fen, 1))
        assertEquals(48336L, perftOf(fen, 3))
    }

    @Test
    fun openingWithBishopsInHand() = check("lnsgk2nl/1r4gs1/p1pppp1pp/1p4p2/7P1/2P6/PP1PPPP1P/1SG4R1/LN2KGSNL[Bb] w - - 0 1", 76, 5325, 277477)

    @Test
    fun everyPieceInHand() = check("4k4/9/9/9/9/9/9/9/4K4[RBGSNLPrbgsnlp] w - - 0 1", 525, 251422)

    @Test
    fun pawnDropMateIsIllegalButOtherMatesAreFine() {
        // Gote king i9 boxed in by its own pieces; a pawn dropped on i8 would mate: illegal.
        val p = Position.fromFen("7nk/7p1/7G1/9/9/9/9/9/K8[P] w - - 0 1")
        assertFalse(p.legalMoves().any { it.toUci() == "P@i8" })
        // With the gold guarding, a gold drop there is mate and allowed.
        val q = Position.fromFen("7nk/7p1/7G1/9/9/9/9/9/K8[G] w - - 0 1")
        val g = q.legalMoves().first { it.toUci() == "G@i8" }
        assertTrue(q.play(g).legalMoves().isEmpty())
    }

    @Test
    fun nifuAndForcedPromotion() {
        val p = Position.start()
        // Nothing in hand at the start; after a pawn exchange a pawn may not go on a file that has one.
        assertTrue(p.legalMoves().none { it.isDrop })
        val lanceOnSeventh = Position.fromFen("4k4/L8/9/9/9/9/9/9/4K4[] w - - 0 1")
        val moves = lanceOnSeventh.legalMoves().filter { it.from == 72 - 9 }.map { it.toUci() }
        assertTrue("a9 must promote: $moves", "a8a9+" in moves && "a8a9" !in moves)
    }

    @Test
    fun notation() {
        val g = Shogi.Game()
        g.play(g.parseUci("g3g4")!!)
        g.play(g.parseUci("c7c6")!!)
        g.play(g.parseUci("h2g2")!!)
        assertEquals(listOf("P-3f", "P-7d", "R-3h"), g.notations)
    }

    @Test
    fun kifRecord() {
        val g = Shogi.Game()
        for (m in listOf("c3c4", "g7g6", "b2h8+", "g9h8", "B@e5")) g.play(g.parseUci(m)!!)
        val kif = g.kif("You", "Engine", 0L, "投了", "先手")
        val moves = kif.lines().filter { it.trim().firstOrNull()?.isDigit() == true }.map { it.trim().split(Regex(" +"))[1] }
        // P-7f, P-3d, Bx2b+, Sx2b (a recapture: 同), B*5e.
        assertEquals(listOf("７六歩(77)", "３四歩(33)", "２二角成(88)", "同　銀(31)", "５五角打", "投了"), moves)
        assertTrue(kif.contains("先手：You") && kif.contains("まで5手で先手の勝ち"))
    }

    @Test
    fun fourfoldRepetitionIsADraw() {
        val g = Shogi.Game()
        repeat(3) {
            for (m in listOf("h2g2", "b8c8", "g2h2", "c8b8")) g.play(g.parseUci(m)!!)
        }
        assertEquals(Shogi.Outcome.DRAW, g.outcome())
    }

    @Test
    fun fenRoundTrip() {
        val fen = "l6nl/5+P1gk/2np1S3/p1p4Pp/3P2Sp1/1PPb2P1P/P5GS1/R8/LN4bKL[RGgsnppppp] w - - 0 1"
        assertEquals(fen, Position.fromFen(fen).toFen())
    }
}
