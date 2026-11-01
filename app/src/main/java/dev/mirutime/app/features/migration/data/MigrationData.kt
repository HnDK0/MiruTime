package dev.mirutime.app.features.migration.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.migration.domain.MigrationCandidateBook
import dev.mirutime.app.features.migration.domain.MigrationSearchHit
import dev.mirutime.app.features.migration.domain.MigrationSearchResult
import dev.mirutime.app.features.migration.domain.MigrationTargetDetails
import dev.mirutime.app.features.migration.domain.availableMigrationTargets
import dev.mirutime.app.features.migration.domain.migrationChapters
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.migration.BookMigrationTarget
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One global cap for every plugin call migration makes (search, target
 * details, chapter refresh). Search fans out and the mass flow runs two books
 * in parallel; without a shared gate that becomes 2 x sources concurrent Lua
 * sessions. `LuaPluginSource` already serializes per source, so this is the
 * only limiter.
 */
class MigrationSourceGate(permits: Int = MaxConcurrentCalls) {
    private val semaphore = Semaphore(permits)

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }

    companion object {
        const val MaxConcurrentCalls = 4
    }
}

/**
 * Real target search across installed plugins. The Finder provider already
 * owns plugin lookup, error mapping and membership, so migration reuses it
 * instead of talking to PluginGraph directly.
 */
class MigrationSearch(
    private val finder: FinderDataProvider,
    private val gate: MigrationSourceGate = MigrationSourceGate(),
) {
    suspend fun sources(): List<CatalogSource> =
        availableMigrationTargets(finder.sources())

    suspend fun search(
        query: String,
        sources: List<CatalogSource>,
    ): MigrationSearchResult {
        val clean = query.trim()
        if (clean.isEmpty() || sources.isEmpty()) {
            return MigrationSearchResult(emptyList(), emptyList())
        }
        // Every source call goes through the shared gate; awaiting in source
        // order keeps the result deterministic regardless of answer timing.
        val answers = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    source to runCatching {
                        gate.withPermit { finder.searchSource(source.id, clean) }
                    }
                }
            }.map { it.await() }
        }
        val hits = mutableListOf<MigrationSearchHit>()
        val failed = mutableListOf<String>()
        answers.forEach { (source, result) ->
            result.onSuccess { books ->
                books.forEach { book ->
                    hits += MigrationSearchHit(
                        sourceId = source.id,
                        sourceName = source.name,
                        book = book,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                failed += source.name
            }
        }
        return MigrationSearchResult(
            hits = hits.distinctBy { it.sourceId to it.book.bookUrl },
            failedSources = failed,
        )
    }
}

/**
 * Resolves a chosen search hit into the coordinator's target. It performs no
 * DB write: the target book row appears only inside migrate().
 */
class BookMigrationTargets(
    private val provider: BookDataProvider,
    private val repository: LibraryRepository,
    private val gate: MigrationSourceGate = MigrationSourceGate(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(
        sourceId: String,
        sourceName: String,
        bookUrl: String,
        fallbackTitle: String,
    ): MigrationTargetDetails? {
        val details = try {
            gate.withPermit { provider.book(bookUrl) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val chapters = try {
            gate.withPermit { provider.refreshChapters(bookUrl) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val unique = chapters.distinctBy { it.index }
        if (unique.isEmpty()) return null
        val title = details?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val target = BookMigrationTarget(
            book = targetEntity(sourceId, bookUrl, title, details?.genres.orEmpty(), details),
            chapters = migrationChapters(bookUrl, unique),
        )
        return MigrationTargetDetails(
            sourceId = sourceId,
            sourceName = sourceName,
            bookUrl = bookUrl,
            title = title,
            chapters = unique,
            target = target,
        )
    }

    suspend fun oldBookId(bookUrl: String): Long? = repository.book(bookUrl)?.bookId

    suspend fun candidate(
        bookUrl: String,
        sourceName: String?,
    ): MigrationCandidateBook? {
        val book = repository.book(bookUrl) ?: return null
        return MigrationCandidateBook(
            bookId = book.bookId,
            bookUrl = book.url,
            title = book.title,
            author = book.author,
            sourceId = book.installId,
            sourceName = sourceName?.takeIf { it.isNotBlank() } ?: book.installId,
            unknownSource = sourceName.isNullOrBlank(),
            chapterCount = book.totalChapters,
            coverUrl = book.coverUrl,
            contentType = book.contentType,
            rating = book.rating.toFloat(),
            readChapters = (book.lastChapterIndex ?: 0).coerceIn(0, book.totalChapters),
            hasUpdate = book.newChapters > 0,
            shelfId = book.shelfId,
        )
    }

    suspend fun candidates(sources: List<CatalogSource>): List<MigrationCandidateBook> {
        val names = sources.associate { it.id to it.name }
        return repository.libraryBooks()
            .asSequence()
            .filterNot { it.installId.startsWith("local-") }
            .map { book ->
                val known = names[book.installId]
                MigrationCandidateBook(
                    bookId = book.bookId,
                    bookUrl = book.url,
                    title = book.title,
                    author = book.author,
                    sourceId = book.installId,
                    sourceName = known ?: book.installId,
                    unknownSource = known == null,
                    chapterCount = book.totalChapters,
                    coverUrl = book.coverUrl,
                    contentType = book.contentType,
                    rating = book.rating.toFloat(),
                    readChapters = (book.lastChapterIndex ?: 0).coerceIn(0, book.totalChapters),
                    hasUpdate = book.newChapters > 0,
                    shelfId = book.shelfId,
                )
            }
            .sortedBy { it.title.lowercase() }
            .toList()
    }

    suspend fun history() = repository.bookMigrations()

    suspend fun deleteHistoryGroup(batchKey: String): Int =
        repository.deleteBookMigrationBatch(batchKey)

    private suspend fun targetEntity(
        sourceId: String,
        bookUrl: String,
        title: String,
        genres: List<String>,
        details: BookDetails?,
    ): BookEntity {
        val existing = repository.book(sourceId, bookUrl)
        if (existing != null) return existing
        val at = now()
        return BookEntity(
            bookId = 0,
            installId = sourceId,
            url = bookUrl,
            title = title,
            author = details?.author.orEmpty().ifBlank { "Unknown" },
            coverUrl = details?.coverUrl,
            contentType = details?.contentType?.name ?: "Novel",
            rating = details?.rating?.toDouble() ?: 0.0,
            shelfId = details?.shelfId ?: "reading",
            totalChapters = 0,
            inLibrary = true,
            addedAt = at,
            updatedAt = at,
            lastReadAt = null,
            lastChapterId = null,
            lastChapterIndex = null,
            chapterListHash = null,
            lastUpdateAt = at,
            genresJson = genresJson(genres),
            firstSeenAt = at,
            addedToLibraryAt = at,
            originLanguage = details?.originLanguage,
            description = details?.description.orEmpty(),
            metadataSource = "PLUGIN",
            metadataUpdatedAt = at,
        )
    }
}

private fun genresJson(genres: List<String>): String =
    genres.distinct().joinToString(prefix = "[", postfix = "]") { value ->
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
