package com.instasave.app

import com.instasave.app.util.Shortcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcodeTest {

    @Test
    fun `roundtrip reels and posts`() {
        val codes = listOf(
            "Chunk8-jurw",
            "BkfuX9UB-eK",
            "Cop84x6u7CP",
            "aye83DjauH",
            "BQ0eAlwhDrw",
            "-Cmh1cukG2",
        )
        for (code in codes) {
            val pk = Shortcode.toPk(code)
            assertNotNull(pk)
            assertEquals(code, Shortcode.fromPk(pk!!))
        }
    }

    @Test
    fun `zero maps to first alphabet char`() {
        assertEquals("A", Shortcode.fromPk(0))
        assertEquals(0L, Shortcode.toPk("A"))
    }

    @Test
    fun `leading zeros collapse but values are consistent`() {
        // base-64 integer encoding: leading zero digits are dropped
        assertEquals(0L, Shortcode.toPk("AAAAAAAAAAA"))
        assertEquals(1L, Shortcode.toPk("B"))
        assertEquals(64L, Shortcode.toPk("BA"))
        assertEquals("BA", Shortcode.fromPk(64))
    }

    @Test
    fun `rejects invalid chars`() {
        assertNull(Shortcode.toPk("not/valid&"))
        assertNull(Shortcode.toPk(""))
    }

    @Test
    fun `returns non null for typical reels`() {
        assertNotNull(Shortcode.toPk("Cop84x6u7CP"))
    }
}