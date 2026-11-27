package dev.mirutime.app.features.translation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.mirutime.app.features.translation.data.MetadataTranslationController
import dev.mirutime.app.features.translation.data.TitleSurface
import dev.mirutime.app.features.translation.data.TitleTranslationJob
import dev.mirutime.app.features.translation.data.metadataConfigFor
import dev.mirutime.app.features.translation.data.translationPresentationFor
import dev.mirutime.core.log.MiruLog

/** One title-bearing row of any surface; [bookKey] is `libraryBookKey(installId, url)`. */
internal data class TitleItem(val bookKey: String, val title: String)

/**
 * Jobs for [items] on [surface]: a blank title, a surface that hides translated
 * titles, or no FULL config keeps the original. One job per book.
 */
internal fun titleJobsFor(
    items: List<TitleItem>,
    surface: TitleSurface,
): List<TitleTranslationJob> = items
    .mapNotNull { item ->
        if (item.title.isBlank()) return@mapNotNull null
        if (!translationPresentationFor(item.bookKey).showsTitle(surface)) return@mapNotNull null
        val config = metadataConfigFor(item.bookKey) ?: return@mapNotNull null
        TitleTranslationJob(item.bookKey, item.title, config)
    }
    .distinctBy { it.bookKey }

/**
 * Live translated titles for a surface: hydrates the store, submits the missing
 * jobs, and returns `bookKey -> title` only while the entry's config still
 * matches the job's (same rule as the library grid).
 */
@Composable
internal fun rememberTranslatedTitles(
    controller: MetadataTranslationController,
    items: List<TitleItem>,
    surface: TitleSurface,
): Map<String, String> {
    val jobs = remember(items, surface) { titleJobsFor(items, surface) }
    val state by controller.state.collectAsState()
    LaunchedEffect(jobs) {
        MiruLog.event("translation", "titles-lane", "surface" to surface.name, "jobs" to jobs.size)
        controller.hydrateTitles(jobs)
        controller.submitTitles(jobs)
    }
    val configs = remember(jobs) { jobs.associate { it.bookKey to it.config } }
    return remember(state, configs) {
        buildMap {
            configs.forEach { (bookKey, config) ->
                state[bookKey]
                    ?.takeIf { it.config == config }
                    ?.title
                    ?.let { put(bookKey, it) }
            }
        }
    }
}
