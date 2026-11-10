package dev.mirutime.app.features.finder.data

import dev.mirutime.app.core.AppCacheConfig
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSource

// One source's answer to the global query (the per-source card of the global
// search). Lives here so the UI and the cache share one type.
data class SourceResult(
    val source: CatalogSource,
    val books: List<CatalogBook>,
    val failed: Boolean,
)

// One global-search section per source, even if a resumed snapshot repeated it.
internal fun dedupeSourceResults(results: List<SourceResult>): List<SourceResult> =
    results.distinctBy { it.source.id }

// Cache key for the global search: query + optional type chip.
fun searchCacheKey(query: String?, scopeType: String?): String =
    "${query.orEmpty()}|${scopeType.orEmpty()}"

// Cache key for one source catalogue: source + the applied filters.
data class CatalogCacheKey(val sourceId: String, val filters: CatalogFilters)

// Process-level, in-memory cache so backing out of a book keeps the last
// results instead of re-running the fan-out / page fetch. Lost on process
// death — acceptable for this cache. `scrollIndex`/`scrollOffset` restore the list position.
object FinderSearchCache {
    data class GlobalSnapshot(
        val key: String,
        val results: List<SourceResult>,
        val total: Int,
        val done: Int,
        val scrollIndex: Int,
        val scrollOffset: Int,
        val storedAt: Long = System.currentTimeMillis(),
    ) {
        // A finished search that found nothing anywhere: replaying it would
        // show "Nothing found" instantly, so it is treated as stale.
        val finishedEmpty: Boolean
            get() = done >= total && results.none { it.books.isNotEmpty() }
    }

    // shortcut: one process-wide singleton instead of a ViewModel/repository;
    // hoist to a ViewModel when search state must outlive navigation or be
    // testable in isolation.
    private var global: GlobalSnapshot? = null

    fun global(key: String, now: Long = System.currentTimeMillis()): GlobalSnapshot? =
        global?.takeIf {
            it.key == key && AppCacheConfig.isFresh(it.storedAt, AppCacheConfig.SEARCH_CACHE_TTL_MS, now)
        }

    fun putGlobal(snapshot: GlobalSnapshot) {
        global = snapshot
    }

    fun clear() {
        global = null
    }
}

// What re-entering the global search can reuse: the answers that are still
// valid for the current targets, the targets nobody has asked yet, and whether
// the snapshot is a finished empty search that must be run again. Pure so the
// resume decision is testable without Compose.
data class GlobalSearchResume(
    val results: List<SourceResult>,
    val missing: List<CatalogSource>,
    val total: Int,
    val done: Int,
    val staleEmpty: Boolean,
)

fun resumeGlobalSearch(
    snapshot: FinderSearchCache.GlobalSnapshot?,
    targets: List<CatalogSource>,
): GlobalSearchResume {
    if (snapshot == null) {
        return GlobalSearchResume(
            results = emptyList(),
            missing = targets,
            total = targets.size,
            done = 0,
            staleEmpty = false,
        )
    }
    // Drop answers from sources that are no longer targets (uninstalled plugin
    // or a narrower scope), and keep the current target order.
    val targetIds = targets.mapTo(HashSet()) { it.id }
    // A failed source produced no answer: leave it in `missing` so re-entering
    // the search retries it instead of replaying the failure until the TTL.
    val answered = snapshot.results
        .filter { it.source.id in targetIds && !it.failed }
    val localTargetIds = targets.filter { it.local }.mapTo(HashSet()) { it.id }
    // Local sources are live SAF scans, so a cached answer goes stale as soon
    // as files are added, removed, imported or deleted. Never replay it: every
    // local target is re-queried, while plugins keep the normal cache reuse.
    // The staleness check still looks at every answer (locals included) so a
    // snapshot that only found local books keeps its plugin answers.
    val answeredById = answered
        .filterNot { it.source.local || it.source.id in localTargetIds }
        .associateBy { it.source.id }
    // Replaying a finished empty search would show "Nothing found" forever.
    if (snapshot.done >= snapshot.total && answered.none { it.books.isNotEmpty() }) {
        return GlobalSearchResume(
            results = emptyList(),
            missing = targets,
            total = targets.size,
            done = 0,
            staleEmpty = true,
        )
    }
    val results = targets.mapNotNull { answeredById[it.id] }
    val missing = targets.filter { it.id !in answeredById }
    return GlobalSearchResume(
        results = results,
        missing = missing,
        total = targets.size,
        done = results.size,
        staleEmpty = false,
    )
}
