package io.github.usernamealreadytakensht.games.game.fox

/**
 * The hunt games, pure Kotlin: one or two hunted pieces against a crowd of hunters.
 *
 * Points are indexed 0 until [FoxBoard.points]; the board maps them to grid squares
 * ([FoxBoard.cols] / [FoxBoard.rows]) for drawing and to names for the move list.
 *
 * See [FoxVariant] for the rules of each game.
 */
object Fox {

    const val EMPTY = 0
    const val FOX = 1
    const val HUNTER = 2 // a hound, a goose or a sepoy

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
        val board2 get() = board

        fun foxes(): List<Int> = board.indices.filter { board[it] == FOX }
        fun hunters(): Int = board.count { it == HUNTER }

        override fun equals(other: Any?) = other is Position && variant == other.variant &&
            toMove == other.toMove && board.contentEquals(other.board)
        override fun hashCode() = board.contentHashCode() * 31 + toMove.ordinal
    }

    fun start(variant: FoxVariant): Position {
        val board = IntArray(variant.board.points)
        for (p in variant.hunterStart) board[p] = HUNTER
        for (p in variant.foxStart) board[p] = FOX
        return Position(variant, board, Side.FOX)
    }

    fun legalMoves(pos: Position): List<Move> {
        val v = pos.variant
        val b = v.board
        val moves = ArrayList<Move>()
        if (pos.toMove == Side.FOX) {
            for (fox in pos.foxes()) {
                for (d in b.directions.indices) {
                    val n = b.step(fox, d)
                    if (n >= 0 && pos[n] == EMPTY) moves += Move(listOf(fox, n), emptyList())
                }
                if (v.foxJumps) collectJumps(pos, fox, listOf(fox), emptyList(), moves)
            }
            // Jumping is optional in these rule sets, so steps and jumps sit side by side.
        } else {
            for (p in 0 until b.points) {
                if (pos[p] != HUNTER) continue
                for (d in b.directions.indices) {
                    if (!v.hunterMayMove(b, p, d)) continue
                    val n = b.step(p, d)
                    if (n >= 0 && pos[n] == EMPTY) moves += Move(listOf(p, n), emptyList())
                }
            }
        }
        return moves
    }

    private fun collectJumps(pos: Position, from: Int, path: List<Int>, captures: List<Int>, out: MutableList<Move>) {
        val b = pos.variant.board
        var extended = false
        for (d in b.directions.indices) {
            val over = b.step(from, d)
            if (over < 0 || pos[over] != HUNTER || over in captures) continue
            val land = b.step(over, d)
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
        val b = v.board
        // Fox and Hounds: the fox breaks through to the hounds' back row.
        if (v.foxGoalRows.isNotEmpty() && pos.foxes().any { b.row(it) in v.foxGoalRows }) return Outcome.FOX_WINS
        // Jumping games: too few hunters left to ever trap the fox.
        if (v.minHunters > 0 && pos.hunters() < v.minHunters) return Outcome.FOX_WINS
        // Asalto: the sepoys hold every square of the fortress.
        if (v.fortress.isNotEmpty() && v.fortress.all { pos[it] == HUNTER }) return Outcome.HUNTERS_WINS
        if (legalMoves(pos).isEmpty()) return if (pos.toMove == Side.FOX) Outcome.HUNTERS_WINS else Outcome.FOX_WINS
        return Outcome.ONGOING
    }

    fun notation(v: FoxVariant, move: Move): String =
        move.path.joinToString(if (move.isJump) "x" else "-") { v.board.name(it) }

    fun parse(v: FoxVariant, pos: Position, text: String): Move? {
        val jump = text.contains('x')
        val names = text.split(if (jump) 'x' else '-')
        val path = names.map { n -> (0 until v.board.points).firstOrNull { v.board.name(it) == n } ?: return null }
        return legalMoves(pos).firstOrNull { it.path == path }
    }
}

/** Board geometry shared by the variants: where the points are and how they connect. */
class FoxBoard private constructor(
    val gridSize: Int,
    val cols: IntArray,
    val rows: IntArray,
    /** Unit steps as (dx, dy); the first four are orthogonal, the rest diagonal. */
    val directions: List<Pair<Int, Int>>,
    /** Diagonals exist only from some points on the cross board (the classic lattice). */
    private val diagonalsFrom: (Int) -> Boolean,
) {
    val points: Int get() = cols.size

    fun col(point: Int) = cols[point]
    fun row(point: Int) = rows[point]

    /** The point one step from [point] in [direction], or -1 off the board. */
    fun step(point: Int, direction: Int): Int {
        if (direction >= 4 && !diagonalsFrom(point)) return -1
        val (dx, dy) = directions[direction]
        val c = cols[point] + dx
        val r = rows[point] + dy
        for (p in 0 until points) if (cols[p] == c && rows[p] == r) return p
        return -1
    }

    /** "a1" style names, a at the left, 1 at the bottom. */
    fun name(point: Int) = "${'a' + cols[point]}${gridSize - rows[point]}"

    fun pointAt(col: Int, row: Int): Int = (0 until points).first { cols[it] == col && rows[it] == row }

    companion object {
        /** The 32 dark squares of an 8x8 board, row by row from the top. */
        val CHECKERS: FoxBoard by lazy {
            FoxBoard(
                gridSize = 8,
                cols = IntArray(32) { i -> 2 * (i % 4) + if ((i / 4) % 2 == 0) 1 else 0 },
                rows = IntArray(32) { i -> i / 4 },
                directions = listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1), // the four diagonals, treated as the "orthogonals"
                diagonalsFrom = { true },
            )
        }

        /** The 33-point cross: rows 0-1 and 5-6 hold columns 2-4, rows 2-4 the full width. */
        val CROSS: FoxBoard by lazy {
            val c = ArrayList<Int>()
            val r = ArrayList<Int>()
            for (row in 0 until 7) for (col in 0 until 7) {
                if (row in 2..4 || col in 2..4) { c += col; r += row }
            }
            val cols = c.toIntArray()
            val rows = r.toIntArray()
            FoxBoard(
                gridSize = 7,
                cols = cols,
                rows = rows,
                directions = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0, -1 to -1, 1 to -1, 1 to 1, -1 to 1),
                diagonalsFrom = { p -> (rows[p] + cols[p]) % 2 == 0 },
            )
        }
    }
}

/** The hunt games offered, in display order. */
enum class FoxVariant(
    val label: String,
    val tagline: String,
    val foxJumps: Boolean,
    /** The fox wins once fewer hunters than this remain (0 = no such rule). */
    val minHunters: Int,
    val description: String,
) {
    HOUNDS(
        "Fox and Hounds", "1 vs 4, no captures", foxJumps = false, minHunters = 0,
        description = "8x8 board, one fox against four hounds. The hounds only move diagonally forward; nothing is ever captured. The fox wins by slipping past them to their back row, the hounds by boxing it in.",
    ),
    GEESE(
        "Fox and Geese", "1 vs 13", foxJumps = true, minHunters = 6,
        description = "Cross board, one fox against thirteen geese. The geese step one point in any direction, the fox steps or jumps geese in chains. The geese win by cornering it, the fox once fewer than six geese remain.",
    ),
    GEESE_17(
        "Fox and Geese (17)", "1 vs 17", foxJumps = true, minHunters = 6,
        description = "The older, harder setting: seventeen geese instead of thirteen, so the fox has far less room. Same rules otherwise.",
    ),
    TWO_FOXES(
        "Two Foxes", "2 vs 17", foxJumps = true, minHunters = 6,
        description = "Two foxes hunt together against seventeen geese, and either of them may jump. The geese must trap both: the foxes lose only when neither can move.",
    ),
    ASALTO(
        "Asalto", "2 officers vs 24 sepoys", foxJumps = true, minHunters = 9,
        description = "The assault on the fortress: two officers defend the nine points of the top arm against twenty-four sepoys, who may only advance or move sideways. The sepoys win by filling the fortress or blocking both officers; the officers by taking so many sepoys that the fortress can no longer be filled.",
    );

    val board: FoxBoard get() = if (this == HOUNDS) FoxBoard.CHECKERS else FoxBoard.CROSS

    /** Fox-side rows that win the game on arrival (Fox and Hounds only). */
    val foxGoalRows: Set<Int> get() = if (this == HOUNDS) setOf(7) else emptySet()

    /** Points that the hunters must all occupy to win (Asalto only). */
    val fortress: IntArray by lazy {
        if (this != ASALTO) IntArray(0)
        else (0 until board.points).filter { board.row(it) <= 2 && board.col(it) in 2..4 }.toIntArray()
    }

    val foxStart: IntArray by lazy {
        val b = board
        when (this) {
            HOUNDS -> intArrayOf(1) // top row, second dark square
            GEESE, GEESE_17 -> intArrayOf(b.pointAt(3, 3)) // the centre
            TWO_FOXES -> intArrayOf(b.pointAt(3, 3), b.pointAt(3, 4))
            ASALTO -> intArrayOf(b.pointAt(2, 2), b.pointAt(4, 2)) // the fortress' lower corners
        }
    }

    val hunterStart: IntArray by lazy {
        val b = board
        when (this) {
            HOUNDS -> intArrayOf(28, 29, 30, 31) // the bottom row
            GEESE -> (0 until b.points).filter { b.row(it) <= 2 }.toIntArray() // 13
            // 17: the top three rows plus the four outer points of the middle row.
            GEESE_17, TWO_FOXES -> (0 until b.points)
                .filter { b.row(it) <= 2 || (b.row(it) == 3 && b.col(it) !in 2..4) }
                .filter { it !in foxStart.toSet() }
                .toIntArray()
            // Every point outside the fortress.
            ASALTO -> (0 until b.points).filter { it !in fortress.toSet() }.toIntArray()
        }
    }

    /** May a hunter on [point] step in [direction]? Hounds and sepoys may not go backwards. */
    fun hunterMayMove(b: FoxBoard, point: Int, direction: Int): Boolean = when (this) {
        HOUNDS -> direction < 2 // the two upward diagonals
        ASALTO -> b.directions[direction].second <= 0 // forward or sideways, never back
        else -> true
    }

    val hunterName: String get() = when (this) {
        HOUNDS -> "hounds"
        ASALTO -> "sepoys"
        else -> "geese"
    }

    val foxName: String get() = when (this) {
        ASALTO -> "officers"
        TWO_FOXES -> "foxes"
        else -> "fox"
    }
}
