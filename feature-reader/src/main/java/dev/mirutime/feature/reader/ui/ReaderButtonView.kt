package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.reader.R
import dev.mirutime.feature.reader.readerBlockKey
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.session.ReaderSessionState
import dev.mirutime.feature.reader.ReaderStyle
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ReaderButtonView(
    state: ReaderSessionState,
    style: ReaderStyle,
    listState: LazyListState,
    contentPadding: PaddingValues,
    translationLabel: String?,
    onAnchor: (ReadingAnchor, Int) -> Unit,
    onRetry: (Int) -> Unit,
    onChapterFinished: (Int) -> Unit,
    onStartOver: () -> Unit,
) {
    val chapterIndex = state.activeChapterIndex
    val document = state.documents[chapterIndex]
    val textColor = Color(style.effectiveTextColor())
    val bodyBlocks = remember(document) { document?.bodyBlocks().orEmpty() }

    if (document == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.statuses[chapterIndex] is ChapterLoadStatus.Failed) {
                TextButton(onClick = { onRetry(chapterIndex) }) {
                    Text(stringResource(R.string.rd_retry), color = textColor)
                }
            } else {
                CircularProgressIndicator(color = textColor)
            }
        }
        return
    }

    LaunchedEffect(state.scrollToken, contentPadding.calculateTopPadding()) {
        val anchor = state.anchor ?: return@LaunchedEffect
        val docIndex = document.blocks.indexOfFirst { it.id == anchor.blockId }
        if (docIndex >= 0) {
            // Item 0 is the chapter header, the body starts at item 1. A block
            // that repeats the title is not part of the body.
            val itemIndex = docIndex - (if (document.titleBlock() != null) 1 else 0) + 1
            listState.scrollToItem(itemIndex, state.offsetPx)
        }
    }

    LaunchedEffect(listState, document, bodyBlocks) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                val block = if (index == 0) {
                    document.titleBlock() ?: bodyBlocks.firstOrNull()
                } else {
                    bodyBlocks.getOrNull(index - 1)
                } ?: return@collect
                onAnchor(ReadingAnchor(block.id.chapterUrl, block.id, 0), offset)
            }
    }

    LaunchedEffect(listState, document) {
        snapshotFlow { !listState.canScrollForward }
            .collect { atEnd ->
                if (atEnd && document.blocks.isNotEmpty()) onChapterFinished(chapterIndex)
            }
    }

    val topPadding = contentPadding.calculateTopPadding()
    val startPadding = contentPadding.calculateLeftPadding(LocalLayoutDirection.current)
    val endPadding = contentPadding.calculateRightPadding(LocalLayoutDirection.current)
    val bottomPadding = contentPadding.calculateBottomPadding()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding),
        contentPadding = PaddingValues(
            start = startPadding,
            end = endPadding,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp),
    ) {
        item(key = "block:chapter-title") {
            ReaderChapterHeader(
                title = document.title,
                style = style,
                translationLabel = translationLabel,
            )
        }
        itemsIndexed(bodyBlocks, key = { _, block -> readerBlockKey(block.id.index) }) { _, block ->
            ReaderBlockView(block, style)
        }
        if (state.endOfBook) {
            item(key = "block:end") {
                TextButton(onClick = onStartOver) {
                    Text(stringResource(R.string.rd_all_read), color = textColor)
                }
            }
        }
    }
}
