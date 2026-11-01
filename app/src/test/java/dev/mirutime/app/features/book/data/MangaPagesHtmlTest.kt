package dev.mirutime.app.features.book.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaPagesHtmlTest {
    private val chapterUrl = "https://site.com/book/1/chapter/2"

    @Test
    fun `absolute and relative pages keep source order`() {
        val html = mangaPagesHtml(
            listOf(
                "https://cdn.site.com/a.jpg",
                "/img/b.png",
                "c.webp",
            ),
            chapterUrl,
        )

        assertEquals(
            "<p><img src=\"https://cdn.site.com/a.jpg\"/></p>" +
                "<p><img src=\"https://site.com/img/b.png\"/></p>" +
                "<p><img src=\"https://site.com/book/1/chapter/2/c.webp\"/></p>",
            html,
        )
    }

    @Test
    fun `protocol relative pages inherit the chapter scheme`() {
        assertEquals(
            "<p><img src=\"https://cdn.site.com/a.jpg\"/></p>",
            mangaPagesHtml(listOf("//cdn.site.com/a.jpg"), chapterUrl),
        )
    }

    @Test
    fun `non http schemes are left intact`() {
        assertEquals(
            "<p><img src=\"data:image/png;base64,AA==\"/></p>",
            mangaPagesHtml(listOf("data:image/png;base64,AA=="), chapterUrl),
        )
    }

    @Test
    fun `empty and blank page lists are no chapter`() {
        assertNull(mangaPagesHtml(emptyList(), chapterUrl))
        assertNull(mangaPagesHtml(listOf("", "   "), chapterUrl))
    }
}
