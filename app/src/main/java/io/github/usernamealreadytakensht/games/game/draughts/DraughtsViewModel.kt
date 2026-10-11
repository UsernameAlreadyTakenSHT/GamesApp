package io.github.usernamealreadytakensht.games.game.draughts

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
import io.github.usernamealreadytakensht.games.engine.draughts.DraughtsEngine
import io.github.usernamealreadytakensht.games.engine.draughts.HubEngine
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.SavedDraughtsGame
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.clockShown
import io.github.usernamealreadytakensht.games.game.draughts.Draughts.Color
import io.github.usernamealreadytakensht.games.game.draughts.Draughts.Move
import io.github.usernamealreadytakensht.games.game.draughts.Draughts.Position
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

enum class DraughtsResult { ONGOING, PLAYER_WINS, ENGINE_WINS, DRAW }

fun colorName(c: Color): String = if (c == Color.WHITE) "White" else "Black"

/** Immutable snapshot of the draughts game, consumed by the UI. */
data class DraughtsState(
    val config: DraughtsConfig = DraughtsConfig(),
    /** Piece codes per square (index 1..50, see [Draughts]). */
    val squares: List<Int> = List(51) { Draughts.EMPTY },
    val playerSide: Color = Color.WHITE,
    val sideToMove: Color = Color.WHITE,
    val selected: Int? = null,
    /** Landing squares chosen so far of a multi-capture being entered. */
    val partialPath: List<Int> = emptyList(),
    /** Squares the current selection may go to next (or finish on). */
    val targets: Set<Int> = emptySet(),
    val lastMove: Move? = null,
    /** Moves in Hub notation, for the move list. */
    val moves: List<String> = emptyList(),
    val thinking: Boolean = false,
    val result: DraughtsResult = DraughtsResult.ONGOING,
    val statusText: String = "",
    /** Squares of the suggested move, shown until a move is played. */
    val hint: Set<Int> = emptySet(),
    val hintText: String? = null,
    val hintLoading: Boolean = false,
    val flipped: Boolean = false,
    val engineError: String? = null,
    val whiteMs: Long? = null,
    val blackMs: Long? = null,
    val runningClock: Color? = null,
) {
    val canUndo: Boolean get() = moves.isNotEmpty() && result == DraughtsResult.ONGOING
    val isPlayerTurn: Boolean get() = (config.twoPlayers || sideToMove == playerSide) && result == DraughtsResult.ONGOING && !thinking
    val engineSide: Color get() = playerSide.other
    /** The side whose pieces the screen's user moves now: theirs, or either side with two players. */
    val mover: Color get() = if (config.twoPlayers) sideToMove else playerSide
    /** Side shown at the bottom of the board (and of the clocks). */
    val bottomSide: Color get() = if (config.twoPlayers) (if (flipped) Color.BLACK else Color.WHITE) else playerSide
    val topSide: Color get() = bottomSide.other
    fun sideLabel(side: Color): String = when {
        config.twoPlayers -> colorName(side)
        side == playerSide -> "You"
        else -> config.opponentLabel
    }
    fun lost(side: Color): Boolean =
        (result == DraughtsResult.PLAYER_WINS && side != playerSide) || (result == DraughtsResult.ENGINE_WINS && side == playerSide)
    fun clockMs(side: Color): Long? = if (side == Color.WHITE) whiteMs else blackMs
}

class DraughtsViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private val repo = GameRepository(app)
    private val records = HistoryRepository(app)
    /** Whether the finished game was already added to the history. */
    private var recorded = false
    private var hintJob: Job? = null
    private var engine: DraughtsEngine? = null
    private val engineLock = Mutex()
    private var engineReady = false
    /** True while [finishLoad] starts the engine: searches wait for it to finish. */
    private var engineStarting = false
    /** Set while the game screen is not shown: no engine runs then. */
    private var released = false

    private var position: Position = Draughts.START
    private val history = ArrayList<Position>()   // positions before each move, plus current
    private val played = ArrayList<Move>()
    private var generation = 0
    private var resigned = false
    private var resignedSide: Color = Color.WHITE
    private var flagged: Color? = null

    var hasGame = false
        private set

    // Clock: remaining time frozen at the start of the turn + when the turn started.
    private var whiteBaseMs = 0L
    private var blackBaseMs = 0L
    private var turnStartedAt = 0L
    private var clockJob: Job? = null
    private var clockPaused = false

    private val _state = MutableStateFlow(DraughtsState())
    val state: StateFlow<DraughtsState> = _state

    init { publish() }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: DraughtsConfig) {
        resetGame(config, if (config.twoPlayers) Color.WHITE else config.playerSide ?: if (Random.nextBoolean()) Color.WHITE else Color.BLACK)
        val initial = config.timeControl.startingMs
        whiteBaseMs = initial ?: 0L
        blackBaseMs = initial ?: 0L
        finishLoad()
    }

    /** Resumes the saved game if any; a game already held by this ViewModel is kept. */
    fun resumeGame(): Boolean = try {
        loadSavedGame()
    } catch (e: Exception) {
        // A save this version cannot replay (corrupted, or from an incompatible build) would
        // crash every Resume: drop it and go back to the menu instead.
        android.util.Log.w("GameResume", "Discarding an unreadable saved game", e)
        repo.clearDraughtsGame(DraughtsVariant.INTERNATIONAL)
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
        val saved = repo.loadDraughtsGame(DraughtsVariant.INTERNATIONAL) ?: return false
        resetGame(saved.config, saved.playerSide)
        for (hub in saved.moves) {
            val m = Draughts.parseHub(position, hub) ?: break
            applyMove(m, clock = false)
        }
        whiteBaseMs = saved.whiteMs ?: 0L
        blackBaseMs = saved.blackMs ?: 0L
        finishLoad()
        return true
    }

    fun onSquareTapped(sq: Int) {
        val s = _state.value
        if (!s.isPlayerTurn || sq !in 1..50) return
        val selected = s.selected

        if (selected != null) {
            val candidates = candidatesFor(selected, s.partialPath)
            // Candidates whose next landing square is the tapped one: step into the capture,
            // playing it outright when only one move continues that way.
            val stepping = candidates.filter { it.path.getOrNull(s.partialPath.size) == sq }
            if (stepping.isNotEmpty()) {
                val path = s.partialPath + sq
                val complete = stepping.filter { it.path == path }
                if (stepping.size == 1) { playPlayerMove(stepping[0]); return }
                if (complete.size == stepping.size) { playPlayerMove(complete[0]); return }
                _state.update { it.copy(partialPath = path, targets = targetsFor(stepping, path)) }
                return
            }
            // A tap on a final square that identifies one move plays it (skipping the steps).
            val finishing = candidates.filter { it.to == sq }
            if (finishing.size == 1) { playPlayerMove(finishing[0]); return }
            if (finishing.size > 1) return // ambiguous: the user has to tap the intermediate squares
        }

        // Select / re-select one of the player's pieces.
        val moves = Draughts.legalMoves(position).filter { it.from == sq }
        if (Draughts.colorOf(position[sq]) == s.mover && moves.isNotEmpty()) {
            _state.update { it.copy(selected = sq, partialPath = emptyList(), targets = targetsFor(moves, emptyList())) }
        } else {
            _state.update { it.copy(selected = null, partialPath = emptyList(), targets = emptySet()) }
        }
    }

    private fun candidatesFor(from: Int, partial: List<Int>): List<Move> =
        Draughts.legalMoves(position).filter { it.from == from && it.path.take(partial.size) == partial }

    private fun targetsFor(candidates: List<Move>, partial: List<Int>): Set<Int> =
        candidates.mapNotNull { it.path.getOrNull(partial.size) }.toSet() + candidates.map { it.to }

    /** Takes back the player's last move (and the engine's reply, if any). */
    fun undo() {
        val s = _state.value
        if (played.isEmpty() || !s.canUndo) return
        cancelSearch()
        clearHint()
        undoOne()
        if (!s.config.twoPlayers && position.toMove != s.playerSide && played.isNotEmpty()) undoOne()
        _state.update { it.copy(selected = null, partialPath = emptyList(), targets = emptySet(), engineError = null) }
        // Time already spent on this turn is not refunded.
        chargeRunningClock()
        restartTurnClock()
        publish()
        persist()
        maybeEngineMove()
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

    fun resign() {
        if (_state.value.result != DraughtsResult.ONGOING || !hasGame) return
        cancelSearch()
        resigned = true
        resignedSide = if (_state.value.config.twoPlayers) position.toMove else _state.value.playerSide
        stopClock()
        _state.update { it.copy(selected = null, partialPath = emptyList(), targets = emptySet()) }
        publish()
        persist()
    }

    fun rematch() = startGame(_state.value.config.let { if (it.twoPlayers) it else it.copy(playerSide = _state.value.engineSide) })

    fun flipBoard() = _state.update { it.copy(flipped = !it.flipped) }

    /** Freezes the running side's remaining time as its base (the turn's time is spent). */
    private fun chargeRunningClock() {
        val running = _state.value.runningClock ?: return
        val left = currentMs(running) ?: 0L
        if (running == Color.WHITE) whiteBaseMs = left else blackBaseMs = left
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
        publish()
    }

    // ---------------------------------------------------------------- game logic

    private fun resetGame(config: DraughtsConfig, side: Color) {
        cancelSearch()
        stopClock()
        clockPaused = false
        position = Draughts.START
        history.clear(); history += position
        played.clear()
        resigned = false
        flagged = null
        hasGame = true
        recorded = false
        clearHint()
        _state.update {
            it.copy(config = config, playerSide = side, flipped = side == Color.BLACK,
                selected = null, partialPath = emptyList(), targets = emptySet(), runningClock = null)
        }
    }

    private fun finishLoad() {
        restartTurnClock()
        publish()
        persist()
        val config = _state.value.config
        _state.update { it.copy(engineError = null) }
        // Two players: no engine now; Hint starts one when asked.
        if (config.twoPlayers) return
        engineStarting = true
        released = false
        viewModelScope.launch {
            try {
                engineLock.withLock {
                    val eng = ensureEngine(config.engine)
                    eng.newGame()
                }
            } catch (e: Exception) {
                _state.update { it.copy(engineError = e.message ?: e.toString()) }
                publish()
                return@launch
            } finally {
                engineStarting = false
            }
            maybeEngineMove()
        }
    }

    /**
     * The engine for the current settings, restarted when its process died (killed in the
     * background, or by the watchdog) or when [restart] is asked after a failed search.
     */
    private suspend fun liveEngine(config: DraughtsConfig, restart: Boolean): DraughtsEngine {
        if (restart) engine?.quit()
        val before = engine
        val eng = ensureEngine(config.engine)
        if (eng !== before) eng.newGame()
        return eng
    }

    private suspend fun ensureEngine(kind: DraughtsEngineKind): DraughtsEngine {
        engine?.let { if (it.kind == kind && it.isRunning) return it }
        engineReady = false
        engine?.quit()
        _state.update { it.copy(engineError = null) }
        publish()
        val eng = HubEngine(app, kind)
        engine = eng
        eng.start()
        engineReady = true
        publish()
        return eng
    }

    private fun playPlayerMove(move: Move) {
        stopHint()
        applyMove(move, clock = true)
        _state.update { it.copy(selected = null, partialPath = emptyList(), targets = emptySet()) }
        publish()
        persist()
        maybeEngineMove()
    }

    private fun applyMove(move: Move, clock: Boolean) {
        clearHint()
        val mover = position.toMove
        position = Draughts.play(position, move)
        history += position
        played += move
        if (clock) onMovePlayed(mover)
    }

    private fun undoOne() {
        played.removeAt(played.size - 1)
        history.removeAt(history.size - 1)
        position = history.last()
    }

    /** Consecutive plies without a capture or a man move (draw counter, repetition window). */
    private val quietKingMoves: Int
        get() {
            var n = 0
            for (i in played.indices.reversed()) {
                if (Draughts.isProgress(history[i], played[i])) break
                n++
            }
            return n
        }

    private fun outcome(): Draughts.Outcome = when {
        resigned -> if (resignedSide == Color.WHITE) Draughts.Outcome.BLACK_WINS else Draughts.Outcome.WHITE_WINS
        flagged != null -> if (flagged == Color.WHITE) Draughts.Outcome.BLACK_WINS else Draughts.Outcome.WHITE_WINS
        else -> Draughts.outcome(position, history.dropLast(1), quietKingMoves)
    }

    private fun isGameOver() = outcome() != Draughts.Outcome.ONGOING

    private fun maybeEngineMove() {
        // The first start reports its own failure; later searches restart the engine themselves.
        if (released || engineStarting || _state.value.engineError != null) return
        if (_state.value.config.twoPlayers) return
        if (position.toMove == _state.value.playerSide || isGameOver()) return

        val gen = ++generation
        val cfg = _state.value.config
        // Reversible moves since the last capture / man move, for repetition detection. The Hub
        // protocol plays them from the given position, so send the position before them.
        val n = quietKingMoves
        val kingMoves = played.takeLast(n).map { it.toHub() }
        val pos = history[played.size - n]
        val moveTime = engineMoveTimeMs()
        _state.update { it.copy(thinking = true) }
        publish()
        viewModelScope.launch {
            hintJob?.join() // a hint search finishes (and restores the strength) first
            var move: Move? = null
            var failure: String? = null
            // A failed search (dead or hung engine, unusable reply) gets one retry on a fresh engine.
            for (attempt in 0..1) {
                try {
                    val eng = engineLock.withLock { liveEngine(cfg, restart = attempt > 0) }
                    val hub = eng.bestMove(pos, kingMoves, cfg.depth, moveTime)
                    if (gen != generation) return@launch
                    move = hub?.let { Draughts.parseHub(position, it) }
                    if (move != null) break
                    failure = "${cfg.engine.label} gave no legal move (${hub ?: "none"})"
                } catch (e: Exception) {
                    if (gen != generation) return@launch
                    failure = e.message ?: e.toString()
                }
            }
            _state.update { it.copy(thinking = false, engineError = if (move == null) failure else null) }
            if (move != null && !isGameOver()) applyMove(move, clock = true)
            publish()
            persist()
        }
    }

    private fun engineMoveTimeMs(): Int {
        val cfg = _state.value.config
        val base = if (cfg.depth == null) 1500L else 8000L
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

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        if (!hasGame) return
        if (isGameOver()) { repo.clearDraughtsGame(DraughtsVariant.INTERNATIONAL); return }
        repo.saveDraughtsGame(
            SavedDraughtsGame(
                config = _state.value.config,
                playerSide = _state.value.playerSide,
                moves = played.map { it.toHub() },
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
                    // A capture half entered by the player is dropped with the game.
                    _state.update { it.copy(runningClock = null, selected = null, partialPath = emptyList(), targets = emptySet()) }
                    publish()
                    persist()
                    break
                }
                val whiteMsNow = currentMs(Color.WHITE)
                val blackMsNow = currentMs(Color.BLACK)
                if (clockShown(whiteMsNow) != clockShown(_state.value.whiteMs) || clockShown(blackMsNow) != clockShown(_state.value.blackMs)) {
                    _state.update { it.copy(whiteMs = whiteMsNow, blackMs = blackMsNow) }
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
            Draughts.Outcome.ONGOING -> DraughtsResult.ONGOING
            Draughts.Outcome.DRAW -> DraughtsResult.DRAW
            Draughts.Outcome.WHITE_WINS -> if (player == Color.WHITE) DraughtsResult.PLAYER_WINS else DraughtsResult.ENGINE_WINS
            Draughts.Outcome.BLACK_WINS -> if (player == Color.BLACK) DraughtsResult.PLAYER_WINS else DraughtsResult.ENGINE_WINS
        }
        val twoPlayers = _state.value.config.twoPlayers
        val mustCapture = result == DraughtsResult.ONGOING && (twoPlayers || position.toMove == player) &&
            Draughts.legalMoves(position).firstOrNull()?.isCapture == true
        val toMove = colorName(position.toMove)
        val status = if (twoPlayers) when {
            resigned -> "${colorName(resignedSide)} resigned — ${colorName(resignedSide.other)} wins."
            flagged != null -> "Time's up — ${colorName(flagged!!.other)} wins."
            result == DraughtsResult.DRAW -> "Draw."
            result != DraughtsResult.ONGOING -> "$toMove cannot move — ${colorName(position.toMove.other)} wins."
            _state.value.partialPath.isNotEmpty() -> "Continue the capture…"
            mustCapture -> "$toMove must capture."
            else -> "$toMove to move."
        } else when {
            _state.value.engineError != null -> "Engine error: ${_state.value.engineError}"
            resigned -> "You resigned — $engineName wins."
            flagged == player -> "Time's up — $engineName wins."
            flagged != null -> "Time's up — you win!"
            result == DraughtsResult.PLAYER_WINS -> "$engineName cannot move — you win!"
            result == DraughtsResult.ENGINE_WINS -> "No legal move — $engineName wins."
            result == DraughtsResult.DRAW -> "Draw."
            !engineReady -> "Starting $engineName…"
            _state.value.thinking -> "$engineName is thinking…"
            _state.value.partialPath.isNotEmpty() -> "Continue the capture…"
            mustCapture -> "You must capture."
            else -> "Your move (${if (player == Color.WHITE) "white" else "black"})."
        }
        _state.update {
            it.copy(
                squares = List(51) { i -> if (i == 0) Draughts.EMPTY else position[i] },
                sideToMove = position.toMove,
                lastMove = played.lastOrNull(),
                moves = played.map { m -> m.toHub() },
                result = result,
                statusText = _state.value.hintText?.takeIf { result == DraughtsResult.ONGOING }?.let { h -> "Hint: $h" } ?: status,
                whiteMs = currentMs(Color.WHITE),
                blackMs = currentMs(Color.BLACK),
            )
        }
        recordIfFinished()
    }

    override fun onCleared() {
        engine?.quit()
    }

    // ---------------------------------------------------------------- export & history

    /** The current game as a file (PDN), for the Share button and the history. */
    fun exportGame(): ExportedGame {
        val s = _state.value
        val now = System.currentTimeMillis()
        val white = if (s.config.twoPlayers) "White player" else if (s.playerSide == Color.WHITE) "You" else s.config.opponentLabel
        val black = if (s.config.twoPlayers) "Black player" else if (s.playerSide == Color.BLACK) "You" else s.config.opponentLabel
        val result = when (s.result) {
            DraughtsResult.PLAYER_WINS -> if (s.playerSide == Color.WHITE) "2-0" else "0-2"
            DraughtsResult.ENGINE_WINS -> if (s.playerSide == Color.WHITE) "0-2" else "2-0"
            DraughtsResult.DRAW -> "1-1"
            DraughtsResult.ONGOING -> "*"
        }
        val tags = listOf(
            "Event" to "Casual game", "Site" to "GamesApp", "Date" to Exports.pgnDate(now),
            "White" to white, "Black" to black, "Result" to result, "GameType" to "20",
        )
        val ending = if (s.result == DraughtsResult.ONGOING) result else "{${s.statusText.replace("}", ")")}} $result"
        // PDN lists landing squares; the Hub notation of the move list lists the captured men.
        val moves = played.mapIndexed { i, m -> Draughts.toPdn(history[i], m) }
        val text = tags.joinToString("\n") { (k, v) -> Exports.tag(k, v) } + "\n\n" + Exports.movetext(moves, ending) + "\n"
        return ExportedGame("draughts-${Exports.fileStamp(now)}.pdn", "text/plain", text)
    }

    /** A game that just ended goes to the history, once. */
    private fun recordIfFinished() {
        val s = _state.value
        if (!hasGame || recorded || s.result == DraughtsResult.ONGOING) return
        recorded = true
        val record = GameRecord(
            endedAt = System.currentTimeMillis(),
            game = "Draughts",
            variant = "International",
            opponent = if (s.config.twoPlayers) colorName(s.engineSide) else s.config.opponentLabel,
            playerSide = colorName(s.playerSide),
            result = when (s.result) {
                DraughtsResult.PLAYER_WINS -> PlayerResult.WIN
                DraughtsResult.ENGINE_WINS -> PlayerResult.LOSS
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

    /** Thinking time for a hint. */
    private val HINT_MS = 1500

    /** Asks the engine, at full strength, for a move for the player; shown until a move is played. */
    fun requestHint() {
        val s = _state.value
        if (!s.isPlayerTurn || s.hintLoading || !hasGame) return
        val cfg = s.config
        val count = played.size
        val asked = position
        val n = quietKingMoves
        val kingMoves = played.takeLast(n).map { it.toHub() }
        val pos = history[played.size - n]
        _state.update { it.copy(hintLoading = true) }
        hintJob = viewModelScope.launch {
            val hub = runCatching {
                val eng = engineLock.withLock { liveEngine(cfg, restart = false) }
                eng.bestMove(pos, kingMoves, null, HINT_MS) // no depth limit: full strength
            }.getOrNull()
            _state.update { it.copy(hintLoading = false) }
            // Same ply count is not enough: an undo then another move (two players) gets back to it.
            val move = hub?.takeIf { played.size == count && position == asked }?.let { Draughts.parseHub(position, it) } ?: return@launch
            _state.update { it.copy(hint = setOf(move.from) + move.path, hintText = move.toHub()) }
            publish()
        }
    }

    private fun clearHint() = _state.update { it.copy(hint = emptySet(), hintText = null) }

    /** The player moved while a hint was being searched: end that search now. */
    private fun stopHint() {
        if (hintJob?.isActive == true) engine?.stop()
    }
}
