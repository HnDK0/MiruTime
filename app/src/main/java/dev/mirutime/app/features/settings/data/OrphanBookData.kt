package dev.mirutime.app.features.settings.data

import java.io.File

/** Covers/downloads/local-book folders that no longer belong to a live row. */
internal data class OrphanBookData(
    val dirCount: Int = 0,
    val bytes: Long = 0,
) {
    companion object {
        val Empty = OrphanBookData()
    }
}

/** Valid entry names per local install (`<sha>` and `<sha>.epub`/`.cbz`). */
internal fun localValidNames(copyPaths: List<String>): Map<String, Set<String>> {
    val names = LinkedHashMap<String, MutableSet<String>>()
    copyPaths.forEach { path ->
        val relative = path.substringAfter("local_books/", "")
        val parts = relative.split('/')
        if (parts.size < 2) return@forEach
        val install = parts[0].takeIf { it.isNotBlank() } ?: return@forEach
        val name = parts[1].takeIf { it.isNotBlank() } ?: return@forEach
        val valid = names.getOrPut(install) { linkedSetOf() }
        valid += name
        valid += name.substringBeforeLast('.')
    }
    return names
}

internal fun inspectOrphanBookData(
    filesDir: File,
    validBookIds: Set<Long>,
    validCoverIds: Set<Long>,
    validLocalNames: Map<String, Set<String>> = emptyMap(),
    now: Long = System.currentTimeMillis(),
): OrphanBookData {
    val entries = orphanBookEntries(
        filesDir, validBookIds, validCoverIds, validLocalNames, now,
    )
    return OrphanBookData(
        dirCount = entries.size,
        bytes = entries.sumOf { it.bytes() },
    )
}

internal fun sweepOrphanBookData(
    filesDir: File,
    validBookIds: Set<Long>,
    validCoverIds: Set<Long>,
    validLocalNames: Map<String, Set<String>> = emptyMap(),
    now: Long = System.currentTimeMillis(),
): OrphanBookData {
    val entries = orphanBookEntries(
        filesDir, validBookIds, validCoverIds, validLocalNames, now,
    )
    val data = OrphanBookData(
        dirCount = entries.size,
        bytes = entries.sumOf { it.bytes() },
    )
    entries.forEach { entry -> runCatching { entry.deleteRecursively() } }
    return data
}

private fun orphanBookEntries(
    filesDir: File,
    validBookIds: Set<Long>,
    validCoverIds: Set<Long>,
    validLocalNames: Map<String, Set<String>>,
    now: Long,
): List<File> {
    val entries = mutableListOf<File>()
    val recentCutoff = now - RECENT_FILE_MS

    fun isOldEnough(entry: File): Boolean = entry.lastModified() <= recentCutoff

    fun numericRoot(root: File, validIds: Set<Long>) {
        root.listFiles().orEmpty().forEach { entry ->
            if (!entry.isDirectory || !isOldEnough(entry)) return@forEach
            val id = entry.name.toLongOrNull()
            if (id != null && id !in validIds) entries += entry
        }
    }

    numericRoot(File(filesDir, "covers"), validCoverIds)
    numericRoot(File(filesDir, "downloads/text"), validBookIds)
    numericRoot(File(filesDir, "downloads/manga"), validBookIds)

    val localRoot = File(filesDir, "local_books")
    for (install in listOf("local-books", "local-manga")) {
        val valid = validLocalNames[install].orEmpty()
        File(localRoot, install).listFiles().orEmpty().forEach { entry ->
            if (!isOldEnough(entry)) return@forEach
            if (entry.name.startsWith(".import-")) return@forEach
            if (entry.name !in valid) entries += entry
        }
    }
    return entries
}

private fun File.bytes(): Long =
    if (isFile) length() else walkTopDown().filter { it.isFile }.sumOf { it.length() }

// Files are often written before their DB row (importer/cover store). Never
// reclaim anything that may still be part of an in-flight write.
private const val RECENT_FILE_MS = 15L * 60 * 1000
