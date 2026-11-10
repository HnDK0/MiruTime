package dev.mirutime.app.features.translation.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TranslationConfigKeysTest {
    @Test
    fun `the specific route book key is tried before the raw URL`() {
        val keys = translationConfigKeys("https://x.test/book", "plugin.a")

        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
        assertEquals("https://x.test/book", keys[1])

        val found = keys.firstNotNullOfOrNull { key ->
            if (key == keys[0]) "config" else null
        }
        assertEquals("config", found)
    }

    @Test
    fun `a route key is not duplicated`() {
        val key = translationConfigKeys("https://x.test/book", "plugin.a")[0]
        assertEquals(listOf(key), translationConfigKeys(key, "plugin.a"))
    }
}
