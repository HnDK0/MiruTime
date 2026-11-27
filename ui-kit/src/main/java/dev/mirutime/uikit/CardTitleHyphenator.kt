package dev.mirutime.uikit

import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import java.text.Normalizer

internal data class WordBreak(
    val head: String,
    val tail: String,
    val compound: Boolean,
)

private val LatinLocaleTags = setOf(
    "en", "de", "fr", "es", "pt", "it", "id", "tr", "vi", "pl",
    "nl", "sv", "cs", "ro", "nb", "nn", "da", "fi", "hu", "sk",
    "hr", "sl", "ca", "gl", "et", "lv", "lt",
)

private val CyrillicVowels = setOf(
    'а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я',
    'і', 'ї', 'є', 'ә', 'ө', 'ү',
)

// Stay glued to the preceding consonant/vowel; breaks occur after them (except apostrophe).
private val CyrillicTrailingModifiers = setOf('й', 'ў', 'ь', 'ъ')
private val CyrillicInseparableModifiers = setOf('\'', '’', '\u02BC')

private val GreekBaseVowels = setOf('α', 'ε', 'η', 'ι', 'ο', 'υ', 'ω')

private val LatinBaseVowels = setOf('a', 'e', 'i', 'o', 'u', 'ı', 'æ', 'œ', 'ø')

private val LatinDigraphOnsets = setOf(
    "ch", "sh", "th", "ph", "gh", "wh", "qu",
    "tr", "br", "cr", "dr", "fr", "gr", "pr",
    "pl", "bl", "cl", "fl", "gl",
)

private val LatinTrigraphOnsets = setOf("sch", "chr", "thr", "shr", "str", "spr", "scr")

private val LatinInseparablePairs = setOf("ck", "ch", "sh", "th", "ph", "gh", "wh", "qu", "ng")

private val BreakAfterPunctuation = setOf(
    '-', '\u2010', '\u2013', '\u2014', '/', '\\', '|', '_', '\u00B7',
)

private enum class TitleScript { Cyrillic, Latin, Greek, Other }

/**
 * Picks the primary BCP-47 language tag matching the script/orthography of
 * [title] so Android's Minikin engine does not stay pinned to an unrelated UI
 * locale (e.g., an `en-US` UI displaying Russian titles).
 */
internal fun cardTitleLocaleTag(
    title: String,
    fallbackLanguageTag: String? = null,
): String? {
    var cyrillicCount = 0
    var latinCount = 0
    var greekCount = 0
    var otherScriptCount = 0

    var hasUkrainianCue = false
    var hasBelarusianCue = false
    var hasVietnameseCue = false
    var hasTurkishCue = false
    var hasPolishCue = false
    var hasGermanCue = false
    var hasFrenchCue = false
    var hasSpanishCue = false
    var hasPortugueseCue = false

    var i = 0
    while (i < title.length) {
        val cp = title.codePointAt(i)
        i += Character.charCount(cp)
        if (!Character.isLetter(cp)) continue
        when {
            isCyrillicCodePoint(cp) -> {
                cyrillicCount++
                when (cp.toChar().lowercaseChar()) {
                    'і', 'ї', 'є', 'ґ' -> hasUkrainianCue = true
                    'ў' -> hasBelarusianCue = true
                }
            }
            isGreekCodePoint(cp) -> greekCount++
            isLatinCodePoint(cp) -> {
                latinCount++
                when (cp) {
                    in 0x1EA0..0x1EF9, 'ơ'.code, 'Ơ'.code, 'ư'.code, 'Ư'.code, 'đ'.code, 'Đ'.code ->
                        hasVietnameseCue = true
                    'ğ'.code, 'Ğ'.code, 'ş'.code, 'Ş'.code, 'ı'.code, 'İ'.code ->
                        hasTurkishCue = true
                    'ą'.code, 'Ą'.code, 'ć'.code, 'Ć'.code, 'ę'.code, 'Ę'.code,
                    'ł'.code, 'Ł'.code, 'ń'.code, 'Ń'.code, 'ś'.code, 'Ś'.code,
                    'ź'.code, 'Ź'.code, 'ż'.code, 'Ż'.code ->
                        hasPolishCue = true
                    'ß'.code, 'ẞ'.code -> hasGermanCue = true
                    'œ'.code, 'Œ'.code -> hasFrenchCue = true
                    'ñ'.code, 'Ñ'.code -> hasSpanishCue = true
                    'ã'.code, 'Ã'.code, 'õ'.code, 'Õ'.code -> hasPortugueseCue = true
                }
            }
            else -> otherScriptCount++
        }
    }

    val normalizedFallback = fallbackLanguageTag
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val fallbackPrimary = normalizedFallback
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.lowercase()

    if (cyrillicCount == 0 && latinCount == 0 && greekCount == 0) {
        return normalizedFallback
    }

    return when {
        cyrillicCount >= latinCount && cyrillicCount >= greekCount && cyrillicCount >= otherScriptCount -> when {
            hasUkrainianCue -> "uk"
            hasBelarusianCue -> "be"
            fallbackPrimary in setOf("ru", "uk", "be", "bg", "sr", "mk", "kk") -> normalizedFallback
            else -> "ru"
        }
        greekCount >= latinCount && greekCount > cyrillicCount && greekCount >= otherScriptCount -> "el"
        latinCount > 0 && latinCount >= otherScriptCount -> when {
            hasVietnameseCue -> "vi"
            hasTurkishCue -> "tr"
            hasPolishCue -> "pl"
            hasGermanCue -> "de"
            hasFrenchCue -> "fr"
            hasSpanishCue -> "es"
            hasPortugueseCue -> "pt"
            fallbackPrimary in LatinLocaleTags -> normalizedFallback
            else -> "en"
        }
        else -> normalizedFallback
    }
}

internal fun cardTitleLocaleList(
    title: String,
    fallback: LocaleList? = null,
    appLocaleTag: String? = null,
): LocaleList? {
    val baseList = fallback ?: LocaleList.current
    val firstTag = baseList.localeList.firstOrNull()?.toLanguageTag() ?: appLocaleTag
    val preferredTag = cardTitleLocaleTag(title, firstTag) ?: return fallback
    val preferredLocale = Locale(preferredTag)
    val preferredPrimary = preferredTag.substringBefore('-').lowercase()
    val merged = buildList {
        add(preferredLocale)
        baseList.localeList.forEach { existing ->
            val existingTag = existing.toLanguageTag()
            if (!existingTag.equals(preferredTag, ignoreCase = true) &&
                existingTag.substringBefore('-').lowercase() != preferredPrimary
            ) {
                add(existing)
            }
        }
    }
    return LocaleList(merged)
}

/**
 * Formats [title] across up to [maxLines] lines for a container of width
 * [maxWidthPx] using [measureWidthPx], eliminating awkward 1-word first lines
 * via greedy cross-script syllable hyphenation and compound breaks.
 */
internal fun formatCardTitleLines(
    title: String,
    maxLines: Int,
    maxWidthPx: Int,
    measureWidthPx: (String) -> Int,
): String {
    val normalized = normalizeSpaces(title)
    if (normalized.isEmpty() || maxLines <= 1 || maxWidthPx <= 0) return normalized

    // Leave a 2px guard for subpixel float advances between single-line measure and MultiParagraph.
    val fitWidthPx = (maxWidthPx - 2).coerceAtLeast(1)
    if (measureWidthPx(normalized) <= fitWidthPx) return normalized

    val spaceWidth = measureWidthPx(" ")
    val queue = ArrayDeque(normalized.split(' '))
    val lines = ArrayList<String>(maxLines)

    for (lineIndex in 0 until maxLines - 1) {
        if (queue.isEmpty()) break
        val current = StringBuilder()
        var currentWidth = 0

        while (queue.isNotEmpty()) {
            val token = queue.first()
            val tokenWidth = measureWidthPx(token)
            // Line width is the sum of token advances: shaping a joined line adds no
            // kerning across spaces, so this avoids re-shaping the growing line once
            // per added word — the most expensive part of a card measure pass.
            val candidateWidth = if (current.isEmpty()) tokenWidth else currentWidth + spaceWidth + tokenWidth
            if (candidateWidth <= fitWidthPx) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(token)
                currentWidth = candidateWidth
                queue.removeFirst()
            } else {
                break
            }
        }

        if (queue.isEmpty()) {
            lines.add(current.toString())
            break
        }

        val currentLine = current.toString()
        val remainingFitsNextSingleLine =
            lineIndex == maxLines - 2 &&
                remainingWidth(queue, spaceWidth, measureWidthPx) <= fitWidthPx

        val shouldBreakNextToken = when {
            currentLine.isEmpty() -> true
            remainingFitsNextSingleLine -> currentWidth < (maxWidthPx * 0.55f).toInt()
            else -> currentWidth < (maxWidthPx * 0.85f).toInt()
        }

        var splitApplied = false
        if (shouldBreakNextToken) {
            val overflowingToken = queue.first()
            val linePrefixWidth = if (currentLine.isEmpty()) 0 else currentWidth + spaceWidth
            val bestBreak = findLongestFittingBreak(
                token = overflowingToken,
                linePrefixWidth = linePrefixWidth,
                fitWidthPx = fitWidthPx,
                measureWidthPx = measureWidthPx,
            )
            if (bestBreak != null) {
                lines.add(if (currentLine.isEmpty()) bestBreak.head else "$currentLine ${bestBreak.head}")
                queue.removeFirst()
                queue.addFirst(bestBreak.tail)
                splitApplied = true
            }
        }

        if (!splitApplied) {
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine)
            } else {
                // Single unbreakable token (e.g., long CJK title): let Android wrap & ellipsize natively.
                lines.add(queue.joinToString(" "))
                queue.clear()
                break
            }
        }
    }

    if (queue.isNotEmpty()) {
        lines.add(queue.joinToString(" "))
    }

    return lines.joinToString("\n")
}

private fun remainingWidth(
    queue: ArrayDeque<String>,
    spaceWidth: Int,
    measureWidthPx: (String) -> Int,
): Int {
    var width = 0
    var first = true
    for (token in queue) {
        if (!first) width += spaceWidth
        width += measureWidthPx(token)
        first = false
    }
    return width
}

private fun findLongestFittingBreak(
    token: String,
    linePrefixWidth: Int,
    fitWidthPx: Int,
    measureWidthPx: (String) -> Int,
): WordBreak? {
    val candidates = wordBreakCandidates(token)
    if (candidates.isEmpty()) return null
    for (candidate in candidates) {
        if (linePrefixWidth + measureWidthPx(candidate.head) <= fitWidthPx) {
            return candidate
        }
    }
    return null
}

/**
 * Produces break candidates `(head, tail)` for [token], sorted from longest
 * `head` to shortest `head`.
 */
internal fun wordBreakCandidates(token: String): List<WordBreak> {
    val nfc = Normalizer.normalize(token, Normalizer.Form.NFC)
    if (nfc.length < 2) return emptyList()

    val clusters = splitClusters(nfc)
    if (clusters.size < 2) return emptyList()

    val compoundSplitIndices = findCompoundSplitIndices(clusters)
    val results = LinkedHashMap<Pair<Int, Boolean>, WordBreak>()

    // Direct compound splits (e.g. "Re:Zero" -> "Re:" + "Zero", "Fate/Grand" -> "Fate/" + "Grand").
    for (splitIdx in compoundSplitIndices) {
        val head = concatClusters(clusters, 0, splitIdx)
        val tail = concatClusters(clusters, splitIdx, clusters.size)
        if (head.isNotEmpty() && tail.isNotEmpty()) {
            results[head.length to false] = WordBreak(head = head, tail = tail, compound = true)
        }
    }

    // Syllabify each sub-segment bounded by compound split points.
    var segStart = 0
    val boundaries = compoundSplitIndices + clusters.size
    for (segEnd in boundaries) {
        val segClusters = clusters.subList(segStart, segEnd)
        val prefix = concatClusters(clusters, 0, segStart)
        val suffix = concatClusters(clusters, segEnd, clusters.size)
        // If this segment ends at a compound separator (i.e. segEnd < clusters.size), strip it before syllabifying.
        val hasTrailingCompoundSep = segEnd < clusters.size && segClusters.size > 1
        val bodyClusters = if (hasTrailingCompoundSep) {
            segClusters.subList(0, segClusters.size - 1)
        } else {
            segClusters
        }
        val extraAfterCore = if (hasTrailingCompoundSep) {
            segClusters.last() + suffix
        } else {
            suffix
        }

        if (bodyClusters.size >= 4) {
            val subBreaks = syllabifySegmentClusters(bodyClusters)
            for (sub in subBreaks) {
                val head = prefix + sub.head
                val tail = sub.tail + extraAfterCore
                if (head.isNotEmpty() && tail.isNotEmpty()) {
                    results.putIfAbsent(head.length to true, WordBreak(head = head, tail = tail, compound = false))
                }
            }
        }
        segStart = segEnd
    }

    return results.values.sortedWith(
        compareByDescending<WordBreak> { it.head.length }
            .thenByDescending { it.compound },
    )
}

private fun findCompoundSplitIndices(clusters: List<String>): List<Int> {
    if (clusters.size < 2) return emptyList()
    val splits = mutableListOf<Int>()
    for (i in 0 until clusters.size - 1) {
        val ch = clusters[i].firstOrNull() ?: continue
        val next = clusters[i + 1]
        if (ch == '\u2011') continue // Non-breaking hyphen never splits.

        val isBreakAfterChar = when {
            ch in BreakAfterPunctuation -> {
                // In runs like "--", only split after the final separator right before content.
                val nextCh = next.firstOrNull()
                nextCh == null || (nextCh !in BreakAfterPunctuation && nextCh != ':' && nextCh != ';')
            }
            ch == ':' || ch == ';' -> {
                val prevIsContent = i > 0 && isClusterLetterOrDigit(clusters[i - 1])
                val nextIsContent = isClusterLetterOrDigit(next)
                prevIsContent && nextIsContent
            }
            else -> false
        }

        if (isBreakAfterChar && isClusterLetterOrDigit(next)) {
            splits.add(i + 1)
        }
    }
    return splits
}

private fun syllabifySegmentClusters(clusters: List<String>): List<WordBreak> {
    var start = 0
    var end = clusters.size

    while (start < end && !isClusterLetterOrDigit(clusters[start])) {
        start++
    }
    while (end > start && !isClusterLetterOrDigit(clusters[end - 1])) {
        end--
    }
    if (end - start < 4) return emptyList()

    // Keep English trailing possessives ('s / ’s) with the final syllable.
    if (end - start > 3 &&
        isApostrophe(clusters[end - 2]) &&
        (clusters[end - 1].equals("s", ignoreCase = true)) &&
        isLatinCodePoint(clusters[end - 3].codePointAt(0))
    ) {
        end -= 2
    }

    // Keep French/Italian 1-2 letter elision prefixes (L', d', qu') glued to the core onset.
    val leadElisionShift = findLeadingElisionLength(clusters, start, end)
    val leadingRaw = concatClusters(clusters, 0, start + leadElisionShift)
    start += leadElisionShift
    val trailingRaw = concatClusters(clusters, end, clusters.size)

    val core = clusters.subList(start, end)
    if (core.size < 4) return emptyList()

    val script = detectUniformAlphabeticScript(core) ?: return emptyList()
    val splitPositions = when (script) {
        TitleScript.Cyrillic -> findCyrillicSplitPositions(core)
        TitleScript.Latin -> findLatinSplitPositions(core)
        TitleScript.Greek -> findGreekSplitPositions(core)
        TitleScript.Other -> emptyList()
    }
    if (splitPositions.isEmpty()) return emptyList()

    return splitPositions.map { splitAt ->
        WordBreak(
            head = leadingRaw + concatClusters(core, 0, splitAt) + "-",
            tail = concatClusters(core, splitAt, core.size) + trailingRaw,
            compound = false,
        )
    }
}

private fun findLeadingElisionLength(clusters: List<String>, start: Int, end: Int): Int {
    for (offset in 1..2) {
        val aposIdx = start + offset
        if (aposIdx < end - 3 && isApostrophe(clusters[aposIdx])) {
            val allLatinBefore = (start until aposIdx).all { isLatinCodePoint(clusters[it].codePointAt(0)) }
            val nextIsLatin = isLatinCodePoint(clusters[aposIdx + 1].codePointAt(0))
            if (allLatinBefore && nextIsLatin) return offset + 1
        }
    }
    return 0
}

private fun detectUniformAlphabeticScript(core: List<String>): TitleScript? {
    var script: TitleScript? = null
    for (cluster in core) {
        val cp = cluster.codePointAt(0)
        if (Character.isDigit(cp)) return null
        if (isApostrophe(cluster)) {
            if (script == null) return null
            continue
        }
        if (!Character.isLetter(cp)) return null
        val itemScript = when {
            isCyrillicCodePoint(cp) -> TitleScript.Cyrillic
            isLatinCodePoint(cp) -> TitleScript.Latin
            isGreekCodePoint(cp) -> TitleScript.Greek
            else -> return null
        }
        if (script == null) {
            script = itemScript
        } else if (script != itemScript) {
            return null
        }
    }
    return script
}

private fun findCyrillicSplitPositions(core: List<String>): List<Int> {
    val n = core.size
    val minPrefix = 2
    val minSuffix = 2
    val vowelIndices = ArrayList<Int>(n)
    for (i in 0 until n) {
        val base = clusterBaseLower(core[i])
        if (base in CyrillicVowels) {
            vowelIndices.add(i)
        }
    }
    if (vowelIndices.size < 2) return emptyList()

    val splits = mutableListOf<Int>()
    for (vIdx in 0 until vowelIndices.size - 1) {
        val v1 = vowelIndices[vIdx]
        val v2 = vowelIndices[vIdx + 1]
        val k = v2 - v1 - 1

        val splitAt: Int = when {
            k == 0 -> v2
            // Ukrainian/word-internal apostrophe stays inseparably inside the syllable unless another gap exists.
            (v1 + 1 until v2).any { core[it].firstOrNull() in CyrillicInseparableModifiers } -> -1
            else -> {
                val modOffset = (v1 + 1 until v2).indexOfFirst {
                    core[it].firstOrNull() in CyrillicTrailingModifiers
                }
                when {
                    modOffset >= 0 -> v1 + 1 + modOffset + 1
                    k == 1 -> v1 + 1
                    else -> v1 + 2
                }
            }
        }

        if (splitAt in minPrefix..(n - minSuffix)) {
            // Never start the next line with a modifier sign.
            val tailFirst = core[splitAt].firstOrNull()
            if (tailFirst !in CyrillicTrailingModifiers && tailFirst !in CyrillicInseparableModifiers) {
                splits.add(splitAt)
            }
        }
    }
    return splits.distinct().sortedDescending()
}

private fun findGreekSplitPositions(core: List<String>): List<Int> {
    val n = core.size
    val minPrefix = 2
    val minSuffix = 2
    val isVowel = BooleanArray(n) { i -> latinOrGreekBaseLower(core[i]) in GreekBaseVowels }

    val nuclei = buildNuclei(isVowel)
    if (nuclei.size < 2) return emptyList()

    val splits = mutableListOf<Int>()
    for (idx in 0 until nuclei.size - 1) {
        val prevEnd = nuclei[idx].last
        val nextStart = nuclei[idx + 1].first
        val gap = nextStart - prevEnd - 1
        val splitAt = when {
            gap <= 0 -> nextStart
            gap == 1 -> prevEnd + 1
            else -> prevEnd + 2
        }
        if (splitAt in minPrefix..(n - minSuffix)) {
            splits.add(splitAt)
        }
    }
    return splits.distinct().sortedDescending()
}

private fun findLatinSplitPositions(core: List<String>): List<Int> {
    val n = core.size
    val minPrefix = 2
    val minSuffix = 3
    if (n < minPrefix + minSuffix) return emptyList()

    val bases = CharArray(n) { latinBaseLower(core[it]) }
    val isVowel = BooleanArray(n)

    for (i in 0 until n) {
        val ch = bases[i]
        isVowel[i] = when {
            ch in LatinBaseVowels -> {
                // `u` inside `qu` before a vowel is part of the consonant onset (`con-quest`, `re-qui-em`).
                !(ch == 'u' && i > 0 && bases[i - 1] == 'q' &&
                    i + 1 < n && bases[i + 1] in LatinBaseVowels)
            }
            ch == 'y' -> {
                val prevIsVowel = i > 0 && isVowel[i - 1]
                val nextIsVowel = i + 1 < n && bases[i + 1] in LatinBaseVowels
                // `y` acts as consonant at word start before vowel (`young`) or as intervocalic glide (`beyond`, `royal`).
                !((i == 0 && nextIsVowel) || (prevIsVowel && nextIsVowel))
            }
            else -> false
        }
    }

    // Suppress silent trailing unaccented English/French `-e`, `-es`, `-ed` (not `-ted`/`-ded`),
    // provided at least one pronounced vowel nucleus stays ahead of it.
    suppressSilentTerminalE(core, bases, isVowel)

    val nuclei = buildNuclei(isVowel)
    if (nuclei.size < 2) return emptyList()

    val splits = mutableListOf<Int>()
    for (idx in 0 until nuclei.size - 1) {
        val prevEnd = nuclei[idx].last
        val nextStart = nuclei[idx + 1].first
        val cStart = prevEnd + 1
        val cEnd = nextStart - 1
        val k = cEnd - cStart + 1

        val splitAt: Int = when {
            k <= 0 -> nextStart
            k == 1 -> {
                if (bases[cStart] == 'x') cStart + 1 else cStart
            }
            k == 2 -> {
                val pair = "${bases[cStart]}${bases[cStart + 1]}"
                when {
                    bases[cStart] == bases[cStart + 1] -> cStart + 1
                    pair == "ck" -> cStart + 2
                    pair in LatinDigraphOnsets && cStart >= minPrefix -> cStart
                    else -> cStart + 1
                }
            }
            else -> {
                var pos = chooseLatinMultiConsonantSplit(bases, cStart, cEnd)
                if (pos == cStart + 1 && cStart + 1 <= cEnd) {
                    val cutPair = "${bases[cStart]}${bases[cStart + 1]}"
                    if (cutPair in LatinInseparablePairs && cStart + 2 <= cEnd) {
                        pos = cStart + 2
                    }
                }
                pos
            }
        }

        if (splitAt in minPrefix..(n - minSuffix)) {
            splits.add(splitAt)
        }
    }
    return splits.distinct().sortedDescending()
}

private fun chooseLatinMultiConsonantSplit(
    bases: CharArray,
    cStart: Int,
    cEnd: Int,
): Int {
    val len = cEnd - cStart + 1
    if (len >= 3) {
        val tail3 = "${bases[cEnd - 2]}${bases[cEnd - 1]}${bases[cEnd]}"
        if (tail3 in LatinTrigraphOnsets && cEnd - 2 > cStart) {
            return cEnd - 2
        }
    }
    if (len >= 2) {
        val tail2 = "${bases[cEnd - 1]}${bases[cEnd]}"
        if (tail2 in LatinDigraphOnsets && cEnd - 1 > cStart) {
            return cEnd - 1
        }
    }
    return cStart + 1
}

private fun suppressSilentTerminalE(
    core: List<String>,
    bases: CharArray,
    isVowel: BooleanArray,
) {
    val n = core.size
    val silentIdx = when {
        n >= 4 && core[n - 1].equals("e", ignoreCase = true) -> n - 1
        n >= 5 && core[n - 1].equals("s", ignoreCase = true) && core[n - 2].equals("e", ignoreCase = true) -> n - 2
        n >= 5 && core[n - 1].equals("d", ignoreCase = true) && core[n - 2].equals("e", ignoreCase = true) &&
            bases[n - 3] != 't' && bases[n - 3] != 'd' -> n - 2
        else -> return
    }
    if (!isVowel[silentIdx]) return
    val hasEarlierVowel = (0 until silentIdx - 1).any { isVowel[it] }
    if (hasEarlierVowel) {
        isVowel[silentIdx] = false
    }
}

private fun buildNuclei(isVowel: BooleanArray): List<IntRange> {
    val nuclei = mutableListOf<IntRange>()
    var i = 0
    while (i < isVowel.size) {
        if (isVowel[i]) {
            val start = i
            while (i + 1 < isVowel.size && isVowel[i + 1]) i++
            nuclei.add(start..i)
        }
        i++
    }
    return nuclei
}

private fun splitClusters(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val start = i
        i += Character.charCount(text.codePointAt(i))
        while (i < text.length) {
            val nextCp = text.codePointAt(i)
            val type = Character.getType(nextCp)
            val isCombiningOrVs =
                type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt() ||
                    nextCp in 0xFE00..0xFE0F ||
                    nextCp in 0xE0100..0xE01EF
            if (!isCombiningOrVs) break
            i += Character.charCount(nextCp)
        }
        out.add(text.substring(start, i))
    }
    return out
}

private fun concatClusters(clusters: List<String>, from: Int, to: Int): String {
    if (from >= to) return ""
    val sb = StringBuilder()
    for (idx in from until to) sb.append(clusters[idx])
    return sb.toString()
}

private fun isClusterLetterOrDigit(cluster: String): Boolean {
    val cp = cluster.codePointAt(0)
    return Character.isLetterOrDigit(cp)
}

private fun isApostrophe(cluster: String): Boolean =
    cluster.length == 1 && (cluster[0] == '\'' || cluster[0] == '’' || cluster[0] == '\u02BC')

private fun clusterBaseLower(cluster: String): Char {
    val cp = cluster.codePointAt(0)
    val ch = if (Character.isBmpCodePoint(cp)) cp.toChar().lowercaseChar() else return '\u0000'
    return when (ch) {
        'й', 'ў', 'ь', 'ъ', 'ё', 'і', 'ї', 'є', 'ә', 'ө', 'ү' -> ch
        else -> {
            val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            decomposed.firstOrNull()?.lowercaseChar() ?: ch
        }
    }
}

private fun latinBaseLower(cluster: String): Char {
    val cp = cluster.codePointAt(0)
    val ch = if (Character.isBmpCodePoint(cp)) cp.toChar().lowercaseChar() else return '\u0000'
    return when (ch) {
        'ı', 'æ', 'œ', 'ø', 'ß', 'ð', 'þ', 'đ', 'ł' -> ch
        else -> {
            val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            decomposed.firstOrNull()?.lowercaseChar() ?: ch
        }
    }
}

private fun latinOrGreekBaseLower(cluster: String): Char {
    val cp = cluster.codePointAt(0)
    val ch = if (Character.isBmpCodePoint(cp)) cp.toChar().lowercaseChar() else return '\u0000'
    val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
    return decomposed.firstOrNull()?.lowercaseChar() ?: ch
}

private fun isCyrillicCodePoint(cp: Int): Boolean =
    cp in 0x0400..0x052F || cp in 0x2DE0..0x2DFF || cp in 0xA640..0xA69F

private fun isGreekCodePoint(cp: Int): Boolean =
    cp in 0x0370..0x03FF || cp in 0x1F00..0x1FFF

private fun isLatinCodePoint(cp: Int): Boolean =
    cp in 0x0041..0x005A ||
        cp in 0x0061..0x007A ||
        cp in 0x00C0..0x024F ||
        cp in 0x1E00..0x1EFF ||
        cp in 0x2C60..0x2C7F ||
        cp in 0xA720..0xA7FF

private fun normalizeSpaces(text: String): String {
    if (text.isBlank()) return ""
    val sb = StringBuilder(text.length)
    var prevWasSpace = true
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (Character.isWhitespace(cp)) {
            if (!prevWasSpace) {
                sb.append(' ')
                prevWasSpace = true
            }
        } else {
            sb.appendCodePoint(cp)
            prevWasSpace = false
        }
        i += Character.charCount(cp)
    }
    if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
        sb.deleteCharAt(sb.length - 1)
    }
    return sb.toString()
}
