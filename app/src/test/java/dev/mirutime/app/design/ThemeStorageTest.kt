package dev.mirutime.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStorageTest {

    @Test
    fun `stored name is the digest so identical headers do not collide`() {
        // Two different JPEGs share these first eight bytes (the old bug).
        val jpegA = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 1, 2, 3,
        )
        val jpegB = jpegA.copyOf().also { it[it.lastIndex] = 4 }

        assertNotEquals(bgFileName(bytesDigest(jpegA)), bgFileName(bytesDigest(jpegB)))
        assertEquals(bgFileName(bytesDigest(jpegA)), bgFileName(bytesDigest(jpegA)))
        assertTrue(bgFileName(bytesDigest(jpegA)).endsWith(".jpg"))

        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9,
        )
        assertNotEquals(bgFileName(bytesDigest(jpegA)), bgFileName(bytesDigest(png)))
    }

    @Test
    fun `normalizes pasted links`() {
        assertEquals("https://site/a.jpg", normalizeBackgroundUrl("https://site/a.jpg"))
        assertEquals("https://site/a.jpg", normalizeBackgroundUrl("  site/a.jpg \n"))
        assertEquals("https://site/a.jpg?x=1&y=2", normalizeBackgroundUrl("https://site/a.jpg?x=1&amp;y=2"))
        assertEquals("https://site/a.jpg", normalizeBackgroundUrl("<https://site/a.jpg>"))
        assertEquals("content://media/1", normalizeBackgroundUrl("content://media/1"))
        assertEquals("HTTP://SITE/A.JPG", normalizeBackgroundUrl("HTTP://SITE/A.JPG"))
    }

    @Test
    fun `rejects unreadable links`() {
        assertNull(normalizeBackgroundUrl(""))
        assertNull(normalizeBackgroundUrl("   "))
        assertNull(normalizeBackgroundUrl("data:image/png;base64,AAAA"))
        assertNull(normalizeBackgroundUrl("blob:https://site/x"))
        assertNull(normalizeBackgroundUrl("ftp://site/a.jpg"))
        assertNull(normalizeBackgroundUrl("file:///tmp/a.jpg"))
    }
}
