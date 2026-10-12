package com.instasave.app.download

import com.instasave.app.resolver.InstagramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

class Downloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Downloads [url] into [dest], reporting progress 0..1. */
    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", InstagramClient.USER_AGENT)
                .header("Referer", "https://www.instagram.com/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw DownloadException("Видеосервер Instagram ответил HTTP ${resp.code}")
                }
                val body = resp.body ?: throw DownloadException("Пустой ответ видеосервера")
                val total = body.contentLength()
                val input = body.byteStream()
                try {
                    dest.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                        out.flush()
                    }
                } finally {
                    input.close()
                }
            }
        }
    }
}