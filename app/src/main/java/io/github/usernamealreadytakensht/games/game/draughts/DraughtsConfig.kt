package io.github.usernamealreadytakensht.games.game.draughts

import io.github.usernamealreadytakensht.games.game.Takebacks
import io.github.usernamealreadytakensht.games.game.TimeControl
import org.json.JSONObject

/** Draughts engines: Hub-protocol binaries bundled in jniLibs with their data as assets. */
enum class DraughtsEngineKind(
    val label: String,
    val binary: String,
    /** Asset directory extracted to the engine's working directory (data files, config). */
    val assetDir: String,
    /** Command-line arguments; Hub mode is selected by the trailing "hub". */
    val args: List<String>,
    val description: String,
) {
    SCAN(
        "Scan 3.1", "libscan.so", "scan", listOf("hub"),
        "Fabien Letouzey's engine, computer-olympiad champion. Book on with light randomness.",
    ),
    MOBYDAM(
        "Moby Dam", "libmobydam.so", "mobydam", listOf("-t", "20", "hub"),
        "Harm Jetten's engine: strong but a notch below Scan, different style.",
    );

    companion object {
        /** Search depth presets; null = time-based (as strong as the thinking time allows). */
        val DEPTH_PRESETS: List<Int?> = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, null)
        const val DEFAULT_DEPTH = 6

        fun strengthLabel(depth: Int?) = if (depth == null) "Max" else "Depth $depth"
    }
}

/** Settings of a draughts game. `depth` null = time-based; `playerSide` null = random. */
data class DraughtsConfig(
    val engine: DraughtsEngineKind = DraughtsEngineKind.SCAN,
    val depth: Int? = DraughtsEngineKind.DEFAULT_DEPTH,
    val playerSide: Draughts.Color? = Draughts.Color.WHITE,
    val timeControl: TimeControl = TimeControl.None,
    val takebacks: Takebacks = Takebacks.UNLIMITED,
) {
    val opponentLabel: String get() = "${engine.label} · ${DraughtsEngineKind.strengthLabel(depth)}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("engine", engine.name)
        put("depth", depth ?: -1)
        put("side", playerSide?.name ?: "random")
        put("clock", timeControl.toJson())
        put("takebacks", takebacks.name)
    }

    companion object {
        fun fromJson(o: JSONObject): DraughtsConfig = DraughtsConfig(
            engine = runCatching { DraughtsEngineKind.valueOf(o.getString("engine")) }.getOrDefault(DraughtsEngineKind.SCAN),
            depth = o.optInt("depth", DraughtsEngineKind.DEFAULT_DEPTH).takeIf { it >= 0 },
            playerSide = when (o.optString("side")) {
                "WHITE" -> Draughts.Color.WHITE
                "BLACK" -> Draughts.Color.BLACK
                else -> null
            },
            timeControl = TimeControl.fromJson(o.optJSONObject("clock") ?: JSONObject()),
            takebacks = runCatching { Takebacks.valueOf(o.getString("takebacks")) }.getOrDefault(Takebacks.UNLIMITED),
        )
    }
}
