package io.github.usernamealreadytakensht.games.game.tafl

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.manywords.softworks.tafl.engine.Game
import io.github.usernamealreadytakensht.games.engine.tafl.OpenTaflEngine
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.SavedTaflGame
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.tafl.Tafl.Move
import io.github.usernamealreadytakensht.games.game.tafl.Tafl.Side
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class TaflResult { ONGOING, PLAYER_WINS, ENGINE_WINS, DRAW }

/** Immutable snapshot of the tafl game, consumed by the UI. */
data class TaflState(
    val config: TaflConfig = TaflConfig(),
    /** Piece code per square, row by row from the top (see [Tafl.EMPTY] …). */
    val squares: List<Int> = emptyList(),
    /** Board size of the variant being played. */
    val size: Int = TaflVariant.COPENHAGEN.size,
    val playerSide: Side = Side.DEFENDERS,
    val sideToMove: Side = Side.ATTACKERS,
    val selected: Pair<Int, Int>? = null,
    val targets: Set<Pair<Int, Int>> = emptySet(),
    val lastMove: Move? = null,
    /** Pieces taken by the last move. */
    val captured: List<Pair<Int, Int>> = emptyList(),
    /** Moves in OpenTafl notation, for the move list. */
    val moves: List<String> = emptyList(),
    val thinking: Boolean = false,
    val result: TaflResult = TaflResult.ONGOING,
    val statusText: String = "",
    val attackersMs: Long? = null,
    val defendersMs: Long? = null,
    val runningClock: Side? = null,
    /** Takebacks still available, null = unlimited. */
    val takebacksLeft: Int? = null,
) {
    val canUndo: Boolean
        get() = moves.isNotEmpty() && result == TaflResult.ONGOING && (takebacksLeft == null || takebacksLeft > 0)
    val isPlayerTurn: Boolean get() = sideToMove == playerSide && result == TaflResult.ONGOING && !thinking
    val engineSide: Side get() = playerSide.other
    fun clockMs(side: Side): Long? = if (side == Side.ATTACKERS) attackersMs else defendersMs
}

class TaflViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GameRepository(app)
    private val engine = OpenTaflEngine()

    private var game: Game = Tafl.newGame(TaflVariant.COPENHAGEN)
    private val played = ArrayList<Move>()
    private var generation = 0
    private var takebacksUsed = 0
    private var resigned = false
    private var flagged: Side? = null

    var hasGame = false
        private set

    // Clock: remaining time frozen at the start of the turn + when the turn started.
    private var attackersBaseMs = 0L
    private var defendersBaseMs = 0L
    private var turnStartedAt = 0L
    private var clockJob: Job? = null
    private var clockPaused = false

    private val _state = MutableStateFlow(TaflState())
    val state: StateFlow<TaflState> = _state

    init { publish() }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: TaflConfig) {
        resetGame(config, config.playerSide ?: if (Random.nextBoolean()) Side.ATTACKERS else Side.DEFENDERS)
        val initial = config.timeControl.startingMs
        attackersBaseMs = initial ?: 0L
        defendersBaseMs = initial ?: 0L
        finishLoad()
    }

    /** Resumes the saved game if any; a game already held by this ViewModel is kept. */
    fun resumeGame(): Boolean {
        if (hasGame) { resumeClock(); return true }
        val saved = repo.loadTaflGame() ?: return false
        resetGame(saved.config, saved.playerSide)
        val (g, moves) = Tafl.replay(saved.config.variant, saved.moves)
        game = g
        played += moves
        attackersBaseMs = saved.attackersMs ?: 0L
        defendersBaseMs = saved.defendersMs ?: 0L
        finishLoad()
        return true
    }

    fun onSquareTapped(x: Int, y: Int) {
        val s = _state.value
        if (!s.isPlayerTurn) return
        val selected = s.selected
        if (selected != null && (x to y) in s.targets) {
            playPlayerMove(Move(selected.first, selected.second, x, y))
            return
        }
        val dests = Tafl.destinations(game, x, y)
        if (dests.isNotEmpty()) {
            _state.update { it.copy(selected = x to y, targets = dests.toSet()) }
        } else {
            _state.update { it.copy(selected = null, targets = emptySet()) }
        }
    }

    /** Takes back the player's last move (and the engine's reply, if any). */
    fun undo() {
        val s = _state.value
        if (played.isEmpty() || !s.canUndo) return
        cancelSearch()
        undoOne()
        if (Tafl.sideToMove(game) != s.playerSide && played.isNotEmpty()) undoOne()
        takebacksUsed++
        clearSelection()
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove()
    }

    fun resign() {
        if (_state.value.result != TaflResult.ONGOING || !hasGame) return
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
            if (running == Side.ATTACKERS) attackersBaseMs = left else defendersBaseMs = left
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

    private fun resetGame(config: TaflConfig, side: Side) {
        cancelSearch()
        stopClock()
        clockPaused = false
        game = Tafl.newGame(config.variant)
        played.clear()
        takebacksUsed = 0
        resigned = false
        flagged = null
        hasGame = true
        _state.update { it.copy(config = config, playerSide = side, selected = null, targets = emptySet(), runningClock = null) }
    }

    private fun finishLoad() {
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove()
    }

    private fun playPlayerMove(move: Move) {
        applyMove(move, clock = true)
        clearSelection()
        publish()
        persist()
        maybeEngineMove()
    }

    private fun clearSelection() = _state.update { it.copy(selected = null, targets = emptySet()) }

    private fun applyMove(move: Move, clock: Boolean) {
        val mover = Tafl.sideToMove(game)
        if (!Tafl.play(game, move)) return
        played += move
        if (clock) onMovePlayed(mover)
    }

    /** OpenTafl's game object has no undo: rebuild it from the shortened move list. */
    private fun undoOne() {
        played.removeAt(played.size - 1)
        val (g, moves) = Tafl.replay(_state.value.config.variant, played.map { it.toNotation() })
        game = g
        played.clear(); played += moves
    }

    private fun outcome(): Tafl.Outcome = when {
        resigned -> if (_state.value.playerSide == Side.ATTACKERS) Tafl.Outcome.DEFENDERS_WIN else Tafl.Outcome.ATTACKERS_WIN
        flagged != null -> if (flagged == Side.ATTACKERS) Tafl.Outcome.DEFENDERS_WIN else Tafl.Outcome.ATTACKERS_WIN
        else -> Tafl.outcome(game)
    }

    private fun isGameOver() = outcome() != Tafl.Outcome.ONGOING

    private fun maybeEngineMove() {
        if (Tafl.sideToMove(game) == _state.value.playerSide || isGameOver()) return
        val gen = ++generation
        val cfg = _state.value.config
        val g = game
        val seconds = engineThinkSeconds()
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            val move = engine.bestMove(g, cfg.depth, seconds)
            if (gen != generation) return@launch
            _state.update { it.copy(thinking = false) }
            if (move != null && !isGameOver()) applyMove(move, clock = true)
            publish()
            persist()
        }
    }

    private fun engineThinkSeconds(): Int {
        val cfg = _state.value.config
        val base = TaflEngineKind.thinkSeconds(cfg.depth)
        val remaining = currentMs(Tafl.sideToMove(game)) ?: return base
        val budgetMs = when (val tc = cfg.timeControl) {
            is TimeControl.PerMove -> tc.perMoveMs / 2
            is TimeControl.Fischer -> remaining / 10 + tc.incrementMs / 2
            else -> remaining / 10
        }
        return minOf(base.toLong(), budgetMs / 1000).coerceAtLeast(1L).toInt()
    }

    private fun cancelSearch() {
        generation++
        if (_state.value.thinking) engine.stop()
        _state.update { it.copy(thinking = false) }
    }

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        if (!hasGame) return
        if (isGameOver()) { repo.clearTaflGame(); return }
        repo.saveTaflGame(
            SavedTaflGame(
                config = _state.value.config,
                playerSide = _state.value.playerSide,
                moves = played.map { it.toNotation() },
                attackersMs = currentMs(Side.ATTACKERS),
                defendersMs = currentMs(Side.DEFENDERS),
            ),
        )
    }

    // ---------------------------------------------------------------- clock

    private fun hasClock() = _state.value.config.timeControl != TimeControl.None

    private fun currentMs(side: Side): Long? {
        if (!hasClock()) return null
        val base = if (side == Side.ATTACKERS) attackersBaseMs else defendersBaseMs
        val elapsed = if (_state.value.runningClock == side) SystemClock.elapsedRealtime() - turnStartedAt else 0L
        return (base - elapsed).coerceAtLeast(0L)
    }

    private fun onMovePlayed(mover: Side) {
        if (!hasClock()) return
        val tc = _state.value.config.timeControl
        val now = SystemClock.elapsedRealtime()
        if (_state.value.runningClock == mover) {
            var left = (if (mover == Side.ATTACKERS) attackersBaseMs else defendersBaseMs) - (now - turnStartedAt)
            left = when (tc) {
                is TimeControl.Fischer -> left + tc.incrementMs
                is TimeControl.PerMove -> tc.perMoveMs
                else -> left
            }.coerceAtLeast(0L)
            if (mover == Side.ATTACKERS) attackersBaseMs = left else defendersBaseMs = left
        } else if (tc is TimeControl.PerMove) {
            if (mover == Side.ATTACKERS) attackersBaseMs = tc.perMoveMs else defendersBaseMs = tc.perMoveMs
        }
        restartTurnClock()
    }

    private fun restartTurnClock() {
        if (!hasClock() || clockPaused || played.isEmpty() || isGameOver()) { stopClock(); return }
        turnStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(runningClock = Tafl.sideToMove(game)) }
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
                    if (running == Side.ATTACKERS) attackersBaseMs = 0 else defendersBaseMs = 0
                    cancelSearch()
                    _state.update { it.copy(runningClock = null) }
                    publish()
                    persist()
                    break
                }
                _state.update { it.copy(attackersMs = currentMs(Side.ATTACKERS), defendersMs = currentMs(Side.DEFENDERS)) }
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
        val engineName = _state.value.config.engine.label
        val out = outcome()
        val result = when (out) {
            Tafl.Outcome.ONGOING -> TaflResult.ONGOING
            Tafl.Outcome.DRAW -> TaflResult.DRAW
            Tafl.Outcome.ATTACKERS_WIN -> if (player == Side.ATTACKERS) TaflResult.PLAYER_WINS else TaflResult.ENGINE_WINS
            Tafl.Outcome.DEFENDERS_WIN -> if (player == Side.DEFENDERS) TaflResult.PLAYER_WINS else TaflResult.ENGINE_WINS
        }
        val toMove = Tafl.sideToMove(game)
        val status = when {
            resigned -> "You resigned."
            flagged != null -> if (flagged == player) "You ran out of time." else "$engineName ran out of time."
            result == TaflResult.PLAYER_WINS -> "You won" + (if (player == Side.DEFENDERS) ": the king escaped." else ": the king is taken.")
            result == TaflResult.ENGINE_WINS -> "$engineName won" + (if (player == Side.ATTACKERS) ": the king escaped." else ": the king is taken.")
            result == TaflResult.DRAW -> "Draw."
            _state.value.thinking -> "$engineName is thinking…"
            toMove == player -> if (player == Side.DEFENDERS) {
                if (_state.value.config.variant.escapeToCorners) "Your move: get the king to a corner."
                else "Your move: get the king to an edge."
            } else "Your move: surround the king."
            else -> "$engineName to move."
        }
        _state.update {
            it.copy(
                squares = Tafl.board(game),
                size = Tafl.size(game),
                sideToMove = toMove,
                lastMove = played.lastOrNull(),
                captured = Tafl.lastCaptures(game),
                moves = played.map { m -> m.toNotation() },
                result = result,
                statusText = status,
                attackersMs = currentMs(Side.ATTACKERS),
                defendersMs = currentMs(Side.DEFENDERS),
                takebacksLeft = it.config.takebacks.limit?.let { limit -> (limit - takebacksUsed).coerceAtLeast(0) },
            )
        }
    }
}
