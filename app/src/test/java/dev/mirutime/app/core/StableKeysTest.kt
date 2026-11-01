package dev.mirutime.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StableKeysTest {

    @Test
    fun `keys carry their feature prefix`() {
        assertEquals("library-row:https://a/1", libraryRowKey("https://a/1"))
        assertEquals("library:https://a/1", libraryListKey("https://a/1"))
        assertEquals("chapter:7", chapterRowKey(7))
        assertEquals("catalog:https://a/1", catalogBookKey("https://a/1"))
        assertEquals("gs-source:plugin.one", globalSourceKey("plugin.one"))
        assertEquals("gs-book:https://a/1", globalBookKey("https://a/1"))
        assertEquals("feed:b:1", feedItemKey("b:1"))
    }

    @Test
    fun `row and catalog keys stay unique for distinct identities`() {
        val urls = listOf("https://a/1", "https://a/2")
        assertEquals(urls.size, urls.map(::libraryRowKey).distinct().size)
        assertEquals(urls.size, urls.map(::libraryListKey).distinct().size)
        assertEquals(urls.size, urls.map(::catalogBookKey).distinct().size)
        assertEquals(urls.size, urls.map(::globalBookKey).distinct().size)
    }
}
