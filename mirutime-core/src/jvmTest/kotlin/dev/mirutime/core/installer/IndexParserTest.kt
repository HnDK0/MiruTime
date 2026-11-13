package dev.mirutime.core.installer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexParserTest {

    private val rootIndex = """
        languages:
          en:
            name: "English"
            url: "https://example.com/en/index.yaml"
        sources:
          - id: "asurascans"
            name: "Asura Scans"
            version: "1.7.1"
            url: "https://example.com/en/asurascans.lua"
            sha256: "abc123"
          - id: "broken"
            name: "No URL"
        libraries:
          - id: "hls"
            url: "https://example.com/libs/hls.lua"
            sha256: "def456"
          - id: "nameless"
    """.trimIndent()

    @Test
    fun `parses sources and libraries sections`() {
        val index = parsePluginIndex(rootIndex)

        assertEquals(1, index.sources.size)
        val source = index.sources.single()
        assertEquals("asurascans", source.id)
        assertEquals("Asura Scans", source.name)
        assertEquals("1.7.1", source.version)
        assertEquals("https://example.com/en/asurascans.lua", source.url)
        assertEquals("abc123", source.sha256)

        // The url-less source and the id-only library are skipped.
        assertEquals(1, index.libraries.size)
        assertEquals("hls", index.libraries.single().id)
        assertNull(index.libraries.single().name)
    }

    @Test
    fun `root index with languages and libraries but no sources parses`() {
        val index = parsePluginIndex(
            """
            languages:
              en:
                name: "English"
                url: "https://example.com/en/index.yaml"
            libraries:
              - id: "urls"
                url: "https://example.com/libs/urls.lua"
                sha256: "def456"
            """.trimIndent(),
        )

        assertEquals(emptyList(), index.sources)
        assertEquals(listOf("en"), index.languages.map { it.id })
        assertEquals(listOf("urls"), index.libraries.map { it.id })
    }

    @Test
    fun `language index source without sha parses with null sha`() {
        val index = parsePluginIndex(
            """
            sources:
              - id: "en-source"
                url: "https://example.com/en/source.lua"
            """.trimIndent(),
        )

        assertNull(index.sources.single().sha256)
    }

    @Test
    fun `missing sections parse as empty lists`() {
        val index = parsePluginIndex("language: \"en\"\nname: \"English\"")
        assertEquals(emptyList(), index.sources)
        assertEquals(emptyList(), index.libraries)
        assertEquals(emptyList(), index.languages)
    }

    @Test
    fun `parses root languages mapping into entries keyed by code`() {
        val index = parsePluginIndex(rootIndex)

        // `en` maps to {name, url}; id is the mapping key.
        assertEquals(1, index.languages.size)
        val en = index.languages.single()
        assertEquals("en", en.id)
        assertEquals("English", en.name)
        assertEquals("https://example.com/en/index.yaml", en.url)
        assertNull(en.sha256)
    }

    @Test
    fun `skips malformed languages lines without failing the index`() {
        val index = parsePluginIndex(
            """
            languages:
              en:
                name: "English"
                url: "https://example.com/en/index.yaml"
              nourl:
                name: "Missing URL"
              notamap: "just-a-string"
            """.trimIndent(),
        )

        assertEquals(listOf("en"), index.languages.map { it.id })
    }

    @Test
    fun `parses api field as int or string and defaults to null when absent`() {
        val index = parsePluginIndex(
            """
            sources:
              - id: "intapi"
                url: "https://example.com/a.lua"
                sha256: "aaa"
                api: 2
              - id: "stringapi"
                url: "https://example.com/b.lua"
                sha256: "bbb"
                api: "3"
              - id: "noapi"
                url: "https://example.com/c.lua"
                sha256: "ccc"
            """.trimIndent(),
        )
        assertEquals(listOf(2, 3, null), index.sources.map { it.api })
    }

    @Test
    fun `parses content_type and language and defaults them to null`() {
        val index = parsePluginIndex(
            """
            sources:
              - id: "mangasite"
                url: "https://example.com/a.lua"
                content_type: "manga"
                language: "ja"
                icon: "https://example.com/icons/mangasite.png"
              - id: "plain"
                url: "https://example.com/b.lua"
            """.trimIndent(),
        )

        val manga = index.sources.first { it.id == "mangasite" }
        assertEquals("manga", manga.contentType)
        assertEquals("ja", manga.language)
        assertEquals("https://example.com/icons/mangasite.png", manga.icon)
        val plain = index.sources.first { it.id == "plain" }
        assertNull(plain.contentType)
        assertNull(plain.language)
        assertNull(plain.icon)
    }

    @Test
    fun `requires list is parsed and normalized`() {
        val index = parsePluginIndex(
            """
            sources:
              - id: "a"
                url: "https://example.com/a.lua"
                requires:
                  - "catalog"
                  - " urls "
                  - ""
                  - "catalog"
              - id: "b"
                url: "https://example.com/b.lua"
            """.trimIndent(),
        )

        assertEquals(listOf("catalog", "urls"), index.sources.first { it.id == "a" }.requires)
        assertTrue(index.sources.first { it.id == "b" }.requires.isEmpty())
    }

    @Test
    fun `non mapping root throws`() {
        assertFailsWith<IndexParseException> { parsePluginIndex("- a\n- b") }
    }

    @Test
    fun `section that is not a list throws`() {
        assertFailsWith<IndexParseException> { parsePluginIndex("sources: just-a-string") }
    }
}
