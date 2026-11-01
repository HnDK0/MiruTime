package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.library.domain.LibrarySort
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.app.features.library.domain.SortDirection
import kotlinx.coroutines.delay

class FakeLibraryDataProvider : LibraryDataProvider {
    // The fixtures are immutable seeds; the fake keeps its own mutable copy so
    // previews/tests exercise the same shelf mutations as Room.
    private var shelfState = LibraryFixtures.shelves
    private var bookState = LibraryFixtures.books

    override suspend fun shelves(): List<Shelf> {
        delay(120)
        return shelfState
    }

    override suspend fun books(): List<LibraryBook> {
        delay(120)
        return bookState
    }

    override suspend fun lastRead(): LibraryBook? {
        delay(120)
        return bookState.minByOrNull { it.lastReadRank }
    }

    override suspend fun searchBooks(
        query: String,
        limit: Int,
        offset: Int,
    ): List<LibraryBook> {
        delay(120)
        return searchMatches(query)
            .sortedBy { it.bookUrl }
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceAtLeast(0))
    }

    override suspend fun libraryPage(
        query: String,
        cursor: LibraryPageCursor?,
        limit: Int,
        filters: LibraryPageFilters,
        sort: LibrarySort,
        direction: SortDirection,
    ): LibraryPageSlice {
        delay(120)
        val pageSize = limit.coerceIn(1, 500)
        val offset = ((cursor as? LibraryPageCursor.Search)?.offset ?: 0).coerceAtLeast(0)
        val terms = query.trim()
        val filtered = searchMatches(terms).filter { it.matches(filters) }
        // Search stays relevance-ish (URL order in the fake); sort only
        // applies to the normal library.
        val matched = if (sortAppliesToQuery(terms)) {
            filtered.orderedBy(sort, direction)
        } else {
            filtered.sortedBy { it.bookUrl }
        }
        val visible = matched.drop(offset).take(pageSize)
        val nextOffset = offset + visible.size
        val hasMore = nextOffset < matched.size
        return LibraryPageSlice(
            books = visible,
            nextCursor = if (hasMore) LibraryPageCursor.Search(nextOffset) else null,
            hasMore = hasMore,
            total = matched.size,
        )
    }

    override suspend fun libraryFacets(): LibraryFacets {
        delay(120)
        return LibraryFacets(
            sources = bookState.map { it.source }.distinct().sorted(),
            genres = bookState.flatMap { it.filterGenres }.distinct().sorted(),
        )
    }

    override suspend fun matchingLibraryUrls(
        query: String,
        filters: LibraryPageFilters,
    ): List<String> = searchMatches(query)
        .filter { it.matches(filters) }
        .map { it.bookUrl }
        .distinct()
        .sorted()

    override suspend fun shelfSummaries(): List<LibraryShelfSummary> {
        delay(120)
        return bookState
            .groupBy { it.shelfId }
            .map { (shelfId, books) ->
                val cover = books.minByOrNull { it.lastReadRank }
                LibraryShelfSummary(
                    shelfId = shelfId,
                    bookCount = books.size,
                    coverUrl = cover?.coverUrl,
                    coverTitle = cover?.title,
                )
            }
            .sortedBy { it.shelfId }
    }

    private fun searchMatches(query: String): List<LibraryBook> {
        val terms = query.trim()
        if (terms.isEmpty()) return bookState
        return bookState.filter {
            it.title.contains(terms, ignoreCase = true) ||
                it.author.contains(terms, ignoreCase = true) ||
                it.source.contains(terms, ignoreCase = true)
        }
    }

    override suspend fun saveShelf(shelf: Shelf) {
        shelfState = if (shelfState.any { it.id == shelf.id }) {
            shelfState.map { if (it.id == shelf.id) shelf else it }
        } else {
            shelfState + shelf
        }
    }

    override suspend fun deleteShelf(id: String) {
        shelfState = shelfState.filterNot { it.id == id }
        bookState = bookState.map { if (it.shelfId == id) it.copy(shelfId = "reading") else it }
    }

    override suspend fun reorderShelves(shelves: List<Shelf>) {
        shelfState = shelves.sortedBy { if (it.isSystemShelf()) 0 else 1 }
    }

    override suspend fun moveBook(bookUrl: String, shelfId: String) {
        bookState = bookState.map {
            if (it.bookUrl == bookUrl) it.copy(shelfId = shelfId) else it
        }
    }

    override suspend fun deleteBooks(bookUrls: Set<String>): BulkActionResult {
        val found = bookState.mapTo(LinkedHashSet()) { it.bookUrl }.intersect(bookUrls)
        bookState = bookState.filterNot { it.bookUrl in found }
        return BulkActionResult(found, bookUrls - found)
    }

    override suspend fun moveBooks(bookUrls: Set<String>, shelfId: String): BulkActionResult {
        val found = bookState.mapTo(LinkedHashSet()) { it.bookUrl }.intersect(bookUrls)
        bookState = bookState.map {
            if (it.bookUrl in found) it.copy(shelfId = shelfId) else it
        }
        return BulkActionResult(found, bookUrls - found)
    }

    override suspend fun markBooksRead(bookUrls: Set<String>, read: Boolean): BulkActionResult {
        val found = bookState.mapTo(LinkedHashSet()) { it.bookUrl }.intersect(bookUrls)
        bookState = bookState.map { book ->
            if (book.bookUrl !in found) {
                book
            } else {
                book.copy(readChapters = if (read) book.totalChapters else 0)
            }
        }
        return BulkActionResult(found, bookUrls - found)
    }

    override suspend fun importFiles(files: List<PickedImportFile>): ImportSummary {
        delay(120)
        var imported = 0
        var duplicates = 0
        for (file in files) {
            if (bookState.any { it.bookUrl == "fake://local/${file.fileName}" }) {
                duplicates++
                continue
            }
            bookState = bookState + fakeLocalBook(file.fileName, file.format)
            imported++
        }
        return ImportSummary(imported = imported, duplicates = duplicates)
    }

    private fun fakeLocalBook(fileName: String, format: String): LibraryBook {
        val rank = (bookState.maxOfOrNull { it.addedRank } ?: 0) + 1
        return LibraryBook(
            bookUrl = "fake://local/$fileName",
            title = fileName.substringBeforeLast("."),
            source = "Local $format",
            author = "Unknown",
            rating = 0f,
            contentType = ContentType.Novel,
            totalChapters = 1,
            readChapters = 0,
            hasUpdate = false,
            shelfId = "reading",
            genres = emptyList(),
            lastReadRank = rank,
            lastUpdateRank = rank,
            addedRank = rank,
            lastReadLabel = "Just now",
            continueLabel = "Chapter 1",
            coverUrl = "https://picsum.photos/seed/local-${fileName.hashCode().toLong().and(0xFFFFFFFFL)}/300/450",
        )
    }
}

private fun LibraryBook.matches(filters: LibraryPageFilters): Boolean {
    if (!filters.shelfId.isNullOrBlank() && shelfId != filters.shelfId) return false
    if (filters.sources.isNotEmpty() && source !in filters.sources) return false
    if (filters.contentType != null && contentType != filters.contentType) return false
    if (filters.genres.isNotEmpty() && filterGenres.none { it in filters.genres }) return false
    return true
}

// Mirrors the SQL semantics: LastRead/LastUpdate/Added base is newest-first;
// Title/Unread base is ascending, so Desc is their reverse.
private fun List<LibraryBook>.orderedBy(
    sort: LibrarySort,
    direction: SortDirection,
): List<LibraryBook> {
    val base = when (sort) {
        LibrarySort.LastRead -> sortedWith(
            compareBy<LibraryBook> { it.lastReadRank }.thenBy { it.bookUrl },
        )
        LibrarySort.LastUpdate -> sortedWith(
            compareBy<LibraryBook> { it.lastUpdateRank }.thenBy { it.bookUrl },
        )
        LibrarySort.Added -> sortedWith(
            compareBy<LibraryBook> { it.addedRank }.thenBy { it.bookUrl },
        )
        LibrarySort.Title -> sortedWith(
            compareBy<LibraryBook> { it.title }.thenBy { it.bookUrl },
        )
        LibrarySort.Unread -> sortedWith(
            compareBy<LibraryBook> { it.unreadChapters }.thenBy { it.bookUrl },
        )
    }
    val reverse = when (sort) {
        LibrarySort.LastRead, LibrarySort.LastUpdate, LibrarySort.Added ->
            direction == SortDirection.Asc
        LibrarySort.Title, LibrarySort.Unread -> direction == SortDirection.Desc
    }
    return if (reverse) base.reversed() else base
}

private fun Shelf.isSystemShelf(): Boolean = id == "reading" || id == "completed"

object LibraryFixtures {
    val shelves = listOf(
        Shelf(id = "reading", label = "Reading"),
        Shelf(id = "completed", label = "Completed"),
        Shelf(id = "wuxia", label = "Wuxia stash"),
    )

    val books = listOf(
        LibraryBook(
            bookUrl = "fake://toonsource/mage-ten-thousand-rebirths",
            title = "Возвращение мага, пережившего десять тысяч перерождений",
            source = "ToonSource",
            author = "ToonSource",
            rating = 4.7f,
            contentType = ContentType.Manga,
            totalChapters = 380,
            readChapters = 142,
            hasUpdate = true,
            shelfId = "reading",
            genres = listOf("Action", "Fantasy"),
            lastUpdateRank = 0,
            addedRank = 0,
            lastReadRank = 0,
            lastReadLabel = "Today, 14:20",
            continueLabel = "Глава 143",
            coverUrl = "https://picsum.photos/seed/toonsource-mage-ten-thousand-rebirths/300/450",
        ),
        LibraryBook(
            bookUrl = "fake://novelnest/chronicles-seven-kingdoms",
            title = "Хроники семи королевств: пепел забытых богов и шёпот древних библиотек",
            source = "NovelNest",
            author = "NovelNest",
            rating = 4.5f,
            contentType = ContentType.Novel,
            totalChapters = 1560,
            readChapters = 1204,
            hasUpdate = false,
            shelfId = "reading",
            genres = listOf("Fantasy", "Adventure"),
            lastUpdateRank = 1,
            addedRank = 1,
            lastReadRank = 1,
            lastReadLabel = "Yesterday, 22:41",
            continueLabel = "Глава 1205",
            coverUrl = "https://picsum.photos/seed/novelnest-chronicles-seven-kingdoms/300/450",
        ),
        LibraryBook(
            bookUrl = "fake://wuxia-archive/iron-lantern",
            title = "Iron Lantern",
            source = "Wuxia Archive",
            author = "Wuxia Archive",
            rating = 4.4f,
            contentType = ContentType.Novel,
            totalChapters = 320,
            readChapters = 41,
            hasUpdate = false,
            shelfId = "wuxia",
            genres = listOf("Wuxia", "Cultivation"),
            lastUpdateRank = 2,
            addedRank = 2,
            lastReadRank = 2,
            lastReadLabel = "3 days ago",
            continueLabel = "Chapter 42",
            coverUrl = "https://picsum.photos/seed/wuxia-archive-iron-lantern/300/450",
        ),
    )
}
