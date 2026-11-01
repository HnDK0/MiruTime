package dev.mirutime.data.library

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File

fun libraryDatabaseBuilder(context: Context): RoomDatabase.Builder<LibraryDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<LibraryDatabase>(
        context = appContext,
        name = appContext.getDatabasePath("mirutime.db").absolutePath,
        factory = LibraryDatabaseConstructor::initialize,
    )
}

/**
 * Opens a second database instance at an arbitrary path. Restore uses it to
 * read the validated backup file; the live instance keeps running.
 */
fun libraryDatabaseAt(context: Context, file: File): LibraryDatabase =
    buildLibraryDatabase(
        Room.databaseBuilder<LibraryDatabase>(
            context = context.applicationContext,
            name = file.absolutePath,
            factory = LibraryDatabaseConstructor::initialize,
        ),
    )
