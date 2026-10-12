package com.instasave.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lightweight in-memory thumbnail cache (about 8 MB of bitmaps).
 */
private object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, value: Bitmap) { cache.put(key, value) }
}

private val thumbClient = OkHttpClient()

private suspend fun loadThumb(url: String): Bitmap? = withContext(Dispatchers.IO) {
    ThumbCache.get(url)?.let { return@withContext it }
    try {
        val resp = thumbClient.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) return@withContext null
        val bytes = resp.body?.bytes() ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256 &&
            bounds.outHeight / (sample * 2) >= 256
        ) sample *= 2

        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null
        ThumbCache.put(url, bmp)
        bmp
    } catch (e: Exception) {
        null
    }
}

/**
 * Square thumbnail of the video, or a neutral placeholder when unavailable.
 */
@Composable
fun Thumb(url: String?, modifier: Modifier = Modifier) {
    val state = produceState<Bitmap?>(initialValue = null, key1 = url) {
        value = url?.takeIf { it.isNotBlank() }?.let { loadThumb(it) }
    }
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = state.value
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp),
            )
        } else {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
