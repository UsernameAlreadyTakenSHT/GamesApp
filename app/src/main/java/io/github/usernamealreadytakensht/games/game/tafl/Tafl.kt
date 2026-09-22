package io.github.usernamealreadytakensht.games.game.tafl

import com.manywords.softworks.tafl.engine.Game
import com.manywords.softworks.tafl.engine.GameState
import com.manywords.softworks.tafl.engine.MoveRecord
import com.manywords.softworks.tafl.rules.Coord
import com.manywords.softworks.tafl.rules.Rules
import com.manywords.softworks.tafl.rules.Taflman
import com.manywords.softworks.tafl.rules.brandub.Brandub
import com.manywords.softworks.tafl.rules.copenhagen.Copenhagen
import com.manywords.softworks.tafl.rules.fetlar.Fetlar
import com.manywords.softworks.tafl.rules.seabattle.SeaBattle
import com.manywords.softworks.tafl.rules.tablut.Tablut
import com.manywords.softworks.tafl.rules.tawlbwrdd.Tawlbwrdd
import com.manywords.softworks.tafl.ui.UiCallback

/**
 * The tafl family, refereed by OpenTafl's rules engine. This wrapper keeps the app's code
 * free of OpenTafl types: squares are (x, y) with (0, 0) top-left, moves are OpenTafl's
 * "a1-a4" notation (file a…, rank 1… from the top).
 */
object Tafl {

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

    /** Parses a square name; the caller checks it against the board size. */
    fun square(name: String): Pair<Int, Int>? {
        if (name.length < 2) return null
        val x = name[0] - 'a'
        val y = (name.substring(1).toIntOrNull() ?: return null) - 1
        return if (x in 0..18 && y in 0..18) x to y else null
    }

    /** Corner squares (a king's goal in corner variants) and the throne, for drawing. */
    fun isCorner(size: Int, x: Int, y: Int) = (x == 0 || x == size - 1) && (y == 0 || y == size - 1)
    fun isThrone(size: Int, x: Int, y: Int) = x == size / 2 && y == size / 2

    private val quietUi = object : UiCallback {
        override fun statusText(text: String) {}
        override fun timeUpdate(currentSideAttackers: Boolean) {}
        override fun timeExpired(currentSideAttackers: Boolean) {}
    }

    init { Coord.initialize() }

    private fun rules(variant: TaflVariant): Rules = when (variant) {
        TaflVariant.COPENHAGEN -> Copenhagen.newCopenhagen11()
        TaflVariant.FETLAR -> Fetlar.newFetlar11()
        TaflVariant.TAWLBWRDD -> Tawlbwrdd.newTawlbwrdd11()
        TaflVariant.TABLUT -> Tablut.newTablut9()
        TaflVariant.SEA_BATTLE -> SeaBattle.newSeaBattle9()
        TaflVariant.BRANDUB -> Brandub.newBrandub7()
    }

    /** Starts a new game of [variant]. */
    fun newGame(variant: TaflVariant): Game = Game(rules(variant), quietUi)

    fun size(game: Game): Int = game.rules.boardSize

    /** Replays [moves] (notation) from the start of [variant], stopping at the first illegal one. */
    fun replay(variant: TaflVariant, moves: List<String>): Pair<Game, List<Move>> {
        val game = newGame(variant)
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
        val size = size(game)
        return List(size * size) { i -> code(state.getPieceAt(i % size, i / size)) }
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
        val size = size(game)
        val moves = ArrayList<Move>()
        for (y in 0 until size) for (x in 0 until size) {
            for ((tx, ty) in destinations(game, x, y)) moves += Move(x, y, tx, ty)
        }
        return moves
    }

    /** Plays [move] on [game]; false when illegal (the game is left untouched). */
    fun play(game: Game, move: Move): Boolean {
        val size = size(game)
        if (move.fromX !in 0 until size || move.fromY !in 0 until size) return false
        if (move.toX !in 0 until size || move.toY !in 0 until size) return false
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

/**
 * The tafl variants offered, all refereed by OpenTafl's own rule sets. [escapeToCorners]
 * and the king modes are what actually changes the feel of the game.
 */
enum class TaflVariant(
    val label: String,
    val size: Int,
    val escapeToCorners: Boolean,
    val tagline: String,
    val description: String,
) {
    COPENHAGEN(
        "Copenhagen", 11, true, "The modern standard",
        "24 attackers vs 12 defenders and a strong king (four sides to capture). Escape to the corners, shieldwall captures and edge forts. The usual tournament rules.",
    ),
    FETLAR(
        "Fetlar", 11, true, "Copenhagen without the extras",
        "The Fetlar Hnefatafl Panel rules: same board and forces as Copenhagen, strong king, corner escapes, but no shieldwall captures and no edge forts.",
    ),
    TAWLBWRDD(
        "Tawlbwrdd", 11, false, "Welsh, weak king",
        "The Welsh board: the king is weak (two attackers take him) and escapes over any edge square. Fast and sharp, and kind to the attackers.",
    ),
    TABLUT(
        "Tablut", 9, false, "The historical Sámi game",
        "Linnaeus' 9x9 game: 16 attackers vs 8 defenders and an armed king, strong only on the throne, escaping over any edge square. Shorter than the 11x11 games.",
    ),
    SEA_BATTLE(
        "Sea Battle", 9, false, "Unarmed king",
        "A 9x9 board where the king takes part in no captures at all, and escapes over any edge square. A harder job for the defenders.",
    ),
    BRANDUB(
        "Brandub", 7, true, "The smallest tafl",
        "The Irish 7x7 game: 8 attackers vs 4 defenders and an armed king who must reach a corner. A couple of minutes per game.",
    );

    val defaultLabel: String get() = "$label ${size}x$size"
}
