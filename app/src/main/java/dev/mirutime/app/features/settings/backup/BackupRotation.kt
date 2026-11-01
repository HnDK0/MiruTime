package dev.mirutime.app.features.settings.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One app-named backup as returned by a SAF tree query. */
data class BackupFileInfo(
    val documentId: String,
    val displayName: String,
    val lastModified: Long,
)

/**
 * Pure naming/rotation rules shared by the SAF helper and unit tests.
 *
 * Only files this app creates are ever deleted: `mirutime-backup-...` and
 * `mirutime-auto-...`. Anything else in the user's tree is left alone.
 */
object BackupRotation {
    private val FileNameFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val AppBackupName = Regex("""^mirutime-(backup|auto)-\d{8}-\d{6}\.zip$""")
    private val AppPartName = Regex("""^mirutime-(backup|auto)-\d{8}-\d{6}\.zip\.part$""")

    const val PartSuffix = ".part"

    /** A part older than this cannot belong to a live backup in this process. */
    const val StalePartMillis = 10 * 60_000L

    /** Grace period before a just-finished archive becomes rotation-eligible. */
    const val RotationGraceMillis = 2 * 60_000L

    fun fileName(now: Long, auto: Boolean): String {
        val timestamp = FileNameFormat.format(
            Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()),
        )
        return "mirutime-${if (auto) "auto" else "backup"}-$timestamp.zip"
    }

    fun partName(finalName: String): String = "$finalName$PartSuffix"

    fun isAppBackup(displayName: String): Boolean = AppBackupName.matches(displayName)

    fun isAppPart(displayName: String): Boolean = AppPartName.matches(displayName)

    fun isStalePart(file: BackupFileInfo, now: Long): Boolean =
        isAppPart(file.displayName) &&
            (file.lastModified <= 0L || now - file.lastModified >= StalePartMillis)

    /**
     * Returns the oldest completed app-named files to delete so at most
     * [maxCount] remain. Non-app files and in-progress `.part` files are never
     * selected. [excludedNames] protects the archive this run just created;
     * files younger than [RotationGraceMillis] are recent and stay as well.
     * Oldest is lastModified first, with the timestamped name as a
     * deterministic fallback.
     */
    fun selectForDeletion(
        files: List<BackupFileInfo>,
        maxCount: Int,
        excludedNames: Set<String> = emptySet(),
        now: Long = 0L,
    ): List<BackupFileInfo> {
        val keep = maxCount.coerceAtLeast(1)
        val appFiles = files
            .filter { isAppBackup(it.displayName) && it.displayName !in excludedNames }
            .filter { now <= 0L || it.lastModified <= 0L ||
                now - it.lastModified >= RotationGraceMillis }
            .sortedWith(compareBy<BackupFileInfo>({ it.lastModified }, { it.displayName }))
        if (appFiles.size <= keep) return emptyList()
        return appFiles.dropLast(keep)
    }
}
