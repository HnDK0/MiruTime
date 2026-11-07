package dev.mirutime.app.features.book.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

// The process-local bookUrl -> (sourceId, title) cache. No Android imports.
class PluginBookIndexTest {

    @After
    fun tearDown() {
        PluginBookIndex.clear()
    }

    @Test
    fun `stores source and title per book url`() {
        PluginBookIndex.put("https://site/book/1", "site")
        PluginBookIndex.putTitle("https://site/book/1", "Book One")

        assertEquals("site", PluginBookIndex.sourceFor("https://site/book/1"))
        assertEquals("Book One", PluginBookIndex.titleFor("https://site/book/1"))
    }

    @Test
    fun `unknown url resolves to null`() {
        assertNull(PluginBookIndex.sourceFor("https://site/nope"))
        assertNull(PluginBookIndex.titleFor("https://site/nope"))
    }

    @Test
    fun `blank values are ignored`() {
        PluginBookIndex.put("", "site")
        PluginBookIndex.putTitle("https://site/book/2", "   ")

        assertNull(PluginBookIndex.sourceFor(""))
        assertNull(PluginBookIndex.titleFor("https://site/book/2"))
    }

    @Test
    fun `oldest entries are evicted past the bound`() {
        repeat(600) { i -> PluginBookIndex.put("https://site/book/$i", "site") }

        assertNull(PluginBookIndex.sourceFor("https://site/book/0"))
        assertEquals("site", PluginBookIndex.sourceFor("https://site/book/599"))
    }

    @Test
    fun `concurrent put and read keeps every entry`() {
        runConcurrently(threads = 8, perThread = 50) { t, i ->
            val url = "https://conc/$t/$i"
            PluginBookIndex.put(url, "site-$t")
            if (i % 5 == 0) PluginBookIndex.sourceFor("https://conc/${t - 1}/$i")
        }

        for (t in 0 until 8) {
            for (i in 0 until 50) {
                assertEquals("site-$t", PluginBookIndex.sourceFor("https://conc/$t/$i"))
            }
        }
    }

    @Test
    fun `concurrent puts stay bounded and uncorrupted`() {
        val urls = (0 until 8).flatMap { t -> (0 until 100).map { i -> "https://evict/$t/$i" to "site-$t" } }

        runConcurrently(threads = 8, perThread = 100) { t, i ->
            PluginBookIndex.put("https://evict/$t/$i", "site-$t")
        }

        val present = urls.count { (url, _) -> PluginBookIndex.sourceFor(url) != null }
        urls.forEach { (url, source) ->
            val actual = PluginBookIndex.sourceFor(url)
            assertTrue("corrupted $url: $actual", actual == null || actual == source)
        }
        assertEquals(500, present)
    }

    @Test
    fun `display title prefers the cached plugin title`() {
        PluginBookIndex.putTitle("https://site/book/1", "Book One")

        assertEquals("Book One", PluginBookIndex.displayTitle("https://site/book/1"))
    }

    @Test
    fun `display title resolves a key through its real url`() {
        val key = dev.mirutime.data.library.libraryBookKey("source", "https://site/book/1")
        PluginBookIndex.putTitle("https://site/book/1", "Book One")

        assertEquals("Book One", PluginBookIndex.displayTitle(key))
    }

    @Test
    fun `display title never returns untitled for a trailing slash url`() {
        assertEquals("1234", PluginBookIndex.displayTitle("https://ranobehub.org/ranobe/1234/"))
    }

    @Test
    fun `display title decodes a cyrillic slug`() {
        val encoded = "https://site/novel/" +
            "%D0%92%D0%BB%D0%B0%D0%B4%D1%8B%D0%BA%D0%B0-%D0%A2%D0%B0%D0%B9%D0%BD"

        assertEquals("Владыка Тайн", PluginBookIndex.displayTitle(encoded))
    }

    @Test
    fun `display title falls back to the host when the path is empty`() {
        assertEquals("site.example", PluginBookIndex.displayTitle("https://site.example/"))
    }

    @Test
    fun `display title strips the scheme from local urls`() {
        assertEquals("abc", PluginBookIndex.displayTitle("local://abc"))
        assertEquals("", PluginBookIndex.displayTitle(""))
    }

    @Test
    fun `display title survives a malformed url`() {
        assertEquals("not a url", PluginBookIndex.displayTitle("not a url"))
    }

    private fun runConcurrently(threads: Int, perThread: Int, block: (thread: Int, index: Int) -> Unit) {
        PluginBookIndex.clear()
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                try {
                    repeat(perThread) { i -> block(t, i) }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }
        failure.get()?.let { throw AssertionError("concurrent access failed", it) }
        assertTrue(workers.none { it.isAlive })
    }
}
