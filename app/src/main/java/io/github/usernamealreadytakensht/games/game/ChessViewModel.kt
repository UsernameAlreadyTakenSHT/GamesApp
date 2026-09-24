package io.github.usernamealreadytakensht.games.game

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.usernamealreadytakensht.games.engine.ChessEngine
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
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
    val canUndo: Boolean get() = sanMoves.isNotEmpty() && result == Result.ONGOING
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
    /** Rules and move history; replaced on every new game (standard or Chess960). */
    private var session: ChessSession = StandardChess()
    private val uciMoves: List<String> get() = session.uciMoves

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

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state

    init {
        publish()
    }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: GameConfig, startFen: String? = null) {
        val fen = if (config.chess960) startFen ?: Chess960.startFen(Random.nextInt(960)) else null
        resetGame(config, config.playerSide ?: if (Random.nextBoolean()) Side.WHITE else Side.BLACK, fen)
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
        resetGame(saved.config, saved.playerSide, saved.startFen)
        for (uci in saved.uciMoves) {
            val move = session.parseUci(uci) ?: break
            session.play(move)
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
            val moves = movesFrom(selected).filter { it.value == square }.map { it.key }
            if (moves.any { it.promotion != Piece.NONE }) {
                _state.update { it.copy(pendingPromotion = selected to square) }
            } else {
                playPlayerMove(moves.first())
            }
            return
        }

        // Select / re-select one of the player's pieces.
        val piece = session.pieceAt(square)
        if (piece != Piece.NONE && piece.pieceSide == s.playerSide) {
            _state.update { it.copy(selected = square, legalTargets = movesFrom(square).values.toSet()) }
        } else {
            _state.update { it.copy(selected = null, legalTargets = emptySet()) }
        }
    }

    fun promote(type: PieceType) {
        val (from, to) = _state.value.pendingPromotion ?: return
        _state.update { it.copy(pendingPromotion = null) }
        playPlayerMove(ChessMove(from, to, Piece.make(_state.value.playerSide, type)))
    }

    /**
     * Legal moves of the piece on [from], each with the square to tap for it. A castle is
     * played by tapping the rook, or the king's destination when no other king move goes there.
     */
    private fun movesFrom(from: Square): Map<ChessMove, Square> {
        val moves = session.legalMoves().filter { it.from == from }
        val plain = moves.filter { !it.isCastle }.map { it.to }.toSet()
        val out = LinkedHashMap<ChessMove, Square>()
        for (m in moves) {
            out[m] = m.to
            if (m.isCastle && m.castleKingTo != m.to && m.castleKingTo !in plain) out[m.copy(to = m.castleKingTo)] = m.castleKingTo
        }
        return out
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
        if (session.sideToMove != s.playerSide && uciMoves.isNotEmpty()) undoOne()
        _state.update {
            it.copy(selected = null, legalTargets = emptySet(), pendingPromotion = null)
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
        _state.update { it.copy(selected = null, legalTargets = emptySet(), pendingPromotion = null) }
        publish()
        persist()
    }

    /** New game with the same settings (and Chess960 start position) and the colours swapped. */
    fun rematch() {
        val s = _state.value
        startGame(s.config.copy(playerSide = s.engineSide), session.startFen)
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

    private fun resetGame(config: GameConfig, side: Side, startFen: String?) {
        cancelSearch()
        stopClock()
        clockPaused = false
        session = if (config.chess960 && startFen != null) Chess960(startFen) else StandardChess()
        flagged = null
        resigned = false
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

    private fun playPlayerMove(move: ChessMove) {
        applyMove(move)
        _state.update { it.copy(selected = null, legalTargets = emptySet()) }
        publish()
        persist()
        maybeEngineMove()
    }

    private fun applyMove(move: ChessMove) {
        val mover = session.sideToMove
        // The king-square alias of a castle (see movesFrom) is played as the real move.
        val real = if (move.isCastle) move.copy(to = move.castleRook!!) else move
        session.play(real)
        onMovePlayed(mover)
    }

    private fun undoOne() {
        session.undo()
    }

    private fun maybeEngineMove() {
        val eng = engine ?: return
        if (!engineReady) return
        if (session.sideToMove == _state.value.playerSide || isGameOver()) return

        val gen = ++generation
        val moves = uciMoves.toList()
        val moveTime = engineMoveTimeMs()
        val nodes = eng.nodesFor(_state.value.config.strength)
        val depth = eng.depthFor(_state.value.config.strength)
        val startFen = if (session.chess960) session.startFen else null
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            val uci = try {
                eng.bestMove(moves, moveTime, nodes, depth, startFen)
            } catch (e: Exception) {
                _state.update { it.copy(engineError = e.message, thinking = false) }
                publish()
                return@launch
            }
            if (gen != generation) return@launch // game changed meanwhile
            _state.update { it.copy(thinking = false) }
            val move = uci?.let { session.parseUci(it) }
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
        val remaining = currentMs(session.sideToMove) ?: return base.toInt()
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
        resigned || flagged != null || session.isMate || session.isDraw || session.isStalemate

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
                startFen = if (session.chess960) session.startFen else null,
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
        _state.update { it.copy(runningClock = session.sideToMove) }
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

    /** Recomputes the snapshot from the board. */
    private fun publish() {
        val player = _state.value.playerSide
        val result = when {
            resigned -> Result.ENGINE_WINS
            flagged != null -> if (flagged == player) Result.ENGINE_WINS else Result.PLAYER_WINS
            session.isMate -> if (session.sideToMove == player) Result.ENGINE_WINS else Result.PLAYER_WINS
            session.isStalemate || session.isDraw -> Result.DRAW
            else -> Result.ONGOING
        }
        val inCheck = session.inCheck
        val thinking = _state.value.thinking
        val engineName = _state.value.config.engine.label
        val status = when {
            _state.value.engineError != null -> "Engine error: ${_state.value.engineError}"
            resigned -> "You resigned — $engineName wins."
            flagged == player -> "Time's up — $engineName wins."
            flagged != null -> "Time's up — you win!"
            result == Result.PLAYER_WINS -> "Checkmate — you win!"
            result == Result.ENGINE_WINS -> "Checkmate — $engineName wins."
            result == Result.DRAW && session.isStalemate -> "Stalemate — draw."
            result == Result.DRAW -> "Draw."
            !engineReady -> "Starting $engineName…"
            thinking -> "$engineName is thinking…"
            inCheck -> "Check! Your move."
            else -> "Your move (${if (player == Side.WHITE) "white" else "black"})."
        }
        _state.update {
            it.copy(
                pieces = List(64) { i -> session.pieceAt(Square.squareAt(i)) },
                sideToMove = session.sideToMove,
                lastMove = session.lastMove,
                checkedKing = if (inCheck) session.kingSquare(session.sideToMove) else null,
                sanMoves = session.sanMoves,
                result = result,
                statusText = status,
                resigned = resigned,
                whiteMs = currentMs(Side.WHITE),
                blackMs = currentMs(Side.BLACK),
            )
        }
    }

    override fun onCleared() {
        engine?.quit()
    }

}
