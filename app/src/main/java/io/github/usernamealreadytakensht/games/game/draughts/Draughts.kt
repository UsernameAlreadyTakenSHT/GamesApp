package io.github.usernamealreadytakensht.games.game.draughts

/**
 * International draughts (10x10, FMJD rules), pure Kotlin.
 *
 * Squares use the standard numbering 1..50: square 1 is the top-left dark square (black's
 * side), numbers run left to right, top to bottom; white starts on 31..50 and moves "up"
 * (towards square 1). A square (row, col) with rows/cols 0..9 from the top-left corner is
 * playable when `(row + col)` is odd.
 *
 * Rules implemented: men move one step diagonally forward and capture forwards and
 * backwards; kings fly; capturing is mandatory and the sequence taking the most pieces must
 * be chosen (any of the maximal ones); captured pieces stay on the board until the sequence
 * ends and cannot be jumped twice (Turkish strike); a man promotes only when its move ends
 * on the last row. A side with no legal move loses. Draw: threefold repetition, or 25
 * consecutive moves by each side without a capture or a man move.
 */
object Draughts {

    const val EMPTY = 0
    const val WHITE_MAN = 1
    const val WHITE_KING = 2
    const val BLACK_MAN = 3
    const val BLACK_KING = 4

    enum class Color { WHITE, BLACK; val other get() = if (this == WHITE) BLACK else WHITE }

    fun isWhite(p: Int) = p == WHITE_MAN || p == WHITE_KING
    fun isBlack(p: Int) = p == BLACK_MAN || p == BLACK_KING
    fun isKing(p: Int) = p == WHITE_KING || p == BLACK_KING
    fun colorOf(p: Int): Color? = if (isWhite(p)) Color.WHITE else if (isBlack(p)) Color.BLACK else null

    // ---------------------------------------------------------------- geometry

    fun rowOf(sq: Int) = (sq - 1) / 5
    fun colOf(sq: Int) = 2 * ((sq - 1) % 5) + if (rowOf(sq) % 2 == 0) 1 else 0

    /** Square number of (row, col), or 0 when the square is not playable / off board. */
    fun squareAt(row: Int, col: Int): Int {
        if (row !in 0..9 || col !in 0..9 || (row + col) % 2 == 0) return 0
        return row * 5 + col / 2 + 1
    }

    /** Directions: 0 = up-left, 1 = up-right, 2 = down-left, 3 = down-right ("up" = towards row 0). */
    private val DR = intArrayOf(-1, -1, 1, 1)
    private val DC = intArrayOf(-1, 1, -1, 1)

    /** neighbour[sq][dir] = adjacent square in that direction, or 0. */
    private val neighbour: Array<IntArray> = Array(51) { sq ->
        IntArray(4) { d -> if (sq == 0) 0 else squareAt(rowOf(sq) + DR[d], colOf(sq) + DC[d]) }
    }

    fun neighbourOf(sq: Int, dir: Int) = neighbour[sq][dir]

    // ---------------------------------------------------------------- position

    /** Immutable position: `squares[1..50]` hold piece codes; index 0 is unused. */
    class Position(val squares: ByteArray, val toMove: Color) {
        operator fun get(sq: Int): Int = squares[sq].toInt()

        fun count(color: Color): Int = (1..50).count { colorOf(this[it]) == color }

        /** Hub protocol encoding: side to move + 50 piece characters. */
        fun toHub(): String {
            val sb = StringBuilder(51)
            sb.append(if (toMove == Color.WHITE) 'W' else 'B')
            for (sq in 1..50) sb.append(
                when (this[sq]) {
                    WHITE_MAN -> 'w'; WHITE_KING -> 'W'; BLACK_MAN -> 'b'; BLACK_KING -> 'B'; else -> 'e'
                },
            )
            return sb.toString()
        }

        /** Key for repetition detection. */
        fun key(): String = toHub()

        override fun equals(other: Any?) = other is Position && toMove == other.toMove && squares.contentEquals(other.squares)
        override fun hashCode() = squares.contentHashCode() * 31 + toMove.ordinal

        companion object {
            fun fromHub(s: String): Position {
                require(s.length == 51) { "hub position must have 51 characters" }
                val sq = ByteArray(51)
                for (i in 1..50) sq[i] = when (s[i]) {
                    'w' -> WHITE_MAN; 'W' -> WHITE_KING; 'b' -> BLACK_MAN; 'B' -> BLACK_KING; else -> EMPTY
                }.toByte()
                return Position(sq, if (s[0] == 'W') Color.WHITE else Color.BLACK)
            }
        }
    }

    val START: Position = ByteArray(51).let { sq ->
        for (i in 1..20) sq[i] = BLACK_MAN.toByte()
        for (i in 31..50) sq[i] = WHITE_MAN.toByte()
        Position(sq, Color.WHITE)
    }

    // ---------------------------------------------------------------- moves

    /**
     * A move. [path] lists every square the piece lands on after [from] (a single square for
     * quiet moves). [captured] lists the jumped pieces in capture order. Two captures with the
     * same from, to and captured set are the same move even if their paths differ.
     */
    class Move(val from: Int, val path: List<Int>, val captured: List<Int>) {
        val to: Int get() = path.last()
        val isCapture: Boolean get() = captured.isNotEmpty()

        /** Identity used by the rules and the engines: from, to and the set of captured pieces. */
        val key: String = "$from-$to-${captured.sorted().joinToString(",")}"

        /** Hub / PDN-style notation: "32-28" or "28x19x23" (captured squares in any order). */
        fun toHub(): String =
            if (!isCapture) "$from-$to" else (listOf(from, to) + captured).joinToString("x")

        override fun equals(other: Any?) = other is Move && key == other.key
        override fun hashCode() = key.hashCode()
        override fun toString() = toHub()
    }

    fun legalMoves(pos: Position): List<Move> {
        val captures = captureMoves(pos)
        if (captures.isNotEmpty()) return captures
        return quietMoves(pos)
    }

    private fun quietMoves(pos: Position): List<Move> {
        val out = ArrayList<Move>()
        val color = pos.toMove
        for (sq in 1..50) {
            val p = pos[sq]
            if (colorOf(p) != color) continue
            if (isKing(p)) {
                for (d in 0 until 4) {
                    var t = neighbour[sq][d]
                    while (t != 0 && pos[t] == EMPTY) {
                        out += Move(sq, listOf(t), emptyList())
                        t = neighbour[t][d]
                    }
                }
            } else {
                val dirs = if (color == Color.WHITE) intArrayOf(0, 1) else intArrayOf(2, 3)
                for (d in dirs) {
                    val t = neighbour[sq][d]
                    if (t != 0 && pos[t] == EMPTY) out += Move(sq, listOf(t), emptyList())
                }
            }
        }
        return out
    }

    private fun captureMoves(pos: Position): List<Move> {
        val color = pos.toMove
        val found = LinkedHashMap<String, Move>()
        var best = 0
        val board = pos.squares.copyOf()
        val captured = ArrayList<Int>(8)
        val path = ArrayList<Int>(8)

        fun record(from: Int) {
            if (captured.isEmpty()) return
            val n = captured.size
            if (n < best) return
            if (n > best) { best = n; found.clear() }
            val m = Move(from, path.toList(), captured.toList())
            found.putIfAbsent(m.key, m)
        }

        fun isEnemy(sq: Int) = colorOf(board[sq].toInt()) == color.other

        // Depth-first over capture continuations. The moving piece has been lifted from the
        // board (its origin is empty) so it may cross or land on its own starting square.
        fun searchMan(from: Int, at: Int) {
            var extended = false
            for (d in 0 until 4) {
                val over = neighbour[at][d]
                if (over == 0 || !isEnemy(over) || over in captured) continue
                val land = neighbour[over][d]
                if (land == 0 || board[land].toInt() != EMPTY) continue
                extended = true
                captured += over; path += land
                searchMan(from, land)
                captured.removeAt(captured.size - 1); path.removeAt(path.size - 1)
            }
            if (!extended) record(from)
        }

        fun searchKing(from: Int, at: Int) {
            var extended = false
            for (d in 0 until 4) {
                var over = neighbour[at][d]
                while (over != 0 && board[over].toInt() == EMPTY) over = neighbour[over][d]
                if (over == 0 || !isEnemy(over) || over in captured) continue
                var land = neighbour[over][d]
                while (land != 0 && board[land].toInt() == EMPTY) {
                    extended = true
                    captured += over; path += land
                    searchKing(from, land)
                    captured.removeAt(captured.size - 1); path.removeAt(path.size - 1)
                    land = neighbour[land][d]
                }
            }
            if (!extended) record(from)
        }

        for (sq in 1..50) {
            val p = board[sq].toInt()
            if (colorOf(p) != color) continue
            board[sq] = EMPTY.toByte()
            captured.clear(); path.clear()
            if (isKing(p)) searchKing(sq, sq) else searchMan(sq, sq)
            board[sq] = p.toByte()
        }
        return found.values.toList()
    }

    /** Applies [move] (assumed legal) and returns the new position. */
    fun play(pos: Position, move: Move): Position {
        val sq = pos.squares.copyOf()
        var p = sq[move.from].toInt()
        sq[move.from] = EMPTY.toByte()
        for (c in move.captured) sq[c] = EMPTY.toByte()
        val to = move.to
        if (p == WHITE_MAN && rowOf(to) == 0) p = WHITE_KING
        if (p == BLACK_MAN && rowOf(to) == 9) p = BLACK_KING
        sq[to] = p.toByte()
        return Position(sq, pos.toMove.other)
    }

    /** Finds the legal move matching Hub notation ("32-28", "28x19x23", also "28x23"). */
    fun parseHub(pos: Position, text: String): Move? {
        val parts = text.trim().split('x', '-', ':').mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return null
        val from = parts[0]
        val to = parts[1]
        val caps = parts.drop(2).sorted()
        val candidates = legalMoves(pos).filter { it.from == from && it.to == to }
        return candidates.firstOrNull { caps.isEmpty() || it.captured.sorted() == caps } ?: candidates.singleOrNull()
    }

    // ---------------------------------------------------------------- game state

    enum class Outcome { ONGOING, WHITE_WINS, BLACK_WINS, DRAW }

    /**
     * Result of a game given its position history. [quietKingMoves] counts consecutive plies
     * without a capture or a man move.
     */
    fun outcome(pos: Position, history: List<Position>, quietKingMoves: Int): Outcome {
        if (legalMoves(pos).isEmpty()) return if (pos.toMove == Color.WHITE) Outcome.BLACK_WINS else Outcome.WHITE_WINS
        if (quietKingMoves >= 50) return Outcome.DRAW
        val key = pos.key()
        if (history.count { it.key() == key } >= 3) return Outcome.DRAW
        return Outcome.ONGOING
    }

    /** Whether [move] resets the no-progress counter (a capture or a man move). */
    fun isProgress(pos: Position, move: Move): Boolean = move.isCapture || !isKing(pos[move.from])

    /** Number of leaf nodes at [depth] (distinct moves per the [Move.key] identity). */
    fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1
        val moves = legalMoves(pos)
        if (depth == 1) return moves.size.toLong()
        var n = 0L
        for (m in moves) n += perft(play(pos, m), depth - 1)
        return n
    }
}
