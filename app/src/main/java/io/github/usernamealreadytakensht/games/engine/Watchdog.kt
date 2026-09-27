package io.github.usernamealreadytakensht.games.engine

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Deadlines for blocking reads from engine processes. A read that outlives its deadline gets
 * the process destroyed, so the pending `readLine()` returns null and the driver fails with
 * an IOException instead of blocking its lock (and the game) forever.
 */
object Watchdog {
    /** Handshakes may load big networks (the shogi net is 160 MB): be generous. */
    const val HANDSHAKE_MS = 120_000L

    /** Slack added to a search's own time limit before it is considered hung. */
    const val SEARCH_SLACK_MS = 15_000L

    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "engine-watchdog").apply { isDaemon = true }
    }

    fun <T> guard(process: Process?, timeoutMs: Long, block: () -> T): T {
        val kill = process?.let { p -> timer.schedule({ p.destroy() }, timeoutMs, TimeUnit.MILLISECONDS) }
        try {
            return block()
        } finally {
            kill?.cancel(false)
        }
    }
}
