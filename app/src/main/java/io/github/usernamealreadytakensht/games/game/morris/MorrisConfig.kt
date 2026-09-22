package io.github.usernamealreadytakensht.games.game.morris

import io.github.usernamealreadytakensht.games.game.TimeControl
import org.json.JSONObject

/** Nine Men's Morris engine families (one so far), in display order. */
enum class MorrisEngineFamily(val label: String, val tagline: String) {
    SANMILL("Sanmill", "The Sanmill app's engine, in Rust."),
}

/** A morris opponent: an engine family plus its search algorithm (Sanmill's UCI `Algorithm` option). */
enum class MorrisEngineKind(
    val family: MorrisEngineFamily,
    val label: String,
    val binary: String,
    val algorithm: Int,
    val description: String,
) {
    SANMILL_MTDF(
        MorrisEngineFamily.SANMILL, "MTD(f)", "libsanmill.so", 2,
        "Sanmill's default search: alpha-beta driven by zero-window probes around a guess. Strong and fast.",
    ),
    SANMILL_ALPHABETA(
        MorrisEngineFamily.SANMILL, "Alpha-beta", "libsanmill.so", 0,
        "Classic alpha-beta with iterative deepening. Same evaluation as MTD(f), a little slower.",
    ),
    SANMILL_MCTS(
        MorrisEngineFamily.SANMILL, "MCTS", "libsanmill.so", 3,
        "Monte-Carlo tree search: plays from simulated games instead of an evaluation. Varied, more human, weaker in sharp tactics.",
    );

    companion object {
        /** Search depth presets; null = time-based (as deep as the thinking time allows). */
        val DEPTH_PRESETS: List<Int?> = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, null)
        const val DEFAULT_DEPTH = 5

        fun of(family: MorrisEngineFamily): List<MorrisEngineKind> = entries.filter { it.family == family }

        fun strengthLabel(depth: Int?) = if (depth == null) "Max" else "Depth $depth"
    }
}

/** Settings of a morris game. `depth` null = time-based; `playerSide` null = random. */
data class MorrisConfig(
    val engine: MorrisEngineKind = MorrisEngineKind.SANMILL_MTDF,
    val depth: Int? = MorrisEngineKind.DEFAULT_DEPTH,
    val playerSide: Morris.Color? = Morris.Color.WHITE,
    val timeControl: TimeControl = TimeControl.None,
) {
    /** "Sanmill (MTD(f)) · Depth 5". */
    val opponentLabel: String
        get() = "${engine.family.label} (${engine.label}) · ${MorrisEngineKind.strengthLabel(depth)}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("engine", engine.name)
        put("depth", depth ?: -1)
        put("side", playerSide?.name ?: "random")
        put("clock", timeControl.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): MorrisConfig = MorrisConfig(
            engine = runCatching { MorrisEngineKind.valueOf(o.getString("engine")) }.getOrDefault(MorrisEngineKind.SANMILL_MTDF),
            depth = o.optInt("depth", MorrisEngineKind.DEFAULT_DEPTH).takeIf { it >= 0 },
            playerSide = when (o.optString("side")) {
                "WHITE" -> Morris.Color.WHITE
                "BLACK" -> Morris.Color.BLACK
                else -> null
            },
            timeControl = TimeControl.fromJson(o.optJSONObject("clock") ?: JSONObject()),
        )
    }
}
