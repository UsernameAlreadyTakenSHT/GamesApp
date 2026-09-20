package io.github.usernamealreadytakensht.games.engine

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/**
 * Input/output encoding of the Maia-3 model (github.com/CSSLab/maia3), mirroring
 * `maia3/dataset.py` and `maia3/utils.py`:
 *
 *  - a position is 64 squares x 12 one-hot planes (P N B R Q K for the side to move, then
 *    the same for the opponent); when black is to move the board is mirrored vertically and
 *    colours are swapped, so the model always "plays white";
 *  - the model input is the last [HISTORY] positions, oldest first, each encoded from the
 *    point of view of the side to move *at that ply*, padded at the front by repeating the
 *    oldest one;
 *  - the move vocabulary is every from-square x to-square pair (4096, a1..h8 row-major)
 *    followed by the 256 white promotions `<file>7<file>8<q|r|b|n>`.
 */
object Maia3Encoder {

    const val HISTORY = 8
    const val PLANES = 12
    const val TOKEN_DIM = HISTORY * PLANES
    const val VOCAB = 64 * 64 + 8 * 8 * 4

    private val PROMO_ORDER = "qrbn"

    /** Piece planes (0-based) for a square; null when empty. */
    private fun plane(piece: Piece, sideToMove: Side): Int? {
        if (piece == Piece.NONE) return null
        val type = when (piece.pieceType) {
            PieceType.PAWN -> 1
            PieceType.KNIGHT -> 2
            PieceType.BISHOP -> 3
            PieceType.ROOK -> 4
            PieceType.QUEEN -> 5
            PieceType.KING -> 6
            else -> return null
        }
        return type - 1 + if (piece.pieceSide == sideToMove) 0 else 6
    }

    /** 64 x 12 planes of [board] from the side to move's perspective (flattened). */
    fun tokenize(board: Board): FloatArray {
        val out = FloatArray(64 * PLANES)
        val stm = board.sideToMove
        for (sq in 0 until 64) {
            // Mirror: square (file, rank) reads the piece at (file, 7 - rank) when black moves.
            val src = if (stm == Side.WHITE) sq else (sq % 8) + 8 * (7 - sq / 8)
            val p = plane(board.getPiece(Square.squareAt(src)), stm) ?: continue
            out[sq * PLANES + p] = 1f
        }
        return out
    }

    /**
     * Model input for the game [uciMoves] played from the start position: shape
     * (64, [TOKEN_DIM]) flattened row-major, i.e. `tokens[sq * TOKEN_DIM + h * 12 + plane]`.
     * Also returns the final board.
     */
    fun encode(uciMoves: List<String>): Pair<Board, FloatArray> {
        val board = Board()
        val positions = ArrayDeque<FloatArray>()
        fun push(t: FloatArray) { positions.addLast(t); if (positions.size > HISTORY) positions.removeFirst() }
        push(tokenize(board))
        for (uci in uciMoves) {
            board.doMove(parseUci(board, uci) ?: error("illegal move $uci"))
            push(tokenize(board))
        }
        val hist = ArrayList<FloatArray>(HISTORY)
        repeat(HISTORY - positions.size) { hist += positions.first() }
        hist += positions
        val out = FloatArray(64 * TOKEN_DIM)
        for (sq in 0 until 64) for (h in 0 until HISTORY) {
            System.arraycopy(hist[h], sq * PLANES, out, sq * TOKEN_DIM + h * PLANES, PLANES)
        }
        return board to out
    }

    /** Vocabulary index of a UCI move already expressed from white's perspective. */
    fun indexOf(uci: String): Int? {
        val from = squareIndex(uci.substring(0, 2)) ?: return null
        val to = squareIndex(uci.substring(2, 4)) ?: return null
        if (uci.length == 4) return from * 64 + to
        val promo = PROMO_ORDER.indexOf(uci[4].lowercaseChar())
        if (promo < 0 || uci[1] != '7' || uci[3] != '8') return null
        return 64 * 64 + ((uci[0] - 'a') * 8 + (uci[2] - 'a')) * 4 + promo
    }

    /** UCI move (white's perspective) for a vocabulary index. */
    fun moveAt(index: Int): String {
        if (index < 64 * 64) return squareName(index / 64) + squareName(index % 64)
        val rest = index - 64 * 64
        val fromFile = 'a' + rest / 32
        val toFile = 'a' + (rest / 4) % 8
        return "${fromFile}7${toFile}8${PROMO_ORDER[rest % 4]}"
    }

    /** Vertical mirror of a UCI move (rank r -> 9 - r), used for black to move. */
    fun mirrorMove(uci: String): String {
        val sb = StringBuilder(uci.length)
        sb.append(uci[0]).append('9' - (uci[1] - '0'))
        sb.append(uci[2]).append('9' - (uci[3] - '0'))
        if (uci.length > 4) sb.append(uci.substring(4))
        return sb.toString()
    }

    /** Legal moves of [board] as (vocabulary index -> chesslib move). */
    fun legalMoves(board: Board): Map<Int, Move> {
        val black = board.sideToMove == Side.BLACK
        val out = HashMap<Int, Move>()
        for (m in board.legalMoves()) {
            var uci = m.from.value().lowercase() + m.to.value().lowercase()
            if (m.promotion != Piece.NONE) uci += m.promotion.pieceType.sanSymbol.lowercase()
            if (black) uci = mirrorMove(uci)
            indexOf(uci)?.let { out[it] = m }
        }
        return out
    }

    fun parseUci(board: Board, uci: String): Move? {
        val from = Square.fromValue(uci.substring(0, 2).uppercase())
        val to = Square.fromValue(uci.substring(2, 4).uppercase())
        val promo = uci.getOrNull(4)?.let { c ->
            val type = when (c.lowercaseChar()) {
                'q' -> PieceType.QUEEN; 'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP; 'n' -> PieceType.KNIGHT
                else -> return null
            }
            Piece.make(board.sideToMove, type)
        }
        val want = if (promo != null) Move(from, to, promo) else Move(from, to)
        return board.legalMoves().firstOrNull { it == want }
    }

    private fun squareIndex(name: String): Int? {
        val f = name[0] - 'a'
        val r = name[1] - '1'
        return if (f in 0..7 && r in 0..7) r * 8 + f else null
    }

    private fun squareName(index: Int) = "${'a' + index % 8}${'1' + index / 8}"
}
