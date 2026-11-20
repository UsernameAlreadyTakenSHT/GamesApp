package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.ui.GameLayoutMath
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sizes of the game screens (pixels here are 1:1 with dp). */
class GameLayoutTest {

    @Test
    fun aTallPhoneKeepsAFullWidthBoard() {
        // 336 wide, 640 tall, 252 of controls: the board may be 388 high, so the width decides.
        assertEquals(null, GameLayoutMath.wideBoardWidth(336, 640, 8, 320))
        assertEquals(388, GameLayoutMath.stackedBoardHeight(640, 252, 200))
    }

    @Test
    fun aShortWindowShrinksTheBoardInsteadOfClippingTheControls() {
        // Shogi on 360x640: 260 of controls leave 380 for a board block that wanted 435.
        assertEquals(380, GameLayoutMath.stackedBoardHeight(640, 260, 200))
        // Below the minimum the board stops shrinking: the screen scrolls.
        assertEquals(200, GameLayoutMath.stackedBoardHeight(380, 260, 200))
    }

    @Test
    fun aWideWindowPutsTheControlsBesideTheBoard() {
        // A tablet in landscape: the board gets what a 320 column of controls leaves.
        assertEquals(952, GameLayoutMath.wideBoardWidth(1280, 800, 8, 320))
        // An unfolded phone in landscape.
        assertEquals(513, GameLayoutMath.wideBoardWidth(841, 673, 8, 320))
    }

    @Test
    fun aWindowBarelyWiderThanTallStaysStacked() {
        // Split screen on a phone: beside a 320 column the board would be a stamp.
        assertEquals(null, GameLayoutMath.wideBoardWidth(411, 380, 8, 320))
        assertEquals(null, GameLayoutMath.wideBoardWidth(600, 600, 8, 320))
    }
}
