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
        assertEquals(32, v.board.points)
        for (p in 0 until 32) assertEquals("dark squares only", 1, (v.board.row(p) + v.board.col(p)) % 2)
        assertEquals("a1", v.board.name(28))
        assertEquals("g1", v.board.name(31))
        assertEquals("d8", v.board.name(v.foxStart.single()))
        val start = Fox.start(v)
        assertEquals(4, start.hunters())
        assertEquals(Side.FOX, start.toMove)
        // Fox on the top edge: two diagonal steps down.
        assertEquals(setOf("c7", "e7"), Fox.legalMoves(start).map { v.board.name(it.to) }.toSet())
    }

    @Test
    fun houndsOnlyMoveForward() {
        val v = FoxVariant.HOUNDS
        val pos = play(v, "d8-c7")
        val moves = Fox.legalMoves(pos)
        assertEquals(Side.HUNTERS, pos.toMove)
        assertTrue(moves.all { v.board.row(it.to) < v.board.row(it.from) })
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
        fun at(name: String) = (0 until 32).first { v.board.name(it) == name }
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
        assertEquals(33, v.board.points)
        val start = Fox.start(v)
        assertEquals(13, start.hunters())
        assertEquals("d4", v.board.name(v.foxStart.single()))
        // The centre has all eight neighbours; a point with an odd row+col has only four.
        val b = v.board
        val centre = v.foxStart.single()
        assertEquals(8, b.directions.indices.count { b.step(centre, it) >= 0 })
        val odd = (0 until 33).first { (b.row(it) + b.col(it)) % 2 == 1 && b.row(it) == 3 && b.col(it) == 2 }
        assertEquals(4, b.directions.indices.count { b.step(odd, it) >= 0 })
        // Fox in the centre facing the geese on row 2: it can step to the free points around it.
        assertTrue(Fox.legalMoves(start).all { !it.isJump })
    }

    @Test
    fun foxJumpsAndChains() {
        val v = FoxVariant.GEESE
        val board = IntArray(33)
        fun at(name: String) = (0 until 33).first { v.board.name(it) == name }
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
        assertEquals(listOf("d2", "d4", "d6"), chain.path.map { v.board.name(it) })
        assertEquals("d2xd4xd6", Fox.notation(v, chain))
    }

    @Test
    fun foxWinsWithFewGeese() {
        val v = FoxVariant.GEESE
        val board = IntArray(33)
        board[v.foxStart.single()] = Fox.FOX
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
    fun seventeenGeeseAndTwoFoxes() {
        val g17 = Fox.start(FoxVariant.GEESE_17)
        assertEquals(17, g17.hunters())
        assertEquals(1, g17.foxes().size)
        val two = Fox.start(FoxVariant.TWO_FOXES)
        assertEquals(17, two.hunters())
        assertEquals(2, two.foxes().size)
        // Both foxes can move, so the move list covers two starting squares.
        val froms = Fox.legalMoves(two).map { it.from }.toSet()
        assertEquals(2, froms.size)
        assertEquals(two.foxes().toSet(), froms)
        // Losing one fox is not losing the game: the other keeps playing.
        val board = two.board.copyOf()
        board[two.foxes()[0]] = Fox.EMPTY
        val oneLeft = Fox.Position(FoxVariant.TWO_FOXES, board, Fox.Side.FOX)
        assertEquals(Fox.Outcome.ONGOING, Fox.outcome(oneLeft))
    }

    @Test
    fun asaltoSetsUpAFortressAndSepoysNeverGoBackwards() {
        val v = FoxVariant.ASALTO
        val start = Fox.start(v)
        assertEquals(9, v.fortress.size)
        assertEquals(2, start.foxes().size)
        assertEquals(24, start.hunters())
        // Officers start inside the fortress, sepoys everywhere else.
        assertTrue(start.foxes().all { it in v.fortress.toSet() })
        assertTrue(v.fortress.none { start[it] == Fox.HUNTER })
        // Sepoys move forward or sideways only.
        val b = v.board
        val sepoyMoves = Fox.legalMoves(Fox.Position(v, start.board, Fox.Side.HUNTERS))
        assertTrue(sepoyMoves.isNotEmpty())
        assertTrue(sepoyMoves.all { b.row(it.to) <= b.row(it.from) })
        // The sepoys win by filling the fortress.
        val filled = IntArray(b.points)
        for (p in v.fortress) filled[p] = Fox.HUNTER
        assertEquals(Fox.Outcome.HUNTERS_WINS, Fox.outcome(Fox.Position(v, filled, Fox.Side.FOX)))
        // The officers win once too few sepoys remain to fill it.
        val thin = IntArray(b.points)
        thin[v.foxStart[0]] = Fox.FOX
        for (p in v.hunterStart.take(8)) thin[p] = Fox.HUNTER
        assertEquals(Fox.Outcome.FOX_WINS, Fox.outcome(Fox.Position(v, thin, Fox.Side.HUNTERS)))
    }

    @Test
    fun engineTakesAFreeGooseAndHoundsCloseTheNet() = runBlocking {
        val engine = FoxEngine()
        val v = FoxVariant.GEESE
        val board = IntArray(33)
        fun at(name: String) = (0 until 33).first { v.board.name(it) == name }
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
