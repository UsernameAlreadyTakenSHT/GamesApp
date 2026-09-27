package io.github.usernamealreadytakensht.games.game.record

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A game written out in a file format: what the Share button sends and the history keeps. */
data class ExportedGame(val fileName: String, val mimeType: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("file", fileName).put("mime", mimeType).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = ExportedGame(o.getString("file"), o.getString("mime"), o.getString("text"))
    }
}

/** The result from the player's point of view. */
enum class PlayerResult(val label: String) { WIN("Win"), LOSS("Loss"), DRAW("Draw") }

/**
 * A finished game as the history keeps it. [game] is the game's family ("Chess", "Draughts"…)
 * used to group the stats; [variant] the rule set when the family has several ("Chess960",
 * "English checkers", "Copenhagen"…).
 */
data class GameRecord(
    val endedAt: Long,
    val game: String,
    val variant: String?,
    val opponent: String,
    val playerSide: String,
    val result: PlayerResult,
    val reason: String,
    val moveCount: Int,
    val export: ExportedGame,
) {
    /** Opponent without its strength setting: "Stockfish 19 · Elo 1500" -> "Stockfish 19". */
    val engine: String get() = opponent.substringBefore(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("endedAt", endedAt)
        put("game", game)
        variant?.let { put("variant", it) }
        put("opponent", opponent)
        put("side", playerSide)
        put("result", result.name)
        put("reason", reason)
        put("moves", moveCount)
        put("export", export.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = GameRecord(
            endedAt = o.getLong("endedAt"),
            game = o.getString("game"),
            variant = o.optString("variant").takeIf { it.isNotEmpty() },
            opponent = o.getString("opponent"),
            playerSide = o.getString("side"),
            result = PlayerResult.valueOf(o.getString("result")),
            reason = o.optString("reason"),
            moveCount = o.optInt("moves"),
            export = ExportedGame.fromJson(o.getJSONObject("export")),
        )
    }
}

/** Helpers shared by the per-game exporters. */
object Exports {
    fun fileStamp(time: Long): String = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(time))
    fun pgnDate(time: Long): String = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(Date(time))
    fun readableDate(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(time))

    /** PGN / PDN tag pair, with quotes and backslashes escaped. */
    fun tag(name: String, value: String) = "[$name \"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"

    /**
     * Numbered move text ("1. e4 e5 2. Nf3 …") ending with [result], wrapped at 80 columns as
     * PGN and PDN expect.
     */
    fun movetext(moves: List<String>, result: String): String {
        val tokens = ArrayList<String>()
        moves.forEachIndexed { i, m -> if (i % 2 == 0) tokens += "${i / 2 + 1}."; tokens += m }
        tokens += result
        val lines = ArrayList<String>()
        val line = StringBuilder()
        for (t in tokens) {
            if (line.isNotEmpty() && line.length + 1 + t.length > 80) { lines += line.toString(); line.clear() }
            if (line.isNotEmpty()) line.append(' ')
            line.append(t)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.joinToString("\n")
    }

    /**
     * Plain-text record for games without a standard file format: a header, then numbered
     * moves, one pair per line.
     */
    fun plainText(title: String, time: Long, header: List<Pair<String, String>>, moves: List<String>): String = buildString {
        appendLine(title)
        appendLine("Date: ${readableDate(time)}")
        header.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        moves.chunked(2).forEachIndexed { i, pair -> appendLine("${i + 1}. ${pair.joinToString("   ")}") }
    }
}
