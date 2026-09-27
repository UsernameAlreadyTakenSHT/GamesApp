package io.github.usernamealreadytakensht.games.game

import android.content.Context
import com.github.bhlangonijr.chesslib.Side
import io.github.usernamealreadytakensht.games.game.draughts.Draughts
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsConfig
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsVariant
import io.github.usernamealreadytakensht.games.game.fox.Fox
import io.github.usernamealreadytakensht.games.game.fox.FoxConfig
import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.MorrisConfig
import io.github.usernamealreadytakensht.games.game.shogi.Shogi
import io.github.usernamealreadytakensht.games.game.shogi.ShogiConfig
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

/** A shogi game in progress: settings, side, UCI moves (Fairy-Stockfish coordinates) and clocks. */
data class SavedShogiGame(
    val config: ShogiConfig,
    val playerSide: Shogi.Side,
    val moves: List<String>,
    val senteMs: Long?,
    val goteMs: Long?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("config", config.toJson())
        put("playerSide", playerSide.name)
        put("moves", JSONArray(moves))
        put("senteMs", senteMs ?: -1L)
        put("goteMs", goteMs ?: -1L)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedShogiGame {
            val moves = o.getJSONArray("moves")
            return SavedShogiGame(
                config = ShogiConfig.fromJson(o.getJSONObject("config")),
                playerSide = Shogi.Side.valueOf(o.getString("playerSide")),
                moves = List(moves.length()) { moves.getString(it) },
                senteMs = o.getLong("senteMs").takeIf { it >= 0 },
                goteMs = o.getLong("goteMs").takeIf { it >= 0 },
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

    // ---- draughts: one save per rule set (international 10x10, English checkers)

    private fun draughtsKey(variant: DraughtsVariant) =
        if (variant == DraughtsVariant.ENGLISH) KEY_CHECKERS_GAME else KEY_DRAUGHTS_GAME

    fun saveDraughtsGame(game: SavedDraughtsGame) {
        val json = game.toJson().put("savedAt", System.currentTimeMillis())
        prefs.edit().putString(draughtsKey(game.config.variant), json.toString()).apply()
    }

    fun loadDraughtsGame(variant: DraughtsVariant): SavedDraughtsGame? {
        migrateSharedDraughtsSave()
        return prefs.getString(draughtsKey(variant), null)
            ?.let { runCatching { SavedDraughtsGame.fromJson(JSONObject(it)) }.getOrNull() }
    }

    /** The most recently saved of the two draughts games (for the home screen's Resume). */
    fun loadLatestDraughtsGame(): SavedDraughtsGame? {
        migrateSharedDraughtsSave()
        return DraughtsVariant.entries
            .mapNotNull { v -> prefs.getString(draughtsKey(v), null)?.let { runCatching { JSONObject(it) }.getOrNull() } }
            .maxByOrNull { it.optLong("savedAt", 0L) }
            ?.let { runCatching { SavedDraughtsGame.fromJson(it) }.getOrNull() }
    }

    fun clearDraughtsGame(variant: DraughtsVariant) {
        prefs.edit().remove(draughtsKey(variant)).apply()
    }

    /** Versions up to 0.2.0 kept both rule sets under one key: move an English game to its own. */
    private fun migrateSharedDraughtsSave() {
        val raw = prefs.getString(KEY_DRAUGHTS_GAME, null) ?: return
        val game = runCatching { SavedDraughtsGame.fromJson(JSONObject(raw)) }.getOrNull() ?: return
        if (game.config.variant == DraughtsVariant.ENGLISH) {
            prefs.edit().remove(KEY_DRAUGHTS_GAME).putString(KEY_CHECKERS_GAME, raw).apply()
        }
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

    // ---- shogi

    fun saveShogiGame(game: SavedShogiGame) {
        prefs.edit().putString(KEY_SHOGI_GAME, game.toJson().toString()).apply()
    }

    fun loadShogiGame(): SavedShogiGame? =
        prefs.getString(KEY_SHOGI_GAME, null)
            ?.let { runCatching { SavedShogiGame.fromJson(JSONObject(it)) }.getOrNull() }

    fun clearShogiGame() {
        prefs.edit().remove(KEY_SHOGI_GAME).apply()
    }

    fun saveLastShogiConfig(config: ShogiConfig) {
        prefs.edit().putString(KEY_SHOGI_CONFIG, config.toJson().toString()).apply()
    }

    fun loadLastShogiConfig(): ShogiConfig =
        prefs.getString(KEY_SHOGI_CONFIG, null)
            ?.let { runCatching { ShogiConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: ShogiConfig()

    private companion object {
        const val KEY_SHOGI_GAME = "shogi_game"
        const val KEY_SHOGI_CONFIG = "shogi_config"
        const val KEY_GAME = "chess_game"
        const val KEY_CONFIG = "chess_config"
        const val KEY_DRAUGHTS_GAME = "draughts_game"
        const val KEY_CHECKERS_GAME = "checkers_game"
        const val KEY_DRAUGHTS_CONFIG = "draughts_config"
        const val KEY_MORRIS_GAME = "morris_game"
        const val KEY_MORRIS_CONFIG = "morris_config"
        const val KEY_TAFL_GAME = "tafl_game"
        const val KEY_TAFL_CONFIG = "tafl_config"
        const val KEY_FOX_GAME = "fox_game"
        const val KEY_FOX_CONFIG = "fox_config"
    }
}
