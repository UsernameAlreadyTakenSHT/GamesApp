package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.game.EngineFamily
import io.github.usernamealreadytakensht.games.game.EngineKind
import io.github.usernamealreadytakensht.games.game.GameConfig
import io.github.usernamealreadytakensht.games.game.StrengthKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gaia's strength setting: levels 1-19, then "Max" (its level 20). */
class GaiaLevelTest {
    private val gaia = EngineKind.GAIA_4

    @Test
    fun levelsRunFromOneToNineteenThenMax() {
        assertEquals(StrengthKind.LEVEL, gaia.strength)
        assertEquals((1..19).toList<Int?>() + null, gaia.strengthPresets)
        assertTrue(gaia.defaultStrength in gaia.strengthPresets)
    }

    @Test
    fun labelsAndDescriptionsFollowTheAuthorsLadder() {
        assertEquals("Level 1", gaia.strengthLabel(1))
        assertEquals("Max", gaia.strengthLabel(null))
        assertEquals("~580 Elo: just learned the moves", gaia.levelDescription(1))
        assertEquals("~2640 Elo: grandmaster", gaia.levelDescription(19))
        assertEquals("Full strength, unbeatable", gaia.levelDescription(null))
    }

    @Test
    fun strengthIsResetWhenComingFromAnotherKindOfSetting() {
        // An Elo of 1500 means nothing as a level: back to the default one.
        assertEquals(gaia.defaultStrength, gaia.carryOver(1500, EngineKind.STOCKFISH_19))
        assertEquals(12, gaia.carryOver(12, gaia))
        assertEquals(19, gaia.snap(40))
    }

    @Test
    fun savedGameComesBackWithItsLevel() {
        val saved = GameConfig(engine = gaia, strength = 7).toJson()
        val back = GameConfig.fromJson(saved)
        assertEquals(gaia, back.engine)
        assertEquals(7, back.strength)
        assertEquals("Gaia 4.4 · Level 7", back.opponentLabel)
        assertFalse(back.chess960)
        assertFalse(EngineFamily.GAIA.plays(chess960 = true))
    }
}
