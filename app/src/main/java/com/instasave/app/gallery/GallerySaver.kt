package com.instasave.app.gallery

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

class SaveException(message: String, cause: Throwable? = null) : IOException(message, cause)

class GallerySaver(
    private val context: Context,
    private val folderProvider: () -> String = { "InstaSave" },
) {

    private val folderName: String get() = folderProvider()
    private val mime = "video/mp4"

    /** Saves [file] into the gallery under Movies/<folder>. Returns the content Uri. */
    suspend fun save(file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) saveModern(file, displayName)
        else saveLegacy(file, displayName)
    }

    private fun saveModern(file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + folderName)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw SaveException("Не удалось создать запись в галерее")
        try {
            val out: OutputStream = resolver.openOutputStream(uri)
                ?: throw SaveException("Не удалось открыть файл для записи")
            out.use { stream ->
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        stream.write(buf, 0, n)
                    }
                    stream.flush()
                }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is SaveException) e else SaveException("Не удалось сохранить видео: ${e.message}", e)
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveLegacy(file: File, displayName: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folderName)
        if (!dir.exists() && !dir.mkdirs()) throw SaveException("Не удалось создать папку InstaSave")
        val dest = File(dir, displayName)
        try {
            file.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        } catch (e: Exception) {
            throw SaveException("Не удалось сохранить видео: ${e.message}", e)
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(dest)
    }
}