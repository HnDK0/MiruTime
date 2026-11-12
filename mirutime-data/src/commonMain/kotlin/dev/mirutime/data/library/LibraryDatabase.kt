package dev.mirutime.data.library

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

const val LibrarySchemaVersion = 12

/**
 * Oldest backup schema the restore path accepts. v6 archives are migrated to
 * [LibrarySchemaVersion] in the staged database; anything older is rejected.
 */
const val MinimumRestorableBackupSchemaVersion = 6

@Database(
    entities = [
        PluginInstallEntity::class,
        ShelfEntity::class,
        BookEntity::class,
        BookSearchEntity::class,
        ChapterEntity::class,
        ChapterBodyEntity::class,
        ProgressEntity::class,
        VideoProgressEntity::class,
        ReadingSessionEntity::class,
        DownloadTaskEntity::class,
        BookCoverEntity::class,
        SourceIconEntity::class,
        TagEntity::class,
        BookTagEntity::class,
        HighlightEntity::class,
        ChapterPageEntity::class,
        LocalBookFileEntity::class,
        ImportJobEntity::class,
        OpdsCatalogEntity::class,
        OpdsEntryEntity::class,
        ChapterTranslationEntity::class,
        TranslationBlockEntity::class,
        BookTranslationEntity::class,
        TtsChunkEntity::class,
        BookMigrationEntity::class,
        HistoryEntity::class,
    ],
    version = LibrarySchemaVersion,
    exportSchema = true,
)
@ConstructedBy(LibraryDatabaseConstructor::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

@Suppress("KotlinNoActualForExpect")
expect object LibraryDatabaseConstructor : RoomDatabaseConstructor<LibraryDatabase> {
    override fun initialize(): LibraryDatabase
}

fun buildLibraryDatabase(
    builder: RoomDatabase.Builder<LibraryDatabase>,
    allowDestructiveFallback: Boolean = false,
): LibraryDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .addMigrations(*LibraryMigrations.ALL)
    // Release builds must never silently wipe a library when a schema version
    // has no migration. Only debug builds opt into the destructive fallback for
    // throwaway pre-release data.
    .apply {
        // The boolean is `dropAllTables`, not an on/off switch: any call enables
        // the fallback. Release builds must not call it at all.
        if (allowDestructiveFallback) {
            fallbackToDestructiveMigration(true)
        }
    }
    .build()
