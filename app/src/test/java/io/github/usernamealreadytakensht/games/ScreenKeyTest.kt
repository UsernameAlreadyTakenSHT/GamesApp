package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.game.GameConfig
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsConfig
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsEngineKind
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsVariant
import io.github.usernamealreadytakensht.games.ui.ChessLaunch
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsLaunch
import org.junit.Assert.assertEquals
import org.junit.Test

/** The screen kept across a recreation of the activity (see screenKey). */
class ScreenKeyTest {

    @Test
    fun plainScreensComeBackAsThemselves() {
        val screens = listOf(
            Screen.Home, Screen.History, Screen.GameSetup, Screen.OpponentSetup,
            Screen.DraughtsSetup, Screen.DraughtsOpponentSetup, Screen.MorrisSetup, Screen.MorrisOpponentSetup,
            Screen.TaflSetup, Screen.TaflOpponentSetup, Screen.FoxSetup, Screen.FoxOpponentSetup,
            Screen.ShogiSetup, Screen.ShogiOpponentSetup,
        )
        for (s in screens) assertEquals(s, screenOf(screenKey(s)))
    }

    @Test
    fun aGameScreenComesBackAsAResumeNeverAsANewGame() {
        val chess = Screen.ChessGame(ChessLaunch.NewGame(GameConfig(), 3))
        assertEquals(Screen.ChessGame(ChessLaunch.Resume), screenOf(screenKey(chess)))
        assertEquals(Screen.ChessGame(ChessLaunch.Resume), screenOf(screenKey(Screen.ChessGame(ChessLaunch.Resume))))
    }

    @Test
    fun draughtsKeepTheirRules() {
        val english = DraughtsConfig(variant = DraughtsVariant.ENGLISH, engine = DraughtsEngineKind.MARCHER)
        assertEquals(
            Screen.DraughtsGame(DraughtsLaunch.Resume(DraughtsVariant.ENGLISH)),
            screenOf(screenKey(Screen.DraughtsGame(DraughtsLaunch.NewGame(english, 1)))),
        )
        val international = Screen.DraughtsGame(DraughtsLaunch.Resume(DraughtsVariant.INTERNATIONAL))
        assertEquals(international, screenOf(screenKey(international)))
    }

    @Test
    fun anUnknownKeyGoesHome() {
        assertEquals(Screen.Home, screenOf("SomethingFromALaterVersion"))
        assertEquals(Screen.DraughtsGame(DraughtsLaunch.Resume(DraughtsVariant.INTERNATIONAL)), screenOf("DraughtsGame:Nope"))
    }
}
