package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.game.Chess960
import io.github.usernamealreadytakensht.games.game.ChessSession
import io.github.usernamealreadytakensht.games.game.StandardChess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Chess960Test {

    private fun perft(s: ChessSession, depth: Int): Long {
        val moves = s.legalMoves()
        if (depth == 1) return moves.size.toLong()
        var n = 0L
        for (m in moves) {
            s.play(m)
            n += perft(s, depth - 1)
            s.undo()
        }
        return n
    }

    @Test
    fun chess960Perft() {
        // Chess960 perft suite positions (Shredder-FEN castling rights); the perft(4) values
        // were checked against Fairy-Stockfish.
        val cases = listOf(
            "bqnb1rkr/pp3ppp/3ppn2/2p5/5P2/P2P4/NPP1P1PP/BQ1BNRKR w HFhf - 2 9" to 326672L,
            "2nnrbkr/p1qppppp/8/1ppb4/6PP/3PP3/PPP2P2/BQNNRBKR w HEhe - 1 9" to 667366L,
            "b1q1rrkb/pppppppp/3nn3/8/P7/1PPP4/4PPPP/BQNNRKRB w GE - 1 9" to 273318L,
            "qbbnnrkr/2pp2pp/p7/1p2pp2/8/P3PP2/1PPP1KPP/QBBNNR1R w hf - 0 9" to 382958L,
        )
        for ((fen, expected) in cases) assertEquals(fen, expected, perft(Chess960(fen), 4))
    }

    @Test
    fun standardPositionThroughBothSessions() {
        // Position 518 is the classical setup: both sessions must agree with the known perft.
        val fen = Chess960.startFen(518)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w HAha - 0 1", fen)
        assertEquals(197281L, perft(Chess960(fen), 4))
        assertEquals(197281L, perft(StandardChess(), 4))
    }

    @Test
    fun startPositionsAreValid() {
        val seen = HashSet<String>()
        for (n in 0..959) {
            val fen = Chess960.startFen(n)
            val row = fen.substringAfterLast('/').substringBefore(' ')
            val k = row.indexOf('K'); val r1 = row.indexOf('R'); val r2 = row.lastIndexOf('R')
            val b1 = row.indexOf('B'); val b2 = row.lastIndexOf('B')
            assertTrue(fen, k in (r1 + 1) until r2)
            assertTrue(fen, (b1 + b2) % 2 == 1)
            // Only a king f1 / rook g1 (O-O) or king d1 / rook c1 (O-O-O) can castle from the
            // start: castling just swaps them.
            val moves = Chess960(fen).legalMoves()
            val swaps = (if (k == 5 && r2 == 6) 1 else 0) + (if (k == 3 && r1 == 2) 1 else 0)
            assertEquals(fen, swaps, moves.count { it.isCastle })
            assertTrue(fen, moves.count { !it.isCastle } in 18..20)
            seen += row
        }
        assertEquals(960, seen.size)
    }

    @Test
    fun castlingNotationAndUci() {
        // King g1 with rooks a1 and h1: castling is written king-takes-rook, and O-O / O-O-O.
        val s = Chess960("4k3/8/8/8/8/8/8/R5KR w HA - 0 1")
        val short = s.legalMoves().first { it.isCastle && it.castleRook!!.value() == "H1" }
        assertEquals("g1h1", short.toUci())
        s.play(short)
        assertEquals(listOf("O-O"), s.sanMoves)
        assertEquals(com.github.bhlangonijr.chesslib.Piece.WHITE_KING, s.pieceAt(com.github.bhlangonijr.chesslib.Square.G1))
        assertEquals(com.github.bhlangonijr.chesslib.Piece.WHITE_ROOK, s.pieceAt(com.github.bhlangonijr.chesslib.Square.F1))
        s.undo()
        assertEquals(s.parseUci("g1a1")?.isCastle, true)
    }
}
