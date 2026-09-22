package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.engine.tafl.OpenTaflEngine
import io.github.usernamealreadytakensht.games.game.tafl.Tafl
import io.github.usernamealreadytakensht.games.game.tafl.TaflVariant
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
        val game = Tafl.newGame(TaflVariant.COPENHAGEN)
        assertEquals(24, Tafl.count(game, Tafl.ATTACKER))
        assertEquals(12, Tafl.count(game, Tafl.DEFENDER))
        assertEquals(1, Tafl.count(game, Tafl.KING))
        assertEquals(Tafl.KING, Tafl.board(game)[5 * 11 + 5])
        assertEquals(Tafl.Side.ATTACKERS, Tafl.sideToMove(game))
        assertEquals(Tafl.Outcome.ONGOING, Tafl.outcome(game))
    }

    @Test
    fun piecesMoveLikeRooksAndCornersAreOffLimits() {
        val game = Tafl.newGame(TaflVariant.COPENHAGEN)
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
        val game = Tafl.newGame(TaflVariant.COPENHAGEN)
        assertTrue(Tafl.play(game, Tafl.Move.parse("d1-d3")!!))
        assertEquals(Tafl.Side.DEFENDERS, Tafl.sideToMove(game))
        assertFalse(Tafl.play(game, Tafl.Move.parse("a1-a2")!!)) // nothing on a1
        assertTrue(Tafl.play(game, Tafl.Move.parse("f4-b4")!!))
        val (replayed, played) = Tafl.replay(TaflVariant.COPENHAGEN, listOf("d1-d3", "f4-b4", "zz"))
        assertEquals(2, played.size)
        assertEquals(Tafl.board(game), Tafl.board(replayed))
    }

    @Test
    fun customCaptureBetweenTwoAttackers() {
        // Bring a defender out and sandwich it: f4-b4, then attackers b1-b3 … wait, b4 sits
        // between a4 (attacker) and c4 once an attacker lands on c4.
        val game = Tafl.newGame(TaflVariant.COPENHAGEN)
        assertTrue(Tafl.play(game, Tafl.Move.parse("d1-d3")!!))
        assertTrue(Tafl.play(game, Tafl.Move.parse("f4-b4")!!))
        assertTrue(Tafl.play(game, Tafl.Move.parse("d3-c3")!!)) // set up
        assertTrue(Tafl.play(game, Tafl.Move.parse("f5-f3")!!))
        assertTrue(Tafl.play(game, Tafl.Move.parse("c3-c4")!!)) // captures b4 against a4
        assertEquals(11, Tafl.count(game, Tafl.DEFENDER))
        assertEquals(listOf(1 to 3), Tafl.lastCaptures(game))
    }

    @Test
    fun everyVariantStartsAndPlays() {
        // Board size, forces and a first legal move for each variant OpenTafl gives us.
        val expected = mapOf(
            TaflVariant.COPENHAGEN to Triple(11, 24, 12),
            TaflVariant.FETLAR to Triple(11, 24, 12),
            TaflVariant.TAWLBWRDD to Triple(11, 24, 12),
            TaflVariant.TABLUT to Triple(9, 16, 8),
            TaflVariant.SEA_BATTLE to Triple(9, 16, 8),
            TaflVariant.BRANDUB to Triple(7, 8, 4),
        )
        for (v in TaflVariant.entries) {
            val game = Tafl.newGame(v)
            val (size, attackers, defenders) = expected.getValue(v)
            assertEquals(v.label, size, Tafl.size(game))
            assertEquals(v.label, size, v.size)
            assertEquals(v.label, attackers, Tafl.count(game, Tafl.ATTACKER))
            assertEquals(v.label, defenders, Tafl.count(game, Tafl.DEFENDER))
            assertEquals(v.label, 1, Tafl.count(game, Tafl.KING))
            // The king starts on the throne, the centre of the board.
            assertEquals(v.label, Tafl.KING, Tafl.board(game)[(size / 2) * size + size / 2])
            assertEquals(v.label, Tafl.Side.ATTACKERS, Tafl.sideToMove(game))
            val move = Tafl.legalMoves(game).first()
            assertTrue(v.label, Tafl.play(game, move))
            assertEquals(v.label, Tafl.Side.DEFENDERS, Tafl.sideToMove(game))
        }
    }

    @Test
    fun edgeVariantsLetTheKingUseEveryEdgeSquare() {
        // Tablut: the king escapes over any edge, so a corner is not special. Walk him to a1
        // is not possible in one move, but an edge square on his own file is.
        val game = Tafl.newGame(TaflVariant.TABLUT)
        assertFalse(TaflVariant.TABLUT.escapeToCorners)
        assertTrue(TaflVariant.BRANDUB.escapeToCorners)
        // Print-free check: some attacker can reach a corner, which corner variants forbid.
        val corners = setOf(0 to 0, 8 to 0, 0 to 8, 8 to 8)
        assertTrue(Tafl.legalMoves(game).any { (it.toX to it.toY) in corners })
        val corner = Tafl.newGame(TaflVariant.BRANDUB)
        val brandubCorners = setOf(0 to 0, 6 to 0, 0 to 6, 6 to 6)
        assertTrue(Tafl.legalMoves(corner).none { (it.toX to it.toY) in brandubCorners })
    }

    @Test
    fun brandubIsPlayedOnSevenSquares() {
        val game = Tafl.newGame(TaflVariant.BRANDUB)
        assertEquals(7, Tafl.size(game))
        assertEquals(49, Tafl.board(game).size)
        // Moves off the smaller board are rejected rather than crashing.
        assertFalse(Tafl.play(game, Tafl.Move.parse("k1-k3")!!))
    }

    @Test
    fun engineFindsAMove() = runBlocking {
        val game = Tafl.newGame(TaflVariant.COPENHAGEN)
        val move = OpenTaflEngine().bestMove(game, depth = 2, thinkSeconds = 5)
        assertNotNull(move)
        assertTrue(move in Tafl.legalMoves(game))
    }
}
