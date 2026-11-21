package io.github.usernamealreadytakensht.games.engine.morris

import android.content.Context
import android.util.Log
import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.MorrisEngineKind
import io.github.usernamealreadytakensht.games.game.morris.MorrisVariant
import io.github.usernamealreadytakensht.games.engine.Watchdog
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

    /** Set by quit(): a start() still under way (copying assets) then ends its new process itself. */
    @Volatile private var quitRequested = false
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Serialises command/response exchanges; `stop()` deliberately bypasses it. */
    private val ioLock = Mutex()

    /** Set by stop(): a move that closes a mill then skips its second search (the removal). */
    @Volatile private var stopRequested = false

    override val isRunning: Boolean get() = process?.isAlive == true

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        quitRequested = false
        if (!executable.canExecute()) {
            throw IOException("Sanmill binary not found for this ABI: ${executable.path}")
        }
        val p = ProcessBuilder(executable.absolutePath, "uci")
            .redirectErrorStream(true)
            .start()
        process = p
        if (quitRequested) {
            p.destroy()
            process = null
            throw IOException("Engine stopped while it was starting")
        }
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

            stopRequested = false
            val tokens = history.flatMap { it.toUciTokens() }.toMutableList()
            val started = System.nanoTime()
            val first = ask(tokens, depth, moveTimeMs) ?: return@withContext null
            val (from, to) = parseStep(first) ?: return@withContext null
            val candidates = legal.filter { it.from == from && it.to == to }
            if (candidates.isEmpty()) return@withContext null
            if (!candidates[0].closesMill || stopRequested) return@withContext candidates[0]

            // The move closes a mill: ask which man to take (the engine now expects "xNN").
            // Both searches share the one budget: the removal gets what the move left of it, and
            // a quarter at least (choosing the man to take is a much smaller search). A second
            // full budget would run the engine past a per-move clock.
            tokens += first
            val spentMs = ((System.nanoTime() - started) / 1_000_000).toInt()
            val removalMs = (moveTimeMs - spentMs).coerceAtLeast(moveTimeMs / 4).coerceAtLeast(50)
            val removal = ask(tokens, depth, removalMs)
            val point = removal?.takeIf { it.startsWith("x") }?.let { Morris.pointOf(it.substring(1)) }
            candidates.firstOrNull { it.remove == point } ?: candidates[0]
        }

    /** One `position … / go …` exchange; returns the engine's move token, or null for "none". */
    private suspend fun ask(tokens: List<String>, depth: Int?, moveTimeMs: Int): String? = ioLock.withLock {
        send(if (tokens.isEmpty()) "position startpos" else "position startpos moves ${tokens.joinToString(" ")}")
        searchCommands(kind, depth, moveTimeMs).forEach { send(it) }
        val line = readUntil("bestmove", moveTimeMs + Watchdog.SEARCH_SLACK_MS)
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
        stopRequested = true
        runCatching { send("stop") }
    }

    override fun quit() {
        quitRequested = true
        runCatching { send("quit") }
        // Let the engine exit on its own, off the caller's thread; destroy it if it lingers.
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

    /** Reads until a line containing [needle] (Sanmill prints "info … bestmove g7" on one line). */
    /** Reads up to the line matching [needle]; the process is killed if it takes over [timeoutMs]. */
    private fun readUntil(needle: String, timeoutMs: Long = Watchdog.HANDSHAKE_MS): String =
        Watchdog.guard(process, timeoutMs) {
            val r = reader ?: throw IOException("Engine not started")
            var line: String
            do {
                line = r.readLine() ?: throw IOException("Sanmill exited")
            } while (!(line.contains(needle)))
            Log.d(TAG, "< $line")
            line
        }

    companion object {
        private const val TAG = "SanmillEngine"

        /** Sanmill's own default for its `SkillLevel` option. */
        private const val DEFAULT_SKILL_LEVEL = 1

        /**
         * The commands of one search. MCTS is sized by SkillLevel (iterations), the tree
         * searches by the go depth. SkillLevel is sent with every search: a Hint (no depth)
         * raises it to 30, and the engine keeps an option until it is set again, so a
         * depth-limited search puts it back to the default it had before any Hint.
         */
        fun searchCommands(kind: MorrisEngineKind, depth: Int?, moveTimeMs: Int): List<String> = when {
            kind == MorrisEngineKind.SANMILL_MCTS -> listOf(
                "setoption name SkillLevel value ${(depth ?: 30).coerceIn(1, 30)}",
                "go movetime $moveTimeMs",
            )
            depth != null -> listOf(
                "setoption name SkillLevel value $DEFAULT_SKILL_LEVEL",
                "go depth $depth movetime $moveTimeMs",
            )
            else -> listOf(
                "setoption name SkillLevel value 30",
                "go movetime $moveTimeMs",
            )
        }

        /** Our combined move as the engine's tokens: "d7", "a1-d1", plus "xg7" for a removal. */
        fun Morris.Move.toUciTokens(): List<String> {
            val step = if (from >= 0) "${Morris.name(from)}-${Morris.name(to)}" else Morris.name(to)
            return if (remove >= 0) listOf(step, "x${Morris.name(remove)}") else listOf(step)
        }
    }
}
