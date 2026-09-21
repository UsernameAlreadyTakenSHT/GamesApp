package io.github.usernamealreadytakensht.games.game.fox

/**
 * The two fox games, pure Kotlin.
 *
 * Points are indexed 0 until [FoxVariant.points]; each variant maps them to grid squares
 * ([FoxVariant.col] / [FoxVariant.row]) for drawing and to names for the move list.
 *
 * **Fox and Hounds**: the 32 dark squares of an 8x8 board. Four hounds start on the bottom
 * row, the fox on the top row and moves first. Hounds step diagonally forward (up), the fox
 * diagonally in any direction; nothing is captured. The fox wins by reaching the hounds'
 * starting row or when the hounds cannot move; the hounds win by immobilising the fox.
 *
 * **Fox and Geese**: the 33-point cross board with diagonals. Thirteen geese fill the top
 * arm and the top row of the middle band, the fox sits in the centre and moves first. Geese
 * step one point along a line in any direction; the fox steps or jumps over an adjacent
 * goose onto the empty point beyond, chaining jumps. The geese win by immobilising the fox;
 * the fox wins once fewer than six geese are left, too few to trap him.
 */
object Fox {

    const val EMPTY = 0
    const val FOX = 1
    const val HUNTER = 2 // a hound or a goose

    enum class Side { FOX, HUNTERS; val other: Side get() = if (this == FOX) HUNTERS else FOX }

    enum class Outcome { ONGOING, FOX_WINS, HUNTERS_WINS }

    /** A move along [path] (start, then every landing point; jumps capture [captures]). */
    data class Move(val path: List<Int>, val captures: List<Int>) {
        val from: Int get() = path.first()
        val to: Int get() = path.last()
        val isJump: Boolean get() = captures.isNotEmpty()
    }

    class Position(val variant: FoxVariant, val board: IntArray, val toMove: Side) {
        operator fun get(p: Int) = board[p]
        val fox: Int get() = board.indexOf(FOX)
        fun hunters(): Int = board.count { it == HUNTER }

        override fun equals(other: Any?) = other is Position && variant == other.variant &&
            toMove == other.toMove && board.contentEquals(other.board)
        override fun hashCode() = board.contentHashCode() * 31 + toMove.ordinal
    }

    fun start(variant: FoxVariant): Position {
        val board = IntArray(variant.points)
        for (p in variant.hunterStart) board[p] = HUNTER
        board[variant.foxStart] = FOX
        return Position(variant, board, Side.FOX)
    }

    fun legalMoves(pos: Position): List<Move> {
        val v = pos.variant
        val moves = ArrayList<Move>()
        if (pos.toMove == Side.FOX) {
            val fox = pos.fox
            for (d in v.directions.indices) {
                val n = v.step(fox, d)
                if (n >= 0 && pos[n] == EMPTY) moves += Move(listOf(fox, n), emptyList())
            }
            if (v.foxJumps) collectJumps(pos, fox, listOf(fox), emptyList(), moves)
            // Jumping is optional in this rule set, so steps and jumps sit side by side.
        } else {
            for (p in 0 until v.points) {
                if (pos[p] != HUNTER) continue
                for (d in v.directions.indices) {
                    if (!v.hunterMayMove(d)) continue
                    val n = v.step(p, d)
                    if (n >= 0 && pos[n] == EMPTY) moves += Move(listOf(p, n), emptyList())
                }
            }
        }
        return moves
    }

    private fun collectJumps(pos: Position, from: Int, path: List<Int>, captures: List<Int>, out: MutableList<Move>) {
        val v = pos.variant
        var extended = false
        for (d in v.directions.indices) {
            val over = v.step(from, d)
            if (over < 0 || pos[over] != HUNTER || over in captures) continue
            val land = v.step(over, d)
            if (land < 0 || (pos[land] != EMPTY && land != path.first())) continue
            if (land in path) continue
            extended = true
            collectJumps(pos, land, path + land, captures + over, out)
        }
        if (!extended && captures.isNotEmpty()) out += Move(path, captures)
    }

    fun play(pos: Position, move: Move): Position {
        val board = pos.board.copyOf()
        val piece = board[move.from]
        board[move.from] = EMPTY
        for (c in move.captures) board[c] = EMPTY
        board[move.to] = piece
        return Position(pos.variant, board, pos.toMove.other)
    }

    fun outcome(pos: Position): Outcome {
        val v = pos.variant
        if (v.foxGoalRows.isNotEmpty() && v.row(pos.fox) in v.foxGoalRows) return Outcome.FOX_WINS
        if (v.foxJumps && pos.hunters() < v.minHunters) return Outcome.FOX_WINS
        if (legalMoves(pos).isEmpty()) return if (pos.toMove == Side.FOX) Outcome.HUNTERS_WINS else Outcome.FOX_WINS
        return Outcome.ONGOING
    }

    fun notation(v: FoxVariant, move: Move): String =
        move.path.joinToString(if (move.isJump) "x" else "-") { v.name(it) }

    fun parse(v: FoxVariant, pos: Position, text: String): Move? {
        val jump = text.contains('x')
        val names = text.split(if (jump) 'x' else '-')
        val path = names.map { n -> (0 until v.points).firstOrNull { v.name(it) == n } ?: return null }
        return legalMoves(pos).firstOrNull { it.path == path }
    }
}

/** Board geometry and rule switches of one fox game. */
enum class FoxVariant(
    val label: String,
    val points: Int,
    val gridSize: Int,
    val foxJumps: Boolean,
    /** Fox wins when fewer hunters than this remain (jumping variant only). */
    val minHunters: Int,
) {
    HOUNDS("Fox and Hounds", 32, 8, foxJumps = false, minHunters = 0) {
        // Dark squares of the 8x8 board, row by row from the top: (row + col) odd.
        override val cols = IntArray(32) { i -> 2 * (i % 4) + if ((i / 4) % 2 == 0) 1 else 0 }
        override val rows = IntArray(32) { i -> i / 4 }
        override val directions = listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1) // up-left, up-right, down-left, down-right
        override fun hunterMayMove(direction: Int) = direction < 2 // hounds only move up
        override val hunterStart = intArrayOf(28, 29, 30, 31)
        override val foxStart = 1 // top row, second dark square (b8 side)
        override val foxGoalRows = setOf(7)
    },
    GEESE("Fox and Geese", 33, 7, foxJumps = true, minHunters = 6) {
        // The cross: rows 0-1 and 5-6 use columns 2-4, rows 2-4 the full width.
        override val cols: IntArray
        override val rows: IntArray
        init {
            val c = ArrayList<Int>(); val r = ArrayList<Int>()
            for (row in 0 until 7) for (col in 0 until 7) {
                if ((row in 2..4) || (col in 2..4)) { c += col; r += row }
            }
            cols = c.toIntArray(); rows = r.toIntArray()
        }
        override val directions = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0, -1 to -1, 1 to -1, 1 to 1, -1 to 1)
        override fun hunterMayMove(direction: Int) = true
        // Diagonals only exist from points where (row + col) is even (the classic lattice).
        override fun step(point: Int, direction: Int): Int {
            if (direction >= 4 && (rows[point] + cols[point]) % 2 != 0) return -1
            return super.step(point, direction)
        }
        override val hunterStart: IntArray = (0 until 33).filter { rows[it] <= 2 }.toIntArray() // 13 geese
        override val foxStart = (0 until 33).first { rows[it] == 3 && cols[it] == 3 }
        override val foxGoalRows = emptySet<Int>()
    };

    abstract val cols: IntArray
    abstract val rows: IntArray
    /** Unit steps as (dx, dy); indices are what [step] and [hunterMayMove] take. */
    abstract val directions: List<Pair<Int, Int>>
    abstract fun hunterMayMove(direction: Int): Boolean
    abstract val hunterStart: IntArray
    abstract val foxStart: Int
    abstract val foxGoalRows: Set<Int>

    fun col(point: Int) = cols[point]
    fun row(point: Int) = rows[point]

    /** The point one step from [point] in [direction], or -1 off the board. */
    open fun step(point: Int, direction: Int): Int {
        val (dx, dy) = directions[direction]
        val c = cols[point] + dx
        val r = rows[point] + dy
        for (p in 0 until points) if (cols[p] == c && rows[p] == r) return p
        return -1
    }

    /** "a1" style names, a at the left, 1 at the bottom. */
    fun name(point: Int) = "${'a' + cols[point]}${gridSize - rows[point]}"

    val hunterName: String get() = if (this == HOUNDS) "hounds" else "geese"
    val hunterNameSingular: String get() = if (this == HOUNDS) "hound" else "goose"
}
