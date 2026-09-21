package io.github.usernamealreadytakensht.games.game

import android.content.Context
import com.github.bhlangonijr.chesslib.Side
import io.github.usernamealreadytakensht.games.game.draughts.Draughts
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsConfig
import org.json.JSONArray
import org.json.JSONObject

/** Everything needed to resume a game in progress. Clocks are stored frozen. */
data class SavedGame(
    val config: GameConfig,
    val playerSide: Side,
    val uciMoves: List<String>,
    val whiteMs: Long?,
    val blackMs: Long?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("config", config.toJson())
        put("playerSide", playerSide.name)
        put("moves", JSONArray(uciMoves))
        put("whiteMs", whiteMs ?: -1L)
        put("blackMs", blackMs ?: -1L)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedGame {
            val moves = o.getJSONArray("moves")
            return SavedGame(
                config = GameConfig.fromJson(o.getJSONObject("config")),
                playerSide = Side.valueOf(o.getString("playerSide")),
                uciMoves = List(moves.length()) { moves.getString(it) },
                whiteMs = o.getLong("whiteMs").takeIf { it >= 0 },
                blackMs = o.getLong("blackMs").takeIf { it >= 0 },
            )
        }
    }
}

/** A draughts game in progress: settings, colour, Hub moves and frozen clocks. */
data class SavedDraughtsGame(
    val config: DraughtsConfig,
    val playerSide: Draughts.Color,
    val moves: List<String>,
    val whiteMs: Long?,
    val blackMs: Long?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("config", config.toJson())
        put("playerSide", playerSide.name)
        put("moves", JSONArray(moves))
        put("whiteMs", whiteMs ?: -1L)
        put("blackMs", blackMs ?: -1L)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedDraughtsGame {
            val moves = o.getJSONArray("moves")
            return SavedDraughtsGame(
                config = DraughtsConfig.fromJson(o.getJSONObject("config")),
                playerSide = Draughts.Color.valueOf(o.getString("playerSide")),
                moves = List(moves.length()) { moves.getString(it) },
                whiteMs = o.getLong("whiteMs").takeIf { it >= 0 },
                blackMs = o.getLong("blackMs").takeIf { it >= 0 },
            )
        }
    }
}

/** Persists the in-progress chess and draughts games and the last used settings. */
class GameRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("games", Context.MODE_PRIVATE)

    fun saveGame(game: SavedGame) {
        prefs.edit().putString(KEY_GAME, game.toJson().toString()).apply()
    }

    fun loadGame(): SavedGame? =
        prefs.getString(KEY_GAME, null)?.let { runCatching { SavedGame.fromJson(JSONObject(it)) }.getOrNull() }

    fun clearGame() {
        prefs.edit().remove(KEY_GAME).apply()
    }

    fun saveLastConfig(config: GameConfig) {
        prefs.edit().putString(KEY_CONFIG, config.toJson().toString()).apply()
    }

    fun loadLastConfig(): GameConfig =
        prefs.getString(KEY_CONFIG, null)
            ?.let { runCatching { GameConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: GameConfig()

    // ---- draughts (same shape, separate keys)

    fun saveDraughtsGame(game: SavedDraughtsGame) {
        prefs.edit().putString(KEY_DRAUGHTS_GAME, game.toJson().toString()).apply()
    }

    fun loadDraughtsGame(): SavedDraughtsGame? =
        prefs.getString(KEY_DRAUGHTS_GAME, null)
            ?.let { runCatching { SavedDraughtsGame.fromJson(JSONObject(it)) }.getOrNull() }

    fun clearDraughtsGame() {
        prefs.edit().remove(KEY_DRAUGHTS_GAME).apply()
    }

    fun saveLastDraughtsConfig(config: DraughtsConfig) {
        prefs.edit().putString(KEY_DRAUGHTS_CONFIG, config.toJson().toString()).apply()
    }

    fun loadLastDraughtsConfig(): DraughtsConfig =
        prefs.getString(KEY_DRAUGHTS_CONFIG, null)
            ?.let { runCatching { DraughtsConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: DraughtsConfig()

    private companion object {
        const val KEY_GAME = "chess_game"
        const val KEY_CONFIG = "chess_config"
        const val KEY_DRAUGHTS_GAME = "draughts_game"
        const val KEY_DRAUGHTS_CONFIG = "draughts_config"
    }
}
