package io.github.usernamealreadytakensht.games.game

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.File
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Rank
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList

/**
 * A move as the app sees it. Castling in Chess960 is [castleRook] != null and is written in
 * UCI "king takes own rook" form (e1h1), as engines expect with UCI_Chess960.
 */
data class ChessMove(val from: Square, val to: Square, val promotion: Piece = Piece.NONE, val castleRook: Square? = null) {
    val isCastle: Boolean get() = castleRook != null

    /** Where the king lands when castling: g- or c-file, whatever the start position. */
    val castleKingTo: Square
        get() = Square.encode(from.rank, if (castleRook!!.file.ordinal > from.file.ordinal) File.FILE_G else File.FILE_C)

    fun toUci(): String {
        val p = if (promotion != Piece.NONE) promotion.pieceType.sanSymbol.lowercase() else ""
        val target = castleRook ?: to
        return from.value().lowercase() + target.value().lowercase() + p
    }
}

/**
 * The rules side of a chess game: position, legal moves, move history and results.
 * [StandardChess] delegates everything to chesslib; [Chess960] adds Fischer-random castling on
 * top of it (chesslib's own 960 support gets castling wrong).
 */
interface ChessSession {
    val startFen: String
    val chess960: Boolean
    val sideToMove: Side
    val uciMoves: List<String>
    val sanMoves: List<String>
    /** Squares to highlight for the last move (for a castle: where the king went from and to). */
    val lastMove: Pair<Square, Square>?
    val inCheck: Boolean
    val isMate: Boolean
    val isStalemate: Boolean
    val isDraw: Boolean

    fun pieceAt(square: Square): Piece
    fun kingSquare(side: Side): Square
    fun legalMoves(): List<ChessMove>
    fun play(move: ChessMove)
    fun undo()

    /** The legal move written [uci] (castling as king-takes-rook in 960), or null. */
    fun parseUci(uci: String): ChessMove? = legalMoves().firstOrNull { it.toUci() == uci }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}

/** Standard chess, entirely chesslib's. */
class StandardChess : ChessSession {
    private val board = Board().apply { loadFromFen(ChessSession.START_FEN) }
    private val moveList = MoveList()
    private val uci = mutableListOf<String>()

    override val startFen = ChessSession.START_FEN
    override val chess960 = false
    override val sideToMove: Side get() = board.sideToMove
    override val uciMoves: List<String> get() = uci
    override val sanMoves: List<String> get() = moveList.toSanArray().toList()
    override val lastMove: Pair<Square, Square>? get() = moveList.lastOrNull()?.let { it.from to it.to }
    override val inCheck: Boolean get() = board.isKingAttacked
    override val isMate: Boolean get() = board.isMated
    override val isStalemate: Boolean get() = board.isStaleMate
    override val isDraw: Boolean get() = board.isDraw

    override fun pieceAt(square: Square): Piece = board.getPiece(square)
    override fun kingSquare(side: Side): Square = board.getKingSquare(side)
    override fun legalMoves(): List<ChessMove> = board.legalMoves().map { ChessMove(it.from, it.to, it.promotion) }

    override fun play(move: ChessMove) {
        val m = if (move.promotion != Piece.NONE) Move(move.from, move.to, move.promotion) else Move(move.from, move.to)
        board.doMove(m)
        moveList.add(m)
        uci += move.toUci()
    }

    override fun undo() {
        board.undoMove()
        moveList.removeLast()
        uci.removeAt(uci.size - 1)
    }
}

/**
 * Chess960. chesslib plays every non-castling move on a board whose FEN carries no castling
 * rights; castling rights (the rook squares) are tracked here and castling moves are
 * generated and applied by hand, following Stockfish's legality rules. Each position is kept
 * as a FEN on a stack, so undo is a pop and repetition is counted on those FENs.
 */
class Chess960(override val startFen: String) : ChessSession {

    /** Castling rook squares still available, per side and wing. */
    private data class Rights(val whiteKing: Square?, val whiteQueen: Square?, val blackKing: Square?, val blackQueen: Square?)

    private data class Snapshot(val fen: String, val rights: Rights, val san: String?, val move: ChessMove?, val lastMove: Pair<Square, Square>?)

    private val board = Board()
    private val stack = ArrayList<Snapshot>()

    override val chess960 = true

    init {
        val parts = startFen.split(' ')
        val noCastle = (parts.take(2) + "-" + parts.drop(3)).joinToString(" ")
        board.loadFromFen(noCastle)
        stack += Snapshot(board.fen, parseRights(parts.getOrElse(2) { "-" }), null, null, null)
    }

    private val top get() = stack.last()

    override val sideToMove: Side get() = board.sideToMove
    override val uciMoves: List<String> get() = stack.drop(1).map { it.move!!.toUci() }
    override val sanMoves: List<String> get() = stack.drop(1).map { it.san!! }
    override val lastMove: Pair<Square, Square>? get() = top.lastMove
    override val inCheck: Boolean get() = board.isKingAttacked
    override val isMate: Boolean get() = inCheck && legalMoves().isEmpty()
    override val isStalemate: Boolean get() = !inCheck && legalMoves().isEmpty()
    override val isDraw: Boolean
        get() = isStalemate || board.isInsufficientMaterial || board.halfMoveCounter >= 100 || repetitions() >= 3

    override fun pieceAt(square: Square): Piece = board.getPiece(square)
    override fun kingSquare(side: Side): Square = board.getKingSquare(side)

    override fun legalMoves(): List<ChessMove> =
        board.legalMoves().map { ChessMove(it.from, it.to, it.promotion) } + castlingMoves()

    override fun play(move: ChessMove) {
        val san = san(move)
        val rights = updatedRights(top.rights, move)
        val kingTo = if (move.isCastle) castleKingTarget(move) else move.to
        if (move.isCastle) {
            board.loadFromFen(castledFen(move))
        } else {
            val m = if (move.promotion != Piece.NONE) Move(move.from, move.to, move.promotion) else Move(move.from, move.to)
            board.doMove(m)
        }
        stack += Snapshot(board.fen, rights, san, move, move.from to kingTo)
        board.loadFromFen(top.fen)
    }

    override fun undo() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        board.loadFromFen(top.fen)
    }

    // ---------------------------------------------------------------- castling

    /** Shredder (HAha) or X-FEN (KQkq, meaning the outermost rook) castling field. */
    private fun parseRights(field: String): Rights {
        var wk: Square? = null; var wq: Square? = null; var bk: Square? = null; var bq: Square? = null
        val wKing = board.getKingSquare(Side.WHITE)
        val bKing = board.getKingSquare(Side.BLACK)
        fun rookOn(rank: Rank, side: Side, kingSide: Boolean, king: Square): Square? {
            val files = if (kingSide) File.entries.filter { it.ordinal in (king.file.ordinal + 1)..7 }.reversed()
                        else File.entries.filter { it.ordinal in 0 until king.file.ordinal }
            return files.map { Square.encode(rank, it) }.firstOrNull { board.getPiece(it) == Piece.make(side, PieceType.ROOK) }
        }
        for (c in field) {
            when {
                c == 'K' -> wk = rookOn(Rank.RANK_1, Side.WHITE, true, wKing)
                c == 'Q' -> wq = rookOn(Rank.RANK_1, Side.WHITE, false, wKing)
                c == 'k' -> bk = rookOn(Rank.RANK_8, Side.BLACK, true, bKing)
                c == 'q' -> bq = rookOn(Rank.RANK_8, Side.BLACK, false, bKing)
                c in 'A'..'H' -> {
                    val sq = Square.encode(Rank.RANK_1, File.entries[c - 'A'])
                    if (sq.file.ordinal > wKing.file.ordinal) wk = sq else wq = sq
                }
                c in 'a'..'h' -> {
                    val sq = Square.encode(Rank.RANK_8, File.entries[c - 'a'])
                    if (sq.file.ordinal > bKing.file.ordinal) bk = sq else bq = sq
                }
            }
        }
        return Rights(wk, wq, bk, bq)
    }

    private fun updatedRights(r: Rights, move: ChessMove): Rights {
        var (wk, wq, bk, bq) = r
        val piece = board.getPiece(move.from)
        if (piece == Piece.WHITE_KING) { wk = null; wq = null }
        if (piece == Piece.BLACK_KING) { bk = null; bq = null }
        // A rook leaving or being captured on its castling square loses that right.
        for (sq in listOf(move.from, move.to)) {
            if (sq == wk) wk = null
            if (sq == wq) wq = null
            if (sq == bk) bk = null
            if (sq == bq) bq = null
        }
        return Rights(wk, wq, bk, bq)
    }

    private fun castleKingTarget(move: ChessMove): Square = move.castleKingTo

    private fun castleRookTarget(move: ChessMove): Square {
        val kingSide = move.castleRook!!.file.ordinal > move.from.file.ordinal
        return Square.encode(move.from.rank, if (kingSide) File.FILE_F else File.FILE_D)
    }

    private fun castlingMoves(): List<ChessMove> {
        val us = board.sideToMove
        val them = if (us == Side.WHITE) Side.BLACK else Side.WHITE
        val r = top.rights
        val rooks = if (us == Side.WHITE) listOf(r.whiteKing, r.whiteQueen) else listOf(r.blackKing, r.blackQueen)
        val king = board.getKingSquare(us)
        if (board.isKingAttacked) return emptyList()
        val out = ArrayList<ChessMove>()
        for (rook in rooks) {
            if (rook == null || board.getPiece(rook) != Piece.make(us, PieceType.ROOK)) continue
            val cand = ChessMove(king, rook, castleRook = rook)
            val kTo = castleKingTarget(cand)
            val rTo = castleRookTarget(cand)
            // Every square the king and rook cross or land on is empty, bar the two of them.
            val files = listOf(king.file.ordinal, rook.file.ordinal, kTo.file.ordinal, rTo.file.ordinal)
            val clear = (files.min()..files.max()).all { f ->
                val sq = Square.encode(king.rank, File.entries[f])
                sq == king || sq == rook || board.getPiece(sq) == Piece.NONE
            }
            if (!clear) continue
            // The king does not cross an attacked square…
            val step = if (kTo.file.ordinal >= king.file.ordinal) 1 else -1
            var f = king.file.ordinal
            var safe = true
            while (f != kTo.file.ordinal) {
                f += step
                if (board.squareAttackedBy(Square.encode(king.rank, File.entries[f]), them) != 0L) { safe = false; break }
            }
            if (!safe) continue
            // …and is not in check once the rook has moved (it may have been shielding the king).
            val after = Board().apply { loadFromFen(castledFen(cand)) }
            if (after.squareAttackedBy(kTo, them) != 0L) continue
            out += cand
        }
        return out
    }

    /** The FEN after [move] (a castle), with no castling rights (they live in [Rights]). */
    private fun castledFen(move: ChessMove): String {
        val us = board.sideToMove
        val grid = Array(8) { arrayOfNulls<Piece>(8) }
        for (i in 0 until 64) {
            val sq = Square.squareAt(i)
            val p = board.getPiece(sq)
            if (p != Piece.NONE) grid[sq.rank.ordinal][sq.file.ordinal] = p
        }
        grid[move.from.rank.ordinal][move.from.file.ordinal] = null
        grid[move.castleRook!!.rank.ordinal][move.castleRook.file.ordinal] = null
        val kTo = castleKingTarget(move)
        val rTo = castleRookTarget(move)
        grid[kTo.rank.ordinal][kTo.file.ordinal] = Piece.make(us, PieceType.KING)
        grid[rTo.rank.ordinal][rTo.file.ordinal] = Piece.make(us, PieceType.ROOK)
        val placement = (7 downTo 0).joinToString("/") { rank ->
            buildString {
                var empty = 0
                for (file in 0 until 8) {
                    val p = grid[rank][file]
                    if (p == null) { empty++ } else {
                        if (empty > 0) { append(empty); empty = 0 }
                        append(p.fenSymbol)
                    }
                }
                if (empty > 0) append(empty)
            }
        }
        val side = if (us == Side.WHITE) "b" else "w"
        val half = board.halfMoveCounter + 1
        val full = board.moveCounter + if (us == Side.BLACK) 1 else 0
        return "$placement $side - - $half $full"
    }

    // ---------------------------------------------------------------- draws and notation

    /** How many times the current position occurred (placement, side to move, rights, en passant). */
    private fun repetitions(): Int {
        fun key(s: Snapshot) = s.fen.split(' ').take(4).joinToString(" ") + s.rights
        val now = key(top)
        return stack.count { key(it) == now }
    }

    /** Standard algebraic notation for [move] in the current position. */
    private fun san(move: ChessMove): String {
        val suffix = run {
            val probe = Chess960(fenWithRights())
            probe.play0(move)
            when {
                probe.isMate -> "#"
                probe.inCheck -> "+"
                else -> ""
            }
        }
        if (move.isCastle) {
            return (if (move.castleRook!!.file.ordinal > move.from.file.ordinal) "O-O" else "O-O-O") + suffix
        }
        val piece = board.getPiece(move.from)
        val capture = board.getPiece(move.to) != Piece.NONE ||
            (piece.pieceType == PieceType.PAWN && move.from.file != move.to.file)
        val dest = move.to.value().lowercase()
        val body = if (piece.pieceType == PieceType.PAWN) {
            val promo = if (move.promotion != Piece.NONE) "=" + move.promotion.pieceType.sanSymbol else ""
            (if (capture) move.from.file.notation.lowercase() + "x" else "") + dest + promo
        } else {
            val others = legalMoves().filter {
                !it.isCastle && it.to == move.to && it.from != move.from && board.getPiece(it.from) == piece
            }
            val disambiguation = when {
                others.isEmpty() -> ""
                others.none { it.from.file == move.from.file } -> move.from.file.notation.lowercase()
                others.none { it.from.rank == move.from.rank } -> move.from.rank.notation
                else -> move.from.value().lowercase()
            }
            piece.pieceType.sanSymbol + disambiguation + (if (capture) "x" else "") + dest
        }
        return body + suffix
    }

    /** The current position as a Shredder-FEN, castling rights included. */
    private fun fenWithRights(): String {
        val parts = top.fen.split(' ').toMutableList()
        val r = top.rights
        val field = listOfNotNull(
            r.whiteKing?.file?.notation?.uppercase(), r.whiteQueen?.file?.notation?.uppercase(),
            r.blackKing?.file?.notation?.lowercase(), r.blackQueen?.file?.notation?.lowercase(),
        ).joinToString("")
        parts[2] = field.ifEmpty { "-" }
        return parts.joinToString(" ")
    }

    /** [play] without computing notation (used when probing for check / mate). */
    private fun play0(move: ChessMove) {
        val rights = updatedRights(top.rights, move)
        if (move.isCastle) board.loadFromFen(castledFen(move))
        else board.doMove(if (move.promotion != Piece.NONE) Move(move.from, move.to, move.promotion) else Move(move.from, move.to))
        stack += Snapshot(board.fen, rights, null, move, null)
        board.loadFromFen(top.fen)
    }

    companion object {
        /**
         * Start position number [n] (0..959) in Scharnagl's numbering; 518 is the standard
         * setup. Castling rights are the two rooks, in Shredder-FEN.
         */
        fun startFen(n: Int): String {
            require(n in 0..959)
            val rank = arrayOfNulls<Char>(8)
            var x = n
            rank[(x % 4) * 2 + 1] = 'B'; x /= 4           // light-squared bishop
            rank[(x % 4) * 2] = 'B'; x /= 4               // dark-squared bishop
            fun free() = (0 until 8).filter { rank[it] == null }
            rank[free()[x % 6]] = 'Q'; x /= 6
            val knights = listOf(0 to 1, 0 to 2, 0 to 3, 0 to 4, 1 to 2, 1 to 3, 1 to 4, 2 to 3, 2 to 4, 3 to 4)[x]
            val f1 = free()
            rank[f1[knights.first]] = 'N'; rank[f1[knights.second]] = 'N'
            val rest = free()
            rank[rest[0]] = 'R'; rank[rest[1]] = 'K'; rank[rest[2]] = 'R'
            val white = rank.joinToString("") { it.toString() }
            val black = white.lowercase()
            val rooks = rest[2].let { ('A' + it).toString() } + rest[0].let { ('A' + it).toString() }
            return "$black/pppppppp/8/8/8/8/PPPPPPPP/$white w $rooks${rooks.lowercase()} - 0 1"
        }
    }
}
