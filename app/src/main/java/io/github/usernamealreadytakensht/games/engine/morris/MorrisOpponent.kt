package io.github.usernamealreadytakensht.games.engine.morris

import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.MorrisEngineKind
import io.github.usernamealreadytakensht.games.game.morris.MorrisVariant

/** A Nine Men's Morris opponent (an external engine process). */
interface MorrisOpponent {
    val kind: MorrisEngineKind
    val variant: MorrisVariant
    val isRunning: Boolean

    /** Launches the engine process and performs the handshake. */
    suspend fun start()

    /** Resets any game-specific state. */
    suspend fun newGame()

    /**
     * The move to play from the variant's start position after [history], searching [depth] plies
     * (null = as deep as [moveTimeMs] allows). Returns the full move, removal included.
     */
    suspend fun bestMove(history: List<Morris.Move>, depth: Int?, moveTimeMs: Int): Morris.Move?

    /** Aborts the running search. */
    fun stop()

    fun quit()
}
