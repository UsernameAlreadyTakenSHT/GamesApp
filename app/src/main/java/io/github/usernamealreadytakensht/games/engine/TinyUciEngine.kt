package io.github.usernamealreadytakensht.games.engine

import android.content.Context
import android.util.Log
import io.github.usernamealreadytakensht.games.game.EngineKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the "4 KB" engines (ice4, c4ke), which only speak a sliver of UCI: no `go depth`,
 * no `go movetime`, and `stop` is heard only during `go infinite`.
 *
 * Every search is therefore a `go infinite` that this class ends itself with `stop`: when
 * the engine reports the wanted depth (`info depth N`), or when the time is up. Either way
 * the engine then answers `bestmove`. Exactly one `stop` goes out per search: a second one
 * would be read as a (harmless) unknown command, and one that came too late would end the
 * next search at once.
 */
class TinyUciEngine(private val context: Context, override val kind: EngineKind) : ChessEngine {

    private val executable = File(context.applicationInfo.nativeLibraryDir, kind.binary)

    override val weights: String? = null

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Serialises command/response exchanges; `stop()` deliberately bypasses it. */
    private val ioLock = Mutex()

    /** A `go infinite` is running and has not been sent its `stop` yet. */
    private val stoppable = AtomicBoolean(false)

    override val isRunning: Boolean get() = process?.isAlive == true

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        if (!executable.canExecute()) {
            throw IOException("${kind.label} binary not found for this ABI: ${executable.path}")
        }
        val p = ProcessBuilder(executable.absolutePath).redirectErrorStream(true).start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()

        ioLock.withLock {
            send("uci") // whatever comes first is taken as `uci`
            readUntil("uciok")
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            send("setoption name Threads value $threads")
            send("setoption name Hash value 64")
            send("ucinewgame")
            send("isready")
            readUntil("readyok")
        }
    }

    /** Nothing to set: the strength is a depth, applied search by search. */
    override suspend fun configure(strength: Int?) = Unit

    override suspend fun newGame(): Unit = withContext(Dispatchers.IO) {
        ioLock.withLock {
            send("ucinewgame")
            send("isready")
            readUntil("readyok")
        }
    }

    override suspend fun bestMove(moves: List<String>, moveTimeMs: Int, nodes: Int?, depth: Int?, startFen: String?): String? =
        withContext(Dispatchers.IO) {
            ioLock.withLock {
                val start = if (startFen == null) "position startpos" else "position fen $startFen"
                // These parsers expect the `moves` keyword even when no move follows.
                send("$start moves ${moves.joinToString(" ")}".trimEnd())
                stoppable.set(true)
                send("go infinite")
                coroutineScope {
                    val timer = launch { delay(moveTimeMs.toLong()); sendStopOnce() }
                    try {
                        val line = Watchdog.guard(process, moveTimeMs + Watchdog.SEARCH_SLACK_MS) {
                            val r = reader ?: throw IOException("Engine not started")
                            var l: String
                            do {
                                l = r.readLine() ?: throw IOException("${kind.label} exited")
                                if (depth != null && reachedDepth(l, depth)) sendStopOnce()
                            } while (!l.startsWith("bestmove"))
                            Log.d(TAG, "< $l")
                            l
                        }
                        // A search that ended by itself (c4ke) still waits for its `stop`.
                        sendStopOnce()
                        // "bestmove e2e4 " (a trailing space when there is no promotion).
                        line.trim().split(' ').getOrNull(1)?.takeIf { it.length >= 4 }
                    } finally {
                        timer.cancel()
                        stoppable.set(false)
                    }
                }
            }
        }

    /** True for an `info depth N ...` line with N at least [depth]. */
    private fun reachedDepth(line: String, depth: Int): Boolean {
        if (!line.startsWith("info depth ")) return false
        val reached = line.substring(11).takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        return reached >= depth
    }

    private fun sendStopOnce() {
        if (stoppable.compareAndSet(true, false)) runCatching { send("stop") }
    }

    /** Aborts the running search (the `bestmove` then arrives immediately). */
    override fun stop() = sendStopOnce()

    override fun quit() {
        // `quit` is only heard between searches: end the one in progress first.
        sendStopOnce()
        runCatching { send("quit") }
        process?.let { p ->
            Thread { runCatching { if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroy() } }.start()
        }
        process = null
        writer = null
        reader = null
    }

    @Synchronized
    private fun send(cmd: String) {
        // One command per line: a line break smuggled in (e.g. from a tampered save) could add commands.
        if ('\n' in cmd || '\r' in cmd) throw IOException("Refusing a multi-line engine command")
        val w = writer ?: throw IOException("Engine not started")
        Log.d(TAG, "> $cmd")
        w.write(cmd)
        w.newLine()
        w.flush()
    }

    private fun readUntil(prefix: String, timeoutMs: Long = Watchdog.HANDSHAKE_MS): String =
        Watchdog.guard(process, timeoutMs) {
            val r = reader ?: throw IOException("Engine not started")
            var line: String
            do {
                line = r.readLine() ?: throw IOException("${kind.label} exited")
            } while (!line.startsWith(prefix))
            Log.d(TAG, "< $line")
            line
        }

    companion object {
        private const val TAG = "TinyUciEngine"
    }
}
