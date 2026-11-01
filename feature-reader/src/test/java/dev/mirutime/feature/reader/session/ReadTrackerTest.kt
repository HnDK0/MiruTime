package dev.mirutime.feature.reader.session

import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.feature.reader.api.ReaderContentSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadTrackerTest {
    @Test
    fun `marks a chapter only once`() = runBlocking {
        val content = object : ReaderContentSource {
            val marks = mutableListOf<Pair<Int, Boolean>>()
            override suspend fun book(bookUrl: String): ReaderBook? = null
            override suspend fun document(bookUrl: String, chapterIndex: Int): ReaderDocument? = null
            override suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean) {
                marks += chapterIndex to read
            }
        }
        val tracker = ReadTracker(content)
        tracker.markFinished("b", 4)
        tracker.markFinished("b", 4)
        assertEquals(listOf(4 to true), content.marks)
    }
}
