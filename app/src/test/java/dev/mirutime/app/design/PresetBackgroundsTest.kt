package dev.mirutime.app.design

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetBackgroundsTest {

    private val cached = listOf(
        PresetBackground("1_Backgrounds.jpeg", "https://cdn/1.jpeg"),
        PresetBackground("2_Backgrounds.jpeg", "https://cdn/2.jpeg"),
    )

    @Test
    fun `open path reads the cache without a listing fetch or a download`() = runTest {
        var fetches = 0
        var downloads = 0

        val result = loadPresetListing(
            cached = cached,
            refresh = false,
            fetch = { fetches++; null },
            save = { error("open path must not write the listing") },
            download = { downloads++; PresetFileOutcome.Downloaded },
        )

        assertEquals(cached, result.presets)
        assertFalse(result.listingFetched)
        assertEquals(0, result.downloaded)
        assertEquals(0, result.skipped)
        assertEquals(0, fetches)
        assertEquals(0, downloads)
    }

    @Test
    fun `refresh downloads every listed picture`() = runTest {
        val fresh = listOf(
            PresetBackground("3_Backgrounds.jpeg", "https://cdn/3.jpeg"),
            PresetBackground("4_Backgrounds.jpeg", "https://cdn/4.jpeg"),
            PresetBackground("5_Backgrounds.jpeg", "https://cdn/5.jpeg"),
        )
        var fetches = 0
        var saved: List<PresetBackground>? = null
        val downloaded = mutableListOf<String>()

        val result = loadPresetListing(
            cached = cached,
            refresh = true,
            fetch = { fetches++; fresh },
            save = { saved = it },
            download = { preset ->
                downloaded += preset.url
                PresetFileOutcome.Downloaded
            },
        )

        assertEquals(fresh, result.presets)
        assertEquals(fresh, saved)
        assertTrue(result.listingFetched)
        assertEquals(3, result.downloaded)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertEquals(1, fetches)
        assertEquals(fresh.map { it.url }, downloaded)
    }

    @Test
    fun `a failed picture keeps the rest and the listing cache`() = runTest {
        val fresh = listOf(
            PresetBackground("3_Backgrounds.jpeg", "https://cdn/3.jpeg"),
            PresetBackground("4_Backgrounds.jpeg", "https://cdn/4.jpeg"),
            PresetBackground("5_Backgrounds.jpeg", "https://cdn/5.jpeg"),
        )
        var saved: List<PresetBackground>? = null

        val result = loadPresetListing(
            cached = cached,
            refresh = true,
            fetch = { fresh },
            save = { saved = it },
            download = { preset ->
                if (preset.url == "https://cdn/4.jpeg") {
                    PresetFileOutcome.Failed
                } else {
                    PresetFileOutcome.Downloaded
                }
            },
        )

        assertEquals(fresh, result.presets)
        assertEquals(fresh, saved)
        assertEquals(2, result.downloaded)
        assertEquals(0, result.skipped)
        assertEquals(1, result.failed)
    }

    @Test
    fun `refresh counts valid cached pictures as skipped`() = runTest {
        val fresh = listOf(
            PresetBackground("3_Backgrounds.jpeg", "https://cdn/3.jpeg"),
            PresetBackground("4_Backgrounds.jpeg", "https://cdn/4.jpeg"),
            PresetBackground("5_Backgrounds.jpeg", "https://cdn/5.jpeg"),
        )

        val result = loadPresetListing(
            cached = cached,
            refresh = true,
            fetch = { fresh },
            save = {},
            download = { preset ->
                if (preset.url == "https://cdn/3.jpeg") {
                    PresetFileOutcome.Downloaded
                } else {
                    PresetFileOutcome.Skipped
                }
            },
        )

        assertEquals(1, result.downloaded)
        assertEquals(2, result.skipped)
        assertEquals(0, result.failed)
    }

    @Test
    fun `valid cached picture is skipped without a download`() = runTest {
        val target = tempFile(jpegBytes())
        var downloads = 0

        val result = obtainPresetFile(target) { downloads++; null }

        assertTrue(result.skipped)
        assertEquals(target, result.file)
        assertEquals(0, downloads)
        assertTrue(target.exists())
    }

    @Test
    fun `invalid cached file is deleted and replaced`() = runTest {
        val target = tempFile("<html>not an image</html>".toByteArray())
        assertFalse(isUsablePresetFile(target))

        val result = obtainPresetFile(target) {
            // The tap/Refresh path deletes the bad file before downloading.
            assertFalse(target.exists())
            target.writeBytes(jpegBytes())
            target
        }

        assertFalse(result.skipped)
        assertEquals(target, result.file)
        assertTrue(isUsablePresetFile(target))
    }

    @Test
    fun `missing picture is downloaded`() = runTest {
        val target = File(Files.createTempDirectory("preset").toFile(), "missing.jpeg")
        var downloads = 0

        val result = obtainPresetFile(target) {
            downloads++
            target.writeBytes(jpegBytes())
            target
        }

        assertFalse(result.skipped)
        assertEquals(target, result.file)
        assertEquals(1, downloads)
    }

    @Test
    fun `dedupe removes a preset copy and keeps every other entry`() {
        val presetCopy = "/files/backgrounds/361d79d68fb9533a.jpg"
        val userBackground = "/files/backgrounds/0e2c4aba2b8667f6.jpg"
        val remote = "https://cdn/cover.jpg"
        val removed = mutableListOf<String>()

        val kept = dedupePresetCopies(
            library = listOf(presetCopy, userBackground, remote),
            presetSavedPaths = setOf(presetCopy),
            onDuplicate = { removed += it },
        )

        assertEquals(listOf(userBackground, remote), kept)
        assertEquals(listOf(presetCopy), removed)
    }

    @Test
    fun `applying a preset uses the in-place file and skips the library`() {
        val rails = source("src/main/java/dev/mirutime/app/design/components/ThemePicker.kt")

        assertTrue(rails.contains("applyPreset(context, preset)"))
        assertEquals(false, rails.contains("importPresetBackground("))
        assertTrue(rails.contains("R.string.theme_preset_item_fmt"))

        val tile = rails.substringAfter("private fun PresetTile(").substringBefore("@Composable")
        assertEquals(false, tile.contains("Icons."))
        val placeholder = rails.substringAfter("private fun PresetPlaceholderTile(")
            .substringBefore("@Composable")
        assertEquals(false, placeholder.contains("Icons."))

        val activity = source("src/main/java/dev/mirutime/app/MainActivity.kt")
        assertTrue(activity.contains("isPresetBackgroundPath(applicationContext, source)"))
        val storage = source("src/main/java/dev/mirutime/app/design/ThemeStorage.kt")
        assertTrue(storage.contains("filterNot { isPresetBackgroundPath(context, it) }"))
    }

    @Test
    fun `preset and local labels are separate strings in both locales`() {
        val en = resource("ui-kit/src/main/res/values/strings.xml")
        val ru = resource("ui-kit/src/main/res/values-ru/strings.xml")

        assertTrue(en.contains("name=\"theme_preset_item_fmt\""))
        assertTrue(en.contains("name=\"theme_custom_item_fmt\""))
        assertTrue(ru.contains("name=\"theme_preset_item_fmt\""))
        assertTrue(ru.contains("name=\"theme_custom_item_fmt\""))
        assertEquals(false, en.contains("name=\"theme_preset_item_fmt\">Background"))
        assertEquals(false, ru.contains("name=\"theme_preset_item_fmt\">Фон"))
    }

    @Test
    fun `presetTempFile creates the missing parent directory`() {
        val root = Files.createTempDirectory("preset-temp").toFile()
        try {
            val target = File(File(root, "missing"), "1_Backgrounds.jpeg")
            assertFalse(target.parentFile?.exists() == true)

            val tmp = presetTempFile(target)

            assertTrue(target.parentFile?.isDirectory == true)
            assertEquals("1_Backgrounds.jpeg.tmp", tmp.name)
            assertEquals(target.parentFile, tmp.parentFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `refresh replaces a picture re-uploaded under the same name`() = runTest {
        val dir = Files.createTempDirectory("preset").toFile()
        val target = File(dir, "1_Backgrounds.jpeg").apply { writeBytes(jpegBytes()) }
        presetEtagFile(target).writeText("\"v1\"")
        val updated = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x01, 0x02, 0x03,
        )
        val seenTags = Collections.synchronizedList(mutableListOf<String?>())

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/1_Backgrounds.jpeg") { exchange ->
                val tag = exchange.requestHeaders.getFirst("If-None-Match")
                seenTags += tag
                if (tag == "\"v1\"") {
                    exchange.sendResponseHeaders(304, -1)
                } else {
                    exchange.responseHeaders.add("ETag", "\"v2\"")
                    exchange.sendResponseHeaders(200, updated.size.toLong())
                    exchange.responseBody.use { it.write(updated) }
                }
                exchange.close()
            }
            server.start()
            val preset = PresetBackground(
                "1_Backgrounds.jpeg",
                "http://127.0.0.1:${server.address.port}/1_Backgrounds.jpeg",
            )

            assertEquals(PresetFileOutcome.Skipped, refreshPresetFile(preset, target, invalidate = {}))
            assertEquals(jpegBytes().toList(), target.readBytes().toList())
            assertEquals("\"v1\"", presetEtagFile(target).readText())

            // The remote tag changed: the local picture must be replaced.
            presetEtagFile(target).writeText("\"v0\"")
            assertEquals(PresetFileOutcome.Downloaded, refreshPresetFile(preset, target, invalidate = {}))
            assertEquals(updated.toList(), target.readBytes().toList())
            assertEquals("\"v2\"", presetEtagFile(target).readText())
            assertEquals(listOf("\"v1\"", "\"v0\""), seenTags)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `folder listing picks up any picture whatever its name and extension`() {
        val html = """
            {"name":"x","path":"testBackgrounds/cover ART.png","contentType":"file"},
            {"name":"y","path":"testBackgrounds/01.jpg","contentType":"file"},
            {"name":"z","path":"testBackgrounds/notes.txt","contentType":"file"},
            {"name":"w","path":"testBackgrounds/sub","contentType":"directory"},
            {"name":"v","path":"testBackgrounds/sub/nested.webp","contentType":"file"}
        """

        val items = parsePresetFolderHtml(html)

        assertEquals(listOf("01.jpg", "cover ART.png"), items.map { it.name })
        assertEquals(
            "https://raw.githubusercontent.com/HnDK0/MiruTime/main/testBackgrounds/cover%20ART.png",
            items[1].url,
        )
    }

    @Test
    fun `refresh drops local pictures that left the listing`() {
        val dir = Files.createTempDirectory("presets").toFile()
        val kept = File(dir, "1_Backgrounds.jpeg").apply { writeBytes(jpegBytes()) }
        File(dir, "1_Backgrounds.jpeg.etag").writeText("\"v1\"")
        val stale = File(dir, "old_Backgrounds.jpeg").apply { writeBytes(jpegBytes()) }
        File(dir, "old_Backgrounds.jpeg.etag").writeText("\"v0\"")
        val active = File(dir, "active.jpeg").apply { writeBytes(jpegBytes()) }
        File(dir, "active.jpeg.etag").writeText("\"v2\"")
        val tmp = File(dir, "3_Backgrounds.jpeg.tmp").apply { writeText("partial") }
        val keptTmp = File(dir, "1_Backgrounds.jpeg.tmp").apply { writeText("in flight") }

        val removed = pruneStalePresetFiles(dir, setOf("1_Backgrounds.jpeg"), active.absolutePath)

        assertEquals(3, removed)
        assertTrue(kept.exists())
        assertTrue(File(dir, "1_Backgrounds.jpeg.etag").exists())
        assertFalse(stale.exists())
        assertFalse(File(dir, "old_Backgrounds.jpeg.etag").exists())
        // The theme still shows this file, so it survives with its tag.
        assertTrue(active.exists())
        assertTrue(File(dir, "active.jpeg.etag").exists())
        assertFalse(tmp.exists())
        // A kept picture owns its temp file: a download may be writing it now.
        assertTrue(keptTmp.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `a picture without a tag is fetched once and tagged`() = runTest {
        val dir = Files.createTempDirectory("preset").toFile()
        val target = File(dir, "fresh.jpeg")
        val served = Collections.synchronizedList(mutableListOf<String?>())

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/fresh.jpeg") { exchange ->
                served += exchange.requestHeaders.getFirst("If-None-Match")
                exchange.responseHeaders.add("ETag", "\"v9\"")
                exchange.sendResponseHeaders(200, jpegBytes().size.toLong())
                exchange.responseBody.use { it.write(jpegBytes()) }
                exchange.close()
            }
            server.start()
            val preset = PresetBackground(
                "fresh.jpeg",
                "http://127.0.0.1:${server.address.port}/fresh.jpeg",
            )

            assertEquals(PresetFileOutcome.Downloaded, refreshPresetFile(preset, target, invalidate = {}))
            assertEquals(listOf<String?>(null), served)
            assertEquals(jpegBytes().toList(), target.readBytes().toList())
            assertEquals("\"v9\"", presetEtagFile(target).readText())
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed fetch keeps the usable picture and fails only a missing one`() = runTest {
        val dir = Files.createTempDirectory("preset").toFile()
        val usable = File(dir, "usable.jpeg").apply { writeBytes(jpegBytes()) }
        val missing = File(dir, "missing.jpeg")

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            listOf("/usable.jpeg", "/missing.jpeg").forEach { path ->
                server.createContext(path) { exchange ->
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
            }
            server.start()
            fun preset(path: String) =
                PresetBackground(path, "http://127.0.0.1:${server.address.port}$path")

            assertEquals(PresetFileOutcome.Skipped, refreshPresetFile(preset("/usable.jpeg"), usable, invalidate = {}))
            assertEquals(jpegBytes().toList(), usable.readBytes().toList())

            assertEquals(PresetFileOutcome.Failed, refreshPresetFile(preset("/missing.jpeg"), missing, invalidate = {}))
            assertFalse(missing.exists())
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `distinct picture names stay distinct on disk`() {
        assertNotEquals(presetFileName("фон.jpg"), presetFileName("дом.jpg"))
        assertNotEquals(presetFileName("a b.jpg"), presetFileName("a_b.jpg"))
        assertEquals("1_Backgrounds.jpeg", presetFileName("1_Backgrounds.jpeg"))
    }

    @Test
    fun `listing decodes json escapes in a picture name`() {
        val items = parsePresetFolderHtml("{\"path\":\"testBackgrounds/a\\u0026b.jpg\"}")

        assertEquals(listOf("a&b.jpg"), items.map { it.name })
        assertTrue(items[0].url.endsWith("/a%26b.jpg"))
    }

    private fun tempFile(content: ByteArray): File =
        Files.createTempFile("preset", ".jpeg").toFile().apply {
            writeBytes(content)
            deleteOnExit()
        }

    private fun jpegBytes(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    private fun resource(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).first { it.isFile }.readText()
}
