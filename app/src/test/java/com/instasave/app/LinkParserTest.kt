package com.instasave.app

import com.instasave.app.util.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkParserTest {

    @Test
    fun `extractShortcode reels`() {
        assertEquals("CxAbC123xyz", LinkParser.extractShortcode("https://www.instagram.com/reel/CxAbC123xyz/"))
    }

    @Test
    fun `extractShortcode post`() {
        assertEquals("CxAbC123xyz", LinkParser.extractShortcode("https://instagram.com/p/CxAbC123xyz?igshid=xyz"))
    }

    @Test
    fun `extractShortcode with username segment`() {
        assertEquals("CxAbC123xyz", LinkParser.extractShortcode("https://www.instagram.com/user.name/reel/CxAbC123xyz/"))
    }

    @Test
    fun `extractShortcode tv`() {
        assertEquals("BkfuX9UB-eK", LinkParser.extractShortcode("https://www.instagram.com/tv/BkfuX9UB-eK/"))
    }

    @Test
    fun `extractShortcode reels plural`() {
        assertEquals("Cop84x6u7CP", LinkParser.extractShortcode("https://www.instagram.com/reels/Cop84x6u7CP/"))
    }

    @Test
    fun `extractShortcode bare url in text`() {
        assertEquals("short", LinkParser.extractShortcode("hey check this: instagram.com/p/short"))
    }

    @Test
    fun `extractShortcode null on non instagram`() {
        assertNull(LinkParser.extractShortcode("https://youtube.com/watch?v=abc"))
    }

    @Test
    fun `extractLink returns first instagram url`() {
        assertEquals(
            "https://www.instagram.com/reel/CxAbC123xyz/",
            LinkParser.extractLink("Посмотри https://www.instagram.com/reel/CxAbC123xyz/ !"),
        )
    }
}