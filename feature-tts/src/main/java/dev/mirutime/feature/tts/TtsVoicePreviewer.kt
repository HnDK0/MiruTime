package dev.mirutime.feature.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Short, non-persistent voice audition. Never changes the playback selection. */
interface TtsVoicePreviewer {
    fun preview(
        voiceId: String?,
        engineId: String?,
        speed: Float,
        pitch: Float,
        text: String,
        onUnavailable: () -> Unit,
    )

    /** Stops the current sample and drops a pending pre-init request. */
    fun stop()

    fun shutdown()
}

object NoopTtsVoicePreviewer : TtsVoicePreviewer {
    override fun preview(
        voiceId: String?,
        engineId: String?,
        speed: Float,
        pitch: Float,
        text: String,
        onUnavailable: () -> Unit,
    ) = Unit

    override fun stop() = Unit

    override fun shutdown() = Unit
}

/**
 * Reusable previewer keeps its last named voice, so a null request must resolve
 * to the engine default (or the first available voice) instead of no-op.
 */
internal fun resolvePreviewVoiceId(
    voiceId: String?,
    defaultVoiceId: String?,
    voiceIds: Collection<String>,
): String? = if (voiceId == null) {
    defaultVoiceId?.takeIf { it in voiceIds } ?: voiceIds.firstOrNull()
} else {
    voiceId.takeIf { it in voiceIds }
}

/**
 * One reusable TextToSpeech instance for auditions. It is not the playback
 * engine and owns no session state; the first tap may wait for async init.
 */
class AndroidTtsVoicePreviewer(context: Context) : TtsVoicePreviewer {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var currentEngine: String? = null
    private var ready = false
    private var requestId = 0
    private var pending: (() -> Unit)? = null

    override fun preview(
        voiceId: String?,
        engineId: String?,
        speed: Float,
        pitch: Float,
        text: String,
        onUnavailable: () -> Unit,
    ) {
        stop()
        val id = ++requestId
        if (tts != null && ready && currentEngine == engineId) {
            speak(voiceId, speed, pitch, text, onUnavailable)
            return
        }
        tts?.shutdown()
        tts = null
        ready = false
        currentEngine = engineId
        pending = { speak(voiceId, speed, pitch, text, onUnavailable) }
        tts = TextToSpeech(
            appContext,
            { status ->
                if (id != requestId) return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit

                            override fun onDone(utteranceId: String?) {
                                if (utteranceId == PREVIEW_UTTERANCE) stopInternal()
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                if (utteranceId == PREVIEW_UTTERANCE) stopInternal()
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                if (utteranceId == PREVIEW_UTTERANCE) stopInternal()
                            }
                        },
                    )
                    pending?.invoke()
                } else {
                    pending = null
                    onUnavailable()
                }
                pending = null
            },
            engineId,
        )
    }

    private fun speak(
        voiceId: String?,
        speed: Float,
        pitch: Float,
        text: String,
        onUnavailable: () -> Unit,
    ) {
        val service = tts ?: return onUnavailable()
        val voiceIds = service.voices?.map { it.name }.orEmpty()
        val resolvedId = resolvePreviewVoiceId(
            voiceId = voiceId,
            defaultVoiceId = service.defaultVoice?.name,
            voiceIds = voiceIds,
        )
        val voice = resolvedId?.let { id ->
            service.voices?.firstOrNull { it.name == id }
        }
        if (voice == null) {
            onUnavailable()
            return
        }
        if (service.setVoice(voice) != TextToSpeech.SUCCESS) {
            onUnavailable()
            return
        }
        service.setSpeechRate(speed)
        service.setPitch(pitch)
        if (service.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), PREVIEW_UTTERANCE) !=
            TextToSpeech.SUCCESS
        ) {
            onUnavailable()
        }
    }

    override fun stop() {
        requestId++
        pending = null
        tts?.stop()
    }

    override fun shutdown() {
        requestId++
        pending = null
        tts?.shutdown()
        tts = null
        ready = false
        currentEngine = null
    }

    private fun stopInternal() {
        pending = null
        tts?.stop()
    }

    private companion object {
        const val PREVIEW_UTTERANCE = "tts-voice-preview"
    }
}
