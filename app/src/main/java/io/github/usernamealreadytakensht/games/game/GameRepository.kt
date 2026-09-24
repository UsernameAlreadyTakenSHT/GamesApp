package io.github.usernamealreadytakensht.games.game

import android.content.Context
import com.github.bhlangonijr.chesslib.Side
import io.github.usernamealreadytakensht.games.game.draughts.Draughts
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsConfig
import io.github.usernamealreadytakensht.games.game.fox.Fox
import io.github.usernamealreadytakensht.games.game.fox.FoxConfig
import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.MorrisConfig
import io.github.usernamealreadytakensht.games.game.tafl.Tafl
import io.github.usernamealreadytakensht.games.game.tafl.TaflConfig
import org.json.JSONArray
import org.json.JSONObject

/** Everything needed to resume a game in progress. Clocks are stored frozen. */
data class SavedGame(
    val config: GameConfig,
    val playerSide: Side,
    val uciMoves: List<String>,
    val whiteMs: Long?,
    val blackMs: Long?,
    /** Chess960 start position (Shredder-FEN); null for standard chess. */
    val startFen: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("config", config.toJson())
        put("playerSide", playerSide.name)
        put("moves", JSONArray(uciMoves))
        startFen?.let { put("startFen", it) }
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
                startFen = o.optString("startFen").takeIf { it.isNotEmpty() },
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

data class SavedMorrisGame(
    val config: MorrisConfig,
    val playerSide: Morris.Color,
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
        fun fromJson(o: JSONObject): SavedMorrisGame {
            val moves = o.getJSONArray("moves")
            return SavedMorrisGame(
                config = MorrisConfig.fromJson(o.getJSONObject("config")),
                playerSide = Morris.Color.valueOf(o.getString("playerSide")),
                moves = List(moves.length()) { moves.getString(it) },
                whiteMs = o.getLong("whiteMs").takeIf { it >= 0 },
                blackMs = o.getLong("blackMs").takeIf { it >= 0 },
            )
        }
    }
}

data class SavedTaflGame(
    val config: TaflConfig,
    val playerSide: Tafl.Side,
    val moves: List<String>,
    val attackersMs: Long?,
    val defendersMs: Long?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("config", config.toJson())
        put("playerSide", playerSide.name)
        put("moves", JSONArray(moves))
        put("attackersMs", attackersMs ?: -1L)
        put("defendersMs", defendersMs ?: -1L)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedTaflGame {
            val moves = o.getJSONArray("moves")
            return SavedTaflGame(
                config = TaflConfig.fromJson(o.getJSONObject("config")),
                playerSide = Tafl.Side.valueOf(o.getString("playerSide")),
                moves = List(moves.length()) { moves.getString(it) },
                attackersMs = o.getLong("attackersMs").takeIf { it >= 0 },
                defendersMs = o.getLong("defendersMs").takeIf { it >= 0 },
            )
        }
    }
}

data class SavedFoxGame(
    val config: FoxConfig,
    val playerSide: Fox.Side,
    val moves: List<String>,
    val foxMs: Long?,
    val huntersMs: Long?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("config", config.toJson())
        put("playerSide", playerSide.name)
        put("moves", JSONArray(moves))
        put("foxMs", foxMs ?: -1L)
        put("huntersMs", huntersMs ?: -1L)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedFoxGame {
            val moves = o.getJSONArray("moves")
            return SavedFoxGame(
                config = FoxConfig.fromJson(o.getJSONObject("config")),
                playerSide = Fox.Side.valueOf(o.getString("playerSide")),
                moves = List(moves.length()) { moves.getString(it) },
                foxMs = o.getLong("foxMs").takeIf { it >= 0 },
                huntersMs = o.getLong("huntersMs").takeIf { it >= 0 },
            )
        }
    }
}

/** Persists the in-progress games (chess, draughts, morris, hnefatafl, fox games) and the last used settings. */
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

    // ---- morris

    fun saveMorrisGame(game: SavedMorrisGame) {
        prefs.edit().putString(KEY_MORRIS_GAME, game.toJson().toString()).apply()
    }

    fun loadMorrisGame(): SavedMorrisGame? =
        prefs.getString(KEY_MORRIS_GAME, null)
            ?.let { runCatching { SavedMorrisGame.fromJson(JSONObject(it)) }.getOrNull() }

    fun clearMorrisGame() {
        prefs.edit().remove(KEY_MORRIS_GAME).apply()
    }

    fun saveLastMorrisConfig(config: MorrisConfig) {
        prefs.edit().putString(KEY_MORRIS_CONFIG, config.toJson().toString()).apply()
    }

    fun loadLastMorrisConfig(): MorrisConfig =
        prefs.getString(KEY_MORRIS_CONFIG, null)
            ?.let { runCatching { MorrisConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: MorrisConfig()

    // ---- hnefatafl

    fun saveTaflGame(game: SavedTaflGame) {
        prefs.edit().putString(KEY_TAFL_GAME, game.toJson().toString()).apply()
    }

    fun loadTaflGame(): SavedTaflGame? =
        prefs.getString(KEY_TAFL_GAME, null)
            ?.let { runCatching { SavedTaflGame.fromJson(JSONObject(it)) }.getOrNull() }

    fun clearTaflGame() {
        prefs.edit().remove(KEY_TAFL_GAME).apply()
    }

    fun saveLastTaflConfig(config: TaflConfig) {
        prefs.edit().putString(KEY_TAFL_CONFIG, config.toJson().toString()).apply()
    }

    fun loadLastTaflConfig(): TaflConfig =
        prefs.getString(KEY_TAFL_CONFIG, null)
            ?.let { runCatching { TaflConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: TaflConfig()

    // ---- fox games

    fun saveFoxGame(game: SavedFoxGame) {
        prefs.edit().putString(KEY_FOX_GAME, game.toJson().toString()).apply()
    }

    fun loadFoxGame(): SavedFoxGame? =
        prefs.getString(KEY_FOX_GAME, null)
            ?.let { runCatching { SavedFoxGame.fromJson(JSONObject(it)) }.getOrNull() }

    fun clearFoxGame() {
        prefs.edit().remove(KEY_FOX_GAME).apply()
    }

    fun saveLastFoxConfig(config: FoxConfig) {
        prefs.edit().putString(KEY_FOX_CONFIG, config.toJson().toString()).apply()
    }

    fun loadLastFoxConfig(): FoxConfig =
        prefs.getString(KEY_FOX_CONFIG, null)
            ?.let { runCatching { FoxConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: FoxConfig()

    private companion object {
        const val KEY_GAME = "chess_game"
        const val KEY_CONFIG = "chess_config"
        const val KEY_DRAUGHTS_GAME = "draughts_game"
        const val KEY_DRAUGHTS_CONFIG = "draughts_config"
        const val KEY_MORRIS_GAME = "morris_game"
        const val KEY_MORRIS_CONFIG = "morris_config"
        const val KEY_TAFL_GAME = "tafl_game"
        const val KEY_TAFL_CONFIG = "tafl_config"
        const val KEY_FOX_GAME = "fox_game"
        const val KEY_FOX_CONFIG = "fox_config"
    }
}
