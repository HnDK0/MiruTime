package dev.mirutime.core.local

// A book file whose content cannot be read for a reason the user can act on. The import layer turns it
// into a message that says so; every other failure keeps the raw error text it always had.
class DrmProtectedException(message: String) : IllegalStateException(message)

// Font obfuscation is the only encryption an ordinary EPUB carries: it scrambles the first bytes of
// the embedded fonts and the text reads fine regardless. Any other algorithm in META-INF/encryption.xml
// (Adobe ADEPT, Kobo, Apple...) encrypts the book itself. A book whose images alone were encrypted by
// such a tool is still reported as DRM: reading its content is the supported case, and a per-resource
// check of CipherReference URIs is only worth adding if one shows up.
private val FONT_OBFUSCATION_ALGORITHMS = setOf(
    "http://www.idpf.org/2008/embedding",
    "http://ns.adobe.com/pdf/enc#rc",
)

internal fun checkNotDrmProtected(encryptionXml: ByteArray?) {
    // An encryption.xml that cannot be read says nothing about the book: it is not reported as DRM.
    val root = encryptionXml?.let {
        try {
            parseXmlDocument(it)
        } catch (_: Exception) {
            null
        }
    } ?: return
    val encrypted = root.anyElement { element ->
        element.name.substringAfter(':') == "EncryptionMethod" &&
            element.attr("Algorithm")?.trim()?.lowercase()
                ?.let { it !in FONT_OBFUSCATION_ALGORITHMS } == true
    }
    if (encrypted) throw DrmProtectedException("EPUB content is encrypted (DRM)")
}

private fun XmlElement.anyElement(predicate: (XmlElement) -> Boolean): Boolean =
    predicate(this) || children.any { it.anyElement(predicate) }
