package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.engine.fox.FoxEngine
import io.github.usernamealreadytakensht.games.game.fox.Fox
import io.github.usernamealreadytakensht.games.game.fox.Fox.Side
import io.github.usernamealreadytakensht.games.game.fox.FoxVariant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxTest {

    private fun play(v: FoxVariant, vararg moves: String): Fox.Position {
        var pos = Fox.start(v)
        for (m in moves) {
            val move = Fox.parse(v, pos, m) ?: throw AssertionError("$m is not legal")
            pos = Fox.play(pos, move)
        }
        return pos
    }

    @Test
    fun houndsGeometry() {
        val v = FoxVariant.HOUNDS
        assertEquals(32, v.points)
        for (p in 0 until 32) assertEquals("dark squares only", 1, (v.row(p) + v.col(p)) % 2)
        assertEquals("a1", v.name(28))
        assertEquals("g1", v.name(31))
        assertEquals("d8", v.name(v.foxStart))
        val start = Fox.start(v)
        assertEquals(4, start.hunters())
        assertEquals(Side.FOX, start.toMove)
        // Fox on the top edge: two diagonal steps down.
        assertEquals(setOf("c7", "e7"), Fox.legalMoves(start).map { v.name(it.to) }.toSet())
    }

    @Test
    fun houndsOnlyMoveForward() {
        val v = FoxVariant.HOUNDS
        val pos = play(v, "d8-c7")
        val moves = Fox.legalMoves(pos)
        assertEquals(Side.HUNTERS, pos.toMove)
        assertTrue(moves.all { v.row(it.to) < v.row(it.from) })
        assertEquals(7, moves.size) // a1: b2; c1: b2 d2; e1: d2 f2; g1: f2 h2
    }

    @Test
    fun foxWinsByReachingTheBackRowOrTrappedHounds() {
        val v = FoxVariant.HOUNDS
        // The a1 hound wanders up the board while the fox walks down the a/b files to a1.
        val pos = play(v, "d8-c7", "a1-b2", "c7-b6", "b2-c3", "b6-a5", "c3-d4", "a5-b4", "d4-e5", "b4-a3", "e5-f6", "a3-b2", "f6-g7", "b2-a1")
        assertEquals(Fox.Outcome.FOX_WINS, Fox.outcome(pos))
    }

    @Test
    fun houndsWinByTrappingTheFox() {
        val v = FoxVariant.HOUNDS
        val board = IntArray(32)
        fun at(name: String) = (0 until 32).first { v.name(it) == name }
        board[at("b8")] = Fox.FOX // its only neighbours are a7 and c7
        board[at("a7")] = Fox.HUNTER
        board[at("c7")] = Fox.HUNTER
        board[at("a1")] = Fox.HUNTER
        board[at("c1")] = Fox.HUNTER
        val pos = Fox.Position(v, board, Side.FOX)
        assertTrue(Fox.legalMoves(pos).isEmpty())
        assertEquals(Fox.Outcome.HUNTERS_WINS, Fox.outcome(pos))
    }

    @Test
    fun geeseGeometryAndDiagonals() {
        val v = FoxVariant.GEESE
        assertEquals(33, v.points)
        val start = Fox.start(v)
        assertEquals(13, start.hunters())
        assertEquals("d4", v.name(v.foxStart))
        // The centre has all eight neighbours; a point with an odd row+col has only four.
        val centre = v.foxStart
        assertEquals(8, v.directions.indices.count { v.step(centre, it) >= 0 })
        val odd = (0 until 33).first { (v.row(it) + v.col(it)) % 2 == 1 && v.row(it) == 3 && v.col(it) == 2 }
        assertEquals(4, v.directions.indices.count { v.step(odd, it) >= 0 })
        // Fox in the centre facing the geese on row 2: it can step to the free points around it.
        assertTrue(Fox.legalMoves(start).all { !it.isJump })
    }

    @Test
    fun foxJumpsAndChains() {
        val v = FoxVariant.GEESE
        val board = IntArray(33)
        fun at(name: String) = (0 until 33).first { v.name(it) == name }
        board[at("d4")] = Fox.FOX
        board[at("d5")] = Fox.HUNTER // straight up: jump to d6 …
        board[at("c5")] = Fox.HUNTER // … then from d6 (a diagonal point) over c5 down-left to b4
        board[at("e6")] = Fox.HUNTER // right of d6, but landing on f6 is off the cross: no jump
        val pos = Fox.Position(v, board, Side.FOX)
        val jumps = Fox.legalMoves(pos).filter { it.isJump }
        // Only maximal chains are offered: the single jump d4-d6 is extended over c5.
        assertEquals(listOf("d4xd6xb4"), jumps.map { Fox.notation(v, it) })
        val after = Fox.play(pos, jumps[0])
        assertEquals(1, after.hunters())
        assertEquals(Fox.EMPTY, after[at("d5")])
        assertEquals(Fox.EMPTY, after[at("c5")])
        assertEquals(Fox.FOX, after[at("b4")])
        // Chain: geese at d5 and d3? Set up a two-jump line d2 -> d4 -> d6 with geese on d3 and d5.
        val b2 = IntArray(33)
        b2[at("d2")] = Fox.FOX
        b2[at("d3")] = Fox.HUNTER
        b2[at("d5")] = Fox.HUNTER
        val p2 = Fox.Position(v, b2, Side.FOX)
        val chain = Fox.legalMoves(p2).filter { it.isJump }.maxByOrNull { it.captures.size }!!
        assertEquals(listOf("d2", "d4", "d6"), chain.path.map { v.name(it) })
        assertEquals("d2xd4xd6", Fox.notation(v, chain))
    }

    @Test
    fun foxWinsWithFewGeese() {
        val v = FoxVariant.GEESE
        val board = IntArray(33)
        board[v.foxStart] = Fox.FOX
        for (p in v.hunterStart.take(5)) board[p] = Fox.HUNTER
        assertEquals(Fox.Outcome.FOX_WINS, Fox.outcome(Fox.Position(v, board, Side.HUNTERS)))
        for (p in v.hunterStart.take(6)) board[p] = Fox.HUNTER
        assertEquals(Fox.Outcome.ONGOING, Fox.outcome(Fox.Position(v, board, Side.HUNTERS)))
    }

    @Test
    fun notationRoundTrips() {
        val v = FoxVariant.HOUNDS
        val pos = Fox.start(v)
        val m = Fox.legalMoves(pos)[0]
        assertEquals(m, Fox.parse(v, pos, Fox.notation(v, m)))
        assertEquals(null, Fox.parse(v, pos, "a1-b2")) // not the side to move
    }

    @Test
    fun engineTakesAFreeGooseAndHoundsCloseTheNet() = runBlocking {
        val engine = FoxEngine()
        val v = FoxVariant.GEESE
        val board = IntArray(33)
        fun at(name: String) = (0 until 33).first { v.name(it) == name }
        board[at("d2")] = Fox.FOX
        board[at("d3")] = Fox.HUNTER
        board[at("d5")] = Fox.HUNTER
        for (n in listOf("c7", "d7", "e7", "a5", "g5", "a4")) board[at(n)] = Fox.HUNTER
        val pos = Fox.Position(v, board, Side.FOX)
        val m = engine.bestMove(pos, depth = 4, moveTimeMs = 3000)!!
        assertTrue("engine should capture", m.isJump)
        assertEquals(2, m.captures.size)

        // Hounds to move with the fox two rows away: the engine keeps a legal, forward move.
        val h = FoxVariant.HOUNDS
        val hp = play(h, "d8-e7")
        val hm = engine.bestMove(hp, depth = 8, moveTimeMs = 3000)!!
        assertTrue(hm in Fox.legalMoves(hp))
        assertFalse(hm.isJump)
    }
}
