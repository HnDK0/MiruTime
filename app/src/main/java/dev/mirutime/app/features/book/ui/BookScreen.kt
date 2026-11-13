package dev.mirutime.app.features.book.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.R as UiKitR
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.LocalMiruLocale
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.compactTouchTarget
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.uikit.CardProgressBar
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruCardType
import dev.mirutime.uikit.MiruTag
import dev.mirutime.uikit.isLargeText
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruCompactButton
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruIconButton
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.uikit.MiruSplitProgress
import dev.mirutime.uikit.MiruSourceChip
import dev.mirutime.uikit.MiruTabPill
import dev.mirutime.uikit.MiruToolTile
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.app.core.chapterRowKey
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.di.MigrationGraph
import dev.mirutime.app.di.ReaderGraph
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.core.PluginErrorReport
import dev.mirutime.app.design.components.PluginErrorState
import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.data.PluginBookIndex
import dev.mirutime.app.shell.LocalImeBottom
import dev.mirutime.app.features.book.data.BookEpubExporter
import dev.mirutime.app.features.book.data.BookCbzExporter
import dev.mirutime.app.features.book.data.CbzExportResult
import dev.mirutime.app.features.book.data.SafCbzOutput
import dev.mirutime.app.features.finder.data.isLocalFileBook
import dev.mirutime.app.features.library.data.LocalBookProvider
import dev.mirutime.app.features.book.data.EpubExportResult
import dev.mirutime.app.features.book.data.epubFileName
import dev.mirutime.app.features.book.domain.BookActionKind
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.book.domain.BookDownloadOption
import dev.mirutime.app.features.book.domain.BookReview
import dev.mirutime.app.features.book.domain.ChapterFilter
import dev.mirutime.app.features.book.domain.bookActionState
import dev.mirutime.app.features.book.domain.continueChapterTitle
import dev.mirutime.app.features.book.domain.bookChaptersCountKnown
import dev.mirutime.app.features.book.domain.inReadingOrder
import dev.mirutime.app.features.book.domain.matches
import dev.mirutime.app.features.dashboard.ui.formatDurationLocalized
import dev.mirutime.app.features.downloads.data.DownloadEngine
import dev.mirutime.app.features.downloads.data.DownloadState
import dev.mirutime.app.features.downloads.data.EnqueueFailure
import dev.mirutime.app.features.downloads.data.EnqueueResult
import dev.mirutime.app.features.library.data.LibraryDataProvider
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.app.features.library.ui.shelfLabel
import dev.mirutime.app.features.migration.ui.MigrationBookSheet
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.app.features.opds.ui.opdsFailureText
import dev.mirutime.app.features.reader.data.toReaderBook
import dev.mirutime.app.features.reader.document.chapterKey
import dev.mirutime.feature.reader.ReaderOpenResolver
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.translation.data.BookMetadataJob
import dev.mirutime.app.features.translation.data.ChapterBulkTranslator
import dev.mirutime.app.features.translation.data.ChapterTranslation
import dev.mirutime.app.features.translation.data.ChapterTranslationPlanner
import dev.mirutime.app.features.translation.data.ChapterTranslationStatus
import dev.mirutime.app.features.translation.data.activeTranslationMode
import dev.mirutime.data.library.DownloadTaskEntity
import dev.mirutime.app.features.translation.data.metadataConfigFor
import dev.mirutime.app.features.translation.data.translationPresentationFor
import dev.mirutime.app.features.video.data.VideoDownloadAction
import dev.mirutime.app.features.video.data.VideoDownloadItem
import dev.mirutime.app.features.video.data.VideoDownloadManager
import dev.mirutime.app.features.video.data.VideoDownloadState
import dev.mirutime.app.features.video.data.VideoSourceRepository
import dev.mirutime.app.features.video.data.VideoVariantProbe
import dev.mirutime.app.features.video.data.videoDownloadsForBook
import dev.mirutime.app.features.video.data.videoDownloadActions
import dev.mirutime.app.features.video.domain.VideoDownloadDecision
import dev.mirutime.app.features.video.domain.VideoVariantStatus
import dev.mirutime.app.features.video.domain.initialVideoStreamIndex
import dev.mirutime.app.features.video.domain.pendingVariantProbes
import dev.mirutime.app.features.video.domain.videoDownloadDecision
import dev.mirutime.app.features.video.domain.videoVariantKey
import dev.mirutime.app.features.video.ui.VideoVariantSheet
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.VideoStream
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// The floating tab panel is hidden on drill-in routes (the shell shows it
// only on the four main tabs), so the page keeps a small gap above the
// system navigation bar — enough to clear the gesture handle.
private val BottomClearance = 16.dp

// Chapter list window: its own scroll viewport inside the page, capped so
// the page stays navigable (the chapter list scrolls in its own window).
private val ChapterWindowHeight = 310.dp

// The provider keeps metadata in memory but the rendered page also holds the
// chapter list. A tiny bounded snapshot lets a reader return paint the page
// immediately; the provider/DB answer then revalidates it in place.
// Characters stays dropped — no plugin character data confirmed yet. READER
// NOTES lives at the bottom of this file on fixtures; real review parsing is
// still pending.

// One toast language: short toasts on every long or invisible action.
internal fun bookToast(context: Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

// One honest message per enqueue: queued/skipped count or the real reason.
private fun enqueueMessage(context: Context, result: EnqueueResult): String = when {
    result.queued > 0 && result.translationRequested && !result.translationConfigured ->
        context.getString(R.string.book_dl_queued_translate_unconfigured_fmt, result.queued)
    result.queued > 0 && result.translationsQueued > 0 ->
        context.getString(
            R.string.book_dl_queued_translate_fmt,
            result.queued,
            result.translationsQueued,
        )
    result.queued > 0 && result.translationsAlreadyQueued > 0 ->
        context.getString(
            R.string.book_dl_queued_translate_pending_fmt,
            result.queued,
            result.translationsAlreadyQueued,
        )
    result.queued > 0 -> context.getString(R.string.book_dl_queued_fmt, result.queued)
    result.translationsQueued > 0 ->
        context.getString(R.string.book_dl_translate_queued_fmt, result.translationsQueued)
    result.translationsAlreadyQueued > 0 ->
        context.getString(
            R.string.book_dl_translate_pending_fmt,
            result.translationsAlreadyQueued,
        )
    result.translationRequested && !result.translationConfigured ->
        context.getString(R.string.book_translate_not_configured)
    result.reason == EnqueueFailure.LOCAL_BOOK -> context.getString(R.string.book_dl_local_offline)
    result.reason == EnqueueFailure.NO_CHAPTERS -> context.getString(R.string.book_dl_no_chapters)
    result.reason == EnqueueFailure.BOOK_NOT_SAVED -> context.getString(R.string.book_dl_add_first)
    result.reason == EnqueueFailure.NOT_CONFIGURED ->
        context.getString(R.string.book_dl_engine_unavailable)
    result.reason == EnqueueFailure.UNSUPPORTED_CONTENT ->
        context.getString(R.string.book_dl_unsupported)
    result.adopted > 0 -> context.getString(R.string.book_dl_adopted_fmt, result.adopted)
    result.cached > 0 -> context.getString(R.string.book_dl_cached_fmt, result.cached)
    else -> context.getString(R.string.book_dl_nothing_new)
}

/**
 * The download queue needs a stored book row, not library membership. Create
 * the history row only when it is missing; a failure leaves the queue untouched.
 */
private suspend fun ensureDownloadBook(
    provider: BookDataProvider,
    engine: DownloadEngine,
    bookUrl: String,
): Long? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    engine.bookId(bookUrl)?.let { return@withContext it }
    try {
        provider.ensureHistoryBook(bookUrl)
        engine.bookId(bookUrl)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        MiruLog.w("book", "download book resolve failed ${failure.javaClass.simpleName}")
        null
    }
}

// Book page: header card (cover left, shelf chip, genre
// tags), 4-cell stat strip, continue/library/download actions, about, tools
// rows, source page, chapters (count + order toggle in one header) window.
@Composable
internal fun BookScreen(
    bookUrl: String,
    contentPadding: PaddingValues,
    // Restore-only revision: reload the row when a live restore replaced it.
    refreshKey: Int = 0,
    readerGraph: ReaderGraph,
    provider: BookDataProvider,
    // Null in previews/tests: the EPUB row then reports "unavailable".
    exporter: BookEpubExporter? = null,
    // Null in previews/tests: the CBZ row then reports "unavailable".
    cbzExporter: BookCbzExporter? = null,
    // Real queue; null keeps previews/tests on honest "unavailable" toasts.
    downloadEngine: DownloadEngine? = null,
    // Video books use the media3 download manager, not the text engine.
    videoDownloads: VideoDownloadManager? = null,
    videoRepository: VideoSourceRepository? = null,
    // Real shelves when the shell has the library provider; null keeps
    // previews/tests on the fixture shelf list.
    libraryProvider: LibraryDataProvider? = null,
    // Opens the per-novel regex rules screen (Settings -> Regex, novel scope).
    onOpenRegex: (bookUrl: String) -> Unit = {},
    // In-app WebView for source-owned flows such as login.
    onOpenWebView: (url: String, title: String) -> Unit = { _, _ -> },
    // Global search screen (Finder tab), opened from the book title.
    onOpenSearch: (String) -> Unit = {},
    // Opens the novel reader at a chapter (Continue or a chapter row).
    onOpenReader: (bookUrl: String, chapterIndex: Int, restoreSaved: Boolean) -> Unit = { _, _, _ -> },
    // Video books never open the text reader: the shell routes them to the
    // player activity instead.
    onOpenVideo: (bookUrl: String, chapterIndex: Int) -> Unit = { _, _ -> },
    // One library/dashboard revision bump per committed membership change.
    onLibraryChanged: () -> Unit = {},
    // A real metadata load succeeded: the shell may clear this book's
    // new-chapter badge. Never called for a failed open.
    onBookOpened: (bookUrl: String) -> Unit = {},
    // Unknown for previews/fakes: the migration row stays unavailable rather
    // than promising a write path that does not exist.
    migration: MigrationGraph? = null,
    // Opens a migrated target book from the sheet.
    onOpenBook: (bookUrl: String) -> Unit = {},
    // OPDS: opens the catalogue that owns the entry (catalog URL from the feed).
    onOpenCatalog: (catalogUrl: String) -> Unit = {},
) {
    val seededBook = remember(bookUrl) { BookPageSnapshotStore.get(bookUrl) }
    var book by remember(bookUrl) { mutableStateOf(seededBook) }
    // The saved reader position is the same source the tap uses; the CTA
    // label must not fall back to "Start · Chapter 1" while it is non-zero.
    var savedContinue by remember(bookUrl) { mutableStateOf<Int?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    // Chapter the saved reading position belongs to; opening exactly this row
    // restores its saved offset instead of starting at the chapter top.
    var savedChapterIndex by remember(bookUrl) { mutableStateOf<Int?>(null) }
    var hasSavedProgress by remember(bookUrl) { mutableStateOf(false) }
    var savedLoaded by remember(bookUrl) { mutableStateOf(false) }
    LaunchedEffect(bookUrl, book) {
        val readerBook = book?.toReaderBook() ?: return@LaunchedEffect
        val saved = runCatching { readerGraph.positions.load(bookUrl) }.getOrNull()
        hasSavedProgress = saved != null
        savedContinue = ReaderOpenResolver.continueChapter(readerBook, saved)
        savedChapterIndex = saved?.anchor?.chapterUrl?.let { url ->
            readerBook.chapters.firstOrNull { it.chapterUrl == url }?.index
        }
        savedLoaded = true
    }
    // Both scroll positions live at the page level so they restore from the
    // nav entry even while the cached book/chapters are being re-read. The
    // loading list gets its own state: feeding the saved page index to the
    // one-item skeleton would clamp it to the top.
    val pageListState = rememberLazyListState()
    val chapterListState = rememberLazyListState()
    val loadingListState = rememberLazyListState()
    // Survives the reader round-trip (nav saveable state). On return the
    // provider cache answers immediately, so no skeleton/source refresh.
    var loadedOnce by rememberSaveable(bookUrl) { mutableStateOf(false) }
    // One "page really opened" notification per open, never per retry.
    var openedNotified by rememberSaveable(bookUrl) { mutableStateOf(false) }
    SideEffect {
        book?.let { current ->
            if (BookPageSnapshotStore.get(bookUrl) != current) {
                BookPageSnapshotStore.put(bookUrl, current)
            }
        }
    }
    var shelves by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var shelvesLoading by remember { mutableStateOf(true) }
    var shelfSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sourcePanelOpen by rememberSaveable { mutableStateOf(false) }
    var downloadPanelOpen by rememberSaveable { mutableStateOf(false) }
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    var translatorSheetOpen by rememberSaveable { mutableStateOf(false) }
    var migrationSheetOpen by rememberSaveable { mutableStateOf(false) }
    var epubSheetOpen by rememberSaveable { mutableStateOf(false) }
    var selectSheetOpen by rememberSaveable { mutableStateOf(false) }
    var epubIncludeTranslation by rememberSaveable { mutableStateOf(true) }
    var epubExporting by remember { mutableStateOf(false) }
    var cbzExporting by remember { mutableStateOf(false) }
    val mangaBook = book?.contentType == ContentType.Manga
    // Audio rides the video player but exposes no reader/download actions.
    val isAudioBook = book?.contentType == ContentType.Audio
    val isLocalBook = remember(bookUrl) {
        isLocalFileBook(bookUrl) || LocalBookProvider.isLocalBook(bookUrl)
    }
    // Bulk passes: the bar/plate shows a spinner and rejects a second tap
    // until the enqueue/translate call returns.
    var bulkBusy by remember { mutableStateOf<BulkAction?>(null) }
    var downloadAllBusy by remember { mutableStateOf(false) }
    // READING ORDER: follows the global CHAPTERS_SORT_ASCENDING setting and
    // writes a toggle back to it, so the next book opens with the same choice.
    var newestFirst by remember { mutableStateOf(AppSettings.newestFirst) }
    var downloadTasks by remember(bookUrl) { mutableStateOf<List<DownloadTaskEntity>>(emptyList()) }
    var lastDownloadTaskSignature by remember(bookUrl) { mutableStateOf<String?>(null) }
    val isVideoBook = book?.contentType == ContentType.Video
    val videoDownloadFlow = remember(videoDownloads) {
        videoDownloads?.downloads ?: MutableStateFlow(emptyList<VideoDownloadItem>())
    }
    val videoDownloadItems by videoDownloadFlow.collectAsState()
    // Only this book's episodes; completed counts feed the summary badges.
    val videoDownloadsByIndex = remember(videoDownloadItems, bookUrl, isVideoBook) {
        if (isVideoBook) {
            videoDownloadsForBook(videoDownloadItems, bookUrl)
        } else {
            emptyMap()
        }
    }
    val videoCompletedCount = remember(videoDownloadsByIndex) {
        videoDownloadsByIndex.values.count { it.state == VideoDownloadState.Completed }
    }
    val videoPausedCount = remember(videoDownloadsByIndex) {
        videoDownloadsByIndex.values.count { it.state == VideoDownloadState.Paused }
    }
    val videoPausedFlow = remember(videoDownloads) {
        videoDownloads?.pausedAll ?: MutableStateFlow(false)
    }
    val videoPausedAll by videoPausedFlow.collectAsState()
    var videoSourceSheetOpen by rememberSaveable { mutableStateOf(false) }
    var videoSourceStreams by remember(bookUrl) { mutableStateOf<List<VideoStream>>(emptyList()) }
    var videoSourceStatuses by remember(bookUrl) {
        mutableStateOf<Map<String, VideoVariantStatus>>(emptyMap())
    }
    var videoSourceProbing by remember(bookUrl) { mutableIntStateOf(0) }
    var videoSourceHandledRefresh by remember(bookUrl) { mutableIntStateOf(0) }
    var videoSourceRefresh by remember(bookUrl) { mutableStateOf(0) }
    // No "download + translate" switch: a download translates when the book
    // already has an active translation pair/config, otherwise it stays a
    // plain download. The choice follows `translationConfigured` below.
    // Plugin metadata lands before the chapter list; the section shows a
    // skeleton until that second call answers (empty state on failure).
    // A seeded snapshot already carries a real chapter list: show it while the
    // fresh rows are read instead of dropping to the chapter skeleton.
    var chaptersLoading by remember(bookUrl) { mutableStateOf(seededBook == null) }
    // Set only by the explicit Refresh action; opening a cached book is local.
    var chaptersRefreshing by remember { mutableStateOf(false) }
    // Typed chapter-refresh failure (op=chapters): shown above the rows that
    // stay on screen, or alone when the store has none. Cleared by a success.
    var chaptersFailure by remember(bookUrl) { mutableStateOf<PluginErrorReport?>(null) }
    // A direct SAF file can fail before any metadata exists (revoked grant,
    // moved file). Keep a real error state instead of an infinite skeleton.
    var loadError by remember(bookUrl) { mutableStateOf<String?>(null) }
    // Typed plugin failure for the same state: shown through PluginErrorState
    // when the source is identifiable, generic card otherwise.
    var loadFailure by remember(bookUrl) { mutableStateOf<PluginError?>(null) }
    // Cover repair failure. The cover owns the retry tile; this holds the
    // typed report so the page can show the shared error state (op=cover).
    var coverFailure by remember(bookUrl) { mutableStateOf<PluginErrorReport?>(null) }
    var retryTick by remember(bookUrl) { mutableIntStateOf(0) }
    // The video player is a separate activity: BookScreen is not re-entered
    // when it finishes, so resume must re-read the session-backed totals.
    var statsTick by remember(bookUrl) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bookUrl) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statsTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(bookUrl, statsTick) {
        if (statsTick == 0) return@LaunchedEffect
        val fresh = try {
            provider.book(bookUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        book = book?.copy(
            readMinutes = fresh.readMinutes,
            ttsMinutes = fresh.ttsMinutes,
            readCount = fresh.readCount,
            totalChapters = maxOf(book?.totalChapters ?: 0, fresh.totalChapters),
        )
    }
    // Membership is optimistic and explicit-target. The channel conflates
    // rapid taps; only the newest target inside the debounce window runs.
    var membershipPending by remember { mutableStateOf(false) }
    var membershipTarget by remember { mutableStateOf<Boolean?>(null) }
    var confirmedInLibrary by remember { mutableStateOf<Boolean?>(null) }
    val membershipIntents = remember(bookUrl) { Channel<Boolean>(Channel.CONFLATED) }

    // OPDS catalogue entry: adding downloads one file and imports it, which is
    // a single cancellable job — there is no membership toggle and no download
    // queue here. The chosen format survives a step out of the page.
    var opdsAdding by remember(bookUrl) { mutableStateOf(false) }
    var opdsAddJob by remember(bookUrl) { mutableStateOf<Job?>(null) }
    var opdsAddFailure by remember(bookUrl) { mutableStateOf<MiruError?>(null) }
    var opdsOptionKey by rememberSaveable(bookUrl) { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val resources = LocalResources.current
    val ioScope = rememberCoroutineScope()
    LaunchedEffect(videoSourceSheetOpen, videoSourceRefresh) {
        if (!videoSourceSheetOpen) return@LaunchedEffect
        val target = book ?: return@LaunchedEffect
        val repository = videoRepository ?: return@LaunchedEffect
        val chapter = target.chapters.firstOrNull()
        if (chapter == null) {
            videoSourceSheetOpen = false
            bookToast(context, resources.getString(R.string.book_dl_no_chapters))
            return@LaunchedEffect
        }
        val streams = (provider.videoStreams(target.bookUrl, chapter.index) as? Outcome.Ok)
            ?.value.orEmpty()
        if (streams.isEmpty()) {
            videoSourceSheetOpen = false
            bookToast(context, resources.getString(R.string.book_download_unavailable))
            return@LaunchedEffect
        }
        videoSourceStreams = streams
        // "Check again" is an explicit user request: re-probe every stream, not
        // only the ones missing a status. The first open warms pending URLs.
        val forceProbe = videoSourceRefresh != videoSourceHandledRefresh
        videoSourceHandledRefresh = videoSourceRefresh
        val toProbe = if (forceProbe) {
            streams.distinctBy { it.url }
        } else {
            pendingVariantProbes(streams, videoSourceStatuses, includeDead = false)
        }
        // One probe per stream, each row flips as soon as its own probe answers.
        toProbe.forEach { stream ->
            launch {
                // Count only a coroutine that actually starts, so a cancelled
                // effect cannot leave the spinner stuck on.
                videoSourceProbing++
                try {
                    val status = VideoVariantProbe.probe(stream.url, repository.headersFor(stream))
                    // A late UNKNOWN must not overwrite an earlier DEAD.
                    if (status != VideoVariantStatus.UNKNOWN || videoSourceStatuses[stream.url] == null) {
                        videoSourceStatuses = videoSourceStatuses + (stream.url to status)
                    }
                } finally {
                    videoSourceProbing = (videoSourceProbing - 1).coerceAtLeast(0)
                }
            }
        }
    }
    // One cover-repair path: the retry tile, the error card and the automatic
    // remote failure all ask the provider for a fresh URI. The caller (this
    // screen) keeps the typed failure; BookCover carries no error type.
    val retryCover: suspend () -> String? = {
        when (val result = provider.refreshCover(bookUrl)) {
            is Outcome.Ok -> {
                coverFailure = null
                result.value
            }
            is Outcome.Err -> {
                coverFailure = buildPluginErrorReport(
                    error = result.error as? PluginError
                        ?: PluginError.ScriptError(result.error::class.simpleName ?: "cover"),
                    sourceId = PluginBookIndex.sourceForBook(bookUrl),
                    sourceName = book?.source,
                    operation = "cover",
                    identity = bookUrl,
                )
                null
            }
        }
    }
    val translationStates by readerGraph.translations.state.collectAsState()
    val translationMode = activeTranslationMode(bookUrl)
    val translationPresentation = translationPresentationFor(bookUrl)
    val translationConfigured = ChapterTranslationPlanner.config(bookUrl, translationMode) != null
    val bulkTranslator = remember(provider, readerGraph) {
        ChapterBulkTranslator(
            provider = provider,
            controller = readerGraph.translations,
            loadDocument = readerGraph.repository::documentForTranslation,
        )
    }
    var pendingTranslationDelete by remember { mutableStateOf<Set<Int>?>(null) }
    // Selection cleanup for the browser is only run once the confirmed
    // delete actually succeeds; cancel and failure leave the selection.
    var pendingTranslationDeleteDone by remember { mutableStateOf<(() -> Unit)?>(null) }
    var seenReadyTranslations by remember(bookUrl) { mutableStateOf(0) }

    // SAF destination for the EPUB. The export itself runs on IO; the sheet
    // closes first so the system picker is the only thing on screen.
    val epubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri ->
        val targetBook = book
        val targetExporter = exporter
        if (uri == null) return@rememberLauncherForActivityResult
        if (targetBook == null || targetExporter == null) {
            bookToast(context, resources.getString(R.string.tool_epub_unavailable))
            return@rememberLauncherForActivityResult
        }
        val includeTranslation = epubIncludeTranslation
        ioScope.launch {
            epubExporting = true
            MiruLog.event(
                "book",
                "epub-start",
                "book" to MiruLog.idHash(targetBook.bookUrl),
                "chapters" to targetBook.chapters.size,
                "translated" to includeTranslation,
            )
            bookToast(context, resources.getString(R.string.tool_epub_started))
            try {
                val result = withContext(Dispatchers.IO) {
                    // Export into a local temp file first: a failed/cancelled
                    // export must not leave a half-written EPUB at the SAF URI.
                    val temp = runCatching {
                        File.createTempFile("miru-epub-", ".epub", context.cacheDir)
                    }.getOrNull()
                        ?: return@withContext EpubExportResult.Failure(
                            "Cannot create a temporary export file",
                        )
                    try {
                        val exportResult = runCatching {
                            temp.outputStream().use { stream ->
                                targetExporter.export(
                                    targetBook.bookUrl,
                                    includeTranslation,
                                    stream,
                                )
                            }
                        }.getOrElse {
                            EpubExportResult.Failure(it.message ?: "EPUB export failed")
                        }
                        if (exportResult !is EpubExportResult.Success) {
                            return@withContext exportResult
                        }
                        val target = context.contentResolver.openOutputStream(uri)
                            ?: return@withContext EpubExportResult.Failure(
                                "Cannot open the selected file",
                            )
                        target.use { out ->
                            temp.inputStream().use { input -> input.copyTo(out) }
                        }
                        exportResult
                    } catch (e: Exception) {
                        EpubExportResult.Failure(e.message ?: "Cannot write the selected file")
                    } finally {
                        temp.delete()
                    }
                }
                when (result) {
                    is EpubExportResult.Success -> {
                        MiruLog.event(
                            "book",
                            "epub-result",
                            "book" to MiruLog.idHash(targetBook.bookUrl),
                            "ok" to true,
                            "written" to result.chaptersWritten,
                        )
                        bookToast(
                            context,
                            resources.getString(
                                R.string.tool_epub_success_fmt,
                                result.chaptersWritten,
                            ),
                        )
                    }
                    EpubExportResult.NoChapters -> {
                        MiruLog.event(
                            "book",
                            "epub-result",
                            "book" to MiruLog.idHash(targetBook.bookUrl),
                            "ok" to false,
                            "reason" to "no-chapters",
                        )
                        bookToast(context, resources.getString(R.string.tool_epub_no_chapters))
                    }
                    is EpubExportResult.Failure -> {
                        MiruLog.event(
                            "book",
                            "epub-result",
                            "book" to MiruLog.idHash(targetBook.bookUrl),
                            "ok" to false,
                            "reason" to "failure",
                        )
                        bookToast(
                            context,
                            resources.getString(R.string.tool_epub_failed_fmt, result.message),
                        )
                    }
                }
            } finally {
                epubExporting = false
            }
        }
    }

    // SAF destination for a CBZ export: a folder, not a file, because one
    // chapter is one archive under a shared manga folder.
    val cbzLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val targetBook = book
        val targetExporter = cbzExporter
        if (uri == null) return@rememberLauncherForActivityResult
        if (targetBook == null || targetExporter == null) {
            bookToast(context, resources.getString(R.string.tool_cbz_unavailable))
            return@rememberLauncherForActivityResult
        }
        ioScope.launch {
            cbzExporting = true
            MiruLog.event(
                "book",
                "cbz-start",
                "book" to MiruLog.idHash(targetBook.bookUrl),
                "chapters" to targetBook.chapters.count { it.downloaded },
            )
            bookToast(context, resources.getString(R.string.tool_cbz_started))
            try {
                // A SAF provider can refuse the directory or fail mid-write;
                // that must surface as a toast, never as a silent no-op.
                val result = try {
                    targetExporter.export(
                        targetBook.bookUrl,
                        SafCbzOutput(context, uri),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CbzExportResult.Failure(e.message ?: "CBZ export failed")
                }
                when (result) {
                    is CbzExportResult.Success -> {
                        MiruLog.event(
                            "book",
                            "cbz-result",
                            "book" to MiruLog.idHash(targetBook.bookUrl),
                            "ok" to true,
                            "written" to result.chaptersWritten,
                            "skipped" to result.chaptersSkipped,
                        )
                        bookToast(
                            context,
                            resources.getString(
                                R.string.tool_cbz_success_fmt,
                                result.chaptersWritten,
                            ),
                        )
                    }
                    CbzExportResult.NoChapters -> {
                        MiruLog.event(
                            "book",
                            "cbz-result",
                            "book" to MiruLog.idHash(targetBook.bookUrl),
                            "ok" to false,
                            "reason" to "no-chapters",
                        )
                        bookToast(context, resources.getString(R.string.tool_cbz_no_chapters))
                    }
                    is CbzExportResult.Failure -> {
                        MiruLog.event(
                            "book",
                            "cbz-result",
                            "book" to MiruLog.idHash(targetBook.bookUrl),
                            "ok" to false,
                            "reason" to "failure",
                        )
                        bookToast(
                            context,
                            resources.getString(R.string.tool_cbz_failed_fmt, result.message),
                        )
                    }
                }
            } finally {
                cbzExporting = false
            }
        }
    }

    // Snapshot loader: the current content stays on screen during a reload
    // (state mutations must never flash the skeleton again). One 120ms
    // fake beat, same as the library and dashboard fakes.
    suspend fun reload() {
        val loaded = provider.book(bookUrl) ?: return
        // Plugin metadata has no chapter list; keep the one already shown.
        val chapters = loaded.chapters.ifEmpty { book?.chapters.orEmpty() }
        book = loaded.copy(chapters = chapters, totalChapters = chapters.size)
    }

    suspend fun reloadChapters() {
        val loaded = runCatching { provider.chapters(bookUrl) }.getOrNull() ?: return
        book = book?.copy(chapters = loaded, totalChapters = loaded.size)
    }

    // A live restore replaces rows behind this page. Reload only then; the
    // membership/open revisions must not re-run the chapter window.
    val seenRefreshKey = remember(bookUrl) { mutableIntStateOf(refreshKey) }
    LaunchedEffect(refreshKey) {
        if (refreshKey == seenRefreshKey.intValue) return@LaunchedEffect
        provider.invalidateBookCache(bookUrl)
        reload()
        reloadChapters()
        // Mark the revision only after both reads completed; a cancelled
        // effect retries on the next composition instead of losing the reload.
        seenRefreshKey.intValue = refreshKey
    }

    // One report shape for both the initial chapter load and an explicit
    // refresh, so every chapter failure names the same source/operation.
    fun chapterReport(error: PluginError): PluginErrorReport = buildPluginErrorReport(
        error = error,
        sourceId = PluginBookIndex.sourceForBook(bookUrl),
        sourceName = book?.source,
        operation = "chapters",
        identity = bookUrl,
    )

    // The only per-screen network refresh: opening a cached book never calls
    // the source. The scheduled worker keeps updating the DB in the background.
    fun refreshChapters() {
        if (chaptersRefreshing) return
        chaptersRefreshing = true
        ioScope.launch {
            try {
                // Metadata first so a stored-missing rating/description/cover can
                // be restored: the chapter refresh alone never wrote them back.
                val metaResult = runCatching { provider.refreshMetadata(bookUrl) }.getOrNull()
                if (metaResult is Outcome.Ok) {
                    val refreshed = try {
                        provider.book(bookUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val currentBook = book
                    if (refreshed != null && currentBook != null &&
                        currentBook.bookUrl == bookUrl
                    ) {
                        book = currentBook.copy(
                            title = refreshed.title,
                            author = refreshed.author,
                            rating = refreshed.rating,
                            description = refreshed.description,
                            coverUrl = refreshed.coverUrl,
                            genres = refreshed.genres,
                            ongoing = refreshed.ongoing,
                            updatedLabel = refreshed.updatedLabel,
                        )
                    }
                }
                val result = try {
                    withContext(Dispatchers.IO) { provider.refreshChaptersOutcome(bookUrl) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Outcome.Err(
                        PluginError.ScriptError(e::class.simpleName ?: "chapters-refresh"),
                    )
                }
                val current = book
                if (current == null || current.bookUrl != bookUrl) return@launch
                val view = chapterRefreshView(current.chapters, result)
                // A failure keeps the stored rows; only a success replaces them.
                if (view.ok) {
                    book = current.copy(chapters = view.rows, totalChapters = view.rows.size)
                }
                chaptersFailure = view.failure?.let { error -> chapterReport(error) }
                MiruLog.event(
                    "book",
                    "chapters-refresh",
                    "book" to MiruLog.idHash(bookUrl),
                    "ok" to view.ok,
                    "count" to view.rows.size,
                    "error" to view.errorClass,
                )
            } finally {
                chaptersRefreshing = false
            }
        }
    }

    // Explicit target, optimistic UI: the button flips immediately and only
    // this action is pending. Rapid taps are conflated to the newest target.
    fun requestMembership(target: Boolean) {
        val current = book ?: return
        if (!membershipPending && current.inLibrary == target) return
        membershipTarget = target
        membershipPending = true
        book = current.copy(inLibrary = target)
        membershipIntents.trySend(target)
    }

    DisposableEffect(bookUrl) {
        onDispose { membershipIntents.close() }
    }

    LaunchedEffect(bookUrl, provider) {
        consumeMembershipIntents(membershipIntents) { target ->
            val result = try {
                provider.setInLibrary(bookUrl, target, book?.installId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MiruLog.w("book", "Library membership failed for $bookUrl", e)
                MembershipResult(success = false)
            }
            // A newer tap arrived while this call ran: its pass owns the UI.
            if (membershipTarget != target) return@consumeMembershipIntents
            MiruLog.event(
                "book",
                "library",
                "action" to if (target) "add" else "remove",
                "ok" to result.success,
                "cleanupFailed" to result.fileCleanupFailed,
                "book" to MiruLog.idHash(bookUrl),
            )
            if (result.success) {
                confirmedInLibrary = target
                book = book?.copy(inLibrary = target)
                membershipPending = false
                onLibraryChanged()
                val message = when {
                    result.fileCleanupFailed -> R.string.book_library_cleanup_failed
                    target -> R.string.book_added_toast
                    else -> R.string.book_removed_toast
                }
                bookToast(context, resources.getString(message))
            } else {
                // Revert to the last committed value, not the failed target.
                book = book?.copy(inLibrary = confirmedInLibrary ?: !target)
                membershipPending = false
                bookToast(context, resources.getString(R.string.book_library_update_failed))
            }
        }
    }

    // Metadata and chapters load independently: the header, stats and actions
    // paint as soon as the plugin's book() answers instead of waiting for a
    // potentially slow chapters() call.
    LaunchedEffect(bookUrl, retryTick) {
        // Saveable state can outlive the process; only a real seeded snapshot
        // proves the page is being reopened rather than cold-started.
        val reopened = loadedOnce && seededBook != null
        // A seeded snapshot already carries a real chapter list: keep it on
        // screen while the fresh rows are read instead of dropping to the
        // chapter skeleton.
        chaptersLoading = book?.chapters.isNullOrEmpty()
        chaptersRefreshing = false
        loadError = null
        loadFailure = null
        chaptersFailure = null
        try {
            val metadata = try {
                provider.book(bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: dev.mirutime.app.core.PluginRequestException) {
                loadError = ""
                loadFailure = e.error
                null
            } catch (e: Exception) {
                loadError = e.message.orEmpty()
                null
            }
            if (metadata == null) {
                if (loadError == null) loadError = ""
                if (loadFailure == null) loadFailure = provider.lastPluginError(bookUrl)
                MiruLog.event(
                    "book",
                    "load",
                    "book" to MiruLog.idHash(bookUrl),
                    "ok" to false,
                    "reason" to "metadata",
                )
                return@LaunchedEffect
            }
            if (reopened) {
                // Reader return: provider.book() answered from its memory
                // cache. Re-read only the persisted rows so read state and
                // progress are fresh, then stop. No skeleton, no source
                // refresh, no jump back to the top of the chapter window.
                val cached = try {
                    provider.cachedChapters(bookUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val fresh = cached ?: try {
                    provider.chapters(bookUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                val loaded = fresh.ifEmpty { book?.chapters.orEmpty() }
                book = metadata.copy(chapters = loaded, totalChapters = loaded.size)
                loadedOnce = true
                confirmedInLibrary = metadata.inLibrary
                if (!openedNotified) {
                    openedNotified = true
                    onBookOpened(bookUrl)
                }
                MiruLog.event(
                    "book",
                    "load",
                    "book" to MiruLog.idHash(bookUrl),
                    "ok" to true,
                    "chapters" to loaded.size,
                    "inLibrary" to metadata.inLibrary,
                )
                return@LaunchedEffect
            }
            // Keep a seeded chapter list visible while the persisted rows are
            // read; only an empty page shows the chapter skeleton.
            val seededChapters = book?.chapters.orEmpty()
            book = if (seededChapters.isEmpty()) {
                metadata
            } else {
                metadata.copy(chapters = seededChapters, totalChapters = seededChapters.size)
            }
            MiruLog.event(
                "book",
                "load",
                "book" to MiruLog.idHash(bookUrl),
                "ok" to true,
                "chapters" to metadata.totalChapters,
                "inLibrary" to metadata.inLibrary,
            )
            confirmedInLibrary = metadata.inLibrary
            // The page really opened: the shell can clear its new-chapter
            // badge after this point (never for a failed metadata load).
            if (!openedNotified) {
                openedNotified = true
                onBookOpened(bookUrl)
            }
            // Persisted rows open the book immediately; only an empty store
            // pays for the plugin call here. A typed failure keeps the rows
            // already shown and reaches the error card instead of being
            // rendered as the empty "No chapters" state.
            val result = loadInitialChapters(
                bookUrl = bookUrl,
                cached = provider::cachedChapters,
                remote = provider::chaptersOutcome,
            )
            val base = book ?: return@LaunchedEffect
            val view = chapterRefreshView(base.chapters, result)
            if (view.ok) {
                book = base.copy(chapters = view.rows, totalChapters = view.rows.size)
                MiruLog.event(
                    "book",
                    "chapters",
                    "book" to MiruLog.idHash(bookUrl),
                    "count" to view.rows.size,
                    "downloaded" to view.rows.count { it.downloaded },
                    "translated" to view.rows.count { it.translationState?.isNotBlank() == true },
                )
            }
            chaptersFailure = view.failure?.let { error -> chapterReport(error) }
            loadedOnce = true
            // Opening a cached book is DB-only. A source refresh (and the
            // possible CF challenge behind it) only runs from the explicit
            // Refresh action or the scheduled library update.
        } finally {
            chaptersLoading = false
        }
    }

    LaunchedEffect(libraryProvider) {
        // Real shelves only; an unavailable provider leaves the list empty so
        // the chip falls back to the ID instead of a fabricated fixture label.
        shelvesLoading = true
        shelves = runCatching { libraryProvider?.shelves().orEmpty() }
            .getOrDefault(emptyList())
        shelvesLoading = false
    }

    // The controller turns a chapter Ready only after the cache save attempt;
    // refresh the DB-backed flags shortly after so the list tells the truth.
    val readyTranslationCount = remember(book?.chapters, translationStates) {
        book?.chapters?.count { chapter ->
            val chapterUrl = chapter.url ?: chapterKey(bookUrl, chapter.index)
            translationStates[chapterUrl]?.status == ChapterTranslationStatus.Ready
        } ?: 0
    }
    LaunchedEffect(readyTranslationCount) {
        if (readyTranslationCount <= seenReadyTranslations) return@LaunchedEffect
        seenReadyTranslations = readyTranslationCount
        delay(150)
        reloadChapters()
    }

    // A row may be created by the first download (history-only book), so the
    // observer must re-key once the id exists.
    var resolvedDownloadBookId by remember(bookUrl) { mutableStateOf<Long?>(null) }
    LaunchedEffect(
        bookUrl,
        downloadEngine,
        book?.inLibrary,
        isVideoBook,
        resolvedDownloadBookId,
    ) {
        if (isVideoBook) {
            downloadTasks = emptyList()
            return@LaunchedEffect
        }
        val engine = downloadEngine ?: return@LaunchedEffect
        val bookId = resolvedDownloadBookId ?: engine.bookId(bookUrl) ?: return@LaunchedEffect
        lastDownloadTaskSignature = null
        engine.observeBook(bookId).collect { tasks ->
            val signature = tasks.joinToString(",") { "${it.id}:${it.state}" }
            downloadTasks = tasks
            if (signature != lastDownloadTaskSignature) {
                lastDownloadTaskSignature = signature
                provider.invalidateBookCache(bookUrl)
                reloadChapters()
            }
        }
    }
    LaunchedEffect(bookUrl, downloadEngine) {
        if (downloadEngine == null) return@LaunchedEffect
        while (true) {
            // A blocked queue cannot progress on its own; skip the 3000-row
            // re-read but keep a short tick so an unblock is picked up quickly.
            delay(5_000)
            if (downloadTasks.any { DownloadState.from(it.state)?.isBlocked == true }) continue
            val translating = book?.chapters?.any {
                it.translationState.equals("PENDING", ignoreCase = true)
            } == true
            if (translating ||
                downloadTasks.any { DownloadState.from(it.state)?.isActive == true }
            ) {
                reloadChapters()
            }
        }
    }
    val downloadProgress = if (isVideoBook) {
        val total = book?.chapters?.size ?: 0
        val active = videoDownloadsByIndex.values.any {
            it.state == VideoDownloadState.Queued ||
                it.state == VideoDownloadState.Downloading
        }
        val activeFraction = videoDownloadsByIndex.values
            .filter { it.state == VideoDownloadState.Downloading }
            .sumOf { it.progress.toDouble() }
        if (active && total > 0) {
            ((videoCompletedCount + activeFraction) / total).toFloat()
        } else {
            null
        }
    } else {
        downloadTasks
            .takeIf { tasks -> tasks.any { DownloadState.from(it.state)?.isActive == true } }
            ?.let { tasks ->
                tasks.count { it.state == DownloadState.COMPLETED.wire }.toFloat() /
                    tasks.size.coerceAtLeast(1)
            }
    }
    // A blocked book must say why on the book page too, not only in Downloads.
    val downloadQueueState = if (isVideoBook) {
        null
    } else {
        run {
            val states = downloadTasks.mapNotNull { DownloadState.from(it.state) }
            when {
                states.any { it == DownloadState.NEEDS_SOURCE_ACTION } ->
                    DownloadState.NEEDS_SOURCE_ACTION
                states.any { it == DownloadState.WAITING_NETWORK } -> DownloadState.WAITING_NETWORK
                states.any { it == DownloadState.PAUSED } -> DownloadState.PAUSED
                states.any { it == DownloadState.FAILED } -> DownloadState.FAILED
                else -> null
            }
        }
    }
    val downloadQueueError = downloadTasks.firstOrNull {
        DownloadState.from(it.state) == downloadQueueState
    }?.error
    val downloadBookId = downloadTasks.firstOrNull()?.bookId
    val downloadRunState = when {
        isVideoBook -> null
        downloadTasks.any { it.state == DownloadState.RUNNING.wire } -> DownloadState.RUNNING
        downloadTasks.any { it.state == DownloadState.QUEUED.wire } -> DownloadState.QUEUED
        else -> null
    }
    val videoDownloadActive = isVideoBook && videoDownloadsByIndex.values.any {
        it.state == VideoDownloadState.Queued || it.state == VideoDownloadState.Downloading
    }
    val downloadActive = downloadRunState != null || videoDownloadActive
    val downloadQueued = downloadRunState == DownloadState.QUEUED
    var stopDownloadsConfirm by remember(bookUrl) { mutableStateOf(false) }

    val current = book
    // One count per chapter-list change, shared by the actions and tools blocks.
    val downloadedCount = remember(current?.chapters, isVideoBook, videoCompletedCount) {
        current?.let { downloadedChapterCount(it, isVideoBook, videoCompletedCount) } ?: 0
    }
    // A failed destructive chapter action stays visible with a retry: a toast
    // cannot offer retry, so a swallowed failure made the button look dead.
    // Selection is cleared by onDone only on a real success.
    var chapterDeleteFailure by remember(bookUrl) {
        mutableStateOf<ChapterDeleteFailure?>(null)
    }

    // The format the CTA sends: the user's pick, else the feed's default
    // acquisition, else the first option the server will actually hand over.
    val opdsOptions = current?.downloadOptions.orEmpty()
    val opdsSelected = opdsSelectedOption(opdsOptions, opdsOptionKey)
    // OPDS: the feed decides what the page's primary action is. Every other
    // book keeps the chapter/continue page untouched.
    // An added audiobook keeps its OpenLibrary action but is a real library
    // row: it must render the track list and the play CTA, not the catalogue
    // block.
    val catalogEntry = current?.action != null && !(isAudioBook && current.inLibrary)

    // One add at a time: the same slot turns into progress, Cancel stops it and
    // a late answer from a cancelled job cannot open a book.
    fun addOpdsBook() {
        if (opdsAdding) return
        val optionKey = opdsSelected?.key
        opdsAddFailure = null
        opdsAdding = true
        opdsAddJob = ioScope.launch {
            try {
                when (val result = provider.addOpdsBook(bookUrl, optionKey)) {
                    is Outcome.Ok -> {
                        MiruLog.event(
                            "book",
                            "opds-add",
                            "book" to MiruLog.idHash(bookUrl),
                            "ok" to true,
                        )
                        onOpenBook(result.value)
                    }
                    is Outcome.Err -> {
                        MiruLog.event(
                            "book",
                            "opds-add",
                            "book" to MiruLog.idHash(bookUrl),
                            "ok" to false,
                            "error" to result.error::class.simpleName,
                        )
                        opdsAddFailure = result.error
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                MiruLog.event(
                    "book",
                    "opds-add",
                    "book" to MiruLog.idHash(bookUrl),
                    "ok" to false,
                    "error" to failure.javaClass.simpleName,
                )
                opdsAddFailure = OpdsFailure.Unknown(failure.javaClass.simpleName)
            } finally {
                opdsAdding = false
                opdsAddJob = null
            }
        }
    }

    fun enqueueVideoChapters(indexes: Set<Int>, onDone: (() -> Unit)? = null) {
        val manager = videoDownloads
        val repository = videoRepository
        val target = book
        MiruLog.event(
            "video",
            "enqueue-ui-start",
            "chapters" to indexes.size,
            "manager" to (manager != null),
            "repository" to (repository != null),
            "book" to (target != null),
        )
        when {
            manager == null || repository == null -> bookToast(
                context,
                resources.getString(R.string.book_download_unavailable),
            )
            target == null -> Unit
            indexes.isEmpty() -> Unit
            else -> ioScope.launch {
                try {
                    var queued = 0
                    var needsSource = false
                    val savedKey = AppSettings.videoVariantForBook(bookUrl)
                    val probed = mutableMapOf<String, VideoVariantStatus>()
                    for (chapter in target.chapters.filter { it.index in indexes }) {
                        val streams = try {
                            provider.videoStreams(target.bookUrl, chapter.index)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            MiruLog.w("video", "enqueue-streams failed ch=${chapter.index}", e)
                            null
                        }
                        val available = (streams as? Outcome.Ok)?.value.orEmpty()
                        MiruLog.event(
                            "video",
                            "enqueue-streams",
                            "chapter" to chapter.index,
                            "count" to available.size,
                        )
                        if (available.isEmpty()) continue
                        val chosen = available.firstOrNull {
                            videoVariantKey(it) == savedKey?.trim()
                        } ?: available.first()
                        val status = probed[chosen.url] ?: VideoVariantProbe.probe(
                            chosen.url,
                            repository.headersFor(chosen),
                        ).also { probed[chosen.url] = it }
                        MiruLog.event(
                            "video",
                            "enqueue-probe",
                            "chapter" to chapter.index,
                            "status" to status.name,
                            "chosen" to videoVariantKey(chosen),
                        )
                        when (val decision = videoDownloadDecision(
                            available,
                            savedKey,
                            mapOf(chosen.url to status),
                        )) {
                            VideoDownloadDecision.NoStreams -> continue
                            VideoDownloadDecision.OpenSheet -> {
                                MiruLog.event("video", "enqueue-needs-source", "chapter" to chapter.index)
                                needsSource = true
                                videoSourceSheetOpen = true
                                break
                            }
                            is VideoDownloadDecision.Download -> {
                                val headers = repository.headersFor(decision.stream)
                                val added = manager.enqueue(
                                    target.bookUrl,
                                    chapter.index,
                                    decision.stream,
                                    headers,
                                )
                                MiruLog.event(
                                    "video",
                                    "enqueue-result",
                                    "chapter" to chapter.index,
                                    "added" to added,
                                )
                                if (added) queued++
                            }
                        }
                    }
                    MiruLog.event(
                        "video",
                        "enqueue-ui-done",
                        "queued" to queued,
                        "needsSource" to needsSource,
                    )
                    val message = when {
                        needsSource ->
                            resources.getString(R.string.book_dl_video_source_needed)
                        queued == 0 && videoPausedAll ->
                            resources.getString(R.string.book_dl_video_paused)
                        else -> resources.getString(R.string.book_dl_queued_fmt, queued)
                    }
                    bookToast(context, message)
                } finally {
                    onDone?.invoke()
                }
            }
        }
    }

    fun applyVideoDownloadAction(index: Int, action: VideoDownloadAction) {
        val manager = videoDownloads ?: return
        val item = videoDownloadsByIndex[index] ?: return
        ioScope.launch {
            when (action) {
                VideoDownloadAction.Pause -> manager.pause(item.bookUrl, item.chapterIndex)
                VideoDownloadAction.Resume -> manager.resume(item.bookUrl, item.chapterIndex)
                VideoDownloadAction.Cancel -> manager.cancel(item.bookUrl, item.chapterIndex)
            }
        }
    }

    fun stopDownloadsNow() {
        if (isVideoBook) {
            videoDownloadsByIndex.values
                .filter {
                    it.state == VideoDownloadState.Queued ||
                        it.state == VideoDownloadState.Downloading ||
                        it.state == VideoDownloadState.Paused
                }
                .forEach { item ->
                    applyVideoDownloadAction(item.chapterIndex, VideoDownloadAction.Cancel)
                }
        } else {
            val id = resolvedDownloadBookId ?: downloadBookId
            if (id != null) {
                ioScope.launch { downloadEngine?.cancelNow(id) }
            }
        }
    }

    fun resumeVideoDownloads() {
        val manager = videoDownloads ?: return
        ioScope.launch {
            if (videoPausedAll) manager.resumeAll() else manager.resumeBook(bookUrl)
        }
    }

    fun deleteDownloadsNow(indexes: Set<Int>, onDone: (() -> Unit)?) {
        if (isVideoBook) {
            val manager = videoDownloads
            when {
                manager == null -> bookToast(
                    context,
                    resources.getString(R.string.book_download_unavailable),
                )
                indexes.isEmpty() -> bookToast(
                    context,
                    resources.getString(R.string.book_dl_no_downloads_selected),
                )
                else -> ioScope.launch {
                    manager.removeChapters(bookUrl, indexes)
                    bookToast(
                        context,
                        resources.getString(R.string.book_download_deleted_fmt, indexes.size),
                    )
                    onDone?.invoke()
                }
            }
            return
        }
        val engine = downloadEngine
        when {
            engine == null -> bookToast(
                context,
                resources.getString(R.string.book_download_unavailable),
            )
            indexes.isEmpty() -> bookToast(
                context,
                resources.getString(R.string.book_dl_no_downloads_selected),
            )
            else -> ioScope.launch {
                val result = try {
                    engine.deleteChaptersNow(bookUrl, indexes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MiruLog.w("book", "Delete downloads failed", e)
                    null
                }
                // Reload even on failure: partial deletes invalidate the
                // cached chapter status, so old flags must not linger.
                reloadChapters()
                when (chapterDeleteOutcome(result?.deleted, result?.failed ?: 0)) {
                    ChapterDeleteOutcome.Success -> {
                        chapterDeleteFailure = null
                        bookToast(
                            context,
                            resources.getString(
                                R.string.book_download_deleted_fmt,
                                result?.deleted ?: 0,
                            ),
                        )
                        onDone?.invoke()
                    }
                    ChapterDeleteOutcome.Failed -> {
                        chapterDeleteFailure = ChapterDeleteFailure(
                            message = resources.getString(
                                R.string.book_delete_downloads_failed,
                            ),
                            retry = { deleteDownloadsNow(indexes, onDone) },
                        )
                    }
                    ChapterDeleteOutcome.Nothing -> bookToast(
                        context,
                        resources.getString(R.string.book_dl_no_downloads_selected),
                    )
                }
            }
        }
    }

    fun deleteTranslationsNow(indexes: Set<Int>, onDone: (() -> Unit)?) {
        val urls = book?.chapters.orEmpty()
            .filter { it.index in indexes }
            .map { it.url ?: chapterKey(bookUrl, it.index) }
        ioScope.launch {
            provider.ensureHistoryBook(bookUrl)
            val removed = try {
                provider.deleteChapterTranslations(bookUrl, indexes)
            } catch (e: CancellationException) {
                // Nothing was removed: drop only volatile reader state that
                // may still point at the old text and re-read what persisted.
                withContext(NonCancellable) { reloadChapters() }
                throw e
            } catch (e: Exception) {
                MiruLog.w("book", "Delete translations failed", e)
                null
            }
            // The reader cache may only be dropped once the database actually
            // forgot these chapters.
            if (removed != null) readerGraph.translations.forget(urls)
            MiruLog.event(
                "book",
                "delete-translations",
                "book" to MiruLog.idHash(bookUrl),
                "chapters" to indexes.size,
                "removed" to (removed ?: 0),
            )
            reloadChapters()
            when (chapterDeleteOutcome(removed)) {
                ChapterDeleteOutcome.Success -> {
                    chapterDeleteFailure = null
                    bookToast(
                        context,
                        resources.getString(
                            R.string.book_chapters_translations_deleted_fmt,
                            removed ?: 0,
                        ),
                    )
                    onDone?.invoke()
                }
                ChapterDeleteOutcome.Failed -> {
                    chapterDeleteFailure = ChapterDeleteFailure(
                        message = resources.getString(
                            R.string.book_delete_translations_failed,
                        ),
                        retry = { deleteTranslationsNow(indexes, onDone) },
                    )
                }
                ChapterDeleteOutcome.Nothing -> bookToast(
                    context,
                    resources.getString(R.string.book_sel_no_translations_selected),
                )
            }
        }
    }

    // FULL scope: title, synopsis and chapter titles arrive from the shared
    // metadata controller; the page itself keeps the originals.
    val metadata by readerGraph.metadataTranslations.state.collectAsState()
    val metadataConfig = metadataConfigFor(bookUrl)
    // Wait for the chapter list: submitting an empty list here would stick
    // until an unrelated metadata change, so chapter titles stay untranslated.
    LaunchedEffect(current, metadataConfig, chaptersLoading, translationPresentation.chapterTitles) {
        if (chaptersLoading) return@LaunchedEffect
        val loaded = current ?: return@LaunchedEffect
        val config = metadataConfig ?: return@LaunchedEffect
        readerGraph.metadataTranslations.hydrate(loaded.bookUrl, config)
        readerGraph.metadataTranslations.submitBook(
            BookMetadataJob(
                bookUrl = loaded.bookUrl,
                title = loaded.title,
                description = loaded.description,
                chapters = loaded.chapters.map { it.index to it.title },
                config = config,
                includeChapterTitles = translationPresentation.chapterTitles,
            ),
        )
    }
    val metadataEntry = metadata[bookUrl]?.takeIf { it.config == metadataConfig }
    val imeBottom = LocalImeBottom.current
    LazyColumn(
        state = if (current == null) loadingListState else pageListState,
        modifier = Modifier.fillMaxSize(),
        // The book page is denser than the global 16dp rhythm: one 12dp gap
        // between blocks, 12dp side padding.
        verticalArrangement = Arrangement.spacedBy(MiruSpace.M),
        contentPadding = PaddingValues(
            start = MiruSpace.M,
            end = MiruSpace.M,
            top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
            bottom = contentPadding.calculateBottomPadding() + BottomClearance +
                if (searchFocused) imeBottom else 0.dp,
        ),
    ) {
        if (current == null) {
            item {
                val failure = loadFailure
                if (failure != null) {
                    val report = buildPluginErrorReport(
                        error = failure,
                        sourceId = PluginBookIndex.sourceForBook(bookUrl),
                        sourceName = book?.source,
                        operation = "details",
                        identity = bookUrl,
                    )
                    PluginErrorState(
                        title = report.title ?: stringResource(R.string.book_load_failed),
                        message = report.message
                            ?: stringResource(R.string.book_load_failed_desc),
                        detailText = report.detailText,
                        reportText = report.reportText,
                        authUrl = report.authUrl,
                        authLabel = report.text.authHostPath,
                        onRetry = {
                            loadFailure = null
                            retryTick++
                        },
                        onOpenAuth = { url -> onOpenWebView(url, "") },
                    )
                } else if (loadError != null) {
                    BookErrorCard(
                        message = loadError,
                        onRetry = { retryTick++ },
                    )
                } else {
                    BookSkeleton()
                }
            }
        } else {
            item {
                AnnounceCard(
                    book = current,
                    title = metadataEntry?.title ?: current.title,
                    shelves = shelves,
                    shelvesLoading = shelvesLoading,
                    onShelfClick = { shelfSheetOpen = true },
                    onOpenSearch = { onOpenSearch(current.title) },
                    onCoverRetry = retryCover,
                    onOpenSource = { sourcePanelOpen = true },
                    onToggleLibrary = { requestMembership(!current.inLibrary) },
                    membershipPending = membershipPending,
                    // A catalogue entry has no shelf and no membership write:
                    // its only action is the one the feed advertises.
                    catalogEntry = catalogEntry,
                    onOpenCatalog = { current.originCatalogUrl?.let(onOpenCatalog) },
                )
            }
            coverFailure?.let { report ->
                item(key = "cover-error") {
                    PluginErrorState(
                        title = report.title ?: stringResource(R.string.book_cover_failed),
                        message = report.message.orEmpty(),
                        detailText = report.detailText,
                        reportText = report.reportText,
                        authUrl = report.authUrl,
                        authLabel = report.text.authHostPath,
                        onRetry = {
                            ioScope.launch {
                                retryCover()?.let { fresh ->
                                    book = book?.copy(coverUrl = fresh)
                                }
                            }
                        },
                        onOpenAuth = { url -> onOpenWebView(url, "") },
                    )
                }
            }
            // Statistics, shelf, chapters and downloads all describe a library
            // row: a catalogue entry has none of them.
            if (!catalogEntry) {
                item {
                    StatStrip(
                        book = current,
                        chaptersLoading = chaptersLoading,
                        downloadedOverride = if (isVideoBook) videoCompletedCount else null,
                    )
                }
            }
            item {
                val opdsAction = current.action
                if (opdsAction != null && !(isAudioBook && current.inLibrary)) {
                    OpdsActions(
                        action = opdsAction,
                        options = opdsOptions,
                        selected = opdsSelected,
                        adding = opdsAdding,
                        onSelect = { option -> opdsOptionKey = option.key },
                        onAdd = ::addOpdsBook,
                        onOpenLibrary = { current.libraryBookKey?.let(onOpenBook) },
                        onOpenCatalog = { current.originCatalogUrl?.let(onOpenCatalog) },
                        onCancel = { opdsAddJob?.cancel() },
                    )
                    return@item
                }
                // The CTA label must use the same saved position as the tap.
                // `book.continueChapter` is null until chapters/progress
                // settle, so a saved index is the source of truth here.
                val savedIndex = savedContinue
                val continueChapter = current.continueChapter
                val continueIndex = savedIndex ?: continueChapter?.index
                val continueTitle = continueChapterTitle(
                    savedIndex = savedIndex,
                    continueChapter = continueChapter,
                    chapters = current.chapters,
                    translatedTitles = if (translationPresentation.chapterTitles) {
                        metadataEntry?.chapterTitles.orEmpty()
                    } else {
                        emptyMap()
                    },
                )
                ActionRow(
                    book = current,
                    continueIndexOverride = continueIndex,
                    continueTitleOverride = continueTitle,
                    hasSavedProgress = hasSavedProgress,
                    chaptersLoading = chaptersLoading || !savedLoaded,
                    // No stored rows plus a typed failure is not a real
                    // "No chapters" answer: the CTA names the failure instead.
                    chaptersFailed = chaptersFailure != null && current.chapters.isEmpty(),
                    continueTitle = continueTitle,
                    downloadEnabled = if (isVideoBook) {
                        videoDownloads != null && videoRepository != null
                    } else {
                        downloadEngine != null
                    },
                    showDownload = !isAudioBook,
                    showTools = !isAudioBook,
                    downloadRunning = downloadActive,
                    downloadQueued = downloadQueued,
                    downloadPercent = downloadProgress,
                    onStopDownload = { stopDownloadsConfirm = true },
                    showSource = !current.bookUrl.startsWith("local") &&
                        current.sourceUrl.isNotBlank(),
                    // ToolsSection always paints Migrate, even when disabled:
                    // text books show translator/export/regex + migrate, while
                    // manga/video show only export/source + migrate.
                    toolCount = if (isVideoBook || mangaBook) 2 else 4,
                    toolsExpanded = toolsExpanded,
                    onContinue = {
                        ioScope.launch {
                            val readerBook = current.toReaderBook()
                            val saved = readerGraph.positions.load(bookUrl)
                            val target = ReaderOpenResolver.continueChapter(readerBook, saved)
                            if (readerBook.isVideo) {
                                onOpenVideo(bookUrl, target)
                            } else {
                                onOpenReader(bookUrl, target, true)
                            }
                        }
                    },
                    onOpenDownloadPanel = { downloadPanelOpen = true },
                    onOpenSourcePanel = { sourcePanelOpen = true },
                    onToggleTools = { toolsExpanded = !toolsExpanded },
                )
            }
            if (!catalogEntry && downloadQueueState != null && downloadBookId != null) {
                item {
                    DownloadQueueNotice(
                        state = downloadQueueState,
                        error = downloadQueueError,
                        progress = downloadProgress,
                        sourceUrl = current.sourceUrl,
                        onOpenSource = { onOpenWebView(current.sourceUrl, current.source) },
                        onRetry = { downloadEngine?.retry(downloadBookId) },
                        onPause = { downloadEngine?.pause(downloadBookId) },
                        onResume = { downloadEngine?.resume(downloadBookId) },
                        onCancel = { downloadEngine?.cancel(downloadBookId) },
                    )
                }
            }
            // A paused queue is state, not a toast: Resume stays on the page
            // after the notification is gone or the process was restarted.
            if (isVideoBook && (videoPausedAll || videoPausedCount > 0)) {
                item {
                    VideoDownloadPausedNotice(onResume = ::resumeVideoDownloads)
                }
            }
            if (toolsExpanded && !catalogEntry && !isAudioBook) {
                item {
                    ToolsSection(
                        translatorOn = AppSettings.novelTranslation[current.bookUrl]?.enabled == true,
                        regexCount = AppSettings.novelRegexRules[current.bookUrl]
                            .orEmpty()
                            .count { it.enabled },
                        epubChapters = downloadedCount,
                        epubEnabled = exporter != null && !epubExporting && downloadedCount > 0,
                        epubBusy = epubExporting,
                        manga = mangaBook,
                        video = isVideoBook,
                        cbzChapters = downloadedCount,
                        cbzEnabled = cbzExporter != null && !cbzExporting && !isLocalBook,
                        cbzBusy = cbzExporting,
                        migrateEnabled = migration != null && current.inLibrary &&
                            !current.bookUrl.startsWith("local"),
                        onTranslator = { translatorSheetOpen = true },
                        onEpub = { if (!epubExporting) epubSheetOpen = true },
                        onCbz = { if (!cbzExporting) cbzLauncher.launch(null) },
                        onVideoSource = { videoSourceSheetOpen = true },
                        onRegex = { onOpenRegex(bookUrl) },
                        onMigrate = { migrationSheetOpen = true },
                    )
                }
            }
            if (current.action == BookActionKind.OpenCatalog) {
                // The catalogue only lists the entry: there is nothing to
                // describe, and the catalogue itself is one tap away.
                item(key = "opds-no-details") {
                    Text(
                        text = stringResource(R.string.opds_no_details),
                        style = MiruCardType.Meta,
                        color = LocalMiruExtraColors.current.muted,
                    )
                }
            } else {
                item {
                    AboutSection(description = metadataEntry?.description ?: current.description)
                }
            }
            if (!catalogEntry) chaptersFailure?.let { report ->
                item(key = "chapters-error") {
                    PluginErrorState(
                        title = report.title
                            ?: stringResource(R.string.book_chapters_refresh_failed),
                        message = report.message
                            ?: stringResource(R.string.book_chapters_refresh_failed_desc),
                        detailText = report.detailText,
                        reportText = report.reportText,
                        authUrl = report.authUrl,
                        authLabel = report.text.authHostPath,
                        onRetry = ::refreshChapters,
                        onOpenAuth = { url -> onOpenWebView(url, "") },
                    )
                }
            }
            if (!catalogEntry) item {
                if (chaptersLoading && current.chapters.isEmpty()) {
                    ChaptersSkeleton()
                } else if (showChaptersSection(current.chapters, chaptersFailure)) {
                    ChaptersSection(
                        book = current,
                        newestFirst = newestFirst,
                        refreshing = chaptersRefreshing,
                        notesCount = current.reviews.size,
                        onRefresh = ::refreshChapters,
                        onToggleOrder = {
                            newestFirst = !newestFirst
                            AppSettings.newestFirst = newestFirst
                        },
                        content = {
                        chapterDeleteFailure?.let { failure ->
                            ChapterDeleteErrorNotice(
                                message = failure.message,
                                onRetry = failure.retry,
                                onDismiss = { chapterDeleteFailure = null },
                            )
                            Spacer(modifier = Modifier.height(MiruSpace.S))
                        }
                        ChapterBrowser(
                            book = current,
                            listState = chapterListState,
                            searchFocused = searchFocused,
                            onSearchFocusChanged = { searchFocused = it },
                            translatedTitles = if (translationPresentation.chapterTitles) {
                                metadataEntry?.chapterTitles.orEmpty()
                            } else {
                                emptyMap()
                            },
                            newestFirst = newestFirst,
                            bulkBusy = bulkBusy,
                            isVideoBook = isVideoBook,
                            videoDownloads = videoDownloadsByIndex,
                            onVideoDownloadAction = ::applyVideoDownloadAction,
                            downloadRunning = downloadActive,
                            downloadQueued = downloadQueued,
                            downloadPercent = downloadProgress,
                            onStopDownload = { stopDownloadsConfirm = true },
                            onOpenChapter = { index ->
                                if (current.contentType == ContentType.Video ||
                                    current.contentType == ContentType.Audio
                                ) {
                                    onOpenVideo(bookUrl, index)
                                } else {
                                    // Opening the saved chapter resumes its saved
                                    // offset; any other row starts at the top.
                                    onOpenReader(
                                        current.bookUrl,
                                        index,
                                        index == savedChapterIndex,
                                    )
                                }
                            },
                            onDownloadNext100 = {
                                if (isVideoBook) {
                                    MiruLog.event(
                                        "video",
                                        "download-tap-100",
                                        "chapters" to current.chapters.size,
                                    )
                                    enqueueVideoChapters(
                                        current.chapters.map { it.index }.toSet(),
                                    )
                                    return@ChapterBrowser
                                }
                                val engine = downloadEngine
                                if (engine == null) {
                                    bookToast(
                                        context,
                                        resources.getString(R.string.book_download_unavailable),
                                    )
                                } else {
                                    ioScope.launch {
                                        resolvedDownloadBookId = ensureDownloadBook(provider, engine, current.bookUrl)
                                        if (resolvedDownloadBookId == null) {
                                            bookToast(
                                                context,
                                                resources.getString(R.string.book_dl_book_unavailable),
                                            )
                                            return@launch
                                        }
                                        val result = engine.enqueueNext100Now(
                                            current.bookUrl,
                                            translationConfigured,
                                        )
                                        bookToast(context, enqueueMessage(context, result))
                                        reloadChapters()
                                    }
                                }
                            },
                            onSelectChapters = { selectSheetOpen = true },
                            downloadAvailable = if (isVideoBook) {
                                videoDownloads != null && videoRepository != null
                            } else if (isAudioBook) {
                                false
                            } else {
                                downloadEngine != null
                            },
                            showDownload = !isAudioBook,
                            translationStates = translationStates,
                            translationConfigured = translationConfigured,
                            onTranslate = { indexes, onDone ->
                                if (bulkBusy == null) {
                                    bulkBusy = BulkAction.Translate
                                    ioScope.launch {
                                        try {
                                            val result = bulkTranslator.translate(
                                                current.bookUrl,
                                                indexes,
                                            )
                                            MiruLog.event(
                                                "translation",
                                                "bulk-submit",
                                                "book" to MiruLog.idHash(current.bookUrl),
                                                "chapters" to indexes.size,
                                                "configured" to result.configured,
                                                "enqueued" to result.enqueued,
                                                "already" to result.alreadyCurrent,
                                            )
                                            val message = when {
                                                !result.configured ->
                                                    resources.getString(R.string.book_translate_not_configured)
                                                result.enqueued > 0 -> resources.getString(
                                                    R.string.book_chapters_translating_fmt,
                                                    result.enqueued,
                                                )
                                                result.alreadyCurrent > 0 -> resources.getString(
                                                    R.string.book_chapters_translation_current_fmt,
                                                    result.alreadyCurrent,
                                                )
                                                else -> resources.getString(
                                                    R.string.book_chapters_translation_no_text,
                                                )
                                            }
                                            bookToast(context, message)
                                        } finally {
                                            bulkBusy = null
                                            onDone()
                                        }
                                    }
                                }
                            },
                            onMarkRead = { indexes, read, onDone ->
                                ioScope.launch {
                                    provider.ensureHistoryBook(current.bookUrl)
                                    provider.markChaptersRead(current.bookUrl, indexes, read)
                                    // Stats/Continue/last-read live on the book
                                    // object, not the chapter list: reload both.
                                    reload()
                                    reloadChapters()
                                    bookToast(
                                        context,
                                        resources.getString(
                                            if (read) {
                                                R.string.book_chapters_marked_read_fmt
                                            } else {
                                                R.string.book_chapters_marked_unread_fmt
                                            },
                                            indexes.size,
                                        ),
                                    )
                                    onDone()
                                }
                            },
                            onDeleteTranslations = { indexes, onDone ->
                                pendingTranslationDelete = indexes
                                pendingTranslationDeleteDone = onDone
                            },
                            onDownloadSelected = { indexes ->
                                if (isVideoBook) {
                                    if (bulkBusy == null) {
                                        bulkBusy = BulkAction.Download
                                        enqueueVideoChapters(indexes) { bulkBusy = null }
                                    }
                                    return@ChapterBrowser
                                }
                                val engine = downloadEngine
                                if (bulkBusy != null) {
                                    // Pass already running; the bar is disabled.
                                } else if (engine == null) {
                                    bookToast(
                                        context,
                                        resources.getString(R.string.book_download_unavailable),
                                    )
                                } else {
                                    bulkBusy = BulkAction.Download
                                    ioScope.launch {
                                        try {
                                            resolvedDownloadBookId = ensureDownloadBook(provider, engine, current.bookUrl)
                                            if (resolvedDownloadBookId == null) {
                                                bookToast(
                                                    context,
                                                    resources.getString(R.string.book_dl_book_unavailable),
                                                )
                                                return@launch
                                            }
                                            val result = engine.enqueueNow(
                                                current.bookUrl,
                                                indexes,
                                                translationConfigured,
                                            )
                                            bookToast(context, enqueueMessage(context, result))
                                            reloadChapters()
                                        } finally {
                                            bulkBusy = null
                                        }
                                    }
                                }
                            },
                            onDeleteDownloads = { indexes, onDone ->
                                deleteDownloadsNow(indexes, onDone)
                            },
                            onRetryTranslation = { index ->
                                ioScope.launch {
                                    val result = bulkTranslator.translate(
                                        current.bookUrl,
                                        listOf(index),
                                    )
                                    if (!result.configured) {
                                        bookToast(
                                            context,
                                            resources.getString(
                                                R.string.book_translate_not_configured,
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                        },
                        notesContent = {
                            ReaderNotesSection(
                                reviews = current.reviews,
                                onTranslate = {
                                    bookToast(
                                        context,
                                        resources.getString(R.string.book_review_translate_stub),
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    if (current != null) {
        fun launchDownload(indexes: Set<Int>?, nextCount: Int? = null) {
            MiruLog.event(
                "video",
                "download-tap",
                "video" to isVideoBook,
                "busy" to downloadAllBusy,
                "chapters" to (indexes?.size ?: -1),
                "next" to (nextCount ?: -1),
                "running" to videoDownloadsByIndex.values.count {
                    it.state == VideoDownloadState.Downloading
                },
                "queued" to videoDownloadsByIndex.values.count {
                    it.state == VideoDownloadState.Queued
                },
            )
            if (downloadAllBusy) return
            if (isVideoBook) {
                val pending = current.chapters.asSequence()
                    .filter { !it.isRead }
                    .filter { chapter ->
                        val state = videoDownloadsByIndex[chapter.index]?.state
                        state != VideoDownloadState.Completed &&
                            state != VideoDownloadState.Queued &&
                            state != VideoDownloadState.Downloading &&
                            state != VideoDownloadState.Paused
                    }
                    .map { it.index }
                    .toSet()
                val target = when {
                    indexes != null -> indexes.intersect(pending)
                    nextCount != null -> pending.toList().take(nextCount).toSet()
                    else -> pending
                }
                if (target.isEmpty()) {
                    bookToast(context, resources.getString(R.string.book_dl_nothing_new))
                    return
                }
                downloadAllBusy = true
                enqueueVideoChapters(target) { downloadAllBusy = false }
                return
            }
            val engine = downloadEngine
            if (engine == null) {
                bookToast(context, resources.getString(R.string.book_download_unavailable))
            } else {
                ioScope.launch {
                    downloadAllBusy = true
                    try {
                        resolvedDownloadBookId = ensureDownloadBook(provider, engine, current.bookUrl)
                        if (resolvedDownloadBookId == null) {
                            bookToast(
                                context,
                                resources.getString(R.string.book_dl_book_unavailable),
                            )
                            return@launch
                        }
                        val result = if (nextCount != null) {
                            engine.enqueueNextNow(
                                current.bookUrl,
                                nextCount,
                                translationConfigured,
                            )
                        } else {
                            engine.enqueueNow(
                                current.bookUrl,
                                indexes,
                                translationConfigured,
                            )
                        }
                        MiruLog.event(
                            "downloads",
                            "enqueue",
                            "book" to MiruLog.idHash(current.bookUrl),
                            "queued" to result.queued,
                            "skipped" to result.skipped,
                            "unknown" to result.unknown,
                            "translate" to result.translationRequested,
                            "adopted" to result.adopted,
                            "cached" to result.cached,
                        )
                        bookToast(context, enqueueMessage(context, result))
                        reloadChapters()
                    } finally {
                        downloadAllBusy = false
                    }
                }
            }
        }

        // A failed add is not a toast: the reason and Retry stay on the page
        // and the chosen format is still there to retry with.
        opdsAddFailure?.let { failure ->
            AppBottomSheet(
                title = stringResource(R.string.opds_open_failed),
                description = current.title,
                onClose = { opdsAddFailure = null },
                closeLabel = stringResource(R.string.st_cancel),
            ) {
                PluginErrorState(
                    title = "",
                    message = opdsFailureText(failure, R.string.opds_open_failed),
                    onRetry = {
                        opdsAddFailure = null
                        addOpdsBook()
                    },
                )
            }
        }
        if (sourcePanelOpen) {
            SourcePanel(
                book = current,
                onClose = { sourcePanelOpen = false },
                onOpenWebView = onOpenWebView,
            )
        }
        if (downloadPanelOpen) {
            DownloadPanel(
                busy = downloadAllBusy,
                onClose = { downloadPanelOpen = false },
                onNext = { count ->
                    downloadPanelOpen = false
                    launchDownload(indexes = null, nextCount = count)
                },
                onAllUnread = {
                    downloadPanelOpen = false
                    launchDownload(
                        current.chapters
                            .filter { !it.isRead }
                            .map { it.index }
                            .toSet(),
                    )
                },
            )
        }
        if (shelfSheetOpen) {
            ShelfMoveSheet(
                shelves = shelves,
                currentId = current.shelfId,
                onSelect = { shelf ->
                    shelfSheetOpen = false
                    ioScope.launch {
                        provider.moveShelf(current.bookUrl, shelf.id)
                        shelves = runCatching { libraryProvider?.shelves().orEmpty() }
                            .getOrDefault(emptyList())
                        reload()
                        bookToast(
                            context,
                            resources.getString(R.string.book_moved_fmt, shelfLabel(shelf, resources)),
                        )
                    }
                },
                onClose = { shelfSheetOpen = false },
            )
        }
        if (translatorSheetOpen) {
            TranslatorSheet(
                bookUrl = current.bookUrl,
                bookTitle = current.title,
                onClose = { translatorSheetOpen = false },
            )
        }
        if (videoSourceSheetOpen) {
            VideoVariantSheet(
                streams = videoSourceStreams,
                selectedStreamIndex = initialVideoStreamIndex(
                    videoSourceStreams,
                    AppSettings.videoVariantForBook(bookUrl),
                ),
                probeStatuses = videoSourceStatuses,
                checking = videoSourceProbing > 0,
                deadDisabled = true,
                onRefresh = { videoSourceRefresh++ },
                onSelect = { index ->
                    videoSourceStreams.getOrNull(index)?.let { stream ->
                        AppSettings.setVideoVariantForBook(
                            bookUrl,
                            videoVariantKey(stream),
                        )
                    }
                    videoSourceSheetOpen = false
                },
                onClose = { videoSourceSheetOpen = false },
            )
        }
        if (epubSheetOpen) {
            EpubSheet(
                onClose = { epubSheetOpen = false },
                onRun = { includeTranslation ->
                    epubSheetOpen = false
                    epubIncludeTranslation = includeTranslation
                    if (exporter == null) {
                        bookToast(context, resources.getString(R.string.tool_epub_unavailable))
                    } else if (!epubExporting) {
                        epubLauncher.launch(epubFileName(current.title))
                    }
                },
            )
        }
        if (migrationSheetOpen && migration != null) {
            MigrationBookSheet(
                bookUrl = current.bookUrl,
                bookSource = current.source,
                graph = migration,
                onClose = { migrationSheetOpen = false },
                onLibraryChanged = onLibraryChanged,
                onOpenBook = { targetUrl ->
                    migrationSheetOpen = false
                    onOpenBook(targetUrl)
                },
            )
        }
        if (selectSheetOpen) {
            SelectChaptersSheet(
                book = current,
                onClose = { selectSheetOpen = false },
                showDownload = !isAudioBook,
                onApply = { range, action ->
                    selectSheetOpen = false
                    val indexes = range.toSet()
                    val count = indexes.size
                    MiruLog.event(
                        "book",
                        "select-range",
                        "book" to MiruLog.idHash(current.bookUrl),
                        "from" to range.first,
                        "to" to range.last,
                        "count" to count,
                        "action" to action.name,
                    )
                    when (action) {
                        ChapterRangeAction.MarkRead,
                        ChapterRangeAction.MarkUnread,
                        -> {
                            val read = action == ChapterRangeAction.MarkRead
                            ioScope.launch {
                                provider.ensureHistoryBook(current.bookUrl)
                                provider.markChaptersRead(current.bookUrl, indexes, read)
                                reload()
                                reloadChapters()
                                bookToast(
                                    context,
                                    resources.getString(
                                        if (read) {
                                            R.string.book_chapters_marked_read_fmt
                                        } else {
                                            R.string.book_chapters_marked_unread_fmt
                                        },
                                        count,
                                    ),
                                )
                            }
                        }
                        ChapterRangeAction.Translate -> {
                            if (bulkBusy != null) return@SelectChaptersSheet
                            bulkBusy = BulkAction.Translate
                            ioScope.launch {
                                try {
                                    val result = bulkTranslator.translate(
                                        current.bookUrl,
                                        indexes,
                                    )
                                    MiruLog.event(
                                        "translation",
                                        "bulk-submit",
                                        "book" to MiruLog.idHash(current.bookUrl),
                                        "chapters" to indexes.size,
                                        "configured" to result.configured,
                                        "enqueued" to result.enqueued,
                                        "already" to result.alreadyCurrent,
                                    )
                                    val message = when {
                                        !result.configured -> resources.getString(
                                            R.string.book_translate_not_configured,
                                        )
                                        result.enqueued > 0 -> resources.getString(
                                            R.string.book_chapters_translating_fmt,
                                            result.enqueued,
                                        )
                                        result.alreadyCurrent > 0 -> resources.getString(
                                            R.string.book_chapters_translation_current_fmt,
                                            result.alreadyCurrent,
                                        )
                                        else -> resources.getString(
                                            R.string.book_chapters_translation_no_text,
                                        )
                                    }
                                    bookToast(context, message)
                                } finally {
                                    bulkBusy = null
                                }
                            }
                        }
                        ChapterRangeAction.Download -> {
                            if (isVideoBook) {
                                if (bulkBusy == null) {
                                    bulkBusy = BulkAction.Download
                                    enqueueVideoChapters(indexes) { bulkBusy = null }
                                }
                            } else {
                                val engine = downloadEngine
                                when {
                                    bulkBusy != null -> Unit
                                    engine == null -> bookToast(
                                        context,
                                        resources.getString(R.string.book_download_unavailable),
                                    )
                                    else -> {
                                        bulkBusy = BulkAction.Download
                                        ioScope.launch {
                                            try {
                                                resolvedDownloadBookId = ensureDownloadBook(provider, engine, current.bookUrl)
                                                if (resolvedDownloadBookId == null) {
                                                    bookToast(
                                                        context,
                                                        resources.getString(R.string.book_dl_book_unavailable),
                                                    )
                                                    return@launch
                                                }
                                                val result = engine.enqueueNow(
                                                    current.bookUrl,
                                                    indexes,
                                                    translationConfigured,
                                                )
                                                bookToast(context, enqueueMessage(context, result))
                                                reloadChapters()
                                            } finally {
                                                bulkBusy = null
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ChapterRangeAction.DeleteTranslations -> {
                            pendingTranslationDelete = indexes
                            pendingTranslationDeleteDone = null
                        }
                        ChapterRangeAction.DeleteDownloads -> {
                            deleteDownloadsNow(indexes, null)
                        }
                    }
                },
            )
        }
        pendingTranslationDelete?.let { indexes ->
            DeleteTranslationsSheet(
                count = indexes.size,
                onClose = {
                    pendingTranslationDelete = null
                    pendingTranslationDeleteDone = null
                },
                onConfirm = {
                    val onDone = pendingTranslationDeleteDone
                    pendingTranslationDelete = null
                    pendingTranslationDeleteDone = null
                    deleteTranslationsNow(indexes, onDone)
                },
            )
        }
        if (stopDownloadsConfirm) {
            StopDownloadsSheet(
                onClose = { stopDownloadsConfirm = false },
                onConfirm = {
                    stopDownloadsConfirm = false
                    stopDownloadsNow()
                },
            )
        }
    }
}

@Composable
private fun BookErrorCard(message: String?, onRetry: () -> Unit) {
    MiruCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.book_load_failed),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.book_load_failed_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
        Spacer(modifier = Modifier.height(MiruSpace.M))
        Button(onClick = onRetry) {
            Text(text = stringResource(UiKitR.string.theme_presets_retry))
        }
    }
}

// Loading skeleton in the final geometry: the header card (cover + text +
// tag block), the stat strip, the action rows and the about card — first
// paint never jumps.
@Composable
private fun BookSkeleton() {
    val loadingLabel = stringResource(R.string.book_loading)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = loadingLabel },
            )
            Text(
                text = loadingLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 176.dp, corner = MiruRadius.Card)
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 64.dp, corner = MiruRadius.Card)
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 92.dp, corner = MiruRadius.Card)
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 190.dp, corner = MiruRadius.Card)
    }
}

// Chapters arrive after the metadata; keep the section's slot stable with a
// strip plate + list-shaped bars instead of flashing an empty section.
@Composable
private fun ChaptersSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
    ) {
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 44.dp, corner = MiruRadius.Card)
        repeat(4) {
            SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 40.dp, corner = MiruRadius.Card)
        }
    }
}

// Book header card: cover left, texts right, the shelf chip pinned to the
// bottom of the text column, then a hairline and the genre/tag pool — one
// plate instead of three loose blocks (card + shelf row + chip strip). The
// title is tap → global search, long-press → copy to the clipboard.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnnounceCard(
    book: BookDetails,
    title: String,
    shelves: List<Shelf>,
    shelvesLoading: Boolean,
    onShelfClick: () -> Unit,
    onOpenSearch: () -> Unit,
    onCoverRetry: suspend () -> String?,
    onOpenSource: () -> Unit,
    onToggleLibrary: () -> Unit,
    membershipPending: Boolean,
    // An OPDS entry is not a library row yet: no shelf, no membership toggle,
    // and the chip names the catalogue instead of a plugin.
    catalogEntry: Boolean = false,
    onOpenCatalog: () -> Unit = {},
) {
    val extra = LocalMiruExtraColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val resources = LocalResources.current
    val largeText = isLargeText()
    val fontScale = LocalDensity.current.fontScale
    var titleExpanded by rememberSaveable(title, fontScale) { mutableStateOf(false) }
    var titleOverflow by remember(title, fontScale) { mutableStateOf(false) }
    val shelf = if (shelvesLoading) {
        stringResource(R.string.book_stat_unknown)
    } else {
        val known = shelves.firstOrNull { it.id == book.shelfId }
        if (known == null) shelfLabel(book.shelfId, shelves) else shelfLabel(known)
    }
    val status = buildString {
        append(stringResource(if (book.ongoing) R.string.book_stat_ongoing else R.string.book_stat_completed))
        val updated = book.updatedLabel.trim()
        if (updated.isNotEmpty()) {
            append(" · ")
            append(updated)
        }
    }
    val pluginLabel = stringResource(R.string.book_plugin)
    MiruCard {
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
            BookCover(
                title = title,
                coverUrl = book.coverUrl,
                hasUpdateDot = false,
                modifier = Modifier
                    .width(MiruSize.DetailCoverWidth)
                    .height(MiruSize.DetailCoverHeight),
                corner = MiruRadius.Cover,
                onRetry = onCoverRetry,
            )
            Column(modifier = Modifier.weight(1f)) {
                MiruSourceChip(
                    text = if (book.installId.startsWith("opds:") || book.originCatalogUrl != null) {
                        book.source
                    } else {
                        "${book.source} · $pluginLabel"
                    },
                    onClick = if (catalogEntry) onOpenCatalog else onOpenSource,
                    contentAlignment = Alignment.TopStart,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (titleExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout ->
                        if (!titleExpanded && layout.hasVisualOverflow) titleOverflow = true
                    },
                    modifier = Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = { onOpenSearch() },
                        onLongClick = {
                            clipboard.setText(AnnotatedString(title))
                            bookToast(
                                context,
                                resources.getString(R.string.book_title_copied),
                            )
                        },
                    ),
                )
                if (titleOverflow || titleExpanded) {
                    Text(
                        text = stringResource(
                            if (titleExpanded) {
                                R.string.book_title_collapse
                            } else {
                                R.string.book_title_show_full
                            },
                        ),
                        style = MiruCardType.Meta,
                        color = extra.accent,
                        modifier = Modifier
                            .compactTouchTarget(MiruSize.ChipCompact)
                            .clip(RoundedCornerShape(MiruRadius.Split))
                            .clickable(
                                role = Role.Button,
                                onClick = { titleExpanded = !titleExpanded },
                            )
                            .padding(vertical = MiruSpace.Xxs),
                    )
                }
                Spacer(modifier = Modifier.height(MiruSpace.Xs))
                val author = book.author.trim()
                if (author.isNotEmpty() && !author.equals("Unknown", ignoreCase = true)) {
                    Text(
                        text = stringResource(R.string.book_by_author_fmt, author),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(extra.accent),
                    )
                    Text(
                        text = status,
                        style = MiruCardType.Meta,
                        color = extra.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (book.genres.isNotEmpty()) {
            Spacer(modifier = Modifier.height(MiruSpace.M))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(MiruSpace.S))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                items(book.genres, key = { it }) { genre -> MiruTag(label = genre) }
            }
        }
        if (!catalogEntry) {
            Spacer(modifier = Modifier.height(MiruSpace.M))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(MiruSpace.S))
            val shelfButton: @Composable (Modifier) -> Unit = { modifier ->
                MiruCompactButton(
                    icon = Icons.Filled.DriveFileMove,
                    label = shelf,
                    enabled = book.inLibrary,
                    onClick = onShelfClick,
                    modifier = modifier,
                    trailing = {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = extra.accent,
                            modifier = Modifier.size(MiruSize.IconSmall),
                        )
                    },
                )
            }
            val libraryButton: @Composable (Modifier) -> Unit = { modifier ->
                MiruCompactButton(
                    icon = if (book.inLibrary) {
                        Icons.Filled.BookmarkAdded
                    } else {
                        Icons.Filled.BookmarkBorder
                    },
                    label = stringResource(
                        if (book.inLibrary) {
                            R.string.book_cd_in_library
                        } else {
                            R.string.book_cd_add_library
                        },
                    ),
                    selected = book.inLibrary,
                    busy = membershipPending,
                    busyDescription = stringResource(R.string.book_library_pending),
                    onClick = onToggleLibrary,
                    modifier = modifier,
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (largeText || maxWidth < MiruSize.NarrowContent) {
                    Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                        shelfButton(Modifier.fillMaxWidth())
                        libraryButton(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                        shelfButton(Modifier.weight(1f))
                        libraryButton(Modifier.weight(1f))
                    }
                }
            }
        }

    }
}

internal fun shelfLabel(shelfId: String, shelves: List<Shelf>): String =
    shelves.firstOrNull { it.id == shelfId }?.label ?: shelfId

// Four-compartment stat strip: rating; read/total + percent; status +
// update time; reading time with the TTS share. The rating cell drops out
// entirely when the plugin gives us no score.
@Composable
private fun StatStrip(
    book: BookDetails,
    chaptersLoading: Boolean,
    downloadedOverride: Int? = null,
) {
    val extra = LocalMiruExtraColors.current
    val locale = LocalMiruLocale.current
    val ttsLabel = formatDurationLocalized(book.ttsMinutes * 60_000L)
    val chaptersKnown = bookChaptersCountKnown(chaptersLoading, book.totalChapters)
    val novelDownloaded = remember(book.chapters) { book.chapters.count { it.downloaded } }
    val downloaded = downloadedOverride ?: novelDownloaded
    val largeText = isLargeText()
    val percentLabel = stringResource(
        R.string.book_stat_read_pct_dec_fmt,
        formatPercent(book.readCount, book.totalChapters, locale),
    )

    val ratingValue = if (book.rating > 0f) {
        formatRating(book.rating, locale)
    } else {
        stringResource(R.string.book_stat_none)
    }
    val ratingCell: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = ratingValue,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = extra.accent,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = stringResource(R.string.book_stat_rating),
                    style = MiruCardType.Meta,
                    color = extra.muted,
                    maxLines = 1,
                )
            }
        }
    }
    val progressCell: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (chaptersKnown) {
                MiruSplitProgress(
                    read = book.readCount,
                    total = book.totalChapters,
                    modifier = Modifier.width(MiruSize.SplitProgress * LocalDensity.current.fontScale),
                    contentDescription = stringResource(
                        R.string.card_ratio_fmt,
                        book.readCount,
                        book.totalChapters,
                    ),
                )
                Spacer(modifier = Modifier.height(MiruSpace.Xxs))
                Text(
                    text = percentLabel,
                    style = MiruCardType.Meta,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    // Audio has no downloads by design: show the track count
                    // instead of a permanent fake "0 downloaded".
                    text = if (book.contentType == ContentType.Audio) {
                        stringResource(R.string.book_stat_tracks_fmt, book.totalChapters)
                    } else {
                        stringResource(R.string.book_stat_downloaded_fmt, downloaded)
                    },
                    style = MiruCardType.Meta,
                    color = if (book.contentType == ContentType.Audio) extra.muted else extra.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = stringResource(R.string.book_stat_none),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.book_stat_none),
                    style = MiruCardType.Meta,
                    color = extra.muted,
                    maxLines = 1,
                )
            }
        }
    }
    val readTime = formatDurationLocalized(book.readMinutes * 60_000L)
    val totalTime = formatDurationLocalized((book.readMinutes + book.ttsMinutes) * 60_000L)
    val timeCell: @Composable (Modifier, Boolean) -> Unit = { modifier, _ ->
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = totalTime,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.book_stat_read_fmt, readTime),
                style = MiruCardType.Meta,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.ttsMinutes > 0) {
                Text(
                    text = stringResource(R.string.book_stat_tts_value_fmt, ttsLabel),
                    style = MiruCardType.Meta,
                    color = extra.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    val dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    MiruCard(contentPadding = PaddingValues(horizontal = MiruSpace.M, vertical = MiruSpace.S)) {
        if (largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                ratingCell(Modifier.fillMaxWidth())
                HorizontalDivider(color = dividerColor)
                progressCell(Modifier.fillMaxWidth())
                HorizontalDivider(color = dividerColor)
                timeCell(Modifier.fillMaxWidth(), false)
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MiruSize.StatStripHeight),
            ) {
                ratingCell(Modifier.weight(1f))
                VerticalDivider(
                    modifier = Modifier
                        .height(MiruSize.StatStripHeight)
                        .padding(vertical = MiruSpace.Xs),
                    color = dividerColor,
                )
                progressCell(Modifier.weight(1f))
                VerticalDivider(
                    modifier = Modifier
                        .height(MiruSize.StatStripHeight)
                        .padding(vertical = MiruSpace.Xs),
                    color = dividerColor,
                )
                timeCell(Modifier.weight(1f), true)
            }
        }
    }
}

// The acquisition the CTA sends to the provider: a still-downloadable pick
// wins, then the feed's default, then the first option the server will hand
// over. Null leaves the choice to the provider (no options, or all of them
// sample/buy-only — the add then fails with its own reason).
internal fun opdsSelectedOption(
    options: List<BookDownloadOption>,
    selectedKey: String?,
): BookDownloadOption? =
    options.firstOrNull { it.key == selectedKey }?.takeIf { it.isDownloadable }
        ?: options.firstOrNull { it.isDefault && it.isDownloadable }
        ?: options.firstOrNull { it.isDownloadable }

// OPDS: the page's only action. The feed's acquisitions sit right above it —
// a rail of formats when there is a real choice, one plain label otherwise —
// and an option the server will not hand over keeps its reason under it.
@Composable
private fun opdsReasonRes(reason: String): Int = when {
    reason.equals("Sample", true) -> R.string.opds_reason_sample
    reason.equals("Buy", true) -> R.string.opds_reason_buy
    reason.equals("Borrow", true) -> R.string.opds_reason_borrow
    reason.equals("Subscribe", true) -> R.string.opds_reason_subscribe
    reason.equals("IndirectOnly", true) -> R.string.opds_reason_indirect
    else -> R.string.opds_reason_unsupported
}

@Composable
private fun OpdsActions(
    action: BookActionKind,
    options: List<BookDownloadOption>,
    selected: BookDownloadOption?,
    adding: Boolean,
    onSelect: (BookDownloadOption) -> Unit,
    onAdd: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenCatalog: () -> Unit,
    onCancel: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val downloadable = remember(options) { options.filter { it.isDownloadable } }
    val label = when (action) {
        BookActionKind.AddToLibrary -> stringResource(R.string.opds_add_to_library)
        BookActionKind.OpenLibrary -> stringResource(R.string.opds_open)
        // Known entry with pdf/djvu/mobi only: say so instead of bouncing back
        // to the catalogue, which looks like an endless loop.
        BookActionKind.NotDownloadable -> stringResource(R.string.opds_no_format)
        else -> stringResource(R.string.opds_open_catalog)
    }
    val onClick = when (action) {
        BookActionKind.AddToLibrary -> onAdd
        BookActionKind.OpenLibrary -> onOpenLibrary
        BookActionKind.NotDownloadable -> ({})
        else -> onOpenCatalog
    }
    val leadingIcon = when (action) {
        BookActionKind.AddToLibrary -> Icons.Filled.BookmarkBorder
        BookActionKind.OpenLibrary -> Icons.Filled.PlayArrow
        BookActionKind.NotDownloadable -> Icons.Filled.Block
        else -> Icons.Filled.Public
    }
    Column {
        if (options.isNotEmpty()) {
            if (downloadable.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    options.forEach { option ->
                        MiruFilterChip(
                            selected = option.key == selected?.key,
                            onClick = { onSelect(option) },
                            label = option.format,
                            enabled = option.isDownloadable,
                        )
                    }
                }
            } else {
                // One usable file is not a choice: a plain label, no selector.
                downloadable.firstOrNull()?.let { option ->
                    Text(
                        text = option.format,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            options.forEach { option ->
                val reason = option.unavailableReason ?: return@forEach
                Text(
                    text = "${option.format} · ${stringResource(opdsReasonRes(reason))}",
                    style = MiruCardType.Meta,
                    color = extra.muted,
                )
            }
            Spacer(modifier = Modifier.height(MiruSpace.S))
        }
        // The same 48dp pill in both states keeps the page from jumping while
        // the file is being added; Cancel sits beside it, never below it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            MiruButton(
                onClick = onClick,
                style = MiruButtonStyle.InversePrimary,
                enabled = !adding && action != BookActionKind.NotDownloadable,
                busy = adding,
                busyDescription = label,
                leadingIcon = leadingIcon,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (adding) stringResource(R.string.opds_opening_book) else label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (adding) {
                MiruButton(onClick = onCancel, style = MiruButtonStyle.Text) {
                    Text(stringResource(R.string.st_cancel), maxLines = 1)
                }
            }
        }
    }
}

// Continue / Start / Read again as the page's loudest action: one
// full-width inverse pill (same ink pair as the sheet footers). Under it a
// pair of plate pills — download-all first (left), then the library toggle
// (right; download-all refuses with a hint until the book is in the library
// and shows its progress in place).
@Composable
private fun ActionRow(
    book: BookDetails,
    chaptersLoading: Boolean,
    continueTitle: String,
    continueIndexOverride: Int? = null,
    continueTitleOverride: String? = null,
    hasSavedProgress: Boolean = false,
    // A failed refresh with no stored rows is not a genuine empty list.
    chaptersFailed: Boolean = false,
    downloadEnabled: Boolean,
    showDownload: Boolean = true,
    // Audio has no tools (no translation/export/migration); the source row and
    // the library toggle remain.
    showTools: Boolean = true,
    downloadRunning: Boolean = false,
    downloadQueued: Boolean = false,
    downloadPercent: Float? = null,
    onStopDownload: () -> Unit = {},
    showSource: Boolean,
    toolCount: Int,
    toolsExpanded: Boolean,
    onContinue: () -> Unit,
    onOpenDownloadPanel: () -> Unit,
    onOpenSourcePanel: () -> Unit,
    onToggleTools: () -> Unit,
) {
    val continueChapter = book.continueChapter
    val continueIndex = continueIndexOverride ?: continueChapter?.index
    val resolvedContinueTitle = continueTitleOverride
        ?.takeIf { it.isNotBlank() }
        ?: continueTitle
    val action = bookActionState(
        chaptersLoading = chaptersLoading,
        totalChapters = book.totalChapters,
        readCount = book.readCount,
        chapterIndex = continueIndex,
        chapterTitle = resolvedContinueTitle.takeIf { it.isNotBlank() }
            ?: continueChapter?.title?.takeIf { it.isNotBlank() },
        hasSavedProgress = hasSavedProgress,
    )
    val chapterIndex = action.chapterIndex
    val chapterTitle = action.chapterTitle?.takeIf { it.isNotBlank() }
    // No fallback "Ch. 1": while loading there is no chapter to name yet, and
    // a real chapter list always answers with an index before this runs.
    val label = when (action.kind) {
        BookActionKind.LoadingChapters -> stringResource(R.string.book_chapters_loading)
        BookActionKind.NoChapters -> stringResource(
            if (chaptersFailed) {
                R.string.book_chapters_refresh_failed
            } else {
                R.string.book_no_chapters
            },
        )
        BookActionKind.ReadAgain -> chapterTitle?.let {
            stringResource(R.string.book_read_again_title_fmt, it)
        } ?: chapterIndex?.let {
            stringResource(R.string.book_read_again_fmt, it)
        } ?: stringResource(R.string.book_no_chapters)
        BookActionKind.Start -> stringResource(R.string.book_start)
        BookActionKind.Continue -> chapterTitle?.let {
            stringResource(R.string.book_continue_title_fmt, it)
        } ?: chapterIndex?.let {
            stringResource(R.string.book_continue_fmt, it)
        } ?: stringResource(R.string.book_no_chapters)
        // Catalogue entries never reach this row (the page swaps in the OPDS
        // action block); the labels only keep the enum mapping total.
        BookActionKind.AddToLibrary -> stringResource(R.string.opds_add_to_library)
        BookActionKind.OpenLibrary -> stringResource(R.string.opds_open)
        BookActionKind.OpenCatalog -> stringResource(R.string.opds_open_catalog)
        BookActionKind.NotDownloadable -> stringResource(R.string.opds_no_format)
    }
    Column {
        MiruButton(
            onClick = onContinue,
            enabled = action.enabled,
            style = MiruButtonStyle.InversePrimary,
            busy = action.kind == BookActionKind.LoadingChapters,
            busyDescription = label,
            leadingIcon = Icons.Filled.PlayArrow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        val largeText = isLargeText()
        val download: @Composable (Modifier) -> Unit = { modifier ->
            if (downloadRunning) {
                val fraction = downloadPercent?.coerceIn(0f, 1f)
                Row(
                    modifier = modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    MiruCompactButton(
                        icon = Icons.Filled.Download,
                        label = if (downloadQueued) {
                            stringResource(R.string.book_ch_dl_queued)
                        } else if (fraction != null) {
                            stringResource(
                                R.string.dl_downloading_fmt,
                                (fraction * 100).roundToInt(),
                            )
                        } else {
                            stringResource(R.string.book_action_download)
                        },
                        progress = if (downloadQueued) null else fraction,
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                    MiruIconButton(
                        icon = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.dl_action_stop),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onStopDownload,
                        modifier = Modifier.size(MiruSize.Control),
                    )
                }
            } else {
                MiruCompactButton(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.book_action_download),
                    enabled = downloadEnabled,
                    onClick = onOpenDownloadPanel,
                    modifier = modifier,
                )
            }
        }
        val source: @Composable (Modifier) -> Unit = { modifier ->
            MiruCompactButton(
                icon = Icons.Filled.Public,
                label = stringResource(R.string.book_action_source),
                onClick = onOpenSourcePanel,
                modifier = modifier,
            )
        }
        val options: @Composable (Modifier) -> Unit = { modifier ->
            MiruCompactButton(
                icon = Icons.Filled.Tune,
                label = stringResource(R.string.book_action_options_fmt, toolCount),
                selected = toolsExpanded,
                onClick = onToggleTools,
                modifier = modifier,
            )
        }
        if (largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                if (showDownload) download(Modifier.fillMaxWidth())
                if (showSource) source(Modifier.fillMaxWidth())
                if (showTools) options(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                if (showDownload) download(Modifier.weight(1f))
                if (showSource) source(Modifier.weight(1f))
                if (showTools) options(Modifier.weight(1f))
            }
        }
    }
}

// Honest book-level download block: the queue state plus the action that can
// actually change it. No fake "downloading" while CF/login or the network is
// blocking the source.
@Composable
private fun DownloadQueueNotice(
    state: DownloadState,
    error: String?,
    progress: Float? = null,
    sourceUrl: String,
    onOpenSource: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = when (state) {
        DownloadState.NEEDS_SOURCE_ACTION -> R.string.dl_status_source_action
        DownloadState.WAITING_NETWORK -> R.string.dl_status_waiting_network
        DownloadState.PAUSED -> R.string.dl_status_paused
        else -> R.string.dl_status_failed_fmt
    }
    val titleText = if (state == DownloadState.FAILED) {
        stringResource(title, error ?: stringResource(R.string.dl_status_cancelled))
    } else {
        stringResource(title)
    }
    MiruSectionCard(
        title = titleText,
        description = error?.takeIf { it.isNotBlank() } ?: stringResource(
            when (state) {
                DownloadState.NEEDS_SOURCE_ACTION -> R.string.book_dl_source_action_desc
                DownloadState.WAITING_NETWORK -> R.string.book_dl_waiting_network_desc
                DownloadState.PAUSED -> R.string.book_dl_paused_desc
                else -> R.string.book_dl_failed_desc
            },
        ),
        contentPadding = PaddingValues(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
    ) {
        if (progress != null) {
            CardProgressBar(
                fraction = progress,
                height = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs)) {
            if (state == DownloadState.NEEDS_SOURCE_ACTION && sourceUrl.isNotBlank()) {
                TextButton(onClick = onOpenSource) {
                    Text(stringResource(R.string.dl_action_open_source))
                }
            }
            if (state != DownloadState.PAUSED) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.dl_action_retry))
                }
            }
            if (state == DownloadState.PAUSED) {
                TextButton(onClick = onResume) {
                    Text(stringResource(R.string.dl_action_resume))
                }
            }
            if (state == DownloadState.WAITING_NETWORK) {
                TextButton(onClick = onPause) {
                    Text(stringResource(R.string.dl_action_pause))
                }
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.dl_action_cancel))
            }
        }
    }
}

// Persistent Resume for video downloads. Media3 keeps STOPPED rows across
// process death, so this block is derived from the real queue state instead of
// a transient notification.
@Composable
private fun VideoDownloadPausedNotice(onResume: () -> Unit) {
    MiruSectionCard(
        title = stringResource(R.string.dl_status_paused),
        description = stringResource(R.string.book_dl_paused_desc),
        contentPadding = PaddingValues(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
    ) {
        TextButton(onClick = onResume) {
            Text(stringResource(R.string.dl_action_resume))
        }
    }
}

// A failed delete is state, not a disappearing toast: the row stays above the
// chapter list with the same retry until it succeeds or the user dismisses it.
// The selection is untouched here; it is cleared by the action only on success.
@Composable
private fun ChapterDeleteErrorNotice(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiruRadius.Chip))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = MiruSpace.M, top = MiruSpace.Xxs, bottom = MiruSpace.Xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.dl_action_retry))
        }
        MiruIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.book_delete_error_dismiss),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            iconSize = MiruSize.IconCompact,
            onClick = onDismiss,
        )
    }
}

// ABOUT THIS STORY: ONE card — title + description line, hairline, then the
// synopsis (three lines max, inline "Read more" at the end of the third line,
// toggling in place). Same construction as the genre block of the header.
@Composable
private fun AboutSection(description: String) {
    if (description.isBlank()) return
    var expanded by remember(description) { mutableStateOf(false) }
    val synopsisTitle = stringResource(R.string.book_synopsis)
    MiruCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = remember(synopsisTitle) { synopsisTitle.uppercase(Locale.ROOT) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    if (expanded) R.string.book_read_less else R.string.book_read_more,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = LocalMiruExtraColors.current.accent,
                modifier = Modifier
                    .compactTouchTarget(MiruSize.ChipCompact)
                    .clip(RoundedCornerShape(MiruRadius.Cover))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(MiruSpace.Xs),
            )
        }
        Spacer(modifier = Modifier.height(MiruSpace.Xs))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Shared inline expander (About + reader notes). The toggle is part of the
// text itself, never a sibling in a Row: a sibling stole its width from the
// paragraph, so an expanded text stopped short of the card edge and left a
// void next to the toggle.
//   collapsed: the text is cut so that "… Read more" ends exactly the last
//              allowed line (measured with the real width + style, so it also
//              holds for long words, CJK without spaces and big font scales);
//   expanded:  the full text at the full width, "Read less" trails the last
//              line inline.
// A text that fits in N lines gets no toggle at all.
@Composable
internal fun ExpandableBody(text: String, collapsedLines: Int) {
    val style = MaterialTheme.typography.bodyMedium
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = LocalMiruExtraColors.current.accent
    val measurer = rememberTextMeasurer()
    var expanded by remember(text) { mutableStateOf(false) }
    val more = stringResource(R.string.book_read_more)
    val less = stringResource(R.string.book_read_less)
    val linkLook = remember(accent) {
        TextLinkStyles(style = SpanStyle(color = accent, fontWeight = FontWeight.Medium))
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        val widthPx = constraints.maxWidth
        val shown: AnnotatedString = remember(text, expanded, widthPx, style, more, less, linkLook) {
            val layout = measurer.measure(
                text = AnnotatedString(text),
                style = style,
                constraints = Constraints(maxWidth = widthPx),
            )
            when {
                layout.lineCount <= collapsedLines -> AnnotatedString(text)
                expanded -> buildAnnotatedString {
                    append(text.trimEnd())
                    append("  ")
                    withLink(
                        LinkAnnotation.Clickable(tag = "less", styles = linkLook) {
                            expanded = false
                        },
                    ) { append(less) }
                }
                else -> {
                    val lastLineStart = layout.getLineStart(collapsedLines - 1)
                    var low = lastLineStart
                    var high = layout.getLineEnd(collapsedLines - 1, visibleEnd = true)
                    fun candidate(end: Int) = buildAnnotatedString {
                        append(text.substring(0, end).trimEnd())
                        append("… ")
                        withStyle(linkLook.style!!) { append(more) }
                    }
                    // Largest cut whose "… Read more" still fits the allowed lines.
                    while (low < high) {
                        val mid = (low + high + 1) / 2
                        val lines = measurer.measure(
                            text = candidate(mid),
                            style = style,
                            constraints = Constraints(maxWidth = widthPx),
                        ).lineCount
                        if (lines <= collapsedLines) low = mid else high = mid - 1
                    }
                    // Prefer a word boundary when the last line has one.
                    val head = text.substring(0, low)
                    val space = head.lastIndexOfAny(charArrayOf(' ', '\n'))
                    val end = if (space > lastLineStart) space else low
                    buildAnnotatedString {
                        append(text.substring(0, end).trimEnd())
                        append("… ")
                        withLink(
                            LinkAnnotation.Clickable(tag = "more", styles = linkLook) {
                                expanded = true
                            },
                        ) { append(more) }
                    }
                }
            }
        }
        Text(
            text = shown,
            style = style,
            color = ink,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// BOOK-SPECIFIC TOOLS: four rows, each opens its own AppBottomSheet — the
// sheets live in BookSheets.kt, one visual language app-wide.
@Composable
private fun ToolsSection(
    translatorOn: Boolean,
    regexCount: Int,
    epubChapters: Int,
    epubEnabled: Boolean,
    epubBusy: Boolean,
    manga: Boolean,
    video: Boolean,
    cbzChapters: Int,
    cbzEnabled: Boolean,
    cbzBusy: Boolean,
    migrateEnabled: Boolean,
    onTranslator: () -> Unit,
    onEpub: () -> Unit,
    onCbz: () -> Unit,
    onVideoSource: () -> Unit,
    onRegex: () -> Unit,
    onMigrate: () -> Unit,
) {
    val largeText = isLargeText()
    val translatorTile: @Composable (Modifier) -> Unit = { modifier ->
        MiruToolTile(
            glyph = "文A",
            title = stringResource(R.string.tool_translator),
            subtitle = stringResource(R.string.tool_translator_desc),
            badge = stringResource(
                if (translatorOn) R.string.book_tool_on else R.string.book_tool_off,
            ),
            badgeHighlighted = translatorOn,
            onClick = onTranslator,
            modifier = modifier,
        )
    }
    val epubTile: @Composable (Modifier) -> Unit = { modifier ->
        MiruToolTile(
            icon = Icons.Filled.Download,
            title = stringResource(R.string.tool_epub),
            subtitle = when {
                epubBusy -> stringResource(R.string.loading_exporting)
                epubChapters == 0 -> stringResource(R.string.book_tool_epub_no_chapters)
                else -> stringResource(R.string.tool_epub_desc)
            },
            badge = stringResource(R.string.book_tool_chapters_fmt, epubChapters),
            badgeHighlighted = epubChapters > 0,
            enabled = epubEnabled && epubChapters > 0,
            onClick = onEpub,
            modifier = modifier,
        )
    }
    val cbzTile: @Composable (Modifier) -> Unit = { modifier ->
        MiruToolTile(
            icon = Icons.Filled.Download,
            title = stringResource(R.string.tool_cbz),
            subtitle = when {
                cbzBusy -> stringResource(R.string.loading_exporting)
                cbzChapters == 0 -> stringResource(R.string.tool_cbz_no_chapters)
                else -> stringResource(R.string.tool_cbz_desc)
            },
            badge = stringResource(R.string.book_tool_chapters_fmt, cbzChapters),
            badgeHighlighted = cbzChapters > 0,
            enabled = cbzEnabled && cbzChapters > 0,
            onClick = onCbz,
            modifier = modifier,
        )
    }
    // Manga is exported as CBZ (one archive per chapter); text keeps EPUB.
    // A video book has no export: its tool is the source picker with a probe.
    val videoSourceTile: @Composable (Modifier) -> Unit = { modifier ->
        MiruToolTile(
            icon = Icons.Filled.PlayArrow,
            title = stringResource(R.string.book_tool_video_source),
            subtitle = stringResource(R.string.book_tool_video_source_desc),
            onClick = onVideoSource,
            modifier = modifier,
        )
    }
    val exportTile = when {
        video -> videoSourceTile
        manga -> cbzTile
        else -> epubTile
    }
    val regexTile: @Composable (Modifier) -> Unit = { modifier ->
        MiruToolTile(
            glyph = "=×",
            title = stringResource(R.string.tool_regex),
            subtitle = stringResource(R.string.tool_regex_desc),
            badge = stringResource(R.string.book_tool_rules_fmt, regexCount),
            badgeHighlighted = regexCount > 0,
            onClick = onRegex,
            modifier = modifier,
        )
    }
    val migrateTile: @Composable (Modifier) -> Unit = { modifier ->
        MiruToolTile(
            icon = Icons.Filled.DriveFileMove,
            title = stringResource(R.string.tool_migrate),
            subtitle = stringResource(
                if (migrateEnabled) R.string.tool_migrate_desc else R.string.migration_needs_library,
            ),
            badge = stringResource(
                if (migrateEnabled) R.string.book_tool_source else R.string.book_tool_migrate_needs_library,
            ),
            enabled = migrateEnabled,
            onClick = onMigrate,
            modifier = modifier,
        )
    }
    // Translation and text cleanup only exist for text books: a manga or
    // video book has no text blocks for either tool to act on.
    val textTools = !manga && !video
    val toolsTitle = stringResource(R.string.book_section_tools)
    MiruCard(contentPadding = PaddingValues(MiruSpace.M)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = remember(toolsTitle) { toolsTitle.uppercase(Locale.ROOT) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.book_tools_scope),
                style = MiruCardType.Meta,
                color = LocalMiruExtraColors.current.muted,
            )
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        if (largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                if (textTools) translatorTile(Modifier.fillMaxWidth())
                exportTile(Modifier.fillMaxWidth())
                if (textTools) regexTile(Modifier.fillMaxWidth())
                migrateTile(Modifier.fillMaxWidth())
            }
        } else if (!textTools) {
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                exportTile(Modifier.weight(1f))
                migrateTile(Modifier.weight(1f))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    translatorTile(Modifier.fillMaxWidth())
                    regexTile(Modifier.fillMaxWidth())
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    exportTile(Modifier.fillMaxWidth())
                    migrateTile(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// Source-page block: the same one-card construction and the same row
// language as the tools — the globe row opens the source page in the in-app
// WebView; when the plugin advertises auth, a login row follows. A failed
// open is silently ignored (pure UI, no Toast Leak).
@Composable
private fun SourcePanel(
    book: BookDetails,
    onClose: () -> Unit,
    onOpenWebView: (url: String, title: String) -> Unit,
) {
    val host = remember(book.sourceUrl) {
        runCatching { Uri.parse(book.sourceUrl).host }.getOrNull().orEmpty().ifBlank { book.source }
    }
    AppBottomSheet(
        title = stringResource(R.string.book_source_title_fmt, book.source),
        description = stringResource(R.string.book_source_panel_desc_fmt, host),
        onClose = onClose,
        compact = true,
    ) {
        MiruActionRow(
            icon = Icons.Filled.Public,
            title = stringResource(R.string.book_source_open_page),
            subtitle = host,
            onClick = {
                onClose()
                if (book.sourceUrl.isNotBlank()) onOpenWebView(book.sourceUrl, book.source)
            },
        )
        if (book.authUrl != null) {
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Login,
                title = stringResource(R.string.book_source_login_webview),
                subtitle = stringResource(R.string.book_source_login_desc),
                onClick = {
                    onClose()
                    book.authUrl?.let { onOpenWebView(it, book.source) }
                },
            )
        }
    }
}

@Composable
private fun DownloadPanel(
    busy: Boolean,
    onClose: () -> Unit,
    onNext: (Int) -> Unit,
    onAllUnread: () -> Unit,
) {
    // House standard: header + action rows, no compact chip grid.
    AppBottomSheet(
        title = stringResource(R.string.book_action_download),
        description = stringResource(R.string.book_download_panel_desc),
        onClose = onClose,
    ) {
        listOf(10, 50, 100).forEachIndexed { index, count ->
            if (index > 0) MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Download,
                title = stringResource(R.string.book_download_next_fmt, count),
                subtitle = stringResource(R.string.book_download_next_desc),
                enabled = !busy,
                trailing = {},
                onClick = { onNext(count) },
            )
        }
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.Download,
            title = stringResource(R.string.book_download_all_unread),
            subtitle = stringResource(R.string.book_download_all_unread_desc),
            enabled = !busy,
            trailing = {},
            onClick = onAllUnread,
        )
    }
}

private fun downloadedChapterCount(
    book: BookDetails,
    isVideoBook: Boolean,
    videoCompletedCount: Int,
): Int = if (isVideoBook) videoCompletedCount else book.chapters.count { it.downloaded }

// Chapters: count + read/downloaded summary on the left, the reading-order
// toggle on the right of the SAME header, and the whole browser (search,
// bulk buttons, list) on the same plate under the hairline — the order is a
// property of the list below, so it sits where the list is named. Tap flips
// it; the chapters follow live. Default is newest first (plugins serve
// 1 -> last).
@Composable
private fun ChaptersSection(
    book: BookDetails,
    newestFirst: Boolean,
    refreshing: Boolean,
    notesCount: Int,
    onRefresh: () -> Unit,
    onToggleOrder: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    notesContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val refreshLabel = stringResource(R.string.book_chapters_refreshing)
    val largeText = isLargeText()
    val tabs: @Composable (Boolean) -> Unit = { wrap ->
        if (wrap) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
            ) {
                BookChapterTabs(book.totalChapters, notesCount, tab, onTab = { tab = it })
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            ) {
                BookChapterTabs(book.totalChapters, notesCount, tab, onTab = { tab = it })
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            if (refreshing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = refreshLabel },
                )
            } else {
                val refreshCd = stringResource(R.string.book_chapters_refresh_cd)
                Box(
                    modifier = Modifier
                        .size(MiruSize.Touch)
                        .clip(RoundedCornerShape(MiruRadius.Cover))
                        .clickable(role = Role.Button, onClick = onRefresh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = refreshCd,
                        tint = LocalMiruExtraColors.current.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            ChapterOrderToggle(newestFirst = newestFirst, onClick = onToggleOrder)
        }
    }
    MiruCard(contentPadding = PaddingValues(MiruSpace.M)) {
        if (largeText) {
            if (notesCount > 0) {
                tabs(true)
            } else {
                Text(
                    text = stringResource(R.string.book_chapters_header_fmt, book.totalChapters),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (tab == 0) {
                Spacer(modifier = Modifier.height(MiruSpace.Xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    actions()
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    if (notesCount > 0) {
                        tabs(false)
                    } else {
                        Text(
                            text = stringResource(
                                R.string.book_chapters_header_fmt,
                                book.totalChapters,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (tab == 0) {
                    Spacer(modifier = Modifier.width(MiruSpace.S))
                    actions()
                }
            }
        }
        Spacer(modifier = Modifier.height(MiruSpace.M))
        if (tab == 0) content() else notesContent()
    }
}

@Composable
private fun BookChapterTabs(
    totalChapters: Int,
    notesCount: Int,
    selectedTab: Int,
    onTab: (Int) -> Unit,
) {
    MiruTabPill(
        label = stringResource(R.string.book_chapters_header_fmt, totalChapters),
        selected = selectedTab == 0,
        onClick = { onTab(0) },
    )
    if (notesCount > 0) {
        MiruTabPill(
            label = notesCount.toString(),
            selected = selectedTab == 1,
            onClick = { onTab(1) },
            icon = Icons.Filled.Star,
            iconContentDescription = stringResource(R.string.book_notes_tab_cd_fmt, notesCount),
        )
    }
}

@Composable
private fun ChapterOrderToggle(newestFirst: Boolean, onClick: () -> Unit) {
    val accent = LocalMiruExtraColors.current.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
        modifier = Modifier
            .clip(RoundedCornerShape(MiruRadius.Control))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.book_order_cd),
                onClick = onClick,
            )
            .defaultMinSize(minHeight = MiruSize.Touch)
            .padding(horizontal = MiruSpace.S),
    ) {
        Icon(
            imageVector = if (newestFirst) {
                Icons.Filled.ArrowDownward
            } else {
                Icons.Filled.ArrowUpward
            },
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(MiruSize.IconSmall),
        )
        Text(
            text = stringResource(
                if (newestFirst) R.string.book_order_newest else R.string.book_order_oldest,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
        )
    }
}

// Chapter browser: search field, two buttons, selection bar, then the
// chapter list in its own scroll window (trails the READING ORDER switch).
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ChapterBrowser(
    book: BookDetails,
    listState: LazyListState,
    searchFocused: Boolean,
    onSearchFocusChanged: (Boolean) -> Unit,
    translatedTitles: Map<Int, String>,
    newestFirst: Boolean,
    bulkBusy: BulkAction?,
    isVideoBook: Boolean,
    videoDownloads: Map<Int, VideoDownloadItem>,
    onVideoDownloadAction: (Int, VideoDownloadAction) -> Unit,
    translationStates: Map<String, ChapterTranslation>,
    translationConfigured: Boolean,
    downloadAvailable: Boolean,
    showDownload: Boolean = true,
    onOpenChapter: (Int) -> Unit,
    onDownloadNext100: () -> Unit,
    downloadRunning: Boolean,
    downloadQueued: Boolean,
    downloadPercent: Float?,
    onStopDownload: () -> Unit,
    onSelectChapters: () -> Unit,
    onTranslate: (Set<Int>, () -> Unit) -> Unit,
    onMarkRead: (Set<Int>, Boolean, () -> Unit) -> Unit,
    onDeleteTranslations: (Set<Int>, () -> Unit) -> Unit,
    onDownloadSelected: (Set<Int>) -> Unit,
    onDeleteDownloads: (Set<Int>, () -> Unit) -> Unit,
    onRetryTranslation: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ChapterFilter.All) }
    var selection by remember { mutableStateOf(emptySet<Int>()) }
    val focusManager = LocalFocusManager.current
    // Long-press starts manual selection; the Select toolbar button is a
    // separate range sheet for marking a from-to span read/unread.
    val selectionActive = selection.isNotEmpty()
    // Chapter the "Last read" button should land on; consumed once the list
    // (possibly un-filtered by clearing the search) contains it.
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    val lastRead = book.lastReadIndex
    // The rail only exists while the list actually has somewhere to go.
    val listScrollable by remember(listState) {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }

    val visible = remember(book, query, newestFirst, translatedTitles, filter) {
        val needle = query.trim()
        val matched = book.chapters.filter {
            filter.matches(it.isRead) && (
                needle.isBlank() ||
                    it.title.contains(needle, ignoreCase = true) ||
                    it.index.toString() == needle ||
                    translatedTitles[it.index]?.contains(needle, ignoreCase = true) == true
                )
        }
        matched.inReadingOrder(newestFirst)
    }

    // Truthful availability for destructive actions: only enabled when at
    // least one selected chapter actually has a translation / download.
    val selectedTranslated = if (selection.isEmpty()) {
        0
    } else {
        remember(book, selection, translationStates) {
            book.chapters.count { chapter ->
                chapter.index in selection && (
                    chapter.translated ||
                        translationStates[chapter.url ?: chapterKey(book.bookUrl, chapter.index)]
                            ?.status == ChapterTranslationStatus.Ready
                    )
            }
        }
    }
    val selectedDownloaded = if (selection.isEmpty()) {
        0
    } else {
        remember(book, selection, isVideoBook, videoDownloads) {
            book.chapters.count { chapter ->
                chapter.index in selection && if (isVideoBook) {
                    videoDownloads[chapter.index]?.state == VideoDownloadState.Completed
                } else {
                    chapter.downloaded
                }
            }
        }
    }

    LaunchedEffect(jumpTo, visible) {
        val target = jumpTo ?: return@LaunchedEffect
        val position = chapterJumpPosition(visible, target)
        if (position >= 0) {
            listState.animateScrollToItem(position)
            jumpTo = null
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    val searchBringIntoView = remember { BringIntoViewRequester() }
    val resultsPeek = with(LocalDensity.current) { 320.dp.toPx() }
    LaunchedEffect(searchFocused) {
        if (!searchFocused) return@LaunchedEffect
        repeat(7) {
            // Keep the field and a slice of the results above the keyboard,
            // not just the 40dp input row.
            searchBringIntoView.bringIntoView(Rect(0f, 0f, 1f, resultsPeek))
            delay(80)
        }
    }
    Column {
        MiruSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.book_ch_search_hint),
            onClear = { query = "" },
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(searchBringIntoView),
            focusRequester = searchFocusRequester,
            onFocusChanged = onSearchFocusChanged,
            plate = false,
        )
        Spacer(modifier = Modifier.height(MiruSpace.S))
        // Three compact plates. They are secondary tools, not page-level
        // actions; at large text they stack so no label is clipped.
        val largeText = isLargeText()
        val downloadButton: @Composable (Modifier) -> Unit = { modifier ->
            if (downloadRunning) {
                val fraction = downloadPercent?.coerceIn(0f, 1f)
                Row(
                    modifier = modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    MiruCompactButton(
                        label = if (downloadQueued) {
                            stringResource(R.string.book_ch_dl_queued)
                        } else if (fraction != null) {
                            stringResource(
                                R.string.dl_downloading_fmt,
                                (fraction * 100).roundToInt(),
                            )
                        } else {
                            stringResource(R.string.book_action_download)
                        },
                        progress = if (downloadQueued) null else fraction,
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                    MiruIconButton(
                        icon = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.dl_action_stop),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onStopDownload,
                        modifier = Modifier.size(MiruSize.Control),
                    )
                }
            } else {
                MiruCompactButton(
                    label = stringResource(
                        if (largeText) {
                            R.string.book_action_download
                        } else {
                            R.string.book_btn_download
                        },
                    ),
                    onClick = onDownloadNext100,
                    modifier = modifier,
                )
            }
        }
        val selectButton: @Composable (Modifier) -> Unit = { modifier ->
            MiruCompactButton(
                label = stringResource(R.string.book_btn_select),
                onClick = onSelectChapters,
                modifier = modifier,
            )
        }
        val lastReadButton: @Composable (Modifier) -> Unit = { modifier ->
            MiruCompactButton(
                label = if (lastRead > 0) {
                    stringResource(R.string.book_btn_last_read_fmt, lastRead)
                } else {
                    stringResource(R.string.book_btn_last_read)
                },
                enabled = lastRead > 0,
                onClick = {
                    focusManager.clearFocus()
                    query = ""
                    filter = ChapterFilter.All
                    jumpTo = lastRead
                },
                modifier = modifier,
            )
        }
        if (largeText) {
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                if (showDownload) downloadButton(Modifier.weight(1f))
                selectButton(Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(MiruSpace.S))
            lastReadButton(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                if (showDownload) downloadButton(Modifier.weight(1f))
                selectButton(Modifier.weight(1f))
                lastReadButton(Modifier.weight(1f))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
        ) {
            MiruFilterChip(
                selected = filter == ChapterFilter.All,
                onClick = {
                    focusManager.clearFocus()
                    filter = ChapterFilter.All
                },
                label = stringResource(R.string.book_filter_all),
                touchTarget = MiruSize.RowDense,
            )
            MiruFilterChip(
                selected = filter == ChapterFilter.Read,
                onClick = {
                    focusManager.clearFocus()
                    filter = ChapterFilter.Read
                },
                label = stringResource(R.string.book_filter_read),
                touchTarget = MiruSize.RowDense,
            )
            MiruFilterChip(
                selected = filter == ChapterFilter.Unread,
                onClick = {
                    focusManager.clearFocus()
                    filter = ChapterFilter.Unread
                },
                label = stringResource(R.string.book_filter_unread),
                touchTarget = MiruSize.RowDense,
            )
        }
        val scrollbarDragThumbTop = remember { mutableStateOf<Float?>(null) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .chapterScrollbarDrag(listState, listScrollable) {
                    scrollbarDragThumbTop.value = it
                },
        ) {
            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.book_ch_empty_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ChapterWindowHeight),
            ) {
                items(visible, key = { chapterRowKey(it.index) }) { chapter ->
                    val chapterUrl = chapter.url ?: chapterKey(book.bookUrl, chapter.index)
                    ChapterRow(
                        chapter = chapter,
                        title = translatedTitles[chapter.index] ?: chapter.title,
                        isLastRead = chapter.index == lastRead,
                        translationStatus = translationStates[chapterUrl]?.status,
                        videoDownload = videoDownloads[chapter.index],
                        isVideoBook = isVideoBook,
                        onVideoDownloadAction = { action ->
                            onVideoDownloadAction(chapter.index, action)
                        },
                        selectionActive = selectionActive,
                        selected = chapter.index in selection,
                        onClick = {
                            if (selectionActive) {
                                selection = toggleIn(selection, chapter.index)
                            } else {
                                onOpenChapter(chapter.index)
                            }
                        },
                        onLongClick = { selection = selection + chapter.index },
                        onRetryTranslation = { onRetryTranslation(chapter.index) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (listScrollable) {
                ChapterScrollbar(
                    state = listState,
                    dragThumbTop = scrollbarDragThumbTop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            }
        }
        if (selectionActive) {
            val allSelected = remember(visible, selection) {
                visible.isNotEmpty() && visible.all { it.index in selection }
            }
            Spacer(modifier = Modifier.height(MiruSpace.S))
            SelectionBar(
                selectionCount = selection.size,
                allSelected = allSelected,
                translationConfigured = translationConfigured,
                downloadAvailable = downloadAvailable,
                showDownload = showDownload,
                translatedCount = selectedTranslated,
                downloadedCount = selectedDownloaded,
                busy = bulkBusy,
                onSelectAll = { selection = selection + visible.map { it.index } },
                onTranslate = { onTranslate(selection) { selection = emptySet() } },
                onMarkRead = { read -> onMarkRead(selection, read) { selection = emptySet() } },
                onDeleteTranslations = {
                    onDeleteTranslations(selection) { selection = emptySet() }
                },
                onDownloadSelected = { onDownloadSelected(selection) },
                onDeleteDownloads = {
                    onDeleteDownloads(selection) { selection = emptySet() }
                },
                onClear = { selection = emptySet() },
                onClose = { selection = emptySet() },
            )
        }
    }
}
