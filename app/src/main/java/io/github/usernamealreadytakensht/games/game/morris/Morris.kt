package io.github.usernamealreadytakensht.games.game.morris

/**
 * Nine Men's Morris rules, pure Kotlin.
 *
 * Points are numbered 0..23 over the three concentric squares (see [POINT_ROW] / [POINT_COL]
 * for their place on the 7x7 grid, and [name] for the a1..g7 coordinate notation):
 *
 * ```
 *  0-----------1-----------2      row 0
 *  |           |           |
 *  |   3-------4-------5   |      row 1
 *  |   |       |       |   |
 *  |   |   6---7---8   |   |      row 2
 *  |   |   |       |   |   |
 *  9---10--11      12--13--14     row 3
 *  |   |   |       |   |   |
 *  |   |   15--16--17  |   |      row 4
 *  |   |       |       |   |
 *  |   18------19------20  |      row 5
 *  |           |           |
 *  21----------22----------23     row 6
 * ```
 *
 * Standard rules: each side places nine men, then slides them along the lines; a side down
 * to three men may fly anywhere. Closing a mill removes an enemy man that is not in a mill
 * (any man when they all are). A side with fewer than three men, or with no legal move,
 * loses. Threefold repetition or fifty moves per side without a mill is a draw.
 */
object Morris {

    const val EMPTY = 0
    const val WHITE = 1
    const val BLACK = 2


    enum class Color { WHITE, BLACK; val other: Color get() = if (this == WHITE) BLACK else WHITE }

    enum class Outcome { ONGOING, WHITE_WINS, BLACK_WINS, DRAW }

    /** Grid row / column (0..6) of each point, for drawing and for the coordinate names. */
    val POINT_ROW = intArrayOf(0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6)
    val POINT_COL = intArrayOf(0, 3, 6, 1, 3, 5, 2, 3, 4, 0, 1, 2, 4, 5, 6, 2, 3, 4, 1, 3, 5, 0, 3, 6)

    /** The sixteen lines along which mills form (and along which men move). */
    val LINES: Array<IntArray> = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
        intArrayOf(9, 10, 11), intArrayOf(12, 13, 14),
        intArrayOf(15, 16, 17), intArrayOf(18, 19, 20), intArrayOf(21, 22, 23),
        intArrayOf(0, 9, 21), intArrayOf(3, 10, 18), intArrayOf(6, 11, 15),
        intArrayOf(1, 4, 7), intArrayOf(16, 19, 22),
        intArrayOf(8, 12, 17), intArrayOf(5, 13, 20), intArrayOf(2, 14, 23),
    )

    /** Lines through each point (always two). */
    val LINES_OF: Array<IntArray> = Array(24) { p -> LINES.indices.filter { p in LINES[it] }.toIntArray() }

    /** Neighbours of each point along the lines. */
    val ADJACENT: Array<IntArray> = Array(24) { p ->
        val n = ArrayList<Int>()
        for (line in LINES) {
            val i = line.indexOf(p)
            if (i < 0) continue
            if (i > 0) n += line[i - 1]
            if (i < 2) n += line[i + 1]
        }
        n.toIntArray()
    }

    /** Coordinate name of a point, a1 bottom-left to g7 top-right. */
    fun name(point: Int): String = "${'a' + POINT_COL[point]}${7 - POINT_ROW[point]}"

    fun pointOf(name: String): Int? {
        if (name.length != 2) return null
        val col = name[0] - 'a'
        val row = 7 - (name[1] - '0')
        return (0 until 24).firstOrNull { POINT_ROW[it] == row && POINT_COL[it] == col }
    }

    /**
     * A ply: place a man on [to] ([from] = -1) or slide/fly one from [from] to [to], then
     * remove the enemy man on [remove] (-1 when no mill was closed).
     */
    data class Move(val from: Int, val to: Int, val remove: Int) {
        val isPlacement: Boolean get() = from < 0
        val closesMill: Boolean get() = remove >= 0

        /** "d7", "a1-d1", with "xg7" appended when a man is removed. */
        fun toNotation(): String = buildString {
            if (from >= 0) append(name(from)).append('-')
            append(name(to))
            if (remove >= 0) append('x').append(name(remove))
        }

        companion object {
            fun parse(text: String): Move? {
                val x = text.indexOf('x')
                val main = if (x >= 0) text.substring(0, x) else text
                val remove = if (x >= 0) pointOf(text.substring(x + 1)) ?: return null else -1
                val dash = main.indexOf('-')
                val from = if (dash >= 0) pointOf(main.substring(0, dash)) ?: return null else -1
                val to = pointOf(if (dash >= 0) main.substring(dash + 1) else main) ?: return null
                return Move(from, to, remove)
            }
        }
    }

    /** Immutable position: rules, board, side to move and men still to be placed. */
    class Position(
        val variant: MorrisVariant,
        val board: IntArray,
        val toMove: Color,
        val whiteInHand: Int,
        val blackInHand: Int,
    ) {
        operator fun get(point: Int): Int = board[point]

        fun inHand(c: Color) = if (c == Color.WHITE) whiteInHand else blackInHand
        fun onBoard(c: Color): Int = board.count { it == stone(c) }
        fun men(c: Color) = inHand(c) + onBoard(c)

        /** Men still in hand. In Lasker Morris a side may also slide while placing. */
        fun isPlacing(c: Color) = inHand(c) > 0
        fun mayPlace(c: Color) = inHand(c) > 0
        fun mayMove(c: Color) = inHand(c) == 0 || variant.mayMoveInPlacingPhase
        fun isFlying(c: Color) = inHand(c) == 0 && onBoard(c) == 3

        override fun equals(other: Any?): Boolean = other is Position && variant == other.variant &&
            board.contentEquals(other.board) && toMove == other.toMove &&
            whiteInHand == other.whiteInHand && blackInHand == other.blackInHand

        override fun hashCode(): Int = (board.contentHashCode() * 31 + toMove.ordinal) * 31 + whiteInHand * 10 + blackInHand
    }

    fun start(variant: MorrisVariant) =
        Position(variant, IntArray(24), Color.WHITE, variant.menPerSide, variant.menPerSide)

    fun stone(c: Color) = if (c == Color.WHITE) WHITE else BLACK
    fun colorOf(stone: Int): Color? = when (stone) { WHITE -> Color.WHITE; BLACK -> Color.BLACK; else -> null }

    /** True when [point] belongs to a completed mill of [stone] on [board]. */
    fun inMill(board: IntArray, point: Int, stone: Int): Boolean {
        for (li in LINES_OF[point]) {
            val l = LINES[li]
            if (board[l[0]] == stone && board[l[1]] == stone && board[l[2]] == stone) return true
        }
        return false
    }

    /** Would a man of [stone] arriving on [to] (after leaving [from]) close a mill? */
    private fun closesMill(board: IntArray, from: Int, to: Int, stone: Int): Boolean {
        for (li in LINES_OF[to]) {
            val l = LINES[li]
            var ok = true
            for (p in l) {
                if (p == to) continue
                if (p == from || board[p] != stone) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    /** Enemy men that may be removed after closing a mill: those outside mills, else all. */
    fun removable(board: IntArray, enemy: Int): List<Int> {
        val free = ArrayList<Int>()
        val all = ArrayList<Int>()
        for (p in 0 until 24) {
            if (board[p] != enemy) continue
            all += p
            if (!inMill(board, p, enemy)) free += p
        }
        return if (free.isEmpty()) all else free
    }

    fun legalMoves(pos: Position): List<Move> {
        val me = pos.toMove
        val stone = stone(me)
        val enemy = stone(me.other)
        val board = pos.board
        val moves = ArrayList<Move>()

        fun add(from: Int, to: Int) {
            if (closesMill(board, from, to, stone)) {
                for (r in removable(board, enemy)) moves += Move(from, to, r)
            } else {
                moves += Move(from, to, -1)
            }
        }

        if (pos.mayPlace(me)) {
            for (to in 0 until 24) if (board[to] == EMPTY) add(-1, to)
        }
        if (pos.mayMove(me)) {
            val flying = pos.isFlying(me)
            for (from in 0 until 24) {
                if (board[from] != stone) continue
                val destinations = if (flying) (0 until 24) else ADJACENT[from].asIterable()
                for (to in destinations) if (board[to] == EMPTY) add(from, to)
            }
        }
        return moves
    }

    fun play(pos: Position, move: Move): Position {
        val board = pos.board.copyOf()
        val me = pos.toMove
        if (move.from >= 0) board[move.from] = EMPTY
        board[move.to] = stone(me)
        if (move.remove >= 0) board[move.remove] = EMPTY
        return Position(
            pos.variant,
            board,
            me.other,
            if (me == Color.WHITE && move.isPlacement) pos.whiteInHand - 1 else pos.whiteInHand,
            if (me == Color.BLACK && move.isPlacement) pos.blackInHand - 1 else pos.blackInHand,
        )
    }

    /** True when the ply resets the draw counter: placements and mills. */
    fun isProgress(move: Move): Boolean = move.isPlacement || move.closesMill

    /**
     * Result with the side to move having [legal] moves. [previous] are the earlier positions
     * (for repetition) and [quietPlies] the plies since the last placement or mill.
     */
    fun outcome(pos: Position, previous: List<Position>, quietPlies: Int, legal: List<Move> = legalMoves(pos)): Outcome {
        val me = pos.toMove
        // Fewer than three men once placement is over: the placing side just lost a man.
        for (c in Color.entries) {
            if (pos.inHand(c) == 0 && pos.onBoard(c) < 3) return if (c == Color.WHITE) Outcome.BLACK_WINS else Outcome.WHITE_WINS
        }
        if (legal.isEmpty()) return if (me == Color.WHITE) Outcome.BLACK_WINS else Outcome.WHITE_WINS
        if (quietPlies >= 100) return Outcome.DRAW
        if (previous.count { it == pos } >= 2) return Outcome.DRAW
        return Outcome.ONGOING
    }
}

/** Rule sets offered for the mill games, in display order. */
enum class MorrisVariant(
    val label: String,
    val menPerSide: Int,
    val mayMoveInPlacingPhase: Boolean,
    val tagline: String,
    val description: String,
) {
    STANDARD(
        "Nine Men's Morris", 9, false, "The classic",
        "Nine men each: place all nine, then slide along the lines, and fly when down to three.",
    ),
    LASKER(
        "Lasker Morris", 10, true, "Place or slide",
        "Emanuel Lasker's version: ten men each, and on every turn you may either place a man from your hand or slide one already on the board. Fewer forced placements, sharper play.",
    );
}
