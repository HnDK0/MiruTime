package dev.mirutime.core.local

import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.host.FileSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

private class ListingFileSystem(private val names: List<String>) : FileSystem {
    override fun read(path: String): ByteArray? = null
    override fun write(path: String, data: ByteArray) = Unit
    override fun exists(path: String): Boolean = path in names
    override fun delete(path: String) = Unit
    override fun rename(from: String, to: String) = Unit
    override fun list(dir: String): List<String> = names
}

private fun comicBytes(): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry("001.jpg"))
        zip.write("page".toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return bytes.toByteArray()
}

private fun <T> withComic(block: (File) -> T): T {
    val file = File.createTempFile("mirutime-test", ".cbz")
    try {
        file.writeBytes(comicBytes())
        return block(file)
    } finally {
        file.delete()
    }
}

class LocalBookExtensionTest {
    @Test
    fun upperCaseExtensionsAreListedInTheLibrary() {
        val names = listOf("lib/A.EPUB", "lib/B.Fb2", "lib/C.FB2.ZIP", "lib/D.CBZ", "lib/E.TXT", "lib/ignored.pdf")
        val page = assertNotNull(runBlocking { LocalSource(ListingFileSystem(names), "lib").catalog(0) }.getOrNull())
        assertEquals(5, page.items.size)
    }

    @Test
    fun upperCaseCbzPagesAreReadable() {
        withComic { file ->
            val source = LocalSource(ListingFileSystem(emptyList()), "lib", fileResolver = { file })
            val bytes = runBlocking { source.pageBytes("lib/Comic.CBZ#001.jpg") }
            assertEquals("page", bytes?.decodeToString())
        }
    }

    @Test
    fun theFileBackedParserDispatchesOnUpperCaseExtensions() {
        withComic { file ->
            assertEquals(1, parseLocalBookFile(file, "Comic.CBZ").chapters.size)
        }
    }
}
