package dev.mirutime.feature.reader.ui

import dev.mirutime.feature.reader.ReaderStyle

/**
 * Style shown by the reader while the Style sheet is open.
 * [committed] is what is persisted; [preview] is not written until commit.
 */
data class ReaderStylePreviewState(
    val committed: ReaderStyle,
    val preview: ReaderStyle? = null,
) {
    val effective: ReaderStyle = (preview ?: committed).sanitized()

    fun preview(style: ReaderStyle): ReaderStylePreviewState = copy(preview = style)

    fun commit(): ReaderStylePreviewState = ReaderStylePreviewState(effective, null)

    fun cancel(): ReaderStylePreviewState = ReaderStylePreviewState(committed, null)
}
