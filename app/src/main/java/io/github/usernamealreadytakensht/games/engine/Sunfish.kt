package io.github.usernamealreadytakensht.games.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of Sunfish ("sunfish 2026", github.com/thomasahle/sunfish, GPL-3.0),
 * Thomas Dybdahl Ahle's minimalist chess engine.
 *
 * The port follows the Python source line by line: 120-character padded board, the side
 * to move always plays "up" (the board is rotated after every move), piece-square-table
 * evaluation, MTD-bi driver over a fail-soft `bound()` with null move, LMR and futility.
 * Comments explaining the algorithm live in the original; only port-specific notes are kept.
 */
object Sunfish {

    // ---------------------------------------------------------------- tables

    private val PIECE = mapOf('P' to 100, 'N' to 280, 'B' to 320, 'R' to 479, 'Q' to 929, 'K' to 60000)

    private val RAW_PST: Map<Char, IntArray> = mapOf(
        'P' to intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            78, 83, 86, 73, 102, 82, 85, 90,
            7, 29, 21, 44, 40, 31, 44, 7,
            -17, 16, -2, 15, 14, 0, 15, -13,
            -26, 3, 10, 9, 6, 1, 0, -23,
            -22, 9, 5, -11, -10, -2, 3, -19,
            -31, 8, -7, -37, -36, -14, 3, -31,
            0, 0, 0, 0, 0, 0, 0, 0,
        ),
        'N' to intArrayOf(
            -66, -53, -75, -75, -10, -55, -58, -70,
            -3, -6, 100, -36, 4, 62, -4, -14,
            10, 67, 1, 74, 73, 27, 62, -2,
            24, 24, 45, 37, 33, 41, 25, 17,
            -1, 5, 31, 21, 22, 35, 2, 0,
            -18, 10, 13, 22, 18, 15, 11, -14,
            -23, -15, 2, 0, 2, 0, -23, -20,
            -74, -23, -26, -24, -19, -35, -22, -69,
        ),
        'B' to intArrayOf(
            -59, -78, -82, -76, -23, -107, -37, -50,
            -11, 20, 35, -42, -39, 31, 2, -22,
            -9, 39, -32, 41, 52, -10, 28, -14,
            25, 17, 20, 34, 26, 25, 15, 10,
            13, 10, 17, 23, 17, 16, 0, 7,
            14, 25, 24, 15, 8, 25, 20, 15,
            19, 20, 11, 6, 7, 6, 20, 16,
            -7, 2, -15, -12, -14, -15, -10, -10,
        ),
        'R' to intArrayOf(
            35, 29, 33, 4, 37, 33, 56, 50,
            55, 29, 56, 67, 55, 62, 34, 60,
            19, 35, 28, 33, 45, 27, 25, 15,
            0, 5, 16, 13, 18, -4, -9, -6,
            -28, -35, -16, -21, -13, -29, -46, -30,
            -42, -28, -42, -25, -25, -35, -26, -46,
            -53, -38, -31, -26, -29, -43, -44, -53,
            -30, -24, -18, 5, -2, -18, -31, -32,
        ),
        'Q' to intArrayOf(
            6, 1, -8, -104, 69, 24, 88, 26,
            14, 32, 60, -10, 20, 76, 57, 24,
            -2, 43, 32, 60, 72, 63, 43, 2,
            1, -16, 22, 17, 25, 20, -13, -6,
            -14, -15, -2, -5, -1, -10, -20, -22,
            -30, -6, -13, -11, -16, -11, -16, -27,
            -36, -18, 0, -19, -15, -15, -21, -38,
            -39, -30, -31, -13, -31, -36, -34, -42,
        ),
        'K' to intArrayOf(
            4, 54, 47, -99, -99, 60, 83, -62,
            -32, 10, 55, 56, 56, 55, 10, 3,
            -62, 12, -57, 44, -67, 28, 37, -31,
            -55, 50, 11, -4, -19, 13, 0, -49,
            -55, -43, -52, -28, -51, -47, -8, -50,
            -47, -42, -43, -79, -64, -32, -29, -32,
            -4, 3, -14, -50, -57, -18, 13, 4,
            17, 30, -3, -14, 6, -1, 40, 18,
        ),
    )

    /** Padded 120-entry tables including the piece value; index by piece char. */
    private val PST: Map<Char, IntArray> = RAW_PST.mapValues { (k, table) ->
        IntArray(120).also { out ->
            for (r in 0 until 8) for (f in 0 until 8) out[20 + r * 10 + 1 + f] = table[r * 8 + f] + PIECE.getValue(k)
        }
    }
    private val K_MID: IntArray = PST.getValue('K')
    private val K_END: IntArray = IntArray(120) { i ->
        PIECE.getValue('K') + 70 - 10 * (abs(2 * (i / 10) - 11) + abs(2 * (i % 10) - 9))
    }

    /** The king table in use for the current search (mid-game or end-game). */
    private var pstK: IntArray = K_MID
    private fun pst(p: Char): IntArray = if (p == 'K') pstK else PST.getValue(p)

    // ---------------------------------------------------------------- constants

    const val A1 = 91
    const val H1 = 98
    const val A8 = 21
    const val H8 = 28

    const val INITIAL =
        "         \n" + "         \n" + " rnbqkbnr\n" + " pppppppp\n" + " ........\n" + " ........\n" +
            " ........\n" + " ........\n" + " PPPPPPPP\n" + " RNBQKBNR\n" + "         \n" + "         \n"

    private const val N = -10
    private const val E = 1
    private const val S = 10
    private const val W = -1
    private val DIRECTIONS = mapOf(
        'P' to intArrayOf(N, N + N, N + W, N + E),
        'N' to intArrayOf(N + N + E, E + N + E, E + S + E, S + S + E, S + S + W, W + S + W, W + N + W, N + N + W),
        'B' to intArrayOf(N + E, S + E, S + W, N + W),
        'R' to intArrayOf(N, E, S, W),
        'Q' to intArrayOf(N, E, S, W, N + E, S + E, S + W, N + W),
        'K' to intArrayOf(N, E, S, W, N + E, S + E, S + W, N + W),
    )

    private val MATE_LOWER = PIECE.getValue('K') - 13 * PIECE.getValue('Q')
    private val MATE_UPPER = PIECE.getValue('K') + 10 * PIECE.getValue('Q')

    private const val QS = 36
    private const val QS_A = 180
    private const val LMR = 70
    private const val EVAL_ROUGHNESS = 15
    private const val NULL_MARGIN = -200

    /** Smaller than the Python default (10^6): a phone heap cannot hold a million positions. */
    private const val TABLE_SIZE = 100_000

    // ---------------------------------------------------------------- chess logic

    /** `prom` is the promotion piece (upper case) or NO_PROM. */
    data class Move(val i: Int, val j: Int, val prom: Char) {
        companion object { const val NO_PROM = '\u0000' }
    }

    data class Position(
        val board: String,
        val score: Int,
        val wc0: Boolean, val wc1: Boolean,
        val bc0: Boolean, val bc1: Boolean,
        val ep: Int,
        val kp: Int,
    ) {
        fun genMoves(): List<Move> {
            val out = ArrayList<Move>(48)
            for (i in 0 until 120) {
                val p = board[i]
                if (p !in "PNBRQK") continue
                for (d in DIRECTIONS.getValue(p)) {
                    var j = i + d
                    while (true) {
                        val q = board[j]
                        if (q in " \nPNBRQK") break
                        if (p == 'P') {
                            if ((d == N || d == N + N) && q != '.') break
                            if (d == N + N && (i < A1 + N || board[i + N] != '.')) break
                            if ((d == N + W || d == N + E) && q == '.' && j != ep && abs(j - kp) > 1) break
                            if (j in A8..H8) {
                                for (prom in "NBRQ") out += Move(i, j, prom)
                                break
                            }
                        }
                        out += Move(i, j, Move.NO_PROM)
                        if (p in "PNK" || q in "pnbrqk") break
                        if (i == A1 && board[j + E] == 'K' && wc0) out += Move(j + E, j + W, Move.NO_PROM)
                        if (i == H1 && board[j + W] == 'K' && wc1) out += Move(j + W, j + E, Move.NO_PROM)
                        j += d
                    }
                }
            }
            return out
        }

        fun rotate(nullmove: Boolean = false): Position = Position(
            board.reversed().swapCase(), -score, bc0, bc1, wc0, wc1,
            if (ep != 0 && !nullmove) 119 - ep else 0,
            if (kp != 0 && !nullmove) 119 - kp else 0,
        )

        fun move(m: Move): Position {
            val (i, j, prom) = m
            val p = board[i]
            val b = board.toCharArray()
            var wc0 = this.wc0; var wc1 = this.wc1
            var bc0 = this.bc0; var bc1 = this.bc1
            var ep = 0; var kp = 0
            val score = this.score + value(m)
            b[j] = b[i]
            b[i] = '.'
            wc0 = wc0 && i != A1; wc1 = wc1 && i != H1
            bc0 = bc0 && j != H8; bc1 = bc1 && j != A8
            if (p == 'K') {
                wc0 = false; wc1 = false
                if (abs(j - i) == 2) {
                    kp = (i + j) / 2
                    b[if (j < i) A1 else H1] = '.'
                    b[kp] = 'R'
                }
            }
            if (p == 'P') {
                if (j in A8..H8) b[j] = prom
                if (j - i == 2 * N) ep = i + N
                if (j == this.ep) b[j + S] = '.'
            }
            return Position(String(b), score, wc0, wc1, bc0, bc1, ep, kp).rotate()
        }

        fun value(m: Move): Int {
            val (i, j, prom) = m
            val p = board[i]
            val q = board[j]
            var score = pst(p)[j] - pst(p)[i]
            if (q in "pnbrqk") score += pst(q.uppercaseChar())[119 - j]
            if (abs(j - kp) < 2) score += pst('K')[119 - j]
            if (p == 'K' && abs(i - j) == 2) {
                score += pst('R')[(i + j) / 2]
                score -= pst('R')[if (j < i) A1 else H1]
            }
            if (p == 'P') {
                if (j in A8..H8) score += pst(prom)[j] - pst('P')[j]
                if (j == ep) score += pst('P')[119 - (j + S)]
            }
            return score
        }

        /** The move capturing the opponent king (or its castling path), if any. */
        fun kingCapture(): Move? = genMoves().firstOrNull { board[it.j] == 'k' || abs(it.j - kp) < 2 }
    }

    private fun String.swapCase(): String {
        val out = CharArray(length)
        for (k in indices) {
            val c = this[k]
            out[k] = if (c.isUpperCase()) c.lowercaseChar() else if (c.isLowerCase()) c.uppercaseChar() else c
        }
        return String(out)
    }

    // ---------------------------------------------------------------- search

    class Stop : RuntimeException()

    private data class Entry(val lower: Int, val upper: Int)

    /** One (depth, gamma, score, move) step of the iterative-deepening driver. */
    data class Step(val depth: Int, val gamma: Int, val score: Int, val move: Move?)

    class Searcher {
        private val tpScore = LinkedHashMap<Pair<Position, Int>, Entry>()
        private val tpMove = LinkedHashMap<Position, Move>()
        private var history: Set<Position> = emptySet()
        private lateinit var root: Position

        var nodes = 0
        /** Wall-clock limits in [System.nanoTime] units; the search throws [Stop] past `deadline`. */
        var deadline = Long.MAX_VALUE
        var soft = Long.MAX_VALUE
        /** Set from another thread to abort the search at the next node check. */
        @Volatile var abort = false

        private fun bound(pos: Position, gamma: Int, depthIn: Int, root: Boolean = false): Int {
            nodes++
            if (nodes % 2048 == 0 && (abort || System.nanoTime() > deadline)) throw Stop()

            val depth = max(depthIn, 0)
            if (pos.score <= -MATE_LOWER) return -MATE_UPPER

            var entry = Entry(-MATE_UPPER, MATE_UPPER)
            if (!root) {
                entry = tpScore[pos to depth] ?: entry
                if (entry.lower >= gamma) return entry.lower
                if (entry.upper < gamma) return entry.upper
                if (depth > 0 && pos in history) return 0
            }

            val killer = tpMove[pos]

            fun ceiling(v: Int): Int =
                if (depth > 4 || v >= MATE_LOWER) MATE_UPPER else pos.score + v + max(depth - 1, 0) * QS_A

            val calm = abs(pos.score) < 750 && pos.board.any { it in "RBNQ" }
            val guard = !root && calm
            val t = pos.score + NULL_MARGIN
            val nmr = calm && depth >= 6 && -bound(pos.rotate(nullmove = true), 1 - t, depth - 7) >= t

            // Same order as the Python generator: null move, stand pat, killer, sorted real moves.
            val candidates = ArrayList<Pair<Int, Move?>>()
            if (depth in 3..5 && guard) candidates += 0 to null
            if (depth == 0) candidates += 0 to null
            if (killer != null) {
                val v = pos.value(killer)
                if ((v >= QS || depth > 0) && ceiling(v) >= gamma) candidates += v to killer
            }
            val real = ArrayList<Pair<Int, Move>>()
            for (m in pos.genMoves()) {
                val v = pos.value(m)
                if (v >= QS || depth > 0) real += v to m
            }
            // Python sorts (value, Move) tuples descending: ties fall back to the move fields.
            real.sortWith(
                compareByDescending<Pair<Int, Move>> { it.first }
                    .thenByDescending { it.second.i }.thenByDescending { it.second.j }.thenByDescending { it.second.prom },
            )
            for (r in real) candidates += r

            var best = -MATE_UPPER
            var live = false
            for ((v, moveIn) in candidates) {
                var move = moveIn
                val score: Int
                if (move == null && depth == 0) {
                    score = pos.score
                } else if (move == null) {
                    val cap = pos.score + EVAL_ROUGHNESS
                    if (cap >= gamma) {
                        var s = min(cap, -bound(pos.rotate(nullmove = true), 1 - gamma, depth - 4))
                        if (s >= gamma) {
                            val proof = pos.kingCapture()
                            if (proof != null) { move = proof; s = MATE_UPPER; live = true }
                        }
                        score = s
                    } else {
                        score = cap
                    }
                } else if (v >= MATE_LOWER) {
                    score = MATE_UPPER; live = true
                } else {
                    val cap = ceiling(v)
                    if (cap < gamma) { best = max(best, cap); break }
                    val moveDepth = depth - 1 - (if (guard && depth >= 7 && v < LMR) 1 else 0) - (if (nmr) 1 else 0)
                    score = min(cap, -bound(pos.move(move), 1 - gamma, moveDepth))
                    live = live || score > -MATE_UPPER
                }
                best = max(best, score)
                if (best >= gamma) {
                    if (move != null && depth > 0) {
                        tpMove[pos] = move
                        if (tpMove.size > TABLE_SIZE) {
                            val victim = tpMove.keys.first { it != this.root }
                            tpMove.remove(victim)
                        }
                    }
                    break
                }
            }

            if (depth > 0 && !live && pos.genMoves().all { pos.move(it).kingCapture() != null }) {
                val mate = max(1 - MATE_UPPER, -MATE_LOWER - depth * EVAL_ROUGHNESS)
                best = if (pos.rotate(nullmove = true).kingCapture() != null) mate else 0
            }

            if (!root) {
                tpScore[pos to depth] = if (best >= gamma) Entry(best, entry.upper) else Entry(entry.lower, best)
                if (tpScore.size > TABLE_SIZE) tpScore.remove(tpScore.keys.first())
            }
            return best
        }

        /**
         * Iterative deepening MTD-bi search over the game [history] (last = position to
         * search), reporting each probe to [onStep]. Stops after [maxDepth] completed plies,
         * after `soft` once a depth completes, or by throwing [Stop] past `deadline`.
         */
        fun search(history: List<Position>, maxDepth: Int, onStep: (Step) -> Unit) {
            nodes = 0
            this.history = history.toHashSet()
            tpScore.clear()
            val pos = history.last()
            root = pos
            pstK = if ('Q' in pos.board && 'q' in pos.board) K_MID else K_END

            var gamma = 0
            for (depth in 1..maxDepth) {
                var lower = 1 - MATE_UPPER
                var upper = MATE_UPPER
                while (lower < upper - EVAL_ROUGHNESS) {
                    val score = bound(pos, gamma, depth, root = true)
                    if (score >= gamma) lower = score
                    if (score < gamma) upper = score
                    onStep(Step(depth, gamma, score, tpMove[pos]))
                    gamma = Math.floorDiv(lower + upper + 1, 2)
                }
                if (System.nanoTime() > soft) return
            }
        }
    }

    // ---------------------------------------------------------------- UCI helpers

    fun parse(sq: String): Int = A1 + (sq[0] - 'a') - 10 * (sq[1] - '1')

    fun render(i: Int): String =
        ('a' + Math.floorMod(i - A1, 10)).toString() + (1 - Math.floorDiv(i - A1, 10)).toString()

    val START = Position(INITIAL, 0, true, true, true, true, 0, 0)

    /** Replays UCI moves from the start position; odd plies are mirrored (rotated board). */
    fun history(uciMoves: List<String>): List<Position> {
        val hist = ArrayList<Position>(uciMoves.size + 1)
        hist += START
        uciMoves.forEachIndexed { ply, mv ->
            var i = parse(mv.substring(0, 2))
            var j = parse(mv.substring(2, 4))
            val prom = mv.getOrNull(4)?.uppercaseChar() ?: Move.NO_PROM
            if (ply % 2 == 1) { i = 119 - i; j = 119 - j }
            hist += hist.last().move(Move(i, j, prom))
        }
        return hist
    }

    /** Renders [move] found for the last position of a [plies]-move game as UCI. */
    fun toUci(move: Move, plies: Int): String {
        var i = move.i
        var j = move.j
        if (plies % 2 == 1) { i = 119 - i; j = 119 - j }
        val prom = if (move.prom == Move.NO_PROM) "" else move.prom.lowercaseChar().toString()
        return render(i) + render(j) + prom
    }
}
