package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.finder.data.ScannedNode
import dev.mirutime.app.features.finder.data.collectMangaChapters
import dev.mirutime.app.features.local.data.isPageImageName
import dev.mirutime.core.local.ComicInfoMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure chapter-walk pieces shared by the SAF image-folder importer and the
// catalogue scanner.
class LocalImageFolderTest {

    private fun dir(
        name: String,
        files: List<String> = emptyList(),
        dirs: List<ScannedNode> = emptyList(),
    ) = ScannedNode(
        uri = "content://test/$name",
        documentId = name,
        name = name,
        isDirectory = true,
        children = files.map {
            ScannedNode(
                uri = "content://test/$name/$it",
                documentId = it,
                name = it,
                isDirectory = false,
            )
        } + dirs,
    )

    @Test
    fun `root images become one chapter in natural order`() {
        val chapters = collectMangaChapters(
            dir(
                "Comic",
                files = listOf("page10.png", "page2.jpg", ".DS_Store", "thumbs.jpg", "ComicInfo.xml"),
            ),
        )

        assertEquals(listOf("Comic"), chapters.map { it.title })
        assertEquals(
            listOf("content://test/Comic/page2.jpg", "content://test/Comic/page10.png"),
            chapters.single().pages,
        )
    }

    @Test
    fun `image subdirectories become chapters in natural order`() {
        val chapters = collectMangaChapters(
            dir(
                "Series",
                files = listOf("ComicInfo.xml"),
                dirs = listOf(
                    dir("ch10", files = listOf("10.jpg", "2.jpg")),
                    dir("ch2", files = listOf("1.png")),
                    dir("thumbs", files = listOf("1.jpg")),
                    dir(".hidden", files = listOf("2.jpg")),
                ),
            ),
        )

        assertEquals(listOf("ch2", "ch10"), chapters.map { it.title })
        assertEquals(
            listOf("content://test/ch10/2.jpg", "content://test/ch10/10.jpg"),
            chapters[1].pages,
        )
    }

    @Test
    fun `chapter directories win over a root cover image`() {
        val chapters = collectMangaChapters(
            dir(
                "Series",
                files = listOf("cover.jpg", "ComicInfo.xml"),
                dirs = listOf(dir("Chapter 1", files = listOf("1.jpg"))),
            ),
        )

        assertEquals(listOf("Chapter 1"), chapters.map { it.title })
    }

    @Test
    fun `cbz-only or empty trees are not image chapters`() {
        val cbz = collectMangaChapters(dir("Manga", files = listOf("a.cbz", ".DS_Store")))
        assertEquals(listOf("a"), cbz.map { it.title })
        assertEquals("content://test/Manga/a.cbz", cbz.single().archiveUri)
        assertTrue(cbz.single().pages.isEmpty())

        assertTrue(collectMangaChapters(dir("Empty")).isEmpty())
    }

    @Test
    fun `path filters reject hidden metadata thumbs and unsafe names`() {
        assertTrue(isPageImageName("001.JPG"))
        assertFalse(isPageImageName("ComicInfo.xml"))
        assertFalse(isPageImageName(".DS_Store"))
        assertFalse(isPageImageName("._001.jpg"))
        assertFalse(isPageImageName("thumb-001.jpg"))

        assertEquals("evil.png", safePageName("../../evil.png", "fallback.jpg"))
        assertEquals("evil.png", safePageName("..\\..\\evil.png", "fallback.jpg"))
        assertEquals("fallback.jpg", safePageName("..", "fallback.jpg"))
        assertEquals("fallback.jpg", safePageName("bad\u0001.png", "fallback.jpg"))
    }

    @Test
    fun `comic metadata drives author and tags but never the book title`() {
        val comic = ComicInfoMeta(
            series = "Berserk",
            volume = "1",
            author = "Kentaro Miura",
            artist = "Kentaro Miura",
            genres = listOf("Action", "Fantasy"),
        )
        assertEquals("Kentaro Miura", folderAuthor(comic))
        assertEquals("Unknown", folderAuthor(null))
        assertEquals(
            listOf("folder"),
            collectMangaChapters(dir("folder", files = listOf("001.jpg")))
                .map { it.title },
        )
    }
}
