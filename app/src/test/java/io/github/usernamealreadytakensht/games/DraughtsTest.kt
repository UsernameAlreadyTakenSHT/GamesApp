package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.game.draughts.Draughts
import io.github.usernamealreadytakensht.games.game.draughts.Draughts.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraughtsTest {

    private fun position(
        whiteMen: List<Int> = emptyList(), blackMen: List<Int> = emptyList(),
        whiteKings: List<Int> = emptyList(), blackKings: List<Int> = emptyList(),
        toMove: Color = Color.WHITE,
    ): Draughts.Position {
        val sq = ByteArray(51)
        whiteMen.forEach { sq[it] = Draughts.WHITE_MAN.toByte() }
        blackMen.forEach { sq[it] = Draughts.BLACK_MAN.toByte() }
        whiteKings.forEach { sq[it] = Draughts.WHITE_KING.toByte() }
        blackKings.forEach { sq[it] = Draughts.BLACK_KING.toByte() }
        return Draughts.Position(sq, toMove)
    }

    @Test
    fun geometry() {
        assertEquals(1, Draughts.squareAt(0, 1))
        assertEquals(5, Draughts.squareAt(0, 9))
        assertEquals(6, Draughts.squareAt(1, 0))
        assertEquals(46, Draughts.squareAt(9, 0))
        assertEquals(50, Draughts.squareAt(9, 8))
        assertEquals(0, Draughts.squareAt(0, 0))
        for (sq in 1..50) assertEquals(sq, Draughts.squareAt(Draughts.rowOf(sq), Draughts.colOf(sq)))
        assertEquals(32, Draughts.neighbourOf(38, 0))
        assertEquals(33, Draughts.neighbourOf(38, 1))
    }

    @Test
    fun perftFromStart() {
        val expected = longArrayOf(9, 81, 658, 4265, 27117, 167140, 1049442, 6483961)
        for (d in 1..expected.size) {
            assertEquals("perft($d)", expected[d - 1], Draughts.perft(Draughts.START, d))
        }
    }

    @Test
    fun hubRoundTrip() {
        assertEquals(Draughts.START, Draughts.Position.fromHub(Draughts.START.toHub()))
        assertEquals("Wbbbbbbbbbbbbbbbbbbbbeeeeeeeeeewwwwwwwwwwwwwwwwwwww", Draughts.START.toHub())
        val m = Draughts.parseHub(Draughts.START, "32-28")!!
        assertEquals(32, m.from); assertEquals(28, m.to)
    }

    @Test
    fun majorityRuleForcesTheLongerCapture() {
        // 38x27x18 takes two men (32 and 22); 38x29 would take only one (33).
        val pos = position(whiteMen = listOf(38), blackMen = listOf(32, 33, 22))
        val moves = Draughts.legalMoves(pos)
        assertEquals(1, moves.size)
        assertEquals(18, moves[0].to)
        assertEquals(setOf(32, 22), moves[0].captured.toSet())
        assertEquals("38x18x32x22", moves[0].toHub())
    }

    @Test
    fun flyingKingMultiCaptureWithTurn() {
        // King on 46 takes 32, 19 (same diagonal) and then turns to take 20, landing on 25.
        val pos = position(whiteKings = listOf(46), blackMen = listOf(32, 19, 20))
        val moves = Draughts.legalMoves(pos)
        assertEquals(1, moves.size)
        assertEquals(25, moves[0].to)
        assertEquals(setOf(32, 19, 20), moves[0].captured.toSet())
    }

    @Test
    fun capturedPiecesCannotBeJumpedTwice() {
        // A man circles back to its own square after four captures; the fifth jump over 22
        // is illegal because 22 is already captured (Turkish strike).
        val pos = position(whiteMen = listOf(28), blackMen = listOf(22, 12, 13, 23))
        val moves = Draughts.legalMoves(pos)
        assertTrue(moves.all { it.captured.size == 4 })
        assertTrue(moves.any { it.from == 28 && it.to == 28 })
    }

    @Test
    fun manPromotesOnlyWhenTheMoveEndsOnTheLastRow() {
        // 13x2x11 crosses the back rank mid-capture: the man stays a man.
        val through = position(whiteMen = listOf(13), blackMen = listOf(8, 7))
        val m = Draughts.legalMoves(through).single()
        assertEquals(11, m.to)
        assertEquals(Draughts.WHITE_MAN, Draughts.play(through, m)[11])

        // 7-1 ends on the back rank: promotion.
        val end = position(whiteMen = listOf(7))
        val k = Draughts.legalMoves(end).first { it.to == 1 }
        assertEquals(Draughts.WHITE_KING, Draughts.play(end, k)[1])
    }

    @Test
    fun threefoldRepetitionCountsTheCurrentPosition() {
        val pos = position(whiteKings = listOf(46), blackKings = listOf(5))
        val other = position(whiteKings = listOf(41), blackKings = listOf(5), toMove = Color.BLACK)
        // Two earlier occurrences plus the current one: third time, draw.
        assertEquals(Draughts.Outcome.DRAW, Draughts.outcome(pos, listOf(pos, other, pos, other), 4))
        assertEquals(Draughts.Outcome.ONGOING, Draughts.outcome(pos, listOf(pos, other), 2))
    }

    @Test
    fun fmjdEndgameLimitsAgainstALoneKing() {
        // Three kings against one: 16 moves each from the move that reached it.
        val pos = position(whiteKings = listOf(46, 47, 48), blackKings = listOf(5))
        val before = (11..42).map { position(whiteKings = listOf(46, 47, it), blackKings = listOf(5)) }
        assertEquals(Draughts.Outcome.ONGOING, Draughts.outcome(pos, before.take(31), 0))
        assertEquals(Draughts.Outcome.DRAW, Draughts.outcome(pos, before.take(32), 0))
        // An earlier position with more material does not count.
        val start = listOf(position(whiteKings = listOf(46, 47, 49), blackKings = listOf(5), blackMen = listOf(6)))
        assertEquals(Draughts.Outcome.ONGOING, Draughts.outcome(pos, start + before.take(31), 0))
        // King and man against king: 5 moves each.
        val small = position(whiteKings = listOf(46), whiteMen = listOf(30), blackKings = listOf(5))
        val smallBefore = (11..20).map { position(whiteKings = listOf(it), whiteMen = listOf(30), blackKings = listOf(5)) }
        assertEquals(Draughts.Outcome.ONGOING, Draughts.outcome(small, smallBefore.take(9), 0))
        assertEquals(Draughts.Outcome.DRAW, Draughts.outcome(small, smallBefore, 0))
        assertEquals(null, Draughts.endgameMoves(Draughts.START))
    }

    @Test
    fun pdnGivesLandingSquaresNotCapturedOnes() {
        assertEquals("32-28", Draughts.toPdn(Draughts.START, Draughts.parseHub(Draughts.START, "32-28")!!))
        val pos = position(whiteMen = listOf(38), blackMen = listOf(32, 33, 22))
        assertEquals("38x18", Draughts.toPdn(pos, Draughts.legalMoves(pos).single()))
    }

    @Test
    fun blackMenMoveDownAndSideWithNoMovesLoses() {
        val pos = position(blackMen = listOf(1), whiteMen = listOf(45), toMove = Color.BLACK)
        val moves = Draughts.legalMoves(pos)
        assertEquals(setOf(6, 7), moves.map { it.to }.toSet())
        val stuck = position(whiteMen = listOf(46), blackMen = listOf(41), blackKings = listOf(37))
        assertEquals(Draughts.Outcome.BLACK_WINS, Draughts.outcome(stuck, emptyList(), 0))
    }
}
