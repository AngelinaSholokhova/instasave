package com.instasave.app.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class Release(
    val tag_name: String? = null,
    val html_url: String? = null,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
)

/**
 * Checks the GitHub Releases of [repo] ("owner/name") for a version newer than the running one.
 * Returns the latest release, or null when nothing newer is available / the check fails.
 */
object UpdateChecker {

    private const val TAG = "InstaSave"
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun latestRelease(repo: String): Release? = withContext(Dispatchers.IO) {
        if (repo.isBlank()) return@withContext null
        try {
            val url = "https://api.github.com/repos/$repo/releases/latest"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InstaSave")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i(TAG, "update check: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string().orEmpty()
                runCatching { json.decodeFromString<Release>(body) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.i(TAG, "update check failed: $e")
            null
        }
    }

    /**
     * True when [tag] is a newer version than [current]. Handles a leading "v" and
     * plain numeric tags like "1.2.3".
     */
    fun isNewer(current: String, tag: String?): Boolean {
        if (tag.isNullOrBlank()) return false
        val newNums = tag.removePrefix("v").split('.', '-', '_')
            .mapNotNull { it.toIntOrNull() }
        val curNums = current.removePrefix("v").split('.', '-', '_')
            .mapNotNull { it.toIntOrNull() }
        if (newNums.isEmpty() || curNums.isEmpty()) return false
        val len = maxOf(newNums.size, curNums.size)
        for (i in 0 until len) {
            val a = curNums.getOrElse(i) { 0 }
            val b = newNums.getOrElse(i) { 0 }
            if (b > a) return true
            if (b < a) return false
        }
        return false
    }
}
