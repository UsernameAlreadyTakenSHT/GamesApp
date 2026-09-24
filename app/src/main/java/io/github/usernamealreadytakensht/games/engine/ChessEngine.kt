package io.github.usernamealreadytakensht.games.engine

import android.content.Context
import io.github.usernamealreadytakensht.games.game.EngineFamily
import io.github.usernamealreadytakensht.games.game.EngineKind
import io.github.usernamealreadytakensht.games.game.StrengthKind

/** A chess opponent: an external UCI process or an in-process engine. */
interface ChessEngine {
    val kind: EngineKind

    /** Network asset this instance was started with (Lc0 only), used to detect a needed restart. */
    val weights: String?

    val isRunning: Boolean

    suspend fun start()

    /** Applies a strength setting where the engine supports one (Stockfish `UCI_Elo`). */
    suspend fun configure(strength: Int?)

    suspend fun newGame()

    /**
     * Searches the best move from the start position ([startFen], or the standard one) after [moves] (UCI notation), within
     * [moveTimeMs] and, when given, at most [nodes] nodes or [depth] plies.
     * Returns the move in UCI (e.g. "e7e8q"), or null when there is none.
     */
    suspend fun bestMove(
        moves: List<String>,
        moveTimeMs: Int,
        nodes: Int? = null,
        depth: Int? = null,
        startFen: String? = null,
    ): String?

    /** Aborts the running search; [bestMove] then returns promptly. */
    fun stop()

    fun quit()

    /** Node limit for [strength], or null when this engine is not node-limited. */
    fun nodesFor(strength: Int?): Int? = when (kind.strength) {
        StrengthKind.NODES -> strength
        StrengthKind.HUMAN_ELO -> 1 // Maia: raw policy, no search
        else -> null
    }

    /** Depth limit for [strength], or null when this engine is not depth-limited. */
    fun depthFor(strength: Int?): Int? = if (kind.strength == StrengthKind.DEPTH) strength else null

    /** Thinking time per move: short at low strength, longer at the top. */
    fun moveTimeMs(strength: Int?): Int = when (kind.strength) {
        StrengthKind.ELO -> {
            if (strength == null) 1500
            else {
                val t = (strength.coerceIn(kind.eloMin, kind.eloMax) - kind.eloMin).toLong()
                (300 + t * 1200 / (kind.eloMax - kind.eloMin)).toInt()
            }
        }
        // Node/depth-limited searches finish early; the cap only guards against slow cases.
        StrengthKind.NODES -> if (strength == null) 2000 else 8000
        StrengthKind.DEPTH -> if (strength == null) 1500 else 8000
        StrengthKind.HUMAN_ELO -> 2000
    }

    companion object {
        fun create(context: Context, kind: EngineKind, strength: Int?): ChessEngine = when (kind.family) {
            EngineFamily.SUNFISH -> SunfishEngine(kind)
            EngineFamily.MAIA -> Maia3Engine(context, kind)
            else -> UciEngine(context, kind, strength)
        }
    }
}
