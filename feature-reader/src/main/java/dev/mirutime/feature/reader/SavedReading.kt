package dev.mirutime.feature.reader

import dev.mirutime.app.features.reader.document.ReadingAnchor

data class SavedReading(
    val anchor: ReadingAnchor?,
    val offsetPx: Int,
    val seq: Long,
    val updatedAt: Long,
)
