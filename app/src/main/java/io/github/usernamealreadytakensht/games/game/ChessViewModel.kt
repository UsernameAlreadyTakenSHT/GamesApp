package io.github.usernamealreadytakensht.games.game

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.usernamealreadytakensht.games.engine.ChessEngine
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

enum class Result { ONGOING, PLAYER_WINS, ENGINE_WINS, DRAW }

/** Immutable snapshot of the game, consumed by the UI. */
data class GameState(
    val config: GameConfig = GameConfig(),
    /** Piece per square, indexed by `Square.ordinal` (A1 = 0 … H8 = 63). */
    val pieces: List<Piece> = List(64) { Piece.NONE },
    val playerSide: Side = Side.WHITE,
    val sideToMove: Side = Side.WHITE,
    val selected: Square? = null,
    val legalTargets: Set<Square> = emptySet(),
    val lastMove: Pair<Square, Square>? = null,
    val checkedKing: Square? = null,
    val pendingPromotion: Pair<Square, Square>? = null,
    /** Move proposed but not yet confirmed (only with `confirmMoves`). */
    val pendingMove: Pair<Square, Square>? = null,
    /** Takebacks still available, null = unlimited. */
    val takebacksLeft: Int? = null,
    val resigned: Boolean = false,
    val sanMoves: List<String> = emptyList(),
    val thinking: Boolean = false,
    val result: Result = Result.ONGOING,
    val statusText: String = "",
    val flipped: Boolean = false,
    val engineError: String? = null,
    /** Remaining time per side (ms), null without a clock. */
    val whiteMs: Long? = null,
    val blackMs: Long? = null,
    /** Side whose clock is currently running. */
    val runningClock: Side? = null,
) {
    val canUndo: Boolean
        get() = sanMoves.isNotEmpty() && result == Result.ONGOING && (takebacksLeft == null || takebacksLeft > 0)
    val isPlayerTurn: Boolean get() = sideToMove == playerSide && result == Result.ONGOING && !thinking
    val engineSide: Side get() = if (playerSide == Side.WHITE) Side.BLACK else Side.WHITE
    fun clockMs(side: Side): Long? = if (side == Side.WHITE) whiteMs else blackMs
}

class ChessViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private var engine: ChessEngine? = null
    /** Serialises engine start / switch / configure sequences. */
    private val engineLock = Mutex()
    private val repo = GameRepository(app)
    private val board = Board()
    private val moveList = MoveList()
    private val uciMoves = mutableListOf<String>()

    /** Bumped on every new game / undo: invalidates in-flight searches. */
    private var generation = 0
    private var engineReady = false

    /** True once a game has been started or resumed in this ViewModel. */
    var hasGame = false
        private set

    // Clock: remaining time frozen at the start of the turn + when the turn started.
    private var whiteBaseMs = 0L
    private var blackBaseMs = 0L
    private var turnStartedAt = 0L
    private var clockJob: Job? = null
    private var clockPaused = false
    private var flagged: Side? = null
    private var resigned = false
    private var takebacksUsed = 0

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state

    init {
        publish()
    }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: GameConfig) {
        resetGame(config, config.playerSide ?: if (Random.nextBoolean()) Side.WHITE else Side.BLACK)
        val initial = config.timeControl.startingMs
        whiteBaseMs = initial ?: 0L
        blackBaseMs = initial ?: 0L
        finishLoad()
    }

    /**
     * Resumes the saved game, if any. Returns false when there is nothing to resume.
     * A game already held by this ViewModel is kept as is.
     */
    fun resumeGame(): Boolean {
        if (hasGame) {
            resumeClock()
            return true
        }
        val saved = repo.loadGame() ?: return false
        resetGame(saved.config, saved.playerSide)
        for (uci in saved.uciMoves) {
            val move = parseUci(uci) ?: break
            board.doMove(move)
            moveList.add(move)
            uciMoves += uci
        }
        whiteBaseMs = saved.whiteMs ?: 0L
        blackBaseMs = saved.blackMs ?: 0L
        finishLoad()
        return true
    }

    fun onSquareTapped(square: Square) {
        val s = _state.value
        if (!s.isPlayerTurn || s.pendingPromotion != null) return

        val selected = s.selected
        if (selected != null && square in s.legalTargets) {
            // With move confirmation on, the first tap only proposes; the same tap again plays.
            if (s.config.confirmMoves && s.pendingMove != selected to square) {
                _state.update { it.copy(pendingMove = selected to square) }
                publish()
                return
            }
            attemptMove(selected, square)
            return
        }

        // Select / re-select one of the player's pieces.
        val piece = board.getPiece(square)
        if (piece != Piece.NONE && piece.pieceSide == s.playerSide) {
            val targets = board.legalMoves().filter { it.from == square }.map { it.to }.toSet()
            _state.update { it.copy(selected = square, legalTargets = targets, pendingMove = null) }
        } else {
            _state.update { it.copy(selected = null, legalTargets = emptySet(), pendingMove = null) }
        }
        publish()
    }

    /** Plays [from]-[to], asking for the promotion piece when needed. */
    private fun attemptMove(from: Square, to: Square) {
        val s = _state.value
        val promotionRank = if (s.playerSide == Side.WHITE) 7 else 0
        if (board.getPiece(from).pieceType == PieceType.PAWN && to.rank.ordinal == promotionRank) {
            if (s.config.autoQueen) {
                playPlayerMove(Move(from, to, Piece.make(s.playerSide, PieceType.QUEEN)))
            } else {
                _state.update { it.copy(pendingPromotion = from to to, pendingMove = null) }
            }
        } else {
            playPlayerMove(Move(from, to))
        }
    }

    /** Plays the move proposed under "confirm moves". */
    fun confirmPendingMove() {
        val (from, to) = _state.value.pendingMove ?: return
        if (!_state.value.isPlayerTurn) return
        attemptMove(from, to)
    }

    fun cancelPendingMove() {
        _state.update { it.copy(pendingMove = null, selected = null, legalTargets = emptySet()) }
        publish()
    }

    fun promote(type: PieceType) {
        val (from, to) = _state.value.pendingPromotion ?: return
        _state.update { it.copy(pendingPromotion = null) }
        playPlayerMove(Move(from, to, Piece.make(_state.value.playerSide, type)))
    }

    fun cancelPromotion() {
        _state.update { it.copy(pendingPromotion = null, selected = null, legalTargets = emptySet()) }
    }

    /** Takes back the player's last move (and the engine's reply, if any). */
    fun undo() {
        val s = _state.value
        if (uciMoves.isEmpty() || s.result != Result.ONGOING || !s.canUndo) return
        cancelSearch()
        undoOne()
        if (board.sideToMove != s.playerSide && uciMoves.isNotEmpty()) undoOne()
        takebacksUsed++
        _state.update {
            it.copy(selected = null, legalTargets = emptySet(), pendingPromotion = null, pendingMove = null)
        }
        // The clock restarts for the side to move; time already spent is not refunded.
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove() // e.g. player has black and everything was taken back
    }

    /** The player gives up; the game ends immediately. */
    fun resign() {
        if (_state.value.result != Result.ONGOING || !hasGame) return
        cancelSearch()
        resigned = true
        stopClock()
        _state.update { it.copy(selected = null, legalTargets = emptySet(), pendingMove = null, pendingPromotion = null) }
        publish()
        persist()
    }

    /** New game with the same settings and the colours swapped. */
    fun rematch() {
        val s = _state.value
        startGame(s.config.copy(playerSide = s.engineSide))
    }

    fun flipBoard() = _state.update { it.copy(flipped = !it.flipped) }

    /** Freezes the running clock (app in background, screen left). */
    fun pauseClock() {
        if (!hasClock() || clockPaused) return
        clockPaused = true
        val running = _state.value.runningClock
        if (running != null) {
            val left = currentMs(running) ?: 0L
            if (running == Side.WHITE) whiteBaseMs = left else blackBaseMs = left
        }
        stopClock()
        persist()
    }

    /** Restarts the clock frozen by [pauseClock]. */
    fun resumeClock() {
        if (!clockPaused) return
        clockPaused = false
        restartTurnClock()
        publish()
    }

    // ---------------------------------------------------------------- game logic

    private fun resetGame(config: GameConfig, side: Side) {
        cancelSearch()
        stopClock()
        clockPaused = false
        board.loadFromFen(START_FEN)
        moveList.clear()
        uciMoves.clear()
        flagged = null
        resigned = false
        takebacksUsed = 0
        hasGame = true
        _state.update {
            it.copy(
                config = config,
                playerSide = side,
                flipped = side == Side.BLACK,
                selected = null,
                legalTargets = emptySet(),
                pendingPromotion = null,
                runningClock = null,
            )
        }
    }

    /** Common tail of [startGame] / [resumeGame]: clocks, engine options, first engine move. */
    private fun finishLoad() {
        restartTurnClock()
        publish()
        persist()
        val config = _state.value.config
        viewModelScope.launch {
            try {
                engineLock.withLock {
                    val eng = ensureEngine(config.engine, config.strength)
                    eng.configure(config.strength)
                    eng.newGame()
                }
            } catch (e: Exception) {
                _state.update { it.copy(engineError = e.message ?: e.toString()) }
                publish()
                return@launch
            }
            maybeEngineMove()
        }
    }

    /** Returns a running engine of [kind] (with the network for [strength]), starting it if needed. */
    private suspend fun ensureEngine(kind: EngineKind, strength: Int?): ChessEngine {
        engine?.let { if (it.kind == kind && it.weights == kind.weightsFor(strength) && it.isRunning) return it }
        engineReady = false
        engine?.quit()
        _state.update { it.copy(engineError = null) }
        publish()
        val eng = ChessEngine.create(app, kind, strength)
        engine = eng
        eng.start()
        engineReady = true
        publish()
        return eng
    }

    private fun playPlayerMove(move: Move) {
        applyMove(move)
        _state.update { it.copy(selected = null, legalTargets = emptySet(), pendingMove = null) }
        publish()
        persist()
        maybeEngineMove()
    }

    private fun applyMove(move: Move) {
        val mover = board.sideToMove
        board.doMove(move)
        moveList.add(move)
        uciMoves += move.toUci()
        onMovePlayed(mover)
    }

    private fun undoOne() {
        board.undoMove()
        moveList.removeLast()
        uciMoves.removeLast()
    }

    private fun maybeEngineMove() {
        val eng = engine ?: return
        if (!engineReady) return
        if (board.sideToMove == _state.value.playerSide || isGameOver()) return

        val gen = ++generation
        val moves = uciMoves.toList()
        val moveTime = engineMoveTimeMs()
        val nodes = eng.nodesFor(_state.value.config.strength)
        val depth = eng.depthFor(_state.value.config.strength)
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            val uci = try {
                eng.bestMove(moves, moveTime, nodes, depth)
            } catch (e: Exception) {
                _state.update { it.copy(engineError = e.message, thinking = false) }
                publish()
                return@launch
            }
            if (gen != generation) return@launch // game changed meanwhile
            _state.update { it.copy(thinking = false) }
            val move = uci?.let { parseUci(it) }
            if (move != null && !isGameOver()) {
                applyMove(move)
            }
            publish()
            persist()
        }
    }

    /** Engine thinking time: the Elo-based budget, capped by its own clock. */
    private fun engineMoveTimeMs(): Int {
        val cfg = _state.value.config
        val base = ((engine?.moveTimeMs(cfg.strength) ?: 1000) * cfg.thinking.factor).toLong()
        val remaining = currentMs(board.sideToMove) ?: return base.toInt()
        val budget = when (val tc = _state.value.config.timeControl) {
            is TimeControl.PerMove -> tc.perMoveMs / 2
            is TimeControl.Fischer -> remaining / 10 + tc.incrementMs / 2
            else -> remaining / 10
        }
        return minOf(base, budget).coerceAtLeast(50L).toInt()
    }

    private fun cancelSearch() {
        generation++
        if (_state.value.thinking) engine?.stop()
        _state.update { it.copy(thinking = false) }
    }

    private fun isGameOver() =
        resigned || flagged != null || board.isMated || board.isStaleMate || board.isDraw

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        if (!hasGame) return
        if (isGameOver()) {
            repo.clearGame()
            return
        }
        repo.saveGame(
            SavedGame(
                config = _state.value.config,
                playerSide = _state.value.playerSide,
                uciMoves = uciMoves.toList(),
                whiteMs = currentMs(Side.WHITE),
                blackMs = currentMs(Side.BLACK),
            ),
        )
    }

    // ---------------------------------------------------------------- clock

    private fun hasClock() = _state.value.config.timeControl != TimeControl.None

    /** Current remaining time of a side, accounting for the turn in progress. */
    private fun currentMs(side: Side): Long? {
        if (!hasClock()) return null
        val base = if (side == Side.WHITE) whiteBaseMs else blackBaseMs
        val running = _state.value.runningClock
        val elapsed = if (running == side) SystemClock.elapsedRealtime() - turnStartedAt else 0L
        return (base - elapsed).coerceAtLeast(0L)
    }

    /** Called after every move: freezes the mover's time and starts the other side's clock. */
    private fun onMovePlayed(mover: Side) {
        if (!hasClock()) return
        val tc = _state.value.config.timeControl
        val now = SystemClock.elapsedRealtime()
        if (_state.value.runningClock == mover) {
            val spent = now - turnStartedAt
            var left = (if (mover == Side.WHITE) whiteBaseMs else blackBaseMs) - spent
            left = when (tc) {
                is TimeControl.Fischer -> left + tc.incrementMs
                is TimeControl.PerMove -> tc.perMoveMs
                else -> left
            }.coerceAtLeast(0L)
            if (mover == Side.WHITE) whiteBaseMs = left else blackBaseMs = left
        } else if (tc is TimeControl.PerMove) {
            // First move of the game: nothing was consumed, but the budget still resets.
            if (mover == Side.WHITE) whiteBaseMs = tc.perMoveMs else blackBaseMs = tc.perMoveMs
        }
        restartTurnClock()
    }

    /** Starts the clock of the side to move, unless paused / no moves yet / game over. */
    private fun restartTurnClock() {
        if (!hasClock() || clockPaused || uciMoves.isEmpty() || isGameOver()) {
            stopClock()
            return
        }
        turnStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(runningClock = board.sideToMove) }
        ensureClockTicking()
    }

    private fun ensureClockTicking() {
        if (clockJob?.isActive == true) return
        clockJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                val running = _state.value.runningClock ?: break
                val left = currentMs(running) ?: break
                if (left <= 0L) {
                    flagged = running
                    if (running == Side.WHITE) whiteBaseMs = 0 else blackBaseMs = 0
                    cancelSearch()
                    _state.update { it.copy(runningClock = null) }
                    publish()
                    persist()
                    break
                }
                publishClocks()
            }
        }
    }

    private fun stopClock() {
        clockJob?.cancel()
        clockJob = null
        _state.update { it.copy(runningClock = null) }
    }

    private fun publishClocks() {
        if (!hasClock()) return
        _state.update { it.copy(whiteMs = currentMs(Side.WHITE), blackMs = currentMs(Side.BLACK)) }
    }

    // ---------------------------------------------------------------- helpers

    private fun parseUci(uci: String): Move? {
        if (uci.length < 4) return null
        val from = Square.fromValue(uci.substring(0, 2).uppercase())
        val to = Square.fromValue(uci.substring(2, 4).uppercase())
        val promo = uci.getOrNull(4)?.let { c ->
            val type = when (c.lowercaseChar()) {
                'q' -> PieceType.QUEEN
                'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP
                'n' -> PieceType.KNIGHT
                else -> return null
            }
            Piece.make(board.sideToMove, type)
        }
        val move = if (promo != null) Move(from, to, promo) else Move(from, to)
        return board.legalMoves().firstOrNull { it == move }
    }

    private fun Move.toUci(): String {
        val p = if (promotion != Piece.NONE) promotion.pieceType.sanSymbol.lowercase() else ""
        return from.value().lowercase() + to.value().lowercase() + p
    }

    /** Recomputes the snapshot from the board. */
    private fun publish() {
        val player = _state.value.playerSide
        val result = when {
            resigned -> Result.ENGINE_WINS
            flagged != null -> if (flagged == player) Result.ENGINE_WINS else Result.PLAYER_WINS
            board.isMated -> if (board.sideToMove == player) Result.ENGINE_WINS else Result.PLAYER_WINS
            board.isStaleMate || board.isDraw -> Result.DRAW
            else -> Result.ONGOING
        }
        val inCheck = board.isKingAttacked
        val thinking = _state.value.thinking
        val engineName = _state.value.config.engine.label
        val status = when {
            _state.value.engineError != null -> "Engine error: ${_state.value.engineError}"
            resigned -> "You resigned — $engineName wins."
            flagged == player -> "Time's up — $engineName wins."
            flagged != null -> "Time's up — you win!"
            result == Result.PLAYER_WINS -> "Checkmate — you win!"
            result == Result.ENGINE_WINS -> "Checkmate — $engineName wins."
            board.isStaleMate -> "Stalemate — draw."
            result == Result.DRAW -> "Draw."
            !engineReady -> "Starting $engineName…"
            thinking -> "$engineName is thinking…"
            _state.value.pendingMove != null -> "Tap the square again or Confirm to play."
            inCheck -> "Check! Your move."
            else -> "Your move (${if (player == Side.WHITE) "white" else "black"})."
        }
        val lastMove = moveList.lastOrNull()?.let { it.from to it.to }
        _state.update {
            it.copy(
                pieces = List(64) { i -> board.getPiece(Square.squareAt(i)) },
                sideToMove = board.sideToMove,
                lastMove = lastMove,
                checkedKing = if (inCheck) board.getKingSquare(board.sideToMove) else null,
                sanMoves = moveList.toSanArray().toList(),
                result = result,
                statusText = status,
                takebacksLeft = it.config.takebacks.limit?.let { limit -> (limit - takebacksUsed).coerceAtLeast(0) },
                resigned = resigned,
                whiteMs = currentMs(Side.WHITE),
                blackMs = currentMs(Side.BLACK),
            )
        }
    }

    override fun onCleared() {
        engine?.quit()
    }

    companion object {
        private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
