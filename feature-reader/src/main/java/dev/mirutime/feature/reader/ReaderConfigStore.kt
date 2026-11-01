package dev.mirutime.feature.reader

import kotlinx.coroutines.flow.StateFlow

enum class ReaderMode { Feed, Paged }

data class ReaderConfig(
    val mode: ReaderMode = ReaderMode.Feed,
    val style: ReaderStyle = ReaderStyle.Default,
    val mangaStyle: MangaReaderStyle = MangaReaderStyle.Default,
)

interface ReaderConfigStore {
    val config: StateFlow<ReaderConfig>
    fun setMode(mode: ReaderMode)
    fun setStyle(style: ReaderStyle)
    fun setMangaStyle(style: MangaReaderStyle)
}
