package com.instasave.app.util

object LinkParser {

    private val URL_RE = Regex("""(?:https?://)?(?:www\.)?instagram\.com\S*""")
    private val SHORTCODE_RE =
        Regex("""(?:https?://)?(?:www\.)?instagram\.com/(?:[^/\s?#]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,})""")

    /** Returns the first instagram.com URL found in the text, or null. */
    fun extractLink(text: String?): String? {
        text ?: return null
        if ("instagram.com" !in text) return null
        val m = URL_RE.find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ']', '»', '“')
    }

    /** Returns the shortcode of the first instagram post/reel URL, or null. */
    fun extractShortcode(text: String?): String? {
        text ?: return null
        val m = SHORTCODE_RE.find(text) ?: return null
        return m.groupValues[1]
    }
}