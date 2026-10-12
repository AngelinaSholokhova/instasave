package com.instasave.app

import com.instasave.app.resolver.Extractors
import com.instasave.app.resolver.ResolveException
import com.instasave.app.resolver.ResolvedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorsTest {

    @Test
    fun `graphQl single video picks highest quality and username`() {
        val json = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "code": "aye83DjauH",
                    "user": { "username": "naomipq" },
                    "video_versions": [
                      { "type": 1, "width": 480, "height": 854, "url": "https://video.example/a_480.mp4" },
                      { "type": 0, "width": 720, "height": 1280, "url": "https://video.example/a_720.mp4" }
                    ],
                    "image_versions2": { "candidates": [
                      { "width": 720, "height": 1280, "url": "https://img.example/a.jpg" }
                    ]},
                    "caption": { "text": "hello world" },
                    "video_duration": 8.7
                  }
                }
              },
              "status": "ok"
            }
        """.trimIndent()
        val media = Extractors.extractFromGraphQl(json)
        assertEquals(1, media.size)
        assertEquals("https://video.example/a_720.mp4", media[0].url)
        assertEquals("naomipq", media[0].username)
        assertEquals("aye83DjauH", media[0].shortcode)
        assertEquals(8, media[0].durationSec)
        assertEquals("hello world", media[0].caption)
    }

    @Test
    fun `graphQl carousel extracts only videos`() {
        val json = """
            {
              "data": { "xig_polaris_media": {
                "if_not_gated_logged_out": {
                  "code": "BQ0eAlwhDrw",
                  "user": { "username": "insta" },
                  "carousel_media": [
                    { "code": "BQ0dSaohpPW",
                      "video_versions": [
                        { "width": 640, "height": 1136, "url": "https://cdn.example/1.mp4" }
                      ] },
                    { "code": "BQ0dTpOhuHT",
                      "image_versions2": { "candidates": [
                        { "width": 640, "height": 640, "url": "https://cdn.example/2.jpg" }
                      ] },
                      "media_type": 1 },
                    { "code": "BQ0dT7RBFeF",
                      "video_versions": [
                        { "width": 640, "height": 1136, "url": "https://cdn.example/3.mp4" }
                      ] }
                  ]
                }
              }}
            }
        """.trimIndent()
        val media = Extractors.extractFromGraphQl(json)
        assertEquals(2, media.size)
        assertEquals("https://cdn.example/1.mp4", media[0].url)
        assertEquals("https://cdn.example/3.mp4", media[1].url)
        assertEquals("insta", media[0].username)
    }

    @Test(expected = ResolveException::class)
    fun `graphQl gated throws login required`() {
        val json = """
            { "data": { "xig_polaris_media": {
              "if_gated_logged_out": { "title": "Login required" }
            } } }
        """.trimIndent()
        Extractors.extractFromGraphQl(json)
    }

    @Test
    fun `og video extraction`() {
        val html = """
            <html><head>
              <meta property="og:video" content="https://cdn.example/og.mp4?se=1&amp;t=2"/>
            </head></html>
        """.trimIndent()
        val urls = Extractors.extractOgVideo(html)
        assertEquals(listOf("https://cdn.example/og.mp4?se=1&t=2"), urls)
    }

    @Test
    fun `video_url extraction escapes backslashes and unicode`() {
        val html = """
            <script type="text/javascript">
            window.__data = {"video_url":"https:\/\/scontent.cdn.example\/v\/x.mp4?cache=123\u0026se=true\u0023frag"};
            </script>
        """.trimIndent()
        val urls = Extractors.extractVideoUrls(html)
        assertEquals(1, urls.size)
        assertEquals("https://scontent.cdn.example/v/x.mp4?cache=123&se=true#frag", urls[0])
    }

    @Test
    fun `data-sjs relay stream extraction`() {
        val html = """
            <html>
              <script type="application/json" data-sjs>
              {"require":[["RelayPrefetchedStreamCache",{}],[{"__bbox":{"result":{"data":{"xig_polaris_media":{
                "if_not_gated_logged_out": {
                  "code": "sjsShor1",
                  "user": { "username": "sjsuser" },
                  "video_versions": [ { "width": 640, "height": 1136, "url": "https://cdn.example/sjs.mp4" } ]
                }
              }}}}}]]}
              </script>
            </html>
        """.trimIndent()
        val media = Extractors.extractDataSjs(html)
        assertEquals(1, media.size)
        assertEquals("https://cdn.example/sjs.mp4", media[0].url)
        assertEquals("sjsuser", media[0].username)
    }

    @Test
    fun `escaped quotes video_url extraction`() {
        val html = """<script>{"video_url\":\"https:\/\/cdn.example\/e.mp4\"}</script>"""
        val urls = Extractors.extractVideoUrls(html)
        assertTrue(urls.contains("https://cdn.example/e.mp4"))
    }

    @Test
    fun `data-sjs with null and wrong-typed fields does not crash`() {
        val html = """
            <script type="application/json" data-sjs>
            {"require":[["RelayPrefetchedStreamCache",{},[{"__bbox":{
              "prefetchedQueries":[
                {"query":{"operationName":"xig_polaris_media"},
                 "response":{"data":{"xdt_shortcode_media":{
                   "code": "xyz123",
                   "user": null,
                   "carousel_media": null,
                   "video_versions": "oops-not-an-array",
                   "image_versions2": 42,
                   "caption": { "text": null },
                   "video_duration": null
                 }}}}
              ]
            }}]]]}
            </script>
        """.trimIndent()
        assertEquals(emptyList<ResolvedMedia>(), Extractors.extractDataSjs(html))
    }

    @Test
    fun `data-sjs new shape xdt_shortcode_media with video_versions`() {
        val html = """
            <script type="application/json" data-sjs>
            {"require":[["RelayPrefetchedStreamCache",{},[{"__bbox":{
              "prefetchedQueries":[
                {"query":{"operationName":"xig_polaris_media","text":"query"},
                 "response":{"status":"ok","data":{"xdt_shortcode_media":{
                   "code": "newShape1",
                   "user": { "username": "newuser" },
                   "video_versions": [
                     { "width": 720, "height": 1280, "url": "https://cdn.example/new.mp4" }
                   ],
                   "is_video": true
                 }}}}
              ]
            }}]]]}
            </script>
        """.trimIndent()
        val media = Extractors.extractDataSjs(html)
        assertEquals(1, media.size)
        assertEquals("https://cdn.example/new.mp4", media[0].url)
        assertEquals("newuser", media[0].username)
        assertEquals("newShape1", media[0].shortcode)
    }

    @Test
    fun `sharedData with malformed sidecar does not crash`() {
        val html = """
            <html><script type="text/javascript">window._sharedData = {
              "entry_data": { "PostPage": [ { "graphql": { "shortcode_media": {
                "shortcode": "ab12cd",
                "owner": { "username": "u1" },
                "edge_sidecar_to_children": "not-an-object"
              } } } ] }
            }; </script></html>
        """.trimIndent()
        assertEquals(emptyList<ResolvedMedia>(), Extractors.extractSharedData(html))
    }
}