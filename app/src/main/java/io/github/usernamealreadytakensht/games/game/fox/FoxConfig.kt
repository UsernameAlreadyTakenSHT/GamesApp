package io.github.usernamealreadytakensht.games.game.fox

import io.github.usernamealreadytakensht.games.game.TimeControl
import org.json.JSONObject

/** Fox-game opponents. There is one: the built-in searcher, "Reynard". */
enum class FoxEngineKind(val label: String, val description: String) {
    REYNARD("Reynard", "The app's own negamax search with a transposition table. These games are small: depth 8+ is close to perfect play.");

    companion object {
        /** Search depth presets; null = time-based (as deep as the thinking time allows). */
        val DEPTH_PRESETS: List<Int?> = listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 20, null)
        const val DEFAULT_DEPTH = 4

        fun strengthLabel(depth: Int?) = if (depth == null) "Max" else "Depth $depth"
    }
}

/** Settings of a fox game. `depth` null = time-based; `playerSide` null = random. */
data class FoxConfig(
    val variant: FoxVariant = FoxVariant.HOUNDS,
    val engine: FoxEngineKind = FoxEngineKind.REYNARD,
    val depth: Int? = FoxEngineKind.DEFAULT_DEPTH,
    val playerSide: Fox.Side? = Fox.Side.FOX,
    val timeControl: TimeControl = TimeControl.None,
) {
    val opponentLabel: String get() = "${engine.label} · ${FoxEngineKind.strengthLabel(depth)}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("variant", variant.name)
        put("engine", engine.name)
        put("depth", depth ?: -1)
        put("side", playerSide?.name ?: "random")
        put("clock", timeControl.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): FoxConfig = FoxConfig(
            variant = runCatching { FoxVariant.valueOf(o.getString("variant")) }.getOrDefault(FoxVariant.HOUNDS),
            engine = runCatching { FoxEngineKind.valueOf(o.getString("engine")) }.getOrDefault(FoxEngineKind.REYNARD),
            depth = o.optInt("depth", FoxEngineKind.DEFAULT_DEPTH).takeIf { it >= 0 },
            playerSide = when (o.optString("side")) {
                "FOX" -> Fox.Side.FOX
                "HUNTERS" -> Fox.Side.HUNTERS
                else -> null
            },
            timeControl = TimeControl.fromJson(o.optJSONObject("clock") ?: JSONObject()),
        )
    }
}
