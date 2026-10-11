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
import io.github.usernamealreadytakensht.games.engine.draughts.MarcherEngine
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.SavedDraughtsGame
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.clockShown
import io.github.usernamealreadytakensht.games.game.draughts.Checkers.Move
import io.github.usernamealreadytakensht.games.game.draughts.Checkers.Position
import io.github.usernamealreadytakensht.games.game.draughts.Draughts.Color
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

/** Immutable snapshot of an English-checkers game, consumed by the UI. */
data class CheckersState(
    val config: DraughtsConfig = DraughtsConfig(variant = DraughtsVariant.ENGLISH, engine = DraughtsEngineKind.MARCHER),
    /** Piece codes per square 0..63 (see [Checkers]). */
    val squares: List<Int> = List(64) { Checkers.EMPTY },
    val playerSide: Color = Color.BLACK,
    val sideToMove: Color = Color.BLACK,
    val selected: Int? = null,
    /** Landing squares chosen so far of a multi-capture being entered. */
    val partialPath: List<Int> = emptyList(),
    /** Squares the current selection may go to next (or finish on). */
    val targets: Set<Int> = emptySet(),
    val lastMove: Move? = null,
    /** Moves in standard notation ("11-15", "22x15"), for the move list. */
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
    val bottomSide: Color get() = if (config.twoPlayers) (if (flipped) Color.WHITE else Color.BLACK) else playerSide
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

/** English checkers against Marcher. Same flow as [DraughtsViewModel], on the 8x8 rules. */
class CheckersViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private val repo = GameRepository(app)
    private val records = HistoryRepository(app)
    /** Whether the finished game was already added to the history. */
    private var recorded = false
    private var hintJob: Job? = null
    private var engine: MarcherEngine? = null
    private val engineLock = Mutex()
    private var engineReady = false
    /** True while [finishLoad] starts the engine: searches wait for it to finish. */
    private var engineStarting = false
    /** Bumped by every [finishLoad]: only the latest one clears [engineStarting] and moves. */
    private var loadId = 0
    /** Set while the game screen is not shown: no engine runs then. */
    private var released = false

    private var position: Position = Checkers.START
    private val history = ArrayList<Position>()   // positions before each move, plus current
    private val played = ArrayList<Move>()
    private var generation = 0
    private var resigned = false
    private var resignedSide: Color = Color.WHITE
    private var flagged: Color? = null

    var hasGame = false
        private set

    private var whiteBaseMs = 0L
    private var blackBaseMs = 0L
    private var turnStartedAt = 0L
    private var clockJob: Job? = null
    private var clockPaused = false

    private val _state = MutableStateFlow(CheckersState())
    val state: StateFlow<CheckersState> = _state

    init { publish() }

    // ---------------------------------------------------------------- UI actions

    fun startGame(config: DraughtsConfig) {
        resetGame(config, if (config.twoPlayers) Color.BLACK else config.playerSide ?: if (Random.nextBoolean()) Color.WHITE else Color.BLACK)
        val initial = config.timeControl.startingMs
        whiteBaseMs = initial ?: 0L
        blackBaseMs = initial ?: 0L
        finishLoad()
    }

    fun resumeGame(): Boolean = try {
        loadSavedGame()
    } catch (e: Exception) {
        // A save this version cannot replay (corrupted, or from an incompatible build) would
        // crash every Resume: drop it and go back to the menu instead.
        android.util.Log.w("GameResume", "Discarding an unreadable saved game", e)
        repo.clearDraughtsGame(DraughtsVariant.ENGLISH)
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
        val saved = repo.loadDraughtsGame(DraughtsVariant.ENGLISH) ?: return false
        resetGame(saved.config, saved.playerSide)
        for (text in saved.moves) {
            val m = Checkers.parse(position, text) ?: break
            applyMove(m, clock = false)
        }
        whiteBaseMs = saved.whiteMs ?: 0L
        blackBaseMs = saved.blackMs ?: 0L
        finishLoad()
        return true
    }

    fun onSquareTapped(sq: Int) {
        val s = _state.value
        if (!s.isPlayerTurn || sq !in 0 until 64 || !Checkers.isDark(sq)) return
        val selected = s.selected

        if (selected != null) {
            val candidates = candidatesFor(selected, s.partialPath)
            // The path entered so far always starts with the selected piece's square.
            val entered = if (s.partialPath.isEmpty()) listOf(selected) else s.partialPath
            val stepping = candidates.filter { it.path.getOrNull(entered.size) == sq }
            if (stepping.isNotEmpty()) {
                val path = entered + sq
                val complete = stepping.filter { it.path == path }
                if (stepping.size == 1) { playPlayerMove(stepping[0]); return }
                if (complete.size == stepping.size) { playPlayerMove(complete[0]); return }
                _state.update { it.copy(partialPath = path, targets = targetsFor(stepping, path)) }
                return
            }
            // A tap on a final square that identifies one move plays it (skipping the steps).
            val finishing = candidates.filter { it.to == sq }
            if (finishing.size == 1) { playPlayerMove(finishing[0]); return }
            if (finishing.size > 1) return
        }

        val moves = Checkers.legalMoves(position).filter { it.from == sq }
        if (Checkers.colorOf(position[sq]) == s.mover && moves.isNotEmpty()) {
            _state.update { it.copy(selected = sq, partialPath = emptyList(), targets = targetsFor(moves, listOf(sq))) }
        } else {
            _state.update { it.copy(selected = null, partialPath = emptyList(), targets = emptySet()) }
        }
    }

    /** Legal moves from [from] that follow the path entered so far ([partial] starts with [from]). */
    private fun candidatesFor(from: Int, partial: List<Int>): List<Move> {
        val prefix = if (partial.isEmpty()) listOf(from) else partial
        return Checkers.legalMoves(position).filter { it.from == from && it.path.take(prefix.size) == prefix }
    }

    /** Next landing squares after [path], plus every final square. */
    private fun targetsFor(candidates: List<Move>, path: List<Int>): Set<Int> =
        candidates.mapNotNull { it.path.getOrNull(path.size) }.toSet() + candidates.map { it.to }

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
        position = Checkers.START
        history.clear(); history += position
        played.clear()
        resigned = false
        flagged = null
        hasGame = true
        recorded = false
        clearHint()
        // Black starts at the bottom of our board: flip when the player takes White.
        _state.update {
            it.copy(config = config, playerSide = side, flipped = side == Color.WHITE,
                selected = null, partialPath = emptyList(), targets = emptySet(), runningClock = null)
        }
    }

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
                engineLock.withLock { ensureEngine(config.engine) }
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

    private suspend fun ensureEngine(kind: DraughtsEngineKind): MarcherEngine {
        engine?.let { if (it.kind == kind && it.isRunning) return it }
        engineReady = false
        engine?.quit()
        _state.update { it.copy(engineError = null) }
        publish()
        val eng = MarcherEngine(app, kind)
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
        position = Checkers.play(position, move)
        history += position
        played += move
        if (clock) onMovePlayed(mover)
    }

    private fun undoOne() {
        played.removeAt(played.size - 1)
        history.removeAt(history.size - 1)
        position = history.last()
    }

    private val quietPlies: Int
        get() {
            var n = 0
            for (i in played.indices.reversed()) {
                if (Checkers.isProgress(history[i], played[i])) break
                n++
            }
            return n
        }

    private fun outcome(): Checkers.Outcome = when {
        resigned -> if (resignedSide == Color.WHITE) Checkers.Outcome.BLACK_WINS else Checkers.Outcome.WHITE_WINS
        flagged != null -> if (flagged == Color.WHITE) Checkers.Outcome.BLACK_WINS else Checkers.Outcome.WHITE_WINS
        else -> Checkers.outcome(position, history.dropLast(1), quietPlies)
    }

    private fun isGameOver() = outcome() != Checkers.Outcome.ONGOING

    private fun maybeEngineMove() {
        // The first start reports its own failure; later searches restart the engine themselves.
        if (released || engineStarting || _state.value.engineError != null) return
        if (_state.value.config.twoPlayers) return
        if (position.toMove == _state.value.playerSide || isGameOver()) return

        val gen = ++generation
        val cfg = _state.value.config
        val pos = position
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
                    val reply = engineLock.withLock {
                        if (attempt > 0) engine?.quit()
                        val eng = ensureEngine(cfg.engine)
                        // Cancelled while waiting for the hint or the engine: no search for nothing.
                        if (gen != generation) return@launch
                        eng.bestMove(pos, cfg.depth, moveTime)
                    }
                    if (gen != generation) return@launch
                    move = reply?.takeIf { it in Checkers.legalMoves(position) }
                    if (move != null) break
                    failure = "${cfg.engine.label} gave no legal move"
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
        val base = if (cfg.depth == null) 1500L else 6000L
        val remaining = currentMs(position.toMove) ?: return base.toInt()
        val budget = when (val tc = cfg.timeControl) {
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

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        if (!hasGame) return
        if (isGameOver()) { repo.clearDraughtsGame(DraughtsVariant.ENGLISH); return }
        repo.saveDraughtsGame(
            SavedDraughtsGame(
                config = _state.value.config,
                playerSide = _state.value.playerSide,
                moves = played.map { Checkers.notation(it) },
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
            // Out of time before the 100 ms tick noticed: the flag fell, the increment (or the
            // per-move reset) does not save it.
            if (left <= 0L) {
                flagged = mover
                if (mover == Color.WHITE) whiteBaseMs = 0 else blackBaseMs = 0
                stopClock()
                return
            }
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
        val result = when (outcome()) {
            Checkers.Outcome.ONGOING -> DraughtsResult.ONGOING
            Checkers.Outcome.DRAW -> DraughtsResult.DRAW
            Checkers.Outcome.WHITE_WINS -> if (player == Color.WHITE) DraughtsResult.PLAYER_WINS else DraughtsResult.ENGINE_WINS
            Checkers.Outcome.BLACK_WINS -> if (player == Color.BLACK) DraughtsResult.PLAYER_WINS else DraughtsResult.ENGINE_WINS
        }
        val twoPlayers = _state.value.config.twoPlayers
        val mustCapture = result == DraughtsResult.ONGOING && (twoPlayers || position.toMove == player) &&
            Checkers.legalMoves(position).firstOrNull()?.isCapture == true
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
            // The game's end first: it is what the history and the PDN record as the reason.
            resigned -> "You resigned — $engineName wins."
            flagged == player -> "Time's up — $engineName wins."
            flagged != null -> "Time's up — you win!"
            result == DraughtsResult.PLAYER_WINS -> "$engineName cannot move — you win!"
            result == DraughtsResult.ENGINE_WINS -> "No legal move — $engineName wins."
            result == DraughtsResult.DRAW -> "Draw."
            _state.value.engineError != null -> "Engine error: ${_state.value.engineError}"
            !engineReady -> "Starting $engineName…"
            _state.value.thinking -> "$engineName is thinking…"
            _state.value.partialPath.isNotEmpty() -> "Continue the capture…"
            mustCapture -> "You must capture."
            else -> "Your move (${if (player == Color.WHITE) "white" else "black"})."
        }
        // A hint shown when the game ends (resign, flag) goes with it.
        if (result != DraughtsResult.ONGOING) clearHint()
        _state.update {
            it.copy(
                squares = List(64) { i -> position[i] },
                sideToMove = position.toMove,
                lastMove = played.lastOrNull(),
                moves = played.map { m -> Checkers.notation(m) },
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
        // Checkers PDN scores the first player, Black, first: "1-0" is a Black win.
        val result = when (s.result) {
            DraughtsResult.PLAYER_WINS -> if (s.playerSide == Color.BLACK) "1-0" else "0-1"
            DraughtsResult.ENGINE_WINS -> if (s.playerSide == Color.BLACK) "0-1" else "1-0"
            DraughtsResult.DRAW -> "1/2-1/2"
            DraughtsResult.ONGOING -> "*"
        }
        val tags = listOf(
            "Event" to "Casual game", "Site" to "GamesApp", "Date" to Exports.pgnDate(now),
            "Black" to black, "White" to white, "Result" to result, "GameType" to "21",
        )
        val ending = if (s.result == DraughtsResult.ONGOING) result else "{${s.statusText.replace("}", ")")}} $result"
        val text = tags.joinToString("\n") { (k, v) -> Exports.tag(k, v) } + "\n\n" + Exports.movetext(s.moves, ending) + "\n"
        return ExportedGame("checkers-${Exports.fileStamp(now)}.pdn", "text/plain", text)
    }

    /** A game that just ended goes to the history, once. */
    private fun recordIfFinished() {
        val s = _state.value
        if (!hasGame || recorded || s.result == DraughtsResult.ONGOING) return
        recorded = true
        val record = GameRecord(
            endedAt = System.currentTimeMillis(),
            game = "Draughts",
            variant = "English checkers",
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
        val pos = position
        _state.update { it.copy(hintLoading = true) }
        hintJob = viewModelScope.launch {
            val reply = runCatching {
                engineLock.withLock { ensureEngine(cfg.engine).bestMove(pos, null, HINT_MS) } // full strength
            }.getOrNull()
            _state.update { it.copy(hintLoading = false) }
            // Same ply count is not enough: an undo then another move (two players) gets back to it.
            // Nor once the game ended meanwhile (resigned, flagged).
            val move = reply?.takeIf { played.size == count && position == pos && !isGameOver() && it in Checkers.legalMoves(position) } ?: return@launch
            _state.update { it.copy(hint = move.path.toSet(), hintText = Checkers.notation(move)) }
            publish()
        }
    }

    private fun clearHint() = _state.update { it.copy(hint = emptySet(), hintText = null) }

    /** The player moved while a hint was being searched: end that search now. */
    private fun stopHint() {
        if (hintJob?.isActive == true) engine?.stop()
    }
}
