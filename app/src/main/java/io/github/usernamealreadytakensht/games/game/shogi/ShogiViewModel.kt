package io.github.usernamealreadytakensht.games.game.shogi

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.usernamealreadytakensht.games.engine.shogi.ShogiEngine
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.SavedShogiGame
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.shogi.Shogi.Kind
import io.github.usernamealreadytakensht.games.game.shogi.Shogi.Move
import io.github.usernamealreadytakensht.games.game.shogi.Shogi.Piece
import io.github.usernamealreadytakensht.games.game.shogi.Shogi.Side
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

enum class ShogiResult { ONGOING, PLAYER_WINS, ENGINE_WINS, DRAW }

/** Immutable snapshot of the shogi game, consumed by the UI. */
data class ShogiState(
    val config: ShogiConfig = ShogiConfig(),
    /** Piece per square (see [Shogi] for the numbering). */
    val pieces: List<Piece?> = List(81) { null },
    /** Pieces in hand per side, indexed like [Kind.HAND]. */
    val senteHand: List<Int> = List(7) { 0 },
    val goteHand: List<Int> = List(7) { 0 },
    val playerSide: Side = Side.SENTE,
    val sideToMove: Side = Side.SENTE,
    /** Selected board square, or the piece kind picked from the player's hand. */
    val selected: Int? = null,
    val selectedDrop: Kind? = null,
    val targets: Set<Int> = emptySet(),
    val lastMove: Move? = null,
    val checkedKing: Int? = null,
    /** A move that may promote or not, waiting for the player's choice. */
    val pendingPromotion: Move? = null,
    val moves: List<String> = emptyList(),
    val thinking: Boolean = false,
    val result: ShogiResult = ShogiResult.ONGOING,
    val statusText: String = "",
    val engineError: String? = null,
    /** Main time left per side (ms), null without a clock. */
    val senteMs: Long? = null,
    val goteMs: Long? = null,
    /** Byoyomi countdown per side (ms), null unless the clock is a byoyomi one. */
    val senteByoyomiMs: Long? = null,
    val goteByoyomiMs: Long? = null,
    val runningClock: Side? = null,
) {
    val canUndo: Boolean get() = moves.isNotEmpty() && result == ShogiResult.ONGOING
    val isPlayerTurn: Boolean get() = sideToMove == playerSide && result == ShogiResult.ONGOING && !thinking
    val engineSide: Side get() = playerSide.other
    fun clockMs(side: Side): Long? = if (side == Side.SENTE) senteMs else goteMs
    fun byoyomiMs(side: Side): Long? = if (side == Side.SENTE) senteByoyomiMs else goteByoyomiMs
    fun hand(side: Side): List<Int> = if (side == Side.SENTE) senteHand else goteHand
}

class ShogiViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GameRepository(app)
    private val engine = ShogiEngine(app)
    private val engineLock = Mutex()
    private var engineReady = false
    /** True while [finishLoad] starts the engine: searches wait for it to finish. */
    private var engineStarting = false

    private var game = Shogi.Game()
    private var generation = 0
    private var resigned = false
    private var flagged: Side? = null

    var hasGame = false
        private set

    // Clock: remaining time frozen at the start of the turn + when the turn started.
    private var senteBaseMs = 0L
    private var goteBaseMs = 0L
    private var turnStartedAt = 0L
    private var clockJob: Job? = null
    private var clockPaused = false

    private val _state = MutableStateFlow(ShogiState())
    val state: StateFlow<ShogiState> = _state

    init { publish() }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: ShogiConfig) {
        resetGame(config, config.playerSide ?: if (Random.nextBoolean()) Side.SENTE else Side.GOTE)
        val initial = config.timeControl.startingMs
        senteBaseMs = initial ?: 0L
        goteBaseMs = initial ?: 0L
        finishLoad()
    }

    /** Resumes the saved game if any; a game already held by this ViewModel is kept. */
    fun resumeGame(): Boolean {
        if (hasGame) { resumeClock(); return true }
        val saved = repo.loadShogiGame() ?: return false
        resetGame(saved.config, saved.playerSide)
        for (uci in saved.moves) game.play(game.parseUci(uci) ?: break)
        senteBaseMs = saved.senteMs ?: 0L
        goteBaseMs = saved.goteMs ?: 0L
        finishLoad()
        return true
    }

    fun onSquareTapped(sq: Int) {
        val s = _state.value
        if (!s.isPlayerTurn || s.pendingPromotion != null) return
        if ((s.selected != null || s.selectedDrop != null) && sq in s.targets) {
            val candidates = game.legalMoves().filter {
                it.to == sq && if (s.selectedDrop != null) it.drop == s.selectedDrop else it.from == s.selected
            }
            val promoting = candidates.firstOrNull { it.promote }
            if (promoting != null && candidates.size > 1) {
                _state.update { it.copy(pendingPromotion = promoting) }
            } else {
                playPlayerMove(candidates.first())
            }
            return
        }
        val piece = game.position.at(sq)
        if (piece != null && piece.side == s.playerSide && s.selected != sq) {
            val targets = game.legalMoves().filter { it.from == sq }.map { it.to }.toSet()
            _state.update { it.copy(selected = sq, selectedDrop = null, targets = targets) }
        } else {
            clearSelection()
        }
    }

    /** Picks (or drops back) a piece of the player's hand. */
    fun onHandTapped(kind: Kind) {
        val s = _state.value
        if (!s.isPlayerTurn || s.pendingPromotion != null) return
        if (s.selectedDrop == kind) { clearSelection(); return }
        val targets = game.legalMoves().filter { it.drop == kind }.map { it.to }.toSet()
        if (targets.isEmpty()) { clearSelection(); return }
        _state.update { it.copy(selected = null, selectedDrop = kind, targets = targets) }
    }

    fun choosePromotion(promote: Boolean) {
        val m = _state.value.pendingPromotion ?: return
        _state.update { it.copy(pendingPromotion = null) }
        playPlayerMove(m.copy(promote = promote))
    }

    fun cancelPromotion() {
        _state.update { it.copy(pendingPromotion = null) }
        clearSelection()
    }

    /** Takes back the player's last move (and the engine's reply, if any). */
    fun undo() {
        val s = _state.value
        if (game.moves.isEmpty() || !s.canUndo) return
        cancelSearch()
        game.undo()
        if (game.position.sideToMove != s.playerSide && game.moves.isNotEmpty()) game.undo()
        clearSelection()
        _state.update { it.copy(engineError = null) }
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove()
    }

    /** Asks the engine again after an error (it is restarted if it died). */
    fun retryEngine() {
        if (_state.value.engineError == null) return
        _state.update { it.copy(engineError = null) }
        publish()
        maybeEngineMove()
    }

    fun resign() {
        if (_state.value.result != ShogiResult.ONGOING || !hasGame) return
        cancelSearch()
        resigned = true
        stopClock()
        clearSelection()
        publish()
        persist()
    }

    fun rematch() = startGame(_state.value.config.copy(playerSide = _state.value.engineSide))

    fun pauseClock() {
        if (!hasClock() || clockPaused) return
        clockPaused = true
        val running = _state.value.runningClock
        if (running != null) {
            val left = currentMs(running) ?: 0L
            if (running == Side.SENTE) senteBaseMs = left else goteBaseMs = left
        }
        stopClock()
        persist()
    }

    fun resumeClock() {
        if (!clockPaused) return
        clockPaused = false
        restartTurnClock()
    }

    // ---------------------------------------------------------------- game flow

    private fun resetGame(config: ShogiConfig, side: Side) {
        cancelSearch()
        stopClock()
        clockPaused = false
        game = Shogi.Game()
        resigned = false
        flagged = null
        hasGame = true
        _state.update {
            it.copy(config = config, playerSide = side, selected = null, selectedDrop = null, targets = emptySet(),
                pendingPromotion = null, runningClock = null)
        }
    }

    private fun finishLoad() {
        restartTurnClock()
        publish()
        persist()
        val config = _state.value.config
        _state.update { it.copy(engineError = null) }
        engineStarting = true
        viewModelScope.launch {
            try {
                engineLock.withLock {
                    engine.start()
                    engine.newGame(ShogiLevels.skill(config.level))
                    engineReady = true
                }
            } catch (e: Exception) {
                _state.update { it.copy(engineError = e.message ?: e.toString()) }
                publish()
                return@launch
            } finally {
                engineStarting = false
            }
            publish()
            maybeEngineMove()
        }
    }

    /**
     * Makes sure the engine process is alive, restarting it when it died (killed in the
     * background, or by the watchdog) or when [restart] is asked after a failed search.
     */
    private suspend fun liveEngine(config: ShogiConfig, restart: Boolean) {
        if (restart) engine.quit()
        if (!engine.isRunning) {
            engine.start()
            engine.newGame(ShogiLevels.skill(config.level))
            engineReady = true
        }
    }

    private fun playPlayerMove(move: Move) {
        applyMove(move)
        clearSelection()
        publish()
        persist()
        maybeEngineMove()
    }

    private fun clearSelection() =
        _state.update { it.copy(selected = null, selectedDrop = null, targets = emptySet()) }

    private fun applyMove(move: Move) {
        val mover = game.position.sideToMove
        game.play(move)
        onMovePlayed(mover)
    }

    private fun outcome(): Shogi.Outcome = when {
        resigned -> if (_state.value.playerSide == Side.SENTE) Shogi.Outcome.GOTE_WINS else Shogi.Outcome.SENTE_WINS
        flagged != null -> if (flagged == Side.SENTE) Shogi.Outcome.GOTE_WINS else Shogi.Outcome.SENTE_WINS
        else -> game.outcome()
    }

    private fun isGameOver() = outcome() != Shogi.Outcome.ONGOING

    private fun maybeEngineMove() {
        // The first start reports its own failure; later searches restart the engine themselves.
        if (engineStarting || _state.value.engineError != null) return
        if (game.position.sideToMove == _state.value.playerSide || isGameOver()) return
        val gen = ++generation
        val config = _state.value.config
        val moves = game.uciMoves
        val moveTime = engineMoveTimeMs()
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            var move: Move? = null
            var failure: String? = null
            // A failed search (dead or hung engine, unusable reply) gets one retry on a fresh engine.
            for (attempt in 0..1) {
                try {
                    engineLock.withLock { liveEngine(config, restart = attempt > 0) }
                    val uci = engine.bestMove(moves, moveTime)
                    if (gen != generation) return@launch
                    move = uci?.let { game.parseUci(it) }
                    if (move != null) break
                    failure = "Fairy-Stockfish gave no legal move (${uci ?: "none"})"
                } catch (e: Exception) {
                    if (gen != generation) return@launch
                    failure = e.message ?: e.toString()
                }
            }
            _state.update { it.copy(thinking = false, engineError = if (move == null) failure else null) }
            if (move != null && !isGameOver()) applyMove(move)
            publish()
            persist()
        }
    }

    private fun engineMoveTimeMs(): Int {
        val cfg = _state.value.config
        val base = ShogiLevels.moveTimeMs(cfg.level).toLong()
        val remaining = currentMs(game.position.sideToMove) ?: return base.toInt()
        val budget = when (val tc = cfg.timeControl) {
            // In byoyomi, use a good part of the period: it comes back after every move.
            is TimeControl.Byoyomi -> if (remaining > 0) remaining / 20 + tc.byoyomiMs / 2 else tc.byoyomiMs * 6 / 10
            is TimeControl.PerMove -> tc.perMoveMs / 2
            is TimeControl.Fischer -> remaining / 20 + tc.incrementMs / 2
            else -> remaining / 20
        }
        return minOf(base, budget).coerceAtLeast(50L).toInt()
    }

    private fun cancelSearch() {
        generation++
        if (_state.value.thinking) engine.stop()
        _state.update { it.copy(thinking = false) }
    }

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        if (!hasGame) return
        if (isGameOver()) { repo.clearShogiGame(); return }
        repo.saveShogiGame(
            SavedShogiGame(
                config = _state.value.config,
                playerSide = _state.value.playerSide,
                moves = game.uciMoves,
                senteMs = currentMs(Side.SENTE),
                goteMs = currentMs(Side.GOTE),
            ),
        )
    }

    // ---------------------------------------------------------------- clock

    private fun hasClock() = _state.value.config.timeControl != TimeControl.None

    private fun currentMs(side: Side): Long? {
        if (!hasClock()) return null
        val base = if (side == Side.SENTE) senteBaseMs else goteBaseMs
        val elapsed = if (_state.value.runningClock == side) SystemClock.elapsedRealtime() - turnStartedAt else 0L
        return (base - elapsed).coerceAtLeast(0L)
    }

    private fun onMovePlayed(mover: Side) {
        if (!hasClock()) return
        val tc = _state.value.config.timeControl
        val now = SystemClock.elapsedRealtime()
        if (_state.value.runningClock == mover) {
            var left = (if (mover == Side.SENTE) senteBaseMs else goteBaseMs) - (now - turnStartedAt)
            left = when (tc) {
                is TimeControl.Fischer -> left + tc.incrementMs
                is TimeControl.PerMove -> tc.perMoveMs
                else -> left
            }.coerceAtLeast(0L)
            if (mover == Side.SENTE) senteBaseMs = left else goteBaseMs = left
        } else if (tc is TimeControl.PerMove) {
            if (mover == Side.SENTE) senteBaseMs = tc.perMoveMs else goteBaseMs = tc.perMoveMs
        }
        restartTurnClock()
    }

    private fun restartTurnClock() {
        if (!hasClock() || clockPaused || game.moves.isEmpty() || isGameOver()) { stopClock(); return }
        turnStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(runningClock = game.position.sideToMove) }
        ensureClockTicking()
    }

    private fun ensureClockTicking() {
        if (clockJob?.isActive == true) return
        clockJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                val running = _state.value.runningClock ?: break
                val left = timeLeft(running) ?: break
                if (left <= 0L) {
                    flagged = running
                    if (running == Side.SENTE) senteBaseMs = 0 else goteBaseMs = 0
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

    private fun publishClocks() = _state.update {
        it.copy(
            senteMs = currentMs(Side.SENTE),
            goteMs = currentMs(Side.GOTE),
            senteByoyomiMs = byoyomiLeft(Side.SENTE),
            goteByoyomiMs = byoyomiLeft(Side.GOTE),
        )
    }

    /**
     * Byoyomi left for [side]: the full period until its main time is gone, then counting
     * down within the current turn. Null for other clocks.
     */
    private fun byoyomiLeft(side: Side): Long? {
        val tc = _state.value.config.timeControl as? TimeControl.Byoyomi ?: return null
        if (flagged == side) return 0L
        if (_state.value.runningClock != side) return tc.byoyomiMs
        val base = if (side == Side.SENTE) senteBaseMs else goteBaseMs
        val over = SystemClock.elapsedRealtime() - turnStartedAt - base
        return if (over <= 0) tc.byoyomiMs else (tc.byoyomiMs - over).coerceAtLeast(0L)
    }

    /** Everything [side] may still spend this turn: main time plus byoyomi. */
    private fun timeLeft(side: Side): Long? {
        val main = currentMs(side) ?: return null
        return main + (byoyomiLeft(side) ?: 0L)
    }

    private fun stopClock() {
        clockJob?.cancel()
        clockJob = null
        _state.update { it.copy(runningClock = null) }
    }

    // ---------------------------------------------------------------- snapshot

    private fun publish() {
        val player = _state.value.playerSide
        val engineName = "Fairy-Stockfish"
        val pos = game.position
        val out = outcome()
        val result = when (out) {
            Shogi.Outcome.ONGOING -> ShogiResult.ONGOING
            Shogi.Outcome.DRAW -> ShogiResult.DRAW
            Shogi.Outcome.SENTE_WINS -> if (player == Side.SENTE) ShogiResult.PLAYER_WINS else ShogiResult.ENGINE_WINS
            Shogi.Outcome.GOTE_WINS -> if (player == Side.GOTE) ShogiResult.PLAYER_WINS else ShogiResult.ENGINE_WINS
        }
        val inCheck = pos.inCheck()
        val repetition = result != ShogiResult.ONGOING && !resigned && flagged == null && game.isRepetition()
        val status = when {
            _state.value.engineError != null -> "Engine error: ${_state.value.engineError}"
            resigned -> "You resigned — $engineName wins."
            flagged == player -> "Time's up — $engineName wins."
            flagged != null -> "Time's up — you win!"
            repetition && result == ShogiResult.DRAW -> "Sennichite: fourfold repetition, draw."
            repetition && result == ShogiResult.PLAYER_WINS -> "Perpetual check by $engineName — you win!"
            repetition -> "Perpetual check — $engineName wins."
            result == ShogiResult.PLAYER_WINS -> "Checkmate — you win!"
            result == ShogiResult.ENGINE_WINS -> "Checkmate — $engineName wins."
            !engineReady -> "Starting $engineName…"
            _state.value.thinking -> "$engineName is thinking…"
            inCheck -> "Check! Your move."
            else -> "Your move (${if (player == Side.SENTE) "sente" else "gote"})."
        }
        _state.update {
            it.copy(
                pieces = List(81) { sq -> pos.at(sq) },
                senteHand = Kind.HAND.map { k -> pos.inHand(Side.SENTE, k) },
                goteHand = Kind.HAND.map { k -> pos.inHand(Side.GOTE, k) },
                sideToMove = pos.sideToMove,
                lastMove = game.moves.lastOrNull(),
                checkedKing = if (inCheck) pos.kingSquare(pos.sideToMove) else null,
                moves = game.notations.toList(),
                result = result,
                statusText = status,
                senteMs = currentMs(Side.SENTE),
                goteMs = currentMs(Side.GOTE),
                senteByoyomiMs = byoyomiLeft(Side.SENTE),
                goteByoyomiMs = byoyomiLeft(Side.GOTE),
            )
        }
    }

    override fun onCleared() {
        engine.quit()
    }
}
