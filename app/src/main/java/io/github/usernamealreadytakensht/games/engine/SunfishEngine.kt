package io.github.usernamealreadytakensht.games.engine

import io.github.usernamealreadytakensht.games.game.EngineKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-process opponent backed by the [Sunfish] port. Search runs on [Dispatchers.Default]. */
class SunfishEngine(override val kind: EngineKind) : ChessEngine {

    override val weights: String? = null
    private var searcher = Sunfish.Searcher()

    @Volatile
    override var isRunning: Boolean = false
        private set

    override suspend fun start() { isRunning = true }

    override suspend fun configure(strength: Int?) = Unit

    override suspend fun newGame() {
        searcher = Sunfish.Searcher() // drops the killer table between games
    }

    override suspend fun bestMove(moves: List<String>, moveTimeMs: Int, nodes: Int?, depth: Int?): String? =
        withContext(Dispatchers.Default) {
            val hist = Sunfish.history(moves)
            val s = searcher
            val start = System.nanoTime()
            s.abort = false
            s.deadline = start + moveTimeMs * 1_000_000L
            // Without a depth limit the driver stops starting new iterations at a third of the budget.
            s.soft = if (depth != null) Long.MAX_VALUE else start + moveTimeMs * 1_000_000L / 3

            // Same bookkeeping as sunfish's `go` handler: only a completed depth's last
            // fail-high move is trusted once the search is cut short.
            var best: Sunfish.Move? = null
            var cand: Sunfish.Move? = null
            var d0 = 1
            var terminal = false
            try {
                s.search(hist, depth ?: 1000) { step ->
                    if (step.depth > d0) { best = cand ?: best; d0 = step.depth }
                    if (step.score >= step.gamma) {
                        if (step.move == null) { terminal = true; throw Sunfish.Stop() } // mated / stalemated
                        cand = step.move
                    }
                }
            } catch (_: Sunfish.Stop) {
                if (!terminal) cand = best ?: cand
            }
            (cand ?: best)?.let { Sunfish.toUci(it, moves.size) }
        }

    override fun stop() { searcher.abort = true }

    override fun quit() { isRunning = false }
}
