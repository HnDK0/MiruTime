package dev.mirutime.feature.reader.ui

import dev.mirutime.core.log.MiruLog
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reader font choices: the three generic families plus every family the
 * device declares in its fonts.xml files. Android has no public API that
 * returns system family names together with a renderable Typeface, so the
 * families are read from the same XML files the platform uses.
 *
 * System choices are stored with a [SystemPrefix] so a stale key from another
 * device can never be mistaken for a built-in family.
 */
object ReaderFontCatalog {
    private const val SystemPrefix = "sys:"

    private val GenericKeys = listOf("serif", "sans-serif", "monospace")

    // Only used when no fonts.xml could be read; these are the platform names
    // that exist on stock Android and are resolved by Typeface.create().
    private val FallbackKeys = listOf(
        "serif",
        "sans-serif",
        "monospace",
        "sans-serif-condensed",
        "sans-serif-light",
        "sans-serif-medium",
        "sans-serif-black",
        "sans-serif-thin",
        "casual",
        "cursive",
        "serif-monospace",
        "sans-serif-smallcaps",
    )

    private val KnownLabels = mapOf(
        "serif" to "Serif",
        "sans-serif" to "Sans Serif",
        "monospace" to "Monospace",
    )

    private val FontXmlPaths = listOf(
        "/system/etc/fonts.xml",
        "/system_ext/etc/fonts.xml",
        "/product/etc/fonts.xml",
        "/vendor/etc/fonts.xml",
        "/odm/etc/fonts.xml",
        "/data/fonts/files/fonts.xml",
    )

    private val FamilyRegex = Regex("""<family\b[^>]*\bname\s*=\s*["']([^"']+)["']""")
    private val AliasRegex = Regex("""<alias\b[^>]*\bname\s*=\s*["']([^"']+)["']""")

    @Volatile
    private var cached: List<String>? = null

    /** The three built-in choices shown before the system list finishes loading. */
    fun legacyOptions(): List<String> = GenericKeys

    fun systemKey(name: String): String = "$SystemPrefix$name"

    fun systemName(key: String): String? = knownSystemName(key, cached)

    /**
     * Once the catalog is loaded, a key from another device is unknown and
     * must fall back to Serif instead of Typeface.create's silent default.
     */
    internal fun knownSystemName(key: String, known: List<String>?): String? {
        if (!key.startsWith(SystemPrefix)) return null
        if (known != null && key !in known) return null
        return key.removePrefix(SystemPrefix).takeIf { it.isNotBlank() }
    }

    /** Maps old saved keys onto the canonical family names. */
    fun canonicalKey(key: String): String {
        if (key.startsWith(SystemPrefix)) return key
        return when (key.lowercase(Locale.ROOT)) {
            "sans" -> "sans-serif"
            "mono" -> "monospace"
            else -> key
        }
    }

    fun displayName(key: String): String {
        val raw = systemName(key) ?: canonicalKey(key)
        KnownLabels[raw]?.let { return it }
        return raw
            .split(' ', '-', '_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
                }
            }
    }

    suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        val parsed = FontXmlPaths.flatMap { path ->
            runCatching { parseFamilyNames(File(path).readText()) }
                .onFailure { error ->
                    // Optional vendor paths are absent on many devices.
                    if (error !is FileNotFoundException) {
                        MiruLog.w(
                            "reader",
                            "font-xml failed path=$path class=${error::class.simpleName}",
                        )
                    }
                }
                .getOrDefault(emptyList())
        }
        val names = mergeNames(parsed)
        val keys = names.map { name -> if (name in GenericKeys) name else systemKey(name) }
        cached = keys
        MiruLog.event("reader", "font-catalog", "families" to keys.size)
        keys
    }

    internal fun parseFamilyNames(xml: String): List<String> {
        val families = FamilyRegex.findAll(xml).map { it.groupValues[1] }
        val aliases = AliasRegex.findAll(xml).map { it.groupValues[1] }
        return dedupe((families + aliases).mapNotNull(::normalizeName).toList())
    }

    internal fun mergeNames(parsed: List<String>): List<String> {
        var names = dedupe(GenericKeys + parsed.mapNotNull(::normalizeName))
        if (names.size <= GenericKeys.size) {
            names = dedupe(names + FallbackKeys)
        }
        val generic = GenericKeys.filter { it in names }
        val rest = names
            .filterNot { it in GenericKeys }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        return generic + rest
    }

    private fun normalizeName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return canonicalKey(trimmed)
    }

    private fun dedupe(names: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return names.filter { seen.add(it.lowercase(Locale.ROOT)) }
    }
}
