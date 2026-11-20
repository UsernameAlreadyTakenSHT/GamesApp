package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** The two children of a [GameScreenLayout] that are sized from what the others leave. */
private enum class GameSlot { BOARD, MOVES }

/** Marks the board of a [GameScreenLayout]. */
internal fun Modifier.gameBoard(): Modifier = layoutId(GameSlot.BOARD)

/** Marks the move list of a [GameScreenLayout]: it takes the height nothing else needs. */
internal fun Modifier.gameMoves(): Modifier = layoutId(GameSlot.MOVES)

/** The sizes behind [GameScreenLayout], in pixels. */
internal object GameLayoutMath {
    /**
     * Width the board may take beside the controls, or null when the window should stack
     * them instead. Side by side needs a window wider than tall that still leaves a
     * [paneMin]-wide column of controls next to a board at least 0.6 x the height.
     */
    fun wideBoardWidth(viewWidth: Int, viewHeight: Int, gap: Int, paneMin: Int): Int? {
        if (viewWidth <= viewHeight) return null
        val width = viewWidth - paneMin - gap
        return if (width * 5 >= viewHeight * 3) width else null
    }

    /**
     * Height the board may take when stacked with the controls: what they leave ([fixed] is
     * their height, gaps included), but never under [minBoard] (the screen then scrolls).
     */
    fun stackedBoardHeight(viewHeight: Int, fixed: Int, minBoard: Int): Int = max(viewHeight - fixed, minBoard)
}

/** Narrowest column of controls put beside the board in a wide window. */
private val PANE_MIN = 320.dp

/** The board is not shrunk under this: a shorter window scrolls instead. */
private val MIN_BOARD = 200.dp

/**
 * Body of a game screen: a column whose children keep their own height, except the board
 * ([gameBoard]) and the move list ([gameMoves]). The board gets what the controls leave, so
 * that on a short window it shrinks instead of pushing them off the screen; the move list
 * gets the rest. In a window wider than tall (a tablet or an unfolded phone) the board goes
 * on the left and everything else in a column beside it. Whatever still does not fit scrolls.
 *
 * A [squareBoard] is given a square; otherwise the board is given the room it may use and
 * sizes itself (shogi, with its two hands).
 */
@Composable
internal fun GameScreenLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    squareBoard: Boolean = true,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val viewWidth = constraints.maxWidth
        val viewHeight = constraints.maxHeight
        Layout(content = content, modifier = Modifier.verticalScroll(rememberScrollState())) { measurables, _ ->
            val gap = spacing.roundToPx()
            val boardM = measurables.firstOrNull { it.layoutId == GameSlot.BOARD }
            val movesM = measurables.firstOrNull { it.layoutId == GameSlot.MOVES }
            val controls = measurables.filter { it !== boardM && it !== movesM }

            fun boardConstraints(maxWidth: Int, maxHeight: Int): Constraints {
                if (!squareBoard) return Constraints(maxWidth = maxWidth, maxHeight = maxHeight)
                val side = min(maxWidth, maxHeight)
                return Constraints.fixed(side, side)
            }

            val wide = GameLayoutMath.wideBoardWidth(viewWidth, viewHeight, gap, PANE_MIN.roundToPx())
            if (wide != null && boardM != null) {
                // Board on the left, the controls and the move list in a column on the right.
                val boardP = boardM.measure(boardConstraints(wide, viewHeight))
                val paneX = boardP.width + gap
                val paneWidth = viewWidth - paneX
                val controlPs = controls.map { it.measure(Constraints.fixedWidth(paneWidth)) }
                val used = controlPs.sumOf { it.height } + gap * max(controlPs.size - 1, 0)
                val movesHeight = max(viewHeight - used - gap, 0)
                val movesP = movesM?.measure(Constraints.fixed(paneWidth, movesHeight))
                layout(viewWidth, maxOf(viewHeight, boardP.height, used)) {
                    boardP.place(0, max((viewHeight - boardP.height) / 2, 0))
                    var y = 0
                    for (p in controlPs) {
                        p.place(paneX, y)
                        y += p.height + gap
                    }
                    movesP?.place(paneX, y)
                }
            } else {
                // One column, in the order given.
                val controlPs = controls.map { it.measure(Constraints.fixedWidth(viewWidth)) }
                val gaps = gap * max(controlPs.size + (if (boardM != null) 1 else 0) - 1, 0)
                val fixed = controlPs.sumOf { it.height } + gaps
                val minBoard = min(viewWidth, MIN_BOARD.roundToPx())
                val boardP = boardM?.measure(
                    boardConstraints(viewWidth, GameLayoutMath.stackedBoardHeight(viewHeight, fixed, minBoard)),
                )
                val used = fixed + (boardP?.height ?: 0)
                val movesHeight = max(viewHeight - used - gap, 0)
                val movesP = movesM?.measure(Constraints.fixed(viewWidth, movesHeight))
                layout(viewWidth, max(viewHeight, used)) {
                    var y = 0
                    var next = 0
                    for (m in measurables) {
                        if (m === movesM) continue
                        val p = if (m === boardM) boardP!! else controlPs[next++]
                        p.place((viewWidth - p.width) / 2, y)
                        y += p.height + gap
                    }
                    // The move list goes under everything else.
                    movesP?.place(0, y)
                }
            }
        }
    }
}
