package io.github.usernamealreadytakensht.games.game.tafl

import io.github.usernamealreadytakensht.games.game.Takebacks
import io.github.usernamealreadytakensht.games.game.TimeControl
import org.json.JSONObject

/** Hnefatafl opponents. There is one: OpenTafl's AI, running in-process. */
enum class TaflEngineKind(val label: String, val description: String) {
    OPENTAFL("OpenTafl", "OpenTafl's alpha-beta search (transposition, killer and history tables) with its Copenhagen evaluation. Depth 1–2 is beatable, 4 plays a fair game, 6+ is strong.");

    companion object {
        /** Search depth presets; null = time-based (as deep as the thinking time allows). */
        val DEPTH_PRESETS: List<Int?> = listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, null)
        const val DEFAULT_DEPTH = 3

        fun strengthLabel(depth: Int?) = if (depth == null) "Max" else "Depth $depth"

        /** Seconds OpenTafl may think: quick at low depth, generous at Max. */
        fun thinkSeconds(depth: Int?): Int = when {
            depth == null -> 5
            depth <= 3 -> 1
            depth <= 5 -> 2
            depth <= 7 -> 4
            else -> 6
        }
    }
}

/** Settings of a hnefatafl game. `depth` null = time-based; `playerSide` null = random. */
data class TaflConfig(
    val engine: TaflEngineKind = TaflEngineKind.OPENTAFL,
    val depth: Int? = TaflEngineKind.DEFAULT_DEPTH,
    val playerSide: Tafl.Side? = Tafl.Side.DEFENDERS,
    val timeControl: TimeControl = TimeControl.None,
    val takebacks: Takebacks = Takebacks.ONCE,
) {
    val opponentLabel: String get() = "${engine.label} · ${TaflEngineKind.strengthLabel(depth)}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("engine", engine.name)
        put("depth", depth ?: -1)
        put("side", playerSide?.name ?: "random")
        put("clock", timeControl.toJson())
        put("takebacks", takebacks.name)
    }

    companion object {
        fun fromJson(o: JSONObject): TaflConfig = TaflConfig(
            engine = runCatching { TaflEngineKind.valueOf(o.getString("engine")) }.getOrDefault(TaflEngineKind.OPENTAFL),
            depth = o.optInt("depth", TaflEngineKind.DEFAULT_DEPTH).takeIf { it >= 0 },
            playerSide = when (o.optString("side")) {
                "ATTACKERS" -> Tafl.Side.ATTACKERS
                "DEFENDERS" -> Tafl.Side.DEFENDERS
                else -> null
            },
            timeControl = TimeControl.fromJson(o.optJSONObject("clock") ?: JSONObject()),
            takebacks = runCatching { Takebacks.valueOf(o.getString("takebacks")) }.getOrDefault(Takebacks.ONCE),
        )
    }
}
