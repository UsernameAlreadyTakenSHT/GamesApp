package io.github.usernamealreadytakensht.games.engine.morris

import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.MorrisEngineKind

/** A Nine Men's Morris opponent: the built-in searcher or an external UCI process. */
interface MorrisOpponent {
    val kind: MorrisEngineKind

    /** Launches / prepares the engine; a no-op for the in-process one. */
    suspend fun start() {}

    /** Resets any game-specific state. */
    suspend fun newGame() {}

    /**
     * The move to play from the start position after [history], searching [depth] plies
     * (null = as deep as [moveTimeMs] allows). Returns the full move, removal included.
     */
    suspend fun bestMove(history: List<Morris.Move>, depth: Int?, moveTimeMs: Int): Morris.Move?

    /** Aborts the running search. */
    fun stop()

    fun quit() {}
}

/** The built-in engine, wrapped as an opponent. */
class MillerOpponent(override val kind: MorrisEngineKind) : MorrisOpponent {
    private val engine = MorrisEngine()

    override suspend fun bestMove(history: List<Morris.Move>, depth: Int?, moveTimeMs: Int): Morris.Move? {
        var pos = Morris.START
        for (m in history) pos = Morris.play(pos, m)
        return engine.bestMove(pos, depth, moveTimeMs)
    }

    override fun stop() = engine.stop()
}
