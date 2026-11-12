package dev.mirutime.app.features.opds.data

import dev.mirutime.app.features.opds.domain.OpdsBookOption
import dev.mirutime.app.features.opds.domain.OpdsUnavailableReason
import dev.mirutime.core.opds.OpdsFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `opds_entries.formatsJson`: the acquisition options of one catalogue entry.
 * A malformed row decodes to an empty list, never to a crash: the worst case is
 * "resolve the entry again".
 *
 * kotlinx.serialization, not org.json: the latter is an android.jar stub in JVM
 * unit tests, so a re-cache there would throw instead of writing a row.
 */
internal object OpdsEntryOptions {
    @Serializable
    private data class StoredOption(
        val format: String? = null,
        val url: String,
        val rel: String = "",
        val reason: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(options: List<OpdsBookOption>): String = json.encodeToString(
        options.map {
            StoredOption(
                format = it.format?.name,
                url = it.url,
                rel = it.rel,
                reason = it.unavailableReason?.name,
            )
        },
    )

    fun decode(raw: String): List<OpdsBookOption> = runCatching {
        json.decodeFromString<List<StoredOption>>(raw).mapNotNull { row ->
            val url = row.url.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            OpdsBookOption(
                format = row.format.toEnumOrNull(OpdsFormat.entries),
                url = url,
                rel = row.rel,
                unavailableReason = row.reason.toEnumOrNull(OpdsUnavailableReason.entries),
            )
        }
    }.getOrDefault(emptyList())

    private fun <T : Enum<T>> String?.toEnumOrNull(values: List<T>): T? =
        takeIf { !it.isNullOrBlank() }?.let { raw -> values.firstOrNull { it.name == raw } }
}
