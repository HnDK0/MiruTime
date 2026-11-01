package dev.mirutime.data.library

import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File

fun libraryDatabaseBuilder(file: File): RoomDatabase.Builder<LibraryDatabase> =
    Room.databaseBuilder<LibraryDatabase>(
        name = file.absolutePath,
        factory = LibraryDatabaseConstructor::initialize,
    )
