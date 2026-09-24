package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.game.draughts.Checkers
import io.github.usernamealreadytakensht.games.game.draughts.Draughts.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckersTest {

    private fun perft(pos: Checkers.Position, depth: Int): Long {
        if (depth == 0) return 1
        val moves = Checkers.legalMoves(pos)
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(Checkers.play(pos, it), depth - 1) }
    }

    private fun position(black: String, white: String, toMove: Color, blackKings: String = "", whiteKings: String = ""): Checkers.Position {
        val b = IntArray(64)
        fun put(list: String, piece: Int) = list.split(' ').filter { it.isNotBlank() }
            .forEach { b[Checkers.squareOf(it.toInt())!!] = piece }
        put(black, Checkers.BLACK_MAN); put(white, Checkers.WHITE_MAN)
        put(blackKings, Checkers.BLACK_KING); put(whiteKings, Checkers.WHITE_KING)
        return Checkers.Position(b, toMove)
    }

    @Test
    fun perftFromTheStart() {
        // Published perft for English draughts (a whole capture sequence counts as one ply).
        val expected = longArrayOf(7, 49, 302, 1469, 7361, 36768, 179740, 845931)
        for ((i, n) in expected.withIndex()) assertEquals("depth ${i + 1}", n, perft(Checkers.START, i + 1))
    }

    @Test
    fun startingMovesUseTheStandardNumbers() {
        val moves = Checkers.legalMoves(Checkers.START).map { Checkers.notation(it) }.toSet()
        assertEquals(setOf("9-13", "9-14", "10-14", "10-15", "11-15", "11-16", "12-16"), moves)
        for (n in 1..32) assertEquals(n, Checkers.number(Checkers.squareOf(n)!!))
        // Black holds 1-12, White 21-32.
        assertTrue((1..12).all { Checkers.colorOf(Checkers.START[Checkers.squareOf(it)!!]) == Color.BLACK })
        assertTrue((21..32).all { Checkers.colorOf(Checkers.START[Checkers.squareOf(it)!!]) == Color.WHITE })
    }

    @Test
    fun capturesAreCompulsoryButAnySequenceMayBeChosen() {
        // Black man on 14 can take 18 (to 23); a quiet move elsewhere is then illegal.
        val pos = position("14 1", "18 30", Color.BLACK)
        val moves = Checkers.legalMoves(pos)
        assertTrue(moves.all { it.isCapture })
        assertEquals(listOf("14x23"), moves.map { Checkers.notation(it) })
        // Two different captures available: both are offered, the shorter one too.
        val choice = position("14", "18 17 25", Color.BLACK)
        val notes = Checkers.legalMoves(choice).map { Checkers.notation(it) }.toSet()
        assertEquals(setOf("14x23", "14x21x30"), notes)
    }

    @Test
    fun menCaptureForwardOnlyAndKingsBothWays() {
        // A white man behind a black man cannot be taken backwards by the man…
        val man = position("18", "14 30", Color.BLACK)
        assertTrue(Checkers.legalMoves(man).none { it.isCapture })
        // …but a king can.
        val king = position("", "14 30", Color.BLACK, blackKings = "18")
        assertEquals(listOf("18x9"), Checkers.legalMoves(king).filter { it.isCapture }.map { Checkers.notation(it) })
    }

    @Test
    fun crowningEndsTheMove() {
        // Black crowns on 29-32. The man on 22 jumps 26 to 31 and is crowned; as a king it
        // could jump 27 next, but crowning ends the move.
        val pos = position("22", "26 27 1", Color.BLACK)
        val moves = Checkers.legalMoves(pos)
        assertEquals(listOf("22x31"), moves.map { Checkers.notation(it) })
        val after = Checkers.play(pos, moves[0])
        assertEquals(Checkers.BLACK_KING, after[Checkers.squareOf(31)!!])
    }

    @Test
    fun noMovesLoses() {
        val blocked = position("", "1 2", Color.BLACK)
        assertEquals(Checkers.Outcome.WHITE_WINS, Checkers.outcome(blocked, emptyList(), 0))
    }

    @Test
    fun bitboardsMatchTheEngineLayout() {
        val bb = Checkers.bitboards(Checkers.START)
        // Same numbers as marcher/cli.c's documentation example for the start position.
        assertEquals(6172839697753047040L, bb[0])
        assertEquals(11163050L, bb[1])
        assertEquals(0L, bb[2] or bb[3])
    }
}
