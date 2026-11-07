package dev.mirutime.data.library

/**
 * Stable identity of a library work. One plugin can expose two different
 * novels under the same URL (it may transform the page), so URL alone is not
 * an identity: installId + URL is.
 *
 * UI/routes carry [libraryBookKey] as an opaque string. The data layer and the
 * plugin provider decode it back to installId + real URL at their boundaries.
 */
data class BookIdentity(val installId: String, val url: String)

internal const val LibraryBookKeyPrefix = "miru-book:v1:"
private const val Hex = "0123456789abcdef"

fun libraryBookKey(installId: String, url: String): String {
    if (installId.isBlank() || url.startsWith(LibraryBookKeyPrefix)) return url
    return LibraryBookKeyPrefix + hexEncode(installId) + ":" + hexEncode(url)
}

fun decodeLibraryBookKey(key: String): BookIdentity? {
    if (!key.startsWith(LibraryBookKeyPrefix)) return null
    val payload = key.removePrefix(LibraryBookKeyPrefix)
    val separator = payload.indexOf(':')
    if (separator <= 0) return null
    val installId = hexDecode(payload.substring(0, separator)) ?: return null
    val url = hexDecode(payload.substring(separator + 1)) ?: return null
    if (installId.isBlank() || url.isBlank()) return null
    return BookIdentity(installId, url)
}

fun bookKeyUrl(key: String): String = decodeLibraryBookKey(key)?.url ?: key

fun bookKeyInstallId(key: String): String? = decodeLibraryBookKey(key)?.installId

private fun hexEncode(value: String): String = buildString(value.length * 2) {
    for (byte in value.encodeToByteArray()) {
        val v = byte.toInt() and 0xff
        append(Hex[v ushr 4])
        append(Hex[v and 0x0f])
    }
}

private fun hexDecode(value: String): String? {
    if (value.length % 2 != 0) return null
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        val high = value[index * 2].hexDigit() ?: return null
        val low = value[index * 2 + 1].hexDigit() ?: return null
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes.toString(Charsets.UTF_8)
}

private fun Char.hexDigit(): Int? = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> null
}
