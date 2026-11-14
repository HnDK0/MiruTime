package dev.mirutime.feature.tts

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlin.math.roundToInt

const val TtsMinSpeed = 0.5f
const val TtsMaxSpeed = 5f
const val TtsSpeedStep = 0.01f
const val TtsMinPitch = 0.5f
const val TtsMaxPitch = 3f
const val TtsPitchStep = 0.01f

/**
 * 230dp keeps six 32dp plate controls plus their gaps on one line inside the
 * panel's 8dp padding; below that the action row wrapped to a second line and
 * the plate grew by a whole row.
 */
const val TtsFloatingMinWidth = 230f
const val TtsFloatingMaxWidth = 480f
const val TtsFloatingMinOpacity = 0.35f
const val TtsFloatingDefaultOpacity = 0.95f
const val TtsFloatingDefaultWidth = 300f

/** Screen edge margin for the panel width range; matches MiruSpace.M (12dp). */
const val TtsFloatingRangeMargin = 12f

/** Auto-next is the product default; the store and the data class share it. */
internal const val TtsAutoNextChapterDefault = true

/** NoveLA's three paragraph presentations; same ids keep backups readable. */
val TtsFloatingParagraphModes = setOf("tts", "both", "inverse")
val TtsFloatingGlowModes = setOf("auto", "on", "off")

/** User defaults for the next utterance; a restored session still wins. */
@Immutable
data class TtsSettings(
    val voiceId: String? = null,
    val engineId: String? = null,
    /** Independent voice/engine for reading TRANSLATED text (FR-040..043). */
    val translatedVoiceId: String? = null,
    val translatedEngineId: String? = null,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val autoNextChapter: Boolean = TtsAutoNextChapterDefault,
    /** Keep the mini player in the reader menu even when TTS is not running. */
    val miniPlayerAlways: Boolean = false,
    /** Optional in-reader overlay; off unless the user turns it on. */
    val floatingPlayer: Boolean = false,
    /** Saved top-left positions in dp; negative means "use the default". */
    val floatingBubbleX: Float = -1f,
    val floatingBubbleY: Float = -1f,
    val floatingPanelX: Float = -1f,
    val floatingPanelY: Float = -1f,
    val floatingPanelWidth: Float = TtsFloatingDefaultWidth,
    val floatingOpacity: Float = TtsFloatingDefaultOpacity,
    val floatingShowText: Boolean = true,
    /** auto = glow while playing, on = always, off = never. */
    val floatingGlow: String = "auto",
    /** tts = spoken text, both = spoken + translation, inverse = translation. */
    val floatingParagraphMode: String = "tts",
    /** User presets: voice + rate + pitch. Empty until they save one. */
    val savedVoices: List<TtsSavedVoice> = emptyList(),
) {
    fun sanitized(): TtsSettings = copy(
        voiceId = voiceId?.takeIf { it.isNotBlank() },
        engineId = engineId?.takeIf { it.isNotBlank() },
        translatedVoiceId = translatedVoiceId?.takeIf { it.isNotBlank() },
        translatedEngineId = translatedEngineId?.takeIf { it.isNotBlank() },
        speed = clampTtsSpeed(speed),
        pitch = clampTtsPitch(pitch),
        floatingBubbleX = finiteOrUnset(floatingBubbleX),
        floatingBubbleY = finiteOrUnset(floatingBubbleY),
        floatingPanelX = finiteOrUnset(floatingPanelX),
        floatingPanelY = finiteOrUnset(floatingPanelY),
        floatingPanelWidth = clampTtsFloatingWidth(floatingPanelWidth),
        floatingOpacity = clampTtsFloatingOpacity(floatingOpacity),
        floatingGlow = floatingGlow.takeIf { it in TtsFloatingGlowModes } ?: "auto",
        floatingParagraphMode = floatingParagraphMode
            .takeIf { it in TtsFloatingParagraphModes } ?: "tts",
        savedVoices = savedVoices
            .filter { it.name.isNotBlank() }
            .map { it.copy(speed = clampTtsSpeed(it.speed), pitch = clampTtsPitch(it.pitch)) },
    )
}

private fun finiteOrUnset(value: Float): Float = if (value.isFinite()) value else -1f

/** Voice/engine a session should speak with, by the language it reads. */
data class TtsVoiceSelection(val voiceId: String?, val engineId: String?)

/**
 * A page/session speaks ONE language, so it uses the matching saved slot. An
 * unset translated slot falls back to the original voice; an unset original
 * slot leaves the engine default. Translation presence is decided by the page
 * mode, not by the first block.
 */
fun TtsSettings.voiceForSession(translated: Boolean): TtsVoiceSelection =
    if (translated && translatedVoiceId != null) {
        TtsVoiceSelection(translatedVoiceId, translatedEngineId)
    } else {
        TtsVoiceSelection(voiceId, engineId)
    }

fun clampTtsFloatingWidth(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(TtsFloatingMinWidth, TtsFloatingMaxWidth)
    } else {
        TtsFloatingDefaultWidth
    }

/**
 * Width range for the floating panel, derived from the SCREEN.
 *
 * Never from the panel itself: the panel's own width changes while the user
 * drags the width slider, so a range that follows it produces a positive
 * feedback loop (the max edge chases the value and the handle snaps back).
 * NoveLA derives the same range from `screenWidthDp`.
 */
fun floatingWidthRange(
    screenWidthDp: Float,
    marginDp: Float,
): ClosedFloatingPointRange<Float> {
    val usable = if (screenWidthDp.isFinite()) screenWidthDp - 2f * marginDp else 0f
    val max = usable.coerceAtLeast(TtsFloatingMinWidth + 1f)
    return TtsFloatingMinWidth..max
}

/** Clamps a stored width into [range]; a corrupt value falls back to the default. */
fun clampFloatingPanelWidth(
    width: Float,
    range: ClosedFloatingPointRange<Float>,
): Float = if (width.isFinite()) width.coerceIn(range.start, range.endInclusive) else TtsFloatingDefaultWidth

/**
 * One pinch step. The multiplier applies to the CURRENT panel width (the stored
 * value), not to the clamped display value, so a pinch can also grow past it.
 */
fun pinchPanelWidth(
    current: Float,
    previousSpan: Float,
    span: Float,
    range: ClosedFloatingPointRange<Float>,
): Float = if (previousSpan <= 0f || span <= 0f) {
    clampFloatingPanelWidth(current, range)
} else {
    clampFloatingPanelWidth(current * (span / previousSpan), range)
}

fun clampTtsFloatingOpacity(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(TtsFloatingMinOpacity, 1f)
    } else {
        TtsFloatingDefaultOpacity
    }

/**
 * One named voice preset; [voiceId] null means the engine default voice.
 * [engineId] is null for legacy presets saved before per-engine favorites;
 * those are applied to the currently selected engine.
 */
data class TtsSavedVoice(
    val name: String,
    val voiceId: String?,
    val speed: Float,
    val pitch: Float,
    val engineId: String? = null,
)

// Single pref string: the backup snapshot is Map<String, String>. Fields are
// tab separated, records newline separated; user text is escaped.
// Current format: name, voiceId, engineId, speed, pitch.
// Legacy four-field records (name, voiceId, speed, pitch) are still decoded.
fun encodeTtsSavedVoices(voices: List<TtsSavedVoice>): String =
    voices.joinToString("\n") { voice ->
        listOf(
            escapeTtsField(voice.name),
            escapeTtsField(voice.voiceId.orEmpty()),
            escapeTtsField(voice.engineId.orEmpty()),
            voice.speed.toString(),
            voice.pitch.toString(),
        ).joinToString("\t")
    }

fun decodeTtsSavedVoices(raw: String?): List<TtsSavedVoice> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split("\n").mapNotNull { line ->
        val parts = line.split("\t")
        val legacy = parts.size == 4
        if (!legacy && parts.size != 5) return@mapNotNull null
        val name = unescapeTtsField(parts[0]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val voiceId = unescapeTtsField(parts[1]).takeIf { it.isNotBlank() }
        val engineId = if (legacy) null else unescapeTtsField(parts[2]).takeIf { it.isNotBlank() }
        val speed = parts[if (legacy) 2 else 3].toFloatOrNull()?.let(::clampTtsSpeed) ?: 1f
        val pitch = parts[if (legacy) 3 else 4].toFloatOrNull()?.let(::clampTtsPitch) ?: 1f
        TtsSavedVoice(name, voiceId, speed, pitch, engineId)
    }
}

private fun escapeTtsField(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '%', '\\', '\n', '\t' ->
                append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
            else -> append(ch)
        }
    }
}

private fun unescapeTtsField(value: String): String {
    if ('%' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch == '%' && i + 2 < value.length) {
            val code = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (code != null) {
                out.append(code.toChar())
                i += 3
                continue
            }
        }
        out.append(ch)
        i++
    }
    return out.toString()
}

fun clampTtsSpeed(value: Float): Float =
    if (value.isFinite()) roundTtsStep(value.coerceIn(TtsMinSpeed, TtsMaxSpeed)) else 1f

fun clampTtsPitch(value: Float): Float =
    if (value.isFinite()) roundTtsStep(value.coerceIn(TtsMinPitch, TtsMaxPitch)) else 1f

/** Store/filter values at the slider precision instead of float noise. */
fun roundTtsStep(value: Float, step: Float = TtsSpeedStep): Float =
    if (!value.isFinite() || step <= 0f) value else (value / step).roundToInt() * step

/** Two-decimal display for rate/pitch; storage stays a two-decimal float. */
fun formatTtsRate(value: Float): String = String.format(Locale.US, "%.2f", value)

/** Number of intermediate stops for a Compose/Miru slider over [range]. */
fun ttsSliderSteps(
    range: ClosedFloatingPointRange<Float>,
    step: Float,
): Int {
    if (step <= 0f) return 0
    val intervals = ((range.endInclusive - range.start) / step).roundToInt()
    return (intervals - 1).coerceAtLeast(0)
}

/**
 * Whether the change must stop and re-queue the current chunk. Only the voice
 * slot the session speaks counts, so tuning the other language's voice never
 * interrupts playback; appearance-only changes (floating player, auto-next,
 * favorites) are ignored.
 */
fun ttsPlaybackChanged(
    previous: TtsSettings,
    next: TtsSettings,
    translated: Boolean,
): Boolean {
    val before = previous.sanitized()
    val after = next.sanitized()
    return before.voiceForSession(translated) != after.voiceForSession(translated) ||
        before.speed != after.speed ||
        before.pitch != after.pitch
}

/** Platform voice exposed to the settings UI; [id] is the stable engine name. */
data class TtsVoice(
    val id: String,
    val name: String,
    val locale: Locale,
    val quality: Int,
    val engineId: String?,
    val needsInternet: Boolean = false,
)

/**
 * Catalog of voices from the live engine plus the auxiliary per-engine scans.
 * The live engine wins for its own voices; other engines fill the rest.
 */
fun ttsVoiceCatalog(
    currentEngineId: String?,
    currentVoices: List<TtsVoice>,
    auxiliaryVoices: Map<String, List<TtsVoice>>,
): List<TtsVoice> {
    val seen = HashSet<String>()
    val voices = ArrayList<TtsVoice>(currentVoices.size)
    fun add(voice: TtsVoice) {
        if (seen.add("${voice.engineId}\u0000${voice.id}")) voices += voice
    }
    val order = compareBy<TtsVoice>({ it.locale.displayName }, { it.name })
    currentVoices.sortedWith(order).forEach(::add)
    auxiliaryVoices.toSortedMap().forEach { (engineId, engineVoices) ->
        if (engineId != currentEngineId) engineVoices.sortedWith(order).forEach(::add)
    }
    return voices
}

/**
 * Search and engine filter shared by the picker. The engine label comes from
 * the caller's installed-engine list; when it is unknown the engine id itself
 * is matched, so no engine is ever derived from a voice name.
 */
fun ttsFilterVoices(
    voices: List<TtsVoice>,
    query: String,
    engineId: String?,
    engineLabel: (String?) -> String = { it.orEmpty() },
): List<TtsVoice> {
    val text = query.trim()
    return voices.filter { voice ->
        val engineMatch = engineId == null || voice.engineId == engineId
        val textMatch = text.isEmpty() ||
            voice.name.contains(text, ignoreCase = true) ||
            voice.id.contains(text, ignoreCase = true) ||
            voice.locale.displayName.contains(text, ignoreCase = true) ||
            voice.locale.language.contains(text, ignoreCase = true) ||
            engineLabel(voice.engineId).contains(text, ignoreCase = true)
        engineMatch && textMatch
    }
}

/** Same search and engine filter for the saved-voice shortcuts. */
fun ttsFilterSavedVoices(
    voices: List<TtsSavedVoice>,
    query: String,
    engineId: String?,
    engineLabel: (String?) -> String = { it.orEmpty() },
): List<TtsSavedVoice> {
    val text = query.trim()
    return voices.filter { preset ->
        val engineMatch = engineId == null || preset.engineId == engineId
        val textMatch = text.isEmpty() ||
            preset.name.contains(text, ignoreCase = true) ||
            preset.voiceId?.contains(text, ignoreCase = true) == true ||
            engineLabel(preset.engineId).contains(text, ignoreCase = true)
        engineMatch && textMatch
    }
}

/**
 * Human label for a platform voice. Engines such as Google report technical
 * names ("ru-RU-language", "en-us-x-sfg#female_1-local"); RHVoice reports the
 * speaker ("Anna", "Evgeniy-Rus"). Keep technical ids out of the title, but
 * use a real name when the engine gives one, otherwise every RHVoice row would
 * read the same "Russian (Russia)".
 */
fun ttsVoiceLabel(voice: TtsVoice): String {
    val name = voice.name.trim()
    val technical = name.isEmpty() ||
        name.any { it == '#' || it == '_' } ||
        name.startsWith("language", ignoreCase = true) ||
        name.matches(Regex("^[a-z]{2,3}[-_].*"))
    return if (technical) voice.locale.displayName else name
}

/** Selected voice first, then the active engine; ties stay locale/name sorted. */
fun ttsSortVoices(
    voices: List<TtsVoice>,
    voiceId: String?,
    engineId: String?,
    currentEngineId: String?,
): List<TtsVoice> = voices.sortedWith(
    compareBy<TtsVoice>(
        {
            val selected = voiceId != null &&
                it.id == voiceId &&
                (engineId == null || it.engineId == engineId)
            when {
                selected -> 0
                it.engineId == (engineId ?: currentEngineId) -> 1
                else -> 2
            }
        },
        { it.locale.displayName },
        { it.name },
        { it.engineId.orEmpty() },
        { it.id },
    ),
)

/** One "ALL VOICES" section per installed engine. */
data class TtsVoiceGroup(
    val engineId: String?,
    val label: String,
    val voices: List<TtsVoice>,
)

/** One "SAVED" section per engine; legacy presets without an engine go last. */
data class TtsSavedVoiceGroup(
    val engineId: String?,
    val label: String,
    val voices: List<TtsSavedVoice>,
)

private fun engineRank(engineOrder: List<String>, engineId: String?): Int =
    engineId?.let { id ->
        engineOrder.indexOf(id).takeIf { it >= 0 }
    } ?: Int.MAX_VALUE

/**
 * One group per installed engine. [emptyEngineIds] are engines the platform
 * reports but whose voice scan returned nothing (no language pack installed,
 * init failure); they stay visible with an empty list instead of silently
 * disappearing from the picker.
 */
fun ttsGroupVoicesByEngine(
    voices: List<TtsVoice>,
    engineOrder: List<String>,
    emptyEngineIds: List<String> = emptyList(),
    engineLabel: (String?) -> String,
): List<TtsVoiceGroup> {
    // The live engine can report a null id while its auxiliary scan reports
    // the same voices under the real package. Keep the named group only, so
    // the catalog is not listed twice under "System default" and the engine.
    val namedIds = voices.mapNotNull { voice ->
        voice.engineId?.let { voice.id }
    }.toSet()
    val display = voices.filter { it.engineId != null || it.id !in namedIds }
    val groups = display.groupBy { it.engineId }
    val empty = emptyEngineIds.filter { it !in groups }.associateWith { emptyList<TtsVoice>() }
    return (groups + empty)
        .map { (engineId, engineVoices) ->
            TtsVoiceGroup(engineId, engineLabel(engineId), engineVoices)
        }
        .sortedWith(compareBy({ engineRank(engineOrder, it.engineId) }, { it.label }))
}

fun ttsGroupSavedVoices(
    voices: List<TtsSavedVoice>,
    engineOrder: List<String>,
    engineLabel: (String?) -> String,
): List<TtsSavedVoiceGroup> = voices
    .groupBy { it.engineId }
    .map { (engineId, presets) ->
        TtsSavedVoiceGroup(engineId, engineLabel(engineId), presets)
    }
    .sortedWith(compareBy({ engineRank(engineOrder, it.engineId) }, { it.label }))

/**
 * Applies a saved preset in one settings write, to the slot being edited. A
 * preset saved before per-engine favorites has no engine id; it keeps the
 * engine that is already selected instead of silently jumping to the system
 * default.
 */
fun TtsSettings.applySavedVoice(
    preset: TtsSavedVoice,
    translated: Boolean = false,
): TtsSettings = if (translated) {
    copy(
        translatedVoiceId = preset.voiceId,
        translatedEngineId = preset.engineId ?: translatedEngineId,
        speed = clampTtsSpeed(preset.speed),
        pitch = clampTtsPitch(preset.pitch),
    )
} else {
    copy(
        voiceId = preset.voiceId,
        engineId = preset.engineId ?: engineId,
        speed = clampTtsSpeed(preset.speed),
        pitch = clampTtsPitch(preset.pitch),
    )
}

/**
 * Adds a preset once. Same engine, voice, speed and pitch is the same setting
 * even when the user typed another name, so saving twice cannot duplicate it.
 */
fun ttsAddSavedVoice(
    voices: List<TtsSavedVoice>,
    preset: TtsSavedVoice,
): List<TtsSavedVoice> {
    val candidate = preset.copy(
        name = preset.name.trim(),
        speed = clampTtsSpeed(preset.speed),
        pitch = clampTtsPitch(preset.pitch),
    )
    if (candidate.name.isBlank()) return voices
    val duplicate = voices.any {
        it.engineId == candidate.engineId &&
            it.voiceId == candidate.voiceId &&
            clampTtsSpeed(it.speed) == candidate.speed &&
            clampTtsPitch(it.pitch) == candidate.pitch
    }
    return if (duplicate) voices else voices + candidate
}

/**
 * Quick favorites are auto-named after the voice's locale. The name is only a
 * label: identity is the stable engine id + voice id pair, so switching the
 * app language (which changes [Locale.getDisplayName]) never breaks the star.
 */
fun ttsVoiceFavoriteName(voice: TtsVoice): String = voice.locale.displayName

fun ttsIsVoiceFavorite(favorites: List<TtsSavedVoice>, voice: TtsVoice): Boolean =
    favorites.any {
        it.engineId == voice.engineId && it.voiceId == voice.id
    }

fun ttsToggleVoiceFavorite(
    favorites: List<TtsSavedVoice>,
    voice: TtsVoice,
    speed: Float,
    pitch: Float,
): List<TtsSavedVoice> {
    val name = ttsVoiceFavoriteName(voice)
    val matches = { preset: TtsSavedVoice ->
        preset.engineId == voice.engineId && preset.voiceId == voice.id
    }
    return if (favorites.any(matches)) {
        favorites.filterNot(matches)
    } else {
        ttsAddSavedVoice(
            favorites,
            TtsSavedVoice(
                name = name,
                voiceId = voice.id,
                speed = clampTtsSpeed(speed),
                pitch = clampTtsPitch(pitch),
                engineId = voice.engineId,
            ),
        )
    }
}

data class TtsEngineInfo(
    val id: String,
    val label: String,
    val isDefault: Boolean,
)

data class TtsCapabilities(
    val ready: Boolean = false,
    val voices: List<TtsVoice> = emptyList(),
    val engines: List<TtsEngineInfo> = emptyList(),
    val currentEngineId: String? = null,
    val defaultEngineId: String? = null,
)
