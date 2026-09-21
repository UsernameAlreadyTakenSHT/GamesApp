package io.github.usernamealreadytakensht.games.game.morris

import io.github.usernamealreadytakensht.games.game.Takebacks
import io.github.usernamealreadytakensht.games.game.TimeControl
import org.json.JSONObject

/** Nine Men's Morris opponents. There is one: the built-in alpha-beta searcher. */
enum class MorrisEngineKind(val label: String, val description: String) {
    MILLER("Miller", "The app's own alpha-beta search. Depth 1–3 misses simple mills, 6 is a solid club opponent, 10+ is very hard to beat.");

    companion object {
        /** Search depth presets; null = time-based (as deep as the thinking time allows). */
        val DEPTH_PRESETS: List<Int?> = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, null)
        const val DEFAULT_DEPTH = 5

        fun strengthLabel(depth: Int?) = if (depth == null) "Max" else "Depth $depth"
    }
}

/** Settings of a morris game. `depth` null = time-based; `playerSide` null = random. */
data class MorrisConfig(
    val engine: MorrisEngineKind = MorrisEngineKind.MILLER,
    val depth: Int? = MorrisEngineKind.DEFAULT_DEPTH,
    val playerSide: Morris.Color? = Morris.Color.WHITE,
    val timeControl: TimeControl = TimeControl.None,
    val takebacks: Takebacks = Takebacks.ONCE,
) {
    val opponentLabel: String get() = "${engine.label} · ${MorrisEngineKind.strengthLabel(depth)}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("engine", engine.name)
        put("depth", depth ?: -1)
        put("side", playerSide?.name ?: "random")
        put("clock", timeControl.toJson())
        put("takebacks", takebacks.name)
    }

    companion object {
        fun fromJson(o: JSONObject): MorrisConfig = MorrisConfig(
            engine = runCatching { MorrisEngineKind.valueOf(o.getString("engine")) }.getOrDefault(MorrisEngineKind.MILLER),
            depth = o.optInt("depth", MorrisEngineKind.DEFAULT_DEPTH).takeIf { it >= 0 },
            playerSide = when (o.optString("side")) {
                "WHITE" -> Morris.Color.WHITE
                "BLACK" -> Morris.Color.BLACK
                else -> null
            },
            timeControl = TimeControl.fromJson(o.optJSONObject("clock") ?: JSONObject()),
            takebacks = runCatching { Takebacks.valueOf(o.getString("takebacks")) }.getOrDefault(Takebacks.ONCE),
        )
    }
}
