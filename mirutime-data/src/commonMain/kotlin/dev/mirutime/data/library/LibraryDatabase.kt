package dev.mirutime.data.library

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

const val LibrarySchemaVersion = 10

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
): LibraryDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    // v6 -> v7 -> v8 -> v9 are explicit in-place migrations; the destructive fallback
    // is only a safety net for versions with no migration (v1 pre-release data).
    .addMigrations(*LibraryMigrations.ALL)
    .fallbackToDestructiveMigration(true)
    .build()
