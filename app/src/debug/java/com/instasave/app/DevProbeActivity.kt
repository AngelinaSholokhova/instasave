package com.instasave.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hidden debug activity: probes many anonymous Instagram endpoints and logs
 * which ones expose the video. Launched via adb, writes results to a file.
 */
class DevProbeActivity : ComponentActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val out = StringBuilder()
    private var code = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        code = intent.getStringExtra("code") ?: ""
        val mode = intent.getStringExtra("mode") ?: ""
        if (code.isBlank()) { finish(); return }
        Log.i("InstaSave", "probe: start code=$code mode=$mode")
        lifecycleScope.launch(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            if (mode == "resolve") resolveRun(code)
            else if (mode == "full") fullRun(code)
            else runProbes()
            out.append("\n=== done in ${(System.currentTimeMillis() - start) / 1000}s ===\n")
            val f = File(filesDir, "probe.txt")
            f.writeText(out.toString())
            Log.i("InstaSave", "probe: wrote ${f.absolutePath} (${out.length} chars)")
            finish()
        }
    }

    /** End-to-end autotest: resolve → download → MP4 check → gallery save. */
    private suspend fun fullRun(code: String) {
        val api = com.instasave.app.resolver.InstagramApi()
        val t0 = System.currentTimeMillis()
        try {
            val media = api.resolve(code)
            if (media.isEmpty()) {
                out.append("FULL FAIL: no media\n")
                return
            }
            val first = media.first()
            val thumbInfo = media.first().thumbnail?.let { turl ->
                try {
                    val r = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(turl).build()).execute()
                    val n = r.body?.bytes()?.size ?: 0
                    "http=${r.code} bytes=$n"
                } catch (e: Exception) { "ERR $e" }
            } ?: "none"
            val dest = File(cacheDir, "autotest_$code.mp4")
            val dl = com.instasave.app.download.Downloader()
            dl.download(first.url, dest)
            val size = dest.length()
            // readNBytes() needs API 33, so the header is read manually.
            val bytes = dest.inputStream().use { ins ->
                val buf = ByteArray(16)
                var off = 0
                while (off < buf.size) {
                    val n = ins.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                buf.copyOf(off)
            }
            val mp4 = bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
            val uri = com.instasave.app.gallery.GallerySaver(this).save(dest, "InstaSave_$code.mp4")
            val view: android.content.pm.ResolveInfo? = try {
                packageManager.resolveActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, "video/mp4"),
                    0,
                )
            } catch (e: Exception) { null }
            out.append(
                "FULL code=$code in=${(System.currentTimeMillis() - t0) / 1000}s\n" +
                    "  url=${first.url.take(90)}…\n" +
                    "  downloaded=${size} bytes mp4=$mp4\n" +
                    "  thumb=$thumbInfo\n" +
                    "  gallery uri=$uri handler=${view?.activityInfo?.packageName}\n",
            )
            Log.i("InstaSave", "probe: FULL ok size=$size mp4=$mp4 uri=$uri handler=${view?.activityInfo?.packageName}")
        } catch (e: Exception) {
            out.append("FULL FAIL in=${(System.currentTimeMillis() - t0) / 1000}s: ${e}\n")
            Log.i("InstaSave", "probe: FULL FAIL ${e}")
        }
    }

    /** End-to-end autotest of the real resolver chain against a live post. */
    private suspend fun resolveRun(code: String) {
        val api = com.instasave.app.resolver.InstagramApi()
        val t0 = System.currentTimeMillis()
        val result = try {
            val media = api.resolve(code)
            val urls = media.map { it.url }
            val u = urls.firstOrNull().orEmpty()
            val head = if (u.length > 120) u.take(120) + "…" else u
            out.append(
                "RESOLVE shorten=$code n=${media.size} ok=${media.isNotEmpty()} in=${(System.currentTimeMillis() - t0) / 1000}s\n" +
                    "    url: $head\n" +
                    "    meta(u=${media.firstOrNull()?.username}, thumb=${if (media.firstOrNull()?.thumbnail != null) "yes" else "NO"}, capt=${media.firstOrNull()?.caption?.take(40)})\n",
            )
            "RESOLVE ok n=${media.size}"
        } catch (e: Exception) {
            out.append("RESOLVE FAIL in=${(System.currentTimeMillis() - t0) / 1000}s: ${e}\n")
            "RESOLVE FAIL: $e"
        }
        Log.i("InstaSave", "probe: $result")
        try {
            val dir = File(filesDir, "debug").apply { mkdirs() }
            File(dir, "resolve.txt").writeText(
                "code=$code\n$result\n${System.currentTimeMillis()}\n",
            )
        } catch (_: Exception) {
        }
    }

    private suspend fun runProbes() {
        val uas = listOf(
            "C125Win" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "C129Win" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
            "iPhSaf" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            "AndWeb" to "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "ytdlpV1" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        )

        val pathVariants = listOf(
            "p" to { p: String -> "https://www.instagram.com/p/$p/" },
            "p?hl" to { p: String -> "https://www.instagram.com/p/$p/?hl=en" },
            "p?a1" to { p: String -> "https://www.instagram.com/p/$p/?__a=1&__d=dis" },
            "reel" to { p: String -> "https://www.instagram.com/reel/$p/" },
            "embed" to { p: String -> "https://www.instagram.com/p/$p/embed/" },
            "m.p" to { p: String -> "https://m.instagram.com/p/$p/" },
            "p?utm" to { p: String -> "https://www.instagram.com/p/$p/?utm_source=ig_web_button_share_sheet" },
        )

        // 1x path probe per UA for a few, then full UA sweep on 'p'
        for ((name, build) in pathVariants) {
            probe("$name|${uas[0].first}", build(code), uas[0].second, desktopHeaders())
        }
        // mobile UA against p and reel
        probe("p|iPhSaf", pathVariants[0].second(code), uas[2].second, mobileHeaders())
        probe("reel|iPhSaf", pathVariants[3].second(code), uas[2].second, mobileHeaders())
        probe("p?utm|AndWeb", pathVariants[6].second(code), uas[3].second, desktopHeaders())

        // graphql attempts with fresh homepage session each time
        for (ua in listOf(uas[0], uas[1])) {
            graphQlAttempt(ua.first, ua.second, desktopHeaders())
        }
    }

    private suspend fun graphQlAttempt(name: String, ua: String, baseHeaders: Map<String, String>) {
        try {
            val home = exec("GET", "https://www.instagram.com/", ua, baseHeaders, null)
            val lsd = Regex("""\["LSD",\[\],\{"token":"([^"]+)"\}\]""").find(home.body)?.groupValues?.get(1)
                ?: Regex("""id="__eqmc"[^>]*>([\s\S]*?)</script>""").find(home.body)
                    ?.groupValues?.get(1)
                    ?.let { runCatching { org.json.JSONObject(it).optString("l") }.getOrNull()?.takeIf { it.isNotBlank() } }

            val headers = buildMap {
                putAll(baseHeaders)
                put("X-CSRFToken", "")
                put("X-FB-LSD", lsd ?: "")
                put("X-Requested-With", "XMLHttpRequest")
                put("X-FB-Friendly-Name", "PolarisLoggedOutDesktopWWWPostRootContentQuery")
                put("Referer", "https://www.instagram.com/p/$code/")
                put("X-IG-App-ID", "936619743392459")
                put("Origin", "https://www.instagram.com")
                put("sec-fetch-site", "same-origin")
                put("sec-fetch-mode", "cors")
                put("sec-fetch-dest", "empty")
            }
            val pk = com.instasave.app.util.Shortcode.toPk(code) ?: 0L
            val form = buildString {
                append("lsd=", urlEncode(lsd ?: ""), "&")
                append("fb_api_caller_class=RelayModern&")
                append("fb_api_req_friendly_name=PolarisLoggedOutDesktopWWWPostRootContentQuery&")
                append("server_timestamps=true&")
                append("variables=", urlEncode("{\"media_id\":\"$pk\"}"), "&")
                append("doc_id=27130156389949648")
            }
            val res = exec("POST", "https://www.instagram.com/api/graphql", ua, headers, form)
            val json = res.body.trimStart().startsWith("{") || res.body.trimStart().startsWith("[")
            out.append("GQL|$name status=${res.code} len=${res.body.length} json=$json lsd=${lsd?.take(8)} " +
                "msg=${if (json) markerSummary(res.body) else res.body.take(120).replace('\n', ' ')}\n")
        } catch (e: Exception) {
            out.append("GQL|$name ERR ${e}\n")
        }
    }

    private suspend fun probe(name: String, url: String, ua: String, headers: Map<String, String>) {
        try {
            val res = exec("GET", url, ua, headers, null)
            val m = markerSummary(res.body)
            out.append("$name status=${res.code} len=${res.body.length} ${m}\n")
            Log.i("InstaSave", "probe: $name -> ${res.code} ${res.body.length} ${m}")
        } catch (e: Exception) {
            Log.i("InstaSave", "probe: $name ERR ${e}")
            out.append("$name ERR ${e}\n")
        }
    }

    private fun markerSummary(body: String): String {
        val flags = mapOf(
            "og:video" to body.contains("og:video"),
            "video_url" to body.contains("video_url"),
            "video_versions" to body.contains("video_versions"),
            "video_play_count" to body.contains("video_play_count"),
            "shortcode_media" to body.contains("shortcode_media"),
            "xig_polaris" to body.contains("xig_polaris_media"),
            "xdt_mediainfo" to body.contains("xdt__api__v1__media__shortcode__web_info"),
            "PolarisError" to body.contains("PolarisErrorRoute"),
            "mp4" to body.contains(".mp4"),
            "scontent" to body.contains("scontent"),
        )
        return flags.entries.joinToString(" ") { (k, v) -> if (v) k else "" }.trim()
            .ifEmpty { "NONE" }
    }

    private suspend fun exec(
        method: String,
        url: String,
        ua: String,
        headers: Map<String, String>,
        formBody: String?,
    ): Result {
        return withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url).header("User-Agent", ua)
            headers.forEach { (k, v) -> if (v.isNotEmpty()) b.header(k, v) }
            val body = formBody?.toRequestBody(CONTENT_TYPE)
            b.method(method, body)
            val started = System.currentTimeMillis()
            client.newCall(b.build()).execute().use { resp ->
                val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                Result(text, resp.code, resp.request.url.toString(), System.currentTimeMillis() - started)
            }
        }
    }

    private fun desktopHeaders() = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept-Encoding" to "identity",
        "Sec-CH-UA" to "\"Not)A;Brand\";v=\"99\", \"Google Chrome\";v=\"125\"",
        "Sec-CH-UA-Mobile" to "?0",
        "Sec-CH-UA-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1",
    )

    private fun mobileHeaders() = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept-Encoding" to "identity",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1",
    )

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    data class Result(val body: String, val code: Int, val finalUrl: String, val ms: Long)

    companion object {
        private val CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
    }
}