package dev.mirutime.app.features.finder.domain

import dev.mirutime.app.features.book.data.MembershipResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Batch "add by link" logic (FR-014..FR-022). Pure and testable: it owns the
 * parsing, the whole-list preflight and the per-link best-effort run, so the
 * sheet stays a thin view.
 *
 * Rules encoded here:
 * - separators are newline and comma; blanks are dropped and duplicates collapsed;
 * - the WHOLE list is checked before any write;
 * - a malformed link or a host no installed plugin serves blocks every add
 *   (FR-015); a source/network error is temporary and does NOT block (FR-018);
 * - after a passing preflight, one link's failure never stops the others, and
 *   successes are never rolled back (FR-019/FR-021);
 * - cancelling keeps the confirmed successes and marks the rest unprocessed,
 *   distinct from failures (FR-022).
 */
object LinkBatch {

    /** Split free-form input, trim, drop blanks, collapse duplicates. */
    fun parse(input: String): List<String> =
        input.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** One link's preflight verdict. */
    sealed interface Verdict {
        val url: String

        /** Resolved to a real book; will be added. */
        data class Ready(override val url: String, val found: LinkResolution.Found) : Verdict

        /** Blocks the whole list: malformed, or a host no installed plugin serves. */
        data class Blocking(override val url: String, val resolution: LinkResolution) : Verdict

        /** Temporary source/network error: does not block (FR-018). */
        data class Temporary(override val url: String, val resolution: LinkResolution.SourceError) :
            Verdict
    }

    data class Preflight(
        val verdicts: List<Verdict>,
    ) {
        /** True only when the whole list passed; false when anything blocks. */
        val canAdd: Boolean =
            verdicts.isNotEmpty() && verdicts.none { it is Verdict.Blocking }

        val ready: List<Verdict.Ready> get() = verdicts.filterIsInstance<Verdict.Ready>()

        val blocking: List<Verdict.Blocking> get() = verdicts.filterIsInstance<Verdict.Blocking>()

        /** Links that are added once the list passes (ready + temporary). */
        val addableUrls: List<String> =
            verdicts.filter { it !is Verdict.Blocking }.map { it.url }
    }

    suspend fun preflight(
        urls: List<String>,
        resolve: suspend (String) -> LinkResolution,
    ): Preflight {
        val verdicts = urls.map { url ->
            when (val resolution = resolve(url)) {
                is LinkResolution.Found -> Verdict.Ready(url, resolution)
                is LinkResolution.Invalid,
                is LinkResolution.UnknownHost,
                -> Verdict.Blocking(url, resolution)
                is LinkResolution.SourceError -> Verdict.Temporary(url, resolution)
            }
        }
        return Preflight(verdicts)
    }

    enum class Status { Added, Failed, Unprocessed }

    data class AddResult(val url: String, val status: Status)

    /**
     * Best-effort add of an already-preflighted list. Runs sequentially so a
     * Cancel (screen dismissed) stops promptly; the caller keeps the results it
     * has collected through [onResult] even when this rethrows.
     */
    suspend fun addAll(
        urls: List<String>,
        add: suspend (String) -> MembershipResult,
        onResult: (AddResult) -> Unit = {},
    ): List<AddResult> {
        val results = mutableListOf<AddResult>()
        fun record(result: AddResult) {
            results += result
            onResult(result)
        }
        for ((index, url) in urls.withIndex()) {
            val success = try {
                add(url).success
            } catch (cancelled: CancellationException) {
                for (j in index until urls.size) {
                    record(AddResult(urls[j], Status.Unprocessed))
                }
                throw cancelled
            } catch (_: Exception) {
                false
            }
            record(AddResult(url, if (success) Status.Added else Status.Failed))
        }
        return results
    }
}
