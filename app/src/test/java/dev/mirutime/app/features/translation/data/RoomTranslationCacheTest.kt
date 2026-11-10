package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.translation.domain.ChapterTranslationResult
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.libraryBookKey
import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationRevision
import dev.mirutime.translator.TranslationUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTranslationCacheTest {

    @Test
    fun `the route key resolves the book when two installs share the url`() = runTest {
        val dao = twoInstalls()
        val cache = RoomTranslationCache(LibraryRepository(dao.proxy))

        cache.save(request(bookUrl = libraryBookKey(INSTALL_A, SHARED_URL)), result())

        assertEquals(1, dao.rows.size)
        assertEquals(1L, dao.rows.single().chapterId)
    }

    @Test
    fun `an ambiguous raw url saves nothing`() = runTest {
        val dao = twoInstalls()
        val cache = RoomTranslationCache(LibraryRepository(dao.proxy))

        cache.save(request(bookUrl = SHARED_URL), result())

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `the saved row keeps the provider of the request`() = runTest {
        val dao = twoInstalls()
        val cache = RoomTranslationCache(LibraryRepository(dao.proxy))

        cache.save(
            request(bookUrl = libraryBookKey(INSTALL_A, SHARED_URL)),
            result(provider = ProviderId.OPENAI_COMPATIBLE),
        )

        assertEquals(ProviderId.GEMINI.name, dao.rows.single().provider)
    }

    @Test
    fun `load same config ignores the source revision but not the prompt`() = runTest {
        val dao = twoInstalls()
        val cache = RoomTranslationCache(LibraryRepository(dao.proxy))
        val request = request(bookUrl = libraryBookKey(INSTALL_A, SHARED_URL))
        cache.save(request, result())

        // The same prompt and another revision still match.
        val otherRevision = request.copy(revision = request.revision.copy(sourceHash = "other"))
        assertEquals(mapOf(1 to "stored"), cache.loadSameConfig(otherRevision))

        // Another prompt is a different translation.
        val otherPrompt = request.copy(prompt = prompt().copy(text = "Another prompt"))
        assertNull(cache.loadSameConfig(otherPrompt))
    }

    private fun twoInstalls(): FakeLibraryDao = FakeLibraryDao(
        books = listOf(
            translationTestBook(bookId = 1, url = SHARED_URL).copy(installId = INSTALL_A),
            translationTestBook(bookId = 2, url = SHARED_URL).copy(installId = INSTALL_B),
        ),
        chapters = listOf(translationTestChapter(1L, 1, bookId = 1)),
        rows = mutableListOf<ChapterTranslationEntity>(),
        blocks = HashMap(),
    )

    private fun request(
        bookUrl: String,
        provider: ProviderId = ProviderId.GEMINI,
    ) = TranslationRequest(
        bookId = 1L,
        bookUrl = bookUrl,
        chapterUrl = "https://example.test/book/1/1",
        units = listOf(TranslationUnit(id = "1", text = "Hello")),
        source = LanguageCode.parse("en"),
        target = LanguageCode.parse("ru"),
        provider = provider,
        prompt = prompt(),
        revision = TranslationRevision(sourceHash = "hash", pipelineVersion = "reader-1"),
    )

    private fun result(provider: ProviderId = ProviderId.GEMINI) = ChapterTranslationResult(
        translations = mapOf(1 to "stored"),
        provider = provider,
        model = null,
    )

    private companion object {
        const val SHARED_URL = "https://example.test/book/shared"
        const val INSTALL_A = "install-a"
        const val INSTALL_B = "install-b"
    }
}
