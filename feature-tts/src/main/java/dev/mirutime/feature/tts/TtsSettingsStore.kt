package dev.mirutime.feature.tts

import android.content.Context

/**
 * Durable defaults for the next TTS session. A restored [TtsSession] still wins
 * over these values, so an old bookmark keeps the voice it was spoken with.
 */
interface TtsSettingsStore {
    fun load(): TtsSettings
    fun save(settings: TtsSettings)
}

class InMemoryTtsSettingsStore(
    private var settings: TtsSettings = TtsSettings(),
) : TtsSettingsStore {
    override fun load(): TtsSettings = settings

    override fun save(settings: TtsSettings) {
        this.settings = settings
    }
}

// The same preferences file as the session slot: one reader-owned TTS store,
// and PrefsReaderBackupStore already backs that file up.
class PrefsTtsSettingsStore(context: Context) : TtsSettingsStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsTtsSessionStore.PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): TtsSettings = TtsSettings(
        voiceId = prefs.getString(PrefsTtsSessionStore.KEY_VOICE_ID, null),
        engineId = prefs.getString(PrefsTtsSessionStore.KEY_ENGINE_ID, null),
        translatedVoiceId = prefs.getString(PrefsTtsSessionStore.KEY_TRANSLATED_VOICE_ID, null),
        translatedEngineId = prefs.getString(PrefsTtsSessionStore.KEY_TRANSLATED_ENGINE_ID, null),
        speed = clampTtsSpeed(readFloat(PrefsTtsSessionStore.KEY_SPEED) ?: 1f),
        pitch = clampTtsPitch(readFloat(PrefsTtsSessionStore.KEY_PITCH) ?: 1f),
        autoNextChapter = readBoolean(PrefsTtsSessionStore.KEY_AUTO_NEXT),
        floatingPlayer = readBoolean(PrefsTtsSessionStore.KEY_FLOATING_PLAYER),
        floatingBubbleX = readFloat(PrefsTtsSessionStore.KEY_FLOATING_BUBBLE_X) ?: -1f,
        floatingBubbleY = readFloat(PrefsTtsSessionStore.KEY_FLOATING_BUBBLE_Y) ?: -1f,
        floatingPanelX = readFloat(PrefsTtsSessionStore.KEY_FLOATING_PANEL_X) ?: -1f,
        floatingPanelY = readFloat(PrefsTtsSessionStore.KEY_FLOATING_PANEL_Y) ?: -1f,
        floatingPanelWidth = clampTtsFloatingWidth(
            readFloat(PrefsTtsSessionStore.KEY_FLOATING_PANEL_WIDTH)
                ?: TtsFloatingDefaultWidth,
        ),
        floatingOpacity = clampTtsFloatingOpacity(
            readFloat(PrefsTtsSessionStore.KEY_FLOATING_OPACITY)
                ?: TtsFloatingDefaultOpacity,
        ),
        floatingShowText = readBoolean(PrefsTtsSessionStore.KEY_FLOATING_SHOW_TEXT, default = true),
        floatingGlow = prefs.getString(PrefsTtsSessionStore.KEY_FLOATING_GLOW, null) ?: "auto",
        floatingParagraphMode = prefs.getString(
            PrefsTtsSessionStore.KEY_FLOATING_PARAGRAPH_MODE,
            null,
        ) ?: "tts",
        savedVoices = decodeTtsSavedVoices(
            prefs.getString(PrefsTtsSessionStore.KEY_SAVED_VOICES, null),
        ),
    )

    override fun save(settings: TtsSettings) {
        val sanitized = settings.sanitized()
        prefs.edit()
            .putString(PrefsTtsSessionStore.KEY_VOICE_ID, sanitized.voiceId)
            .putString(PrefsTtsSessionStore.KEY_ENGINE_ID, sanitized.engineId)
            .putString(
                PrefsTtsSessionStore.KEY_TRANSLATED_VOICE_ID,
                sanitized.translatedVoiceId,
            )
            .putString(
                PrefsTtsSessionStore.KEY_TRANSLATED_ENGINE_ID,
                sanitized.translatedEngineId,
            )
            // Strings, not floats: ReaderBackupPrefs is a Map<String, String>
            // and a float here would make a backup snapshot throw.
            .putString(PrefsTtsSessionStore.KEY_SPEED, sanitized.speed.toString())
            .putString(PrefsTtsSessionStore.KEY_PITCH, sanitized.pitch.toString())
            // Strings only: the backup snapshot is a Map<String, String>.
            .putString(
                PrefsTtsSessionStore.KEY_AUTO_NEXT,
                if (sanitized.autoNextChapter) "1" else "0",
            )
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_PLAYER,
                if (sanitized.floatingPlayer) "1" else "0",
            )
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_BUBBLE_X,
                sanitized.floatingBubbleX.toString(),
            )
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_BUBBLE_Y,
                sanitized.floatingBubbleY.toString(),
            )
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_PANEL_X,
                sanitized.floatingPanelX.toString(),
            )
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_PANEL_Y,
                sanitized.floatingPanelY.toString(),
            )
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_PANEL_WIDTH,
                sanitized.floatingPanelWidth.toString(),
            )
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_OPACITY,
                sanitized.floatingOpacity.toString(),
            )
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_SHOW_TEXT,
                if (sanitized.floatingShowText) "1" else "0",
            )
            .putString(PrefsTtsSessionStore.KEY_FLOATING_GLOW, sanitized.floatingGlow)
            .putString(
                PrefsTtsSessionStore.KEY_FLOATING_PARAGRAPH_MODE,
                sanitized.floatingParagraphMode,
            )
            .putString(
                PrefsTtsSessionStore.KEY_SAVED_VOICES,
                encodeTtsSavedVoices(sanitized.savedVoices),
            )
            .apply()
    }

    // Also accepts float values written by an earlier build of this slice.
    private fun readFloat(key: String): Float? = when (val raw = prefs.all[key]) {
        is Float -> raw
        is String -> raw.toFloatOrNull()
        else -> null
    }

    private fun readBoolean(key: String, default: Boolean = false): Boolean =
        when (val raw = prefs.all[key]) {
            is Boolean -> raw
            is String -> raw == "1" || raw.equals("true", ignoreCase = true)
            else -> default
        }
}
