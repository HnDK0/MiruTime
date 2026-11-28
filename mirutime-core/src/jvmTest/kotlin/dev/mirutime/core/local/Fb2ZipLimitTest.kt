package dev.mirutime.core.local

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun zipped(name: String, content: ByteArray): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
    }
    return bytes.toByteArray()
}

class Fb2ZipLimitTest {
    @Test
    fun aZippedFb2WithAnOverlargeEntryReportsTheSizeLimit() {
        // 33 MB of zeros compresses to a few dozen KB, but is over the 32 MB entry limit.
        val failure = assertFailsWith<IllegalStateException> {
            parseFb2(zipped("book.fb2", ByteArray(33 * 1024 * 1024)))
        }
        assertTrue("too large" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aSmallZippedFb2IsStillUnwrapped() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>T</book-title></title-info></description>
<body><section><p>Zipped text.</p></section></body></FictionBook>"""
        val book = parseFb2(zipped("book.fb2", xml.toByteArray(Charsets.UTF_8)))
        assertTrue("Zipped text." in book.chapters.single().body)
    }
}
