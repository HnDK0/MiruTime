package dev.mirutime.feature.tts

/** Which language a TTS session speaks. */
enum class TtsLanguage {
    Original,
    Translation;

    companion object {
        /** Compatibility default for callers that only have a translation map. */
        fun from(translations: Map<Int, String>): TtsLanguage =
            if (translations.isNotEmpty()) Translation else Original
    }
}
