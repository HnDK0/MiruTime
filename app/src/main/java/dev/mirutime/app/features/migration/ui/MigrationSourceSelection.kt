package dev.mirutime.app.features.migration.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.migration.domain.migrationSelectAllSources
import dev.mirutime.app.features.migration.domain.toggleMigrationSource

/**
 * Target sources for one migration flow. Lives above the flow state so the
 * filter sheet can edit it even before a book is picked, and survives moving
 * between the book and result steps.
 */
class MigrationSourceSelection {
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    private var available: Set<String> = emptySet()
    private var initialized = false

    fun sync(sources: List<CatalogSource>) {
        if (sources.isEmpty()) return
        val ids = migrationSelectAllSources(sources)
        available = ids
        // First real list selects every target; later lists only drop removed
        // ids, so a user's empty or partial choice is never silently undone.
        selected = if (initialized) selected.intersect(ids) else {
            initialized = true
            ids
        }
    }

    /** New library source: default every target again, minus the old one. */
    fun reset(sources: List<CatalogSource>) {
        available = migrationSelectAllSources(sources)
        initialized = true
        selected = available
    }

    fun toggle(sourceId: String) {
        selected = toggleMigrationSource(selected, sourceId)
    }

    fun selectAll() {
        selected = available
    }

    fun apply(ids: Set<String>) {
        selected = ids.intersect(available)
    }
}
