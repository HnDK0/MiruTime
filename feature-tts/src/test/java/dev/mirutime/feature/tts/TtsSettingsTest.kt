package dev.mirutime.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSettingsTest {

    @Test
    fun settingsRoundTripThroughStore() {
        val store = InMemoryTtsSettingsStore()
        val saved = TtsSettings(
            voiceId = "en-us-x-sfg#female_1-local",
            engineId = "com.google.android.tts",
            speed = 1.3f,
            pitch = 0.9f,
            autoNextChapter = false,
            miniPlayerAlways = true,
        )

        store.save(saved)

        assertEquals(saved, store.load())
    }

    @Test
    fun autoNextChapterIsOnByDefault() {
        assertTrue(TtsSettings().autoNextChapter)
    }

    @Test
    fun sanitizedClampsOutOfRangeValues() {
        val settings = TtsSettings(speed = 5f, pitch = 0.1f).sanitized()

        assertEquals(3f, settings.speed)
        assertEquals(0.5f, settings.pitch)
    }

    @Test
    fun rateRangeIsHalfToThreeAtHundredthSteps() {
        assertEquals(0.5f, clampTtsSpeed(-1f))
        assertEquals(3f, clampTtsSpeed(9f))
        assertEquals(2.34f, TtsSettings(speed = 2.344f).sanitized().speed, 0.001f)
        assertEquals(2.5f, TtsSettings(speed = 2.5f).sanitized().speed)
        assertEquals("2.50", formatTtsRate(2.5f))
        assertEquals(249, ttsSliderSteps(TtsMinSpeed..TtsMaxSpeed, TtsSpeedStep))
    }

    @Test
    fun playbackChangedIgnoresAppearanceOnlyFields() {
        val base = TtsSettings(voiceId = "voice-a", engineId = "engine-a", speed = 1.2f, pitch = 0.9f)

        assertFalse(ttsPlaybackChanged(base, base.copy(floatingPlayer = true), translated = false))
        assertFalse(
            ttsPlaybackChanged(base, base.copy(savedVoices = emptyList()), translated = false),
        )
        assertTrue(ttsPlaybackChanged(base, base.copy(voiceId = "voice-b"), translated = false))
        assertTrue(ttsPlaybackChanged(base, base.copy(engineId = "engine-b"), translated = false))
        assertTrue(ttsPlaybackChanged(base, base.copy(speed = 1.3f), translated = false))
        assertTrue(ttsPlaybackChanged(base, base.copy(pitch = 1.1f), translated = false))
        assertFalse(ttsPlaybackChanged(base, base.copy(floatingOpacity = 0.5f), translated = false))
    }

    @Test
    fun playbackChangedOnlyCountsTheSlotTheSessionSpeaks() {
        val base = TtsSettings(
            voiceId = "voice-a",
            engineId = "engine-a",
            translatedVoiceId = "voice-t",
            translatedEngineId = "engine-t",
        )

        // Tuning the other language's voice must not restart the current chunk.
        assertFalse(
            ttsPlaybackChanged(base, base.copy(translatedVoiceId = "voice-t2"), translated = false),
        )
        assertFalse(ttsPlaybackChanged(base, base.copy(voiceId = "voice-b"), translated = true))
        assertTrue(
            ttsPlaybackChanged(base, base.copy(translatedVoiceId = "voice-t2"), translated = true),
        )
        assertTrue(ttsPlaybackChanged(base, base.copy(voiceId = "voice-b"), translated = false))

        // An unset translated slot follows the original one.
        val fallback = TtsSettings(voiceId = "voice-a")
        assertTrue(ttsPlaybackChanged(fallback, fallback.copy(voiceId = "voice-b"), translated = true))
    }

    @Test
    fun savedVoicesRoundTripThroughPrefCodec() {
        val voices = listOf(
            TtsSavedVoice(
                "Night	voice",
                "en-us-x-sfg#female_1-local",
                1.25f,
                0.9f,
                "com.google.android.tts",
            ),
            TtsSavedVoice("Default % 2", null, 1f, 1f),
        )

        assertEquals(voices, decodeTtsSavedVoices(encodeTtsSavedVoices(voices)))
        assertEquals(emptyList<TtsSavedVoice>(), decodeTtsSavedVoices(null))
    }

    @Test
    fun legacyFlatSavedVoiceDecodesWithoutEngine() {
        val decoded = decodeTtsSavedVoices("Old voice\tvoice-a\t1.5\t0.75")

        assertEquals(
            listOf(TtsSavedVoice("Old voice", "voice-a", 1.5f, 0.75f, engineId = null)),
            decoded,
        )
    }

    @Test
    fun voiceCatalogKeepsCurrentEngineAndAddsAuxiliaryEngines() {
        val current = listOf(
            TtsVoice("v1", "Voice 1", java.util.Locale.US, 300, "engine-a", needsInternet = false),
        )
        val auxiliary = mapOf(
            "engine-a" to listOf(
                TtsVoice("v1", "Voice 1", java.util.Locale.US, 400, "engine-a", true),
            ),
            "engine-b" to listOf(
                TtsVoice("v2", "Voice 2", java.util.Locale.UK, 500, "engine-b", true),
            ),
        )

        val catalog = ttsVoiceCatalog("engine-a", current, auxiliary)

        assertEquals(2, catalog.size)
        assertEquals("engine-a", catalog.first().engineId)
        assertEquals("Voice 1", catalog.first { it.engineId == "engine-a" }.name)
        assertEquals(false, catalog.first { it.engineId == "engine-a" }.needsInternet)
        assertEquals(true, catalog.first { it.engineId == "engine-b" }.needsInternet)
    }

    @Test
    fun voiceFilterMatchesNameIdLocaleAndEngineLabel() {
        val voices = listOf(
            TtsVoice("v1", "Aurora", java.util.Locale.US, 300, "engine-a"),
            TtsVoice("v2", "Borealis", java.util.Locale.UK, 300, "engine-b"),
        )
        val labels = mapOf("engine-a" to "Engine A", "engine-b" to "Engine B")
        val label: (String?) -> String = { id -> labels[id].orEmpty() }

        assertEquals(listOf("v1"), ttsFilterVoices(voices, "aurora", null, label).map { it.id })
        assertEquals(listOf("v2"), ttsFilterVoices(voices, "v2", null, label).map { it.id })
        assertEquals(listOf("v2"), ttsFilterVoices(voices, "Engine B", null, label).map { it.id })
        assertEquals(listOf("v2"), ttsFilterVoices(voices, "", "engine-b", label).map { it.id })
        assertTrue(ttsFilterVoices(voices, "nothing", null, label).isEmpty())
    }

    @Test
    fun voiceSortKeepsSelectedFirstThenCurrentEngine() {
        val voices = listOf(
            TtsVoice("v-b", "B", java.util.Locale.US, 300, "engine-b"),
            TtsVoice("v-a", "A", java.util.Locale.US, 300, "engine-a"),
            TtsVoice("v-c", "C", java.util.Locale.US, 300, "engine-a"),
        )

        val sorted = ttsSortVoices(voices, "v-b", "engine-b", "engine-a")

        assertEquals(listOf("v-b", "v-a", "v-c"), sorted.map { it.id })
        assertEquals(listOf("v-a", "v-c", "v-b"), ttsSortVoices(voices, null, null, "engine-a").map { it.id })
    }

    @Test
    fun savedVoiceApplyWritesEngineVoiceSpeedAndPitchTogether() {
        val preset = TtsSavedVoice("Night", "voice-b", 1.75f, 0.85f, "engine-b")
        val applied = TtsSettings(
            engineId = "engine-a",
            voiceId = "voice-a",
            speed = 1f,
            pitch = 1f,
        ).applySavedVoice(preset)

        assertEquals("engine-b", applied.engineId)
        assertEquals("voice-b", applied.voiceId)
        assertEquals(1.75f, applied.speed, 0.001f)
        assertEquals(0.85f, applied.pitch, 0.001f)
    }

    @Test
    fun savedVoiceApplyTargetsOnlyTheEditedSlot() {
        val preset = TtsSavedVoice("Night", "voice-b", 1.2f, 0.9f, "engine-b")
        val settings = TtsSettings(
            voiceId = "voice-a",
            engineId = "engine-a",
            translatedVoiceId = "voice-c",
            translatedEngineId = "engine-a",
        )

        val original = settings.applySavedVoice(preset)
        val translated = settings.applySavedVoice(preset, translated = true)

        // Editing one slot must never relabel the other: a shared voice would
        // make both chips show the same value.
        assertEquals("voice-b", original.voiceId)
        assertEquals("voice-c", original.translatedVoiceId)
        assertEquals("voice-a", translated.voiceId)
        assertEquals("voice-b", translated.translatedVoiceId)
        assertEquals("engine-b", translated.translatedEngineId)
    }

    @Test
    fun aLegacyPresetKeepsTheEditedSlotsEngine() {
        val preset = TtsSavedVoice("Old", "voice-b", 1.2f, 0.9f)

        val translated = TtsSettings(
            translatedEngineId = "engine-c",
            translatedVoiceId = "voice-a",
        ).applySavedVoice(preset, translated = true)

        assertEquals("engine-c", translated.translatedEngineId)
        assertEquals("voice-b", translated.translatedVoiceId)
        // The other slot stays untouched.
        assertEquals(null, translated.voiceId)
    }

    @Test
    fun legacySavedVoiceKeepsTheSelectedEngine() {
        val preset = TtsSavedVoice("Old", "voice-b", 1.2f, 0.9f)

        val applied = TtsSettings(engineId = "engine-a").applySavedVoice(preset)

        assertEquals("engine-a", applied.engineId)
        assertEquals("voice-b", applied.voiceId)
    }

    @Test
    fun savingTheSameVoiceSettingsTwiceDoesNotDuplicate() {
        val preset = TtsSavedVoice("Night", "voice-a", 1.3f, 0.8f, "engine-a")

        val first = ttsAddSavedVoice(emptyList(), preset)
        val second = ttsAddSavedVoice(first, preset.copy(name = "Another name"))

        assertEquals(first, second)
        assertEquals(1, second.size)
        assertEquals(
            1.3f,
            ttsAddSavedVoice(emptyList(), preset.copy(speed = 1.304f)).single().speed,
            0.001f,
        )
    }

    @Test
    fun floatingPlayerAndSavedVoicesPersistTogether() {
        val store = InMemoryTtsSettingsStore()
        val saved = TtsSettings(
            miniPlayerAlways = true,
            floatingPlayer = true,
            floatingBubbleX = 120f,
            floatingBubbleY = 640f,
            floatingPanelX = 24f,
            floatingPanelY = 300f,
            floatingPanelWidth = 340f,
            floatingOpacity = 0.7f,
            floatingShowText = false,
            floatingGlow = "on",
            floatingParagraphMode = "both",
            savedVoices = listOf(TtsSavedVoice("Mine", "voice-a", 1.4f, 0.8f)),
        )

        store.save(saved)

        assertEquals(saved, store.load())
    }

    @Test
    fun floatingAppearanceIsSanitized() {
        val settings = TtsSettings(
            floatingPanelWidth = 5_000f,
            floatingOpacity = -1f,
            floatingGlow = "sometimes",
            floatingParagraphMode = "sideways",
        ).sanitized()

        assertEquals(TtsFloatingMaxWidth, settings.floatingPanelWidth)
        assertEquals(TtsFloatingMinOpacity, settings.floatingOpacity)
        assertEquals("auto", settings.floatingGlow)
        assertEquals("tts", settings.floatingParagraphMode)
    }

    @Test
    fun sanitizedFallsBackForNonFiniteValues() {
        val settings = TtsSettings(
            speed = Float.NaN,
            pitch = Float.POSITIVE_INFINITY,
            floatingBubbleX = Float.NaN,
            floatingBubbleY = Float.POSITIVE_INFINITY,
            floatingPanelX = Float.NEGATIVE_INFINITY,
            floatingPanelY = Float.NaN,
            floatingPanelWidth = Float.NaN,
            floatingOpacity = Float.POSITIVE_INFINITY,
        ).sanitized()

        assertEquals(1f, settings.speed)
        assertEquals(1f, settings.pitch)
        assertEquals(-1f, settings.floatingBubbleX)
        assertEquals(-1f, settings.floatingBubbleY)
        assertEquals(-1f, settings.floatingPanelX)
        assertEquals(-1f, settings.floatingPanelY)
        assertEquals(TtsFloatingDefaultWidth, settings.floatingPanelWidth)
        assertEquals(TtsFloatingDefaultOpacity, settings.floatingOpacity)
    }

    @Test
    fun twoEngineCatalogGroupsAndFiltersForPicker() {
        val google = "com.google.android.tts"
        val rhvoice = "com.github.olga_yakovleva.rhvoice.android"
        val engines = listOf(
            TtsEngineInfo(google, "Speech Services by Google", isDefault = true),
            TtsEngineInfo(rhvoice, "RHVoice", isDefault = false),
        )
        val voices = listOf(
            TtsVoice("google-en", "Aurora", java.util.Locale.US, 400, google),
            TtsVoice(
                "rhvoice-es",
                "Borealis",
                java.util.Locale.forLanguageTag("es-ES"),
                300,
                rhvoice,
            ),
            TtsVoice("rhvoice-en", "Borealis", java.util.Locale.UK, 300, rhvoice),
        )
        val capabilities = TtsCapabilities(
            ready = true,
            voices = voices,
            engines = engines,
            currentEngineId = google,
        )
        val labels = capabilities.engines.associate { it.id to it.label }
        val label: (String?) -> String = { labels[it].orEmpty() }
        val engineOrder = capabilities.engines.map { it.id }

        val groups = ttsGroupVoicesByEngine(
            ttsFilterVoices(capabilities.voices, "", null, label),
            engineOrder,
            engineLabel = label,
        )

        assertEquals(listOf(google, rhvoice), groups.map { it.engineId })
        assertEquals(listOf("google-en"), groups[0].voices.map { it.id })
        assertEquals(listOf("rhvoice-es", "rhvoice-en"), groups[1].voices.map { it.id })
        assertEquals(
            listOf("rhvoice-es"),
            ttsFilterVoices(capabilities.voices, "rhvoice-es", null, label).map { it.id },
        )
        assertEquals(
            listOf("rhvoice-es", "rhvoice-en"),
            ttsFilterVoices(capabilities.voices, "", rhvoice, label).map { it.id },
        )
        assertEquals(
            listOf("rhvoice-es", "rhvoice-en"),
            ttsFilterVoices(capabilities.voices, "RHVoice", null, label).map { it.id },
        )
    }

    @Test
    fun nullEngineDuplicatesFoldIntoNamedEngineGroup() {
        val voices = listOf(
            TtsVoice("v1", "Voice", java.util.Locale.US, 300, engineId = null),
            TtsVoice("v1", "Voice", java.util.Locale.US, 300, "engine-a"),
            TtsVoice("v2", "Other", java.util.Locale.UK, 300, engineId = null),
        )

        val groups = ttsGroupVoicesByEngine(
            voices,
            listOf("engine-a"),
        ) { it ?: "System" }

        assertEquals(listOf("engine-a", null), groups.map { it.engineId })
        assertEquals(listOf("v1"), groups[0].voices.map { it.id })
        assertEquals(listOf("v2"), groups[1].voices.map { it.id })
    }

    @Test
    fun voiceLabelPrefersHumanEngineNameButHidesTechnicalIds() {
        val russian = java.util.Locale.forLanguageTag("ru-RU")
        assertEquals(
            "Anna",
            ttsVoiceLabel(TtsVoice("Anna", "Anna", russian, 300, "rhvoice")),
        )
        assertEquals(
            "Evgeniy-Rus",
            ttsVoiceLabel(TtsVoice("Evgeniy-Rus", "Evgeniy-Rus", russian, 300, "rhvoice")),
        )
        assertEquals(
            russian.displayName,
            ttsVoiceLabel(TtsVoice("ru-RU-language", "ru-RU-language", russian, 400, "google")),
        )
        val us = java.util.Locale.US
        assertEquals(
            us.displayName,
            ttsVoiceLabel(
                TtsVoice(
                    "en-us-x-sfg#female_1-local",
                    "en-us-x-sfg#female_1-local",
                    us,
                    400,
                    "google",
                ),
            ),
        )
    }

    @Test
    fun installedEngineWithoutVoicesStaysVisible() {
        val google = "com.google.android.tts"
        val rhvoice = "com.github.olga_yakovleva.rhvoice.android"
        val label: (String?) -> String = { it.orEmpty() }
        val voices = listOf(
            TtsVoice("google-en", "Aurora", java.util.Locale.US, 400, google),
        )

        val groups = ttsGroupVoicesByEngine(
            voices = voices,
            engineOrder = listOf(google, rhvoice),
            engineLabel = label,
            emptyEngineIds = listOf(google, rhvoice),
        )

        assertEquals(listOf(google, rhvoice), groups.map { it.engineId })
        assertEquals(listOf("google-en"), groups[0].voices.map { it.id })
        assertTrue(groups[1].voices.isEmpty())
    }

    @Test
    fun engineRemovedFromPlatformDisappearsFromGroups() {
        val rhvoice = "com.github.olga_yakovleva.rhvoice.android"
        val label: (String?) -> String = { it.orEmpty() }

        val groups = ttsGroupVoicesByEngine(
            voices = emptyList(),
            engineOrder = listOf(rhvoice),
            engineLabel = label,
            emptyEngineIds = listOf(rhvoice),
        )
        assertTrue(groups.first { it.engineId == rhvoice }.voices.isEmpty())

        // Uninstall: the next refresh no longer reports the engine, so it must
        // not survive from a cached voice/engine list.
        val afterRemoval = ttsGroupVoicesByEngine(
            voices = emptyList(),
            engineOrder = emptyList(),
            engineLabel = label,
            emptyEngineIds = emptyList(),
        )
        assertTrue(afterRemoval.isEmpty())
    }

    @Test
    fun savedVoicesGroupPerEngineAndFilter() {
        val saved = listOf(
            TtsSavedVoice("Night", "a-en", 1.2f, 0.9f, "engine-a"),
            TtsSavedVoice("Day", "b-es", 1f, 1f, "engine-b"),
            TtsSavedVoice("Legacy", "a-en", 1f, 1f, engineId = null),
        )
        val label: (String?) -> String = { it ?: "Unknown engine" }

        val groups = ttsGroupSavedVoices(
            saved,
            listOf("engine-a", "engine-b"),
            label,
        )

        assertEquals(listOf("engine-a", "engine-b", null), groups.map { it.engineId })
        assertEquals(listOf("Night"), groups[0].voices.map { it.name })
        assertEquals(listOf("Day"), groups[1].voices.map { it.name })
        assertEquals(listOf("Legacy"), groups[2].voices.map { it.name })
        assertEquals(
            listOf("Day"),
            ttsFilterSavedVoices(saved, "b-es", null, label).map { it.name },
        )
        assertEquals(
            listOf("Night"),
            ttsFilterSavedVoices(saved, "", "engine-a", label).map { it.name },
        )
    }

    @Test
    fun voiceFavoriteToggleAddsAndRemovesByStableVoiceIdentity() {
        val voice = TtsVoice("a-en", "Aurora", java.util.Locale.US, 400, "engine-a")
        val other = TtsSavedVoice("My voice", "another", 1f, 1f, "engine-a")

        assertFalse(ttsIsVoiceFavorite(listOf(other), voice))

        val added = ttsToggleVoiceFavorite(listOf(other), voice, 1.25f, 0.9f)
        val favorite = added.first { it.voiceId == voice.id }

        assertEquals(2, added.size)
        assertTrue(ttsIsVoiceFavorite(added, voice))
        assertEquals(voice.id, favorite.voiceId)
        assertEquals(voice.engineId, favorite.engineId)
        assertEquals(1.25f, favorite.speed, 0.001f)
        assertEquals(0.9f, favorite.pitch, 0.001f)

        val removed = ttsToggleVoiceFavorite(added, voice, 1.25f, 0.9f)

        assertFalse(ttsIsVoiceFavorite(removed, voice))
        assertEquals(listOf(other), removed)
    }

    @Test
    fun voiceFavoriteSurvivesDisplayNameLanguageChange() {
        val english = TtsVoice("a-en", "Aurora", java.util.Locale.US, 400, "engine-a")
        val saved = TtsSavedVoice(
            name = english.locale.displayName,
            voiceId = english.id,
            speed = 1f,
            pitch = 1f,
            engineId = english.engineId,
        )
        // Same engine + voice id, localized display name changed after the
        // app language switched to Russian.
        val russian = TtsVoice(
            "a-en",
            "Aurora",
            java.util.Locale.forLanguageTag("ru-RU"),
            400,
            "engine-a",
        )

        assertTrue(ttsIsVoiceFavorite(listOf(saved), russian))
        assertTrue(ttsToggleVoiceFavorite(listOf(saved), russian, 1f, 1f).isEmpty())
    }
}
