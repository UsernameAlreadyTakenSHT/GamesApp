package io.github.usernamealreadytakensht.games.game.tafl

import com.manywords.softworks.tafl.engine.Game
import com.manywords.softworks.tafl.engine.GameState
import com.manywords.softworks.tafl.engine.MoveRecord
import com.manywords.softworks.tafl.rules.Coord
import com.manywords.softworks.tafl.rules.Taflman
import com.manywords.softworks.tafl.rules.copenhagen.Copenhagen
import com.manywords.softworks.tafl.ui.UiCallback

/**
 * Copenhagen hnefatafl (11x11), refereed by OpenTafl's rules engine. This wrapper keeps the
 * app's code free of OpenTafl types: squares are (x, y) with (0, 0) top-left, moves are
 * OpenTafl's "a1-a4" notation (file a–k, rank 1–11 from the top).
 */
object Tafl {

    const val SIZE = 11

    const val EMPTY = 0
    const val ATTACKER = 1
    const val DEFENDER = 2
    const val KING = 3

    enum class Side { ATTACKERS, DEFENDERS; val other: Side get() = if (this == ATTACKERS) DEFENDERS else ATTACKERS }

    enum class Outcome { ONGOING, ATTACKERS_WIN, DEFENDERS_WIN, DRAW }

    data class Move(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) {
        fun toNotation(): String = "${name(fromX, fromY)}-${name(toX, toY)}"

        companion object {
            fun parse(text: String): Move? {
                val dash = text.indexOf('-')
                if (dash <= 0) return null
                val from = square(text.substring(0, dash)) ?: return null
                val to = square(text.substring(dash + 1)) ?: return null
                return Move(from.first, from.second, to.first, to.second)
            }
        }
    }

    fun name(x: Int, y: Int): String = "${'a' + x}${y + 1}"

    fun square(name: String): Pair<Int, Int>? {
        if (name.length < 2) return null
        val x = name[0] - 'a'
        val y = (name.substring(1).toIntOrNull() ?: return null) - 1
        return if (x in 0 until SIZE && y in 0 until SIZE) x to y else null
    }

    /** Corner squares (the king's goal) and the throne, for drawing. */
    fun isCorner(x: Int, y: Int) = (x == 0 || x == SIZE - 1) && (y == 0 || y == SIZE - 1)
    fun isThrone(x: Int, y: Int) = x == SIZE / 2 && y == SIZE / 2

    private val quietUi = object : UiCallback {
        override fun statusText(text: String) {}
        override fun timeUpdate(currentSideAttackers: Boolean) {}
        override fun timeExpired(currentSideAttackers: Boolean) {}
    }

    init { Coord.initialize() }

    /** Starts a new Copenhagen game. */
    fun newGame(): Game = Game(Copenhagen.newCopenhagen11(), quietUi)

    /** Replays [moves] (notation) from the start, stopping at the first illegal one. */
    fun replay(moves: List<String>): Pair<Game, List<Move>> {
        val game = newGame()
        val played = ArrayList<Move>()
        for (text in moves) {
            val m = Move.parse(text) ?: break
            if (!play(game, m)) break
            played += m
        }
        return game to played
    }

    /** The piece code on each square, row by row from the top. */
    fun board(game: Game): List<Int> {
        val state = game.currentState
        return List(SIZE * SIZE) { i -> code(state.getPieceAt(i % SIZE, i / SIZE)) }
    }

    fun code(taflman: Char): Int = when {
        taflman == Taflman.EMPTY -> EMPTY
        Taflman.isKing(taflman) -> KING
        Taflman.getPackedSide(taflman) == Taflman.SIDE_ATTACKERS -> ATTACKER
        else -> DEFENDER
    }

    fun sideToMove(game: Game): Side = if (game.currentSide.isAttackingSide) Side.ATTACKERS else Side.DEFENDERS

    fun sideOf(code: Int): Side? = when (code) {
        ATTACKER -> Side.ATTACKERS
        DEFENDER, KING -> Side.DEFENDERS
        else -> null
    }

    /** Squares the piece on (x, y) may move to (empty when it is not the side to move's). */
    fun destinations(game: Game, x: Int, y: Int): List<Pair<Int, Int>> {
        val state = game.currentState
        val taflman = state.getPieceAt(x, y)
        if (taflman == Taflman.EMPTY) return emptyList()
        if (Taflman.getSide(state, taflman).isAttackingSide != state.currentSide.isAttackingSide) return emptyList()
        return Taflman.getAllowableDestinations(state, taflman).map { it.x.toInt() to it.y.toInt() }
    }

    fun legalMoves(game: Game): List<Move> {
        val moves = ArrayList<Move>()
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            for ((tx, ty) in destinations(game, x, y)) moves += Move(x, y, tx, ty)
        }
        return moves
    }

    /** Plays [move] on [game]; false when illegal (the game is left untouched). */
    fun play(game: Game, move: Move): Boolean {
        val record = MoveRecord(Coord.get(move.fromX, move.fromY), Coord.get(move.toX, move.toY))
        return game.currentState.makeMove(record) >= GameState.LOWEST_NONERROR_RESULT
    }

    fun outcome(game: Game): Outcome = when (game.currentState.checkVictory()) {
        GameState.ATTACKER_WIN -> Outcome.ATTACKERS_WIN
        GameState.DEFENDER_WIN -> Outcome.DEFENDERS_WIN
        GameState.DRAW -> Outcome.DRAW
        else -> Outcome.ONGOING
    }

    /** Squares captured by the last move, for highlighting. */
    fun lastCaptures(game: Game): List<Pair<Int, Int>> =
        game.currentState.enteringMove?.captures?.map { it.x.toInt() to it.y.toInt() } ?: emptyList()

    fun count(game: Game, code: Int): Int = board(game).count { it == code }
}
