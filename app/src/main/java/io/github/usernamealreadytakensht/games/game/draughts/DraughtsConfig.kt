package io.github.usernamealreadytakensht.games.game.draughts

import io.github.usernamealreadytakensht.games.game.TimeControl
import org.json.JSONObject

/** Rule sets of the draughts tile. International is the default. */
enum class DraughtsVariant(val label: String, val size: Int, val tagline: String, val description: String) {
    INTERNATIONAL(
        "International", 10, "10x10, flying kings",
        "The FMJD game: 20 men each on 10x10, men capture backwards too, kings fly along the diagonals, and the capture taking the most pieces is compulsory. White moves first.",
    ),
    ENGLISH(
        "English checkers", 8, "8x8, American rules",
        "English draughts / American checkers: 12 men each on 8x8, men move and capture forward only, kings move one square in any direction, captures are compulsory but you may choose which one. Black moves first.",
    );
}

/** Draughts engines. Scan and Moby Dam speak the Hub protocol on 10x10, Marcher plays 8x8. */
enum class DraughtsEngineKind(
    val label: String,
    val variant: DraughtsVariant,
    val binary: String,
    /** Asset directory extracted to the engine's working directory (data files, config). */
    val assetDir: String,
    /** Command-line arguments; Hub mode is selected by the trailing "hub". */
    val args: List<String>,
    val description: String,
) {
    SCAN(
        "Scan 3.1", DraughtsVariant.INTERNATIONAL, "libscan.so", "scan", listOf("hub"),
        "Fabien Letouzey's engine, computer-olympiad champion. Book on with light randomness.",
    ),
    MOBYDAM(
        "Moby Dam", DraughtsVariant.INTERNATIONAL, "libmobydam.so", "mobydam", listOf("-t", "20", "hub"),
        "Harm Jetten's engine: strong but a notch below Scan, different style.",
    ),
    MARCHER(
        "Marcher", DraughtsVariant.ENGLISH, "libmarcher.so", "marcher", emptyList(),
        "Collin Kees' checkers engine: alpha-beta with an NNUE evaluation and a 4-piece endgame database, about KingsRow's strength.",
    );

    companion object {
        /** Search depth presets; null = time-based (as strong as the thinking time allows). */
        val DEPTH_PRESETS: List<Int?> = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, null)
        const val DEFAULT_DEPTH = 6

        fun of(variant: DraughtsVariant): List<DraughtsEngineKind> = entries.filter { it.variant == variant }

        fun strengthLabel(depth: Int?) = if (depth == null) "Max" else "Depth $depth"
    }
}

/** Settings of a draughts game. `depth` null = time-based; `playerSide` null = random. */
data class DraughtsConfig(
    val variant: DraughtsVariant = DraughtsVariant.INTERNATIONAL,
    val engine: DraughtsEngineKind = DraughtsEngineKind.SCAN,
    val depth: Int? = DraughtsEngineKind.DEFAULT_DEPTH,
    val playerSide: Draughts.Color? = Draughts.Color.WHITE,
    val timeControl: TimeControl = TimeControl.None,
) {
    val opponentLabel: String get() = "${engine.label} · ${DraughtsEngineKind.strengthLabel(depth)}"

    /** Switches the rule set, moving the engine to one that plays it. */
    fun withVariant(v: DraughtsVariant): DraughtsConfig =
        if (engine.variant == v) copy(variant = v) else copy(variant = v, engine = DraughtsEngineKind.of(v).first())

    fun toJson(): JSONObject = JSONObject().apply {
        put("variant", variant.name)
        put("engine", engine.name)
        put("depth", depth ?: -1)
        put("side", playerSide?.name ?: "random")
        put("clock", timeControl.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): DraughtsConfig {
            val variant = runCatching { DraughtsVariant.valueOf(o.getString("variant")) }.getOrDefault(DraughtsVariant.INTERNATIONAL)
            val engine = runCatching { DraughtsEngineKind.valueOf(o.getString("engine")) }.getOrNull()
                ?.takeIf { it.variant == variant } ?: DraughtsEngineKind.of(variant).first()
            return DraughtsConfig(
                variant = variant,
                engine = engine,
                depth = o.optInt("depth", DraughtsEngineKind.DEFAULT_DEPTH).takeIf { it >= 0 },
                playerSide = when (o.optString("side")) {
                    "WHITE" -> Draughts.Color.WHITE
                    "BLACK" -> Draughts.Color.BLACK
                    else -> null
                },
                timeControl = TimeControl.fromJson(o.optJSONObject("clock") ?: JSONObject()),
            )
        }
    }
}
