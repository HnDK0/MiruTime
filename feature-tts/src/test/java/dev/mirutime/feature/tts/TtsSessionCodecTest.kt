package dev.mirutime.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsSessionCodecTest {
    @Test
    fun `round trip keeps every field`() {
        val session = TtsSession(
            bookUrl = "local://book?x=1&y=%2F",
            chapterIndex = 7,
            chapterUrl = "local://book#chapter=7&part=a=b",
            blockIndex = 12,
            sentenceIndex = 3,
            charOffset = 456,
            voiceId = "en-us-x#a=b&c",
            speed = 1.25f,
            pitch = 0.75f,
            wasPlaying = true,
            translated = true,
            textHash = "12:345",
            updatedAt = 1_700_000_000_000L,
        )

        assertEquals(session, TtsSessionCodec.decode(TtsSessionCodec.encode(session)))
    }

    @Test
    fun `nullable fields survive round trip`() {
        val session = TtsSession(
            bookUrl = "book",
            chapterIndex = 0,
            chapterUrl = "book#chapter=1",
            blockIndex = 0,
            sentenceIndex = 0,
            charOffset = 0,
        )

        assertEquals(session, TtsSessionCodec.decode(TtsSessionCodec.encode(session)))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(TtsSessionCodec.decode(null))
        assertNull(TtsSessionCodec.decode(""))
        assertNull(TtsSessionCodec.decode("not a session"))
        assertNull(TtsSessionCodec.decode("v=1&book=book"))
    }
}
