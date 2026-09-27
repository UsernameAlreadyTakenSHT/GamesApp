package io.github.usernamealreadytakensht.games.game.record

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * Finished games, newest first, kept in `files/history.json` (at most [MAX] games). Writes go
 * through a temporary file and a rename, so a crash never leaves half a history.
 */
class HistoryRepository(context: Context) {

    private val file = File(context.filesDir, "history.json")

    @Synchronized
    fun all(): List<GameRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { arr.getJSONObject(it) }.mapNotNull { runCatching { GameRecord.fromJson(it) }.getOrNull() }
        }.getOrElse { e ->
            Log.w(TAG, "Unreadable history", e)
            emptyList()
        }
    }

    @Synchronized
    fun add(record: GameRecord) = write((listOf(record) + all()).take(MAX))

    @Synchronized
    fun delete(endedAt: Long) = write(all().filterNot { it.endedAt == endedAt })

    @Synchronized
    fun clear() = write(emptyList())

    private fun write(records: List<GameRecord>) {
        val tmp = File(file.parentFile, "history.json.tmp")
        tmp.writeText(JSONArray().apply { records.forEach { put(it.toJson()) } }.toString())
        file.delete()
        tmp.renameTo(file)
    }

    private companion object {
        const val TAG = "History"
        const val MAX = 500
    }
}
