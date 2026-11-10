package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.book.domain.BookReview
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.core.reader.ChapterContent
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

// Mutable runtime that survives screen visits within one process: shelf,
// in-library flag and chapter-download actions stick when the user goes
// back to Library/Dashboard and opens the same bookUrl again. The immutable
// seeds stay static; mutations are merged on top when snapshots are served.
class BookRuntime(
    var shelfId: String,
    var inLibrary: Boolean,
    // Only chapters the user touched: index → downloaded (false = deleted).
    val downloadOverrides: MutableMap<Int, Boolean> = mutableMapOf(),
    // Only chapters the user touched: index → read (true = read).
    val readOverrides: MutableMap<Int, Boolean> = mutableMapOf(),
)

class FakeBookDataProvider(
    private val contentOverride: (suspend (String, Int) -> ChapterContent?)? = null,
) : BookDataProvider {
    override suspend fun book(bookUrl: String): BookDetails? {
        // Same one-beat latency as the library/dashboard fakes.
        delay(120)
        return BookCatalog.details(bookUrl)
    }

    override suspend fun chapters(bookUrl: String): List<BookChapter> {
        delay(120)
        return BookCatalog.details(bookUrl)?.chapters.orEmpty()
    }

    override suspend fun moveShelf(bookUrl: String, shelfId: String) {
        BookCatalog.moveShelf(bookUrl, shelfId)
    }

    override suspend fun ensureHistoryBook(bookUrl: String) = Unit

    override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult {
        val current = BookCatalog.details(bookUrl)?.inLibrary ?: false
        if (current != inLibrary) BookCatalog.toggleInLibrary(bookUrl)
        return MembershipResult(success = true)
    }

    override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) {
        BookCatalog.setDownloaded(bookUrl, range, downloaded)
    }

    override suspend fun downloadNext100(bookUrl: String) {
        delay(300)
        BookCatalog.markNext100Downloaded(bookUrl)
    }

    override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) {
        BookCatalog.markChapterRead(bookUrl, chapterIndex, read)
    }

    override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? {
        contentOverride?.let { return it(bookUrl, chapterIndex) }
        val book = book(bookUrl) ?: return null
        val chapter = book.chapters.firstOrNull { it.index == chapterIndex } ?: return null
        return ChapterContent(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            title = chapter.title,
            html = fakeChapterHtml(book.title, chapter),
        )
    }

    private fun fakeChapterHtml(bookTitle: String, chapter: BookChapter): String = buildString {
        append("<h2>").append(chapter.title.replace("&", "&amp;").replace("<", "&lt;")).append("</h2>")
        val seed = (bookTitle.hashCode() * 31 + chapter.index).toLong()
        val random = kotlin.random.Random(seed)
        repeat(18 + random.nextInt(8)) { paragraph ->
            append("<p>")
            if (paragraph == 0) append("The morning began quietly. ")
            append(
                "Paragraph ${paragraph + 1} of ${chapter.title} keeps the fixture deterministic " +
                    "for ${bookTitle}. It exists to exercise wrapping, spacing and position restore " +
                    "without the network. ",
            )
            repeat(2 + random.nextInt(3)) { sentence ->
                append("Sentence ${sentence + 1} adds enough text to make the reader scroll. ")
            }
            append("</p>")
        }
    }
}

// One static home for the fake book world: the shell header reads titles
// synchronously from here, screens snapshot async. Fixture bookUrls match
// the library/dashboard fixtures, so a tap from ANY list opens the SAME
// book with the SAME state.
object BookCatalog {

    // Per-book runtime created on first mutation/snapshot.
    private val runtime = mutableMapOf<String, BookRuntime>()

    private fun stateFor(url: String): BookRuntime = runtime.getOrPut(url) {
        val seed = seeds[url] ?: synthesize(url)
        BookRuntime(shelfId = seed.shelfId, inLibrary = seed.inLibrary)
    }

    // Served CURRENT state as an immutable snapshot.
    fun details(bookUrl: String): BookDetails? {
        val seed = seeds[bookUrl] ?: synthesize(bookUrl)
        val state = stateFor(bookUrl)
        return seed.copy(
            shelfId = state.shelfId,
            inLibrary = state.inLibrary,
            chapters = seed.chapters.map { chapter ->
                val downloaded = state.downloadOverrides[chapter.index] ?: chapter.downloaded
                chapter.copy(
                    isRead = state.readOverrides[chapter.index] ?: chapter.isRead,
                    downloaded = downloaded,
                    // Deleting a download removes its translation too.
                    translated = chapter.translated && downloaded,
                )
            },
        )
    }

    // Sync title for the shell header (no suspension there).
    fun titleFor(bookUrl: String): String =
        (seeds[bookUrl] ?: synthesize(bookUrl)).title

    fun moveShelf(bookUrl: String, shelfId: String) {
        stateFor(bookUrl).shelfId = shelfId
    }

    // Cheap flag for catalogue tiles (the "In library" badge).
    fun isInLibrary(bookUrl: String): Boolean = stateFor(bookUrl).inLibrary

    fun toggleInLibrary(bookUrl: String) {
        val state = stateFor(bookUrl)
        state.inLibrary = !state.inLibrary
    }

    fun setDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) {
        val state = stateFor(bookUrl)
        val total = (seeds[bookUrl] ?: synthesize(bookUrl)).totalChapters
        for (index in range.first..range.last.coerceAtMost(total)) {
            if (index >= 1) state.downloadOverrides[index] = downloaded
        }
    }

    fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) {
        stateFor(bookUrl).readOverrides[chapterIndex] = read
    }

    fun markNext100Downloaded(bookUrl: String) {
        val seed = seeds[bookUrl] ?: synthesize(bookUrl)
        val state = stateFor(bookUrl)
        val first = (seed.readCount + 1).coerceAtMost(seed.totalChapters)
        val last = (first + 99).coerceAtMost(seed.totalChapters)
        for (index in first..last) state.downloadOverrides[index] = true
    }

    // A 53-entry title pool (53 and 17 share no factor, so stepping by 17
    // walks the whole pool before repeating). Chapter names below slot 203
    // recycle with numbering, as long serials do.
    // NOTE: declared ABOVE `seeds` on purpose — seeds builds chapters()
    // during <clinit>, so the pool must already exist or every app start NPEs.
    private val chapterTitles = listOf(
        "Brake in the Village", "Ashes of Dawn", "The Silent Ledger", "Nine Days of Rain",
        "The Borrowed Map", "Confluence", "What the River Took", "Salt and Ember",
        "The Patient Balcony", "Second Guest", "A Debt in Letters", "The Long Corridor",
        "Winter Verdict", "Nobody's Appraiser", "Blue Hour", "The Stubborn Magnet",
        "Old Bones, New Boiler", "The Quiet Recruit", "Smoke Without a Fire", "The Third Key",
        "On the Way Down", "The Uninvited", "Falling Sideways", "The Rest of the Story",
        "Small Hours", "A Field of Paper Birds", "The Ninth Apology", "Below Zero",
        "The Two-Step Duel", "Glass Meridian", "The Caretaker's Diary", "Slow Disaster",
        "Tea With a Ghost", "The Standoff at Mealtime", "Hollow Director", "Over the Wall",
        "The Longest Survey", "Return Postage", "A Woman Named Tuesday", "Static",
        "The Uphill Homecoming", "One Borrowed Sun", "Two Honest Criminals", "The Deep Stair",
        "Answering Machine of the Gods", "The Final Grade", "Understudy", "Between Floors",
        "The Last Loose Thread", "Machinery of Forgiveness", "Allotment", "Void, Politely",
        "The Curtain Rises Again",
    )

    // === Fixture seeds =====================================================

    // Reader-note pools for synthesized books (0–2 reviews each). Seeded
    // books carry their own curated sets inline below.
    // NOTE: stays ABOVE `seeds` — seeds builds during <clinit>.
    private val reviewAuthorPool = listOf(
        "quiet_reader", "page_unseen", "margin_notes", "kettle_of_tea", "DungeonLurker",
    )

    private val reviewTextPool = listOf(
        "Started this on a rainy evening and lost the whole weekend to it. The middle arc " +
            "drags a little around the guild politics, but the payoff in the last third " +
            "earns the patience it asks for. Would hand it to anyone who likes the quieter " +
            "corners of the genre.",
        "The translation is doing a lot of quiet work here — idioms land, the humor survives " +
            "the trip, and only a couple of sentences a chapter feel stiff. Impressed by how " +
            "readable it stays at this length.",
        "Honest take: the first twenty chapters are rough and the author clearly finds " +
            "their voice later. Push past the intro duel and it becomes something special — " +
            "the side cast carries whole arcs on their own.",
        "Been following since the early scans. The recent chapters slow down to one small " +
            "scene each, which is fine, but the cliffhangers stopped landing for me. Still " +
            "reading every week — the world is worth it.",
    )

    // Deterministic placeholder art; the slug matches the library/dashboard
    // fixtures for the same book so a cover stays identical across screens.
    private fun testCoverUrl(bookUrl: String): String {
        val slug = bookUrl.removePrefix("fake://")
            .map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-') it else '-' }
            .joinToString("")
        return "https://picsum.photos/seed/$slug/300/450"
    }

    private val seeds: Map<String, BookDetails> = listOf(
        BookDetails(
            bookUrl = "fake://toonsource/mage-ten-thousand-rebirths",
            title = "Возвращение мага, пережившего десять тысяч перерождений",
            source = "ToonSource",
            author = "Rain Night Studio",
            originLanguage = "Korean",
            contentType = ContentType.Manga,
            rating = 4.7f,
            ongoing = true,
            updatedLabel = "Updated 2 h ago",
            totalChapters = 380,
            readCount = 142,
            readMinutes = 624,
            ttsMinutes = 141,
            inLibrary = true,
            shelfId = "reading",
            genres = listOf(
                "Action", "Fantasy", "Martial arts", "Cultivation", "Regression", "Drama",
                "Adventure", "Comedy", "Supernatural", "Thriller",
            ),
            description = "Ten thousand lifetimes were needed to master a single spell. Reborn " +
                "once more at the bottom of the order, the mage decides to change his path: " +
                "no more burning cores, no more borrowed bodies. With every remembered " +
                "dou-strike and every reading of the ancient archive, the road back to the " +
                "top grows shorter. This time the enemy is younger than he is; this time " +
                "he keeps count — of debts owed, and of the faces on the ten thousandth " +
                "reincarnation that were not there before.",
            sourceUrl = "https://toonsource.example/mage-ten-thousand-rebirths",
            authUrl = null,
            chapters = chapters(
                total = 380,
                readCount = 142,
                downloadingIndex = 147,
            ),
            reviews = listOf(
                BookReview(
                    author = "ShadowIndex",
                    rating = 5f,
                    dateLabel = "2 d ago",
                    text = "Ten thousand lives and the pacing still stays tight. The regression " +
                        "never turns into an info dump — every loop reveals exactly one new " +
                        "piece of the archive and the rest stays buried where it belongs. " +
                        "The art carries the quiet chapters better than the loud ones, which " +
                        "is rare and very welcome.",
                    translated = false,
                ),
                BookReview(
                    author = "kettle_of_tea",
                    rating = 4.5f,
                    dateLabel = "5 d ago",
                    text = "Consistently good, occasionally great. The duels respect the magic " +
                        "system, and the protagonist actually remembers what he learned.",
                    translated = false,
                ),
                BookReview(
                    author = "RainNightFan",
                    rating = 4f,
                    dateLabel = "1 w ago",
                    text = "The first dozen chapters wobble while the reboot settles, but the " +
                        "archive subplot that starts around chapter forty is the best thing " +
                        "this team has written. Only wish the update schedule matched the " +
                        "ambition of the story.",
                    translated = false,
                ),
                BookReview(
                    author = "DungeonLurker",
                    rating = 0f,
                    dateLabel = "2 w ago",
                    text = "Came for the rebirth gimmick, stayed for the debts ledger.",
                    translated = false,
                ),
            ),
        ),
        BookDetails(
            bookUrl = "fake://novelnest/chronicles-seven-kingdoms",
            title = "Хроники семи королевств: пепел забытых богов и шёпот древних библиотек",
            source = "NovelNest",
            author = "Ellen Vasquez",
            originLanguage = "English",
            contentType = ContentType.Novel,
            rating = 4.5f,
            ongoing = true,
            updatedLabel = "Updated yesterday",
            totalChapters = 1560,
            readCount = 1204,
            readMinutes = 5160,
            ttsMinutes = 2230,
            inLibrary = true,
            shelfId = "reading",
            genres = listOf(
                "Fantasy", "Adventure", "Politics", "Mystery", "Drama", "Slow burn",
                "Kingdom building", "Magic academy", "Multiple POV", "Cultivation",
                "Slice of life", "Epic",
            ),
            description = "Seven kingdoms signed the same truce three hundred years ago, and " +
                "ever since the old libraries have been quietly emptied of the truth. A " +
                "junior archivist finds a footnote that should not exist — a census of the " +
                "dead that keeps updating itself — and follows it across borders with a " +
                "coat that does not fit her. Between the six guilds and two " +
                "exiled houses she learns the real reason the gods went silent, and why " +
                "every attempt to write it down keeps vanishing in transit.",
            sourceUrl = "https://novelnest.example/chronicles-seven-kingdoms",
            authUrl = "https://novelnest.example/login",
            chapters = chapters(
                total = 1560,
                readCount = 1204,
                downloadingIndex = 1209,
            ),
            reviews = listOf(
                BookReview(
                    author = "Marguerite O.",
                    rating = 5f,
                    dateLabel = "yesterday",
                    text = "The census that keeps updating itself is the single best hook I have " +
                        "read all year. Every POV switch tightens the knot instead of loosening " +
                        "it, and the archivist's coat that does not fit her is quietly the " +
                        "saddest detail in the whole book.",
                    translated = false,
                ),
                BookReview(
                    author = "archivist_uno",
                    rating = 4.5f,
                    dateLabel = "3 d ago",
                    text = "Slow burn done right: the first fifty chapters are letters and " +
                        "arguments, and I would not cut a single one.",
                    translated = false,
                ),
                BookReview(
                    author = "P. Ivanova",
                    rating = 4f,
                    dateLabel = "1 w ago",
                    text = "A kingdom-building epic that trusts its readers to keep track of six " +
                        "guilds and two exiled houses, and mostly earns that trust. The middle " +
                        "books of the middle act sag under their own lore, but the library " +
                        "sequences are worth every detour.",
                    translated = false,
                ),
                BookReview(
                    author = "the_sixth_guild",
                    rating = 3.5f,
                    dateLabel = "2 w ago",
                    text = "Beautiful prose, glacial plot. Decide which one you came for.",
                    translated = false,
                ),
                BookReview(
                    author = "NightLibrarian",
                    rating = 5f,
                    dateLabel = "3 w ago",
                    text = "Translated a few chapters for a friend and ended up rereading the " +
                        "whole first arc with them. The voice holds up even at reading speed.",
                    translated = true,
                ),
            ),
        ),
        BookDetails(
            bookUrl = "fake://asurascans/solo-leveling",
            title = "Solo Leveling",
            source = "Asura Scans",
            author = "Chugong",
            originLanguage = "Korean",
            contentType = ContentType.Manga,
            rating = 4.8f,
            ongoing = true,
            updatedLabel = "Updated 2 h ago",
            totalChapters = 179,
            readCount = 122,
            readMinutes = 305,
            ttsMinutes = 0,
            inLibrary = false,
            shelfId = "reading",
            genres = listOf("Action", "Fantasy", "System", "Monsters", "Dungeon", "Adventure"),
            description = "The weakest hunter in the country keeps waking up on guild floors " +
                "with an unpaid hospital bill. Then one dungeon goes very, very wrong, and " +
                "he wakes up to a quest window no one else can see. The System has a " +
                "daily punishment, the shadow army has always one seat — and every one of " +
                "them used to be the strongest. His plan stays the same as always: refuse " +
                "leveling resists, level anyway, never take a party invite.",
            sourceUrl = "https://asurascans.example/solo-leveling",
            authUrl = "https://asurascans.example/login",
            chapters = chapters(
                total = 179,
                readCount = 122,
                downloadingIndex = 127,
            ),
            reviews = listOf(
                BookReview(
                    author = "SystemWindow",
                    rating = 5f,
                    dateLabel = "4 h ago",
                    text = "A one-man shadow army — every seat used to be the strongest, and " +
                        "the series never lets you forget it.",
                    translated = false,
                ),
                BookReview(
                    author = "JinwooDefenseSquad",
                    rating = 4.5f,
                    dateLabel = "2 d ago",
                    text = "Re-read the whole dungeon arc before this season and it is somehow " +
                        "tighter than I remembered. The art on the monarch chapters is " +
                        "genuinely absurd, in the best way.",
                    translated = false,
                ),
                BookReview(
                    author = "CriticNoName",
                    rating = 4f,
                    dateLabel = "1 w ago",
                    text = "The weakest-hunter premise has been copied a hundred times since, " +
                        "but this is the version everyone was copying for a reason. The daily " +
                        "punishment gags get old; the raid structure never does.",
                    translated = false,
                ),
            ),
        ),
        // Completed book: exercises the Ongoing/Completed stat cell and the
        // read-again continue button.
        BookDetails(
            bookUrl = "fake://wtr-lab/mother-of-learning",
            title = "Mother of Learning",
            source = "WTR Lab",
            author = "Nobody103",
            originLanguage = "English",
            contentType = ContentType.Novel,
            rating = 4.7f,
            ongoing = false,
            updatedLabel = "Completed",
            totalChapters = 108,
            readCount = 108,
            readMinutes = 1620,
            ttsMinutes = 540,
            inLibrary = true,
            shelfId = "completed",
            genres = listOf("Fantasy", "Time loop", "Mystery", "Magic academy", "Adventure"),
            description = "Zorian Kyrien just wants to pass his apprentice exams before the " +
                "invasion arrives. The invasion has other plans — about a month of them, " +
                "day after day after day. Every loop rearranges the city slightly, every " +
                "competitor remembers a little longer than they should. A study in " +
                "patience: memorize, experiment, die politely, wake up in bed, repeat.",
            sourceUrl = "https://wtr-lab.example/mother-of-learning",
            authUrl = null,
            chapters = chapters(
                total = 108,
                readCount = 108,
                downloadingIndex = 0,
            ),
            reviews = listOf(
                BookReview(
                    author = "TimeLoopEnjoyer",
                    rating = 5f,
                    dateLabel = "2 w ago",
                    text = "A study in patience and I failed the study: binged it in four days. " +
                        "The loops never feel repetitive because Zorian never repeats a " +
                        "mistake the same way twice. The ending lands, which for a time-loop " +
                        "story is a small miracle.",
                    translated = false,
                ),
                BookReview(
                    author = "ZorianIsFineActually",
                    rating = 5f,
                    dateLabel = "1 mo ago",
                    text = "Every competitor remembers a little longer than they should — best " +
                        "description of this book I have seen.",
                    translated = false,
                ),
                BookReview(
                    author = "bells_and_bones",
                    rating = 4.5f,
                    dateLabel = "1 mo ago",
                    text = "Memorize, experiment, die politely, repeat. The formula stays " +
                        "fresh for the whole ride.",
                    translated = false,
                ),
                BookReview(
                    author = "red_robe_reader",
                    rating = 4f,
                    dateLabel = "2 mo ago",
                    text = "The academy chapters are the strongest in the genre, but the city " +
                        "rearranges itself slightly every loop and I wish the book lingered " +
                        "there more. Still an easy recommendation for anyone who liked the " +
                        "clever parts of the magic-school shelf.",
                    translated = true,
                ),
            ),
        ),
    ).map { it.copy(coverUrl = testCoverUrl(it.bookUrl)) }.associateBy { it.bookUrl }

    // Catch-all for bookUrls from the dashboard/history/import fixtures not
    // seeded above. Deterministic from the URL, so repeated visits read the
    // same fake book. The two library seeds above override nothing here.
    private fun synthesize(url: String): BookDetails {
        val slug = url.substringAfterLast('/').replace('-', ' ')
        val title = slug.split(' ')
            .joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercase(Locale.US) }
            }
            .ifBlank {
                url.substringAfter("://").substringBefore('/').ifBlank { url }
            }
        val source = url.substringAfter("fake://").substringBefore('/')
        val hash = abs(url.hashCode())
        val total = 140 + hash % 460
        val readCount = total * (hash % 10) / 10
        val pool = listOf(
            "Action", "Fantasy", "Drama", "Adventure", "Mystery", "Supernatural",
            "Romance", "Thriller", "Comedy", "Slice of life",
        )
        val genreCount = 4 + hash % 4
        val genres = List(genreCount) { pool[(hash + it * 3) % pool.size] }.distinct()
        val descriptions = listOf(
            "The academy keeps accepting students without telling them about the balcony " +
                "on the fourth floor. The balcony is very patient. This year the pattern " +
                "breaks — a student walks in before it opens.",
            "Nine years after the war, the river-split city still settles its debts in " +
                "letters. The postmistress reads more of them than she answers, until one " +
                "address that should not exist starts writing back.",
            "Every winter the mountain publishes its verdicts, and the village pretends " +
                "not to hear them. She was the only one who ever wrote back — and up " +
                "until tonight, the only one who ever got a reply.",
        )
        return BookDetails(
            bookUrl = url,
            title = title,
            source = source,
            author = "Unknown",
            originLanguage = "English",
            contentType = ContentType.Novel,
            rating = if (hash % 5 == 0) 0f else 4f + (hash % 10) / 100f,
            ongoing = hash % 4 != 0,
            updatedLabel = if (hash % 4 != 0) "Updated 3 h ago" else "Completed",
            totalChapters = total,
            readCount = readCount,
            readMinutes = (readCount * 4) % 700 + 40,
            ttsMinutes = if (hash % 3 == 0) (readCount * 2) % 300 + 20 else 0,
            inLibrary = hash % 2 == 0,
            shelfId = "reading",
            genres = genres,
            description = descriptions[hash % descriptions.size],
            sourceUrl = "https://${
                source.replace(
                    oldChar = '.',
                    newChar = '-',
                )
            }.example/${
                url.substringAfterLast('/')
            }",
            authUrl = if (hash % 3 == 0) "https://$source.example/login" else null,
            chapters = chapters(
                total = total,
                readCount = readCount,
                downloadingIndex = if (readCount + 5 <= total) readCount + 5 else 0,
            ),
            reviews = synthesizeReviews(hash),
            coverUrl = testCoverUrl(url),
        )
    }

    // 0–2 reader notes for a synthesized book, deterministic from the hash —
    // every third book shows the section's empty state on purpose.
    private fun synthesizeReviews(hash: Int): List<BookReview> =
        List(hash % 3) { position ->
            BookReview(
                author = reviewAuthorPool[(hash + position * 7) % reviewAuthorPool.size],
                rating = 4f + ((hash + position * 3) % 10) / 10f,
                dateLabel = listOf("2 d ago", "1 w ago", "3 w ago")[(hash + position) % 3],
                text = reviewTextPool[(hash + position * 5) % reviewTextPool.size],
                translated = (hash + position) % 6 == 0,
            )
        }

    // === Chapter generation ================================================

    private fun chapterTitle(index: Int): String {
        val base = chapterTitles[(index * 17) % chapterTitles.size]
        val round = index / chapterTitles.size
        return if (round > 0) "$base · part ${round + 1}" else base
    }

    // Baseline flags per chapter: read / downloaded /
    // translation / animating download. One mid-queue chapter downloads to
    // show the animated icon.
    private fun chapters(total: Int, readCount: Int, downloadingIndex: Int): List<BookChapter> {
        fun downloadedAt(index: Int): Boolean =
            (index <= readCount && index % 5 == 0) ||
                (index in readCount + 1..readCount + 24)

        fun translatedAt(index: Int): Boolean =
            downloadedAt(index) && index % 3 == 0

        return List(total) { position ->
            val index = position + 1
            BookChapter(
                index = index,
                title = chapterTitle(index),
                isRead = index <= readCount,
                downloaded = downloadedAt(index),
                translated = translatedAt(index),
                downloading = index == downloadingIndex,
                isLast = index == total,
            )
        }
    }
}
