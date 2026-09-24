package io.github.usernamealreadytakensht.games.engine.draughts

import android.content.Context
import android.util.Log
import io.github.usernamealreadytakensht.games.engine.EngineAssets
import io.github.usernamealreadytakensht.games.game.draughts.Checkers
import io.github.usernamealreadytakensht.games.game.draughts.Draughts.Color
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsEngineKind
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
 * Drives the Marcher English-checkers engine through marcher/cli.c's line protocol
 * (`search p1 p2 p1k p2k player seconds depth forced` -> `move from to eval depth`).
 *
 * Marcher plays one jump per search: a multi-jump is completed by searching again with the
 * jumping piece's square as `forced`, until the legal-move list says the capture is over.
 * Its endgame database (db/wld_*.bin, 4 pieces) ships as assets and is read from the
 * working directory.
 */
class MarcherEngine(private val context: Context, val kind: DraughtsEngineKind) {

    private val executable = File(context.applicationInfo.nativeLibraryDir, kind.binary)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val ioLock = Mutex()

    val isRunning: Boolean get() = process?.isAlive == true

    suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        if (!executable.canExecute()) {
            throw IOException("${kind.label} binary not found for this ABI: ${executable.path}")
        }
        val workDir = EngineAssets.ensureDir(context, kind.assetDir)
        val p = ProcessBuilder(executable.absolutePath)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()
        ioLock.withLock {
            send("isready")
            readUntil("readyok")
        }
    }

    /**
     * The complete move for [pos] (with every jump of a capture), searching [depth] plies
     * (null = time only) within [moveTimeMs]. Returns null when the engine has no move.
     */
    suspend fun bestMove(pos: Checkers.Position, depth: Int?, moveTimeMs: Int): Checkers.Move? =
        withContext(Dispatchers.IO) {
            val legal = Checkers.legalMoves(pos)
            if (legal.isEmpty()) return@withContext null
            if (legal.size == 1) return@withContext legal[0]

            val seconds = moveTimeMs / 1000.0
            var board = pos
            var candidates: List<Checkers.Move> = legal
            val path = ArrayList<Int>()
            var forced = -1
            while (true) {
                val (from, to) = ask(board, depth, seconds, forced) ?: break
                if (path.isEmpty()) path += from
                if (path.last() != from) break
                path += to
                candidates = candidates.filter { it.path.size >= path.size && it.path.subList(0, path.size) == path }
                if (candidates.isEmpty()) break
                // Done once some legal move ends here; otherwise the capture goes on.
                candidates.firstOrNull { it.path == path }?.let { return@withContext it }
                board = Checkers.partial(board, from, to)
                forced = to
            }
            // Anything unexpected: fall back on a legal move rather than stalling the game.
            candidates.firstOrNull() ?: legal.first()
        }

    private suspend fun ask(pos: Checkers.Position, depth: Int?, seconds: Double, forced: Int): Pair<Int, Int>? = ioLock.withLock {
        val bb = Checkers.bitboards(pos)
        val player = if (pos.toMove == Color.BLACK) 1 else 2
        val maxDepth = depth ?: 64
        send(
            "search ${java.lang.Long.toUnsignedString(bb[0])} ${java.lang.Long.toUnsignedString(bb[1])} " +
                "${java.lang.Long.toUnsignedString(bb[2])} ${java.lang.Long.toUnsignedString(bb[3])} " +
                "$player ${"%.3f".format(java.util.Locale.ROOT, seconds)} $maxDepth $forced",
        )
        val parts = readUntil("move").trim().split(' ')
        val from = parts.getOrNull(1)?.toIntOrNull() ?: return@withLock null
        val to = parts.getOrNull(2)?.toIntOrNull() ?: return@withLock null
        if (from < 0 || to < 0) null else from to to
    }

    /** Marcher checks its clock itself; a running search cannot be interrupted from outside. */
    fun stop() {}

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
            val line = r.readLine() ?: throw IOException("${kind.label} exited")
            if (line.startsWith(prefix)) {
                Log.d(TAG, "< $line")
                return line
            }
        }
    }

    private companion object {
        const val TAG = "MarcherEngine"
    }
}
