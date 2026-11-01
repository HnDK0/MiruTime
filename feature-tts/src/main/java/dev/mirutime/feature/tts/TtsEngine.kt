package dev.mirutime.feature.tts

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.mirutime.core.log.MiruLog
import java.util.Locale

interface TtsEngineListener {
    fun onReady(ok: Boolean)
    fun onStart(utteranceId: Int)
    fun onDone(utteranceId: Int)
    fun onError()
    fun onRangeStart(utteranceId: Int, start: Int, end: Int)

    /** Auxiliary engines finished scanning their voices. */
    fun onVoicesChanged() = Unit
}

/** Thin seam over the platform engine so controller state is unit-testable. */
interface TtsEngine {
    var listener: TtsEngineListener?
    val isReady: Boolean
    val voices: List<TtsVoice>
    val engines: List<TtsEngineInfo>
    val currentEngineId: String?
    val defaultEngineId: String?

    /** Re-reads the installed engines from the platform (no app restart). */
    fun refreshEngines()

    fun speak(text: String, utteranceId: Int): Boolean
    fun preview(text: String): Boolean
    fun stop()
    fun setVoice(name: String?): Boolean
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)

    /** Recreates the platform engine; the listener reports readiness again. */
    fun selectEngine(engineId: String): Boolean
    fun restart()
    fun shutdown()
}

class AndroidTtsEngine(context: Context) : TtsEngine {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var engineId: String? = null

    // Voices of the installed engines that are not current. TextToSpeech only
    // lists the voices of its own engine, so each auxiliary engine is queried
    // once and shut down again; the catalog keeps the metadata afterwards.
    private val auxiliaryLock = Any()
    private val auxiliaryVoices = HashMap<String, List<TtsVoice>>()
    private val auxiliaryRequested = HashSet<String>()
    private val auxiliaryEngines = HashMap<String, TextToSpeech>()

    override var listener: TtsEngineListener? = null

    override val isReady: Boolean
        get() = ready

    override val defaultEngineId: String?
        get() = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH,
        )?.takeIf { it.isNotBlank() }

    override val currentEngineId: String?
        get() {
            val selected = engineId ?: defaultEngineId
            val installed = tts?.engines.orEmpty().map { it.name }
            return selected?.takeIf { installed.isEmpty() || it in installed }
                ?: installed.firstOrNull()
        }

    override val voices: List<TtsVoice>
        get() {
            val id = currentEngineId
            val current = tts?.voices.orEmpty()
                .filter { it.locale != null }
                .map { voice ->
                    TtsVoice(
                        id = voice.name,
                        name = voice.name,
                        locale = voice.locale,
                        quality = voice.quality,
                        engineId = id,
                        needsInternet = voice.isNetworkConnectionRequired,
                    )
                }
            val auxiliary = synchronized(auxiliaryLock) {
                auxiliaryVoices.mapValues { it.value }
            }
            return ttsVoiceCatalog(id, current, auxiliary)
        }

    override val engines: List<TtsEngineInfo>
        get() {
            val default = defaultEngineId
            return tts?.engines.orEmpty()
                .map { TtsEngineInfo(id = it.name, label = it.label, isDefault = it.name == default) }
                .sortedWith(compareBy({ !it.isDefault }, { it.label }))
        }

    override fun refreshEngines() {
        val platform = tts ?: return
        val installed = platform.engines.orEmpty().map { it.name }
        // Debug-only evidence: which engines the platform actually exposes to
        // this app (package visibility matters on Android 11+).
        MiruLog.d(
            "tts",
            "event=tts_engines count=${installed.size} ids=${installed.joinToString(",")}",
        )
        // The selected engine may have been uninstalled while the app was
        // running; fall back to the system engine instead of keeping a dead
        // package id in settings.
        val selected = engineId
        if (selected != null && installed.isNotEmpty() && selected !in installed) {
            initEngine(null)
            return
        }
        refreshAuxiliaryVoices()
        listener?.onVoicesChanged()
    }

    private fun refreshAuxiliaryVoices() {
        val installed = tts?.engines.orEmpty().map { it.name }
        if (installed.isEmpty()) return
        val current = currentEngineId
        val missing = synchronized(auxiliaryLock) {
            installed.filter { it != current && it !in auxiliaryRequested }
        }
        missing.forEach(::loadAuxiliaryVoices)
        var removed = false
        synchronized(auxiliaryLock) {
            val keep = installed.toSet()
            auxiliaryRequested.retainAll(keep)
            if (auxiliaryVoices.keys.any { it !in keep }) {
                auxiliaryVoices.keys.retainAll(keep)
                removed = true
            }
            val stale = auxiliaryEngines.keys.filter { it !in keep }
            stale.forEach { id -> auxiliaryEngines.remove(id)?.shutdown() }
        }
        if (removed) listener?.onVoicesChanged()
    }

    private fun loadAuxiliaryVoices(engine: String) {
        synchronized(auxiliaryLock) {
            if (!auxiliaryRequested.add(engine)) return
        }
        // TextToSpeech reports readiness asynchronously, so the callback needs
        // a holder instead of a self-reference in the constructor expression.
        val holder = arrayOfNulls<TextToSpeech>(1)
        val instance = TextToSpeech(
            appContext,
            { status ->
                val auxiliary = holder[0]
                val voices = if (status == TextToSpeech.SUCCESS) {
                    auxiliary?.voices.orEmpty()
                        .filter { it.locale != null }
                        .map { voice ->
                            TtsVoice(
                                id = voice.name,
                                name = voice.name,
                                locale = voice.locale,
                                quality = voice.quality,
                                engineId = engine,
                                needsInternet = voice.isNetworkConnectionRequired,
                            )
                        }
                } else {
                    emptyList()
                }
                synchronized(auxiliaryLock) {
                    auxiliaryEngines.remove(engine)
                    if (voices.isNotEmpty()) {
                        auxiliaryVoices[engine] = voices
                    } else {
                        // Allow a later refresh to retry a failed scan.
                        auxiliaryRequested.remove(engine)
                    }
                }
                MiruLog.d(
                    "tts",
                    "event=tts_engine_voices engine=$engine status=$status " +
                        "count=${voices.size} ids=${voices.joinToString(",") { it.id }}",
                )
                auxiliary?.shutdown()
                listener?.onVoicesChanged()
            },
            engine,
        )
        holder[0] = instance
        synchronized(auxiliaryLock) {
            auxiliaryEngines[engine] = instance
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.toIntOrNull()?.let { listener?.onStart(it) }
        }

        override fun onDone(utteranceId: String?) {
            utteranceId?.toIntOrNull()?.let { listener?.onDone(it) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            listener?.onError()
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            utteranceId?.toIntOrNull()?.let { listener?.onRangeStart(it, start, end) }
        }
    }

    init {
        initEngine(null)
    }

    private fun initEngine(enginePackage: String?) {
        ready = false
        tts?.stop()
        tts?.shutdown()
        engineId = enginePackage
        tts = TextToSpeech(
            appContext,
            { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(progress)
                    refreshAuxiliaryVoices()
                }
                listener?.onReady(ready)
            },
            enginePackage,
        )
    }

    override fun speak(text: String, utteranceId: Int): Boolean =
        tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId.toString()) ==
            TextToSpeech.SUCCESS

    // QUEUE_FLUSH: a test utterance must be heard now, not after the book queue.
    override fun preview(text: String): Boolean =
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), PREVIEW_UTTERANCE) ==
            TextToSpeech.SUCCESS

    override fun stop() {
        tts?.stop()
    }

    override fun setVoice(name: String?): Boolean {
        val engine = tts ?: return false
        val voice = if (name == null) {
            engine.defaultVoice ?: engine.voices?.firstOrNull()
        } else {
            engine.voices?.firstOrNull { it.name == name }
        } ?: return false
        return engine.setVoice(voice) == TextToSpeech.SUCCESS
    }

    override fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    override fun selectEngine(engineId: String): Boolean {
        if (engineId.isBlank()) return false
        if (engineId == currentEngineId && ready) return true
        initEngine(engineId)
        return true
    }

    override fun restart() {
        initEngine(engineId)
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        synchronized(auxiliaryLock) {
            auxiliaryEngines.values.forEach { it.shutdown() }
            auxiliaryEngines.clear()
        }
    }

    private companion object {
        const val PREVIEW_UTTERANCE = "tts-preview"
    }
}
