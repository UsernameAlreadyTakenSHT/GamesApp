package io.github.usernamealreadytakensht.games.game.tafl

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
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.manywords.softworks.tafl.engine.Game
import io.github.usernamealreadytakensht.games.engine.tafl.OpenTaflEngine
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.SavedTaflGame
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.clockShown
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

fun sideName(side: Side): String = if (side == Side.ATTACKERS) "Attackers" else "Defenders"

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
    /** Squares of the suggested move, shown until a move is played. */
    val hint: Set<Pair<Int, Int>> = emptySet(),
    val hintText: String? = null,
    val hintLoading: Boolean = false,
    val attackersMs: Long? = null,
    val defendersMs: Long? = null,
    val runningClock: Side? = null,
) {
    val canUndo: Boolean get() = moves.isNotEmpty() && result == TaflResult.ONGOING
    val isPlayerTurn: Boolean get() = (config.twoPlayers || sideToMove == playerSide) && result == TaflResult.ONGOING && !thinking
    fun sideLabel(side: Side): String = when {
        config.twoPlayers -> sideName(side)
        side == playerSide -> "You · ${sideName(side).lowercase()}"
        else -> "${config.opponentLabel} · ${sideName(side).lowercase()}"
    }
    fun lost(side: Side): Boolean =
        (result == TaflResult.PLAYER_WINS && side != playerSide) || (result == TaflResult.ENGINE_WINS && side == playerSide)
    val engineSide: Side get() = playerSide.other
    fun clockMs(side: Side): Long? = if (side == Side.ATTACKERS) attackersMs else defendersMs
}

class TaflViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GameRepository(app)
    private val records = HistoryRepository(app)
    /** Whether the finished game was already added to the history. */
    private var recorded = false
    private var hintJob: Job? = null
    private val engine = OpenTaflEngine()

    private var game: Game = Tafl.newGame(TaflVariant.COPENHAGEN)
    private val played = ArrayList<Move>()
    private var generation = 0
    private var resigned = false
    private var resignedSide: Side = Side.ATTACKERS
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
        resetGame(config, if (config.twoPlayers) Side.ATTACKERS else config.playerSide ?: if (Random.nextBoolean()) Side.ATTACKERS else Side.DEFENDERS)
        val initial = config.timeControl.startingMs
        attackersBaseMs = initial ?: 0L
        defendersBaseMs = initial ?: 0L
        finishLoad()
    }

    /** Resumes the saved game if any; a game already held by this ViewModel is kept. */
    fun resumeGame(): Boolean = try {
        loadSavedGame()
    } catch (e: Exception) {
        // A save this version cannot replay (corrupted, or from an incompatible build) would
        // crash every Resume: drop it and go back to the menu instead.
        android.util.Log.w("GameResume", "Discarding an unreadable saved game", e)
        repo.clearTaflGame()
        hasGame = false
        false
    }

    private fun loadSavedGame(): Boolean {
        if (hasGame) {
            resumeClock()
            maybeEngineMove() // the search was stopped when the screen was left
            return true
        }
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
        clearHint()
        undoOne()
        if (!s.config.twoPlayers && Tafl.sideToMove(game) != s.playerSide && played.isNotEmpty()) undoOne()
        clearSelection()
        // Time already spent on this turn is not refunded.
        chargeRunningClock()
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove()
    }

    /** The game screen was left: stop any search so it does not run in the background. Resume searches again. */
    fun releaseEngine() {
        stopHint()
        cancelSearch()
    }

    fun resign() {
        if (_state.value.result != TaflResult.ONGOING || !hasGame) return
        cancelSearch()
        resigned = true
        resignedSide = if (_state.value.config.twoPlayers) Tafl.sideToMove(game) else _state.value.playerSide
        stopClock()
        clearHint()
        clearSelection()
        publish()
        persist()
    }

    fun rematch() = startGame(_state.value.config.let { if (it.twoPlayers) it else it.copy(playerSide = _state.value.engineSide) })

    /** Freezes the running side's remaining time as its base (the turn's time is spent). */
    private fun chargeRunningClock() {
        val running = _state.value.runningClock ?: return
        val left = currentMs(running) ?: 0L
        if (running == Side.ATTACKERS) attackersBaseMs = left else defendersBaseMs = left
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

    private fun resetGame(config: TaflConfig, side: Side) {
        cancelSearch()
        stopClock()
        clockPaused = false
        game = Tafl.newGame(config.variant)
        played.clear()
        resigned = false
        flagged = null
        hasGame = true
        recorded = false
        clearHint()
        _state.update { it.copy(config = config, playerSide = side, selected = null, targets = emptySet(), runningClock = null) }
    }

    private fun finishLoad() {
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove()
    }

    private fun playPlayerMove(move: Move) {
        stopHint()
        applyMove(move, clock = true)
        clearSelection()
        publish()
        persist()
        maybeEngineMove()
    }

    private fun clearSelection() = _state.update { it.copy(selected = null, targets = emptySet()) }

    private fun applyMove(move: Move, clock: Boolean) {
        clearHint()
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
        resigned -> if (resignedSide == Side.ATTACKERS) Tafl.Outcome.DEFENDERS_WIN else Tafl.Outcome.ATTACKERS_WIN
        flagged != null -> if (flagged == Side.ATTACKERS) Tafl.Outcome.DEFENDERS_WIN else Tafl.Outcome.ATTACKERS_WIN
        else -> Tafl.outcome(game)
    }

    private fun isGameOver() = outcome() != Tafl.Outcome.ONGOING

    private fun maybeEngineMove() {
        if (_state.value.config.twoPlayers) return
        if (Tafl.sideToMove(game) == _state.value.playerSide || isGameOver()) return
        val gen = ++generation
        val cfg = _state.value.config
        val g = searchCopy()
        val budgetMs = engineThinkMs()
        // OpenTafl thinks in whole seconds: a shorter budget is enforced by stopping it early.
        val seconds = ((budgetMs + 999) / 1000).toInt().coerceAtLeast(1)
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            // A hint search finishes first: two OpenTafl searches at once share its static tables.
            hintJob?.join()
            if (gen != generation) return@launch // cancelled while waiting: its stop() found no search to end
            val timer = if (budgetMs < seconds * 1000L) launch {
                delay(budgetMs)
                if (gen == generation) engine.stop()
            } else null
            val move = try {
                engine.bestMove(g, cfg.depth, seconds)
            } catch (e: Exception) {
                // Never crash the game over the AI: log it and fall back on any legal move.
                Log.w(TAG, "OpenTafl search failed", e)
                null
            } finally {
                timer?.cancel() // so it cannot stop a later search (a hint)
            } ?: Tafl.legalMoves(g).randomOrNull()
            if (gen != generation) return@launch
            _state.update { it.copy(thinking = false) }
            if (move != null && !isGameOver()) applyMove(move, clock = true)
            publish()
            persist()
        }
    }

    /**
     * A private copy of the game for a search: OpenTafl's AI counts the positions it explores
     * in the game's own repetition table, which must not see them (a move played while a hint
     * search unwinds, or a search that fails half-way, would leave false repetitions behind).
     */
    private fun searchCopy(): Game = Tafl.replay(_state.value.config.variant, played.map { it.toNotation() }).first

    /** Thinking budget in ms: the depth's usual time, capped by the engine's own clock. */
    private fun engineThinkMs(): Long {
        val cfg = _state.value.config
        val base = TaflEngineKind.thinkSeconds(cfg.depth) * 1000L
        val remaining = currentMs(Tafl.sideToMove(game)) ?: return base
        val budgetMs = when (val tc = cfg.timeControl) {
            // What is left of this move's time (less than the full period after a pause).
            is TimeControl.PerMove -> remaining / 2
            is TimeControl.Fischer -> remaining / 10 + tc.incrementMs / 2
            else -> remaining / 10
        }
        return minOf(base, budgetMs).coerceAtLeast(100L)
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
                    clearHint()
                    _state.update { it.copy(runningClock = null) }
                    publish()
                    persist()
                    break
                }
                val attackersMsNow = currentMs(Side.ATTACKERS)
                val defendersMsNow = currentMs(Side.DEFENDERS)
                if (clockShown(attackersMsNow) != clockShown(_state.value.attackersMs) || clockShown(defendersMsNow) != clockShown(_state.value.defendersMs)) {
                    _state.update { it.copy(attackersMs = attackersMsNow, defendersMs = defendersMsNow) }
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
        val engineName = _state.value.config.engine.label
        val out = outcome()
        val result = when (out) {
            Tafl.Outcome.ONGOING -> TaflResult.ONGOING
            Tafl.Outcome.DRAW -> TaflResult.DRAW
            Tafl.Outcome.ATTACKERS_WIN -> if (player == Side.ATTACKERS) TaflResult.PLAYER_WINS else TaflResult.ENGINE_WINS
            Tafl.Outcome.DEFENDERS_WIN -> if (player == Side.DEFENDERS) TaflResult.PLAYER_WINS else TaflResult.ENGINE_WINS
        }
        val toMove = Tafl.sideToMove(game)
        val corners = _state.value.config.variant.escapeToCorners
        // How the game was won: the king's capture or escape are named; other wins (edge fort,
        // encirclement, repetition, a side left without a move) just say who won.
        val size = Tafl.size(game)
        val king = Tafl.board(game).indexOf(Tafl.KING)
        val kx = king % size
        val ky = king / size
        val escaped = king >= 0 &&
            if (corners) Tafl.isCorner(size, kx, ky) else kx == 0 || ky == 0 || kx == size - 1 || ky == size - 1
        val how = when (out) {
            Tafl.Outcome.ATTACKERS_WIN -> if (king < 0) ": the king is taken." else "."
            Tafl.Outcome.DEFENDERS_WIN -> if (escaped) ": the king escaped." else "."
            else -> "."
        }
        val status = if (_state.value.config.twoPlayers) when {
            resigned -> "${sideName(resignedSide)} resigned — ${sideName(resignedSide.other)} win."
            flagged != null -> "Time's up — ${sideName(flagged!!.other)} win."
            out == Tafl.Outcome.DEFENDERS_WIN -> "Defenders win$how"
            out == Tafl.Outcome.ATTACKERS_WIN -> "Attackers win$how"
            result == TaflResult.DRAW -> "Draw."
            toMove == Side.DEFENDERS -> if (corners) "Defenders: get the king to a corner." else "Defenders: get the king to an edge."
            else -> "Attackers: surround the king."
        } else when {
            resigned -> "You resigned."
            flagged != null -> if (flagged == player) "You ran out of time." else "$engineName ran out of time."
            result == TaflResult.PLAYER_WINS -> "You won$how"
            result == TaflResult.ENGINE_WINS -> "$engineName won$how"
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
                statusText = _state.value.hintText?.takeIf { result == TaflResult.ONGOING }?.let { h -> "Hint: $h" } ?: status,
                attackersMs = currentMs(Side.ATTACKERS),
                defendersMs = currentMs(Side.DEFENDERS),
            )
        }
        recordIfFinished()
    }

    private companion object {
        const val TAG = "TaflViewModel"
    }

    // ---------------------------------------------------------------- export & history

    /** The current game as a file (plain text), for the Share button and the history. */
    fun exportGame(): ExportedGame {
        val s = _state.value
        val now = System.currentTimeMillis()
        val result = when (s.result) {
            TaflResult.PLAYER_WINS -> if (s.config.twoPlayers) "${sideName(s.playerSide)} won" else "You won"
            TaflResult.ENGINE_WINS -> if (s.config.twoPlayers) "${sideName(s.engineSide)} won" else "${s.config.opponentLabel} won"
            TaflResult.ONGOING -> "In progress"
            else -> "Draw"
        }
        val attackers = if (s.config.twoPlayers) "Attacking player" else if (s.playerSide == Side.ATTACKERS) "You" else s.config.opponentLabel
        val defenders = if (s.config.twoPlayers) "Defending player" else if (s.playerSide == Side.DEFENDERS) "You" else s.config.opponentLabel
        val header = listOf("Attackers" to attackers, "Defenders" to defenders, "Result" to result, "Status" to s.statusText)
        val text = Exports.plainText("${s.config.variant.label} (hnefatafl)", now, header, s.moves)
        return ExportedGame("hnefatafl-${Exports.fileStamp(now)}.txt", "text/plain", text)
    }

    /** A game that just ended goes to the history, once. */
    private fun recordIfFinished() {
        val s = _state.value
        if (!hasGame || recorded || s.result == TaflResult.ONGOING) return
        recorded = true
        val record = GameRecord(
            endedAt = System.currentTimeMillis(),
            game = "Hnefatafl",
            variant = s.config.variant.label,
            opponent = if (s.config.twoPlayers) sideName(s.engineSide) else s.config.opponentLabel,
            playerSide = sideName(s.playerSide),
            result = when (s.result) {
                TaflResult.PLAYER_WINS -> PlayerResult.WIN
                TaflResult.ENGINE_WINS -> PlayerResult.LOSS
                else -> PlayerResult.DRAW
            },
            reason = s.statusText,
            moveCount = s.moves.size,
            export = exportGame(),
            twoPlayers = s.config.twoPlayers,
        )
        viewModelScope.launch(Dispatchers.IO) { records.add(record) }
    }

    // ---------------------------------------------------------------- hint

    /** Asks the engine, at full strength, for a move for the player; shown until a move is played. */
    fun requestHint() {
        val s = _state.value
        if (!s.isPlayerTurn || s.hintLoading || !hasGame) return
        val count = played.size
        val live = game
        // The player may move while the search unwinds: it runs on a copy (see searchCopy).
        val g = searchCopy()
        _state.update { it.copy(hintLoading = true) }
        hintJob = viewModelScope.launch {
            val move = runCatching { engine.bestMove(g, null, 2) }.getOrNull() // no depth limit: full strength
            _state.update { it.copy(hintLoading = false) }
            // Same position only: an undo rebuilds the game object, then a move restores the count.
            if (move == null || game !== live || played.size != count || isGameOver()) return@launch
            _state.update { it.copy(hint = setOf(move.fromX to move.fromY, move.toX to move.toY), hintText = move.toNotation()) }
            publish()
        }
    }

    private fun clearHint() = _state.update { it.copy(hint = emptySet(), hintText = null) }

    /** The player moved while a hint was being searched: end that search now. */
    private fun stopHint() {
        if (hintJob?.isActive == true) engine.stop()
    }
}
