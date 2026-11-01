package dev.mirutime.feature.tts

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.SentenceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsPlaybackInfoTest {
    @Test
    fun `playing notification uses the chapter as title and the book as artist`() {
        val info = ttsNotificationInfo(TtsState(status = TtsStatus.Playing), "Book", "Chapter 1")

        assertEquals("Chapter 1", info.title)
        assertEquals("Book", info.text)
        assertEquals(TtsTransportAction.Pause, info.action)
    }

    @Test
    fun `loading is treated as active playback`() {
        val info = ttsNotificationInfo(TtsState(status = TtsStatus.Loading), "Book", "Chapter 1")

        assertEquals(TtsTransportAction.Pause, info.action)
    }

    @Test
    fun `paused notification offers play`() {
        val info = ttsNotificationInfo(TtsState(status = TtsStatus.Paused), "Book", "Chapter 1")

        assertEquals(TtsTransportAction.Play, info.action)
    }

    @Test
    fun `idle and failed offer stop`() {
        assertEquals(
            TtsTransportAction.Stop,
            ttsNotificationInfo(TtsState(status = TtsStatus.Idle), "Book", "Chapter").action,
        )
        assertEquals(
            TtsTransportAction.Stop,
            ttsNotificationInfo(TtsState(status = TtsStatus.Failed), "Book", "Chapter").action,
        )
    }

    @Test
    fun `missing book title falls back to the chapter`() {
        val info = ttsNotificationInfo(TtsState(status = TtsStatus.Playing), "  ", "Chapter 1")

        assertEquals("Chapter 1", info.title)
        assertEquals("Chapter 1", info.text)
    }

    @Test
    fun `chapter skip actions are available only in a live session`() {
        val statuses = listOf(
            TtsStatus.Playing to true,
            TtsStatus.Loading to true,
            TtsStatus.Paused to true,
            TtsStatus.Idle to false,
            TtsStatus.Failed to false,
        )

        statuses.forEach { (status, expected) ->
            assertEquals(
                "canSkip for $status",
                expected,
                ttsNotificationInfo(TtsState(status = status), "Book", "Chapter").canSkip,
            )
        }
    }

    @Test
    fun `service action strings map to transport commands`() {        assertEquals(
            TtsTransportCommand.Play,
            ttsTransportCommand(TtsPlaybackIntent.ActionPlay),
        )
        assertEquals(
            TtsTransportCommand.Pause,
            ttsTransportCommand(TtsPlaybackIntent.ActionPause),
        )
        assertEquals(
            TtsTransportCommand.Stop,
            ttsTransportCommand(TtsPlaybackIntent.ActionStop),
        )
        assertEquals(
            TtsTransportCommand.NextChapter,
            ttsTransportCommand(TtsPlaybackIntent.ActionNextChapter),
        )
        assertEquals(
            TtsTransportCommand.PreviousChapter,
            ttsTransportCommand(TtsPlaybackIntent.ActionPreviousChapter),
        )
        assertEquals(null, ttsTransportCommand(TtsPlaybackIntent.ActionStart))
        assertEquals(null, ttsTransportCommand(null))
        assertEquals(null, ttsTransportCommand("unknown"))
    }

    @Test
    fun `progress follows the spoken chunk count`() {
        assertEquals(
            50,
            ttsProgressPercent(
                TtsState(status = TtsStatus.Playing, currentChunk = 4, chunkCount = 10),
            ),
        )
        assertEquals(100, ttsProgressPercent(TtsState(currentChunk = 9, chunkCount = 10)))
        assertEquals(0, ttsProgressPercent(TtsState(currentChunk = 0, chunkCount = 0)))
    }

    private val chapterUrl = "local://book#chapter=1"
    private val document = ReaderDocument(
        bookUrl = "local://book",
        chapterUrl = chapterUrl,
        chapterIndex = 1,
        title = "Chapter",
        blocks = listOf(
            ReaderBlock(
                id = BlockId(chapterUrl, 0),
                kind = BlockKind.Paragraph,
                original = "First sentence. Second sentence.",
                translation = "Première phrase. Deuxième phrase.",
                sentences = listOf(SentenceSpan(0, 15), SentenceSpan(16, 32)),
            ),
        ),
    )

    @Test
    fun `preview uses the sentence span of the current cursor`() {
        val preview = ttsSentencePreview(
            document,
            TtsState(
                status = TtsStatus.Playing,
                blockId = BlockId(chapterUrl, 0),
                sentenceIndex = 1,
                rangeStart = 16,
                rangeEnd = 32,
            ),
        )

        assertEquals("Second sentence.", preview)
    }

    @Test
    fun `preview falls back to the live spoken range`() {
        val preview = ttsSentencePreview(
            document,
            TtsState(
                status = TtsStatus.Playing,
                currentChunk = 3,
                blockId = BlockId(chapterUrl, 0),
                sentenceIndex = 99,
                rangeStart = 6,
                rangeEnd = 14,
            ),
        )

        assertEquals("sentence", preview)
    }

    @Test
    fun `translated preview uses the rendered sentence over the live word range`() {
        val translatedText = "Première phrase longue avec beaucoup de mots pour dépasser largement la limite de cent caractères. Deuxième phrase longue avec encore plus de mots pour être certaine que la découpe fonctionne comme prévu."
        val preview = ttsSentencePreview(
            document,
            TtsState(
                status = TtsStatus.Playing,
                blockId = BlockId(chapterUrl, 0),
                sentenceIndex = 0,
                rangeStart = 0,
                rangeEnd = 8,
                translated = true,
            ),
            translatedText = translatedText,
        )

        assertEquals(
            "Première phrase longue avec beaucoup de mots pour dépasser largement la limite de cent caractères.",
            preview,
        )
    }

    @Test
    fun `preview is null without a loaded block`() {
        assertNull(ttsSentencePreview(null, TtsState(status = TtsStatus.Loading)))
        assertNull(
            ttsSentencePreview(
                document,
                TtsState(
                    status = TtsStatus.Playing,
                    blockId = BlockId("local://book#chapter=2", 0),
                    rangeStart = 0,
                    rangeEnd = 5,
                ),
            ),
        )
    }

    @Test
    fun `preview info maps the spoken range onto the sentence`() {
        val info = ttsSentencePreviewInfo(
            document,
            TtsState(
                status = TtsStatus.Playing,
                blockId = BlockId(chapterUrl, 0),
                sentenceIndex = 1,
                rangeStart = 20,
                rangeEnd = 23,
            ),
        )

        assertEquals("Second sentence.", info?.text)
        assertEquals(4, info?.start)
        assertEquals(7, info?.end)
    }

    @Test
    fun `preview info trims surrounding whitespace from the range`() {
        val spacedUrl = "local://book#chapter=9"
        val spacedDocument = ReaderDocument(
            bookUrl = "local://book",
            chapterUrl = spacedUrl,
            chapterIndex = 9,
            title = "Chapter",
            blocks = listOf(
                ReaderBlock(
                    id = BlockId(spacedUrl, 0),
                    kind = BlockKind.Paragraph,
                    original = "  abc def  ",
                    sentences = listOf(SentenceSpan(0, 11)),
                ),
            ),
        )
        val info = ttsSentencePreviewInfo(
            spacedDocument,
            TtsState(
                status = TtsStatus.Playing,
                blockId = BlockId(spacedUrl, 0),
                sentenceIndex = 0,
                rangeStart = 2,
                rangeEnd = 5,
            ),
        )

        assertEquals("abc def", info?.text)
        assertEquals(0, info?.start)
        assertEquals(3, info?.end)
    }
}
