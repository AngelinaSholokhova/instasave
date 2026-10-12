package com.instasave.app.resolver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

data class HttpResult(val body: String, val code: Int, val finalUrl: String) {
    override fun toString(): String = "HttpResult(code=$code, chars=${body.length}, final=$finalUrl)"
}

class InstagramClient {

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        const val CORE_APP_ID = "936619743392459"
        const val BASE = "https://www.instagram.com/"
        const val API_BASE = "https://www.instagram.com/api/v1/"
        const val GRAPHQL_URL = "https://www.instagram.com/api/graphql"
        private const val DOC_ID = "27130156389949648"
        private const val CH_UA = "\"Not)A;Brand\";v=\"99\", \"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\""
        private const val REQUESTED_WITH = "XMLHttpRequest"
        private const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        val API_HEADERS = mapOf(
            "X-IG-App-ID" to CORE_APP_ID,
            "X-ASBD-ID" to "359341",
            "X-IG-WWW-Claim" to "0",
            "Origin" to "https://www.instagram.com",
        )

        /** Browser-like base headers required to avoid Instagram's anonymous soft-404/bot page. */
        private val BASE_HEADERS = mapOf(
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "identity",
            "Sec-CH-UA" to CH_UA,
            "Sec-CH-UA-Mobile" to "?0",
            "Sec-CH-UA-Platform" to "\"Windows\"",
            "Upgrade-Insecure-Requests" to "1",
        )

        /** Builds the GraphQL form body used for logged-out post lookup. */
        fun graphQlForm(lsd: String, pk: Long): Map<String, String> = linkedMapOf(
            "lsd" to lsd,
            "fb_api_caller_class" to "RelayModern",
            "fb_api_req_friendly_name" to "PolarisLoggedOutDesktopWWWPostRootContentQuery",
            "server_timestamps" to "true",
            "variables" to """{"media_id":"$pk"}""",
            "doc_id" to DOC_ID,
        )
    }

    private val cookies = mutableMapOf<String, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Synchronized
    fun cookie(name: String): String? = cookies[name]

    @Synchronized
    private fun saveCookies(headers: okhttp3.Headers) {
        for (i in 0 until headers.size) {
            if (!headers.name(i).equals("Set-Cookie", ignoreCase = true)) continue
            val value = headers.value(i)
            val part = value.substringBefore(';')
            val eq = part.indexOf('=')
            if (eq > 0) cookies[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
    }

    @Synchronized
    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    suspend fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult =
        request("GET", url, null, extraHeaders)

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResult = request("POST", url, form.toBody(), extraHeaders)

    private fun Map<String, String>.toBody(): RequestBody {
        val body = okhttp3.FormBody.Builder()
        forEach { (k, v) -> body.add(k, v) }
        return body.build()
    }

    private suspend fun request(
        method: String,
        url: String,
        body: RequestBody?,
        extraHeaders: Map<String, String>,
    ): HttpResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .method(method, body)
            .header("User-Agent", USER_AGENT)
        cookieHeader().takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

        val isGet = method.equals("GET", ignoreCase = true)
        val navHeaders = if (isGet) {
            mapOf(
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
            )
        } else {
            mapOf(
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
            )
        }

        (BASE_HEADERS + mapOf("Accept" to HTML_ACCEPT) + navHeaders + extraHeaders)
            .forEach { (k, v) -> if (v.isNotBlank()) builder.header(k, v) }

        val started = System.currentTimeMillis()
        client.newCall(builder.build()).execute().use { resp ->
            saveCookies(resp.headers)
            val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            val ms = System.currentTimeMillis() - started
            Log.i(
                "InstaSave",
                "http $method ${resp.request.url} -> ${resp.code} (${text.length} chars, ${ms}ms) final=${resp.request.url}",
            )
            HttpResult(text, resp.code, resp.request.url.toString())
        }
    }
}