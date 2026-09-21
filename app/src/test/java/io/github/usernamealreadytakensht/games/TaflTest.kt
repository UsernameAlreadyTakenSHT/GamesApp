package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.engine.tafl.OpenTaflEngine
import io.github.usernamealreadytakensht.games.game.tafl.Tafl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises OpenTafl's rules engine through the app's wrapper. */
class TaflTest {

    @Test
    fun copenhagenStartPosition() {
        val game = Tafl.newGame()
        assertEquals(24, Tafl.count(game, Tafl.ATTACKER))
        assertEquals(12, Tafl.count(game, Tafl.DEFENDER))
        assertEquals(1, Tafl.count(game, Tafl.KING))
        assertEquals(Tafl.KING, Tafl.board(game)[5 * Tafl.SIZE + 5])
        assertEquals(Tafl.Side.ATTACKERS, Tafl.sideToMove(game))
        assertEquals(Tafl.Outcome.ONGOING, Tafl.outcome(game))
    }

    @Test
    fun piecesMoveLikeRooksAndCornersAreOffLimits() {
        val game = Tafl.newGame()
        // Attacker on d1 (x=3, y=0): may slide along rank 1 but not onto the corner a1.
        val dests = Tafl.destinations(game, 3, 0)
        assertTrue(dests.contains(1 to 0))
        assertTrue(dests.contains(2 to 0))
        assertFalse(dests.contains(0 to 0))
        // Defenders cannot move while attackers are to move.
        assertTrue(Tafl.destinations(game, 5, 3).isEmpty())
        assertTrue(Tafl.legalMoves(game).isNotEmpty())
    }

    @Test
    fun notationRoundTrips() {
        assertEquals("a1", Tafl.name(0, 0))
        assertEquals("k11", Tafl.name(10, 10))
        for (m in listOf("d1-d3", "a4-a2", "k6-h6")) assertEquals(m, Tafl.Move.parse(m)!!.toNotation())
        assertEquals(null, Tafl.Move.parse("z9-a1"))
    }

    @Test
    fun playAndReplay() {
        val game = Tafl.newGame()
        assertTrue(Tafl.play(game, Tafl.Move.parse("d1-d3")!!))
        assertEquals(Tafl.Side.DEFENDERS, Tafl.sideToMove(game))
        assertFalse(Tafl.play(game, Tafl.Move.parse("a1-a2")!!)) // nothing on a1
        assertTrue(Tafl.play(game, Tafl.Move.parse("f4-b4")!!))
        val (replayed, played) = Tafl.replay(listOf("d1-d3", "f4-b4", "zz"))
        assertEquals(2, played.size)
        assertEquals(Tafl.board(game), Tafl.board(replayed))
    }

    @Test
    fun customCaptureBetweenTwoAttackers() {
        // Bring a defender out and sandwich it: f4-b4, then attackers b1-b3 … wait, b4 sits
        // between a4 (attacker) and c4 once an attacker lands on c4.
        val game = Tafl.newGame()
        assertTrue(Tafl.play(game, Tafl.Move.parse("d1-d3")!!))
        assertTrue(Tafl.play(game, Tafl.Move.parse("f4-b4")!!))
        assertTrue(Tafl.play(game, Tafl.Move.parse("d3-c3")!!)) // set up
        assertTrue(Tafl.play(game, Tafl.Move.parse("f5-f3")!!))
        assertTrue(Tafl.play(game, Tafl.Move.parse("c3-c4")!!)) // captures b4 against a4
        assertEquals(11, Tafl.count(game, Tafl.DEFENDER))
        assertEquals(listOf(1 to 3), Tafl.lastCaptures(game))
    }

    @Test
    fun engineFindsAMove() = runBlocking {
        val game = Tafl.newGame()
        val move = OpenTaflEngine().bestMove(game, depth = 2, thinkSeconds = 5)
        assertNotNull(move)
        assertTrue(move in Tafl.legalMoves(game))
    }
}
