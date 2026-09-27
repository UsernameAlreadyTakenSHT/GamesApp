package io.github.usernamealreadytakensht.games.engine.shogi

import android.content.Context
import android.util.Log
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
 * Fairy-Stockfish playing shogi over UCI (`UCI_Variant shogi`). Moves use its coordinates:
 * "g3g4", "b8h2+" (promotion), "P@e5" (drop). Without an EvalFile the engine uses its
 * classical shogi evaluation.
 */
class ShogiEngine(context: Context) {

    private val executable = File(context.applicationInfo.nativeLibraryDir, "libfairy.so")
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val ioLock = Mutex()

    val isRunning: Boolean get() = process?.isAlive == true

    suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        if (!executable.canExecute()) throw IOException("Fairy-Stockfish binary not found: ${executable.path}")
        val p = ProcessBuilder(executable.absolutePath).redirectErrorStream(true).start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()
        ioLock.withLock {
            send("uci")
            readUntil("uciok")
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            send("setoption name Threads value $threads")
            send("setoption name Hash value 64")
            send("setoption name UCI_Variant value shogi")
            send("ucinewgame")
            send("isready")
            readUntil("readyok")
        }
    }

    /** Sets the `Skill Level` (-20 … 20) and starts a new game. */
    suspend fun newGame(skill: Int): Unit = withContext(Dispatchers.IO) {
        ioLock.withLock {
            send("setoption name Skill Level value $skill")
            send("ucinewgame")
            send("isready")
            readUntil("readyok")
        }
    }

    /** Best move after [moves] from the start position, or null when there is none. */
    suspend fun bestMove(moves: List<String>, moveTimeMs: Int): String? = withContext(Dispatchers.IO) {
        ioLock.withLock {
            send(if (moves.isEmpty()) "position startpos" else "position startpos moves ${moves.joinToString(" ")}")
            send("go movetime $moveTimeMs")
            val move = readUntil("bestmove").split(' ').getOrNull(1)
            if (move == null || move == "(none)") null else move
        }
    }

    fun stop() {
        runCatching { send("stop") }
    }

    fun quit() {
        runCatching { send("quit") }
        process?.let { p -> runCatching { if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroy() } }
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
            val line = r.readLine() ?: throw IOException("Fairy-Stockfish exited")
            if (line.startsWith(prefix)) {
                Log.d(TAG, "< $line")
                return line
            }
        }
    }

    private companion object {
        const val TAG = "ShogiEngine"
    }
}
