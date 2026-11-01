package dev.mirutime.app.features.library.data

import dev.mirutime.core.local.ComicInfoMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure folder-layout pieces of the SAF image-folder importer.
class LocalImageFolderTest {

    private fun tree(name: String, files: List<String> = emptyList(), dirs: List<FolderTree> = emptyList()) =
        FolderTree(name, files, dirs)

    @Test
    fun `root images become one chapter in natural order`() {
        val layout = detectFolderLayout(
            tree(
                "Comic",
                files = listOf("page10.png", "page2.jpg", ".DS_Store", "thumbs.jpg", "ComicInfo.xml"),
            ),
        )
        assertEquals(
            FolderLayout.SingleChapter(listOf("page2.jpg", "page10.png")),
            layout,
        )
    }

    @Test
    fun `image subdirectories become chapters in natural order`() {
        val layout = detectFolderLayout(
            tree(
                "Series",
                files = listOf("ComicInfo.xml"),
                dirs = listOf(
                    tree("ch10", listOf("10.jpg", "2.jpg")),
                    tree("ch2", listOf("1.png")),
                    tree("thumbs", listOf("1.jpg")),
                    tree(".hidden", listOf("2.jpg")),
                ),
            ),
        )
        assertEquals(
            FolderLayout.ChapterDirs(
                listOf(
                    FolderChapter("ch2", listOf("1.png")),
                    FolderChapter("ch10", listOf("2.jpg", "10.jpg")),
                ),
            ),
            layout,
        )
    }

    @Test
    fun `chapter directories win over a root cover image`() {
        val layout = detectFolderLayout(
            tree(
                "Series",
                files = listOf("cover.jpg", "ComicInfo.xml"),
                dirs = listOf(tree("Chapter 1", listOf("1.jpg"))),
            ),
        )
        assertEquals(
            FolderLayout.ChapterDirs(listOf(FolderChapter("Chapter 1", listOf("1.jpg")))),
            layout,
        )
    }

    @Test
    fun `cbz-only or empty trees are not image folders`() {
        assertEquals(FolderLayout.None, detectFolderLayout(tree("Manga", listOf("a.cbz", ".DS_Store"))))
        assertEquals(FolderLayout.None, detectFolderLayout(tree("Empty")))
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
    fun `comic metadata drives title author and tags`() {
        val comic = ComicInfoMeta(
            series = "Berserk",
            volume = "1",
            author = "Kentaro Miura",
            artist = "Kentaro Miura",
            genres = listOf("Action", "Fantasy"),
        )
        assertEquals("Berserk Vol 1", folderTitle(comic, "folder"))
        assertEquals("Kentaro Miura", folderAuthor(comic))
        assertEquals("folder", folderTitle(null, "folder"))
        assertEquals("Unknown", folderAuthor(null))
    }
}
