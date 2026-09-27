package io.github.usernamealreadytakensht.games.game.shogi

/**
 * Shogi rules: move generation (moves, drops, promotions), legality (no move into check,
 * nifu, uchifuzume, no dead pieces), mate and sennichite. Verified by perft against
 * Fairy-Stockfish (see ShogiTest).
 *
 * Squares are 0..80, `rank * 9 + file`, with file 0..8 = a..i from Sente's left and rank
 * 0..8 = 1..9 from Sente's side: Fairy-Stockfish's UCI coordinates (e.g. "g3g4", "P@e5",
 * "b8h2+"). Sente (uppercase in FEN) moves first, up the board.
 */
object Shogi {

    enum class Side {
        SENTE, GOTE;
        val other: Side get() = if (this == SENTE) GOTE else SENTE
        /** Rank direction of this side's forward moves. */
        val forward: Int get() = if (this == SENTE) 1 else -1
    }

    /** Piece kinds; the first seven (pawn → rook) can be held in hand, in that order. */
    enum class Kind(val letter: Char, val promotable: Boolean) {
        PAWN('P', true), LANCE('L', true), KNIGHT('N', true), SILVER('S', true),
        GOLD('G', false), BISHOP('B', true), ROOK('R', true), KING('K', false);

        companion object {
            val HAND = listOf(PAWN, LANCE, KNIGHT, SILVER, GOLD, BISHOP, ROOK)
            fun ofLetter(c: Char): Kind? = entries.firstOrNull { it.letter == c.uppercaseChar() }
        }
    }

    data class Piece(val side: Side, val kind: Kind, val promoted: Boolean = false) {
        /** FEN letter(s): "P", "+p"… */
        val fen: String get() = (if (promoted) "+" else "") + if (side == Side.SENTE) kind.letter else kind.letter.lowercaseChar()
    }

    /** A board move ([from] >= 0) or a drop of [drop] (then [from] = -1). */
    data class Move(val from: Int, val to: Int, val promote: Boolean = false, val drop: Kind? = null) {
        val isDrop: Boolean get() = drop != null

        fun toUci(): String =
            if (drop != null) "${drop.letter}@${name(to)}"
            else name(from) + name(to) + if (promote) "+" else ""
    }

    fun name(sq: Int): String = "${'a' + sq % 9}${sq / 9 + 1}"
    fun file(sq: Int) = sq % 9
    fun rank(sq: Int) = sq / 9

    enum class Outcome { ONGOING, SENTE_WINS, GOTE_WINS, DRAW }

    const val START_FEN = "lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL[] w - - 0 1"

    // ---------------------------------------------------------------- position

    class Position private constructor(
        val board: Array<Piece?>,
        /** Pieces in hand, per side, indexed like [Kind.HAND]. */
        val hands: Array<IntArray>,
        val sideToMove: Side,
    ) {
        fun at(sq: Int): Piece? = board[sq]
        fun inHand(side: Side, kind: Kind): Int = hands[side.ordinal][Kind.HAND.indexOf(kind)]

        fun kingSquare(side: Side): Int = board.indexOfFirst { it != null && it.side == side && it.kind == Kind.KING }

        /** Position after [move] (assumed pseudo-legal). */
        fun play(move: Move): Position {
            val b = board.copyOf()
            val h = arrayOf(hands[0].copyOf(), hands[1].copyOf())
            val us = sideToMove
            if (move.drop != null) {
                b[move.to] = Piece(us, move.drop)
                h[us.ordinal][Kind.HAND.indexOf(move.drop)]--
            } else {
                val p = b[move.from]!!
                b[move.to]?.let { captured -> h[us.ordinal][Kind.HAND.indexOf(captured.kind)]++ }
                b[move.from] = null
                b[move.to] = if (move.promote) p.copy(promoted = true) else p
            }
            return Position(b, h, us.other)
        }

        /** Whether [side]'s king is attacked. */
        fun inCheck(side: Side = sideToMove): Boolean {
            val k = kingSquare(side)
            return k >= 0 && attacked(k, side.other)
        }

        /** Whether any piece of [by] attacks [sq]. */
        fun attacked(sq: Int, by: Side): Boolean {
            for (from in 0 until 81) {
                val p = board[from] ?: continue
                if (p.side != by) continue
                if (attacks(from, p, sq)) return true
            }
            return false
        }

        private fun attacks(from: Int, p: Piece, target: Int): Boolean {
            val df = file(target) - file(from)
            val dr = rank(target) - rank(from)
            if (df == 0 && dr == 0) return false
            for ((sf, sr) in steps(p)) if (sf == df && sr == dr) return true
            for ((sf, sr) in slides(p)) {
                var f = file(from) + sf
                var r = rank(from) + sr
                while (f in 0..8 && r in 0..8) {
                    if (f == file(target) && r == rank(target)) return true
                    if (board[r * 9 + f] != null) break
                    f += sf; r += sr
                }
            }
            return false
        }

        /** All legal moves of the side to move (computed once: positions are immutable). */
        fun legalMoves(): List<Move> = legal

        private val legal: List<Move> by lazy(LazyThreadSafetyMode.NONE) { pseudoMoves().filter { isLegal(it) } }

        /** Whether the side to move is in check (cached, like [legalMoves]). */
        val isCheck: Boolean by lazy(LazyThreadSafetyMode.NONE) { inCheck(sideToMove) }

        private fun isLegal(m: Move): Boolean {
            val next = play(m)
            if (next.inCheck(sideToMove)) return false
            // Uchifuzume: a pawn drop may not deliver checkmate.
            if (m.drop == Kind.PAWN && next.inCheck(next.sideToMove) && next.legalMoves().isEmpty()) return false
            return true
        }

        private fun pseudoMoves(): List<Move> {
            val us = sideToMove
            val out = ArrayList<Move>(128)
            for (from in 0 until 81) {
                val p = board[from] ?: continue
                if (p.side != us) continue
                fun add(to: Int) {
                    val t = board[to]
                    if (t != null && t.side == us) return
                    val canPromote = p.kind.promotable && !p.promoted &&
                        (inZone(from, us) || inZone(to, us))
                    if (canPromote) out += Move(from, to, promote = true)
                    if (!mustPromote(p, to)) out += Move(from, to)
                }
                for ((sf, sr) in steps(p)) {
                    val f = file(from) + sf; val r = rank(from) + sr
                    if (f in 0..8 && r in 0..8) add(r * 9 + f)
                }
                for ((sf, sr) in slides(p)) {
                    var f = file(from) + sf; var r = rank(from) + sr
                    while (f in 0..8 && r in 0..8) {
                        val to = r * 9 + f
                        add(to)
                        if (board[to] != null) break
                        f += sf; r += sr
                    }
                }
            }
            // Drops.
            val hand = hands[us.ordinal]
            for ((i, kind) in Kind.HAND.withIndex()) {
                if (hand[i] == 0) continue
                val pawnFiles = if (kind == Kind.PAWN) BooleanArray(9).also { files ->
                    for (sq in 0 until 81) {
                        val p = board[sq]
                        if (p != null && p.side == us && p.kind == Kind.PAWN && !p.promoted) files[file(sq)] = true
                    }
                } else null
                for (to in 0 until 81) {
                    if (board[to] != null) continue
                    if (mustPromote(Piece(us, kind), to)) continue // no dead pieces
                    if (pawnFiles != null && pawnFiles[file(to)]) continue // nifu
                    out += Move(-1, to, drop = kind)
                }
            }
            return out
        }

        /** Position key for repetition: board, hands and side to move. */
        fun key(): String = keyCache

        private val keyCache: String by lazy(LazyThreadSafetyMode.NONE) { toFen().substringBeforeLast(" - ") }

        /** Fairy-Stockfish FEN: placement, `[hand]`, side ("w" = Sente). */
        fun toFen(): String {
            val rows = (8 downTo 0).joinToString("/") { r ->
                buildString {
                    var empty = 0
                    for (f in 0..8) {
                        val p = board[r * 9 + f]
                        if (p == null) { empty++; continue }
                        if (empty > 0) { append(empty); empty = 0 }
                        append(p.fen)
                    }
                    if (empty > 0) append(empty)
                }
            }
            val hand = buildString {
                for (side in Side.entries) for (kind in Kind.HAND.reversed()) {
                    val c = if (side == Side.SENTE) kind.letter else kind.letter.lowercaseChar()
                    repeat(inHand(side, kind)) { append(c) }
                }
            }
            return "$rows[$hand] ${if (sideToMove == Side.SENTE) "w" else "b"} - - 0 1"
        }

        companion object {
            fun fromFen(fen: String): Position {
                val board = arrayOfNulls<Piece>(81)
                val hands = arrayOf(IntArray(7), IntArray(7))
                val placement = fen.substringBefore('[').substringBefore(' ')
                var r = 8; var f = 0; var promoted = false
                for (c in placement) {
                    when {
                        c == '/' -> { r--; f = 0 }
                        c == '+' -> promoted = true
                        c.isDigit() -> f += c - '0'
                        else -> {
                            val side = if (c.isUpperCase()) Side.SENTE else Side.GOTE
                            board[r * 9 + f] = Piece(side, Kind.ofLetter(c)!!, promoted)
                            promoted = false
                            f++
                        }
                    }
                }
                if ('[' in fen) {
                    for (c in fen.substringAfter('[').substringBefore(']')) {
                        val kind = Kind.ofLetter(c) ?: continue
                        val side = if (c.isUpperCase()) Side.SENTE else Side.GOTE
                        hands[side.ordinal][Kind.HAND.indexOf(kind)]++
                    }
                }
                val side = if (fen.substringAfter(']', fen.substringAfter(' ')).trim().startsWith("b")) Side.GOTE else Side.SENTE
                return Position(board, hands, side)
            }

            fun start(): Position = fromFen(START_FEN)
        }
    }

    // ---------------------------------------------------------------- piece geometry

    private val KING_STEPS = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)
    private val ORTHO = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
    private val DIAG = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

    /** Move tables per (side, promoted, kind), built once: attack tests run thousands of times per move. */
    private fun tableIndex(p: Piece) = p.side.ordinal * 16 + (if (p.promoted) 8 else 0) + p.kind.ordinal
    private fun allPieces() = List(32) { i -> Piece(Side.entries[i / 16], Kind.entries[i % 8], (i / 8) % 2 == 1) }
    private val STEPS: List<List<Pair<Int, Int>>> by lazy { allPieces().map { stepsOf(it) } }
    private val SLIDES: List<List<Pair<Int, Int>>> by lazy { allPieces().map { slidesOf(it) } }

    private fun steps(p: Piece): List<Pair<Int, Int>> = STEPS[tableIndex(p)]
    private fun slides(p: Piece): List<Pair<Int, Int>> = SLIDES[tableIndex(p)]

    /** Single-step moves of [p], as (file, rank) offsets. */
    private fun stepsOf(p: Piece): List<Pair<Int, Int>> {
        val fw = p.side.forward
        val gold = listOf(-1 to fw, 0 to fw, 1 to fw, -1 to 0, 1 to 0, 0 to -fw)
        return when {
            p.kind == Kind.KING -> KING_STEPS
            p.promoted && p.kind == Kind.BISHOP -> ORTHO
            p.promoted && p.kind == Kind.ROOK -> DIAG
            p.promoted || p.kind == Kind.GOLD -> gold
            p.kind == Kind.SILVER -> listOf(-1 to fw, 0 to fw, 1 to fw, -1 to -fw, 1 to -fw)
            p.kind == Kind.KNIGHT -> listOf(-1 to 2 * fw, 1 to 2 * fw)
            p.kind == Kind.PAWN -> listOf(0 to fw)
            else -> emptyList()
        }
    }

    /** Sliding directions of [p]. */
    private fun slidesOf(p: Piece): List<Pair<Int, Int>> = when (p.kind) {
        Kind.ROOK -> ORTHO
        Kind.BISHOP -> DIAG
        Kind.LANCE -> if (p.promoted) emptyList() else listOf(0 to p.side.forward)
        else -> emptyList()
    }

    /** The three far ranks, where pieces may promote. */
    fun inZone(sq: Int, side: Side): Boolean = if (side == Side.SENTE) rank(sq) >= 6 else rank(sq) <= 2

    /** A pawn or lance on the last rank, or a knight on the last two, could never move again. */
    fun mustPromote(p: Piece, to: Int): Boolean {
        if (p.promoted) return false
        val fromEnd = if (p.side == Side.SENTE) 8 - rank(to) else rank(to)
        return when (p.kind) {
            Kind.PAWN, Kind.LANCE -> fromEnd == 0
            Kind.KNIGHT -> fromEnd <= 1
            else -> false
        }
    }

    // ---------------------------------------------------------------- game

    /** A game: positions from the start, for undo, repetition and notation. */
    class Game(startFen: String = START_FEN) {
        private val positions = arrayListOf(Position.fromFen(startFen))
        private val played = ArrayList<Move>()
        private val notation = ArrayList<String>()

        val position: Position get() = positions.last()
        val moves: List<Move> get() = played
        val uciMoves: List<String> get() = played.map { it.toUci() }
        val notations: List<String> get() = notation

        fun legalMoves(): List<Move> = position.legalMoves()

        fun parseUci(uci: String): Move? = legalMoves().firstOrNull { it.toUci() == uci }

        fun play(move: Move) {
            notation += notate(position, move)
            played += move
            positions += position.play(move)
        }

        fun undo() {
            if (played.isEmpty()) return
            played.removeAt(played.size - 1)
            notation.removeAt(notation.size - 1)
            positions.removeAt(positions.size - 1)
        }

        /**
         * Game result: a side with no legal move has lost (mate, or the rare stalemate).
         * Sennichite: the fourth occurrence of a position is a draw, unless one side gave
         * check on every move of the cycle, in which case that side loses.
         */
        fun outcome(): Outcome {
            // The result only depends on the positions so far: cached per last position.
            val pos = position
            cachedOutcome?.let { (at, result) -> if (at === pos) return result }
            return computeOutcome(pos).also { cachedOutcome = pos to it }
        }

        private var cachedOutcome: Pair<Position, Outcome>? = null

        private fun computeOutcome(pos: Position): Outcome {
            if (pos.legalMoves().isEmpty()) return if (pos.sideToMove == Side.SENTE) Outcome.GOTE_WINS else Outcome.SENTE_WINS
            val key = pos.key()
            val same = positions.indices.filter { positions[it].key() == key }
            if (same.size < 4) return Outcome.ONGOING
            val first = same[same.size - 4]
            for (checker in Side.entries) {
                // Positions after the checker's moves in the cycle have the other side in check.
                val afterChecker = (first + 1..positions.lastIndex).filter { positions[it].sideToMove == checker.other }
                if (afterChecker.isNotEmpty() && afterChecker.all { positions[it].isCheck }) {
                    return if (checker == Side.SENTE) Outcome.GOTE_WINS else Outcome.SENTE_WINS
                }
            }
            return Outcome.DRAW
        }

        /**
         * The game as a KIF file, the most widespread shogi record format. [ending] is the
         * closing keyword (投了 resignation, 詰み mate, 切れ負け time, 千日手 repetition) and
         * [winner] "先手" / "後手", or null for a draw or an unfinished game.
         */
        fun kif(sente: String, gote: String, startedAt: Long, ending: String?, winner: String?): String = buildString {
            val date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(startedAt))
            appendLine("#KIF version=2.0 encoding=UTF-8")
            appendLine("開始日時：$date")
            appendLine("手合割：平手")
            appendLine("先手：$sente")
            appendLine("後手：$gote")
            appendLine("手数----指手---------消費時間--")
            played.forEachIndexed { i, m ->
                val before = positions[i]
                val previousTo = played.getOrNull(i - 1)?.to
                appendLine("%4d %s   ( 0:00/00:00:00)".format(i + 1, kifMove(before, m, previousTo)))
            }
            if (ending != null) {
                appendLine("%4d %s   ( 0:00/00:00:00)".format(played.size + 1, ending))
                if (winner != null) appendLine("まで${played.size}手で${winner}の勝ち")
                else appendLine("まで${played.size}手で$ending")
            }
        }

        /** Why the game ended, for the status line (outcome() must not be ONGOING). */
        fun isRepetition(): Boolean {
            val key = position.key()
            return positions.count { it.key() == key } >= 4
        }
    }

    private const val KIF_FILES = "１２３４５６７８９"
    private const val KIF_RANKS = "一二三四五六七八九"

    private fun kifPiece(p: Piece): String = if (p.promoted) when (p.kind) {
        Kind.PAWN -> "と"; Kind.LANCE -> "成香"; Kind.KNIGHT -> "成桂"; Kind.SILVER -> "成銀"
        Kind.BISHOP -> "馬"; Kind.ROOK -> "龍"; else -> kifPiece(p.copy(promoted = false))
    } else when (p.kind) {
        Kind.PAWN -> "歩"; Kind.LANCE -> "香"; Kind.KNIGHT -> "桂"; Kind.SILVER -> "銀"
        Kind.GOLD -> "金"; Kind.BISHOP -> "角"; Kind.ROOK -> "飛"; Kind.KING -> "玉"
    }

    /** One KIF move: destination (or 同 for a recapture), piece, 成 / 不成 / 打, origin "(77)". */
    private fun kifMove(pos: Position, m: Move, previousTo: Int?): String {
        val file = 9 - file(m.to)
        val rank = 9 - rank(m.to)
        val dest = if (m.to == previousTo) "同　" else "${KIF_FILES[file - 1]}${KIF_RANKS[rank - 1]}"
        if (m.drop != null) return dest + kifPiece(Piece(pos.sideToMove, m.drop)) + "打"
        val p = pos.at(m.from)!!
        val canPromote = p.kind.promotable && !p.promoted && (inZone(m.from, p.side) || inZone(m.to, p.side))
        val suffix = when {
            m.promote -> "成"
            canPromote && !mustPromote(p, m.to) -> "不成"
            else -> ""
        }
        return dest + kifPiece(p) + suffix + "(${9 - file(m.from)}${9 - rank(m.from)})"
    }

    /**
     * Western (Hodges) notation: piece, origin when ambiguous, "-" / "x" / "*", destination
     * as file 9..1 from Sente's left plus rank a..i from Gote's side, then "+" (promotes) or
     * "=" (declines). E.g. "P-7f", "Bx2b+", "S*5e", "G6i-5h".
     */
    fun notate(pos: Position, move: Move): String {
        fun sq(s: Int) = "${9 - file(s)}${'a' + (8 - rank(s))}"
        if (move.drop != null) return "${move.drop.letter}*${sq(move.to)}"
        val p = pos.at(move.from)!!
        val letter = (if (p.promoted) "+" else "") + p.kind.letter
        val others = pos.legalMoves().filter {
            !it.isDrop && it.to == move.to && it.from != move.from && pos.at(it.from) == p
        }
        val origin = if (others.isNotEmpty()) sq(move.from) else ""
        val sep = if (pos.at(move.to) != null) "x" else "-"
        val canPromote = p.kind.promotable && !p.promoted &&
            (inZone(move.from, p.side) || inZone(move.to, p.side))
        val suffix = when {
            move.promote -> "+"
            canPromote && !mustPromote(p, move.to) -> "="
            else -> ""
        }
        return letter + origin + sep + sq(move.to) + suffix
    }
}
