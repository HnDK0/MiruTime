package dev.mirutime.app.features.opds

import dev.mirutime.app.features.library.data.ImportOrigin
import dev.mirutime.app.features.library.data.LocalImportPort
import dev.mirutime.app.features.library.data.LocalImportResult
import dev.mirutime.app.features.opds.data.ArchiveOrgFormats
import dev.mirutime.app.features.opds.data.OpdsTransport
import dev.mirutime.app.features.opds.data.OpdsControllerImpl
import dev.mirutime.app.features.opds.data.OpdsCredentialStore
import dev.mirutime.app.features.opds.data.RemoteFetch
import dev.mirutime.app.features.opds.domain.OpdsBook
import dev.mirutime.app.features.opds.domain.OpdsBookOption
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.app.features.opds.domain.OpdsPage
import dev.mirutime.app.features.opds.domain.OpdsUnavailableReason
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.opds.OpdsFormat
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.OpdsCatalogEntity
import dev.mirutime.data.library.OpdsEntryEntity
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsControllerTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `acquisition feed maps books, nav links and resolves against final url`() = runTest {
        val transport = FakeTransport()
        transport.respond(
            status = 200,
            body = ACQUISITION_FEED,
            finalUrl = "https://final.test/opds/root/",
        )
        val controller = controller(transport)

        val added = controller.add(
            name = "Test catalog",
            url = "https://start.test/opds",
            login = "логин",
            password = "пароль",
        )
        val catalog = (added as Outcome.Ok).value
        val page = (controller.page(catalog.id, null) as Outcome.Ok).value
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("логин:пароль".toByteArray(Charsets.UTF_8)),
            transport.lastHeaders["Authorization"],
        )

        assertEquals("https://final.test/opds/root/", page.url)
        assertEquals("Catalog", page.title)
        assertEquals("https://final.test/opds/root/page2", page.nextUrl)

        val book = page.books.single()
        assertEquals("Book One", book.title)
        assertEquals("Jane", book.author)
        assertEquals("Blurb", book.summary)
        assertEquals("https://final.test/opds/root/covers/1.jpg", book.coverUrl)
        assertEquals(OpdsFormat.EPUB, book.format)
        assertEquals("https://final.test/opds/root/books/1.epub", book.downloadUrl)
        assertTrue(book.isDownloadable)

        val section = page.links.single()
        assertEquals("Section", section.title)
        assertEquals("https://final.test/opds/root/shelf/2", section.url)
    }

    @Test
    fun `search template resolves without another fetch`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, FEED_WITH_SEARCH, "https://final.test/opds/")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        val page = (controller.page(catalog.id, null) as Outcome.Ok).value
        assertEquals(
            "https://final.test/opds/search?q=a+b",
            controller.searchUrl(catalog.id, page, "a b"),
        )
        assertEquals(1, transport.gets)
    }

    @Test
    fun `401 maps to NeedsLogin`() = runTest {
        val transport = FakeTransport()
        transport.respond(401, "", "https://final.test/opds")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        assertFailure(controller.page(catalog.id, null), OpdsFailure.NeedsLogin)
    }

    @Test
    fun `calibre digest-only 400 maps to DigestOnly`() = runTest {
        val transport = FakeTransport()
        transport.respond(
            status = 400,
            body = "<html>Unsupported authentication method: digest</html>",
            finalUrl = "https://final.test/opds",
        )
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        assertFailure(controller.page(catalog.id, null), OpdsFailure.DigestOnly)
    }

    @Test
    fun `OPDS 2 root opens its sections and keeps the search template`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, OPDS2_ROOT, "https://final.test/opds")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val page = (controller.page(catalog.id, null) as Outcome.Ok).value

        assertEquals("Archive.org", page.title)
        assertEquals(
            listOf("eBooks", "Audiobooks", "eBooks Open Access", "Audiobooks & Music", "Texts"),
            page.links.map { it.title },
        )
        // A group is a section with its item count, not a book card.
        assertEquals("3667231", page.links[2].subtitle)
        // Group previews never replace the sections a catalogue root is for.
        assertTrue(page.books.isEmpty())
        assertEquals(
            "https://archive.org/services/opds/catalog{?query}&type=search",
            page.searchTemplate,
        )
        assertEquals(
            "https://archive.org/services/opds/catalog?query=a+b&type=search",
            controller.searchUrl(catalog.id, page, "a b"),
        )
        assertTrue(page.facets.isEmpty())
    }

    @Test
    fun `OPDS 2 publications become book cards, pdf-only stays unavailable`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, OPDS2_PUBLICATIONS, "https://final.test/opds/catalog")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val page = (controller.page(catalog.id, null) as Outcome.Ok).value

        assertEquals(2, page.books.size)
        assertEquals("https://final.test/opds/catalog?page=2", page.nextUrl)

        val pdfOnly = page.books[0]
        assertEquals("Kirkland, Thomas", pdfOnly.author)
        assertEquals("https://final.test/opds/publications/1", pdfOnly.entryUrl)
        assertNull(pdfOnly.format)
        assertNull(pdfOnly.downloadUrl)
        assertTrue(!pdfOnly.isDownloadable)
        // The Readium manifest is a landing document, never an option.
        assertTrue(pdfOnly.options.none { it.url.contains("action=webpub") })
        assertTrue(pdfOnly.options.none { it.isDownloadable })

        val epub = page.books[1]
        assertEquals(OpdsFormat.EPUB, epub.format)
        assertEquals("https://final.test/opds/books/2.epub", epub.downloadUrl)
        assertTrue(epub.isDownloadable)

        val facet = page.facets.single()
        assertEquals("Browse by Language", facet.label)
        assertEquals(listOf("English", "French"), facet.options.map { it.label })
    }

    @Test
    fun `a JSON body that is not a catalogue is a typed NotAFeed`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, """{"status":"error"}""", "https://final.test/opds")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        assertFailure(controller.page(catalog.id, null), OpdsFailure.NotAFeed)
    }

    @Test
    fun `unsupported formats yield a book with no download url`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, UNSUPPORTED_FEED, "https://final.test/opds/")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        val page = (controller.page(catalog.id, null) as Outcome.Ok).value
        val book = page.books.single()
        assertEquals("Pdf Book", book.title)
        assertNull(book.format)
        assertNull(book.downloadUrl)
        assertTrue(!book.isDownloadable)
    }

    @Test
    fun `duplicate and non-http urls are rejected`() = runTest {
        val controller = controller(FakeTransport())
        assertTrue(controller.add("c", "ftp://x.test/opds", "", "") is Outcome.Err)
        assertTrue(controller.add("c", "https://dup.test/opds", "", "") is Outcome.Ok)
        assertTrue(controller.add("c", "https://dup.test/opds", "", "") is Outcome.Err)
        assertEquals(1, controller.catalogs.value.size)
    }

    @Test
    fun `page registers host auth for private catalog covers`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ACQUISITION_FEED, "https://final.test/opds/root/")
        val registered = mutableListOf<Triple<String, String, String?>>()
        val controller = controller(
            transport,
            registerHost = { sourceId, feedUrl, auth -> registered += Triple(sourceId, feedUrl, auth) },
        )
        val catalog = (controller.add("c", "https://final.test/opds", "login", "pass") as Outcome.Ok).value
        registered.clear()
        controller.page(catalog.id, null)
        val registration = registered.single()
        assertEquals(catalog.id, registration.first)
        assertEquals("https://final.test/opds", registration.second)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("login:pass".toByteArray(Charsets.UTF_8)),
            registration.third,
        )
    }

    @Test
    fun `anonymous page clears host auth`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ACQUISITION_FEED, "https://final.test/opds/root/")
        val registered = mutableListOf<String?>()
        val controller = controller(
            transport,
            registerHost = { _, _, auth -> registered += auth },
        )
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        registered.clear()
        controller.page(catalog.id, null)
        assertNull(registered.single())
    }

    @Test
    fun `entry identity and options rank direct formats above non-downloadable links`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, MIXED_RELATIONS_FEED, "https://final.test/opds/root/")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val book = (controller.page(catalog.id, null) as Outcome.Ok).value.books.single()

        // Atom id wins over the file link.
        assertEquals("https://final.test/opds/entry/1", book.entryUrl)
        assertEquals(
            listOf(
                OpdsFormat.EPUB to null,
                OpdsFormat.FB2 to null,
                OpdsFormat.EPUB to OpdsUnavailableReason.Sample,
                OpdsFormat.EPUB to OpdsUnavailableReason.Buy,
                OpdsFormat.EPUB to OpdsUnavailableReason.Borrow,
                OpdsFormat.EPUB to OpdsUnavailableReason.Subscribe,
                null to OpdsUnavailableReason.IndirectOnly,
                // An unreadable landing link is noise next to a real file and
                // is dropped; it survives only when it is the sole option.
            ),
            book.options.map { it.format to it.unavailableReason },
        )
        // Compatibility fields still point at the default (first direct) option.
        assertEquals(OpdsFormat.EPUB, book.format)
        assertEquals("https://final.test/opds/root/books/1.epub", book.downloadUrl)
    }

    @Test
    fun `entry without an atom id identifies itself by its alternate link`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ALTERNATE_ENTRY_FEED, "https://final.test/opds/root/")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val book = (controller.page(catalog.id, null) as Outcome.Ok).value.books.single()

        assertEquals("https://final.test/opds/root/entry/7", book.entryUrl)
        assertEquals(OpdsFormat.EPUB, book.format)
    }

    @Test
    fun `resolveEntry reads the publication feed one hop down and caches it`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, PUBLICATION_FEED, "https://final.test/opds/root/book/9")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val options = (
            controller.resolveEntry(catalog.url, "https://final.test/opds/root/book/9")
                as Outcome.Ok
            ).value

        assertEquals("https://final.test/opds/root/book/9.epub", options.single().url)
        assertEquals(OpdsFormat.EPUB, options.single().format)
        // Second call is served from memory, not the network.
        controller.resolveEntry(catalog.url, "https://final.test/opds/root/book/9")
        assertEquals(1, transport.gets)
    }

    @Test
    fun `resolveEntry reports a feed with no acquisition as not downloadable`() = runTest {
        val transport = FakeTransport()
        // A feed whose entries carry no acquisition at all.
        transport.respond(200, FEED_WITH_SEARCH, "https://final.test/opds/root/book/9")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val failed = controller.resolveEntry(catalog.url, "https://final.test/opds/root/book/9")

        assertEquals(OpdsFailure.NotDownloadable, (failed as Outcome.Err).error)
    }

    @Test
    fun `resolveEntry binds the requested entry of a multi-publication feed`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, MULTI_PUBLICATION_FEED, "https://final.test/opds/feed/")
        val controller = controller(transport)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val options = (
            controller.resolveEntry(catalog.url, "https://final.test/opds/feed/b")
                as Outcome.Ok
            ).value

        assertEquals("https://final.test/opds/feed/b.epub", options.single().url)
        assertEquals(OpdsFormat.EPUB, options.single().format)
    }

    @Test
    fun `a feed that cannot name the requested entry is not guessed`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, MULTI_PUBLICATION_FEED, "https://final.test/opds/feed/")
        val controller = controller(transport, importer = neverImporting())
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val failed = controller.addEntry(
            catalog.url,
            "https://final.test/opds/feed/missing",
            null,
            "Test Catalog",
        )

        assertEquals(OpdsFailure.NotDownloadable, (failed as Outcome.Err).error)
        assertEquals(0, transport.downloads)
    }

    @Test
    fun `addEntry downloads the chosen option and keeps catalogue provenance`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, MIXED_RELATIONS_FEED, "https://final.test/opds/root/")
        val dao = FakeDao()
        val imported = mutableListOf<Pair<String, ImportOrigin?>>()
        val controller = controller(
            transport = transport,
            importer = object : LocalImportPort {
                override suspend fun import(
                    file: File,
                    origin: ImportOrigin?,
                ): LocalImportResult {
                    imported += file.name to origin
                    assertTrue(file.exists())
                    return LocalImportResult.Success(
                        bookId = 1,
                        bookUrl = "local://new-book",
                        title = "Book One",
                        duplicate = false,
                    )
                }
            },
            dao = dao,
        )
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        val entryUrl = "https://final.test/opds/entry/1"
        dao.putEntry(catalog.url, entryUrl, CACHED_OPTIONS)

        val added = controller.addEntry(catalog.url, entryUrl, null, "Test Catalog")

        assertEquals(Outcome.Ok("local://new-book"), added)
        assertEquals(1, transport.downloads)
        val (fileName, origin) = imported.single()
        assertTrue(fileName.endsWith(".epub"))
        assertEquals(
            ImportOrigin(
                catalogUrl = catalog.url,
                catalogName = "Test Catalog",
                entryUrl = entryUrl,
                sourceFileUrl = "https://final.test/opds/root/books/1.epub",
            ),
            origin,
        )
    }

    @Test
    fun `addEntry rejects a non-downloadable option with its reason`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, MIXED_RELATIONS_FEED, "https://final.test/opds/root/")
        val dao = FakeDao()
        val controller = controller(transport, importer = neverImporting(), dao = dao)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        val entryUrl = "https://final.test/opds/entry/1"
        dao.putEntry(catalog.url, entryUrl, CACHED_OPTIONS)
        val sampleUrl = "https://final.test/opds/root/books/1-sample.epub"

        val failed = controller.addEntry(catalog.url, entryUrl, sampleUrl, "Test Catalog")

        assertEquals(
            OpdsFailure.Unavailable(OpdsUnavailableReason.Sample),
            (failed as Outcome.Err).error,
        )
        assertEquals(0, transport.downloads)
    }

    @Test
    fun `addEntry resolves the entry feed when nothing is stored`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, PUBLICATION_FEED, "https://final.test/opds/root/book/9")
        val controller = controller(transport, importer = succeedingImport())
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val added = controller.addEntry(
            catalog.url,
            "https://final.test/opds/root/book/9",
            null,
            "Test Catalog",
        )

        assertEquals(Outcome.Ok("local://new-book"), added)
        assertEquals(1, transport.downloads)
    }

    @Test
    fun `addEntry reports a failed entry resolution`() = runTest {
        val transport = FakeTransport()
        transport.respond(500, "", "https://final.test/opds/root/book/9")
        val controller = controller(transport, importer = neverImporting())
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value

        val failed = controller.addEntry(
            catalog.url,
            "https://final.test/opds/root/book/9",
            null,
            "Test Catalog",
        )

        assertEquals(OpdsFailure.Network(500), (failed as Outcome.Err).error)
        assertEquals(0, transport.downloads)
    }

    @Test
    fun `credentials never leave the catalogue origin`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ACQUISITION_FEED, "https://final.test/opds/root/")
        val controller = controller(transport, importer = succeedingImport())
        val catalog = (
            controller.add("c", "https://final.test/opds", "login", "pass") as Outcome.Ok
            ).value
        val expected =
            "Basic " + Base64.getEncoder().encodeToString("login:pass".toByteArray(Charsets.UTF_8))

        controller.page(catalog.id, "https://final.test/opds/shelf")
        assertEquals(
            expected,
            transport.headersByUrl["https://final.test/opds/shelf"]?.get("Authorization"),
        )

        // A feed may link a file or a section on another host: no Basic header.
        controller.page(catalog.id, "https://other.test/feed")
        assertNull(transport.headersByUrl["https://other.test/feed"]?.get("Authorization"))

        val book = OpdsBook(
            title = "Book",
            author = null,
            summary = null,
            coverUrl = null,
            format = OpdsFormat.EPUB,
            downloadUrl = "https://cdn.test/1.epub",
        )
        controller.download(catalog.id, book)
        assertNull(transport.headersByUrl["https://cdn.test/1.epub"]?.get("Authorization"))

        controller.download(catalog.id, book.copy(downloadUrl = "https://final.test/opds/1.epub"))
        assertEquals(
            expected,
            transport.headersByUrl["https://final.test/opds/1.epub"]?.get("Authorization"),
        )
    }

    @Test
    fun `credentials follow an http to https redirect but never a downgrade`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ACQUISITION_FEED, "https://final.test/opds/root/")
        val controller = controller(transport)
        val expected =
            "Basic " + Base64.getEncoder().encodeToString("login:pass".toByteArray(Charsets.UTF_8))

        // Typed http, every href and the final URL are https: the header stays.
        val plain = (
            controller.add("c", "http://final.test/opds", "login", "pass") as Outcome.Ok
            ).value
        controller.page(plain.id, "https://final.test/opds/root/page2")
        assertEquals(
            expected,
            transport.headersByUrl["https://final.test/opds/root/page2"]?.get("Authorization"),
        )

        val secure = (
            controller.add("d", "https://final.test/opds", "login", "pass") as Outcome.Ok
            ).value
        controller.page(secure.id, "http://final.test/opds/plain")
        assertNull(transport.headersByUrl["http://final.test/opds/plain"]?.get("Authorization"))
        controller.page(secure.id, "https://cdn.test/feed")
        assertNull(transport.headersByUrl["https://cdn.test/feed"]?.get("Authorization"))
    }

    @Test
    fun `a cache miss re-resolves the entry from the feed it was listed in`() = runTest {
        val transport = FakeTransport()
        val feedUrl = "https://final.test/opds/root/"
        transport.respond(200, PUBLICATION_FEED, feedUrl)
        val dao = FakeDao()
        val controller = controller(transport, importer = succeedingImport(), dao = dao)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        val entryUrl = "https://final.test/opds/root/book/9"
        // Stored metadata, but no options and no memory of the file.
        dao.putEntry(catalog.url, entryUrl, "[]", feedUrl = feedUrl)

        val added = controller.addEntry(catalog.url, entryUrl, null, "Test Catalog")

        assertEquals(Outcome.Ok("local://new-book"), added)
        assertEquals(1, transport.downloads)
        // Only the feed page was fetched: never the entry URL.
        assertEquals(listOf(feedUrl), transport.requested)
        // The resolved options are written back for the next visit.
        assertTrue(
            dao.entries[catalog.url to entryUrl]?.formatsJson
                ?.contains("https://final.test/opds/root/book/9.epub") == true,
        )
    }

    @Test
    fun `a cache miss whose entry left the feed fails without fetching the entry url`() = runTest {
        val transport = FakeTransport()
        val feedUrl = "https://final.test/opds/root/"
        transport.respond(200, PUBLICATION_FEED, feedUrl)
        val dao = FakeDao()
        val controller = controller(transport, importer = succeedingImport(), dao = dao)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        val entryUrl = "https://final.test/opds/root/book/404"
        dao.putEntry(catalog.url, entryUrl, "[]", feedUrl = feedUrl)

        val failed = controller.addEntry(catalog.url, entryUrl, null, "Test Catalog")

        assertEquals(OpdsFailure.Unknown("entry-not-found"), (failed as Outcome.Err).error)
        assertEquals(0, transport.downloads)
        assertEquals(listOf(feedUrl), transport.requested)
    }

    @Test
    fun `a real entry feed failure wins over the page's not-downloadable`() = runTest {
        val transport = FakeTransport()
        val feedUrl = "https://final.test/opds/root/"
        transport.bodiesByUrl[feedUrl] = INDIRECT_ENTRY_PAGE
        // The entry feed itself is broken: report that, not "not downloadable".
        transport.respond(500, "", "https://final.test/opds/root/book/9")
        val dao = FakeDao()
        val controller = controller(transport, importer = neverImporting(), dao = dao)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        val entryUrl = "https://final.test/opds/root/book/9"
        dao.putEntry(catalog.url, entryUrl, "[]", feedUrl = feedUrl)

        val failed = controller.addEntry(catalog.url, entryUrl, null, "Test Catalog")

        assertEquals(OpdsFailure.Network(500), (failed as Outcome.Err).error)
        assertEquals(listOf(feedUrl, entryUrl), transport.requested)
        assertEquals(0, transport.downloads)
    }

    @Test
    fun `an indirect entry resolves from its own feed after the page had no options`() = runTest {
        val transport = FakeTransport()
        val feedUrl = "https://final.test/opds/root/"
        val entryUrl = "https://final.test/opds/root/book/9"
        transport.bodiesByUrl[feedUrl] = INDIRECT_ENTRY_PAGE
        transport.bodiesByUrl[entryUrl] = PUBLICATION_FEED
        val dao = FakeDao()
        val controller = controller(transport, importer = succeedingImport(), dao = dao)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        dao.putEntry(catalog.url, entryUrl, "[]", feedUrl = feedUrl)

        val added = controller.addEntry(catalog.url, entryUrl, null, "Test Catalog")

        assertEquals(Outcome.Ok("local://new-book"), added)
        assertEquals(listOf(feedUrl, entryUrl), transport.requested)
        assertEquals(1, transport.downloads)
    }

    @Test
    fun `a stale option key fails instead of downloading another format`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, MIXED_RELATIONS_FEED, "https://final.test/opds/root/")
        val dao = FakeDao()
        val controller = controller(transport, importer = neverImporting(), dao = dao)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        val entryUrl = "https://final.test/opds/entry/1"
        dao.putEntry(catalog.url, entryUrl, CACHED_OPTIONS)

        val failed = controller.addEntry(
            catalog.url,
            entryUrl,
            "https://final.test/opds/root/books/0.epub",
            "Test Catalog",
        )

        assertEquals(OpdsFailure.Unknown("stale-option"), (failed as Outcome.Err).error)
        assertEquals(0, transport.downloads)
    }

    @Test
    fun `removing a catalogue drops its cached entries`() = runTest {
        val dao = FakeDao()
        val controller = controller(FakeTransport(), dao = dao)
        val catalog = (controller.add("c", "https://final.test/opds", "", "") as Outcome.Ok).value
        dao.putEntry(catalog.url, "https://final.test/opds/entry/1", CACHED_OPTIONS)

        controller.remove(catalog.id)

        assertTrue(dao.entries.isEmpty())
        assertTrue(controller.catalogs.value.isEmpty())
    }

    @Test
    fun `archive book links are recognised by host, path and glob query`() {
        assertEquals(
            "item1",
            ArchiveOrgFormats.isArchiveBookLink(
                "https://archive.org/services/opds/book/item1?glob_pattern=*pdf",
            ),
        )
        assertEquals(
            "item1",
            ArchiveOrgFormats.isArchiveBookLink(
                "https://www.archive.org/services/opds/book/item1?glob_pattern=*epub",
            ),
        )
        assertNull(ArchiveOrgFormats.isArchiveBookLink("https://archive.org/download/item1/item1.epub"))
        assertNull(ArchiveOrgFormats.isArchiveBookLink("https://archive.org/services/opds/book/item1"))
        assertNull(
            ArchiveOrgFormats.isArchiveBookLink(
                "https://evil.test/services/opds/book/item1?glob_pattern=*pdf",
            ),
        )
    }

    @Test
    fun `archive pdf-only entry gains the epub its opds omits`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ARCHIVE_ENTRY_FEED, ARCHIVE_ENTRY)
        transport.bodiesByUrl[ARCHIVE_EPUB_PROBE] = ARCHIVE_EPUB_ENVELOPE
        val controller = controller(transport)
        val catalog = (controller.add("c", ARCHIVE_ENTRY, "", "") as Outcome.Ok).value

        val options = (controller.resolveEntry(catalog.url, ARCHIVE_ENTRY) as Outcome.Ok).value

        val epub = options.first()
        assertEquals(OpdsFormat.EPUB, epub.format)
        assertEquals("EPUB", epub.label)
        assertEquals("http://opds-spec.org/acquisition/open-access", epub.rel)
        assertEquals("https://archive.org/download/item1/item1.epub", epub.url)
        assertTrue(epub.isDownloadable)
        // The catalogue's own pdf stays listed, with its reason.
        assertEquals(OpdsUnavailableReason.UnsupportedType, options.last().unavailableReason)

        // One feed + one probe; the detail page does not probe again.
        assertEquals(2, transport.gets)
        controller.resolveEntry(catalog.url, ARCHIVE_ENTRY)
        assertEquals(2, transport.gets)
    }

    @Test
    fun `add from an archive entry downloads the probed epub`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ARCHIVE_ENTRY_FEED, ARCHIVE_ENTRY)
        transport.bodiesByUrl[ARCHIVE_EPUB_PROBE] = ARCHIVE_EPUB_ENVELOPE
        val controller = controller(transport, importer = succeedingImport())
        val catalog = (controller.add("c", ARCHIVE_ENTRY, "", "") as Outcome.Ok).value

        val added = controller.addEntry(catalog.url, ARCHIVE_ENTRY, null, "Archive")

        assertEquals(Outcome.Ok("local://new-book"), added)
        assertEquals(1, transport.downloads)
    }

    @Test
    fun `an archive probe that finds no epub leaves the list as served`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ARCHIVE_ENTRY_FEED, ARCHIVE_ENTRY)
        // No match: the endpoint answers with an empty location.
        transport.bodiesByUrl[ARCHIVE_EPUB_PROBE] = ARCHIVE_NO_EPUB
        val controller = controller(transport)
        val catalog = (controller.add("c", ARCHIVE_ENTRY, "", "") as Outcome.Ok).value

        val options = (controller.resolveEntry(catalog.url, ARCHIVE_ENTRY) as Outcome.Ok).value

        assertTrue(options.none { it.format == OpdsFormat.EPUB })
        assertTrue(options.all { it.unavailableReason != null })
    }

    @Test
    fun `an archive probe that resolves to a non-epub is ignored`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ARCHIVE_ENTRY_FEED, ARCHIVE_ENTRY)
        transport.bodiesByUrl[ARCHIVE_EPUB_PROBE] = ARCHIVE_PDF_LOCATION
        val controller = controller(transport)
        val catalog = (controller.add("c", ARCHIVE_ENTRY, "", "") as Outcome.Ok).value

        val options = (controller.resolveEntry(catalog.url, ARCHIVE_ENTRY) as Outcome.Ok).value

        assertTrue(options.none { it.format == OpdsFormat.EPUB })
    }

    @Test
    fun `a failed archive probe never fails the detail page`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ARCHIVE_ENTRY_FEED, ARCHIVE_ENTRY)
        // A broken match: not even JSON.
        transport.bodiesByUrl[ARCHIVE_EPUB_PROBE] = "<html>bad gateway</html>"
        val controller = controller(transport)
        val catalog = (controller.add("c", ARCHIVE_ENTRY, "", "") as Outcome.Ok).value

        val options = (controller.resolveEntry(catalog.url, ARCHIVE_ENTRY) as Outcome.Ok).value

        assertTrue(options.none { it.format == OpdsFormat.EPUB })
        assertTrue(options.isNotEmpty())
    }

    @Test
    fun `an archive entry resolved from its listing page gains the epub too`() = runTest {
        val transport = FakeTransport()
        val pageUrl = "https://archive.org/services/opds/catalog?type=browse&section=ebooks"
        transport.bodiesByUrl[pageUrl] = ARCHIVE_LISTING_PAGE
        transport.bodiesByUrl[ARCHIVE_EPUB_PROBE] = ARCHIVE_EPUB_ENVELOPE
        val dao = FakeDao()
        val controller = controller(transport, dao = dao)
        val catalog = (controller.add("c", ARCHIVE_ENTRY, "", "") as Outcome.Ok).value
        dao.putEntry(catalog.url, ARCHIVE_ENTRY, "[]", feedUrl = pageUrl)

        val options = (controller.resolveEntry(catalog.url, ARCHIVE_ENTRY) as Outcome.Ok).value

        assertEquals(OpdsFormat.EPUB, options.first().format)
        // Written back, so the next visit does not resolve again.
        assertTrue(
            dao.entries[catalog.url to ARCHIVE_ENTRY]?.formatsJson
                ?.contains("item1.epub") == true,
        )
    }

    @Test
    fun `catalogue cards never probe the archive book endpoint`() = runTest {
        val transport = FakeTransport()
        transport.respond(200, ARCHIVE_LISTING_PAGE, ARCHIVE_ENTRY)
        transport.bodiesByUrl[ARCHIVE_EPUB_PROBE] = ARCHIVE_EPUB_ENVELOPE
        val controller = controller(transport)
        val catalog = (controller.add("c", ARCHIVE_ENTRY, "", "") as Outcome.Ok).value

        val page = (controller.page(catalog.id, null) as Outcome.Ok).value

        // One request per page: a card is cheap, the probe belongs to the detail.
        assertEquals(1, transport.gets)
        assertTrue(page.books.single().options.none { it.format == OpdsFormat.EPUB })
    }

    private fun succeedingImport(): LocalImportPort = object : LocalImportPort {
        override suspend fun import(file: File, origin: ImportOrigin?): LocalImportResult =
            LocalImportResult.Success(
                bookId = 1,
                bookUrl = "local://new-book",
                title = "Book",
                duplicate = false,
            )
    }

    private fun neverImporting(): LocalImportPort = object : LocalImportPort {
        override suspend fun import(file: File, origin: ImportOrigin?): LocalImportResult =
            LocalImportResult.Failure("not expected")
    }

    private fun controller(
        transport: FakeTransport,
        registerHost: (String, String, String?) -> Unit = { _, _, _ -> },
        importer: LocalImportPort? = null,
        dao: FakeDao = FakeDao(),
    ): OpdsControllerImpl {
        return OpdsControllerImpl(
            library = LibraryRepository(dao.proxy),
            credentials = OpdsCredentialStore(File(tempDir(), "opds_credentials.json")),
            transport = transport,
            importer = importer,
            cacheDir = tempDir(),
            newId = { "catalog-${dao.rows.size + 1}" },
            now = { 1_000L },
            registerHost = registerHost,
        )
    }

    private fun tempDir(): File =
        Files.createTempDirectory("opds-controller").toFile().also { tempDirs += it }

    private fun assertFailure(outcome: Outcome<OpdsPage>, expected: OpdsFailure) {
        assertTrue("expected $expected, got $outcome", outcome is Outcome.Err)
        assertEquals(expected, (outcome as Outcome.Err).error)
    }

    /** Only the OPDS calls the controller makes; anything else fails. */
    private class FakeDao {
        val rows = mutableListOf<OpdsCatalogEntity>()
        val entries = mutableMapOf<Pair<String, String>, OpdsEntryEntity>()

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "opdsCatalogs" -> rows.toList()
                "upsertOpdsCatalog" -> {
                    val entity = args!![0] as OpdsCatalogEntity
                    rows.removeAll { it.id == entity.id }
                    rows += entity
                    null
                }
                "deleteOpdsCatalogRow" -> {
                    rows.removeAll { it.id == args!![0] }
                    null
                }
                "opdsEntry" -> entries[args!![0] as String to args[1] as String]
                "upsertOpdsEntries" -> {
                    (args!![0] as List<*>).filterIsInstance<OpdsEntryEntity>().forEach {
                        entries[it.catalogUrl to it.entryUrl] = it
                    }
                    null
                }
                "deleteOpdsEntries" -> {
                    val catalogUrl = args!![0] as String
                    entries.keys.removeAll { it.first == catalogUrl }
                    null
                }
                "pruneOpdsEntries" -> null
                else -> error("unexpected dao call: ${method.name}")
            }
        } as LibraryDao

        fun putEntry(
            catalogUrl: String,
            entryUrl: String,
            formatsJson: String,
            feedUrl: String = "",
        ) {
            entries[catalogUrl to entryUrl] = OpdsEntryEntity(
                catalogUrl = catalogUrl,
                entryUrl = entryUrl,
                feedUrl = feedUrl,
                title = "Book One",
                author = "Jane",
                description = "Blurb",
                coverUrl = null,
                formatsJson = formatsJson,
                updatedAt = 1L,
            )
        }
    }

    private class FakeTransport : OpdsTransport {
        var status = 200
        var body = ""
        var finalUrl = ""
        var gets = 0
        var lastHeaders: Map<String, String> = emptyMap()
        val headersByUrl = mutableMapOf<String, Map<String, String>>()
        val requested = mutableListOf<String>()
        // Per-URL 200 bodies; anything else falls back to the single default
        // response above (status + body + finalUrl).
        val bodiesByUrl = mutableMapOf<String, String>()

        fun respond(status: Int, body: String, finalUrl: String) {
            this.status = status
            this.body = body
            this.finalUrl = finalUrl
        }

        override suspend fun get(url: String, headers: Map<String, String>): RemoteFetch {
            gets++
            lastHeaders = headers
            headersByUrl[url] = headers
            requested += url
            bodiesByUrl[url]?.let { page ->
                return RemoteFetch(
                    status = 200,
                    bytes = page.encodeToByteArray(),
                    finalUrl = url,
                    contentType = "application/atom+xml",
                )
            }
            return RemoteFetch(
                status = status,
                bytes = body.encodeToByteArray(),
                finalUrl = finalUrl.ifBlank { url },
                contentType = "application/atom+xml",
            )
        }

        var downloads = 0

        override suspend fun download(
            url: String,
            headers: Map<String, String>,
            target: File,
        ): Long {
            downloads++
            headersByUrl[url] = headers
            target.parentFile?.mkdirs()
            val bytes = "book-bytes".encodeToByteArray()
            target.writeBytes(bytes)
            return bytes.size.toLong()
        }
    }
}

private val ACQUISITION_FEED = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Catalog</title>
      <link rel="next" href="page2"/>
      <entry>
        <title>Book One</title>
        <author><name>Jane</name></author>
        <summary>Blurb</summary>
        <link rel="http://opds-spec.org/image" href="covers/1.jpg"/>
        <link rel="http://opds-spec.org/acquisition" href="books/1.epub"
              type="application/epub+zip"/>
        <link rel="http://opds-spec.org/acquisition" href="books/1.pdf" type="application/pdf"/>
      </entry>
      <entry>
        <title>Section</title>
        <link rel="subsection" href="shelf/2"/>
      </entry>
    </feed>
""".trimIndent()

private val FEED_WITH_SEARCH = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Searchable</title>
      <link rel="search" href="search?q={searchTerms}" type="application/atom+xml"/>
    </feed>
""".trimIndent()

private val UNSUPPORTED_FEED = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Pdf only</title>
      <entry>
        <title>Pdf Book</title>
        <link rel="http://opds-spec.org/acquisition" href="books/1.pdf" type="application/pdf"/>
      </entry>
    </feed>
""".trimIndent()

private val MIXED_RELATIONS_FEED = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Mixed</title>
      <entry>
        <id>https://final.test/opds/entry/1</id>
        <title>Book One</title>
        <link rel="http://opds-spec.org/image" href="covers/1.jpg"/>
        <link rel="http://opds-spec.org/acquisition" href="books/1.epub"
              type="application/epub+zip"/>
        <link rel="http://opds-spec.org/acquisition" href="books/1.fb2"
              type="application/x-fictionbook+xml"/>
        <link rel="http://opds-spec.org/acquisition/sample" href="books/1-sample.epub"
              type="application/epub+zip"/>
        <link rel="http://opds-spec.org/acquisition/buy" href="buy/1"
              type="application/epub+zip"/>
        <link rel="http://opds-spec.org/acquisition/borrow" href="borrow/1"
              type="application/epub+zip"/>
        <link rel="http://opds-spec.org/acquisition/subscribe" href="subscribe/1"
              type="application/epub+zip"/>
        <link rel="http://opds-spec.org/acquisition" href="books/1"
              type="application/atom+xml;profile=opds-catalog">
          <opds:indirectAcquisition xmlns:opds="http://opds-spec.org/2010/catalog"
              type="application/epub+zip"/>
        </link>
        <link rel="http://opds-spec.org/acquisition" href="books/1.pdf" type="application/pdf"/>
      </entry>
    </feed>
""".trimIndent()

private val ALTERNATE_ENTRY_FEED = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Alternate</title>
      <entry>
        <title>Alternate Book</title>
        <link rel="alternate" href="entry/7" type="application/atom+xml"/>
        <link rel="http://opds-spec.org/acquisition" href="books/7.epub"
              type="application/epub+zip"/>
      </entry>
    </feed>
""".trimIndent()

// Gutenberg shape: the publication lives in its own feed, one hop down.
private val PUBLICATION_FEED = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Publication</title>
      <entry>
        <id>https://final.test/opds/root/book/9</id>
        <title>Book Nine</title>
        <link rel="http://opds-spec.org/acquisition/open-access"
              href="https://final.test/opds/root/book/9.epub" type="application/epub+zip"/>
      </entry>
    </feed>
""".trimIndent()

// The listing page of an indirect publication: the entry exists, its files do
// not, so resolving it costs a second fetch.
private val INDIRECT_ENTRY_PAGE = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Listing</title>
      <entry>
        <id>https://final.test/opds/root/book/9</id>
        <title>Book Nine</title>
        <link rel="subsection" href="https://final.test/opds/root/book/9.opds"
              type="application/atom+xml"/>
      </entry>
    </feed>
""".trimIndent()

// Two publications in one feed: resolving one must not hand back the other.
private val MULTI_PUBLICATION_FEED = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Two books</title>
      <entry>
        <id>https://final.test/opds/feed/a</id>
        <title>Book A</title>
        <link rel="http://opds-spec.org/acquisition/open-access" href="a.epub"
              type="application/epub+zip"/>
      </entry>
      <entry>
        <id>https://final.test/opds/feed/b</id>
        <title>Book B</title>
        <link rel="http://opds-spec.org/acquisition/open-access" href="b.epub"
              type="application/epub+zip"/>
      </entry>
    </feed>
""".trimIndent()

// OPDS 2 root, as archive.org serves it: navigation sections plus group previews.
private val OPDS2_ROOT = """
    {"@context":"https://readium.org/webpub-manifest/context.jsonld",
     "metadata":{"title":"Archive.org"},
     "links":[{"href":"https://archive.org/services/opds/catalog{?query}&type=search",
               "type":"application/opds+json","rel":"search","templated":true}],
     "navigation":[{"href":"https://final.test/opds?nav_key=page_ebooks","title":"eBooks",
                    "type":"application/opds+json","rel":"collection"},
                   {"href":"https://final.test/opds?nav_key=page_audiobooks","title":"Audiobooks",
                    "type":"application/opds+json","rel":"collection"}],
     "groups":[{"metadata":{"title":"eBooks Open Access","numberOfItems":3667231},
                "links":[{"href":"https://final.test/opds?group=ebooks",
                          "type":"application/opds+json","rel":"self"}],
                "publications":[{"metadata":{"title":"Preview","identifier":"https://final.test/details/p"},
                                 "links":[{"href":"https://final.test/details/p&view=theater",
                                           "type":"text/html",
                                           "rel":"http://opds-spec.org/acquisition/sample"}]}]},
               {"metadata":{"title":"Audiobooks & Music","numberOfItems":12000},
                "links":[{"href":"https://final.test/opds?group=audio",
                          "type":"application/opds+json","rel":"self"}]},
               {"metadata":{"title":"Texts","numberOfItems":99},
                "links":[{"href":"https://final.test/opds?group=texts",
                          "type":"application/opds+json","rel":"self"}]}]}
""".trimIndent()

private val OPDS2_PUBLICATIONS = """
    {"@context":"https://readium.org/webpub-manifest/context.jsonld",
     "metadata":{"title":"eBooks Open Access","numberOfItems":10000,"itemsPerPage":25,"currentPage":1},
     "links":[{"href":"https://archive.org/services/opds/catalog{?query}&type=search",
               "type":"application/opds+json","rel":"search","templated":true},
              {"href":"https://final.test/opds/catalog?page=2","type":"application/opds+json","rel":"next"}],
     "publications":[
       {"metadata":{"title":"A reply to Mr. Maxwell's Answer",
                    "identifier":"https://final.test/opds/publications/1",
                    "language":["en"],
                    "description":"2 unnumbered leaves",
                    "author":[{"name":"Kirkland, Thomas"}]},
        "links":[{"href":"https://final.test/details/1&view=theater","type":"text/html",
                  "rel":"http://opds-spec.org/acquisition/sample"},
                 {"href":"https://final.test/loans/loan?action=webpub&identifier=1",
                  "type":"application/opds-publication+json","rel":"self","title":"Internet Archive"},
                 {"href":"https://final.test/opds/book/1?glob_pattern=*pdf","type":"application/pdf",
                  "rel":"http://opds-spec.org/acquisition/open-access",
                  "properties":{"availability":{"state":"available"}}}],
        "images":[{"href":"https://final.test/download/1/thumb.jpg","type":"image/jpeg","rel":"cover"}]},
       {"metadata":{"title":"Open book","identifier":"https://final.test/opds/publications/2",
                    "author":[{"name":"Ann Author"}]},
        "links":[{"href":"https://final.test/opds/books/2.epub","type":"application/epub+zip",
                  "rel":"http://opds-spec.org/acquisition/open-access"}]}],
     "facets":[{"metadata":{"title":"Browse by Language"},"links":[
       {"title":"English","href":"https://final.test/opds/catalog?facet_item=english",
        "type":"application/opds+json"},
       {"title":"French","href":"https://final.test/opds/catalog?facet_item=french",
        "type":"application/opds+json","properties":{"numberOfItems":12}}]}]}
""".trimIndent()

// What the feed mapper would have written for MIXED_RELATIONS_FEED.
private val CACHED_OPTIONS = """
    [{"format":"EPUB","url":"https://final.test/opds/root/books/1.epub",
      "rel":"http://opds-spec.org/acquisition","reason":null},
     {"format":"EPUB","url":"https://final.test/opds/root/books/1-sample.epub",
      "rel":"http://opds-spec.org/acquisition/sample","reason":"Sample"}]
""".trimIndent()

// archive.org serves a text item with only a pdf link; the epub lives behind
// the book endpoint, which its own OPDS never mentions.
private const val ARCHIVE_ENTRY = "https://archive.org/details/item1"
private const val ARCHIVE_EPUB_PROBE =
    "https://archive.org/services/opds/book/item1?glob_pattern=*epub"
private const val ARCHIVE_EPUB_ENVELOPE =
    """{"status_code":302,"_headers":{"location":"https://archive.org/download/item1/item1.epub"}}"""
private const val ARCHIVE_NO_EPUB = """{"status_code":302,"_headers":{"location":"[]"}}"""
private const val ARCHIVE_PDF_LOCATION =
    """{"status_code":302,"_headers":{"location":"https://archive.org/download/item1/item1.pdf"}}"""

private val ARCHIVE_ENTRY_FEED = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Publication</title>
      <entry>
        <id>https://archive.org/details/item1</id>
        <title>Archive Book</title>
        <link rel="http://opds-spec.org/image/thumbnail"
              href="https://archive.org/download/item1/__ia_thumb.jpg"/>
        <link rel="http://opds-spec.org/acquisition/open-access"
              href="https://archive.org/services/opds/book/item1?glob_pattern=*pdf"
              type="application/pdf"/>
      </entry>
    </feed>
""".trimIndent()

private val ARCHIVE_LISTING_PAGE = """
    <?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>eBooks</title>
      <entry>
        <id>https://archive.org/details/item1</id>
        <title>Archive Book</title>
        <link rel="http://opds-spec.org/acquisition/open-access"
              href="https://archive.org/services/opds/book/item1?glob_pattern=*pdf"
              type="application/pdf"/>
      </entry>
    </feed>
""".trimIndent()
