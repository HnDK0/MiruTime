package dev.mirutime.core.local

// ComicInfo.xml metadata shared by CBZ files and image-folder imports.
data class ComicInfoMeta(
    val title: String? = null,
    val series: String? = null,
    val volume: String? = null,
    val summary: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val genres: List<String> = emptyList(),
) {
    // Series + volume is the common "no Title" shape; callers supply a folder
    // name fallback when this returns null.
    val displayTitle: String?
        get() = title?.takeIf { it.isNotBlank() }
            ?: series?.takeIf { it.isNotBlank() }?.let { name ->
                volume?.takeIf { it.isNotBlank() }?.let { "$name Vol $it" } ?: name
            }
}

fun parseComicInfo(raw: ByteArray): ComicInfoMeta? {
    val doc = runCatching { parseXmlDocument(raw) }.getOrNull() ?: return null
    fun field(tag: String): String? = doc.selectFirstTag(tag)
        ?.textContent
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return ComicInfoMeta(
        title = field("Title"),
        series = field("Series"),
        volume = field("Volume"),
        summary = field("Summary"),
        artist = field("Penciller") ?: field("Artist") ?: field("Inker"),
        author = field("Writer"),
        genres = field("Genre").orEmpty()
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct(),
    )
}
