package com.instasave.app.resolver

import android.util.Log
import com.instasave.app.App
import com.instasave.app.util.Shortcode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

class InstagramApi(private val client: InstagramClient = InstagramClient()) {

    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "InstaSave"

    /**
     * Tries several strategies and returns all videos in the post.
     * Order: webpage HTML (no session needed) → embed page → GraphQL API.
     */
    suspend fun resolve(shortcode: String): List<ResolvedMedia> {
        val errors = mutableListOf<ResolveException>()

        try {
            val media = pageStrategy(shortcode)
            if (media.isNotEmpty()) {
                Log.i(tag, "resolve: PAGE OK, ${media.size} video(s) for $shortcode")
                return media
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResolveException) {
            Log.w(tag, "resolve: page failed: ${e.message}")
            errors += e
        } catch (e: IOException) {
            Log.w(tag, "resolve: page network error: ${e.message}")
            errors += networkError(e)
        } catch (e: Throwable) {
            Log.w(tag, "resolve: page unexpected: $e")
            errors += ResolveException("Формат страницы Instagram не распознан.", ResolveKind.PARSE, e)
        }

        try {
            val media = embedStrategy(shortcode)
            if (media.isNotEmpty()) {
                Log.i(tag, "resolve: EMBED OK, ${media.size} video(s) for $shortcode")
                return media
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResolveException) {
            Log.w(tag, "resolve: embed failed: ${e.message}")
            errors += e
        } catch (e: IOException) {
            Log.w(tag, "resolve: embed network error: ${e.message}")
            errors += networkError(e)
        } catch (e: Throwable) {
            Log.w(tag, "resolve: embed unexpected: $e")
            errors += ResolveException("Формат страницы Instagram не распознан.", ResolveKind.PARSE, e)
        }

        try {
            val media = apiStrategy(shortcode)
            if (media.isNotEmpty()) {
                Log.i(tag, "resolve: API OK, ${media.size} video(s) for $shortcode")
                return media
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResolveException) {
            Log.w(tag, "resolve: api failed: ${e.message}")
            errors += e
        } catch (e: IOException) {
            Log.w(tag, "resolve: api network error: ${e.message}")
            errors += networkError(e)
        } catch (e: Throwable) {
            Log.w(tag, "resolve: api unexpected: $e")
            errors += ResolveException("Ответ Instagram имеет неожиданный формат.", ResolveKind.PARSE, e)
        }

        throw pickBestError(errors, shortcode)
    }

    // ------------------------------------------------------------- webpage

    private suspend fun pageStrategy(shortcode: String): List<ResolvedMedia> {
        val url = "https://www.instagram.com/p/$shortcode/"
        val res = client.get(
            url,
            mapOf("Accept" to "text/html,application/xhtml+xml", "Referer" to "https://www.instagram.com/"),
        )
        Log.i(tag, "page: $url -> $res")
        dump("page_latest.html", res.body)
        if (res.code == 404) {
            throw ResolveException("Пост не найден. Он мог быть удалён.", ResolveKind.NOT_FOUND)
        }
        if (res.finalUrl.contains("/accounts/login") || res.finalUrl == InstagramClient.BASE.trimEnd('/')) {
            throw ResolveException(
                "Instagram перенаправил на страницу входа — превышен лимит анонимного доступа. " +
                    "Подождите несколько минут или включите VPN.",
                ResolveKind.RATE_LIMIT,
            )
        }

        val og = Extractors.extractOgVideo(res.body)
        if (og.isNotEmpty()) return og.map { ResolvedMedia(url = it, shortcode = shortcode) }

        val sjs = Extractors.extractDataSjs(res.body)
        if (sjs.isNotEmpty()) return sjs

        val shared = Extractors.extractSharedData(res.body)
        if (shared.isNotEmpty()) return shared

        val urls = Extractors.extractVideoUrls(res.body)
        if (urls.isNotEmpty()) return urls.map { ResolvedMedia(url = it, shortcode = shortcode) }

        Log.i(tag, "page: no video found, body ${res.body.length} chars, first 200: ${res.body.take(200)}")
        dump("page.html", res.body)
        return emptyList()
    }

    private suspend fun embedStrategy(shortcode: String): List<ResolvedMedia> {
        val url = "https://www.instagram.com/p/$shortcode/embed/"
        val res = client.get(url, mapOf("Accept" to "text/html,application/xhtml+xml"))
        Log.i(tag, "embed: $url -> $res")
        if (res.code == 404) {
            throw ResolveException("Пост не найден. Он мог быть удалён.", ResolveKind.NOT_FOUND)
        }
        val urls = (Extractors.extractVideoUrls(res.body) + Extractors.extractOgVideo(res.body)).distinct()
        if (urls.isEmpty()) dump("embed.html", res.body)
        return urls.map { ResolvedMedia(url = it, shortcode = shortcode) }
    }

    // ------------------------------------------------------------------ API

    private suspend fun apiStrategy(shortcode: String): List<ResolvedMedia> {
        val pk = Shortcode.toPk(shortcode)
            ?: throw ResolveException("Некорректная ссылка Instagram.", ResolveKind.PARSE)

        val lsd = fetchLsd()
            ?: throw ResolveException("Не удалось начать сессию с Instagram.", ResolveKind.PARSE)
        checkRuling(pk, shortcode)
        val csrf = client.cookie("csrftoken")

        val headers = mapOf(
            "X-CSRFToken" to (csrf ?: ""),
            "X-FB-LSD" to lsd,
            "X-Requested-With" to "XMLHttpRequest",
            "X-FB-Friendly-Name" to "PolarisLoggedOutDesktopWWWPostRootContentQuery",
            "Referer" to "https://www.instagram.com/p/$shortcode/",
        ) + InstagramClient.API_HEADERS

        val res = client.postForm(InstagramClient.GRAPHQL_URL, InstagramClient.graphQlForm(lsd, pk), headers)
        Log.i(tag, "api: graphql -> $res")
        if (res.code == 403 || res.code == 429) {
            throw ResolveException(
                "Instagram блокирует автоматические запросы. Подождите несколько минут или включите VPN.",
                ResolveKind.RATE_LIMIT,
            )
        }
        if (res.code == 404) {
            throw ResolveException("Пост не найден. Он мог быть удалён.", ResolveKind.NOT_FOUND)
        }
        if (res.code !in 200..299) {
            throw ResolveException("Instagram вернул ошибку HTTP ${res.code}.", ResolveKind.PARSE)
        }

        if (!res.body.trimStart().startsWith("{") && !res.body.trimStart().startsWith("[")) {
            dump("graphql.html", res.body)
            throw ResolveException("Ответ Instagram имеет неожиданный формат.", ResolveKind.PARSE)
        }

        val media = try {
            Extractors.extractFromGraphQl(res.body)
        } catch (e: ResolveException) {
            throw e
        } catch (e: Exception) {
            throw ResolveException("Ответ Instagram имеет неожиданный формат.", ResolveKind.PARSE, e)
        }
        if (media.isNotEmpty()) return media

        val node = runCatching { json.parseToJsonElement(res.body) as? JsonObject }
            .getOrNull()
            ?.findObjectContainingKey("xig_polaris_media")
            ?.get("xig_polaris_media")
        if (node is JsonObject && node["if_gated_logged_out"] is JsonObject) {
            throw ResolveException(
                "Доступ к этому контенту закрыт. Такие посты можно скачивать только после входа в аккаунт.",
                ResolveKind.LOGIN_REQUIRED,
            )
        }
        throw ResolveException("В этом посте не найдено видео.", ResolveKind.NOT_VIDEO)
    }

    private suspend fun fetchLsd(): String? {
        val res = client.get(
            InstagramClient.BASE,
            mapOf("Accept" to "text/html,application/xhtml+xml"),
        )
        Log.i(tag, "home: -> $res")
        val raw = Regex("""\["LSD",\[\],\{"token":"([^"]+)"\}\]""").find(res.body)?.groupValues?.get(1)
        val eqmc = Regex("""<script[^>]*\bid="__eqmc"[^>]*>([\s\S]*?)</script>""")
            .find(res.body)
            ?.groupValues?.get(1)
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?.get("l")?.jsonPrimitive?.contentOrNull
        val token = raw ?: eqmc
        Log.i(tag, "home: lsd=${token?.take(10)}... (raw=${raw != null}, eqmc=${eqmc != null})")
        return token
    }

    private suspend fun checkRuling(pk: Long, shortcode: String) {
        val url = InstagramClient.API_BASE + "web/get_ruling_for_content/?content_type=MEDIA&target_id=$pk"
        val res = client.get(url, InstagramClient.API_HEADERS + mapOf("Referer" to "https://www.instagram.com/p/$shortcode/"))
        Log.i(tag, "api: ruling -> $res")
        val obj = runCatching { json.parseToJsonElement(res.body) as? JsonObject }.getOrNull()
        if (obj == null || obj["status"]?.jsonPrimitive?.contentOrNull == "ok") return
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        throw classifyError(title, description)
    }

    // ----------------------------------------------------------------- utils

    private fun dump(name: String, text: String) {
        try {
            val dir = File(App.instance.filesDir, "debug").apply { mkdirs() }
            File(dir, name).writeText(text)
            Log.i(tag, "dumped files/$name (${text.length} chars)")
        } catch (e: Exception) {
            Log.w(tag, "dump failed: $e")
        }
    }

    private fun networkError(e: IOException): ResolveException =
        ResolveException(
            "Не удалось соединиться с Instagram. Проверьте VPN и интернет. ($e)",
            ResolveKind.NETWORK,
            e,
        )

    private fun classifyError(title: String?, description: String?): ResolveException {
        val text = listOfNotNull(title, description).joinToString(" ").lowercase()
        return when {
            text.contains("wait a few minutes") || text.contains("rate limit") ||
                text.contains("too many requests") ->
                ResolveException(
                    "Слишком много запросов к Instagram. Подождите несколько минут и повторите.",
                    ResolveKind.RATE_LIMIT,
                )
            text.contains("login") || text.contains("restricted video") ->
                ResolveException(
                    "Доступ к этому контенту закрыт. Такие посты можно скачивать только после входа в аккаунт.",
                    ResolveKind.LOGIN_REQUIRED,
                )
            text.contains("private") ->
                ResolveException("Пост или аккаунт приватный.", ResolveKind.PRIVATE)
            else -> ResolveException(
                description?.takeIf { it.isNotBlank() }
                    ?: title?.takeIf { it.isNotBlank() }
                    ?: "Instagram не открыл доступ к этому посту.",
                ResolveKind.PARSE,
            )
        }
    }

    private fun pickBestError(errors: List<ResolveException>, shortcode: String): ResolveException {
        if (errors.isEmpty()) {
            return ResolveException("Не удалось получить видео из этого поста.", ResolveKind.PARSE)
        }
        val priority = mapOf(
            ResolveKind.NETWORK to 0,
            ResolveKind.RATE_LIMIT to 1,
            ResolveKind.LOGIN_REQUIRED to 2,
            ResolveKind.PRIVATE to 3,
            ResolveKind.NOT_FOUND to 4,
            ResolveKind.NOT_VIDEO to 5,
            ResolveKind.PARSE to 6,
        )
        return errors.minByOrNull { priority[it.kind] ?: 9 } ?: errors.first()
    }
}