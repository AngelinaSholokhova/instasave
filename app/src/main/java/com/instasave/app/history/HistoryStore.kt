package com.instasave.app.history

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HistoryEntry(
    val shortcode: String,
    val username: String = "",
    val fileName: String,
    val uri: String? = null,
    val thumbnail: String? = null,
    val finishedAt: Long,
)

/**
 * Persists finished downloads so they survive app restarts.
 * Stored as JSON in SharedPreferences; capped to avoid unbounded growth.
 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("instasave_history", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = "entries"
    private val maxEntries = 100

    fun load(): List<HistoryEntry> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val list = runCatching {
            json.decodeFromString<List<HistoryEntry>>(raw)
        }.getOrDefault(emptyList())
        // Drop entries whose file the user deleted from the gallery.
        val alive = list.filter { entryStillExists(it) }
        if (alive.size != list.size) store(alive)
        return alive
    }

    fun put(entry: HistoryEntry) {
        // Newest first, drop duplicates by file name, drop entries whose file is gone.
        val list = (listOf(entry) + load())
            .distinctBy { it.fileName }
            .filter { entryStillExists(it) }
            .take(maxEntries)
        store(list)
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }

    /** Removes a single entry (by file name) without touching the rest of the history. */
    fun remove(fileName: String) {
        if (fileName.isBlank()) return
        store(load().filterNot { it.fileName == fileName })
    }

    /** True if an entry for [shortcode] exists and its file is still in the gallery. */
    fun containsShortcode(shortcode: String): Boolean {
        if (shortcode.isBlank()) return false
        return load().any { it.shortcode == shortcode }
    }

    private fun store(list: List<HistoryEntry>) {
        runCatching { prefs.edit().putString(key, json.encodeToString<List<HistoryEntry>>(list)).apply() }
    }

    private fun entryStillExists(entry: HistoryEntry): Boolean {
        val uriText = entry.uri ?: return true
        return runCatching {
            val uri = Uri.parse(uriText)
            resolver.query(uri, arrayOf("_id"), null, null, null)?.use { c -> c.count > 0 }
        }.getOrNull() ?: true
    }
}
