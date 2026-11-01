package dev.mirutime.imageloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageCacheConfigTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `disk cache lives under files image_cache`() {
        val filesDir = temp.newFolder("files")

        val dir = ImageCacheConfig.cacheDir(filesDir)

        assertEquals(File(filesDir, "image_cache").absolutePath, dir.absolutePath)
    }

    @Test
    fun `image disk cache keeps the shipped 24 MB bound`() {
        // Moved out of cacheDir, so the old installd-quota clamp no longer
        // applies; the bound stays because changing it is a product decision.
        assertEquals(24L * 1024 * 1024, ImageCacheConfig.DISK_CACHE_BYTES)
    }

    @Test
    fun `only 2xx responses are cacheable`() {
        assertTrue(ImageCacheConfig.isCacheableStatus(200))
        assertTrue(ImageCacheConfig.isCacheableStatus(206))
        assertFalse(ImageCacheConfig.isCacheableStatus(404))
        assertFalse(ImageCacheConfig.isCacheableStatus(503))
    }

    @Test
    fun `text and avif bodies are not cacheable`() {
        assertFalse(ImageCacheConfig.isCacheableContentType("text/html; charset=utf-8"))
        assertFalse(ImageCacheConfig.isCacheableContentType("TEXT/PLAIN"))
        assertFalse(ImageCacheConfig.isCacheableContentType("image/avif"))
    }

    @Test
    fun `real image content types are cacheable`() {
        assertTrue(ImageCacheConfig.isCacheableContentType("image/webp"))
        assertTrue(ImageCacheConfig.isCacheableContentType("image/jpeg; q=1"))
        assertTrue(ImageCacheConfig.isCacheableContentType(null))
    }
}
