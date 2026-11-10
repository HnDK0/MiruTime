package dev.mirutime.feature.tts

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.SentenceSpan
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsControllerTest {
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
                sentences = listOf(SentenceSpan(0, 15), SentenceSpan(16, 31)),
            ),
        ),
    )

    private fun session(
        sentence: Int = 1,
        offset: Int = 16,
        playing: Boolean = true,
        chapter: String = chapterUrl,
    ) = TtsSession(
        bookUrl = "local://book",
        chapterIndex = 1,
        chapterUrl = chapter,
        blockIndex = 0,
        sentenceIndex = sentence,
        charOffset = offset,
        wasPlaying = playing,
    )

    private class FakeEngine(
        private var ready: Boolean = true,
    ) : TtsEngine {
        override var listener: TtsEngineListener? = null
        override val isReady: Boolean
            get() = ready

        val spoken = mutableListOf<Int>()
        val spokenTexts = mutableListOf<String>()
        val previews = mutableListOf<String>()
        var stops = 0
        var failSpeak = false
        val availableVoices = setOf("voice-a", "voice-b")
        var voice: String? = null
        var appliedRate = 1f
        var appliedPitch = 1f
        var deferEngineReady = false
        var pendingEngineId: String? = null
        var refreshCount = 0
        var installedEngines = listOf(
            TtsEngineInfo("default", "Default", true),
            TtsEngineInfo("engine-b", "Second", false),
        )
        override var currentEngineId: String? = "default"
        override val defaultEngineId: String? = "default"

        override val voices: List<TtsVoice>
            get() = availableVoices.map { name ->
                TtsVoice(name, name, java.util.Locale.US, 400, currentEngineId)
            }

        override val engines: List<TtsEngineInfo>
            get() = installedEngines

        override fun refreshEngines() {
            refreshCount += 1
            listener?.onVoicesChanged()
        }

        override fun speak(text: String, utteranceId: Int): Boolean {
            spoken += utteranceId
            spokenTexts += text
            return !failSpeak
        }

        override fun preview(text: String): Boolean {
            previews += text
            return true
        }

        override fun stop() {
            stops += 1
        }

        override fun setVoice(name: String?): Boolean {
            if (name != null && name !in availableVoices) return false
            voice = name
            return true
        }

        override fun setRate(rate: Float) {
            appliedRate = rate
        }

        override fun setPitch(pitch: Float) {
            appliedPitch = pitch
        }

        override fun selectEngine(engineId: String): Boolean {
            currentEngineId = engineId
            if (deferEngineReady) {
                pendingEngineId = engineId
                ready = false
                return true
            }
            ready = true
            listener?.onReady(true)
            return true
        }

        fun completeEngineInit() {
            pendingEngineId = null
            ready = true
            listener?.onReady(true)
        }

        override fun restart() {
            ready = true
            listener?.onReady(true)
        }

        override fun shutdown() = Unit
    }

    @Test
    fun `restore is paused and never speaks`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryTtsSessionStore()
        val controller = TtsController(engine, this, store)

        assertTrue(controller.restore(document, emptyMap(), session()))

        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertEquals(1, controller.state.value.currentChunk)
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `persisted playing rehydrates as paused`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())

        assertTrue(controller.restore(document, emptyMap(), session(playing = true)))

        assertEquals(TtsStatus.Paused, controller.state.value.status)
    }

    @Test
    fun `restore rejects a mismatched chapter`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())

        assertTrue(
            !controller.restore(document, emptyMap(), session(chapter = "local://book#chapter=2")),
        )
        assertEquals(TtsStatus.Idle, controller.state.value.status)
    }

    @Test
    fun `resume continues from the restored cursor`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.restore(document, emptyMap(), session())

        controller.resume()

        assertEquals(1, engine.spoken.size)
        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertEquals(1, controller.state.value.currentChunk)
    }

    @Test
    fun `a stale utterance error does not fail the live session`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document, emptyMap())
        val live = engine.spoken.last()

        engine.listener?.onError(live + 100_000)
        assertEquals(TtsStatus.Playing, controller.state.value.status)

        engine.listener?.onError(live)
        assertEquals(TtsStatus.Failed, controller.state.value.status)
    }

    @Test
    fun `a handoff that never arrives leaves the session paused`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document, emptyMap())

        controller.skipNextChapter()
        assertEquals(TtsStatus.Paused, controller.state.value.status)

        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(TtsStatus.Paused, controller.state.value.status)
    }

    @Test
    fun `a speak failure stops the already queued chunks`() = runTest {
        val engine = FakeEngine().apply { failSpeak = true }
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())

        controller.play(document, emptyMap())

        assertEquals(TtsStatus.Failed, controller.state.value.status)
        // One stop before queueing, one stop on the failure.
        assertTrue(engine.stops >= 2)
    }

    @Test
    fun `state carries a remaining-time estimate from spoken characters`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())

        controller.play(document, emptyMap())

        val state = controller.state.value
        assertTrue(state.estimatedTotalSeconds > 0)
        assertTrue(state.estimatedRemainingSeconds in 1..state.estimatedTotalSeconds)
    }

    @Test
    fun `play persists the cursor immediately in the background`() = runTest {
        val store = RecordingSessionStore()
        val controller = TtsController(FakeEngine(), this, store)

        controller.play(document, emptyMap())

        assertNotNull(store.saved)
        assertTrue(store.savedImmediate)
        assertTrue(store.savedBackground)
        assertEquals(true, store.saved?.wasPlaying)
    }

    @Test
    fun `speed changes update the live time estimate`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())
        controller.play(document, emptyMap())
        val normal = controller.state.value.estimatedRemainingSeconds

        controller.updateSettings(TtsSettings(speed = 2f))

        assertTrue(controller.state.value.estimatedRemainingSeconds < normal)
    }

    @Test
    fun `only a focus-loss pause can auto-resume`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())
        controller.play(document, emptyMap())
        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertFalse(controller.pausedByFocusLoss)

        controller.pauseForFocusLoss()
        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertTrue(controller.pausedByFocusLoss)

        controller.resume()
        assertFalse(controller.pausedByFocusLoss)
        assertEquals(TtsStatus.Playing, controller.state.value.status)

        // A headset/notification pause after a focus-loss pause cancels the
        // auto-resume, so the call ending cannot start speech the user stopped.
        controller.pauseForFocusLoss()
        assertTrue(controller.pausedByFocusLoss)
        controller.pause()
        assertFalse(controller.pausedByFocusLoss)

        controller.resume()
        controller.stop()
        assertFalse(controller.pausedByFocusLoss)
    }

    @Test
    fun `only a finished queue reports a natural chapter end`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.updateSettings(TtsSettings(autoNextChapter = true))
        controller.play(document, emptyMap())

        // A manual skip from the last chunk is not a natural end, even though
        // the state reports atEnd = true there.
        controller.skipNextChunk()
        assertTrue(controller.state.value.atEnd)
        controller.skipNextChapter()
        assertFalse(controller.chapterEndedNaturally)

        // The queue finishing the last chunk is the only natural end.
        controller.play(document, emptyMap())
        engine.listener?.onDone(engine.spoken.last())
        assertTrue(controller.chapterEndedNaturally)

        // A manual skip after that must clear it again.
        controller.skipNextChapter()
        assertFalse(controller.chapterEndedNaturally)
    }

    @Test
    fun `paragraph seek keeps a paused session paused`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document, emptyMap())
        controller.skipNextChunk()
        controller.pause()
        assertEquals(1, controller.state.value.currentChunk)
        val spoken = engine.spoken.size

        controller.seekToBlock(document, emptyMap(), blockIndex = 0)

        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertEquals(0, controller.state.value.currentChunk)
        assertEquals(spoken, engine.spoken.size)
    }

    @Test
    fun `paragraph seek keeps a playing session playing`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())
        controller.play(document, emptyMap())
        controller.skipNextChunk()
        assertEquals(1, controller.state.value.currentChunk)

        controller.seekToBlock(document, emptyMap(), blockIndex = 0)

        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertEquals(0, controller.state.value.currentChunk)
    }

    @Test
    fun `stop clears the persisted session`() = runTest {
        val store = InMemoryTtsSessionStore()
        val controller = TtsController(FakeEngine(), this, store)
        controller.restore(document, emptyMap(), session())
        assertTrue(store.load() != null)

        controller.stop()

        assertNull(store.load())
        assertEquals(TtsStatus.Idle, controller.state.value.status)
    }

    @Test
    fun `playFrom starts at the visible block instead of the chapter top`() = runTest {
        val multiBlock = ReaderDocument(
            bookUrl = "local://book",
            chapterUrl = chapterUrl,
            chapterIndex = 1,
            title = "Chapter",
            blocks = (0..4).map { index ->
                ReaderBlock(
                    id = BlockId(chapterUrl, index),
                    kind = BlockKind.Paragraph,
                    original = "Block $index sentence.",
                    sentences = listOf(SentenceSpan(0, 19)),
                )
            },
        )
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())

        controller.playFrom(multiBlock, emptyMap(), blockIndex = 3)

        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertEquals(3, controller.state.value.currentChunk)
        assertEquals(3, controller.state.value.blockId?.index)
        assertEquals(5, controller.state.value.chunkCount)
        assertEquals(2, engine.spoken.size)
    }

    @Test
    fun `playFrom skips a block without speech and lands on the next one`() {
        val chunks = listOf(
            TtsChunk(BlockId(chapterUrl, 0), 0, "one", 0, 3, false),
            TtsChunk(BlockId(chapterUrl, 2), 0, "two", 0, 3, false),
        )

        assertEquals(1, ttsStartIndex(chunks, blockIndex = 1, charOffset = 0))
        assertEquals(1, ttsStartIndex(chunks, blockIndex = 2, charOffset = 0))
        assertEquals(0, ttsStartIndex(chunks, blockIndex = 0, charOffset = 0))
        assertEquals(1, ttsStartIndex(chunks, blockIndex = 9, charOffset = 0))
    }

    @Test
    fun `playFrom uses the char offset inside a long block`() {
        val chunks = listOf(
            TtsChunk(BlockId(chapterUrl, 0), 0, "one", 0, 10, false),
            TtsChunk(BlockId(chapterUrl, 0), 1, "two", 10, 20, false),
        )

        assertEquals(1, ttsStartIndex(chunks, blockIndex = 0, charOffset = 12))
    }

    @Test
    fun `play applies persisted settings and engine`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(
                voiceId = "voice-a",
                engineId = "engine-b",
                speed = 1.5f,
                pitch = 0.8f,
            ),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        controller.play(document)

        assertEquals("engine-b", engine.currentEngineId)
        assertEquals("voice-a", engine.voice)
        assertEquals(1.5f, engine.appliedRate, 0.001f)
        assertEquals(0.8f, engine.appliedPitch, 0.001f)
        assertEquals(TtsStatus.Playing, controller.state.value.status)
    }

    @Test
    fun `translated page uses the translated voice and original page uses the original voice`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(voiceId = "voice-a", translatedVoiceId = "voice-b"),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        controller.play(document, mapOf(0 to "First sentence. Second sentence."))
        assertEquals("voice-b", engine.voice)

        controller.stop()
        controller.play(document, emptyMap())
        assertEquals("voice-a", engine.voice)
    }

    @Test
    fun `missing saved voice falls back to the engine default`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(TtsSettings(voiceId = "voice-gone"))
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        controller.play(document)

        assertEquals(null, engine.voice)
        assertEquals(TtsStatus.Playing, controller.state.value.status)
    }

    @Test
    fun `restore keeps session values and falls back to saved settings`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(voiceId = "voice-a", speed = 1.6f, pitch = 1.7f),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        assertTrue(
            controller.restore(
                document,
                emptyMap(),
                session().copy(voiceId = null, speed = 0.7f, pitch = 1.2f),
            ),
        )

        assertEquals("voice-a", engine.voice)
        assertEquals(0.7f, engine.appliedRate, 0.001f)
        assertEquals(1.2f, engine.appliedPitch, 0.001f)
    }

    @Test
    fun `session voice wins over saved settings`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(TtsSettings(voiceId = "voice-a"))
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        assertTrue(
            controller.restore(document, emptyMap(), session().copy(voiceId = "voice-b")),
        )

        assertEquals("voice-b", engine.voice)
    }

    @Test
    fun `restore after a parallel language switch picks the new voice`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(voiceId = "voice-a", translatedVoiceId = "voice-b"),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        assertTrue(
            controller.restore(
                document,
                mapOf(0 to "First sentence. Second sentence."),
                session().copy(translated = false, voiceId = "voice-a"),
            ),
        )

        assertEquals("voice-b", engine.voice)
    }

    @Test
    fun `restore of a translated session uses the translated engine`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(
                voiceId = "voice-a",
                engineId = "default",
                translatedVoiceId = "voice-b",
                translatedEngineId = "engine-b",
            ),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        assertTrue(
            controller.restore(
                document,
                emptyMap(),
                session(),
                language = TtsLanguage.Translation,
            ),
        )

        assertEquals("engine-b", engine.currentEngineId)
        assertEquals("voice-b", engine.voice)
    }

    @Test
    fun `translation language keeps the translated voice without a translation map`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(voiceId = "voice-a", translatedVoiceId = "voice-b"),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        controller.playFrom(
            document,
            translations = emptyMap(),
            blockIndex = null,
            language = TtsLanguage.Translation,
        )

        // An explicit Translation language (the reader only asks for it when the
        // chapter has translated text) keeps the translated voice even for an
        // empty map; the text falls back to the original.
        assertEquals("voice-b", engine.voice)
        assertEquals(listOf("First sentence.", "Second sentence"), engine.spokenTexts)
    }

    @Test
    fun `session language follows the played session and clears on stop`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())

        assertNull(controller.sessionLanguage)

        controller.play(document, mapOf(0 to "Переведённое предложение."))
        assertEquals(TtsLanguage.Translation, controller.sessionLanguage)

        controller.stop()
        assertNull(controller.sessionLanguage)

        controller.play(document, emptyMap())
        assertEquals(TtsLanguage.Original, controller.sessionLanguage)
    }

    @Test
    fun `toggle with a different language restarts the spoken block in the new voice`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(voiceId = "voice-a", translatedVoiceId = "voice-b"),
        )
        val store = InMemoryTtsSessionStore()
        val controller = TtsController(engine, this, store, settings)
        controller.play(document, mapOf(0 to "Переведённое предложение."))
        controller.skipNextChunk()
        controller.pause()
        assertEquals("voice-b", engine.voice)
        engine.spokenTexts.clear()

        controller.toggle(document, emptyMap(), TtsLanguage.Original)

        // The new language re-chunks the text and picks the original voice; the
        // cursor stays in the spoken block instead of the chapter top.
        assertEquals("voice-a", engine.voice)
        assertEquals(listOf("First sentence.", "Second sentence"), engine.spokenTexts)
        assertEquals(TtsLanguage.Original, controller.sessionLanguage)
        assertFalse(engine.spokenTexts.any { it.contains("Переведённое") })
        assertEquals(false, store.load()?.translated)
    }

    @Test
    fun `toggle with the same language resumes instead of re-chunking`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document, emptyMap())
        controller.skipNextChunk()
        controller.pause()
        assertEquals(1, controller.state.value.currentChunk)
        engine.spokenTexts.clear()

        controller.toggle(document, emptyMap(), TtsLanguage.Original)

        // Resuming continues at the current chunk; a re-chunk would speak the
        // chapter's first sentence again.
        assertEquals(listOf("Second sentence"), engine.spokenTexts)
    }

    @Test
    fun `seek to block with a new language stays paused and switches the voice`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(voiceId = "voice-a", translatedVoiceId = "voice-b"),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)
        controller.play(document, mapOf(0 to "Переведённое предложение."))
        controller.pause()
        assertEquals("voice-b", engine.voice)
        val spoken = engine.spokenTexts.size

        controller.seekToBlock(document, emptyMap(), blockIndex = 0, language = TtsLanguage.Original)

        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertEquals("voice-a", engine.voice)
        assertEquals(spoken, engine.spokenTexts.size)
    }

    @Test
    fun `original language never reads the translation`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(voiceId = "voice-a", translatedVoiceId = "voice-b"),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)

        controller.playFrom(
            document,
            translations = mapOf(0 to "Translated sentence."),
            blockIndex = null,
            language = TtsLanguage.Original,
        )

        assertEquals("voice-a", engine.voice)
        assertEquals(listOf("First sentence.", "Second sentence"), engine.spokenTexts)
    }

    @Test
    fun `failed engine is surfaced and retry restarts playback`() = runTest {
        val engine = FakeEngine(ready = false)
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())

        controller.play(document)
        engine.listener?.onReady(false)

        assertEquals(TtsStatus.Failed, controller.state.value.status)
        assertEquals("TTS unavailable", controller.state.value.message)

        controller.retry()

        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertEquals(2, engine.spoken.size)
    }

    @Test
    fun `failed engine still exposes other engine voices for recovery`() = runTest {
        val engine = FakeEngine(ready = false)
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())

        engine.listener?.onReady(false)

        assertFalse(controller.capabilities.value.ready)
        assertEquals(
            setOf("voice-a", "voice-b"),
            controller.capabilities.value.voices.map { it.id }.toSet(),
        )
        assertEquals(2, controller.capabilities.value.engines.size)
    }

    @Test
    fun `test utterance pauses reading and speaks the sample`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)

        controller.testUtterance("sample")

        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertEquals(listOf("sample"), engine.previews)
    }

    @Test
    fun `settings are clamped before they reach the engine`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryTtsSettingsStore()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), store)

        controller.updateSettings(TtsSettings(speed = 9f, pitch = 0.1f))

        assertEquals(3f, controller.settings.value.speed)
        assertEquals(0.5f, controller.settings.value.pitch)
        assertEquals(3f, engine.appliedRate)
        assertEquals(0.5f, engine.appliedPitch)
        assertEquals(TtsSettings(speed = 3f, pitch = 0.5f), store.load())
    }

    @Test
    fun `skip next chunk stops the queue and continues at the next chunk`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryTtsSessionStore()
        val controller = TtsController(engine, this, store)
        controller.play(document)
        engine.listener?.onStart(engine.spoken[0])
        engine.spoken.clear()
        engine.stops = 0

        controller.skipNextChunk()

        assertEquals(1, engine.stops)
        assertEquals(1, engine.spoken.size)
        assertEquals(1, controller.state.value.currentChunk)
        assertTrue(controller.state.value.atEnd)
        assertEquals(1, store.load()?.sentenceIndex)
    }

    @Test
    fun `skip previous chunk re-speaks from the previous chunk`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)
        engine.listener?.onStart(engine.spoken[1])
        engine.spoken.clear()

        controller.skipPreviousChunk()

        assertEquals(2, engine.spoken.size)
        assertEquals(0, controller.state.value.currentChunk)
        assertFalse(controller.state.value.atEnd)
    }

    @Test
    fun `paused skip moves the cursor without speaking`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryTtsSessionStore()
        val controller = TtsController(engine, this, store)
        controller.restore(document, emptyMap(), session())

        controller.skipPreviousChunk()

        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, controller.state.value.currentChunk)
        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertFalse(controller.state.value.atEnd)
        assertEquals(0, store.load()?.sentenceIndex)
    }

    @Test
    fun `chapter skip stops speech and requests the next chapter`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryTtsSessionStore()
        val controller = TtsController(engine, this, store)
        val requests = mutableListOf<TtsChapterDirection>()
        val job = launch { controller.chapterRequests.collect { requests += it } }
        runCurrent()
        controller.play(document)
        engine.listener?.onStart(engine.spoken[0])
        engine.spoken.clear()
        engine.stops = 0

        controller.skipNextChapter()
        runCurrent()

        assertEquals(listOf(TtsChapterDirection.Next), requests)
        assertEquals(1, engine.stops)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertFalse(store.load()?.wasPlaying ?: true)
        job.cancel()
    }

    @Test
    fun `previous chapter skip requests the previous direction`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())
        val requests = mutableListOf<TtsChapterDirection>()
        val job = launch { controller.chapterRequests.collect { requests += it } }
        runCurrent()
        controller.play(document)

        controller.skipPreviousChapter()
        runCurrent()

        assertEquals(listOf(TtsChapterDirection.Previous), requests)
        job.cancel()
    }

    @Test
    fun `late range from a previous utterance cannot move the highlight`() = runTest {
        val twoBlocks = document.copy(
            blocks = listOf(
                ReaderBlock(
                    id = BlockId(chapterUrl, 0),
                    kind = BlockKind.Paragraph,
                    original = "First block.",
                    sentences = listOf(SentenceSpan(0, 12)),
                ),
                ReaderBlock(
                    id = BlockId(chapterUrl, 1),
                    kind = BlockKind.Paragraph,
                    original = "Second block.",
                    sentences = listOf(SentenceSpan(0, 13)),
                ),
            ),
        )
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(twoBlocks)
        val first = engine.spoken[0]
        val second = engine.spoken[1]
        engine.listener?.onStart(first)
        engine.listener?.onStart(second)

        val before = controller.state.value
        engine.listener?.onRangeStart(first, 2, 7)

        assertEquals(before.rangeStart, controller.state.value.rangeStart)
        assertEquals(before.rangeEnd, controller.state.value.rangeEnd)
        assertEquals(1, controller.state.value.currentChunk)
        assertEquals(1, controller.state.value.blockId?.index)

        engine.listener?.onRangeStart(second, 0, 6)

        assertEquals(0, controller.state.value.rangeStart)
        assertEquals(6, controller.state.value.rangeEnd)
        assertEquals(TtsStatus.Playing, controller.state.value.status)
    }

    @Test
    fun `late range after pause cannot move the highlight`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)
        val last = engine.spoken[1]
        engine.listener?.onStart(last)
        val before = controller.state.value

        controller.pause()
        engine.listener?.onRangeStart(last, 2, 7)

        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertEquals(before.rangeStart, controller.state.value.rangeStart)
        assertEquals(before.rangeEnd, controller.state.value.rangeEnd)
        assertEquals(1, controller.state.value.currentChunk)
    }

    @Test
    fun `late range after a chapter request cannot move the highlight`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)
        val last = engine.spoken[1]
        engine.listener?.onStart(last)

        controller.skipNextChapter()
        val paused = controller.state.value
        engine.listener?.onRangeStart(last, 2, 7)

        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertEquals(paused.rangeStart, controller.state.value.rangeStart)
        assertEquals(paused.rangeEnd, controller.state.value.rangeEnd)
        assertEquals(1, controller.state.value.currentChunk)
    }

    @Test
    fun `late onStart cannot move the cursor back`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)
        val first = engine.spoken[0]
        val second = engine.spoken[1]
        engine.listener?.onStart(first)
        engine.listener?.onStart(second)

        engine.listener?.onStart(first)

        assertEquals(1, controller.state.value.currentChunk)
        assertEquals(TtsStatus.Playing, controller.state.value.status)
    }

    @Test
    fun `atEnd follows the last chunk only`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)

        engine.listener?.onStart(engine.spoken[0])
        assertFalse(controller.state.value.atEnd)

        engine.listener?.onStart(engine.spoken[1])
        assertTrue(controller.state.value.atEnd)
    }

    @Test
    fun `auto-next finishes the last chunk with a next chapter request`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(
            engine,
            this,
            InMemoryTtsSessionStore(),
            InMemoryTtsSettingsStore(TtsSettings(autoNextChapter = true)),
        )
        val requests = mutableListOf<TtsChapterDirection>()
        val job = launch { controller.chapterRequests.collect { requests += it } }
        runCurrent()
        controller.play(document)
        val last = engine.spoken[1]
        engine.listener?.onStart(last)

        engine.listener?.onDone(last)
        runCurrent()

        assertEquals(listOf(TtsChapterDirection.Next), requests)
        assertEquals(TtsStatus.Loading, controller.state.value.status)
        assertTrue(controller.state.value.atEnd)
        job.cancel()
    }

    @Test
    fun `auto-next off ends the chapter without a request`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(
            engine,
            this,
            InMemoryTtsSessionStore(),
            InMemoryTtsSettingsStore(TtsSettings(autoNextChapter = false)),
        )
        val requests = mutableListOf<TtsChapterDirection>()
        val job = launch { controller.chapterRequests.collect { requests += it } }
        runCurrent()
        controller.play(document)
        val last = engine.spoken[1]
        engine.listener?.onStart(last)

        engine.listener?.onDone(last)
        runCurrent()

        assertTrue(requests.isEmpty())
        assertEquals(TtsStatus.Idle, controller.state.value.status)
        assertTrue(controller.state.value.atEnd)
        job.cancel()
    }

    @Test
    fun `restore applies voice and clamps rate and pitch`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())

        assertTrue(
            controller.restore(
                document,
                emptyMap(),
                session().copy(voiceId = "voice-a", speed = 5f, pitch = 0.1f),
            ),
        )

        assertEquals("voice-a", engine.voice)
        assertEquals(3f, engine.appliedRate)
        assertEquals(0.5f, engine.appliedPitch)
    }
    @Test
    fun `playing rate change stops and requeues the current chunk`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryTtsSessionStore()
        val controller = TtsController(engine, this, store)
        controller.play(document)
        engine.listener?.onStart(engine.spoken[1])
        engine.spoken.clear()
        engine.stops = 0

        controller.updateSettings(
            controller.settings.value.copy(speed = 2.5f, voiceId = "voice-b"),
        )

        assertEquals(1, engine.stops)
        assertEquals(1, engine.spoken.size)
        assertEquals(1, controller.state.value.currentChunk)
        assertEquals(2.5f, engine.appliedRate, 0.001f)
        assertEquals("voice-b", engine.voice)
        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertEquals(2.5f, store.load()?.speed)
        assertEquals(1, store.load()?.sentenceIndex)
    }

    @Test
    fun `paused settings change applies without speaking`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.restore(document, emptyMap(), session())
        engine.spoken.clear()
        engine.stops = 0

        controller.updateSettings(controller.settings.value.copy(speed = 2.5f))

        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, engine.stops)
        assertEquals(2.5f, engine.appliedRate, 0.001f)
        assertEquals(TtsStatus.Paused, controller.state.value.status)
    }

    @Test
    fun `appearance only settings do not interrupt playback`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)
        engine.listener?.onStart(engine.spoken[1])
        engine.spoken.clear()
        engine.stops = 0

        controller.updateSettings(
            controller.settings.value.copy(
                floatingPlayer = true,
                floatingOpacity = 0.5f,
                autoNextChapter = true,
            ),
        )

        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, engine.stops)
        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertEquals(1, controller.state.value.currentChunk)
    }

    @Test
    fun `engine switch during playback resumes the same chunk after init`() = runTest {
        val engine = FakeEngine().apply { deferEngineReady = true }
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)
        engine.listener?.onStart(engine.spoken[1])
        engine.spoken.clear()
        engine.stops = 0

        controller.updateSettings(controller.settings.value.copy(engineId = "engine-b"))

        assertEquals(TtsStatus.Loading, controller.state.value.status)
        assertEquals(1, controller.state.value.currentChunk)
        assertTrue(engine.spoken.isEmpty())

        engine.completeEngineInit()

        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertEquals(1, controller.state.value.currentChunk)
        assertEquals(1, engine.spoken.size)
        assertEquals("engine-b", engine.currentEngineId)
    }

    @Test
    fun `refresh discovers engines installed or removed while running`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        assertEquals(
            listOf("default", "engine-b"),
            controller.capabilities.value.engines.map { it.id },
        )

        engine.installedEngines = listOf(
            TtsEngineInfo("com.google.android.tts", "Speech Services by Google", true),
            TtsEngineInfo("com.github.olga_yakovleva.rhvoice.android", "RHVoice", false),
        )
        controller.refreshEngines()

        assertEquals(1, engine.refreshCount)
        assertEquals(
            listOf("Speech Services by Google", "RHVoice"),
            controller.capabilities.value.engines.map { it.label },
        )

        engine.installedEngines = listOf(
            TtsEngineInfo("com.google.android.tts", "Speech Services by Google", true),
        )
        controller.refreshEngines()

        assertEquals(
            listOf("com.google.android.tts"),
            controller.capabilities.value.engines.map { it.id },
        )
    }

    private fun finishChapter(engine: FakeEngine) {
        val last = engine.spoken.last()
        engine.listener?.onStart(last)
        engine.listener?.onDone(last)
    }

    @Test
    fun `sleep timer stops playback and keeps the session`() = runTest {
        val engine = FakeEngine()
        val store = InMemoryTtsSessionStore()
        val controller = TtsController(
            engine,
            this,
            store,
            InMemoryTtsSettingsStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimer(1)

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(TtsStatus.Idle, controller.state.value.status)
        assertTrue(engine.stops > 0)
        assertNotNull(store.load())
        assertFalse(controller.state.value.atEnd)
        assertNull(controller.sleepTimer.value)
    }

    @Test
    fun `sleep timer ticks down every second`() = runTest {
        val controller = TtsController(
            FakeEngine(),
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimer(1)

        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(58, controller.sleepTimer.value?.remainingSeconds)
        controller.cancelSleepTimer()
    }

    @Test
    fun `user pause cancels the sleep timer`() = runTest {
        val controller = TtsController(
            FakeEngine(),
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimer(1)

        controller.pause()
        assertNull(controller.sleepTimer.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(TtsStatus.Paused, controller.state.value.status)
    }

    @Test
    fun `system pause keeps the sleep timer running`() = runTest {
        val controller = TtsController(
            FakeEngine(),
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimer(1)

        controller.pauseFromSystem()
        assertNotNull(controller.sleepTimer.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(TtsStatus.Idle, controller.state.value.status)
    }

    @Test
    fun `test utterance keeps the sleep timer`() = runTest {
        val controller = TtsController(
            FakeEngine(),
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimer(5)

        controller.testUtterance("sample")

        assertNotNull(controller.sleepTimer.value)
        controller.cancelSleepTimer()
    }

    @Test
    fun `chapter timer continues chapters without auto-next and stops at zero`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(
            engine,
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        val requests = mutableListOf<TtsChapterDirection>()
        val job = launch { controller.chapterRequests.collect { requests += it } }
        runCurrent()
        controller.play(document)
        controller.setSleepTimerChapters(2)

        finishChapter(engine)
        runCurrent()
        assertEquals(listOf(TtsChapterDirection.Next), requests)
        assertEquals(TtsStatus.Loading, controller.state.value.status)
        assertEquals(1, controller.sleepTimer.value?.chaptersRemaining)

        controller.play(document)
        finishChapter(engine)
        runCurrent()

        assertEquals(1, requests.size)
        assertEquals(TtsStatus.Idle, controller.state.value.status)
        assertTrue(controller.state.value.atEnd)
        assertNull(controller.sleepTimer.value)
        job.cancel()
    }

    @Test
    fun `manual chapter skip does not spend the chapter timer`() = runTest {
        val controller = TtsController(
            FakeEngine(),
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimerChapters(2)

        controller.skipNextChapter()
        runCurrent()

        assertEquals(2, controller.sleepTimer.value?.chaptersRemaining)
        controller.cancelSleepTimer()
    }

    @Test
    fun `stop and close clear the sleep timer`() = runTest {
        val controller = TtsController(
            FakeEngine(),
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimerChapters(2)
        controller.stop()
        assertNull(controller.sleepTimer.value)

        controller.play(document)
        controller.setSleepTimer(5)
        controller.close()
        assertNull(controller.sleepTimer.value)
    }

    @Test
    fun `natural chapter end without auto-next clears the minutes timer`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(
            engine = engine,
            scope = this,
            sessions = InMemoryTtsSessionStore(),
            settingsStore = InMemoryTtsSettingsStore(TtsSettings(autoNextChapter = false)),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimer(5)

        finishChapter(engine)
        runCurrent()

        assertEquals(TtsStatus.Idle, controller.state.value.status)
        assertNull(controller.sleepTimer.value)
    }

    @Test
    fun `sleep timer is ignored while playback is idle`() = runTest {
        val controller = TtsController(
            FakeEngine(),
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )

        controller.setSleepTimer(5)
        controller.setSleepTimerChapters(2)

        assertNull(controller.sleepTimer.value)
    }

    @Test
    fun `expired timer blocks a chapter hand-off`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(
            engine,
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimer(1)
        advanceTimeBy(60_000)
        runCurrent()

        val spoken = engine.spoken.size
        assertFalse(controller.playHandoff(document, emptyMap()))
        assertEquals(spoken, engine.spoken.size)
        assertEquals(TtsStatus.Idle, controller.state.value.status)

        controller.play(document)
        assertEquals(TtsStatus.Playing, controller.state.value.status)
    }

    @Test
    fun `manual skip at the timer boundary cannot resurrect the chapter`() = runTest {
        val controller = TtsController(
            FakeEngine(),
            this,
            InMemoryTtsSessionStore(),
            now = { testScheduler.currentTime },
        )
        controller.play(document)
        controller.setSleepTimer(1)
        controller.skipNextChapter()
        advanceTimeBy(60_000)
        runCurrent()

        assertFalse(controller.playHandoff(document, emptyMap()))
        assertEquals(TtsStatus.Idle, controller.state.value.status)
    }

    @Test
    fun `stop during a chapter handoff drops it`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)
        finishChapter(engine)
        runCurrent()
        assertEquals(TtsStatus.Loading, controller.state.value.status)

        controller.stop()
        val spoken = engine.spoken.size

        assertFalse(controller.playHandoff(document, emptyMap()))
        assertEquals(spoken, engine.spoken.size)
        assertEquals(TtsStatus.Idle, controller.state.value.status)
    }

    @Test
    fun `pause during a chapter handoff drops it`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(engine, this, InMemoryTtsSessionStore())
        controller.play(document)
        finishChapter(engine)
        runCurrent()

        controller.pause()

        assertFalse(controller.playHandoff(document, emptyMap()))
        assertEquals(TtsStatus.Paused, controller.state.value.status)
    }

    @Test
    fun `handoff without a request is ignored`() = runTest {
        val controller = TtsController(FakeEngine(), this, InMemoryTtsSessionStore())

        assertFalse(controller.playHandoff(document, emptyMap()))
        assertEquals(TtsStatus.Idle, controller.state.value.status)
    }

    @Test
    fun `reload settings applies a restored store snapshot`() = runTest {
        val engine = FakeEngine()
        val settings = InMemoryTtsSettingsStore(
            TtsSettings(voiceId = "voice-a", engineId = "default", speed = 1f, pitch = 1f),
        )
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)
        engine.listener?.onReady(true)

        settings.save(
            TtsSettings(voiceId = "voice-b", engineId = "default", speed = 1.8f, pitch = 0.6f),
        )
        controller.reloadSettings()
        runCurrent()

        assertEquals("voice-b", controller.settings.value.voiceId)
        assertEquals("voice-b", engine.voice)
        assertEquals(1.8f, engine.appliedRate, 0.001f)
        assertEquals(0.6f, engine.appliedPitch, 0.001f)
    }

    @Test
    fun `reload settings with a new engine resumes the current chunk`() = runTest {
        val engine = FakeEngine().apply { deferEngineReady = true }
        val settings = InMemoryTtsSettingsStore(TtsSettings(engineId = "default"))
        val controller = TtsController(engine, this, InMemoryTtsSessionStore(), settings)
        controller.play(document)
        engine.listener?.onStart(engine.spoken[1])
        engine.spoken.clear()
        engine.stops = 0

        settings.save(TtsSettings(engineId = "engine-b"))
        controller.reloadSettings()
        runCurrent()

        // The engine restarts: the session must not stay "Playing" without sound.
        assertEquals(TtsStatus.Loading, controller.state.value.status)
        assertEquals(1, controller.state.value.currentChunk)
        assertTrue(engine.spoken.isEmpty())

        engine.completeEngineInit()

        assertEquals(TtsStatus.Playing, controller.state.value.status)
        assertEquals(1, controller.state.value.currentChunk)
        assertEquals(1, engine.spoken.size)
        assertEquals("engine-b", engine.currentEngineId)
    }

    @Test
    fun `sleep timer presets set the expected duration`() = runTest {
        val engine = FakeEngine()
        val controller = TtsController(
            engine = engine,
            scope = this,
            sessions = InMemoryTtsSessionStore(),
            now = { 1_000L },
        )
        controller.play(document)

        TtsSleepTimerPresetMinutes.forEach { minutes ->
            controller.setSleepTimer(minutes)
            val timer = controller.sleepTimer.value
            assertEquals(minutes * 60, timer?.totalSeconds)
            assertTrue(isSleepTimerPresetActive(timer, minutes))
        }
        controller.setSleepTimer(20)
        assertFalse(isSleepTimerPresetActive(controller.sleepTimer.value, 15))
        controller.setSleepTimerChapters(2)
        assertFalse(isSleepTimerPresetActive(controller.sleepTimer.value, 15))
    }

    @Test
    fun `preview voice delegates without changing the selected voice`() = runTest {
        val engine = FakeEngine()
        val previewer = FakeVoicePreviewer()
        val settings = InMemoryTtsSettingsStore(TtsSettings(voiceId = "voice-a"))
        val controller = TtsController(
            engine = engine,
            scope = this,
            sessions = InMemoryTtsSessionStore(),
            settingsStore = settings,
            voicePreviewer = previewer,
        )
        val before = controller.settings.value

        controller.previewVoice("voice-b", "engine-b", 1.4f, 0.9f, "sample")

        assertEquals("voice-b", previewer.voiceId)
        assertEquals("engine-b", previewer.engineId)
        assertEquals(1.4f, previewer.speed)
        assertEquals(0.9f, previewer.pitch)
        assertEquals("sample", previewer.text)
        assertEquals(before, controller.settings.value)
        assertEquals("voice-a", controller.settings.value.voiceId)

        controller.stopVoicePreview()
        assertEquals(1, previewer.stops)
    }

    @Test
    fun `preview voice pauses active reading`() = runTest {
        val engine = FakeEngine()
        val previewer = FakeVoicePreviewer()
        val controller = TtsController(
            engine = engine,
            scope = this,
            sessions = InMemoryTtsSessionStore(),
            voicePreviewer = previewer,
        )
        controller.play(document)
        assertEquals(TtsStatus.Playing, controller.state.value.status)

        controller.previewVoice("voice-b", null, 1f, 1f, "sample")

        assertEquals(TtsStatus.Paused, controller.state.value.status)
        assertEquals("voice-b", previewer.voiceId)
    }

    @Test
    fun `stop remembers the last session`() = runTest {
        val sessions = InMemoryTtsSessionStore()
        val controller = TtsController(FakeEngine(), this, sessions)
        controller.play(document)

        controller.stop()

        val stopped = controller.lastStoppedSession
        assertNotNull(stopped)
        assertEquals(document.bookUrl, stopped?.bookUrl)
        assertEquals(document.chapterIndex, stopped?.chapterIndex)
    }

}

private class FakeVoicePreviewer : TtsVoicePreviewer {
    var voiceId: String? = null
    var engineId: String? = null
    var speed: Float = 0f
    var pitch: Float = 0f
    var text: String = ""
    var stops = 0

    override fun preview(
        voiceId: String?,
        engineId: String?,
        speed: Float,
        pitch: Float,
        text: String,
        onUnavailable: () -> Unit,
    ) {
        this.voiceId = voiceId
        this.engineId = engineId
        this.speed = speed
        this.pitch = pitch
        this.text = text
    }

    override fun stop() {
        stops++
    }

    override fun shutdown() = Unit
}

private class RecordingSessionStore : TtsSessionStore {
    var saved: TtsSession? = null
        private set
    var savedImmediate = false
        private set
    var savedBackground = false
        private set

    override fun load(): TtsSession? = saved

    override fun save(session: TtsSession, immediate: Boolean, background: Boolean) {
        saved = session
        savedImmediate = immediate
        savedBackground = background
    }

    override fun clear(immediate: Boolean, background: Boolean) {
        saved = null
    }
}
