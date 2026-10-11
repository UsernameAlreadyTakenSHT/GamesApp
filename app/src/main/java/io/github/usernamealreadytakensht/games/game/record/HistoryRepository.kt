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

    // Each game screen has its own repository: the lock is shared, not per instance.
    fun all(): List<GameRecord> = synchronized(LOCK) {
        if (!file.exists()) emptyList()
        else runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { arr.getJSONObject(it) }.mapNotNull { runCatching { GameRecord.fromJson(it) }.getOrNull() }
        }.getOrElse { e ->
            Log.w(TAG, "Unreadable history", e)
            emptyList()
        }
    }

    fun add(record: GameRecord) = synchronized(LOCK) { write((listOf(record) + all()).take(MAX)) }

    fun delete(endedAt: Long) = synchronized(LOCK) { write(all().filterNot { it.endedAt == endedAt }) }

    fun clear() = synchronized(LOCK) { write(emptyList()) }

    private fun write(records: List<GameRecord>) {
        val tmp = File(file.parentFile, "history.json.tmp")
        tmp.writeText(JSONArray().apply { records.forEach { put(it.toJson()) } }.toString())
        // rename() replaces the old file atomically; deleting it first would open a window
        // where a crash loses the whole history.
        if (!tmp.renameTo(file)) Log.w(TAG, "Could not save the history")
    }

    private companion object {
        const val TAG = "History"
        const val MAX = 500
        val LOCK = Any()
    }
}
