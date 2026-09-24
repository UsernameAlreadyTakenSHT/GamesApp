package io.github.usernamealreadytakensht.games.game.draughts

import io.github.usernamealreadytakensht.games.game.draughts.Draughts.Color

/**
 * English draughts / American checkers on 8x8, pure Kotlin.
 *
 * Squares are 0..63 as row * 8 + col, row 0 at the top: the same indexing as the Marcher
 * engine's bitboards. Only the dark squares ((row + col) odd) are used. Black moves first
 * and starts on rows 5-7 (the bottom), moving up; White starts on rows 0-2.
 *
 * Rules: men step one square diagonally forward, kings one square in any diagonal
 * direction (no flying kings). Capturing is compulsory, a capture must be continued while
 * the capturing piece can jump again, but the player may choose any capture sequence (not
 * necessarily the longest). Men capture forward only. A man reaching the last row is crowned
 * and the move ends there. A side with no legal move loses; threefold repetition or forty
 * moves each without a capture or a man move is a draw.
 *
 * The standard notation numbers the dark squares 1..32 with Black on 1-12 at the top of the
 * diagram, which is our board seen from White's side: see [number].
 */
object Checkers {

    const val EMPTY = 0
    const val BLACK_MAN = 1
    const val BLACK_KING = 2
    const val WHITE_MAN = 3
    const val WHITE_KING = 4

    enum class Outcome { ONGOING, BLACK_WINS, WHITE_WINS, DRAW }

    /** A complete move along [path] (start, then every landing square); jumps take [captures]. */
    data class Move(val path: List<Int>, val captures: List<Int>) {
        val from: Int get() = path.first()
        val to: Int get() = path.last()
        val isCapture: Boolean get() = captures.isNotEmpty()
    }

    class Position(val board: IntArray, val toMove: Color) {
        operator fun get(sq: Int) = board[sq]

        override fun equals(other: Any?) = other is Position && toMove == other.toMove && board.contentEquals(other.board)
        override fun hashCode() = board.contentHashCode() * 31 + toMove.ordinal
    }

    fun isDark(sq: Int) = (sq / 8 + sq % 8) % 2 == 1

    val START: Position by lazy {
        val b = IntArray(64)
        for (sq in 0 until 64) {
            if (!isDark(sq)) continue
            if (sq / 8 <= 2) b[sq] = WHITE_MAN
            if (sq / 8 >= 5) b[sq] = BLACK_MAN
        }
        Position(b, Color.BLACK)
    }

    fun colorOf(piece: Int): Color? = when (piece) {
        BLACK_MAN, BLACK_KING -> Color.BLACK
        WHITE_MAN, WHITE_KING -> Color.WHITE
        else -> null
    }

    fun isKing(piece: Int) = piece == BLACK_KING || piece == WHITE_KING

    /** Row steps a piece may take: men forward only (Black up, White down), kings both ways. */
    private fun rowSteps(piece: Int): IntArray = when (piece) {
        BLACK_MAN -> intArrayOf(-1)
        WHITE_MAN -> intArrayOf(1)
        else -> intArrayOf(-1, 1)
    }

    private fun onBoard(r: Int, c: Int) = r in 0..7 && c in 0..7

    fun legalMoves(pos: Position): List<Move> {
        val me = pos.toMove
        val captures = ArrayList<Move>()
        for (sq in 0 until 64) {
            val piece = pos[sq]
            if (colorOf(piece) != me) continue
            collectJumps(pos, sq, piece, listOf(sq), emptyList(), captures)
        }
        if (captures.isNotEmpty()) return captures

        val moves = ArrayList<Move>()
        for (sq in 0 until 64) {
            val piece = pos[sq]
            if (colorOf(piece) != me) continue
            val r = sq / 8
            val c = sq % 8
            for (dr in rowSteps(piece)) for (dc in intArrayOf(-1, 1)) {
                val nr = r + dr
                val nc = c + dc
                if (onBoard(nr, nc) && pos[nr * 8 + nc] == EMPTY) moves += Move(listOf(sq, nr * 8 + nc), emptyList())
            }
        }
        return moves
    }

    /**
     * Extends a capture from [at] ([piece] started on path[0]). Captured pieces stay on the
     * board until the move ends (they cannot be jumped twice); the start square counts as
     * empty. A man that reaches the last row is crowned and stops.
     */
    private fun collectJumps(pos: Position, at: Int, piece: Int, path: List<Int>, captured: List<Int>, out: MutableList<Move>) {
        val me = colorOf(piece)!!
        val r = at / 8
        val c = at % 8
        var extended = false
        if (captured.isEmpty() || !reachesLastRow(piece, at)) {
            for (dr in rowSteps(piece)) for (dc in intArrayOf(-1, 1)) {
                val mr = r + dr
                val mc = c + dc
                val lr = r + 2 * dr
                val lc = c + 2 * dc
                if (!onBoard(lr, lc)) continue
                val over = mr * 8 + mc
                val land = lr * 8 + lc
                val victim = pos[over]
                if (victim == EMPTY || colorOf(victim) == me || over in captured) continue
                if (pos[land] != EMPTY && land != path.first()) continue
                extended = true
                collectJumps(pos, land, piece, path + land, captured + over, out)
            }
        }
        if (!extended && captured.isNotEmpty()) out += Move(path, captured)
    }

    private fun reachesLastRow(piece: Int, sq: Int) =
        (piece == BLACK_MAN && sq / 8 == 0) || (piece == WHITE_MAN && sq / 8 == 7)

    fun play(pos: Position, move: Move): Position {
        val b = pos.board.copyOf()
        var piece = b[move.from]
        b[move.from] = EMPTY
        for (c in move.captures) b[c] = EMPTY
        if (reachesLastRow(piece, move.to)) piece = if (piece == BLACK_MAN) BLACK_KING else WHITE_KING
        b[move.to] = piece
        return Position(b, pos.toMove.other)
    }

    /** True for captures and man moves, which reset the draw counter. */
    fun isProgress(pos: Position, move: Move) = move.isCapture || !isKing(pos[move.from])

    /**
     * Result with [previous] the earlier positions (for repetition) and [quietPlies] the plies
     * since the last capture or man move.
     */
    fun outcome(pos: Position, previous: List<Position>, quietPlies: Int): Outcome {
        if (legalMoves(pos).isEmpty()) return if (pos.toMove == Color.BLACK) Outcome.WHITE_WINS else Outcome.BLACK_WINS
        if (quietPlies >= 80) return Outcome.DRAW
        if (previous.count { it == pos } >= 2) return Outcome.DRAW
        return Outcome.ONGOING
    }

    // ---------------------------------------------------------------- notation

    /** Standard square number 1..32 (Black on 1-12 at the top of the usual diagram). */
    fun number(sq: Int): Int {
        val r = 7 - sq / 8
        val c = 7 - sq % 8
        return r * 4 + c / 2 + 1
    }

    fun squareOf(number: Int): Int? = (0 until 64).firstOrNull { isDark(it) && number(it) == number }

    /** "11-15" for a step, "22x15x8" for a capture. */
    fun notation(move: Move): String =
        move.path.joinToString(if (move.isCapture) "x" else "-") { number(it).toString() }

    fun parse(pos: Position, text: String): Move? {
        val parts = text.split('-', 'x').map { it.toIntOrNull()?.let(::squareOf) ?: return null }
        return legalMoves(pos).firstOrNull { it.path == parts }
    }

    // ---------------------------------------------------------------- engine bitboards

    /** The four bitboards Marcher works on: black men, white men, black kings, white kings. */
    fun bitboards(pos: Position): LongArray {
        val bb = LongArray(4)
        for (sq in 0 until 64) {
            val bit = 1L shl sq
            when (pos[sq]) {
                BLACK_MAN -> bb[0] = bb[0] or bit
                WHITE_MAN -> bb[1] = bb[1] or bit
                BLACK_KING -> bb[2] = bb[2] or bit
                WHITE_KING -> bb[3] = bb[3] or bit
            }
        }
        return bb
    }

    /** Applies one step (a plain move or a single jump) without ending the turn, for the engine loop. */
    fun partial(pos: Position, from: Int, to: Int): Position {
        val b = pos.board.copyOf()
        var piece = b[from]
        b[from] = EMPTY
        if (kotlin.math.abs(to / 8 - from / 8) == 2) b[(from + to) / 2] = EMPTY
        if (reachesLastRow(piece, to)) piece = if (piece == BLACK_MAN) BLACK_KING else WHITE_KING
        b[to] = piece
        return Position(b, pos.toMove)
    }
}
