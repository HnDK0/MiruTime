package dev.mirutime.app.features.library.data

import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.imageloader.FetchedImage
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Pure JVM coverage for the cover store: the persistence side is two lambdas
// (the production wiring uses LibraryRepository), so no Room/Android needed.
class BookCoverStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private class Rows {
        val map = mutableMapOf<Long, BookCoverEntity>()
        val load: suspend (Long) -> BookCoverEntity? = { map[it] }
        val save: suspend (BookCoverEntity) -> Unit = { map[it.bookId] = it }
    }

    @Test
    fun `valid image is stored atomically and a fresh local file skips the network`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        var fetches = 0
        var now = 1_000_000L
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ ->
                fetches++
                FetchedImage(200, png, etag = "\"v1\"", lastModified = "Thu, 01 Jan 2026 00:00:00 GMT")
            },
            clock = { now },
        )

        val first = store.ensureCover(7L, "src", "https://cdn.test/cover.png")
        assertNotNull(first)
        assertTrue(first!!.startsWith("file:"))
        val file = File(root, "7/cover")
        assertTrue(file.isFile)
        assertArrayEquals(png, file.readBytes())
        assertEquals(1, fetches)

        val row = rows.map.getValue(7L)
        assertEquals("https://cdn.test/cover.png", row.remoteUrl)
        assertEquals(file.absolutePath, row.localPath)
        assertEquals(png.size.toLong(), row.bytes)
        assertNotNull(row.sha256)
        assertNull(row.retryAt)
        assertNull(row.lastError)
        // Only the final cover remains: no temp file leaks from the write.
        assertEquals(listOf("cover"), File(root, "7").list()!!.toList())

        now += 60_000L
        assertEquals(first, store.ensureCover(7L, "src", "https://cdn.test/cover.png"))
        assertEquals(1, fetches)
    }

    @Test
    fun `changed remote URL keeps a valid local cover without fetching`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        var fetches = 0
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ ->
                fetches++
                if (fetches == 1) FetchedImage(200, png) else FetchedImage(0)
            },
            clock = { 1_000_000L },
        )

        val first = store.ensureCover(7L, "hitomi", "https://cdn.test/old-token.png")
        assertNotNull(first)
        assertEquals(1, fetches)

        // Hitomi rotates CDN tokens: the valid local file must survive and the
        // new URL must be stored without a second network request.
        assertEquals(first, store.ensureCover(7L, "hitomi", "https://cdn.test/new-token.png"))
        assertEquals(1, fetches)
        assertEquals("https://cdn.test/new-token.png", rows.map.getValue(7L).remoteUrl)
        assertArrayEquals(png, File(root, "7/cover").readBytes())
    }

    @Test
    fun `local cover is stored atomically and non-images are rejected`() = runTest {
        val root = temp.newFolder("local-covers")
        val rows = Rows()
        var now = 2_000_000L
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ -> FetchedImage(0) },
            clock = { now },
        )

        assertTrue(store.storeLocalCover(7L, remoteUrl = null, bytes = png))
        val file = File(root, "7/cover")
        assertTrue(file.isFile)
        assertArrayEquals(png, file.readBytes())
        val row = rows.map.getValue(7L)
        assertNull(row.remoteUrl)
        assertEquals(now, row.fetchedAt)
        assertEquals(now, row.checkedAt)
        assertEquals(png.size.toLong(), row.bytes)
        assertNotNull(row.sha256)
        assertNotNull(store.localCoverUrl(7L))

        val html = "<html>not an image</html>".encodeToByteArray()
        assertFalse(store.storeLocalCover(8L, remoteUrl = "https://cdn.test/x.jpg", bytes = html))
        assertFalse(File(root, "8/cover").exists())
        assertNull(rows.map[8L])
        assertFalse(store.storeLocalCover(9L, remoteUrl = null, bytes = ByteArray(0)))
    }

    @Test
    fun `invalid bytes never overwrite and enter a transient cooldown`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        var fetches = 0
        var now = 5_000_000L
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ ->
                fetches++
                FetchedImage(200, byteArrayOf(1, 2, 3, 4))
            },
            clock = { now },
        )

        assertNull(store.ensureCover(9L, "src", "https://cdn.test/broken.png"))
        assertFalse(File(root, "9/cover").exists())
        val row = rows.map.getValue(9L)
        assertEquals("invalid image", row.lastError)
        assertEquals(now + TRANSIENT_MS, row.retryAt)

        assertNull(store.ensureCover(9L, "src", "https://cdn.test/broken.png"))
        assertEquals(1, fetches)
    }

    @Test
    fun `404 sets a long cooldown and is not retried`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        var fetches = 0
        var now = 9_000_000L
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ ->
                fetches++
                FetchedImage(404)
            },
            clock = { now },
        )

        assertNull(store.ensureCover(11L, "src", "https://cdn.test/gone.png"))
        val row = rows.map.getValue(11L)
        assertEquals("HTTP 404", row.lastError)
        assertEquals(now + NOT_FOUND_MS, row.retryAt)

        assertNull(store.ensureCover(11L, "src", "https://cdn.test/gone.png"))
        assertEquals(1, fetches)
    }

    @Test
    fun `a cold-start fetch carries the persisted row referer`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        rows.map[7L] = BookCoverEntity(
            bookId = 7L,
            remoteUrl = "https://cdn.test/cover.png",
            referer = "https://site.test/book/1",
        )
        var sent: Map<String, String>? = null
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, headers ->
                sent = headers
                FetchedImage(200, png)
            },
            headersFor = { mapOf("User-Agent" to "UA") },
            clock = { 1_000_000L },
        )

        assertNotNull(store.ensureCover(7L, "src", "https://cdn.test/cover.png"))
        assertEquals("https://site.test/book/1", sent?.get("Referer"))
        assertEquals("UA", sent?.get("User-Agent"))
        // The row keeps the Referer that was actually sent.
        assertEquals("https://site.test/book/1", rows.map.getValue(7L).referer)
    }

    @Test
    fun `caller headers win over the stored row referer`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        rows.map[7L] = BookCoverEntity(
            bookId = 7L,
            remoteUrl = "https://cdn.test/cover.png",
            referer = "https://row.test/old",
        )
        var sent: Map<String, String>? = null
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, headers ->
                sent = headers
                FetchedImage(200, png)
            },
            headersFor = { mapOf("Referer" to "https://caller.test/page") },
            clock = { 1_000_000L },
        )

        store.ensureCover(7L, "src", "https://cdn.test/cover.png")
        assertEquals("https://caller.test/page", sent?.get("Referer"))
    }

    @Test
    fun `local cover uris carry the file version fragment`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ -> FetchedImage(200, png) },
            clock = { 1_000_000L },
        )
        val sha8 = CoverBytes.sha256Hex(png).take(8)

        val uri = store.ensureCover(7L, "src", "https://cdn.test/cover.png")
        val file = File(root, "7/cover")
        assertEquals(file.toURI().toString() + "#v=$sha8", uri)
        assertEquals(uri, store.localCoverUrl(7L))
        assertEquals(uri, store.localCoverUrl(7L, rows.map[7L]))

        // No stored sha (legacy row): the length is still a stable version.
        val legacyFile = File(root, "8/cover")
        legacyFile.parentFile!!.mkdirs()
        legacyFile.writeBytes(png)
        assertEquals(
            legacyFile.toURI().toString() + "#v=${png.size}",
            store.localCoverUrl(8L),
        )
    }

    @Test
    fun `force bypasses the local fast path and the retry cooldown`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        var fetches = 0
        var now = 1_000_000L
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ ->
                fetches++
                // A different body means a different version fragment.
                FetchedImage(200, if (fetches == 1) png else png + 0x00.toByte())
            },
            clock = { now },
        )

        val first = store.ensureCover(7L, "src", "https://cdn.test/cover.png")
        assertNotNull(first)
        assertEquals(1, fetches)

        // A valid local file + a live retry cooldown: a normal call is a no-op.
        rows.map[7L] = rows.map.getValue(7L).copy(retryAt = now + 60 * 60 * 1000L)
        assertEquals(first, store.ensureCover(7L, "src", "https://cdn.test/cover.png"))
        assertEquals(1, fetches)

        val repaired = store.ensureCover(7L, "src", "https://cdn.test/cover.png", force = true)
        assertEquals(2, fetches)
        assertNotNull(repaired)
        assertTrue(repaired != first)
    }

    @Test
    fun `a failed force fetch keeps the old file and uri`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        var fetches = 0
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ ->
                fetches++
                if (fetches == 1) FetchedImage(200, png) else FetchedImage(500)
            },
            clock = { 1_000_000L },
        )

        val first = store.ensureCover(7L, "src", "https://cdn.test/cover.png")
        assertNotNull(first)
        val second = store.ensureCover(7L, "src", "https://cdn.test/cover.png", force = true)

        assertEquals(first, second)
        assertArrayEquals(png, File(root, "7/cover").readBytes())
        assertEquals("HTTP 500", rows.map.getValue(7L).lastError)
    }

    @Test
    fun `an undecodable body never overwrites a good file`() = runTest {
        val root = temp.newFolder("covers")
        val rows = Rows()
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, _, _ -> FetchedImage(200, png) },
            isDecodable = { false },
            clock = { 1_000_000L },
        )

        assertNull(store.ensureCover(9L, "src", "https://cdn.test/cover.png"))
        assertFalse(File(root, "9/cover").exists())
        assertEquals("invalid image", rows.map.getValue(9L).lastError)
    }

    @Test
    fun `magic byte validation accepts the four supported formats`() {
        assertTrue(CoverBytes.isValidImage(png))
        assertTrue(CoverBytes.isValidImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(CoverBytes.isValidImage("GIF89a....".encodeToByteArray()))
        assertTrue(CoverBytes.isValidImage("RIFF1234WEBP....".encodeToByteArray()))
        assertFalse(CoverBytes.isValidImage(byteArrayOf()))
        assertFalse(CoverBytes.isValidImage(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
    }

    private companion object {
        const val NOT_FOUND_MS = 24 * 60 * 60 * 1000L
        const val TRANSIENT_MS = 15 * 60 * 1000L
    }
}
