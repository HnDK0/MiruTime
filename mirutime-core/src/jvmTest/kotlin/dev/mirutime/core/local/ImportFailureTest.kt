package dev.mirutime.core.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

private fun container(opfPath: String) = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

private fun opf(manifest: String, spine: String) = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title><dc:identifier id="id">x</dc:identifier></metadata>
  <manifest>$manifest</manifest>
  <spine>$spine</spine>
</package>"""

private fun page(body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body>$body</body></html>"""

private fun item(id: String, href: String, properties: String = "", mediaType: String = "application/xhtml+xml"): String {
    val props = if (properties.isEmpty()) "" else " properties=\"$properties\""
    return "<item id=\"$id\" href=\"$href\" media-type=\"$mediaType\"$props/>"
}

private fun epub(opfPath: String, manifest: String, spine: String, vararg files: Pair<String, String>): ByteArray =
    zipOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to container(opfPath),
        opfPath to opf(manifest, spine),
        *files,
    )

private fun withFile(bytes: ByteArray, block: (File) -> Unit) {
    val file = File.createTempFile("mirutime-test", ".epub")
    try {
        file.writeBytes(bytes)
        block(file)
    } finally {
        file.delete()
    }
}

// The import path opts into the check; reading an already-imported book must not run it.
private fun importReader(file: File): LocalBook = EpubFileReader(file, checkDrm = true).use { it.readAll() }

private const val ONE_CHAPTER = """<itemref idref="c1"/>"""

private fun encryption(algorithm: String) = """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
  <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/>
    <enc:CipherData><enc:CipherReference URI="OEBPS/ch1.xhtml"/></enc:CipherData></enc:EncryptedData>
</encryption>"""

private fun book(vararg extra: Pair<String, String>): ByteArray =
    epub("OEBPS/content.opf", item("c1", "ch1.xhtml"), ONE_CHAPTER, "OEBPS/ch1.xhtml" to page("<p>Readable.</p>"), *extra)

class ImportFailureTest {
    @Test
    fun anEpubWithEncryptedContentIsRejectedByTheImportReaderOnly() {
        val data = book("META-INF/encryption.xml" to encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc"))
        withFile(data) { file ->
            assertFailsWith<DrmProtectedException> { importReader(file) }
            // A book imported before this change still opens: the reading path does not gate on DRM.
            assertTrue("Readable." in EpubFileReader(file).use { it.readAll() }.chapters.single().body)
        }
    }

    @Test
    fun anOrdinaryEpubImportsWithTheCheckOn() {
        withFile(book()) { file ->
            assertTrue("Readable." in importReader(file).chapters.single().body)
        }
    }

    @Test
    fun fontObfuscationAloneIsNotDrm() {
        for (algorithm in listOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) {
            val data = book("META-INF/encryption.xml" to encryption(algorithm))
            withFile(data) { file ->
                assertTrue("Readable." in importReader(file).chapters.single().body)
            }
        }
    }

    @Test
    fun anUnreadableEncryptionFileDoesNotBlockTheImport() {
        val data = book("META-INF/encryption.xml" to "<encryption><not closed")
        withFile(data) { file ->
            assertTrue("Readable." in importReader(file).chapters.single().body)
        }
    }
}
