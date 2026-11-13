package dev.mirutime.app.features.opds

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.mirutime.app.features.opds.data.OpdsControllerImpl
import dev.mirutime.app.features.opds.data.OpdsCredentialStore
import dev.mirutime.app.features.opds.data.OpdsEntryOptions
import dev.mirutime.app.features.opds.data.OpdsTransport
import dev.mirutime.app.features.opds.data.RemoteFetch
import dev.mirutime.app.features.opds.domain.OpdsBookOption
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.opds.OpdsFormat
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryDatabaseConstructor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.OpdsCatalogEntity
import dev.mirutime.data.library.OpdsEntryEntity
import dev.mirutime.data.library.RemoteAudioBook
import dev.mirutime.data.library.RemoteAudioBookStore
import dev.mirutime.data.library.libraryBookKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalogue add maps a manifest onto the remote-audiobook store. */
class OpdsAudioAddTest {
    private val catalogUrl = "https://archive.org/services/opds"
    private val entryUrl = "https://archive.org/details/vie_des_abeilles_2002_librivox"
    private val manifestUrl =
        "https://archive.org/services/opds/audiobooks/vie_des_abeilles_2002_librivox"

    private val db: LibraryDatabase = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
        factory = LibraryDatabaseConstructor::initialize,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val repo = LibraryRepository(db.libraryDao())
    private val store = FakeAudioStore()
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        db.close()
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `an audiobook manifest becomes a remote book with one track per readingOrder entry`() = runTest {
        val controller = controller(FakeTransport(AUDIOBOOK_MANIFEST))
        val catalog = seedCatalog(controller)
        seedEntry(manifestUrl)

        val added = controller.addEntry(catalog.url, entryUrl, manifestUrl, "Archive.org")

        assertEquals(Outcome.Ok(libraryBookKey("opds:${catalog.id}", entryUrl)), added)
        val saved = store.saved.single()
        assertEquals("opds:${catalog.id}", saved.installId)
        // The row identity is the catalogue entry, not the manifest URL.
        assertEquals(entryUrl, saved.url)
        assertEquals("La vie des abeilles", saved.title)
        assertEquals("Maurice Maeterlinck", saved.author)
        assertEquals("Archive.org", saved.description)
        assertEquals("https://archive.org/download/x/__ia_thumb.jpg", saved.coverUrl)
        assertEquals(
            listOf(
                "https://archive.org/download/x/a_01.mp3",
                "https://archive.org/download/x/a_02.mp3",
                "https://archive.org/download/x/a_03.mp3",
            ),
            saved.tracks.map { it.url },
        )
        assertEquals("01 - Premier", saved.tracks.first().title)
    }

    @Test
    fun `a bare mp3 acquisition becomes a one-track book without a manifest fetch`() = runTest {
        val transport = FakeTransport(AUDIOBOOK_MANIFEST)
        val controller = controller(transport)
        val catalog = seedCatalog(controller)
        val mp3 = "https://archive.org/download/x/whole.mp3"
        seedEntry(mp3)

        val added = controller.addEntry(catalog.url, entryUrl, mp3, "Archive.org")

        assertTrue(added is Outcome.Ok)
        val saved = store.saved.single()
        assertEquals(1, saved.tracks.size)
        assertEquals(mp3, saved.tracks.single().url)
        assertEquals(0, transport.gets)
    }

    @Test
    fun `a manifest without tracks is not added`() = runTest {
        val controller = controller(FakeTransport("""{"metadata":{"title":"Empty"}}"""))
        val catalog = seedCatalog(controller)
        seedEntry(manifestUrl)

        val added = controller.addEntry(catalog.url, entryUrl, manifestUrl, "Archive.org")

        assertTrue(added is Outcome.Err)
        assertTrue(store.saved.isEmpty())
    }

    private suspend fun seedCatalog(controller: OpdsControllerImpl): OpdsCatalogEntity {
        val added = controller.add(name = "Archive.org", url = catalogUrl, login = "", password = "")
        val catalog = (added as Outcome.Ok).value
        return OpdsCatalogEntity(
            id = catalog.id,
            name = catalog.name,
            url = catalog.url,
            addedAt = catalog.addedAt,
        )
    }

    private suspend fun seedEntry(optionUrl: String) {
        repo.upsertOpdsEntries(
            listOf(
                OpdsEntryEntity(
                    catalogUrl = catalogUrl,
                    entryUrl = entryUrl,
                    feedUrl = "https://archive.org/services/opds/catalog?type=browse&section=audiobooks",
                    title = "La vie des abeilles",
                    author = "Maurice Maeterlinck",
                    description = "Archive.org",
                    coverUrl = "https://archive.org/download/x/__ia_thumb.jpg",
                    formatsJson = OpdsEntryOptions.encode(
                        listOf(
                            OpdsBookOption(
                                format = OpdsFormat.AUDIO,
                                url = optionUrl,
                                rel = "http://opds-spec.org/acquisition/open-access",
                            ),
                        ),
                    ),
                    updatedAt = 1L,
                ),
            ),
        )
    }

    private fun controller(transport: OpdsTransport): OpdsControllerImpl {
        val dir = Files.createTempDirectory("opds-audio").toFile().also { tempDirs += it }
        return OpdsControllerImpl(
            library = repo,
            credentials = OpdsCredentialStore(File(dir, "opds_credentials.json")),
            transport = transport,
            importer = null,
            cacheDir = dir,
            newId = { "cat-1" },
            now = { 1_000L },
            audioStore = store,
        )
    }

    private class FakeAudioStore : RemoteAudioBookStore {
        val saved = mutableListOf<RemoteAudioBook>()

        override suspend fun save(book: RemoteAudioBook): Long {
            saved += book
            return saved.size.toLong()
        }
    }

    private class FakeTransport(private val manifest: String) : OpdsTransport {
        var gets = 0

        override suspend fun get(url: String, headers: Map<String, String>): RemoteFetch {
            gets++
            return RemoteFetch(
                status = 200,
                bytes = manifest.encodeToByteArray(),
                finalUrl = url,
                contentType = "application/json",
            )
        }

        override suspend fun download(url: String, headers: Map<String, String>, target: File): Long =
            error("an audiobook must never be downloaded")
    }
}

private val AUDIOBOOK_MANIFEST = """
{"metadata":{"title":"La vie des abeilles","author":[{"name":"Maurice Maeterlinck"}],"duration":120.0},
 "links":[{"href":"https://archive.org/download/x/__ia_thumb.jpg","type":"image/jpeg","rel":"cover"}],
 "readingOrder":[
  {"href":"https://archive.org/download/x/a_01.mp3","type":"audio/mpeg","title":"01 - Premier","duration":"0:29"},
  {"href":"https://archive.org/download/x/a_02.mp3","type":"audio/mpeg","title":"02 - Deuxieme","duration":"0:54"},
  {"href":"https://archive.org/download/x/a_03.mp3","type":"audio/mpeg","title":"03 - Troisieme","duration":"0:27"}
 ]}
""".trimIndent()
