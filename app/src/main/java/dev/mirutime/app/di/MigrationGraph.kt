package dev.mirutime.app.di

import dev.mirutime.app.features.migration.data.BookMigrationTargets
import dev.mirutime.app.features.migration.data.MigrationSearch
import dev.mirutime.data.library.migration.BookMigrationCoordinator

/** One read/write surface for the migration sheet and the full screen. */
class MigrationGraph(
    val coordinator: BookMigrationCoordinator,
    val search: MigrationSearch,
    val targets: BookMigrationTargets,
)
