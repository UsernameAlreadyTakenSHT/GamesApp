package io.github.usernamealreadytakensht.games.engine.fox

import io.github.usernamealreadytakensht.games.game.fox.Fox
import io.github.usernamealreadytakensht.games.game.fox.Fox.Move
import io.github.usernamealreadytakensht.games.game.fox.Fox.Position
import io.github.usernamealreadytakensht.games.game.fox.Fox.Side
import io.github.usernamealreadytakensht.games.game.fox.FoxVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

/**
 * Opponent for the hunt games: iterative-deepening negamax with a transposition table.
 * The games are tiny, so a deep search plays close to perfectly; strength is the depth
 * (null = as deep as the time allows).
 */
class FoxEngine {

    @Volatile private var stopRequested = false
    private var deadline = 0L
    private var nodes = 0L
    private var aborted = false
    private val table = HashMap<Position, Entry>()

    private class Entry(val depth: Int, val value: Int, val flag: Int) // flag: 0 exact, 1 lower, 2 upper

    fun stop() { stopRequested = true }

    suspend fun bestMove(pos: Position, depth: Int?, moveTimeMs: Int): Move? = withContext(Dispatchers.Default) {
        stopRequested = false
        deadline = System.nanoTime() + moveTimeMs * 1_000_000L
        nodes = 0
        aborted = false
        table.clear()
        val moves = Fox.legalMoves(pos)
        if (moves.isEmpty()) return@withContext null
        if (moves.size == 1) return@withContext moves[0]

        val ordered = moves.sortedByDescending { it.captures.size }
        var best: List<Move> = listOf(ordered[0])
        for (d in 1..(depth ?: MAX_DEPTH)) {
            val scored = ArrayList<Pair<Move, Int>>()
            var alpha = -INF
            for (m in ordered) {
                val child = Fox.play(pos, m)
                var v = -negamax(child, d - 1, -INF, -alpha, 1)
                if (v == alpha && alpha > -INF && !aborted) v = -negamax(child, d - 1, -INF, INF, 1)
                if (aborted) break
                scored += m to v
                if (v > alpha) alpha = v
            }
            if (aborted) break
            val top = scored.maxOf { it.second }
            best = scored.filter { it.second == top }.map { it.first }
            if (top >= WIN - 100 || top <= -WIN + 100) break
        }
        best[Random.nextInt(best.size)]
    }

    private fun timeUp() = (nodes and 1023L) == 0L && System.nanoTime() >= deadline

    private fun negamax(pos: Position, depth: Int, alphaIn: Int, betaIn: Int, ply: Int): Int {
        nodes++
        if (aborted || stopRequested || timeUp()) { aborted = true; return 0 }
        when (Fox.outcome(pos)) {
            Fox.Outcome.FOX_WINS -> return if (pos.toMove == Side.FOX) WIN - ply else -(WIN - ply)
            Fox.Outcome.HUNTERS_WINS -> return if (pos.toMove == Side.HUNTERS) WIN - ply else -(WIN - ply)
            Fox.Outcome.ONGOING -> Unit
        }
        if (depth <= 0) return evaluate(pos)

        var alpha = alphaIn
        var beta = betaIn
        table[pos]?.let { e ->
            if (e.depth >= depth) {
                when (e.flag) {
                    0 -> return e.value
                    1 -> if (e.value > alpha) alpha = e.value
                    2 -> if (e.value < beta) beta = e.value
                }
                if (alpha >= beta) return e.value
            }
        }

        val moves = Fox.legalMoves(pos).sortedByDescending { it.captures.size }
        var best = -INF
        for (m in moves) {
            val v = -negamax(Fox.play(pos, m), depth - 1, -beta, -alpha, ply + 1)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        if (!aborted) {
            val flag = if (best <= alphaIn) 2 else if (best >= betaIn) 1 else 0
            table[pos] = Entry(depth, best, flag)
        }
        return best
    }

    /** Static evaluation from the side to move's point of view (positive = good for it). */
    private fun evaluate(pos: Position): Int {
        val v = pos.variant
        val b = v.board
        val foxMoves = Fox.legalMoves(Position(v, pos.board, Side.FOX))
        var foxScore = 6 * foxMoves.size
        when (v) {
            FoxVariant.HOUNDS -> {
                // The fox wants to get down the board, the hounds to keep a line ahead of it.
                foxScore += 12 * (pos.foxes().maxOfOrNull { b.row(it) } ?: 0)
            }
            FoxVariant.ASALTO -> {
                // Sepoys press into the fortress; officers live off captures and room to jump.
                foxScore += 60 * (v.hunterStart.size - pos.hunters())
                foxScore += 10 * foxMoves.count { it.isJump }
                val held = v.fortress.count { pos[it] == HUNTER_CODE }
                foxScore -= 25 * held
            }
            else -> {
                foxScore += 60 * (v.hunterStart.size - pos.hunters())
                foxScore += 10 * foxMoves.count { it.isJump }
                // Geese keep their formation tight around the foxes.
                val foxes = pos.foxes()
                if (foxes.isNotEmpty()) {
                    var spread = 0
                    for (p in 0 until b.points) {
                        if (pos[p] != HUNTER_CODE) continue
                        spread += foxes.minOf { abs(b.col(p) - b.col(it)) + abs(b.row(p) - b.row(it)) }
                    }
                    foxScore += spread
                }
            }
        }
        val hunterScore = 2 * Fox.legalMoves(Position(v, pos.board, Side.HUNTERS)).size
        val score = foxScore - hunterScore
        return if (pos.toMove == Side.FOX) score else -score
    }

    companion object {
        private const val INF = 1_000_000
        private const val WIN = 100_000
        private const val MAX_DEPTH = 40
        private const val HUNTER_CODE = Fox.HUNTER
    }
}
