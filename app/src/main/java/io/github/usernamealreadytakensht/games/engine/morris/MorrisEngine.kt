package io.github.usernamealreadytakensht.games.engine.morris

import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.Morris.Color
import io.github.usernamealreadytakensht.games.game.morris.Morris.Move
import io.github.usernamealreadytakensht.games.game.morris.Morris.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Built-in Nine Men's Morris opponent: iterative-deepening alpha-beta with a hand-written
 * evaluation (material, mills, open twos, mobility, blocked men). Runs in-process.
 *
 * Strength is the search depth ([bestMove]'s `depth`, null = as deep as the time allows).
 */
class MorrisEngine {

    @Volatile private var stopRequested = false
    private var deadline = 0L
    private var nodes = 0L
    /** Set once the search ran out of time or was stopped: results after that are garbage. */
    private var aborted = false

    fun stop() { stopRequested = true }

    /**
     * Best move for [pos], searching at most [depth] plies (null = unlimited) within
     * [moveTimeMs]. Ties at the root are broken at random for variety.
     */
    suspend fun bestMove(pos: Position, depth: Int?, moveTimeMs: Int): Move? = withContext(Dispatchers.Default) {
        stopRequested = false
        deadline = System.nanoTime() + moveTimeMs * 1_000_000L
        nodes = 0
        aborted = false
        val moves = Morris.legalMoves(pos)
        if (moves.isEmpty()) return@withContext null
        if (moves.size == 1) return@withContext moves[0]

        val ordered = moves.sortedByDescending { it.closesMill }
        var best: List<Move> = listOf(ordered[0])
        val maxDepth = depth ?: MAX_DEPTH
        for (d in 1..maxDepth) {
            val scored = ArrayList<Pair<Move, Int>>(ordered.size)
            var alpha = -INF
            for (m in ordered) {
                val child = Morris.play(pos, m)
                var v = -negamax(child, d - 1, -INF, -alpha, 1)
                // A value equal to alpha may be a fail-high bound rather than a real tie:
                // re-search with the full window so ties are genuine before picking at random.
                if (v == alpha && alpha > -INF && !aborted) v = -negamax(child, d - 1, -INF, INF, 1)
                if (aborted) break
                scored += m to v
                if (v > alpha) alpha = v
            }
            if (aborted) break
            val top = scored.maxOf { it.second }
            best = scored.filter { it.second == top }.map { it.first }
            // A forced win or loss cannot improve with more depth.
            if (top >= WIN - 100 || top <= -WIN + 100) break
        }
        best[Random.nextInt(best.size)]
    }

    /** Exact score of every root move at [depth] (full window), for tests and tuning. */
    internal fun analyse(pos: Position, depth: Int): List<Pair<Move, Int>> {
        stopRequested = false
        deadline = Long.MAX_VALUE
        aborted = false
        return Morris.legalMoves(pos).map { m -> m to -negamax(Morris.play(pos, m), depth - 1, -INF, INF, 1) }
    }

    private fun timeUp(): Boolean = (nodes and 1023L) == 0L && System.nanoTime() >= deadline

    private fun negamax(pos: Position, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        nodes++
        if (aborted || stopRequested || timeUp()) { aborted = true; return 0 }
        val me = pos.toMove
        // Material loss decided before the side to move gets to play.
        if (pos.inHand(me) == 0 && pos.onBoard(me) < 3) return -(WIN - ply)
        if (pos.inHand(me.other) == 0 && pos.onBoard(me.other) < 3) return WIN - ply
        val moves = Morris.legalMoves(pos)
        if (moves.isEmpty()) return -(WIN - ply)
        if (depth <= 0) return evaluate(pos)

        var alpha = alphaIn
        var best = -INF
        // Mill-closing moves first: they change material and cause most cut-offs.
        val ordered = if (moves.any { it.closesMill }) moves.sortedByDescending { it.closesMill } else moves
        for (m in ordered) {
            val v = -negamax(Morris.play(pos, m), depth - 1, -beta, -alpha, ply + 1)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    /** Static evaluation from the side to move's point of view. */
    private fun evaluate(pos: Position): Int {
        val me = pos.toMove
        return sideScore(pos, me) - sideScore(pos, me.other)
    }

    private fun sideScore(pos: Position, c: Color): Int {
        val board = pos.board
        val stone = Morris.stone(c)
        val enemy = Morris.stone(c.other)
        var score = 100 * pos.men(c)

        // Mills and open twos (two own men on a line with the third point empty).
        for (line in Morris.LINES) {
            var mine = 0
            var empty = 0
            for (p in line) {
                when (board[p]) { stone -> mine++; Morris.EMPTY -> empty++ }
            }
            if (mine == 3) score += 25
            else if (mine == 2 && empty == 1) score += 8
        }

        if (!pos.isPlacing(c) && !pos.isFlying(c)) {
            // Mobility along the lines, and men that cannot move at all.
            var free = 0
            var blocked = 0
            for (p in 0 until 24) {
                if (board[p] != stone) continue
                var n = 0
                for (q in Morris.ADJACENT[p]) if (board[q] == Morris.EMPTY) n++
                free += n
                if (n == 0) blocked++
            }
            score += 3 * free - 8 * blocked
        } else if (pos.isFlying(c)) {
            score += 15 // flying men are mobile even when the board is crowded
        }

        // Enemy men we could take: count enemy men outside mills as slightly more exposed.
        var exposed = 0
        for (p in 0 until 24) if (board[p] == enemy && !Morris.inMill(board, p, enemy)) exposed++
        score += exposed
        return score
    }

    companion object {
        private const val INF = 1_000_000
        private const val WIN = 100_000
        private const val MAX_DEPTH = 40
    }
}
