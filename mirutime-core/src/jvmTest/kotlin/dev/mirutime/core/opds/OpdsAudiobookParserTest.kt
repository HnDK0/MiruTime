package dev.mirutime.core.opds

import dev.mirutime.core.api.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpdsAudiobookParserTest {

    @Test
    fun `archive org manifest keeps title, author, cover and every track`() {
        val book = parseOk(ARCHIVE_MANIFEST.toByteArray(), "https://archive.org/services/opds/audiobooks/x")

        assertEquals("La vie des abeilles", book.title)
        assertEquals(listOf("Maurice Maeterlinck"), book.authors)
        assertEquals(22054.0, book.durationSeconds)
        assertEquals(
            "https://archive.org/download/vie_des_abeilles_2002_librivox/__ia_thumb.jpg",
            book.coverUrl,
        )
        assertEquals(9, book.tracks.size)
        val first = book.tracks.first()
        assertEquals(
            "https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_01_maeterlinck_64kb.mp3",
            first.href,
        )
        assertEquals("01 - Livre premier: Au seuil de la ruche", first.title)
        assertEquals(29 * 60 + 9.0, first.durationSeconds)
        assertEquals(60 * 60 + 12.0, book.tracks.last().durationSeconds)
    }

    @Test
    fun `track duration accepts hours, plain seconds and absence`() {
        val book = parseOk(
            """
            {"metadata":{"title":"T"},
             "readingOrder":[
               {"href":"https://h.test/a.mp3","type":"audio/mpeg","duration":"1:02:03"},
               {"href":"https://h.test/b.mp3","type":"audio/mpeg","duration":95},
               {"href":"https://h.test/c.mp3","type":"audio/mpeg","duration":"95"},
               {"href":"https://h.test/d.mp3","type":"audio/mpeg"}
             ]}
            """.trimIndent().toByteArray(),
        )

        assertEquals(
            listOf(3723.0, 95.0, 95.0, null),
            book.tracks.map { it.durationSeconds },
        )
    }

    @Test
    fun `tracks without a playable audio type or href are skipped`() {
        val book = parseOk(
            """
            {"metadata":{"title":"T"},
             "readingOrder":[
               {"href":"https://h.test/a.mp3","type":"audio/mpeg"},
               {"href":"https://h.test/b.mp3"},
               {"href":"https://h.test/c.pdf","type":"application/pdf"},
               {"href":"","type":"audio/mpeg"},
               {"href":"https://h.test/d.m4a","type":"audio/mp4; codecs=mp4a.40.2"}
             ]}
            """.trimIndent().toByteArray(),
        )

        assertEquals(listOf("https://h.test/a.mp3", "https://h.test/d.m4a"), book.tracks.map { it.href })
    }

    @Test
    fun `missing track title derives from the file name and relative hrefs resolve`() {
        val book = parseOk(
            """
            {"metadata":{"title":"T"},
             "readingOrder":[{"href":"tracks/01_first_track.mp3","type":"audio/mpeg"}]}
            """.trimIndent().toByteArray(),
            "https://h.test/audiobook/id",
        )

        assertEquals("https://h.test/audiobook/tracks/01_first_track.mp3", book.tracks[0].href)
        assertEquals("01 first track", book.tracks[0].title)
    }

    @Test
    fun `a manifest without playable tracks is an error`() {
        val noOrder = OpdsAudiobookParser.parse("""{"metadata":{"title":"T"}}""".toByteArray())
        val empty = OpdsAudiobookParser.parse(
            """{"metadata":{"title":"T"},"readingOrder":[]}""".toByteArray(),
        )

        assertEquals(Outcome.Err(OpdsError.NoTracks), noOrder)
        assertEquals(Outcome.Err(OpdsError.NoTracks), empty)
    }

    @Test
    fun `malformed json and a non object body are Malformed`() {
        assertEquals(
            Outcome.Err(OpdsError.Malformed),
            OpdsAudiobookParser.parse("""{"metadata":""".toByteArray()),
        )
        assertEquals(
            Outcome.Err(OpdsError.Malformed),
            OpdsAudiobookParser.parse("ID3audio".toByteArray()),
        )
    }

    @Test
    fun `non http tracks are skipped and duplicate hrefs collapse`() {
        val book = parseOk(
            """
            {"metadata":{"title":"T"},
             "readingOrder":[
               {"href":"file:///sdcard/a.mp3","type":"audio/mpeg"},
               {"href":"data:audio/mpeg;base64,AAAA","type":"audio/mpeg"},
               {"href":"https://h.test/a.mp3","type":"audio/mpeg","title":"A"},
               {"href":"https://h.test/a.mp3","type":"audio/mpeg","title":"A again"}
             ]}
            """.trimIndent().toByteArray(),
        )

        assertEquals(listOf("https://h.test/a.mp3"), book.tracks.map { it.href })
    }

    @Test
    fun `author accepts a plain string as well as an array`() {
        val single = parseOk(
            """
            {"metadata":{"title":"T","author":"Jane Doe"},
             "readingOrder":[{"href":"https://h.test/a.mp3","type":"audio/mpeg"}]}
            """.trimIndent().toByteArray(),
        )

        assertEquals(listOf("Jane Doe"), single.authors)
    }

    @Test
    fun `cover falls back to an image link when rel is absent`() {
        val book = parseOk(
            """
            {"metadata":{"title":"T"},
             "links":[{"href":"https://h.test/c.jpg","type":"image/jpeg"}],
             "readingOrder":[{"href":"https://h.test/a.mp3","type":"audio/mpeg"}]}
            """.trimIndent().toByteArray(),
        )

        assertEquals("https://h.test/c.jpg", book.coverUrl)
        assertNull(book.durationSeconds)
    }

    private fun parseOk(bytes: ByteArray, baseUrl: String = ""): OpdsAudiobook {
        val parsed = OpdsAudiobookParser.parse(bytes, baseUrl)
        assertTrue(parsed is Outcome.Ok, "expected Ok, got $parsed")
        return (parsed as Outcome.Ok).value
    }
}

private val ARCHIVE_MANIFEST = """
{"@context":"https://readium.org/webpub-manifest/context.jsonld",
 "metadata":{"title":"La vie des abeilles",
  "identifier":"https://archive.org/details/vie_des_abeilles_2002_librivox",
  "type":"http://schema.org/Audiobook",
  "author":[{"name":"Maurice Maeterlinck"}],
  "duration":22054.0},
 "links":[{"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/__ia_thumb.jpg",
  "type":"image/jpeg","rel":"cover","height":1400,"width":800}],
 "readingOrder":[
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_01_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"01 - Livre premier: Au seuil de la ruche","duration":"29:09"},
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_02_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"02 - Livre deuxieme: L'essaim, 01-23","duration":"54:09"},
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_03_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"03 - Livre deuxieme: L'essaim, 24-31","duration":"27:40"},
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_04_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"04 - Livre troisieme: La fondation de la cite, 01-09","duration":"34:30"},
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_05_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"05 - Livre troisieme: La fondation de la cite, 10-25","duration":"52:01"},
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_06_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"06 - Livre quatrieme: Les jeunes reines","duration":"54:06"},
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_07_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"07 - Livre cinquieme: Le vol nuptial","duration":"43:57"},
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_08_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"08 - Livre sixieme: Le massacre des males","duration":"11:44"},
  {"href":"https://archive.org/download/vie_des_abeilles_2002_librivox/viedesabeilles_09_maeterlinck_64kb.mp3","type":"audio/mpeg","title":"09 - Livre septieme: Le progres de l'espece","duration":"60:12"}
 ]}
""".trimIndent()
