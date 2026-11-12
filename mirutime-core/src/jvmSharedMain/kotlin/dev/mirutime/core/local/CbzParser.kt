package dev.mirutime.core.local

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

// ── CBZ parser (port from NoveLA tooling/epub_parser CbzParser) ─────────────
// Replacements vs the ethalon: entries are LISTED via ZipInputStream (no
// ZipFile/temp file) and only the cover image is materialized (a 300MB comic
// would otherwise sit fully expanded in the heap); ComicInfo.xml parsed via
// javax DOM (XmlPullParser is Android-only).

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

internal data class CbzListing(
    val imageNames: List<String>,
    val comicInfo: ByteArray?,
)

// Список entry без байтов картинок: readZipEntries материализовал бы все
// страницы целиком (OMM на комиксах в сотни МБ), используются только имена.
internal fun readCbzListing(bytes: ByteArray): CbzListing {
    var comicInfo: ByteArray? = null
    val imageNames = mutableListOf<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                when {
                    entry.name == "ComicInfo.xml" -> comicInfo = readEntryLimited(zip)
                    isImageEntry(entry.name) -> imageNames += entry.name
                    // Прочие entry пропускаются целиком: имя — единственное,
                    // что CBZ-парсеру от них нужно.
                }
            }
            entry = zip.nextEntry
        }
    }
    return CbzListing(imageNames, comicInfo)
}

internal fun parseCbz(bytes: ByteArray): LocalBook {
    val listing = readCbzListing(bytes)
    val imageNames = listing.imageNames.sortedBy { naturalSortKey(it) }

    val comicInfo = listing.comicInfo?.let(::parseComicInfo)

    // Каждая картинка — отдельная страница-глава; body несёт имя записи.
    val chapters = imageNames.map { name ->
        LocalChapter(
            url = name,
            title = name.substringAfterLast('/').substringBeforeLast('.'),
            body = name,
        )
    }

    // Only this one entry is read: common cover names win, otherwise the first
    // naturally sorted image. A corrupt/oversized cover is not fatal.
    val coverName = coverImageName(imageNames)
    val cover = coverName?.let { name ->
        runCatching { readCbzEntry(bytes, name) }.getOrNull()
    }

    return LocalBook(
        title = comicInfo?.title ?: "Unknown Title",
        description = comicInfo?.summary,
        chapters = chapters,
        coverBytes = cover,
        coverMediaType = coverName?.let(::mediaTypeOf),
    )
}

private fun isImageEntry(name: String): Boolean {
    if (name.endsWith("/")) return false
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in IMAGE_EXTENSIONS
}

private val NaturalDigitsRegex = Regex("\\d+")

// Natural order: page2 before page10 (digit runs zero-padded to a fixed width).
private fun naturalSortKey(name: String): String =
    name.replace(NaturalDigitsRegex) { it.value.padStart(12, '0') }

private fun coverImageName(names: List<String>): String? =
    names.sortedWith(
        compareBy({ !isCoverName(it) }, { naturalSortKey(it) }),
    ).firstOrNull()

private fun isCoverName(name: String): Boolean {
    val stem = name.substringAfterLast('/').substringBeforeLast('.').lowercase()
    return stem == "cover" || stem.startsWith("cover-") || stem.startsWith("cover_")
}

// Second pass over the archive, streaming past every entry and materializing
// only the requested cover.
internal fun readCbzEntry(bytes: ByteArray, name: String): ByteArray? {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name == name) return readEntryLimited(zip)
            entry = zip.nextEntry
        }
    }
    return null
}

private fun mediaTypeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    else -> null
}
