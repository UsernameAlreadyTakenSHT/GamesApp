package io.github.usernamealreadytakensht.games.game

import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import io.github.usernamealreadytakensht.games.game.record.ExportedGame
import io.github.usernamealreadytakensht.games.game.record.Exports
import io.github.usernamealreadytakensht.games.game.record.GameRecord
import io.github.usernamealreadytakensht.games.game.record.HistoryRepository
import io.github.usernamealreadytakensht.games.game.record.PlayerResult
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

fun colorName(side: Side): String = if (side == Side.WHITE) "White" else "Black"

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
    /** Squares of the suggested move, shown until a move is played. */
    val hint: Set<Square> = emptySet(),
    val hintText: String? = null,
    val hintLoading: Boolean = false,
    val flipped: Boolean = false,
    val engineError: String? = null,
    /** Remaining time per side (ms), null without a clock. */
    val whiteMs: Long? = null,
    val blackMs: Long? = null,
    /** Side whose clock is currently running. */
    val runningClock: Side? = null,
) {
    val canUndo: Boolean get() = sanMoves.isNotEmpty() && result == Result.ONGOING
    val isPlayerTurn: Boolean get() = (config.twoPlayers || sideToMove == playerSide) && result == Result.ONGOING && !thinking
    val engineSide: Side get() = if (playerSide == Side.WHITE) Side.BLACK else Side.WHITE
    /** The side whose pieces the screen's user moves now: theirs, or either side with two players. */
    val mover: Side get() = if (config.twoPlayers) sideToMove else playerSide
    /** Side shown at the bottom of the board (and of the clocks). */
    val bottomSide: Side get() = if (config.twoPlayers) (if (flipped) Side.BLACK else Side.WHITE) else playerSide
    val topSide: Side get() = if (bottomSide == Side.WHITE) Side.BLACK else Side.WHITE
    /** Name for a side's bar: you / the engine, or the colour with two players. */
    fun sideLabel(side: Side): String = when {
        config.twoPlayers -> colorName(side)
        side == playerSide -> "You"
        else -> config.opponentLabel
    }
    fun clockMs(side: Side): Long? = if (side == Side.WHITE) whiteMs else blackMs
    /** Whether [side] lost the finished game (its clock turns red). */
    fun lost(side: Side): Boolean =
        (result == Result.PLAYER_WINS && side != playerSide) || (result == Result.ENGINE_WINS && side == playerSide)
}

class ChessViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private var engine: ChessEngine? = null
    /** Serialises engine start / switch / configure sequences. */
    private val engineLock = Mutex()
    private val repo = GameRepository(app)
    private val records = HistoryRepository(app)
    /** Whether the finished game was already added to the history. */
    private var recorded = false
    private var hintJob: Job? = null
    /** Rules and move history; replaced on every new game (standard or Chess960). */
    private var session: ChessSession = StandardChess()
    private val uciMoves: List<String> get() = session.uciMoves

    /** Bumped on every new game / undo: invalidates in-flight searches. */
    private var generation = 0
    private var engineReady = false
    /** True while [finishLoad] starts the engine: searches wait for it to finish. */
    private var engineStarting = false
    /** Bumped by every [finishLoad]: only the latest one clears [engineStarting] and moves. */
    private var loadId = 0
    /** Set while the game screen is not shown: no engine runs then. */
    private var released = false

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
    /** Who resigned: the player, or the side to move in a two-player game. */
    private var resignedSide: Side = Side.WHITE

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state

    init {
        publish()
    }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: GameConfig, startFen: String? = null) {
        val fen = if (config.chess960) startFen ?: Chess960.startFen(Random.nextInt(960)) else null
        val side = if (config.twoPlayers) Side.WHITE else config.playerSide ?: if (Random.nextBoolean()) Side.WHITE else Side.BLACK
        resetGame(config, side, fen)
        val initial = config.timeControl.startingMs
        whiteBaseMs = initial ?: 0L
        blackBaseMs = initial ?: 0L
        finishLoad()
    }

    /**
     * Resumes the saved game, if any. Returns false when there is nothing to resume.
     * A game already held by this ViewModel is kept as is.
     */
    fun resumeGame(): Boolean = try {
        loadSavedGame()
    } catch (e: Exception) {
        // A save this version cannot replay (corrupted, or from an incompatible build) would
        // crash every Resume: drop it and go back to the menu instead.
        android.util.Log.w("GameResume", "Discarding an unreadable saved game", e)
        repo.clearGame()
        hasGame = false
        false
    }

    private fun loadSavedGame(): Boolean {
        if (hasGame) {
            released = false
            resumeClock()
            maybeEngineMove()
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
        if (piece != Piece.NONE && piece.pieceSide == s.mover) {
            _state.update { it.copy(selected = square, legalTargets = movesFrom(square).values.toSet()) }
        } else {
            _state.update { it.copy(selected = null, legalTargets = emptySet()) }
        }
    }

    fun promote(type: PieceType) {
        val (from, to) = _state.value.pendingPromotion ?: return
        _state.update { it.copy(pendingPromotion = null) }
        playPlayerMove(ChessMove(from, to, Piece.make(_state.value.mover, type)))
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
            // No alias when the king does not move (e.g. king g1, rook h1): its own square would
            // become a target, and tapping the selected king again would castle.
            if (m.isCastle && m.castleKingTo != m.to && m.castleKingTo != m.from && m.castleKingTo !in plain) {
                out[m.copy(to = m.castleKingTo)] = m.castleKingTo
            }
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
        clearHint()
        undoOne()
        // Against the engine, its reply goes too; with two players, one move at a time.
        if (!s.config.twoPlayers && session.sideToMove != s.playerSide && uciMoves.isNotEmpty()) undoOne()
        _state.update {
            it.copy(selected = null, legalTargets = emptySet(), pendingPromotion = null, engineError = null)
        }
        // The clock restarts for the side to move; time already spent is not refunded.
        chargeRunningClock()
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove() // e.g. player has black and everything was taken back
    }

    /**
     * The game screen was left: stop the engine process so it does not sit in memory.
     * Coming back (resume) restarts it with the next search.
     */
    fun releaseEngine() {
        released = true
        cancelSearch()
        viewModelScope.launch { engineLock.withLock { engine?.quit() } }
    }

    /** Asks the engine again after an error (it is restarted if it died). */
    fun retryEngine() {
        if (_state.value.engineError == null) return
        _state.update { it.copy(engineError = null) }
        publish()
        maybeEngineMove()
    }

    /** The player gives up; the game ends immediately. */
    fun resign() {
        if (_state.value.result != Result.ONGOING || !hasGame) return
        cancelSearch()
        resigned = true
        resignedSide = if (_state.value.config.twoPlayers) session.sideToMove else _state.value.playerSide
        stopClock()
        _state.update { it.copy(selected = null, legalTargets = emptySet(), pendingPromotion = null) }
        publish()
        persist()
    }

    /** New game with the same settings (and Chess960 start position) and the colours swapped. */
    fun rematch() {
        val s = _state.value
        startGame(if (s.config.twoPlayers) s.config else s.config.copy(playerSide = s.engineSide), session.startFen)
    }

    fun flipBoard() = _state.update { it.copy(flipped = !it.flipped) }

    /** Freezes the running side's remaining time as its base (the turn's time is spent). */
    private fun chargeRunningClock() {
        val running = _state.value.runningClock ?: return
        val left = currentMs(running) ?: 0L
        if (running == Side.WHITE) whiteBaseMs = left else blackBaseMs = left
    }

    /** Freezes the running clock (app in background, screen left). */
    fun pauseClock() {
        if (!hasClock() || clockPaused) return
        clockPaused = true
        chargeRunningClock()
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
        recorded = false
        clearHint()
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
        _state.update { it.copy(engineError = null) }
        // A start still running for an earlier game must not report into this one.
        val load = ++loadId
        // Two players: no engine now; Hint starts one when asked.
        if (config.twoPlayers) {
            engineStarting = false
            return
        }
        engineStarting = true
        released = false
        viewModelScope.launch {
            try {
                engineLock.withLock {
                    val eng = ensureEngine(config.engine, config.strength)
                    eng.configure(config.strength)
                    eng.newGame()
                }
            } catch (e: Exception) {
                if (load != loadId) return@launch
                _state.update { it.copy(engineError = e.message ?: e.toString()) }
                publish()
                return@launch
            } finally {
                if (load == loadId) engineStarting = false
            }
            if (load == loadId) maybeEngineMove()
        }
    }

    /**
     * The engine for the current settings, restarted when its process died (killed in the
     * background, or by the watchdog) or when [restart] is asked after a failed search.
     */
    private suspend fun liveEngine(config: GameConfig, restart: Boolean): ChessEngine {
        if (restart) engine?.quit()
        val before = engine
        val eng = ensureEngine(config.engine, config.strength)
        if (eng !== before) {
            eng.configure(config.strength)
            eng.newGame()
        }
        return eng
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
        stopHint()
        applyMove(move)
        _state.update { it.copy(selected = null, legalTargets = emptySet()) }
        publish()
        persist()
        maybeEngineMove()
    }

    private fun applyMove(move: ChessMove) {
        clearHint()
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
        // The first start reports its own failure; later searches restart the engine themselves.
        if (released || engineStarting || _state.value.engineError != null) return
        if (_state.value.config.twoPlayers) return
        if (session.sideToMove == _state.value.playerSide || isGameOver()) return

        val gen = ++generation
        val config = _state.value.config
        val moves = uciMoves.toList()
        val moveTime = engineMoveTimeMs()
        val startFen = if (session.chess960) session.startFen else null
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            hintJob?.join() // a hint search finishes (and restores the strength) first
            var move: ChessMove? = null
            var failure: String? = null
            // A failed search (dead or hung engine, unusable reply) gets one retry on a fresh engine.
            for (attempt in 0..1) {
                try {
                    val eng = engineLock.withLock { liveEngine(config, restart = attempt > 0) }
                    // Cancelled while waiting for the hint or the engine: the "stop" went out
                    // before this search, which would run its full time for nothing.
                    if (gen != generation) return@launch
                    val uci = eng.bestMove(moves, moveTime, eng.nodesFor(config.strength), eng.depthFor(config.strength), startFen)
                    if (gen != generation) return@launch // game changed meanwhile
                    move = uci?.let { session.parseUci(it) }
                    if (move != null) break
                    failure = "${config.engine.label} gave no legal move (${uci ?: "none"})"
                } catch (e: Exception) {
                    if (gen != generation) return@launch
                    failure = e.message ?: e.toString()
                }
            }
            _state.update { it.copy(thinking = false, engineError = if (move == null) failure else null) }
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
            // What is left of the move's budget: less than the full budget after an undo
            // charged the engine's thinking time to it.
            is TimeControl.PerMove -> remaining / 2
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
            // Out of time before the 100 ms tick noticed: the flag fell, the increment (or the
            // per-move reset) does not save it.
            if (left <= 0L) {
                flagged = mover
                if (mover == Side.WHITE) whiteBaseMs = 0 else blackBaseMs = 0
                stopClock()
                return
            }
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
        val whiteMsNow = currentMs(Side.WHITE)
        val blackMsNow = currentMs(Side.BLACK)
        if (clockShown(whiteMsNow) != clockShown(_state.value.whiteMs) || clockShown(blackMsNow) != clockShown(_state.value.blackMs)) {
            _state.update { it.copy(whiteMs = whiteMsNow, blackMs = blackMsNow) }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Recomputes the snapshot from the board. */
    private fun publish() {
        val player = _state.value.playerSide
        val result = when {
            resigned -> if (resignedSide == player) Result.ENGINE_WINS else Result.PLAYER_WINS
            flagged != null -> if (flagged == player) Result.ENGINE_WINS else Result.PLAYER_WINS
            session.isMate -> if (session.sideToMove == player) Result.ENGINE_WINS else Result.PLAYER_WINS
            session.isStalemate || session.isDraw -> Result.DRAW
            else -> Result.ONGOING
        }
        val inCheck = session.inCheck
        val thinking = _state.value.thinking
        val engineName = _state.value.config.engine.label
        val toMove = colorName(session.sideToMove)
        val other = colorName(if (session.sideToMove == Side.WHITE) Side.BLACK else Side.WHITE)
        val status = if (_state.value.config.twoPlayers) when {
            resigned -> "${colorName(resignedSide)} resigned — ${colorName(if (resignedSide == Side.WHITE) Side.BLACK else Side.WHITE)} wins."
            flagged != null -> "Time's up — ${colorName(if (flagged == Side.WHITE) Side.BLACK else Side.WHITE)} wins."
            session.isMate -> "Checkmate — $other wins."
            result == Result.DRAW && session.isStalemate -> "Stalemate — draw."
            result == Result.DRAW -> "Draw."
            inCheck -> "Check! $toMove to move."
            else -> "$toMove to move."
        } else when {
            // The game's end first: it is what the history and the PGN record as the reason.
            resigned -> "You resigned — $engineName wins."
            flagged == player -> "Time's up — $engineName wins."
            flagged != null -> "Time's up — you win!"
            result == Result.PLAYER_WINS -> "Checkmate — you win!"
            result == Result.ENGINE_WINS -> "Checkmate — $engineName wins."
            result == Result.DRAW && session.isStalemate -> "Stalemate — draw."
            result == Result.DRAW -> "Draw."
            _state.value.engineError != null -> "Engine error: ${_state.value.engineError}"
            !engineReady -> "Starting $engineName…"
            thinking -> "$engineName is thinking…"
            inCheck -> "Check! Your move."
            else -> "Your move (${if (player == Side.WHITE) "white" else "black"})."
        }
        // A hint shown when the game ends (resign, flag) goes: the status must give the result.
        if (result != Result.ONGOING) clearHint()
        _state.update {
            it.copy(
                pieces = List(64) { i -> session.pieceAt(Square.squareAt(i)) },
                sideToMove = session.sideToMove,
                lastMove = session.lastMove,
                checkedKing = if (inCheck) session.kingSquare(session.sideToMove) else null,
                sanMoves = session.sanMoves,
                result = result,
                statusText = _state.value.hintText?.let { h -> "Hint: $h" } ?: status,
                resigned = resigned,
                whiteMs = currentMs(Side.WHITE),
                blackMs = currentMs(Side.BLACK),
            )
        }
        recordIfFinished()
    }

    override fun onCleared() {
        engine?.quit()
    }

    // ---------------------------------------------------------------- export & history

    /** The current game as a file (PGN), for the Share button and the history. */
    fun exportGame(): ExportedGame {
        val s = _state.value
        val now = System.currentTimeMillis()
        val white = if (s.config.twoPlayers) "White player" else if (s.playerSide == Side.WHITE) "You" else s.config.opponentLabel
        val black = if (s.config.twoPlayers) "Black player" else if (s.playerSide == Side.BLACK) "You" else s.config.opponentLabel
        val result = when (s.result) {
            Result.PLAYER_WINS -> if (s.playerSide == Side.WHITE) "1-0" else "0-1"
            Result.ENGINE_WINS -> if (s.playerSide == Side.WHITE) "0-1" else "1-0"
            Result.DRAW -> "1/2-1/2"
            Result.ONGOING -> "*"
        }
        val tags = mutableListOf(
            "Event" to if (session.chess960) "Casual Chess960 game" else "Casual game",
            "Site" to "GamesApp", "Date" to Exports.pgnDate(now), "Round" to "-",
            "White" to white, "Black" to black, "Result" to result,
        )
        if (session.chess960) {
            tags += "Variant" to "Chess960"
            tags += "SetUp" to "1"
            tags += "FEN" to session.startFen
        }
        tags += "TimeControl" to when (val tc = s.config.timeControl) {
            TimeControl.None -> "-"
            is TimeControl.SuddenDeath -> "${tc.initialMs / 1000}"
            is TimeControl.Fischer -> "${tc.initialMs / 1000}+${tc.incrementMs / 1000}"
            is TimeControl.PerMove -> "1/${tc.perMoveMs / 1000}"
            is TimeControl.Byoyomi -> "${tc.initialMs / 1000}"
        }
        val ending = if (s.result == Result.ONGOING) result else "{${s.statusText.replace("}", ")")}} $result"
        val text = tags.joinToString("\n") { (k, v) -> Exports.tag(k, v) } + "\n\n" + Exports.movetext(s.sanMoves, ending) + "\n"
        return ExportedGame("chess-${Exports.fileStamp(now)}.pgn", "text/plain", text)
    }

    /** A game that just ended goes to the history, once. */
    private fun recordIfFinished() {
        val s = _state.value
        if (!hasGame || recorded || s.result == Result.ONGOING) return
        recorded = true
        val record = GameRecord(
            endedAt = System.currentTimeMillis(),
            game = "Chess",
            variant = if (session.chess960) "Chess960" else null,
            opponent = if (s.config.twoPlayers) colorName(s.engineSide) else s.config.opponentLabel,
            playerSide = colorName(s.playerSide),
            result = when (s.result) {
                Result.PLAYER_WINS -> PlayerResult.WIN
                Result.ENGINE_WINS -> PlayerResult.LOSS
                else -> PlayerResult.DRAW
            },
            reason = s.statusText,
            moveCount = s.sanMoves.size,
            export = exportGame(),
            twoPlayers = s.config.twoPlayers,
        )
        viewModelScope.launch(Dispatchers.IO) { records.add(record) }
    }

    // ---------------------------------------------------------------- hint

    /** Thinking time for a hint. */
    private val HINT_MS = 1500

    /** Asks the engine, at full strength, for a move for the player; shown until a move is played. */
    fun requestHint() {
        val s = _state.value
        if (!s.isPlayerTurn || s.hintLoading || !hasGame) return
        val config = s.config
        val moves = uciMoves.toList()
        val startFen = if (session.chess960) session.startFen else null
        _state.update { it.copy(hintLoading = true) }
        hintJob = viewModelScope.launch {
            val uci = runCatching {
                val eng = engineLock.withLock { liveEngine(config, restart = false) }
                try {
                    eng.configure(null) // full strength for the hint
                    eng.bestMove(moves, HINT_MS, null, null, startFen)
                } finally {
                    withContext(NonCancellable) { eng.configure(config.strength) }
                }
            }.getOrNull()
            _state.update { it.copy(hintLoading = false) }
            // Same moves, not just as many: with two players, undo + another move keeps the count.
            // Nor once the game ended meanwhile (resigned, flagged).
            val move = uci?.takeIf { uciMoves == moves && !isGameOver() }?.let { session.parseUci(it) } ?: return@launch
            val to = move.castleRook ?: move.to
            _state.update {
                it.copy(hint = setOf(move.from, to), hintText = "${move.from.value().lowercase()} → ${to.value().lowercase()}")
            }
            publish()
        }
    }

    private fun clearHint() = _state.update { it.copy(hint = emptySet(), hintText = null) }

    /** The player moved while a hint was being searched: end that search now. */
    private fun stopHint() {
        if (hintJob?.isActive == true) engine?.stop()
    }
}
