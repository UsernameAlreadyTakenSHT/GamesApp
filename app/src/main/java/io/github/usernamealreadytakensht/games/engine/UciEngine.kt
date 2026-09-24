package io.github.usernamealreadytakensht.games.engine

import android.content.Context
import android.util.Log
import io.github.usernamealreadytakensht.games.game.EngineFamily
import io.github.usernamealreadytakensht.games.game.EngineKind
import io.github.usernamealreadytakensht.games.game.StrengthKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Drives a UCI engine process (Stockfish or Lc0).
 *
 * Binaries ship inside the APK as `lib*.so` files (jniLibs): Android extracts them into
 * `nativeLibraryDir`, the only location an app may execute a native binary from (API 29+).
 * Lc0 networks ship as assets and are copied to the app's files directory on first use.
 */
class UciEngine(private val context: Context, override val kind: EngineKind, strength: Int?) : ChessEngine {

    private val executable = File(context.applicationInfo.nativeLibraryDir, kind.binary)

    /** Network asset this instance was started with (Lc0 only). */
    override val weights: String? = kind.weightsFor(strength)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Serialises command/response exchanges; `stop()` deliberately bypasses it. */
    private val ioLock = Mutex()

    override val isRunning: Boolean get() = process?.isAlive == true

    /** Launches the process, performs the UCI handshake and loads the network if any. */
    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        if (!executable.canExecute()) {
            throw IOException("${kind.label} binary not found for this ABI: ${executable.path}")
        }
        val weightsFile = weights?.let { NetAssets.ensure(context, it) }
        // Engines with data files (Rodent's personalities, nets and books) run inside their
        // extracted asset folder so that the relative paths in their config files resolve.
        val workDir = kind.dataDir?.let { NetAssets.ensureDir(context, it) }

        val p = ProcessBuilder(executable.absolutePath)
            .redirectErrorStream(true)
            .apply { if (workDir != null) directory(workDir) }
            .start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()

        ioLock.withLock {
            send("uci")
            readUntil("uciok")
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            when (kind.family) {
                EngineFamily.LC0 -> {
                    send("setoption name WeightsFile value ${weightsFile!!.absolutePath}")
                    send("setoption name Threads value ${threads.coerceAtMost(2)}")
                    // Lc0 replies to the first `isready` only once the network is loaded.
                }
                else -> {
                    send("setoption name Threads value $threads")
                    send("setoption name Hash value 64")
                }
            }
            // Fairy-Stockfish is only offered for Chess960 (castling as king-takes-rook);
            // without an EvalFile it plays with its classical evaluation.
            if (kind.family == EngineFamily.FAIRY) send("setoption name UCI_Chess960 value true")
            kind.personality?.let {
                send("setoption name PersonalityFile value $it")
                send("setoption name OwnBook value true") // personalities come with their own book
            }
            send("ucinewgame")
            send("isready")
            readUntil("readyok")
        }
    }

    /**
     * Applies a strength setting. For Elo-based engines this sets `UCI_LimitStrength`
     * (null = full strength); node-limited engines are handled per search instead.
     */
    override suspend fun configure(strength: Int?): Unit = withContext(Dispatchers.IO) {
        if (kind.strength != StrengthKind.ELO) return@withContext
        ioLock.withLock {
            if (strength == null) {
                send("setoption name UCI_LimitStrength value false")
            } else {
                send("setoption name UCI_LimitStrength value true")
                send("setoption name UCI_Elo value ${strength.coerceIn(kind.eloMin, kind.eloMax)}")
            }
            send("isready")
            readUntil("readyok")
        }
    }

    override suspend fun newGame(): Unit = withContext(Dispatchers.IO) {
        ioLock.withLock {
            send("ucinewgame")
            send("isready")
            readUntil("readyok")
        }
    }

    /**
     * Searches the best move from the start position after [moves] (UCI notation),
     * within [moveTimeMs] and, when given, at most [nodes] nodes or [depth] plies.
     * Returns the move in UCI (e.g. "e7e8q"), or null when the engine answers `(none)`.
     */
    override suspend fun bestMove(moves: List<String>, moveTimeMs: Int, nodes: Int?, depth: Int?, startFen: String?): String? =
        withContext(Dispatchers.IO) {
            ioLock.withLock {
                val start = if (startFen == null) "position startpos" else "position fen $startFen"
                send(if (moves.isEmpty()) start else "$start moves ${moves.joinToString(" ")}")
                val limit = when {
                    nodes != null -> " nodes $nodes"
                    depth != null -> " depth $depth"
                    else -> ""
                }
                send("go$limit movetime $moveTimeMs")
                val line = readUntil("bestmove")
                val move = line.split(' ').getOrNull(1)
                if (move == null || move == "(none)") null else move
            }
        }

    /** Aborts the running search (the `bestmove` then arrives immediately). */
    override fun stop() {
        runCatching { send("stop") }
    }

    override fun quit() {
        runCatching { send("quit") }
        process?.let { p ->
            runCatching {
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroy()
            }
        }
        process = null
        writer = null
        reader = null
    }

    @Synchronized
    private fun send(cmd: String) {
        val w = writer ?: throw IOException("Engine not started")
        Log.d(TAG, "> $cmd")
        w.write(cmd)
        w.newLine()
        w.flush()
    }

    private fun readUntil(prefix: String): String {
        val r = reader ?: throw IOException("Engine not started")
        while (true) {
            val line = r.readLine() ?: throw IOException("${kind.label} exited")
            if (line.startsWith(prefix)) {
                Log.d(TAG, "< $line")
                return line
            }
        }
    }

    companion object {
        private const val TAG = "UciEngine"
    }
}
