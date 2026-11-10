package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.ChapterDownloadException
import dev.mirutime.app.features.book.data.ChapterFailureKind
import dev.mirutime.app.features.book.data.FakeBookDataProvider
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.feature.reader.ReaderLoadFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReaderRepositoryFallbackTest {

    private fun fallback() = ReaderDocument(
        bookUrl = BOOK,
        chapterUrl = "$BOOK#chapter=1",
        chapterIndex = 1,
        title = "Fallback",
        blocks = listOf(
            ReaderBlock(
                id = BlockId("$BOOK#chapter=1", 0),
                kind = BlockKind.Paragraph,
                original = "Only translation",
            ),
        ),
        translationOnly = true,
    )

    @Test
    fun `a null source document falls back to the cached translation`() = runTest {
        val repo = DefaultReaderRepository(
            provider = FakeProvider { null },
            translationOnlyDocument = { _, _ -> fallback() },
        )

        val document = repo.document(BOOK, 1)

        assertEquals("Only translation", document?.blocks?.single()?.original)
        assertTrue(document?.translationOnly == true)
    }

    @Test
    fun `a typed source failure also falls back`() = runTest {
        val repo = DefaultReaderRepository(
            provider = FakeProvider {
                throw ChapterDownloadException(
                    kind = ChapterFailureKind.NETWORK,
                    retryable = true,
                    message = "offline",
                )
            },
            translationOnlyDocument = { _, _ -> fallback() },
        )

        assertEquals("Only translation", repo.document(BOOK, 1)?.blocks?.single()?.original)
    }

    @Test
    fun `a login wall keeps the typed failure instead of the cached translation`() = runTest {
        var fallbacks = 0
        val repo = DefaultReaderRepository(
            provider = FakeProvider {
                throw ChapterDownloadException(
                    kind = ChapterFailureKind.AUTH_REQUIRED,
                    retryable = false,
                    message = "login",
                    pluginError = PluginError.UserMessage(
                        title = "Login required",
                        message = "Session expired",
                        authUrl = "https://site.example/login",
                    ),
                )
            },
            translationOnlyDocument = { _, _ -> fallbacks++; fallback() },
        )

        try {
            repo.document(BOOK, 1)
            fail("a login wall must not be replaced by a translation")
        } catch (failure: ReaderLoadFailure) {
            assertEquals("https://site.example/login", failure.authUrl)
        }
        assertEquals(0, fallbacks)
    }

    @Test
    fun `an HTTP auth wall without an auth URL keeps the typed failure`() = runTest {
        var fallbacks = 0
        val repo = DefaultReaderRepository(
            provider = FakeProvider {
                throw ChapterDownloadException(
                    kind = ChapterFailureKind.AUTH_REQUIRED,
                    retryable = false,
                    message = "HTTP 401",
                    pluginError = PluginError.Network("401"),
                )
            },
            translationOnlyDocument = { _, _ -> fallbacks++; fallback() },
        )

        try {
            repo.document(BOOK, 1)
            fail("a 401 wall must not be replaced by a translation")
        } catch (failure: ReaderLoadFailure) {
            assertTrue(failure.message, "op=content" in failure.message)
        }
        assertEquals(0, fallbacks)
    }

    @Test
    fun `a cloudflare challenge keeps the typed failure`() = runTest {
        var fallbacks = 0
        val repo = DefaultReaderRepository(
            provider = FakeProvider {
                throw ChapterDownloadException(
                    kind = ChapterFailureKind.CLOUDFLARE,
                    retryable = false,
                    message = "Cloudflare verification required",
                )
            },
            translationOnlyDocument = { _, _ -> fallbacks++; fallback() },
        )

        try {
            repo.document(BOOK, 1)
            fail("a clearance wall must not be replaced by a translation")
        } catch (failure: ReaderLoadFailure) {
            assertTrue(failure.message, "Cloudflare" in failure.message)
        }
        assertEquals(0, fallbacks)
    }

    @Test
    fun `bulk translation never receives a synthetic document`() = runTest {
        var fallbacks = 0
        val repo = DefaultReaderRepository(
            provider = FakeProvider { null },
            translationOnlyDocument = { _, _ -> fallbacks++; fallback() },
        )

        assertEquals(null, repo.documentForTranslation(BOOK, 1))
        assertEquals(0, fallbacks)
    }

    @Test
    fun `a not-found source still serves the cached translation`() = runTest {
        val repo = DefaultReaderRepository(
            provider = FakeProvider {
                throw ChapterDownloadException(
                    kind = ChapterFailureKind.NOT_FOUND,
                    retryable = false,
                    message = "gone",
                )
            },
            translationOnlyDocument = { _, _ -> fallback() },
        )

        assertEquals("Only translation", repo.document(BOOK, 1)?.blocks?.single()?.original)
    }

    @Test
    fun `a source failure still serves the cached translation`() = runTest {
        val repo = DefaultReaderRepository(
            provider = FakeProvider {
                throw ChapterDownloadException(
                    kind = ChapterFailureKind.SOURCE,
                    retryable = false,
                    message = "parse failed",
                )
            },
            translationOnlyDocument = { _, _ -> fallback() },
        )

        assertEquals("Only translation", repo.document(BOOK, 1)?.blocks?.single()?.original)
    }

    @Test
    fun `a rules reload can keep a cached-only document`() = runTest {
        val repo = DefaultReaderRepository(
            provider = FakeBookDataProvider { _, _ -> null },
            translationOnlyDocument = { _, _ -> fallback() },
        )

        assertEquals(null, repo.documentForRulesReload(BOOK, 1))
        assertEquals(
            "Only translation",
            repo.documentForRulesReload(BOOK, 1, allowTranslationOnly = true)
                ?.blocks?.single()?.original,
        )
    }

    @Test
    fun `document records history and the other entry points do not`() = runTest {
        var touches = 0
        val repo = DefaultReaderRepository(
            provider = FakeProvider { null },
            translationOnlyDocument = { _, _ -> fallback() },
            onDocumentLoaded = { _, _ -> touches++ },
        )

        repo.document(BOOK, 1)
        assertEquals(1, touches)

        repo.documentForTranslation(BOOK, 1)
        repo.documentForPrefetch(BOOK, 1)
        repo.documentForRulesReload(BOOK, 1)
        assertEquals(1, touches)
    }

    @Test
    fun `a successfully loaded original is returned unchanged`() = runTest {
        val repo = DefaultReaderRepository(
            provider = FakeProvider {
                ChapterContent(
                    bookUrl = BOOK,
                    chapterIndex = 1,
                    title = "Original",
                    html = "<p>Original text</p>",
                    chapterUrl = "$BOOK#chapter=1",
                )
            },
            translationOnlyDocument = { _, _ -> error("fallback must not run") },
        )

        val document = repo.document(BOOK, 1)

        assertEquals("Original", document?.title)
        assertEquals("Original text", document?.blocks?.single()?.original)
        assertFalse(document?.translationOnly == true)
    }

    private class FakeProvider(
        private val content: suspend () -> ChapterContent?,
    ) : BookDataProvider by FakeBookDataProvider({ _, _ -> content() })

    private companion object {
        const val BOOK = "https://fallback.test/book"
    }
}
