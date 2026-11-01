package dev.mirutime.core.reader.migration

/**
 * One chapter as seen by [ChaptersMatcher]. [key] is an opaque caller key
 * (chapterId, list index, ...); the matcher never interprets it.
 */
data class ChapterMatchCandidate(
    val key: String,
    val title: String,
    val volume: Int? = null,
)

data class ChapterMatchPair(
    val old: ChapterMatchCandidate,
    val new: ChapterMatchCandidate,
)

data class ChaptersMatchResult(
    val matched: List<ChapterMatchPair>,
    val unmatchedOld: List<ChapterMatchCandidate>,
    val unmatchedNew: List<ChapterMatchCandidate>,
    /** Matched old chapters / total old chapters. 0 when [old] is empty. */
    val score: Float,
)

/**
 * Pure port of NoveLA's chapter matcher: EN/RU-style `Chapter 12`, volume
 * prefixes, East-Asian `第15話`/`15화`, leading chapter numbers and chapter
 * numbers in brackets. Extras (prologue/bonus/side story/SS/author notes) are
 * never matched by number. Duplicate numbers pair in list order; leftovers go
 * to the unmatched lists.
 */
class ChaptersMatcher {
    private companion object {
        val BRACKET_REGEX = Regex("""\[.*?]""")
        val SS_KEYWORD_REGEX = Regex("""(?i)\bss\b""")
        val AUTHOR_NOTE_REGEX = Regex("""(?i)\bauthor'?s?\s+note\b""")
        val CHAPTER_NUM_REGEX = Regex(
            """(?i)(?:chapter|ch\.?\s*|episode|ep\.?\s*|chapitre|capítulo|kapitel|bab)\s*""" +
                """(\d+(?:\.\d+)?)""",
        )
        val ASIAN_CHAPTER_REGEX = Regex("""[第제]?\s*(\d+(?:\.\d+)?)\s*[話화章节]""")
        val LEADING_NUM_REGEX = Regex("""^(\d+(?:\.\d+)?)""")
        val VOLUME_REGEX = Regex(
            """(?i)(?:volume|vol\.?\s*|book|v)\s*(\d+)\s*""" +
                """(?:chapter|ch\.?\s*|episode|ep\.?\s*|話)""",
        )
        val NON_MAIN_KEYWORDS = listOf(
            "side story", "side chapter", "extra", "special", "omake",
            "bonus", "prologue", "epilogue", "afterword",
        )
    }

    fun match(
        old: List<ChapterMatchCandidate>,
        new: List<ChapterMatchCandidate>,
    ): ChaptersMatchResult {
        val oldById = LinkedHashMap<ChapterId, MutableList<ChapterMatchCandidate>>()
        val newById = LinkedHashMap<ChapterId, MutableList<ChapterMatchCandidate>>()
        old.forEach { candidate ->
            val id = candidate.id() ?: return@forEach
            oldById.getOrPut(id) { mutableListOf() }.add(candidate)
        }
        new.forEach { candidate ->
            val id = candidate.id() ?: return@forEach
            newById.getOrPut(id) { mutableListOf() }.add(candidate)
        }

        val matched = mutableListOf<ChapterMatchPair>()
        val matchedOldKeys = HashSet<String>()
        val matchedNewKeys = HashSet<String>()
        for ((id, oldCandidates) in oldById) {
            val newCandidates = newById[id] ?: continue
            val count = minOf(oldCandidates.size, newCandidates.size)
            for (index in 0 until count) {
                val oldCandidate = oldCandidates[index]
                val newCandidate = newCandidates[index]
                matched.add(ChapterMatchPair(oldCandidate, newCandidate))
                matchedOldKeys.add(oldCandidate.key)
                matchedNewKeys.add(newCandidate.key)
            }
        }

        return ChaptersMatchResult(
            matched = matched,
            unmatchedOld = old.filter { it.key !in matchedOldKeys },
            unmatchedNew = new.filter { it.key !in matchedNewKeys },
            score = if (old.isEmpty()) 0f else matched.size.toFloat() / old.size,
        )
    }

    private data class ChapterId(val number: Float, val volume: Int?)

    private fun ChapterMatchCandidate.id(): ChapterId? {
        val number = extractChapterNumber(title) ?: return null
        return ChapterId(number, volume ?: extractVolume(title))
    }

    private fun isMainChapter(title: String): Boolean {
        val clean = title.lowercase().trim().replace(BRACKET_REGEX, "").trim()
        if (NON_MAIN_KEYWORDS.any { clean.startsWith(it) }) return false
        if (SS_KEYWORD_REGEX.containsMatchIn(clean)) return false
        if (AUTHOR_NOTE_REGEX.containsMatchIn(clean)) return false
        return true
    }

    private fun extractChapterNumber(title: String): Float? {
        if (!isMainChapter(title)) return null
        val clean = title.trim().replace(BRACKET_REGEX, "").trim()

        CHAPTER_NUM_REGEX.find(clean)?.let { return it.groupValues[1].toFloatOrNull() }
        ASIAN_CHAPTER_REGEX.find(clean)?.let { return it.groupValues[1].toFloatOrNull() }
        LEADING_NUM_REGEX.find(clean)?.let { return it.groupValues[1].toFloatOrNull() }
        return null
    }

    private fun extractVolume(title: String): Int? {
        val clean = title.trim().replace(BRACKET_REGEX, "").trim()
        return VOLUME_REGEX.find(clean)?.groupValues?.get(1)?.toIntOrNull()
    }
}
