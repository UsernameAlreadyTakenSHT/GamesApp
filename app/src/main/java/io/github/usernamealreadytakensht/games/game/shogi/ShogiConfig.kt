package io.github.usernamealreadytakensht.games.game.shogi

import io.github.usernamealreadytakensht.games.game.TimeControl
import org.json.JSONObject

/** How pieces are drawn: traditional kanji, or Latin letters for newcomers. */
enum class ShogiPieceStyle(val label: String, val tagline: String) {
    KANJI("Kanji", "Traditional pieces"),
    LETTERS("Letters", "K, R, B, G…"),
}

/**
 * Fairy-Stockfish's strength for shogi: levels 1–10 map onto its `Skill Level` option
 * (-20 … 16; negative levels are weaker than chess Stockfish's floor), null = full strength.
 */
object ShogiLevels {
    val PRESETS: List<Int?> = (1..10).toList() + listOf(null)
    const val DEFAULT = 4

    fun skill(level: Int?): Int = if (level == null) 20 else -20 + (level - 1) * 4

    fun label(level: Int?) = if (level == null) "Max" else "Level $level"

    fun description(level: Int?): String = when {
        level == null -> "Full strength: very hard to beat"
        level <= 2 -> "Beginner: blunders pieces"
        level <= 4 -> "Casual player"
        level <= 6 -> "Club player"
        level <= 8 -> "Strong amateur"
        else -> "Dan level"
    }

    /** Thinking time per move before the clock caps it. */
    fun moveTimeMs(level: Int?): Int = if (level == null) 3000 else 300 + level * 150
}

/** Settings of a shogi game. `level` null = full strength; `playerSide` null = random. */
data class ShogiConfig(
    val level: Int? = ShogiLevels.DEFAULT,
    val playerSide: Shogi.Side? = Shogi.Side.SENTE,
    val timeControl: TimeControl = TimeControl.None,
    val pieces: ShogiPieceStyle = ShogiPieceStyle.KANJI,
) {
    val opponentLabel: String get() = "Fairy-Stockfish · ${ShogiLevels.label(level)}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("level", level ?: -1)
        put("side", playerSide?.name ?: "random")
        put("clock", timeControl.toJson())
        put("pieces", pieces.name)
    }

    companion object {
        fun fromJson(o: JSONObject): ShogiConfig = ShogiConfig(
            level = o.optInt("level", ShogiLevels.DEFAULT).takeIf { it > 0 },
            playerSide = when (o.optString("side")) {
                "SENTE" -> Shogi.Side.SENTE
                "GOTE" -> Shogi.Side.GOTE
                else -> null
            },
            timeControl = TimeControl.fromJson(o.optJSONObject("clock") ?: JSONObject()),
            pieces = runCatching { ShogiPieceStyle.valueOf(o.getString("pieces")) }.getOrDefault(ShogiPieceStyle.KANJI),
        )
    }
}
