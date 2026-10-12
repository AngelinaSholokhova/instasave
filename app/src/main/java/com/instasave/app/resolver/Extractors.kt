package com.instasave.app.resolver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Pure extraction helpers over Instagram HTML/JSON responses.
 * Kept dependency-free from Android so they can be unit tested on the JVM.
 */
object Extractors {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- JSON

    /** Parses a GraphQL response and extracts video media. Throws [ResolveException] if gated. */
    fun extractFromGraphQl(responseText: String): List<ResolvedMedia> {
        val root = json.parseToJsonElement(responseText)
        val holder = root.findObjectContainingKey("xig_polaris_media")
            ?.get("xig_polaris_media")
        val viaWrapper = try {
            extractFromMediaNode(holder)
        } catch (e: ResolveException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        if (viaWrapper.isNotEmpty()) return viaWrapper
        return runCatching { extractFromProduct(holder) }.getOrDefault(emptyList())
    }

private fun extractFromMediaNode(node: JsonElement?): List<ResolvedMedia> {
    val n = node.obj() ?: return emptyList()
    if (n["if_gated_logged_out"].obj() != null) {
        throw ResolveException(
            "Доступ к этому контенту закрыт. Такие посты можно скачивать только после входа в аккаунт.",
            ResolveKind.LOGIN_REQUIRED,
        )
    }
    return extractFromProduct(n["if_not_gated_logged_out"])
}

fun extractFromProduct(product: JsonElement?): List<ResolvedMedia> {
    val obj = product.obj() ?: return emptyList()
    val username = obj["user"].obj()?.get("username").str()
    val code = obj["code"].str()

    val carousel = obj["carousel_media"].arr()
    if (carousel != null && carousel.isNotEmpty()) {
        return carousel.mapNotNull { videoFromProduct(it.obj(), username, code) }
    }
    return listOfNotNull(videoFromProduct(obj, username, code))
}

private fun videoFromProduct(
    el: JsonObject?,
    fallbackUser: String?,
    fallbackCode: String?,
): ResolvedMedia? {
    val obj = el ?: return null
    val versions = obj["video_versions"].arr() ?: return null
    val variants = versions
        .mapNotNull { it.obj() }
        .mapNotNull { v ->
            val u = v["url"].str() ?: return@mapNotNull null
            VideoVariant(
                url = u,
                width = v["width"].int() ?: 0,
                height = v["height"].int() ?: 0,
                type = v["type"].int() ?: 0,
            )
        }
        .distinctBy { it.url }
    val best = variants
        .maxByOrNull { it.height * 10_000 + it.width }
        ?: return null
    val url = best.url
    val thumb = obj["image_versions2"].obj()
        ?.get("candidates").arr()
        ?.mapNotNull { it.obj() }
        ?.maxByOrNull { it["width"].int() ?: 0 }
        ?.get("url").str()
    val caption = obj["caption"].obj()?.get("text").str()
    val duration = obj["video_duration"].double()?.toInt()
    return ResolvedMedia(
        url = url,
        shortcode = obj["code"].str() ?: fallbackCode ?: "",
        username = obj["user"].obj()?.get("username").str() ?: fallbackUser,
        thumbnail = thumb,
        caption = caption,
        durationSec = duration,
        variants = variants,
    )
}

    // ---------------------------------------------------------------- HTML

    /** Extracts `og:video` meta content values. */
    fun extractOgVideo(html: String): List<String> {
        val out = linkedSetOf<String>()
        val re = Regex("""<meta[^>]+property=["']og:video["']\s+content=["']([^"']+)["']""")
        for (m in re.findAll(html)) {
            out += unescapeHtml(m.groupValues[1])
        }
        return out.toList()
    }

    /** Extracts videos embedded in `<script data-sjs>` JSON (RelayPrefetchedStreamCache). */
    fun extractDataSjs(html: String): List<ResolvedMedia> {
        val re = Regex("""<script[^>]*\sdata-sjs>([\s\S]*?)</script>""")
        for (m in re.findAll(html)) {
            val node = runCatching { json.parseToJsonElement(m.groupValues[1]) }.getOrNull() ?: continue
            val media = node.findObjectContainingKey("xig_polaris_media")?.get("xig_polaris_media")
            val parsed = runCatching { extractFromMediaNode(media) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
            val direct = runCatching { extractFromProduct(media) }.getOrDefault(emptyList())
            if (direct.isNotEmpty()) return direct
            val cacheNode = node.findObjectContainingKey("xdt_shortcode_media")
                ?.get("xdt_shortcode_media")
            if (cacheNode != null) {
                val fromCache = runCatching { extractFromProduct(cacheNode) }.getOrDefault(emptyList())
                if (fromCache.isNotEmpty()) return fromCache
            }
        }
        return emptyList()
    }

    /** Extracts `"video_url"` values from embedded JSON (handles escaped and raw quotes). */
    fun extractVideoUrls(html: String): List<String> {
        val normalized = html.replace("\\\"", "\"")
        val re = Regex("""\"video_url\"\s*:\s*"([^"]*)"""")
        val out = linkedSetOf<String>()
        for (m in re.findAll(normalized)) {
            val v = unescapeJson(m.groupValues[1]).trim()
            if (v.isBlank()) continue
            out += if (v.startsWith("//")) "https:$v" else v
        }
        return out.toList()
    }

    /** Extracts videos from `window._sharedData` JSON (legacy PostPage graphql). */
    fun extractSharedData(html: String): List<ResolvedMedia> {
        val re = Regex("""window\._sharedData\s*=\s*(\{[\s\S]*?\})\s*;\s*</script>""")
        val m = re.find(html) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(m.groupValues[1]) }.getOrNull() ?: return emptyList()
        val media = root.findObjectContainingKey("shortcode_media")?.get("shortcode_media").obj()
            ?: return emptyList()
        val username = media["owner"].obj()?.get("username").str()
        val code = media["shortcode"].str()
        val singleUrl = media["video_url"].str()
        val out = mutableListOf<ResolvedMedia>()
        if (singleUrl != null) {
            out += ResolvedMedia(url = singleUrl, shortcode = code ?: "", username = username)
        }
        val children = media["edge_sidecar_to_children"].obj()
            ?.get("edges").arr()
        if (children != null) {
            for (edge in children) {
                val node = edge.obj()?.get("node").obj() ?: continue
                val u = node["video_url"].str() ?: continue
                out += ResolvedMedia(
                    url = u,
                    shortcode = node["shortcode"].str() ?: code ?: "",
                    username = username,
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- utils

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> {
                        if (i + 5 < s.length) {
                            val cp = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (cp != null) {
                                sb.append(cp.toChar())
                                i += 6
                                continue
                            }
                        }
                        i += 2
                        continue
                    }
                    '/', '\\', '"' -> {
                        sb.append(n)
                        i += 2
                        continue
                    }
                    'n' -> {
                        sb.append('\n'); i += 2; continue
                    }
                    'r' -> {
                        sb.append('\r'); i += 2; continue
                    }
                    't' -> {
                        sb.append('\t'); i += 2; continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun unescapeHtml(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
    }
}

/** Breadth-first search for a JSON object containing [key]. */
internal fun JsonElement?.findObjectContainingKey(key: String): JsonObject? {
    this ?: return null
    val queue = ArrayDeque<JsonElement>()
    queue.addLast(this)
    val seen = HashSet<JsonObject>()
    while (queue.isNotEmpty()) {
        val el = queue.removeFirst()
        when (el) {
            is JsonObject -> {
                if (el.containsKey(key)) return el
                if (seen.add(el)) el.values.forEach { queue.addLast(it) }
            }
            is JsonArray -> el.forEach { queue.addLast(it) }
            else -> {}
        }
    }
    return null
}