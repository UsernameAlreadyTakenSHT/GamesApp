package io.github.usernamealreadytakensht.games.game.morris

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.usernamealreadytakensht.games.engine.morris.MorrisOpponent
import io.github.usernamealreadytakensht.games.engine.morris.SanmillEngine
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.SavedMorrisGame
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.morris.Morris.Color
import io.github.usernamealreadytakensht.games.game.morris.Morris.Move
import io.github.usernamealreadytakensht.games.game.morris.Morris.Position
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

enum class MorrisResult { ONGOING, PLAYER_WINS, ENGINE_WINS, DRAW }

/** Immutable snapshot of the morris game, consumed by the UI. */
data class MorrisState(
    val config: MorrisConfig = MorrisConfig(),
    /** Stone per point (0..23), see [Morris.EMPTY] / [Morris.WHITE] / [Morris.BLACK]. */
    val points: List<Int> = List(24) { Morris.EMPTY },
    val whiteInHand: Int = Morris.MEN_PER_SIDE,
    val blackInHand: Int = Morris.MEN_PER_SIDE,
    val playerSide: Color = Color.WHITE,
    val sideToMove: Color = Color.WHITE,
    /** Own man picked up (sliding / flying phase). */
    val selected: Int? = null,
    /** Points the selection may go to, or every empty point while placing. */
    val targets: Set<Int> = emptySet(),
    /** A mill was just closed: enemy men that may be taken to finish the move. */
    val removable: Set<Int> = emptySet(),
    /** The half-played move waiting for that choice (its man is drawn as a ghost). */
    val pendingMove: Move? = null,
    val lastMove: Move? = null,
    /** Moves in coordinate notation, for the move list. */
    val moves: List<String> = emptyList(),
    val thinking: Boolean = false,
    val engineError: String? = null,
    val result: MorrisResult = MorrisResult.ONGOING,
    val statusText: String = "",
    val whiteMs: Long? = null,
    val blackMs: Long? = null,
    val runningClock: Color? = null,
) {
    val canUndo: Boolean get() = moves.isNotEmpty() && result == MorrisResult.ONGOING
    val isPlayerTurn: Boolean get() = sideToMove == playerSide && result == MorrisResult.ONGOING && !thinking
    val engineSide: Color get() = playerSide.other
    fun inHand(side: Color) = if (side == Color.WHITE) whiteInHand else blackInHand
    fun clockMs(side: Color): Long? = if (side == Color.WHITE) whiteMs else blackMs
}

class MorrisViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private val repo = GameRepository(app)
    private var engine: MorrisOpponent? = null
    private val engineLock = Mutex()
    private var engineReady = false

    private var position: Position = Morris.START
    private val history = ArrayList<Position>()   // positions before each move, plus current
    private val played = ArrayList<Move>()
    /** The move being completed by a removal choice, with `remove` still unset. */
    private var pending: Move? = null
    private var generation = 0
    private var resigned = false
    private var flagged: Color? = null

    var hasGame = false
        private set

    // Clock: remaining time frozen at the start of the turn + when the turn started.
    private var whiteBaseMs = 0L
    private var blackBaseMs = 0L
    private var turnStartedAt = 0L
    private var clockJob: Job? = null
    private var clockPaused = false

    private val _state = MutableStateFlow(MorrisState())
    val state: StateFlow<MorrisState> = _state

    init { publish() }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: MorrisConfig) {
        resetGame(config, config.playerSide ?: if (Random.nextBoolean()) Color.WHITE else Color.BLACK)
        val initial = config.timeControl.startingMs
        whiteBaseMs = initial ?: 0L
        blackBaseMs = initial ?: 0L
        finishLoad()
    }

    /** Resumes the saved game if any; a game already held by this ViewModel is kept. */
    fun resumeGame(): Boolean {
        if (hasGame) { resumeClock(); return true }
        val saved = repo.loadMorrisGame() ?: return false
        resetGame(saved.config, saved.playerSide)
        for (text in saved.moves) {
            val m = Move.parse(text) ?: break
            if (m !in Morris.legalMoves(position)) break
            applyMove(m, clock = false)
        }
        whiteBaseMs = saved.whiteMs ?: 0L
        blackBaseMs = saved.blackMs ?: 0L
        finishLoad()
        return true
    }

    fun onPointTapped(point: Int) {
        val s = _state.value
        if (!s.isPlayerTurn || point !in 0 until 24) return

        // Finishing a mill: the tap must pick one of the removable enemy men.
        pending?.let { p ->
            if (point in s.removable) playPlayerMove(p.copy(remove = point))
            return
        }

        val legal = Morris.legalMoves(position)
        if (position.isPlacing(s.playerSide)) {
            offer(legal.filter { it.isPlacement && it.to == point })
            return
        }
        val selected = s.selected
        if (selected != null) {
            val candidates = legal.filter { it.from == selected && it.to == point }
            if (candidates.isNotEmpty()) { offer(candidates); return }
        }
        // Select / re-select one of the player's men.
        val moves = legal.filter { it.from == point }
        if (moves.isNotEmpty()) {
            _state.update { it.copy(selected = point, targets = moves.map { m -> m.to }.toSet()) }
        } else {
            _state.update { it.copy(selected = null, targets = emptySet()) }
        }
    }

    /** Plays the move outright, or asks which enemy man to take when it closes a mill. */
    private fun offer(candidates: List<Move>) {
        if (candidates.isEmpty()) return
        if (candidates.size == 1 && !candidates[0].closesMill) { playPlayerMove(candidates[0]); return }
        val first = candidates[0]
        pending = first.copy(remove = -1)
        _state.update {
            it.copy(selected = null, targets = emptySet(), removable = candidates.map { m -> m.remove }.toSet(), pendingMove = pending)
        }
        publish()
    }

    /** Takes back the player's last move (and the engine's reply, if any). */
    fun undo() {
        val s = _state.value
        if (played.isEmpty() || !s.canUndo) return
        cancelSearch()
        pending = null
        undoOne()
        if (position.toMove != s.playerSide && played.isNotEmpty()) undoOne()
        clearSelection()
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove()
    }

    fun resign() {
        if (_state.value.result != MorrisResult.ONGOING || !hasGame) return
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
            if (running == Color.WHITE) whiteBaseMs = left else blackBaseMs = left
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

    private fun resetGame(config: MorrisConfig, side: Color) {
        cancelSearch()
        stopClock()
        clockPaused = false
        position = Morris.START
        history.clear(); history += position
        played.clear()
        pending = null
        resigned = false
        flagged = null
        hasGame = true
        _state.update {
            it.copy(config = config, playerSide = side, selected = null, targets = emptySet(), removable = emptySet(),
                pendingMove = null, runningClock = null)
        }
    }

    private fun finishLoad() {
        restartTurnClock()
        publish()
        persist()
        val kind = _state.value.config.engine
        viewModelScope.launch {
            try {
                engineLock.withLock {
                    val eng = ensureEngine(kind)
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

    /** Reuses the running engine when it is the right one, otherwise starts the right one. */
    private suspend fun ensureEngine(kind: MorrisEngineKind): MorrisOpponent {
        engine?.let { if (it.kind == kind && it.isRunning) return it }
        engineReady = false
        engine?.quit()
        _state.update { it.copy(engineError = null) }
        publish()
        val eng: MorrisOpponent = SanmillEngine(app, kind)
        engine = eng
        eng.start()
        engineReady = true
        publish()
        return eng
    }

    private fun playPlayerMove(move: Move) {
        pending = null
        applyMove(move, clock = true)
        clearSelection()
        publish()
        persist()
        maybeEngineMove()
    }

    private fun clearSelection() =
        _state.update { it.copy(selected = null, targets = emptySet(), removable = emptySet(), pendingMove = null) }

    private fun applyMove(move: Move, clock: Boolean) {
        val mover = position.toMove
        position = Morris.play(position, move)
        history += position
        played += move
        if (clock) onMovePlayed(mover)
    }

    private fun undoOne() {
        played.removeAt(played.size - 1)
        history.removeAt(history.size - 1)
        position = history.last()
    }

    /** Plies since the last placement or mill (draw counter). */
    private val quietPlies: Int
        get() {
            var n = 0
            for (i in played.indices.reversed()) {
                if (Morris.isProgress(played[i])) break
                n++
            }
            return n
        }

    private fun outcome(): Morris.Outcome = when {
        resigned -> if (_state.value.playerSide == Color.WHITE) Morris.Outcome.BLACK_WINS else Morris.Outcome.WHITE_WINS
        flagged != null -> if (flagged == Color.WHITE) Morris.Outcome.BLACK_WINS else Morris.Outcome.WHITE_WINS
        else -> Morris.outcome(position, history.dropLast(1), quietPlies)
    }

    private fun isGameOver() = outcome() != Morris.Outcome.ONGOING

    private fun maybeEngineMove() {
        val eng = engine ?: return
        if (!engineReady) return
        if (position.toMove == _state.value.playerSide || isGameOver()) return
        val gen = ++generation
        val cfg = _state.value.config
        val history = played.toList()
        val moveTime = engineMoveTimeMs()
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            val move = try {
                eng.bestMove(history, cfg.depth, moveTime)
            } catch (e: Exception) {
                _state.update { it.copy(engineError = e.message, thinking = false) }
                publish()
                return@launch
            }
            if (gen != generation) return@launch
            _state.update { it.copy(thinking = false) }
            if (move != null && move in Morris.legalMoves(position) && !isGameOver()) applyMove(move, clock = true)
            publish()
            persist()
        }
    }

    private fun engineMoveTimeMs(): Int {
        val cfg = _state.value.config
        val base = if (cfg.depth == null) 1500L else 6000L
        val remaining = currentMs(position.toMove) ?: return base.toInt()
        val budget = when (val tc = cfg.timeControl) {
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

    override fun onCleared() {
        engine?.quit()
    }

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        if (!hasGame) return
        if (isGameOver()) { repo.clearMorrisGame(); return }
        repo.saveMorrisGame(
            SavedMorrisGame(
                config = _state.value.config,
                playerSide = _state.value.playerSide,
                moves = played.map { it.toNotation() },
                whiteMs = currentMs(Color.WHITE),
                blackMs = currentMs(Color.BLACK),
            ),
        )
    }

    // ---------------------------------------------------------------- clock

    private fun hasClock() = _state.value.config.timeControl != TimeControl.None

    private fun currentMs(side: Color): Long? {
        if (!hasClock()) return null
        val base = if (side == Color.WHITE) whiteBaseMs else blackBaseMs
        val elapsed = if (_state.value.runningClock == side) SystemClock.elapsedRealtime() - turnStartedAt else 0L
        return (base - elapsed).coerceAtLeast(0L)
    }

    private fun onMovePlayed(mover: Color) {
        if (!hasClock()) return
        val tc = _state.value.config.timeControl
        val now = SystemClock.elapsedRealtime()
        if (_state.value.runningClock == mover) {
            var left = (if (mover == Color.WHITE) whiteBaseMs else blackBaseMs) - (now - turnStartedAt)
            left = when (tc) {
                is TimeControl.Fischer -> left + tc.incrementMs
                is TimeControl.PerMove -> tc.perMoveMs
                else -> left
            }.coerceAtLeast(0L)
            if (mover == Color.WHITE) whiteBaseMs = left else blackBaseMs = left
        } else if (tc is TimeControl.PerMove) {
            if (mover == Color.WHITE) whiteBaseMs = tc.perMoveMs else blackBaseMs = tc.perMoveMs
        }
        restartTurnClock()
    }

    private fun restartTurnClock() {
        if (!hasClock() || clockPaused || played.isEmpty() || isGameOver()) { stopClock(); return }
        turnStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(runningClock = position.toMove) }
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
                    if (running == Color.WHITE) whiteBaseMs = 0 else blackBaseMs = 0
                    cancelSearch()
                    _state.update { it.copy(runningClock = null) }
                    publish()
                    persist()
                    break
                }
                _state.update { it.copy(whiteMs = currentMs(Color.WHITE), blackMs = currentMs(Color.BLACK)) }
            }
        }
    }

    private fun stopClock() {
        clockJob?.cancel()
        clockJob = null
        _state.update { it.copy(runningClock = null) }
    }

    // ---------------------------------------------------------------- snapshot

    private fun publish() {
        val player = _state.value.playerSide
        val engineName = _state.value.config.engine.family.label
        val result = when (outcome()) {
            Morris.Outcome.ONGOING -> MorrisResult.ONGOING
            Morris.Outcome.DRAW -> MorrisResult.DRAW
            Morris.Outcome.WHITE_WINS -> if (player == Color.WHITE) MorrisResult.PLAYER_WINS else MorrisResult.ENGINE_WINS
            Morris.Outcome.BLACK_WINS -> if (player == Color.BLACK) MorrisResult.PLAYER_WINS else MorrisResult.ENGINE_WINS
        }
        val playerTurn = result == MorrisResult.ONGOING && position.toMove == player
        val status = when {
            _state.value.engineError != null -> "Engine error: ${_state.value.engineError}"
            resigned -> "You resigned."
            flagged != null -> if (flagged == player) "You ran out of time." else "$engineName ran out of time."
            result == MorrisResult.PLAYER_WINS -> if (position.onBoard(player.other) < 3) "You won: $engineName is down to two men."
                                                  else "You won: $engineName cannot move."
            result == MorrisResult.ENGINE_WINS -> if (position.onBoard(player) < 3) "$engineName won: you are down to two men."
                                                  else "$engineName won: you cannot move."
            result == MorrisResult.DRAW -> "Draw."
            !engineReady -> "Starting $engineName…"
            _state.value.thinking -> "$engineName is thinking…"
            playerTurn && pending != null -> "Mill! Take an enemy man."
            playerTurn && position.isPlacing(player) -> "Place a man (${position.inHand(player)} left)."
            playerTurn && position.isFlying(player) -> "Three men left: you may fly anywhere."
            playerTurn -> "Slide a man along a line."
            else -> "$engineName to move."
        }
        // While placing, every empty point is a target (shown as dots).
        val placingTargets = if (playerTurn && pending == null && position.isPlacing(player))
            (0 until 24).filter { position[it] == Morris.EMPTY }.toSet() else null
        _state.update {
            it.copy(
                points = List(24) { p -> position[p] },
                whiteInHand = position.whiteInHand,
                blackInHand = position.blackInHand,
                sideToMove = position.toMove,
                lastMove = played.lastOrNull(),
                moves = played.map { m -> m.toNotation() },
                result = result,
                statusText = status,
                targets = when {
                    placingTargets != null -> placingTargets
                    !playerTurn || pending != null || it.selected == null -> emptySet()
                    else -> it.targets
                },
                whiteMs = currentMs(Color.WHITE),
                blackMs = currentMs(Color.BLACK),
            )
        }
    }
}
