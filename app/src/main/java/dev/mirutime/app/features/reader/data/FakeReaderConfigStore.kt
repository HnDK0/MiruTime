package dev.mirutime.app.features.reader.data

import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.ReaderConfig
import dev.mirutime.feature.reader.ReaderConfigStore
import dev.mirutime.feature.reader.ReaderMode
import dev.mirutime.feature.reader.ReaderStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeReaderConfigStore(
    initial: ReaderConfig = ReaderConfig(style = ReaderStyle.Default),
) : ReaderConfigStore {
    private val _config = MutableStateFlow(initial)
    override val config: StateFlow<ReaderConfig> = _config.asStateFlow()

    override fun setMode(mode: ReaderMode) {
        _config.update { it.copy(mode = mode) }
    }

    override fun setStyle(style: ReaderStyle) {
        _config.update { it.copy(style = style) }
    }

    override fun setMangaStyle(style: MangaReaderStyle) {
        _config.update { it.copy(mangaStyle = style.sanitized()) }
    }
}
