package dev.mirutime.translator.internal

import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import dev.mirutime.translator.TranslationUnit
import kotlin.math.floor

/** JSON keys, ids and punctuation allowance for one item in an LLM request. */
internal const val LLM_UNIT_OVERHEAD = 32

internal data class TextChunk(
    val indices: List<Int>,
    val text: String,
    // Kept so a chunk can be bisected without re-joining units with a
    // different separator than the request used (<br>, <p>, newline).
    val separator: String = "\n",
)

internal fun cappedMaxChars(configured: Int?, hardLimit: Int): Int {
    require(hardLimit > 0)
    return configured?.takeIf { it > 0 }?.coerceAtMost(hardLimit) ?: hardLimit
}

/**
 * Character budget for an LLM request. An explicit positive [configured] value wins;
 * otherwise the provider output limit is converted to target-language characters.
 * CJK output is denser per token, so it gets a smaller budget than Latin output.
 */
internal fun llmMaxChars(
    configured: Int?,
    maxOutputTokens: Int?,
    defaultMaxOutputTokens: Int,
    target: LanguageCode?,
): Int {
    configured?.takeIf { it > 0 }?.let { return it }
    val effectiveMaxOutput = maxOutputTokens?.takeIf { it > 0 } ?: defaultMaxOutputTokens
    val charsPerToken = when (target?.value?.substringBefore('-')?.lowercase()) {
        null -> 2.0
        "zh", "ja", "ko" -> 0.5
        else -> 3.5
    }
    return floor(effectiveMaxOutput * charsPerToken / 1.3 * 0.8)
        .toInt()
        .coerceAtLeast(256)
}

internal data class BudgetSegment(
    val originalId: String,
    val partIds: List<String>,
    val separators: List<String>,
)

/**
 * Expanded units plus the information needed to merge split translations back into
 * the original paragraph ids.
 */
internal data class BudgetPlan(
    val units: List<TranslationUnit>,
    internal val segments: List<BudgetSegment>,
    internal val provider: ProviderId,
) {
    fun restore(translated: Map<String, String>): Map<String, String> {
        val restored = linkedMapOf<String, String>()
        for (segment in segments) {
            if (segment.partIds.size == 1) {
                restored[segment.originalId] = translated[segment.originalId]
                    ?: missing(segment, translated)
                continue
            }
            val joined = buildString {
                segment.partIds.forEachIndexed { index, partId ->
                    if (index > 0) append(segment.separators[index - 1])
                    val part = translated[partId] ?: missing(segment, translated)
                    append(part)
                }
            }
            restored[segment.originalId] = joined
        }
        return restored
    }

    private fun missing(segment: BudgetSegment, translated: Map<String, String>): Nothing =
        throw TranslationFailure(
            TranslationError.Truncated(
                provider = provider,
                expected = segment.partIds.size,
                actual = segment.partIds.count { it in translated },
            ),
        )
}

/**
 * Splits units that exceed [maxChars] at natural boundaries and returns a plan that
 * restores the original ids and separator placement after translation.
 */
internal fun splitToBudget(
    units: List<TranslationUnit>,
    maxChars: Int,
    provider: ProviderId,
): BudgetPlan {
    require(maxChars > 0)
    val usedIds = units.mapTo(mutableSetOf()) { it.id }
    val expanded = mutableListOf<TranslationUnit>()
    val segments = mutableListOf<BudgetSegment>()

    for (unit in units) {
        if (unit.text.length <= maxChars) {
            expanded += unit
            segments += BudgetSegment(unit.id, listOf(unit.id), emptyList())
            continue
        }

        val split = splitText(unit.text, maxChars)
        val partIds = split.parts.mapIndexed { index, part ->
            val partId = if (index == 0) unit.id else uniquePartId(unit.id, index + 1, usedIds)
            expanded += TranslationUnit(partId, part)
            partId
        }
        segments += BudgetSegment(unit.id, partIds, split.separators)
    }

    return BudgetPlan(expanded, segments, provider)
}

private data class SplitText(
    val parts: List<String>,
    val separators: List<String>,
)

private data class SplitPoint(
    val end: Int,
    val next: Int,
    val separator: String,
)

private fun splitText(text: String, maxChars: Int): SplitText {
    val parts = mutableListOf<String>()
    val separators = mutableListOf<String>()
    var remaining = text

    while (remaining.length > maxChars) {
        val point = splitPoint(remaining, maxChars)
        parts += remaining.substring(0, point.end)
        separators += point.separator
        remaining = remaining.substring(point.next)
    }
    if (remaining.isNotEmpty()) parts += remaining
    return SplitText(parts, separators)
}

private fun uniquePartId(originalId: String, partNumber: Int, usedIds: MutableSet<String>): String {
    val base = "$originalId#$partNumber"
    var candidate = base
    var suffix = 1
    while (!usedIds.add(candidate)) {
        candidate = "$base#$suffix"
        suffix++
    }
    return candidate
}

private fun splitPoint(text: String, maxChars: Int): SplitPoint {
    val limit = minOf(maxChars, text.length)
    val min = maxOf(1, maxChars / 2)
    return paragraphBoundary(text, limit, min)
        ?: lineBoundary(text, limit, min)
        ?: sentenceBoundary(text, limit, min)
        ?: whitespaceBoundary(text, limit, min)
        ?: hardSplit(text, limit)
}

private fun paragraphBoundary(text: String, limit: Int, min: Int): SplitPoint? {
    for (index in (limit - 1) downTo min) {
        if (!text[index].isNewline()) continue
        val newlines = newlineRun(text, index)
        val start = newlines.first
        val end = newlines.last + 1
        if (end - start < 2) continue
        if (start == 0 || end >= text.length) continue
        return SplitPoint(start, end, text.substring(start, end))
    }
    return null
}

private fun lineBoundary(text: String, limit: Int, min: Int): SplitPoint? {
    for (index in (limit - 1) downTo min) {
        if (!text[index].isNewline()) continue
        val newlines = newlineRun(text, index)
        val start = newlines.first
        val end = newlines.last + 1
        if (end - start != 1) continue
        if (start == 0 || end >= text.length) continue
        return SplitPoint(start, end, text.substring(start, end))
    }
    return null
}

private fun newlineRun(text: String, index: Int): IntRange {
    var start = index
    while (start > 0 && text[start - 1].isNewline()) start--
    var end = index
    while (end + 1 < text.length && text[end + 1].isNewline()) end++
    return start..end
}

private fun Char.isNewline(): Boolean = this == '\r' || this == '\n'

private fun sentenceBoundary(text: String, limit: Int, min: Int): SplitPoint? {
    for (index in (limit - 1) downTo min) {
        val char = text[index]
        if (char !in SENTENCE_ENDS) continue

        var end = index + 1
        while (end < text.length && text[end] in CLOSING_CHARS) end++
        if (end > limit) continue

        var next = end
        while (next < text.length && text[next].isWhitespace()) next++
        if (next >= text.length) continue

        val cjk = char == '。' || char == '！' || char == '？'
        if (!cjk && next == end) continue
        return SplitPoint(end, next, text.substring(end, next))
    }
    return null
}

private fun whitespaceBoundary(text: String, limit: Int, min: Int): SplitPoint? {
    for (index in (limit - 1) downTo min) {
        if (!text[index].isWhitespace()) continue

        var start = index
        while (start > 0 && text[start - 1].isWhitespace()) start--
        if (start == 0) continue

        var end = index + 1
        while (end < text.length && text[end].isWhitespace()) end++
        if (end >= text.length) continue
        return SplitPoint(start, end, text.substring(start, end))
    }
    return null
}

private fun hardSplit(text: String, limit: Int): SplitPoint {
    var end = limit.coerceAtLeast(1)
    if (
        end < text.length &&
        text[end - 1].isHighSurrogate() &&
        text[end].isLowSurrogate()
    ) {
        end--
        if (end == 0) end = 2
    }
    return SplitPoint(end, end, "")
}

private const val SENTENCE_ENDS = ".!?…。！？"
private const val CLOSING_CHARS = "\"')]}»”’"

internal fun chunkUnits(
    units: List<TranslationUnit>,
    maxChars: Int,
    separator: String,
): List<TextChunk> {
    require(maxChars > 0)
    val chunks = mutableListOf<TextChunk>()
    val indices = mutableListOf<Int>()
    val parts = mutableListOf<String>()
    var length = 0

    fun flush() {
        if (parts.isEmpty()) return
        chunks += TextChunk(indices.toList(), parts.joinToString(separator), separator)
        indices.clear()
        parts.clear()
        length = 0
    }

    for ((index, unit) in units.withIndex()) {
        val extra = if (parts.isEmpty()) {
            unit.text.length
        } else {
            separator.length + unit.text.length
        }
        if (parts.isNotEmpty() && length + extra > maxChars) {
            flush()
        }
        indices += index
        if (parts.isNotEmpty()) length += separator.length
        parts += unit.text
        length += unit.text.length
    }
    flush()
    return chunks
}

/**
 * Groups whole units into requests whose total text length stays within [maxChars].
 * [overheadPerUnit] reserves room for JSON keys, ids and syntax. A single oversized
 * unit still travels alone.
 */
internal fun chunkUnitGroups(
    units: List<TranslationUnit>,
    maxChars: Int,
    overheadPerUnit: Int = 0,
): List<List<TranslationUnit>> {
    require(maxChars > 0)
    require(overheadPerUnit >= 0)
    val groups = mutableListOf<List<TranslationUnit>>()
    val current = mutableListOf<TranslationUnit>()
    var length = 0

    for (unit in units) {
        val cost = unit.text.length + overheadPerUnit
        if (current.isNotEmpty() && length + cost > maxChars) {
            groups += current.toList()
            current.clear()
            length = 0
        }
        current += unit
        length += cost
    }
    if (current.isNotEmpty()) groups += current.toList()
    return groups
}

internal fun validateChunkLines(
    raw: String,
    chunk: TextChunk,
    provider: ProviderId,
    separatorRegex: Regex,
) {
    val actual = raw.split(separatorRegex).count { it.isNotBlank() }
    if (chunk.indices.size == 1 && actual >= 1) return
    if (actual != chunk.indices.size) {
        throw TranslationFailure(
            TranslationError.Truncated(provider, chunk.indices.size, actual),
        )
    }
}

/** True when a failure is a recoverable chunk line-count mismatch. */
internal fun TranslationFailure.isTruncated(): Boolean =
    error is TranslationError.Truncated

/**
 * Applies one chunk's translation. A provider that merges or drops the line
 * separators (common for CJK, where `<br>`/newlines vanish) must not turn every
 * unit into a separate request: that is what made one 8k batch explode into
 * hundreds of tiny calls. Instead the chunk is bisected, so a mismatch costs
 * only O(log n) extra requests around the damaged units and the batch
 * architecture stays intact. Returns unitId -> translation.
 */
internal suspend fun applyChunkWithFallback(
    chunk: TextChunk,
    units: List<TranslationUnit>,
    provider: ProviderId,
    separatorRegex: Regex,
    transformLine: (String) -> String = { it },
    translateChunk: suspend (TextChunk) -> String,
): Map<String, String> {
    val raw = try {
        translateChunk(chunk)
    } catch (failure: TranslationFailure) {
        if (!failure.isTruncated()) throw failure
        return bisectChunk(
            chunk, units, provider, separatorRegex, transformLine, translateChunk,
        ) ?: throw failure
    }
    val lines = raw.split(separatorRegex).map { transformLine(it).trim() }.filter { it.isNotBlank() }
    if (lines.size == chunk.indices.size) {
        return chunk.indices.mapIndexed { index, unitIndex ->
            units[unitIndex].id to lines[index]
        }.toMap()
    }
    if (chunk.indices.size == 1 && lines.isNotEmpty()) {
        return mapOf(units[chunk.indices[0]].id to lines.joinToString("\n"))
    }
    return bisectChunk(
        chunk, units, provider, separatorRegex, transformLine, translateChunk,
    ) ?: throw TranslationFailure(
        TranslationError.Truncated(provider, chunk.indices.size, lines.size),
    )
}

/** Halves an unmatched chunk; null when a single unit still does not map. */
private suspend fun bisectChunk(
    chunk: TextChunk,
    units: List<TranslationUnit>,
    provider: ProviderId,
    separatorRegex: Regex,
    transformLine: (String) -> String,
    translateChunk: suspend (TextChunk) -> String,
): Map<String, String>? {
    if (chunk.indices.size <= 1) return null
    val middle = chunk.indices.size / 2
    val left = applyChunkWithFallback(
        chunk = chunk.slice(0, middle, units),
        units = units,
        provider = provider,
        separatorRegex = separatorRegex,
        transformLine = transformLine,
        translateChunk = translateChunk,
    )
    val right = applyChunkWithFallback(
        chunk = chunk.slice(middle, chunk.indices.size, units),
        units = units,
        provider = provider,
        separatorRegex = separatorRegex,
        transformLine = transformLine,
        translateChunk = translateChunk,
    )
    return left + right
}

private fun TextChunk.slice(from: Int, to: Int, units: List<TranslationUnit>): TextChunk {
    val slice = indices.subList(from, to)
    return TextChunk(slice, slice.joinToString(separator) { units[it].text }, separator)
}
