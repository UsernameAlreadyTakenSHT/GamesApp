package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.engine.Sunfish
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunfishTest {

    /** Runs the driver like SunfishEngine does and returns the chosen move in UCI. */
    private fun bestMove(moves: List<String>, depth: Int): String? {
        val s = Sunfish.Searcher()
        var cand: Sunfish.Move? = null
        var best: Sunfish.Move? = null
        var d0 = 1
        var terminal = false
        try {
            s.search(Sunfish.history(moves), depth) { step ->
                if (step.depth > d0) { best = cand ?: best; d0 = step.depth }
                if (step.score >= step.gamma) {
                    if (step.move == null) { terminal = true; throw Sunfish.Stop() }
                    cand = step.move
                }
            }
        } catch (_: Sunfish.Stop) {
            if (!terminal) cand = best ?: cand
        }
        return (cand ?: best)?.let { Sunfish.toUci(it, moves.size) }
    }

    private fun isLegal(board: Board, uci: String): Boolean {
        val from = Square.fromValue(uci.substring(0, 2).uppercase())
        val to = Square.fromValue(uci.substring(2, 4).uppercase())
        return board.legalMoves().any { it.from == from && it.to == to }
    }

    @Test
    fun selfPlayProducesLegalMoves() {
        val board = Board()
        val moves = mutableListOf<String>()
        repeat(30) {
            val uci = bestMove(moves, depth = 3) ?: return
            assertTrue("illegal move $uci after $moves", isLegal(board, uci))
            val from = Square.fromValue(uci.substring(0, 2).uppercase())
            val to = Square.fromValue(uci.substring(2, 4).uppercase())
            board.doMove(board.legalMoves().first { it.from == from && it.to == to })
            moves += uci
            if (board.isMated || board.isStaleMate || board.isDraw) return
        }
    }

    @Test
    fun findsMateInOne() {
        // 1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6?? 4.Qxf7#
        val moves = listOf("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6")
        assertEquals("h5f7", bestMove(moves, depth = 3))
    }

    @Test
    fun blackFindsMateInOne() {
        // 1.f3 e5 2.g4 Qh4#
        val moves = listOf("f2f3", "e7e5", "g2g4")
        assertEquals("d8h4", bestMove(moves, depth = 3))
    }

    @Test
    fun promotionIsRendered() {
        // White pawn on a7, black king h8, white king h1: a8=Q is the obvious move.
        // Reached via a synthetic history is awkward, so check the renderer directly.
        assertEquals("a7a8q", Sunfish.toUci(Sunfish.Move(Sunfish.parse("a7"), Sunfish.parse("a8"), 'Q'), 0))
        assertNotNull(Sunfish.history(listOf("e2e4", "d7d5", "e4d5")).last())
    }
}
