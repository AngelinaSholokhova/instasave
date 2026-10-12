package com.instasave.app

import com.instasave.app.resolver.ResolvedMedia
import com.instasave.app.resolver.VideoVariant
import com.instasave.app.resolver.selectUrl
import com.instasave.app.settings.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {

    private val media = ResolvedMedia(
        url = "https://cdn.example/hi.mp4",
        shortcode = "abc123",
        variants = listOf(
            VideoVariant(url = "https://cdn.example/lo.mp4", width = 480, height = 854, type = 101),
            VideoVariant(url = "https://cdn.example/hi.mp4", width = 720, height = 1280, type = 102),
            VideoVariant(url = "https://cdn.example/mid.mp4", width = 640, height = 1136, type = 102),
        ),
    )

    @Test
    fun `max quality picks tallest rendition`() {
        assertEquals("https://cdn.example/hi.mp4", media.selectUrl(Quality.MAX))
    }

    @Test
    fun `low quality picks smallest rendition`() {
        assertEquals("https://cdn.example/lo.mp4", media.selectUrl(Quality.LOW))
    }

    @Test
    fun `medium quality picks middle rendition`() {
        assertEquals("https://cdn.example/mid.mp4", media.selectUrl(Quality.MEDIUM))
    }

    @Test
    fun `falls back to url when no variants known`() {
        val single = ResolvedMedia(url = "https://cdn.example/only.mp4", shortcode = "x")
        assertEquals("https://cdn.example/only.mp4", single.selectUrl(Quality.LOW))
        assertEquals("https://cdn.example/only.mp4", single.selectUrl(Quality.MAX))
    }

    @Test
    fun `ignores non progressive renditions like dash`() {
        val withDash = ResolvedMedia(
            url = "https://cdn.example/hi.mp4",
            shortcode = "y",
            variants = listOf(
                VideoVariant(url = "https://cdn.example/dash.mpd", width = 1080, height = 1920, type = 104),
                VideoVariant(url = "https://cdn.example/real.mp4", width = 720, height = 1280, type = 102),
            ),
        )
        assertEquals("https://cdn.example/real.mp4", withDash.selectUrl(Quality.MAX))
    }
}
