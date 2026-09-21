package io.github.usernamealreadytakensht.games.engine.draughts

import android.content.Context
import android.util.Log
import io.github.usernamealreadytakensht.games.engine.EngineAssets
import io.github.usernamealreadytakensht.games.game.draughts.Draughts
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

/** A draughts opponent (Scan / Moby Dam over the Hub protocol). */
interface DraughtsEngine {
    val kind: DraughtsEngineKind
    val isRunning: Boolean
    suspend fun start()
    suspend fun newGame()

    /**
     * Searches [pos]. [kingMoves] are the recent reversible moves (Hub notation) for
     * repetition detection. [depth] limits the search (null = time only); [moveTimeMs] caps it.
     * Returns the move in Hub notation ("32-28", "28x19x23"), or null.
     */
    suspend fun bestMove(pos: Draughts.Position, kingMoves: List<String>, depth: Int?, moveTimeMs: Int): String?

    /** Aborts the running search; [bestMove] then returns promptly. */
    fun stop()
    fun quit()
}

/**
 * Drives a Hub-protocol engine process (protocol.txt of Scan 3.1). The binary ships as a
 * `lib*.so` in jniLibs and is executed from `nativeLibraryDir`; its data/config assets are
 * extracted to a private directory used as the working directory.
 */
class HubEngine(private val context: Context, override val kind: DraughtsEngineKind) : DraughtsEngine {

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
            throw IOException("${kind.label} binary not found for this ABI: ${executable.path}")
        }
        val workDir = EngineAssets.ensureDir(context, kind.assetDir)
        val p = ProcessBuilder(listOf(executable.absolutePath) + kind.args)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()

        ioLock.withLock {
            send("hub")
            readUntil("wait")
            send("set-param name=threads value=1")
            send("set-param name=bb-size value=0") // no endgame databases shipped
            send("init")
            readUntil("ready")
        }
    }

    override suspend fun newGame(): Unit = withContext(Dispatchers.IO) {
        ioLock.withLock {
            send("new-game")
            send("ping")
            readUntil("pong")
        }
    }

    override suspend fun bestMove(pos: Draughts.Position, kingMoves: List<String>, depth: Int?, moveTimeMs: Int): String? =
        withContext(Dispatchers.IO) {
            ioLock.withLock {
                val moves = if (kingMoves.isEmpty()) "" else " moves=\"${kingMoves.joinToString(" ")}\""
                send("pos pos=${pos.toHub()}$moves")
                val seconds = "%.3f".format(java.util.Locale.ROOT, moveTimeMs / 1000.0)
                send(if (depth != null) "level depth=$depth move-time=$seconds" else "level move-time=$seconds")
                send("go think")
                val line = readUntil("done")
                Regex("""move=(\S+)""").find(line)?.groupValues?.get(1)?.trim('"')
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

    private fun readUntil(prefix: String): String {
        val r = reader ?: throw IOException("Engine not started")
        while (true) {
            val line = r.readLine() ?: throw IOException("${kind.label} exited")
            if (line.startsWith(prefix)) {
                Log.d(TAG, "< $line")
                return line
            }
            if (line.startsWith("error")) Log.w(TAG, "< $line")
        }
    }

    companion object {
        private const val TAG = "HubEngine"
    }
}
