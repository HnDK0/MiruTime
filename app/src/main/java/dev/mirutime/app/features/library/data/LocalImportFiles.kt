package dev.mirutime.app.features.library.data

import android.net.Uri

/**
 * One SAF-picked file. [uri] is the original document URI (string form so the
 * model stays pure-JVM testable); imports copy the bytes and keep this only as
 * the re-import/diagnostic reference.
 */
data class PickedImportFile(
    val uri: String,
    val fileName: String,
    val format: String,
) {
    fun documentUri(): Uri = Uri.parse(uri)
}

data class ImportSummary(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val failures: Int = 0,
) {
    // Duplicates are already in the library, so the user-visible count covers both.
    val accepted: Int get() = imported + duplicates
}

internal fun summarizeImports(results: List<LocalImportResult>): ImportSummary {
    var imported = 0
    var duplicates = 0
    var failures = 0
    for (result in results) {
        when (result) {
            is LocalImportResult.Success -> if (result.duplicate) duplicates++ else imported++
            is LocalImportResult.Failure -> failures++
        }
    }
    return ImportSummary(imported = imported, duplicates = duplicates, failures = failures)
}
