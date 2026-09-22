package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.Morris.Color
import io.github.usernamealreadytakensht.games.game.morris.Morris.Move
import io.github.usernamealreadytakensht.games.game.morris.Morris.Position
import io.github.usernamealreadytakensht.games.game.morris.MorrisVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorrisTest {

    private fun position(
        white: String,
        black: String,
        toMove: Color,
        whiteInHand: Int = 0,
        blackInHand: Int = 0,
        variant: MorrisVariant = MorrisVariant.STANDARD,
    ): Position {
        val board = IntArray(24)
        white.split(' ').filter { it.isNotBlank() }.forEach { board[Morris.pointOf(it)!!] = Morris.WHITE }
        black.split(' ').filter { it.isNotBlank() }.forEach { board[Morris.pointOf(it)!!] = Morris.BLACK }
        return Position(variant, board, toMove, whiteInHand, blackInHand)
    }

    private fun play(vararg moves: String): Position {
        var pos = Morris.start(MorrisVariant.STANDARD)
        for (m in moves) {
            val move = Move.parse(m)!!
            assertTrue("$m must be legal", move in Morris.legalMoves(pos))
            pos = Morris.play(pos, move)
        }
        return pos
    }

    @Test
    fun boardGeometry() {
        // Every point lies on exactly two lines and has two to four neighbours; 32 edges in all.
        for (p in 0 until 24) {
            assertEquals(2, Morris.LINES_OF[p].size)
            assertTrue(Morris.ADJACENT[p].size in 2..4)
            for (q in Morris.ADJACENT[p]) assertTrue(p in Morris.ADJACENT[q])
        }
        assertEquals(64, Morris.ADJACENT.sumOf { it.size })
        assertEquals(16, Morris.LINES.size)
        for (p in 0 until 24) assertEquals(p, Morris.pointOf(Morris.name(p)))
        assertEquals("a7", Morris.name(0))
        assertEquals("g1", Morris.name(23))
        assertEquals("c4", Morris.name(11))
        assertEquals("e4", Morris.name(12))
    }

    @Test
    fun centreIsNotAPoint() {
        assertEquals(null, Morris.pointOf("d4"))
    }

    @Test
    fun placementPhase() {
        assertEquals(24, Morris.legalMoves(Morris.start(MorrisVariant.STANDARD)).size)
        val pos = play("a7")
        assertEquals(8, pos.whiteInHand)
        assertEquals(Color.BLACK, pos.toMove)
        assertEquals(23, Morris.legalMoves(pos).size)
    }

    @Test
    fun closingAMillOffersEachRemovableEnemyMan() {
        // White has a7, d7; black has a1, g1 (not in a mill). g7 closes the top line.
        val pos = play("a7", "a1", "d7", "g1")
        val mills = Morris.legalMoves(pos).filter { it.to == Morris.pointOf("g7") }
        assertEquals(2, mills.size)
        assertTrue(mills.all { it.closesMill })
        assertEquals(setOf("a1", "g1"), mills.map { Morris.name(it.remove) }.toSet())
        val after = Morris.play(pos, Move.parse("g7xa1")!!)
        assertEquals(Morris.EMPTY, after[Morris.pointOf("a1")!!])
        assertEquals(1, after.onBoard(Color.BLACK))
    }

    @Test
    fun menInMillsAreProtectedUnlessAllAreInMills() {
        // Black mill a1 d1 g1 plus a loose man at b2; white closes a7 d7 g7.
        val pos = play("a7", "a1", "d7", "d1", "b6", "g1xb6", "e5", "b2")
        val mills = Morris.legalMoves(pos).filter { it.to == Morris.pointOf("g7") }
        assertEquals(listOf("b2"), mills.map { Morris.name(it.remove) })
        // With only the mill on the board, any of its men may go.
        val allInMill = position("a7 d7", "a1 d1 g1", Color.WHITE, whiteInHand = 7, blackInHand = 6)
        val onlyMill = Morris.legalMoves(allInMill).filter { it.to == Morris.pointOf("g7") }
        assertEquals(setOf("a1", "d1", "g1"), onlyMill.map { Morris.name(it.remove) }.toSet())
    }

    @Test
    fun slidingPhaseFollowsTheLines() {
        val pos = position("a7 d7 b6 f2", "a1 d1 g1 b2", Color.WHITE)
        assertFalse(pos.isFlying(Color.WHITE))
        val moves = Morris.legalMoves(pos)
        assertTrue(moves.none { it.isPlacement })
        val from = { n: String -> moves.filter { it.from == Morris.pointOf(n) }.map { Morris.name(it.to) }.toSet() }
        assertEquals(setOf("a4"), from("a7"))          // d7 is occupied
        assertEquals(setOf("g7", "d6"), from("d7"))
        assertEquals(setOf("d6", "b4"), from("b6"))
        assertEquals(setOf("d2", "f4"), from("f2"))
        assertEquals(7, moves.size)
    }

    @Test
    fun threeMenMayFly() {
        val pos = position("a7 d7 g7", "a1 d1 g1 b2 f2 c3", Color.WHITE)
        assertTrue(pos.isFlying(Color.WHITE))
        val targets = Morris.legalMoves(pos).filter { it.from == Morris.pointOf("a7") }.map { it.to }.toSet()
        assertEquals(24 - 9, targets.size)
    }

    @Test
    fun gameEndsBelowThreeMenOrWithoutMoves() {
        val twoMen = position("a7 d7", "a1 d1 g1 b2", Color.WHITE)
        assertEquals(Morris.Outcome.BLACK_WINS, Morris.outcome(twoMen, emptyList(), 0))
        // Four white men along the top-left, every exit held by black: no legal move.
        val blocked = position("a7 d7 g7 a4", "d6 g4 b4 a1 b2 f2", Color.WHITE)
        assertFalse(blocked.isFlying(Color.WHITE))
        assertTrue(Morris.legalMoves(blocked).isEmpty())
        assertEquals(Morris.Outcome.BLACK_WINS, Morris.outcome(blocked, emptyList(), 0))
        // Draws by inactivity and by repetition.
        val quiet = position("a7 d7 a4 b6", "a1 d1 g1 b2", Color.WHITE)
        assertEquals(Morris.Outcome.DRAW, Morris.outcome(quiet, emptyList(), 100))
        assertEquals(Morris.Outcome.DRAW, Morris.outcome(quiet, listOf(quiet, quiet), 0))
        assertEquals(Morris.Outcome.ONGOING, Morris.outcome(quiet, listOf(quiet), 0))
    }

    @Test
    fun laskerMorrisPlacesOrSlides() {
        val v = MorrisVariant.LASKER
        val start = Morris.start(v)
        assertEquals(10, start.whiteInHand)
        // With an empty board there is nothing to slide, so only the 24 placements.
        assertEquals(24, Morris.legalMoves(start).size)
        // With men on the board, placements and slides are both offered from move one.
        val pos = position("a7", "a1", Color.WHITE, whiteInHand = 9, blackInHand = 9, variant = v)
        val moves = Morris.legalMoves(pos)
        assertTrue(moves.any { it.isPlacement })
        assertTrue(moves.any { !it.isPlacement && Morris.name(it.from) == "a7" })
        // The standard game only allows placements while men remain in hand.
        val standard = position("a7", "a1", Color.WHITE, whiteInHand = 8, blackInHand = 8)
        assertTrue(Morris.legalMoves(standard).all { it.isPlacement })
    }

    @Test
    fun notationRoundTrips() {
        for (m in listOf("d7", "a1-d1", "g7xa1", "a1-d1xg7")) assertEquals(m, Move.parse(m)!!.toNotation())
        assertEquals(null, Move.parse("d4"))
        assertEquals(null, Move.parse("zz"))
    }
}
