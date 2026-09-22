package io.github.usernamealreadytakensht.games.engine.morris

import android.content.Context
import android.util.Log
import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.MorrisEngineKind
import io.github.usernamealreadytakensht.games.game.morris.MorrisVariant
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
 * Drives the Sanmill engine (`tgf uci`, the Rust console engine of the Sanmill app) as a
 * process. It speaks a UCI dialect whose moves are `a7` (place), `a1-a4` (slide / fly) and
 * `xa4` (remove); a removal is a separate action, so a mill-closing move takes two `go`s.
 * The coordinate labels are the same as ours (a7 top-left, g1 bottom-right).
 */
class SanmillEngine(
    private val context: Context,
    override val kind: MorrisEngineKind,
    override val variant: MorrisVariant,
) : MorrisOpponent {

    private val executable = File(context.applicationInfo.nativeLibraryDir, kind.binary)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Serialises command/response exchanges; `stop()` deliberately bypasses it. */
    private val ioLock = Mutex()

    override val isRunning: Boolean get() = process?.isAlive == true

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        if (!executable.canExecute()) {
            throw IOException("Sanmill binary not found for this ABI: ${executable.path}")
        }
        val p = ProcessBuilder(executable.absolutePath, "uci")
            .redirectErrorStream(true)
            .start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()

        ioLock.withLock {
            send("uci")
            readUntil("uciok")
            send("setoption name Algorithm value ${kind.algorithm}")
            send("setoption name Shuffling value true") // vary equal moves between games
            // Rule switches of the variant (Sanmill plays them all natively).
            send("setoption name PiecesCount value ${variant.menPerSide}")
            send("setoption name MayMoveInPlacingPhase value ${variant.mayMoveInPlacingPhase}")
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

    override suspend fun bestMove(history: List<Morris.Move>, depth: Int?, moveTimeMs: Int): Morris.Move? =
        withContext(Dispatchers.IO) {
            var pos = Morris.start(variant)
            for (m in history) pos = Morris.play(pos, m)
            val legal = Morris.legalMoves(pos)
            if (legal.isEmpty()) return@withContext null

            val tokens = history.flatMap { it.toUciTokens() }.toMutableList()
            val first = ask(tokens, depth, moveTimeMs) ?: return@withContext null
            val (from, to) = parseStep(first) ?: return@withContext null
            val candidates = legal.filter { it.from == from && it.to == to }
            if (candidates.isEmpty()) return@withContext null
            if (!candidates[0].closesMill) return@withContext candidates[0]

            // The move closes a mill: ask which man to take (the engine now expects "xNN").
            tokens += first
            val removal = ask(tokens, depth, moveTimeMs)
            val point = removal?.takeIf { it.startsWith("x") }?.let { Morris.pointOf(it.substring(1)) }
            candidates.firstOrNull { it.remove == point } ?: candidates[0]
        }

    /** One `position … / go …` exchange; returns the engine's move token, or null for "none". */
    private suspend fun ask(tokens: List<String>, depth: Int?, moveTimeMs: Int): String? = ioLock.withLock {
        send(if (tokens.isEmpty()) "position startpos" else "position startpos moves ${tokens.joinToString(" ")}")
        // MCTS is sized by SkillLevel (iterations), the tree searches by the go depth.
        if (kind == MorrisEngineKind.SANMILL_MCTS) {
            send("setoption name SkillLevel value ${(depth ?: 30).coerceIn(1, 30)}")
            send("go movetime $moveTimeMs")
        } else if (depth != null) {
            send("go depth $depth movetime $moveTimeMs")
        } else {
            send("setoption name SkillLevel value 30")
            send("go movetime $moveTimeMs")
        }
        val line = readUntil("bestmove")
        val token = line.substringAfter("bestmove").trim().split(' ').firstOrNull()
        if (token.isNullOrEmpty() || token == "none") null else token
    }

    /** "d7" -> (-1, 16); "a1-d1" -> (21, 22); anything else -> null. */
    private fun parseStep(token: String): Pair<Int, Int>? {
        val dash = token.indexOf('-')
        return if (dash >= 0) {
            val from = Morris.pointOf(token.substring(0, dash)) ?: return null
            val to = Morris.pointOf(token.substring(dash + 1)) ?: return null
            from to to
        } else {
            val to = Morris.pointOf(token) ?: return null
            -1 to to
        }
    }

    override fun stop() {
        runCatching { send("stop") }
    }

    override fun quit() {
        runCatching { send("quit") }
        process?.let { p ->
            runCatching { if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroy() }
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

    /** Reads until a line containing [needle] (Sanmill prints "info … bestmove g7" on one line). */
    private fun readUntil(needle: String): String {
        val r = reader ?: throw IOException("Engine not started")
        while (true) {
            val line = r.readLine() ?: throw IOException("Sanmill exited")
            if (line.contains(needle)) {
                Log.d(TAG, "< $line")
                return line
            }
        }
    }

    companion object {
        private const val TAG = "SanmillEngine"

        /** Our combined move as the engine's tokens: "d7", "a1-d1", plus "xg7" for a removal. */
        fun Morris.Move.toUciTokens(): List<String> {
            val step = if (from >= 0) "${Morris.name(from)}-${Morris.name(to)}" else Morris.name(to)
            return if (remove >= 0) listOf(step, "x${Morris.name(remove)}") else listOf(step)
        }
    }
}
