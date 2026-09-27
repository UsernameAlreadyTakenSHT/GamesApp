package io.github.usernamealreadytakensht.games.game.fox

import kotlinx.coroutines.Dispatchers
import io.github.usernamealreadytakensht.games.game.record.ExportedGame
import io.github.usernamealreadytakensht.games.game.record.Exports
import io.github.usernamealreadytakensht.games.game.record.GameRecord
import io.github.usernamealreadytakensht.games.game.record.HistoryRepository
import io.github.usernamealreadytakensht.games.game.record.PlayerResult
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.usernamealreadytakensht.games.engine.fox.FoxEngine
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.SavedFoxGame
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.clockShown
import io.github.usernamealreadytakensht.games.game.fox.Fox.Move
import io.github.usernamealreadytakensht.games.game.fox.Fox.Position
import io.github.usernamealreadytakensht.games.game.fox.Fox.Side
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class FoxResult { ONGOING, PLAYER_WINS, ENGINE_WINS }

/** Immutable snapshot of a fox game, consumed by the UI. */
data class FoxState(
    val config: FoxConfig = FoxConfig(),
    /** Piece per point, see [Fox.EMPTY] / [Fox.FOX] / [Fox.HUNTER]. */
    val points: List<Int> = emptyList(),
    val playerSide: Side = Side.FOX,
    val sideToMove: Side = Side.FOX,
    val selected: Int? = null,
    val targets: Set<Int> = emptySet(),
    val lastMove: Move? = null,
    val moves: List<String> = emptyList(),
    val thinking: Boolean = false,
    val result: FoxResult = FoxResult.ONGOING,
    val statusText: String = "",
    val foxMs: Long? = null,
    val huntersMs: Long? = null,
    val runningClock: Side? = null,
) {
    val variant: FoxVariant get() = config.variant
    val canUndo: Boolean get() = moves.isNotEmpty() && result == FoxResult.ONGOING
    val isPlayerTurn: Boolean get() = sideToMove == playerSide && result == FoxResult.ONGOING && !thinking
    val engineSide: Side get() = playerSide.other
    fun clockMs(side: Side): Long? = if (side == Side.FOX) foxMs else huntersMs
}

class FoxViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GameRepository(app)
    private val records = HistoryRepository(app)
    /** Whether the finished game was already added to the history. */
    private var recorded = false
    private val engine = FoxEngine()

    private var position: Position = Fox.start(FoxVariant.HOUNDS)
    private val history = ArrayList<Position>()
    private val played = ArrayList<Move>()
    private var generation = 0
    private var resigned = false
    private var flagged: Side? = null

    var hasGame = false
        private set

    private var foxBaseMs = 0L
    private var huntersBaseMs = 0L
    private var turnStartedAt = 0L
    private var clockJob: Job? = null
    private var clockPaused = false

    private val _state = MutableStateFlow(FoxState())
    val state: StateFlow<FoxState> = _state

    init { publish() }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: FoxConfig) {
        resetGame(config, config.playerSide ?: if (Random.nextBoolean()) Side.FOX else Side.HUNTERS)
        val initial = config.timeControl.startingMs
        foxBaseMs = initial ?: 0L
        huntersBaseMs = initial ?: 0L
        finishLoad()
    }

    fun resumeGame(): Boolean = try {
        loadSavedGame()
    } catch (e: Exception) {
        // A save this version cannot replay (corrupted, or from an incompatible build) would
        // crash every Resume: drop it and go back to the menu instead.
        android.util.Log.w("GameResume", "Discarding an unreadable saved game", e)
        repo.clearFoxGame()
        hasGame = false
        false
    }

    private fun loadSavedGame(): Boolean {
        if (hasGame) { resumeClock(); return true }
        val saved = repo.loadFoxGame() ?: return false
        resetGame(saved.config, saved.playerSide)
        for (text in saved.moves) {
            val m = Fox.parse(saved.config.variant, position, text) ?: break
            applyMove(m, clock = false)
        }
        foxBaseMs = saved.foxMs ?: 0L
        huntersBaseMs = saved.huntersMs ?: 0L
        finishLoad()
        return true
    }

    fun onPointTapped(point: Int) {
        val s = _state.value
        if (!s.isPlayerTurn) return
        val legal = Fox.legalMoves(position)
        val selected = s.selected
        if (selected != null && point in s.targets) {
            // Several jump paths may end on the same point: take the one capturing most.
            val move = legal.filter { it.from == selected && it.to == point }.maxByOrNull { it.captures.size }
            if (move != null) { playPlayerMove(move); return }
        }
        val own = legal.filter { it.from == point }
        if (own.isNotEmpty()) {
            _state.update { it.copy(selected = point, targets = own.map { m -> m.to }.toSet()) }
        } else {
            _state.update { it.copy(selected = null, targets = emptySet()) }
        }
    }

    fun undo() {
        val s = _state.value
        if (played.isEmpty() || !s.canUndo) return
        cancelSearch()
        undoOne()
        if (position.toMove != s.playerSide && played.isNotEmpty()) undoOne()
        clearSelection()
        // Time already spent on this turn is not refunded.
        chargeRunningClock()
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove()
    }

    fun resign() {
        if (_state.value.result != FoxResult.ONGOING || !hasGame) return
        cancelSearch()
        resigned = true
        stopClock()
        clearSelection()
        publish()
        persist()
    }

    fun rematch() = startGame(_state.value.config.copy(playerSide = _state.value.engineSide))

    /** Freezes the running side's remaining time as its base (the turn's time is spent). */
    private fun chargeRunningClock() {
        val running = _state.value.runningClock ?: return
        val left = currentMs(running) ?: 0L
        if (running == Side.FOX) foxBaseMs = left else huntersBaseMs = left
    }

    fun pauseClock() {
        if (!hasClock() || clockPaused) return
        clockPaused = true
        chargeRunningClock()
        stopClock()
        persist()
    }

    fun resumeClock() {
        if (!clockPaused) return
        clockPaused = false
        restartTurnClock()
    }

    // ---------------------------------------------------------------- game flow

    private fun resetGame(config: FoxConfig, side: Side) {
        cancelSearch()
        stopClock()
        clockPaused = false
        position = Fox.start(config.variant)
        history.clear(); history += position
        played.clear()
        resigned = false
        flagged = null
        hasGame = true
        recorded = false
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
        val mover = position.toMove
        position = Fox.play(position, move)
        history += position
        played += move
        if (clock) onMovePlayed(mover)
    }

    private fun undoOne() {
        played.removeAt(played.size - 1)
        history.removeAt(history.size - 1)
        position = history.last()
    }

    private fun outcome(): Fox.Outcome = when {
        resigned -> if (_state.value.playerSide == Side.FOX) Fox.Outcome.HUNTERS_WINS else Fox.Outcome.FOX_WINS
        flagged != null -> if (flagged == Side.FOX) Fox.Outcome.HUNTERS_WINS else Fox.Outcome.FOX_WINS
        else -> Fox.outcome(position)
    }

    private fun isGameOver() = outcome() != Fox.Outcome.ONGOING

    private fun maybeEngineMove() {
        if (position.toMove == _state.value.playerSide || isGameOver()) return
        val gen = ++generation
        val cfg = _state.value.config
        val pos = position
        val moveTime = engineMoveTimeMs()
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            val move = try {
                engine.bestMove(pos, cfg.depth, moveTime)
            } catch (e: Exception) {
                // Never crash the game over the AI: log it and fall back on any legal move.
                Log.w(TAG, "Reynard search failed", e)
                null
            } ?: Fox.legalMoves(pos).randomOrNull()
            if (gen != generation) return@launch
            _state.update { it.copy(thinking = false) }
            if (move != null && !isGameOver()) applyMove(move, clock = true)
            publish()
            persist()
        }
    }

    private fun engineMoveTimeMs(): Int {
        val cfg = _state.value.config
        val base = if (cfg.depth == null) 1500L else 4000L
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
        if (_state.value.thinking) engine.stop()
        _state.update { it.copy(thinking = false) }
    }

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        if (!hasGame) return
        if (isGameOver()) { repo.clearFoxGame(); return }
        val v = _state.value.config.variant
        repo.saveFoxGame(
            SavedFoxGame(
                config = _state.value.config,
                playerSide = _state.value.playerSide,
                moves = played.map { Fox.notation(v, it) },
                foxMs = currentMs(Side.FOX),
                huntersMs = currentMs(Side.HUNTERS),
            ),
        )
    }

    // ---------------------------------------------------------------- clock

    private fun hasClock() = _state.value.config.timeControl != TimeControl.None

    private fun currentMs(side: Side): Long? {
        if (!hasClock()) return null
        val base = if (side == Side.FOX) foxBaseMs else huntersBaseMs
        val elapsed = if (_state.value.runningClock == side) SystemClock.elapsedRealtime() - turnStartedAt else 0L
        return (base - elapsed).coerceAtLeast(0L)
    }

    private fun onMovePlayed(mover: Side) {
        if (!hasClock()) return
        val tc = _state.value.config.timeControl
        val now = SystemClock.elapsedRealtime()
        if (_state.value.runningClock == mover) {
            var left = (if (mover == Side.FOX) foxBaseMs else huntersBaseMs) - (now - turnStartedAt)
            left = when (tc) {
                is TimeControl.Fischer -> left + tc.incrementMs
                is TimeControl.PerMove -> tc.perMoveMs
                else -> left
            }.coerceAtLeast(0L)
            if (mover == Side.FOX) foxBaseMs = left else huntersBaseMs = left
        } else if (tc is TimeControl.PerMove) {
            if (mover == Side.FOX) foxBaseMs = tc.perMoveMs else huntersBaseMs = tc.perMoveMs
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
                    if (running == Side.FOX) foxBaseMs = 0 else huntersBaseMs = 0
                    cancelSearch()
                    _state.update { it.copy(runningClock = null) }
                    publish()
                    persist()
                    break
                }
                val foxMsNow = currentMs(Side.FOX)
                val huntersMsNow = currentMs(Side.HUNTERS)
                if (clockShown(foxMsNow) != clockShown(_state.value.foxMs) || clockShown(huntersMsNow) != clockShown(_state.value.huntersMs)) {
                    _state.update { it.copy(foxMs = foxMsNow, huntersMs = huntersMsNow) }
                }
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
        val v = _state.value.config.variant
        val engineName = _state.value.config.engine.label
        val result = when (outcome()) {
            Fox.Outcome.ONGOING -> FoxResult.ONGOING
            Fox.Outcome.FOX_WINS -> if (player == Side.FOX) FoxResult.PLAYER_WINS else FoxResult.ENGINE_WINS
            Fox.Outcome.HUNTERS_WINS -> if (player == Side.HUNTERS) FoxResult.PLAYER_WINS else FoxResult.ENGINE_WINS
        }
        val status = when {
            resigned -> "You resigned."
            flagged != null -> if (flagged == player) "You ran out of time." else "$engineName ran out of time."
            result == FoxResult.PLAYER_WINS -> "You won."
            result == FoxResult.ENGINE_WINS -> "$engineName won."
            _state.value.thinking -> "$engineName is thinking…"
            position.toMove == player -> when {
                player == Side.FOX && v == FoxVariant.HOUNDS -> "Your move: slip past the hounds."
                player == Side.FOX && v == FoxVariant.ASALTO -> "Your move: thin out the sepoys (${position.hunters()} left)."
                player == Side.FOX -> "Your move: jump the ${v.hunterName} (${position.hunters()} left)."
                v == FoxVariant.HOUNDS -> "Your move: close the net."
                v == FoxVariant.ASALTO -> "Your move: press into the fortress."
                else -> "Your move: corner the ${v.foxName} (${position.hunters()} geese)."
            }
            else -> "$engineName to move."
        }
        _state.update {
            it.copy(
                points = List(v.board.points) { p -> position[p] },
                sideToMove = position.toMove,
                lastMove = played.lastOrNull(),
                moves = played.map { m -> Fox.notation(v, m) },
                result = result,
                statusText = status,
                foxMs = currentMs(Side.FOX),
                huntersMs = currentMs(Side.HUNTERS),
            )
        }
        recordIfFinished()
    }

    private companion object {
        const val TAG = "FoxViewModel"
    }

    // ---------------------------------------------------------------- export & history

    /** The current game as a file (plain text), for the Share button and the history. */
    fun exportGame(): ExportedGame {
        val s = _state.value
        val now = System.currentTimeMillis()
        val result = when (s.result) {
            FoxResult.PLAYER_WINS -> "You won"
            FoxResult.ENGINE_WINS -> "${s.config.opponentLabel} won"
            FoxResult.ONGOING -> "In progress"
            else -> "Draw"
        }
        val v = s.config.variant
        val fox = if (s.playerSide == Side.FOX) "You" else s.config.opponentLabel
        val hunters = if (s.playerSide == Side.HUNTERS) "You" else s.config.opponentLabel
        val header = listOf(
            v.foxName.replaceFirstChar { it.uppercase() } to fox,
            v.hunterName.replaceFirstChar { it.uppercase() } to hunters,
            "Result" to result, "Status" to s.statusText,
        )
        val text = Exports.plainText(v.label, now, header, s.moves)
        return ExportedGame("fox-${Exports.fileStamp(now)}.txt", "text/plain", text)
    }

    /** A game that just ended goes to the history, once. */
    private fun recordIfFinished() {
        val s = _state.value
        if (!hasGame || recorded || s.result == FoxResult.ONGOING) return
        recorded = true
        val record = GameRecord(
            endedAt = System.currentTimeMillis(),
            game = "Fox games",
            variant = s.config.variant.label,
            opponent = s.config.opponentLabel,
            playerSide = (if (s.playerSide == Side.FOX) s.config.variant.foxName else s.config.variant.hunterName).replaceFirstChar { it.uppercase() },
            result = when (s.result) {
                FoxResult.PLAYER_WINS -> PlayerResult.WIN
                FoxResult.ENGINE_WINS -> PlayerResult.LOSS
                else -> PlayerResult.DRAW
            },
            reason = s.statusText,
            moveCount = s.moves.size,
            export = exportGame(),
        )
        viewModelScope.launch(Dispatchers.IO) { records.add(record) }
    }
}
