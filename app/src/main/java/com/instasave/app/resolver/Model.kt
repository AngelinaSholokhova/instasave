package com.instasave.app.resolver

import com.instasave.app.settings.Quality

/** One of several renditions of the same video (`video_versions` entry). */
data class VideoVariant(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val type: Int = 0,
)

data class ResolvedMedia(
    val url: String,
    val shortcode: String,
    val username: String? = null,
    val thumbnail: String? = null,
    val caption: String? = null,
    val durationSec: Int? = null,
    /** All known renditions; the first item is also available as [url]. */
    val variants: List<VideoVariant> = emptyList(),
)

enum class ResolveKind { LOGIN_REQUIRED, RATE_LIMIT, PRIVATE, NOT_FOUND, NOT_VIDEO, NETWORK, PARSE }

class ResolveException(
    message: String,
    val kind: ResolveKind = ResolveKind.PARSE,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Picks the rendition matching [quality]. Falls back to [url] when only one is known.
 */
fun ResolvedMedia.selectUrl(quality: Quality): String {
    if (variants.isEmpty()) return url
    // Progressive MP4 renditions (101/102/103) are the ones that download standalone.
    val candidates = variants.filter { it.type in 101..103 || it.type == 0 }
        .ifEmpty { variants }
        .distinctBy { it.url }
        .sortedBy { it.height * 10_000 + it.width }
    return when (quality) {
        Quality.MAX -> candidates.last().url
        Quality.LOW -> candidates.first().url
        Quality.MEDIUM -> candidates[candidates.size / 2].url
    }
}
