package dev.mirutime.data.library

import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Id-free store for packed plugin chapter bodies. */
const val BackupBodyStoreDir = "downloads/bodies"

/**
 * Relative path for a packed plugin body: already-stored paths are kept,
 * everything else becomes `<BackupBodyStoreDir>/<hash(relativePath)>.html`.
 * `hash` must be a lowercase hex SHA-256 of [relativePath] (as UTF-8).
 */
fun stableBackupBodyPath(relativePath: String, hash: (String) -> String): String =
    if (relativePath.startsWith("$BackupBodyStoreDir/")) relativePath
    else "$BackupBodyStoreDir/${hash(relativePath)}.html"

/**
 * Rewrites `chapter_bodies.path` in the backup snapshot for plugin books
 * (every install id except `local-books` and `local-manga`) from
 * `<filesRoot>/downloads/text/...` to `<filesRoot>/downloads/bodies/<hash>.html`.
 * Paths outside [filesRoot] and already-stable paths are left alone.
 * Returns the number of rewritten rows.
 */
fun rewriteCachedBodyPaths(
    snapshotPath: String,
    filesRoot: String,
    hash: (String) -> String,
): Int {
    val root = filesRoot.normalizedSeparators().trimEnd('/') + "/"
    val rewrites = ArrayList<Pair<Long, String>>()
    BundledSQLiteDriver().open(snapshotPath).use { connection ->
        connection.prepare(BACKUP_BODY_SELECT).use { statement ->
            while (statement.step()) {
                val chapterId = statement.getLong(0)
                val path = statement.getText(1).normalizedSeparators()
                val installId = statement.getText(2)
                if (installId in LocalBodyInstallIds) continue
                if (!path.startsWith(root)) continue
                val relative = path.removePrefix(root)
                if (relative.isBlank()) continue
                val stable = stableBackupBodyPath(relative, hash)
                if (stable == relative) continue
                rewrites += chapterId to root + stable
            }
        }
        connection.prepare("UPDATE chapter_bodies SET path = ? WHERE chapterId = ?").use { update ->
            rewrites.forEach { (chapterId, newPath) ->
                update.bindText(1, newPath)
                update.bindLong(2, chapterId)
                update.step()
                update.reset()
            }
        }
    }
    return rewrites.size
}

private fun String.normalizedSeparators(): String = replace('\\', '/')

private val LocalBodyInstallIds = setOf("local-books", "local-manga")

private const val BACKUP_BODY_SELECT =
    "SELECT cb.chapterId, cb.path, b.installId " +
        "FROM chapter_bodies cb " +
        "JOIN chapters c ON c.chapterId = cb.chapterId " +
        "JOIN books b ON b.bookId = c.bookId"
