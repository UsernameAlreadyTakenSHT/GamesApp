package io.github.usernamealreadytakensht.games.engine.tafl

import com.manywords.softworks.tafl.engine.Game
import com.manywords.softworks.tafl.engine.ai.AiWorkspace
import com.manywords.softworks.tafl.ui.UiCallback
import io.github.usernamealreadytakensht.games.game.tafl.Tafl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenTafl's AI (`AiWorkspace`: iterative-deepening alpha-beta with transposition, killer
 * and history tables), run in-process. Strength is a maximum depth (null = as deep as the
 * time allows); the think time is whole seconds, OpenTafl's unit.
 */
class OpenTaflEngine {

    @Volatile private var workspace: AiWorkspace? = null

    private val quietUi = object : UiCallback {
        override fun statusText(text: String) {}
        override fun timeUpdate(currentSideAttackers: Boolean) {}
        override fun timeExpired(currentSideAttackers: Boolean) {}
    }

    /** Best move for the current position of [game]. */
    suspend fun bestMove(game: Game, depth: Int?, thinkSeconds: Int): Tafl.Move? = withContext(Dispatchers.Default) {
        val ws = AiWorkspace(quietUi, game, game.currentState, TT_SIZE_MB)
        workspace = ws
        try {
            if (depth != null) ws.setMaxDepth(depth)
            ws.explore(thinkSeconds.coerceAtLeast(1))
            ws.stopExploring()
            val move = ws.treeRoot.bestChild?.rootMove ?: return@withContext null
            Tafl.Move(move.start.x.toInt(), move.start.y.toInt(), move.end.x.toInt(), move.end.y.toInt())
        } finally {
            workspace = null
        }
    }

    /** Aborts the running search; [bestMove] then returns the best move found so far. */
    fun stop() {
        workspace?.crashStop()
    }

    private companion object {
        const val TT_SIZE_MB = 20
    }
}
