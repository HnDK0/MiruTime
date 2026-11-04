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
import androidx.compose.foundation.layout.ContextualFlowRow
import androidx.compose.foundation.layout.ContextualFlowRowOverflow
import androidx.compose.foundation.layout.ContextualFlowRowOverflowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.R as UiKitR
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
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
import dev.mirutime.app.features.book.data.BookEpubExporter
import dev.mirutime.app.features.book.data.EpubExportResult
import dev.mirutime.app.features.book.data.epubFileName
import dev.mirutime.app.features.book.domain.BookActionKind
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.book.domain.BookReview
import dev.mirutime.app.features.book.domain.bookActionState
import dev.mirutime.app.features.book.domain.continueChapterTitle
import dev.mirutime.app.features.book.domain.bookChaptersCountKnown
import dev.mirutime.app.features.book.domain.inReadingOrder
import dev.mirutime.app.features.dashboard.ui.formatDurationLocalized
import dev.mirutime.app.features.downloads.data.DownloadEngine
import dev.mirutime.app.features.downloads.data.DownloadState
import dev.mirutime.app.features.downloads.data.EnqueueFailure
import dev.mirutime.app.features.downloads.data.EnqueueResult
import dev.mirutime.app.features.library.data.LibraryDataProvider
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.app.features.migration.ui.MigrationBookSheet
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
import dev.mirutime.app.features.video.data.VideoDownloadAction
import dev.mirutime.app.features.video.data.VideoDownloadItem
import dev.mirutime.app.features.video.data.VideoDownloadManager
import dev.mirutime.app.features.video.data.VideoDownloadState
import dev.mirutime.app.features.video.data.VideoSourceRepository
import dev.mirutime.app.features.video.data.videoDownloadActions
import dev.mirutime.app.features.video.domain.selectVideoStream
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
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
private val ChapterWindowHeight = 480.dp

// The provider keeps metadata in memory but the rendered page also holds the
// chapter list. A tiny bounded snapshot lets a reader return paint the page
// immediately; the provider/DB answer then revalidates it in place.
private object BookPageSnapshotCache {
    private const val MaxEntries = 4
    private val entries = LinkedHashMap<String, BookDetails>()

    fun get(bookUrl: String): BookDetails? = entries[bookUrl]

    fun put(bookUrl: String, book: BookDetails) {
        entries.remove(bookUrl)
        entries[bookUrl] = book
        while (entries.size > MaxEntries) {
            entries.remove(entries.keys.first())
        }
    }
}

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
    else -> context.getString(R.string.book_dl_nothing_new)
}

// Book page: header card (cover left, shelf chip, genre
// tags), 4-cell stat strip, continue/library/download actions, about, tools
// rows, source page, chapters (count + order toggle in one header) window.
@Composable
fun BookScreen(
    bookUrl: String,
    contentPadding: PaddingValues,
    readerGraph: ReaderGraph,
    provider: BookDataProvider,
    // Null in previews/tests: the EPUB row then reports "unavailable".
    exporter: BookEpubExporter? = null,
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
) {
    val seededBook = remember(bookUrl) { BookPageSnapshotCache.get(bookUrl) }
    var book by remember(bookUrl) { mutableStateOf(seededBook) }
    // The saved reader position is the same source the tap uses; the CTA
    // label must not fall back to "Start · Chapter 1" while it is non-zero.
    var savedContinue by remember(bookUrl) { mutableStateOf<Int?>(null) }
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
    SideEffect {
        book?.let { BookPageSnapshotCache.put(bookUrl, it) }
    }
    var shelves by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var shelvesLoading by remember { mutableStateOf(true) }
    var shelfSheetOpen by rememberSaveable { mutableStateOf(false) }
    var translatorSheetOpen by rememberSaveable { mutableStateOf(false) }
    var migrationSheetOpen by rememberSaveable { mutableStateOf(false) }
    var epubSheetOpen by rememberSaveable { mutableStateOf(false) }
    var selectSheetOpen by rememberSaveable { mutableStateOf(false) }
    var epubIncludeTranslation by rememberSaveable { mutableStateOf(true) }
    var epubExporting by remember { mutableStateOf(false) }
    // Bulk passes: the bar/plate shows a spinner and rejects a second tap
    // until the enqueue/translate call returns.
    var bulkBusy by remember { mutableStateOf<BulkAction?>(null) }
    var downloadAllBusy by remember { mutableStateOf(false) }
    // READING ORDER: follows the global CHAPTERS_SORT_ASCENDING setting and
    // writes a toggle back to it, so the next book opens with the same choice.
    var newestFirst by remember { mutableStateOf(AppSettings.newestFirst) }
    var downloadTasks by remember(bookUrl) { mutableStateOf<List<DownloadTaskEntity>>(emptyList()) }
    val isVideoBook = book?.contentType == ContentType.Video
    val videoDownloadFlow = remember(videoDownloads) {
        videoDownloads?.downloads ?: MutableStateFlow(emptyList<VideoDownloadItem>())
    }
    val videoDownloadItems by videoDownloadFlow.collectAsState()
    // Only this book's episodes; completed counts feed the summary badges.
    val videoDownloadsByIndex = remember(videoDownloadItems, bookUrl, isVideoBook) {
        if (isVideoBook) {
            videoDownloadItems
                .filter { it.bookUrl == bookUrl }
                .associateBy { it.chapterIndex }
        } else {
            emptyMap()
        }
    }
    val videoCompletedCount = videoDownloadsByIndex.values
        .count { it.state == VideoDownloadState.Completed }
    val videoPausedCount = videoDownloadsByIndex.values
        .count { it.state == VideoDownloadState.Paused }
    val videoPausedFlow = remember(videoDownloads) {
        videoDownloads?.pausedAll ?: MutableStateFlow(false)
    }
    val videoPausedAll by videoPausedFlow.collectAsState()
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

    val context = LocalContext.current
    val resources = LocalResources.current
    val ioScope = rememberCoroutineScope()
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
                    sourceId = PluginBookIndex.sourceFor(bookUrl),
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

    // One report shape for both the initial chapter load and an explicit
    // refresh, so every chapter failure names the same source/operation.
    fun chapterReport(error: PluginError): PluginErrorReport = buildPluginErrorReport(
        error = error,
        sourceId = PluginBookIndex.sourceFor(bookUrl),
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
                provider.setInLibrary(bookUrl, target)
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
                onBookOpened(bookUrl)
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
            onBookOpened(bookUrl)
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
    val readyTranslationCount = book?.chapters?.count { chapter ->
        val chapterUrl = chapter.url ?: chapterKey(bookUrl, chapter.index)
        translationStates[chapterUrl]?.status == ChapterTranslationStatus.Ready
    } ?: 0
    LaunchedEffect(readyTranslationCount) {
        if (readyTranslationCount <= seenReadyTranslations) return@LaunchedEffect
        seenReadyTranslations = readyTranslationCount
        delay(150)
        reloadChapters()
    }

    // Real queue observations: the chapter list is re-read once per second
    // while a task is active so progress comes from Room, not an animation.
    LaunchedEffect(bookUrl, downloadEngine, book?.inLibrary, isVideoBook) {
        if (isVideoBook) {
            downloadTasks = emptyList()
            return@LaunchedEffect
        }
        val engine = downloadEngine ?: return@LaunchedEffect
        val bookId = engine.bookId(bookUrl) ?: return@LaunchedEffect
        engine.observeBook(bookId).collect { tasks -> downloadTasks = tasks }
    }
    LaunchedEffect(bookUrl, downloadEngine) {
        if (downloadEngine == null) return@LaunchedEffect
        while (true) {
            delay(1_000)
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
        if (active && total > 0) videoCompletedCount.toFloat() / total else null
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

    val current = book
    // A failed destructive chapter action stays visible with a retry: a toast
    // cannot offer retry, so a swallowed failure made the button look dead.
    // Selection is cleared by onDone only on a real success.
    var chapterDeleteFailure by remember(bookUrl) {
        mutableStateOf<ChapterDeleteFailure?>(null)
    }

    fun enqueueVideoChapters(indexes: Set<Int>, onDone: (() -> Unit)? = null) {
        val manager = videoDownloads
        val repository = videoRepository
        val target = book
        when {
            manager == null || repository == null -> bookToast(
                context,
                resources.getString(R.string.book_download_unavailable),
            )
            target == null -> Unit
            !target.inLibrary -> bookToast(
                context,
                resources.getString(R.string.book_download_needs_library),
            )
            indexes.isEmpty() -> Unit
            else -> ioScope.launch {
                try {
                    var queued = 0
                    for (chapter in target.chapters.filter { it.index in indexes }) {
                        val streams = try {
                            provider.videoStreams(target.bookUrl, chapter.index)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        val stream = (streams as? Outcome.Ok)?.value
                            ?.let { selectVideoStream(it, null) }
                            ?: continue
                        val headers = repository.headersFor(stream)
                        if (manager.enqueue(target.bookUrl, chapter.index, stream, headers)) {
                            queued++
                        }
                    }
                    val message = if (queued == 0 && videoPausedAll) {
                        resources.getString(R.string.book_dl_video_paused)
                    } else {
                        resources.getString(R.string.book_dl_queued_fmt, queued)
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
                VideoDownloadAction.Pause -> manager.pause(item.bookUrl, item.chapterUrl)
                VideoDownloadAction.Resume -> manager.resume(item.bookUrl, item.chapterUrl)
                VideoDownloadAction.Cancel -> manager.cancel(item.bookUrl, item.chapterUrl)
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
    LaunchedEffect(current, metadataConfig, chaptersLoading) {
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
            ),
        )
    }
    val metadataEntry = metadata[bookUrl]?.takeIf { it.config == metadataConfig }
    LazyColumn(
        state = if (current == null) loadingListState else pageListState,
        modifier = Modifier.fillMaxSize(),
        // ONE gap between every block of the page (MiruSize.Gap = 16dp):
        // blocks never carry their own top/bottom margins.
        verticalArrangement = Arrangement.spacedBy(MiruSize.Gap),
        contentPadding = PaddingValues(
            start = MiruSize.ScreenPad,
            end = MiruSize.ScreenPad,
            top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
            bottom = contentPadding.calculateBottomPadding() + BottomClearance,
        ),
    ) {
        if (current == null) {
            item {
                val failure = loadFailure
                if (failure != null) {
                    val report = buildPluginErrorReport(
                        error = failure,
                        sourceId = PluginBookIndex.sourceFor(bookUrl),
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
            item { StatStrip(book = current, chaptersLoading = chaptersLoading) }
            item {
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
                    translatedTitles = metadataEntry?.chapterTitles.orEmpty(),
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
                    downloadProgress = downloadProgress,
                    downloadBusy = downloadAllBusy,
                    membershipPending = membershipPending,
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
                    // Explicit target from the optimistic state; the intent
                    // channel coalesces rapid taps to the newest one.
                    onToggleLibrary = { requestMembership(!current.inLibrary) },
                    onDownloadAll = downloadAll@{
                        if (downloadAllBusy) return@downloadAll
                        if (isVideoBook) {
                            if (!current.inLibrary) {
                                bookToast(
                                    context,
                                    resources.getString(R.string.book_download_needs_library),
                                )
                            } else if (videoDownloads == null || videoRepository == null) {
                                bookToast(
                                    context,
                                    resources.getString(R.string.book_download_unavailable),
                                )
                            } else {
                                downloadAllBusy = true
                                enqueueVideoChapters(
                                    current.chapters.map { it.index }.toSet(),
                                ) { downloadAllBusy = false }
                            }
                            return@downloadAll
                        }
                        val engine = downloadEngine
                        if (!current.inLibrary) {
                            // Downloads live in the library: the
                            // button refuses with a hint until the book is in.
                            bookToast(
                                context,
                                resources.getString(R.string.book_download_needs_library),
                            )
                        } else if (engine == null) {
                            bookToast(context, resources.getString(R.string.book_download_unavailable))
                        } else {
                            ioScope.launch {
                                downloadAllBusy = true
                                try {
                                    val result = engine.enqueueNow(
                                        current.bookUrl,
                                        null,
                                        translationConfigured,
                                    )
                                    MiruLog.event(
                                        "downloads",
                                        "enqueue",
                                        "book" to MiruLog.idHash(current.bookUrl),
                                        "queued" to result.queued,
                                        "skipped" to result.skipped,
                                        "unknown" to result.unknown,
                                        "translate" to result.translationRequested,
                                    )
                                    bookToast(context, enqueueMessage(context, result))
                                    reloadChapters()
                                } finally {
                                    downloadAllBusy = false
                                }
                            }
                        }
                    },
                )
            }
            if (downloadQueueState != null && downloadBookId != null) {
                item {
                    DownloadQueueNotice(
                        state = downloadQueueState,
                        error = downloadQueueError,
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
            item { AboutSection(description = metadataEntry?.description ?: current.description) }
            item {
                SourcePageSection(
                    book = current,
                    onOpenWebView = onOpenWebView,
                )
            }
            item {
                ToolsSection(
                    onTranslator = { translatorSheetOpen = true },
                    onEpub = { if (!epubExporting) epubSheetOpen = true },
                    epubEnabled = !epubExporting,
                    epubBusy = epubExporting,
                    // Per-novel rules open the real editor scoped to this
                    // book (Settings -> Regex); the global set lives in
                    // Settings -> Regex without a novel.
                    onRegex = { onOpenRegex(bookUrl) },
                    onMigrate = { migrationSheetOpen = true },
                    migrateEnabled = migration != null && current.inLibrary &&
                        !current.bookUrl.startsWith("local"),
                )
            }
            chaptersFailure?.let { report ->
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
            item {
                if (chaptersLoading && current.chapters.isEmpty()) {
                    ChaptersSkeleton()
                } else if (showChaptersSection(current.chapters, chaptersFailure)) {
                    ChaptersSection(
                        book = current,
                        newestFirst = newestFirst,
                        refreshing = chaptersRefreshing,
                        downloadedOverride = if (isVideoBook) videoCompletedCount else null,
                        onRefresh = ::refreshChapters,
                        onToggleOrder = {
                            newestFirst = !newestFirst
                            AppSettings.newestFirst = newestFirst
                        },
                    ) {
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
                            translatedTitles = metadataEntry?.chapterTitles.orEmpty(),
                            newestFirst = newestFirst,
                            bulkBusy = bulkBusy,
                            isVideoBook = isVideoBook,
                            videoDownloads = videoDownloadsByIndex,
                            onVideoDownloadAction = ::applyVideoDownloadAction,
                            onOpenChapter = { index ->
                                if (current.contentType == ContentType.Video) {
                                    onOpenVideo(current.bookUrl, index)
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
                                    enqueueVideoChapters(
                                        current.chapters.map { it.index }.toSet(),
                                    )
                                    return@ChapterBrowser
                                }
                                val engine = downloadEngine
                                if (current.inLibrary && engine != null) {
                                    ioScope.launch {
                                        val result = engine.enqueueNext100Now(
                                            current.bookUrl,
                                            translationConfigured,
                                        )
                                        bookToast(context, enqueueMessage(context, result))
                                        reloadChapters()
                                    }
                                } else {
                                    bookToast(
                                        context,
                                        resources.getString(
                                            if (!current.inLibrary) {
                                                R.string.book_download_needs_library
                                            } else {
                                                R.string.book_download_unavailable
                                            },
                                        ),
                                    )
                                }
                            },
                            onSelectChapters = { selectSheetOpen = true },
                            downloadAvailable = if (isVideoBook) {
                                videoDownloads != null && videoRepository != null &&
                                    current.inLibrary
                            } else {
                                downloadEngine != null && current.inLibrary
                            },
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
                                } else if (!current.inLibrary) {
                                    bookToast(
                                        context,
                                        resources.getString(R.string.book_download_needs_library),
                                    )
                                } else if (engine == null) {
                                    bookToast(
                                        context,
                                        resources.getString(R.string.book_download_unavailable),
                                    )
                                } else {
                                    bulkBusy = BulkAction.Download
                                    ioScope.launch {
                                        try {
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
                    }
                }
            }
            // Very bottom of the page, after the chapters: READER NOTES.
            // Fixture reviews; empty state on books whose plugin offered none.
            item {
                ReaderNotesSection(
                    reviews = current.reviews,
                    onTranslate = {
                        bookToast(
                            context,
                            resources.getString(R.string.book_review_translate_stub),
                        )
                    },
                )
            }
        }
    }

    if (current != null) {
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
                            resources.getString(R.string.book_moved_fmt, shelf.label),
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
                                    !current.inLibrary -> bookToast(
                                        context,
                                        resources.getString(R.string.book_download_needs_library),
                                    )
                                    engine == null -> bookToast(
                                        context,
                                        resources.getString(R.string.book_download_unavailable),
                                    )
                                    else -> {
                                        bulkBusy = BulkAction.Download
                                        ioScope.launch {
                                            try {
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
        verticalArrangement = Arrangement.spacedBy(MiruSize.Gap),
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
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 276.dp, corner = MiruRadius.Card)
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 66.dp, corner = MiruRadius.Card)
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 104.dp, corner = MiruRadius.Card)
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 160.dp, corner = MiruRadius.Card)
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
        SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 56.dp, corner = MiruRadius.Card)
        repeat(4) {
            SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 44.dp, corner = MiruRadius.Card)
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
) {
    val extra = LocalMiruExtraColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val resources = LocalResources.current
    MiruCard {
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.L)) {
            // Detail cover: 120x180 (2:3), the page's hero — larger than any
            // library thumb, still leaves ~190dp of text width on a 360dp phone.
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Top,
            ) {
                Column {
                    // SOURCE · plugin — caps eyebrow.
                    Text(
                        text = buildString {
                            append(book.source.uppercase(Locale.ROOT))
                            append(" · ")
                            append(stringResource(R.string.book_plugin).uppercase(Locale.ROOT))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(MiruSpace.Xs))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickable(
                            role = Role.Button,
                            // The global search screen already exists; the
                            // title opens it without a query.
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
                    Spacer(modifier = Modifier.height(MiruSpace.Xs))
                    val author = book.author.trim()
                    // Plugins without getBookAuthor hand us a blank or
                    // "Unknown" name; skip the whole line rather than print
                    // "by Unknown".
                    if (author.isNotEmpty() && !author.equals("Unknown", ignoreCase = true)) {
                        Text(
                            text = stringResource(R.string.book_by_author_fmt, author),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    book.originLanguage?.takeIf { it.isNotBlank() }?.let { language ->
                        Text(
                            text = stringResource(R.string.book_origin_fmt, language),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // One dense metadata band under the hairline: the current shelf is
        // the first chip of the genre flow, so shelf and genres share rows
        // instead of stacking three full-width stripes. The shelf chip keeps
        // its chevron and opens the same move sheet; genres keep the two-line
        // collapse with the "+N / Less" overflow.
        Spacer(modifier = Modifier.height(MiruSpace.M))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(MiruSpace.S))
        ShelfGenreFlow(
            // The raw shelf id is a database value, not a label: keep the chip
            // neutral until the real shelf list answers.
            shelf = if (shelvesLoading) {
                stringResource(R.string.book_stat_unknown)
            } else {
                shelfLabel(book.shelfId, shelves)
            },
            genres = book.genres,
            onShelfClick = onShelfClick,
        )
    }
}

// Compact "move to shelf" chip: folder glyph, current shelf, chevron — a
// 32dp visual pill centered in a 48dp touch slot, so it stays level with the
// genre chips while keeping the minimum tap target. The shelf name ellipsizes
// so a long label can never push the genres off the row.
@Composable
private fun ShelfChip(shelf: String, onClick: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .heightIn(min = MiruSize.Touch)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.book_move_shelf),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(extra.accent.copy(alpha = 0.10f))
                .border(1.dp, extra.accent.copy(alpha = 0.45f), shape)
                .padding(horizontal = MiruSpace.S),
        ) {
            Icon(
                imageVector = Icons.Filled.DriveFileMove,
                contentDescription = null,
                tint = extra.accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = shelf,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = extra.muted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

internal fun shelfLabel(shelfId: String, shelves: List<Shelf>): String =
    shelves.firstOrNull { it.id == shelfId }?.label ?: shelfId

// Shelf + genre pool: the current shelf is item 0, so it flows inline with
// the quiet tonal genre tags (labels, not filters — nothing to tap but the
// shelf chip and the "+N / Less" tag). Two rows max when collapsed.
// ContextualFlowRow (not FlowRow): the overflow indicator reads
// shownItemCount during composition, and only the contextual variant
// initializes it in that phase (the plain FlowRow one throws).
private const val CollapsedGenreLines = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfGenreFlow(
    shelf: String,
    genres: List<String>,
    onShelfClick: () -> Unit,
) {
    var maxLines by remember { mutableStateOf(CollapsedGenreLines) }
    val overflowTag: @Composable ContextualFlowRowOverflowScope.() -> Unit = {
        // shownItemCount includes the shelf chip, so the hidden genre count
        // is total items minus the shown ones.
        val hidden = genres.size + 1 - shownItemCount
        if (hidden > 0) {
            GenreTag(
                label = stringResource(R.string.book_genres_more_fmt, hidden),
                emphasized = true,
                onClick = { maxLines = Int.MAX_VALUE },
            )
        } else {
            GenreTag(
                label = stringResource(R.string.book_genres_less),
                emphasized = true,
                onClick = { maxLines = CollapsedGenreLines },
            )
        }
    }
    ContextualFlowRow(
        itemCount = genres.size + 1,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxLines = maxLines,
        overflow = ContextualFlowRowOverflow.expandOrCollapseIndicator(
            expandIndicator = overflowTag,
            collapseIndicator = overflowTag,
            minRowsToShowCollapse = CollapsedGenreLines + 1,
        ),
    ) { index ->
        if (index == 0) {
            ShelfChip(shelf = shelf, onClick = onShelfClick)
        } else {
            GenreTag(label = genres[index - 1])
        }
    }
}

// 24dp tonal tag in a 32dp row slot (the clickable overflow tag gets the
// whole slot as its hit box). Plain tags: onSurface ink on a 7% onSurface
// wash — readable on the plate in every theme. The overflow tag: accent ink
// on a hairline accent outline, so it reads as the one interactive tag.
@Composable
private fun GenreTag(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(8.dp)
    val ink = if (emphasized) extra.accent else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .let { base ->
                if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(24.dp)
                .clip(shape)
                .then(
                    if (emphasized) {
                        Modifier.border(1.dp, extra.accent.copy(alpha = 0.55f), shape)
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    },
                )
                .padding(horizontal = MiruSpace.M),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = ink,
                maxLines = 1,
            )
        }
    }
}

// Four-compartment stat strip: rating; read/total + percent; status +
// update time; reading time with the TTS share. The rating cell drops out
// entirely when the plugin gives us no score.
@Composable
private fun StatStrip(book: BookDetails, chaptersLoading: Boolean) {
    val readLabel = formatDurationLocalized(book.readMinutes * 60_000L)
    val ttsLabel = formatDurationLocalized(book.ttsMinutes * 60_000L)
    val chaptersKnown = bookChaptersCountKnown(chaptersLoading, book.totalChapters)
    BookStatPanel(
        cells = buildList {
            if (book.rating > 0f) {
                add(
                    BookStatCell(
                        value = formatRating(book.rating),
                        label = stringResource(R.string.book_stat_rating),
                        icon = Icons.Filled.Star,
                    ),
                )
            }
            add(
                BookStatCell(
                    value = if (chaptersKnown) {
                        stringResource(
                            R.string.card_ratio_fmt,
                            book.readCount,
                            book.totalChapters,
                        )
                    } else {
                        stringResource(R.string.book_stat_unknown)
                    },
                    label = if (chaptersKnown) {
                        stringResource(
                            R.string.book_stat_read_pct_fmt,
                            (book.progressFraction * 100).roundToInt(),
                        )
                    } else {
                        stringResource(R.string.book_stat_none)
                    },
                ),
            )
            add(
                BookStatCell(
                    value = stringResource(
                        if (book.ongoing) R.string.book_stat_ongoing else R.string.book_stat_completed,
                    ),
                    label = book.updatedLabel,
                ),
            )
            add(
                BookStatCell(
                    value = formatDurationLocalized((book.readMinutes + book.ttsMinutes) * 60_000L),
                    label = if (book.ttsMinutes > 0) {
                        stringResource(R.string.book_stat_time_labels_fmt, ttsLabel)
                    } else {
                        stringResource(R.string.book_stat_time)
                    },
                ),
            )
        },
    )
}

// Same plate as the shared StatPanel (equal cells, hairline dividers,
// titleMedium values) with one addition: an optional leading glyph, needed
// only here for the rating star. The shared cell is text-only.
private data class BookStatCell(
    val value: String,
    val label: String,
    val icon: ImageVector? = null,
)

@Composable
private fun BookStatPanel(
    cells: List<BookStatCell>,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = MiruSpace.M),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) {
                    VerticalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = MiruSpace.Xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                    ) {
                        cell.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = extra.accent,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = cell.value,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Text(
                        text = cell.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
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
    downloadProgress: Float?,
    downloadBusy: Boolean,
    membershipPending: Boolean,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
    onDownloadAll: () -> Unit,
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
    }
    Column {
        Button(
            onClick = onContinue,
            enabled = action.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(MiruSize.ButtonPrimary),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            if (action.kind == BookActionKind.LoadingChapters) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = label },
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
            PlateActionButton(
                icon = Icons.Filled.Download,
                label = when {
                    downloadBusy -> stringResource(R.string.loading_adding_to_queue)
                    downloadProgress != null -> stringResource(
                        R.string.book_downloading_progress_fmt,
                        (downloadProgress * 100).roundToInt(),
                    )
                    else -> stringResource(R.string.book_cd_download_all)
                },
                muted = !book.inLibrary,
                progress = if (downloadBusy) null else downloadProgress,
                pending = downloadBusy,
                pendingDescription = stringResource(R.string.loading_adding_to_queue),
                enabled = !downloadBusy,
                onClick = onDownloadAll,
                modifier = Modifier.weight(1f),
            )
            PlateActionButton(
                icon = if (book.inLibrary) {
                    Icons.Filled.BookmarkAdded
                } else {
                    Icons.Filled.BookmarkBorder
                },
                label = stringResource(
                    if (book.inLibrary) R.string.book_cd_in_library else R.string.book_cd_add_library,
                ),
                active = book.inLibrary,
                pending = membershipPending,
                onClick = onToggleLibrary,
                modifier = Modifier.weight(1f),
            )
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
            .clip(RoundedCornerShape(8.dp))
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
        SelectionIconAction(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.book_delete_error_dismiss),
            color = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onDismiss,
        )
    }
}

// Compact secondary action: 36dp visual rectangle on a 44dp touch slot. Card
// plate + hairline keeps the button language; muted ink = "not available
// yet" (downloads outside the library); active = accent for the saved state;
// a progress ring replaces the icon while a real queue runs.
@Composable
private fun PlateActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    muted: Boolean = false,
    progress: Float? = null,
    // Indeterminate "membership is being written" indicator; the label and
    // the rest of the page stay interactive.
    pending: Boolean = false,
    pendingDescription: String = stringResource(R.string.book_library_pending),
    enabled: Boolean = true,
    iconTint: Color? = null,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(8.dp)
    val ink = when {
        muted -> extra.muted
        active -> extra.accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .background(miruCardSurface())
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress },
                    strokeWidth = 2.dp,
                    color = extra.accent,
                    modifier = Modifier.size(16.dp),
                )
            } else if (pending) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = extra.accent,
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = pendingDescription },
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: ink,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ABOUT THIS STORY: ONE card — title + description line, hairline, then the
// synopsis (three lines max, inline "Read more" at the end of the third line,
// toggling in place). Same construction as the genre block of the header.
@Composable
private fun AboutSection(description: String) {
    MiruSectionCard(
        title = stringResource(R.string.book_section_about),
        description = stringResource(R.string.book_section_about_desc),
    ) {
        ExpandableBody(text = description, collapsedLines = 3)
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
private fun ExpandableBody(text: String, collapsedLines: Int) {
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
    onTranslator: () -> Unit,
    onEpub: () -> Unit,
    epubEnabled: Boolean,
    epubBusy: Boolean,
    onRegex: () -> Unit,
    onMigrate: () -> Unit,
    migrateEnabled: Boolean,
) {
    MiruSectionCard(
        title = stringResource(R.string.book_section_tools),
        description = stringResource(R.string.book_section_tools_desc),
        contentPadding = PaddingValues(vertical = MiruSpace.Xs),
    ) {
        ToolRow(
            icon = Icons.Filled.Translate,
            title = stringResource(R.string.tool_translator),
            subtitle = stringResource(R.string.tool_translator_desc),
            onClick = onTranslator,
        )
        ToolDivider()
        ToolRow(
            icon = Icons.Filled.Download,
            title = stringResource(R.string.tool_epub),
            subtitle = if (epubBusy) {
                stringResource(R.string.loading_exporting)
            } else {
                stringResource(R.string.tool_epub_desc)
            },
            onClick = onEpub,
            enabled = epubEnabled,
            busy = epubBusy,
        )
        ToolDivider()
        ToolRow(
            icon = Icons.Filled.Rule,
            title = stringResource(R.string.tool_regex),
            subtitle = stringResource(R.string.tool_regex_desc),
            onClick = onRegex,
        )
        ToolDivider()
        ToolRow(
            icon = Icons.Filled.DriveFileMove,
            title = stringResource(R.string.tool_migrate),
            subtitle = stringResource(
                if (migrateEnabled) {
                    R.string.tool_migrate_desc
                } else {
                    R.string.migration_needs_library
                },
            ),
            onClick = onMigrate,
            enabled = migrateEnabled,
        )
    }
}

@Composable
private fun ToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) extra.accent else extra.muted,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            val exportingLabel = stringResource(R.string.loading_exporting)
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = extra.accent,
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = exportingLabel },
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = extra.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ToolDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiruSpace.L)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    )
}

// Source-page block: the same one-card construction and the same row
// language as the tools — the globe row opens the source page in the in-app
// WebView; when the plugin advertises auth, a login row follows. A failed
// open is silently ignored (pure UI, no Toast Leak).
@Composable
private fun SourcePageSection(
    book: BookDetails,
    onOpenWebView: (url: String, title: String) -> Unit,
) {
    val host = remember(book.sourceUrl) {
        runCatching { Uri.parse(book.sourceUrl).host }.getOrNull().orEmpty().ifBlank { book.source }
    }
    MiruSectionCard(
        title = stringResource(R.string.book_section_page),
        description = stringResource(R.string.book_section_page_desc),
        contentPadding = PaddingValues(vertical = MiruSpace.Xs),
    ) {
        ToolRow(
            icon = Icons.Filled.Public,
            title = stringResource(R.string.book_open_page),
            subtitle = host,
            onClick = {
                if (book.sourceUrl.isNotBlank()) onOpenWebView(book.sourceUrl, book.source)
            },
        )
        if (book.authUrl != null) {
            ToolDivider()
            ToolRow(
                icon = Icons.Filled.Login,
                title = stringResource(R.string.book_open_login),
                subtitle = host,
                onClick = {
                    book.authUrl?.let { onOpenWebView(it, book.source) }
                },
            )
        }
    }
}

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
    onRefresh: () -> Unit,
    onToggleOrder: () -> Unit,
    downloadedOverride: Int? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val downloaded = downloadedOverride
        ?: remember(book) { book.chapters.count { it.downloaded } }
    val refreshLabel = stringResource(R.string.book_chapters_refreshing)
    MiruSectionCard(
        title = stringResource(R.string.book_chapters_header_fmt, book.totalChapters),
        description = stringResource(
            R.string.book_chapters_summary_fmt,
            book.readCount,
            downloaded,
        ),
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .size(16.dp)
                            .semantics {
                                contentDescription = refreshLabel
                            },
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
        },
        contentPadding = PaddingValues(MiruSpace.M),
        content = content,
    )
}

@Composable
private fun ChapterOrderToggle(newestFirst: Boolean, onClick: () -> Unit) {
    val accent = LocalMiruExtraColors.current.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(MiruRadius.Cover))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.book_order_cd),
                onClick = onClick,
            )
            .defaultMinSize(minHeight = MiruSize.Touch)
            .padding(horizontal = MiruSpace.M),
    ) {
        Icon(
            imageVector = if (newestFirst) {
                Icons.Filled.ArrowDownward
            } else {
                Icons.Filled.ArrowUpward
            },
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(
                if (newestFirst) R.string.book_order_newest else R.string.book_order_oldest,
            ),
            style = MaterialTheme.typography.labelLarge,
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
    translatedTitles: Map<Int, String>,
    newestFirst: Boolean,
    bulkBusy: BulkAction?,
    isVideoBook: Boolean,
    videoDownloads: Map<Int, VideoDownloadItem>,
    onVideoDownloadAction: (Int, VideoDownloadAction) -> Unit,
    translationStates: Map<String, ChapterTranslation>,
    translationConfigured: Boolean,
    downloadAvailable: Boolean,
    onOpenChapter: (Int) -> Unit,
    onDownloadNext100: () -> Unit,
    onSelectChapters: () -> Unit,
    onTranslate: (Set<Int>, () -> Unit) -> Unit,
    onMarkRead: (Set<Int>, Boolean, () -> Unit) -> Unit,
    onDeleteTranslations: (Set<Int>, () -> Unit) -> Unit,
    onDownloadSelected: (Set<Int>) -> Unit,
    onDeleteDownloads: (Set<Int>, () -> Unit) -> Unit,
    onRetryTranslation: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(emptySet<Int>()) }
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

    val visible = remember(book, query, newestFirst, translatedTitles) {
        val matched = book.chapters.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                translatedTitles[it.index]?.contains(query, ignoreCase = true) == true
        }
        matched.inReadingOrder(newestFirst)
    }

    // Truthful availability for destructive actions: only enabled when at
    // least one selected chapter actually has a translation / download.
    val selectedTranslated = remember(book, selection, translationStates) {
        book.chapters.count { chapter ->
            chapter.index in selection && (
                chapter.translated ||
                    translationStates[chapter.url ?: chapterKey(book.bookUrl, chapter.index)]
                        ?.status == ChapterTranslationStatus.Ready
                )
        }
    }
    val selectedDownloaded = remember(book, selection, isVideoBook, videoDownloads) {
        book.chapters.count { chapter ->
            chapter.index in selection && if (isVideoBook) {
                videoDownloads[chapter.index]?.state == VideoDownloadState.Completed
            } else {
                chapter.downloaded
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

    Column {
        // Search chapters by title. Classic field look; on a photo the
        // card plate already carries the ink, no extra frost inside a card.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    text = stringResource(R.string.book_ch_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.book_cd_ch_search),
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MiruSpace.S))
        // Three compact rectangles, one row. They are secondary tools, not
        // page-level actions, so they stay 36dp tall (44dp touch slot).
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
            BrowserButton(
                label = stringResource(R.string.book_btn_download),
                onClick = onDownloadNext100,
                modifier = Modifier.weight(1f),
            )
            BrowserButton(
                label = stringResource(R.string.book_btn_select),
                onClick = onSelectChapters,
                modifier = Modifier.weight(1f),
            )
            BrowserButton(
                label = stringResource(R.string.book_btn_last_read),
                enabled = lastRead > 0,
                onClick = {
                    query = ""
                    jumpTo = lastRead
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (selectionActive) {
            Spacer(modifier = Modifier.height(MiruSpace.S))
            SelectionBar(
                selectionCount = selection.size,
                allSelected = visible.isNotEmpty() && visible.all { it.index in selection },
                translationConfigured = translationConfigured,
                downloadAvailable = downloadAvailable,
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
        Spacer(modifier = Modifier.height(MiruSpace.S))
        val scrollbarDragThumbTop = remember { mutableStateOf<Float?>(null) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .chapterScrollbarDrag(listState, listScrollable) {
                    scrollbarDragThumbTop.value = it
                },
        ) {
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
}

// Compact secondary button: 36dp visual rectangle on a 44dp touch slot. The
// smaller radius (8dp) and padding keep the chapter toolbar quiet; the plate
// surface + hairline still matches the page's button language.
@Composable
private fun BrowserButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(8.dp)
    val ink = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        extra.muted.copy(alpha = 0.6f)
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .background(miruCardSurface())
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(horizontal = MiruSpace.S),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Chapter jump position follows the visible reading order, not the source
// chapter index: the list may be filtered and/or newest-first.
internal fun chapterJumpPosition(visible: List<BookChapter>, chapterIndex: Int): Int =
    visible.indexOfFirst { it.index == chapterIndex }

// Toggle helper: add/remove one index (pure, no composable state).
private fun toggleIn(set: Set<Int>, index: Int): Set<Int> =
    if (index in set) set - index else set + index

// A bulk pass that is still running. The bar is disabled and shows the
// matching label until the enqueue/translate call returns.
private enum class BulkAction(val labelRes: Int) {
    Translate(R.string.loading_translating),
    Download(R.string.loading_adding_to_queue),
}

// Selection bar: count + select all/clear, then every bulk action as a
// labeled compact rectangle (no icon-only row). Delete translations sits
// right after Translate; both deletes are disabled when nothing selected
// actually owns translations/downloads. Close exits selection mode.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    selectionCount: Int,
    allSelected: Boolean,
    translationConfigured: Boolean,
    downloadAvailable: Boolean,
    translatedCount: Int,
    downloadedCount: Int,
    busy: BulkAction?,
    onSelectAll: () -> Unit,
    onTranslate: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onDeleteTranslations: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDeleteDownloads: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiruSpace.M))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionIconAction(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.lib_selection_close),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onClose,
            )
            Text(
                text = if (selectionCount == 0) {
                    stringResource(R.string.book_select_chapters)
                } else {
                    pluralStringResource(
                        R.plurals.plural_selected_chapters,
                        selectionCount,
                        selectionCount,
                    )
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (busy != null) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(busy.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onSelectAll, enabled = busy == null && !allSelected) {
                Text(text = stringResource(R.string.lib_select_all))
            }
            TextButton(onClick = onClear, enabled = busy == null && selectionCount > 0) {
                Text(text = stringResource(R.string.book_sel_clear))
            }
        }
        // Compact action strip: never wrap. On narrow screens the user
        // scrolls horizontally instead of growing the bar vertically.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = MiruSpace.S, end = MiruSpace.S, bottom = MiruSpace.S),
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            SelectionAction(
                icon = Icons.Filled.Translate,
                label = stringResource(R.string.book_sel_action_translate),
                color = if (translationConfigured) extra.accent else extra.muted,
                enabled = busy == null && selectionCount > 0,
                onClick = onTranslate,
            )
            SelectionAction(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.book_sel_action_delete_translations),
                color = MaterialTheme.colorScheme.error,
                enabled = busy == null && selectionCount > 0 && translatedCount > 0,
                onClick = onDeleteTranslations,
            )
            SelectionAction(
                icon = Icons.Filled.Download,
                label = stringResource(R.string.book_sel_action_download),
                color = if (downloadAvailable) extra.accent else extra.muted,
                enabled = busy == null && selectionCount > 0,
                onClick = onDownloadSelected,
            )
            SelectionAction(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.book_sel_action_delete_downloads),
                color = MaterialTheme.colorScheme.error,
                enabled = busy == null && selectionCount > 0 && downloadedCount > 0,
                onClick = onDeleteDownloads,
            )
            SelectionAction(
                icon = Icons.Filled.Check,
                label = stringResource(R.string.book_sel_action_mark_read),
                color = MaterialTheme.colorScheme.primary,
                enabled = busy == null && selectionCount > 0,
                onClick = { onMarkRead(true) },
            )
            SelectionAction(
                icon = Icons.Filled.RemoveDone,
                label = stringResource(R.string.book_sel_action_mark_unread),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                enabled = busy == null && selectionCount > 0,
                onClick = { onMarkRead(false) },
            )
        }
    }
}

// Labeled action chip: 32dp visual rectangle on a 44dp touch slot, icon +
// text so the action never relies on a glyph alone.
@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(8.dp)
    val ink = if (enabled) color else extra.muted.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(miruCardSurface())
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = ink,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SelectionIconAction(
    icon: ImageVector,
    contentDescription: String,
    color: Color,
    onClick: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
) {
    Box(
        modifier = Modifier
            .size(MiruSize.Touch)
            .clip(RoundedCornerShape(MiruRadius.Cover))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = contentAlignment,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
    }
}

// Chapter row: number + title, at most two trailing glyphs — translated
// (accent) and downloaded. Read state is carried by a dimmed title, never by
// a third icon; a pending/loading translation shows a spinner or retry in the
// translated slot but leaves no permanent mark. Long-press starts selection,
// like in the library; a tap in selection mode toggles the row.
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ChapterRow(
    chapter: BookChapter,
    title: String,
    isLastRead: Boolean,
    translationStatus: ChapterTranslationStatus?,
    videoDownload: VideoDownloadItem?,
    isVideoBook: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRetryTranslation: () -> Unit,
    onVideoDownloadAction: (VideoDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val translationLoading = stringResource(R.string.book_ch_translation_loading)
    // 0 means the body/pages are not stored locally; show no size at all
    // rather than a fake estimate or "0 B".
    val sizeLabel = remember(chapter.sizeBytes) {
        chapter.sizeBytes.takeIf { it > 0L }?.let(::formatChapterSize)
    }
    // Video rows take their state from media3; text rows keep the existing
    // DownloadEngine queue state. The two never mix for one book.
    val videoState = videoDownload?.state
    val videoProgress = videoDownload?.progress ?: 0f
    val downloaded = if (isVideoBook) {
        videoState == VideoDownloadState.Completed
    } else {
        chapter.downloaded
    }
    val queuedState = if (isVideoBook) {
        when (videoState) {
            VideoDownloadState.Queued -> DownloadState.QUEUED
            VideoDownloadState.Downloading -> DownloadState.RUNNING
            VideoDownloadState.Paused -> DownloadState.PAUSED
            VideoDownloadState.Failed -> DownloadState.FAILED
            VideoDownloadState.Completed, null -> null
        }
    } else {
        DownloadState.from(chapter.downloadState)
    }
    val progress = if (isVideoBook) videoProgress else chapter.downloadProgress
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        modifier = modifier
            .heightIn(min = MiruSize.Touch)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    isLastRead -> extra.accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .clip(RoundedCornerShape(8.dp))
            // Trailing Pause/Resume/Cancel/retry/status glyphs end at the same
            // 8dp gutter as the scrollbar rail. The rail's 48dp lane reaches
            // into the row but only claims the visible 8dp track on touch-down,
            // so a tap on any icon still belongs to that icon. No vertical
            // padding is added, so the rail and rows share top/bottom edges.
            .padding(start = MiruSpace.S, end = ChapterRowEndGutter + MiruSpace.Xxs),
    ) {
        if (selectionActive) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    extra.muted
                },
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = chapter.index.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                modifier = Modifier.width(40.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                // Read chapters recede by ink and carry an explicit badge; a
                // dimmed title alone is too easy to miss.
                color = if (chapter.isRead) {
                    extra.muted
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (queuedState != null && !downloaded) {
                Text(
                    text = when (queuedState) {
                        DownloadState.QUEUED -> stringResource(R.string.book_ch_dl_queued)
                        DownloadState.RUNNING -> stringResource(
                            R.string.book_ch_dl_running_fmt,
                            (progress * 100).roundToInt(),
                        )
                        DownloadState.PAUSED -> stringResource(
                            R.string.book_ch_dl_paused_fmt,
                            (progress * 100).roundToInt(),
                        )
                        DownloadState.FAILED -> stringResource(R.string.book_ch_dl_failed)
                        DownloadState.NEEDS_SOURCE_ACTION ->
                            stringResource(R.string.book_ch_dl_source_action)
                        DownloadState.WAITING_NETWORK ->
                            stringResource(R.string.book_ch_dl_waiting_network)
                        else -> null
                    } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (queuedState == DownloadState.FAILED ||
                        queuedState == DownloadState.NEEDS_SOURCE_ACTION
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        extra.muted
                    },
                    maxLines = 1,
                )
            }
            if (isLastRead || chapter.isLast || chapter.isRead || sizeLabel != null) {
                // FlowRow: RU badges at 200% font wrap instead of clipping.
                // Each label may take two lines; a single line would cut
                // "ПОСЛЕДНЯЯ ПРОЧИТАННАЯ" at the column edge with no ellipsis.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isLastRead) {
                        Text(
                            text = stringResource(R.string.book_ch_last_read_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.accent,
                            maxLines = ChapterBadgeMaxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // "Last read"/"Last chapter" already tell the story; a
                    // second READ badge would just duplicate them.
                    if (shouldShowReadBadge(chapter.isRead, isLastRead, chapter.isLast)) {
                        val readDescription = stringResource(R.string.book_ch_read_cd)
                        Text(
                            text = stringResource(R.string.book_ch_read_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.accent,
                            maxLines = ChapterBadgeMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics {
                                contentDescription = readDescription
                            },
                        )
                    }
                    if (chapter.isLast) {
                        Text(
                            text = stringResource(R.string.book_ch_last_chapter_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.muted,
                            maxLines = ChapterBadgeMaxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Size is quiet text, not a third trailing icon; long
                    // titles never affect it because the title is one line.
                    if (sizeLabel != null) {
                        val sizeDescription = stringResource(R.string.book_ch_size_cd, sizeLabel)
                        Text(
                            text = sizeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics {
                                contentDescription = sizeDescription
                            },
                        )
                    }
                }
            }
        }
        // Two trailing slots at most: translation state (spinner while
        // loading, retry on failure, accent glyph when Ready) + downloaded.
        // Video rows add the queue controls the current state can honour.
        Row(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isVideoBook && !selectionActive) {
                videoDownloadActions(videoState).forEach { action ->
                    when (action) {
                        VideoDownloadAction.Pause -> SelectionIconAction(
                            icon = Icons.Filled.Pause,
                            contentDescription = stringResource(R.string.dl_action_pause),
                            color = extra.muted,
                            onClick = { onVideoDownloadAction(action) },
                            contentAlignment = Alignment.CenterEnd,
                        )
                        VideoDownloadAction.Resume -> SelectionIconAction(
                            icon = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.dl_action_resume),
                            color = extra.accent,
                            onClick = { onVideoDownloadAction(action) },
                            contentAlignment = Alignment.CenterEnd,
                        )
                        VideoDownloadAction.Cancel -> SelectionIconAction(
                            icon = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.dl_action_cancel),
                            color = MaterialTheme.colorScheme.error,
                            onClick = { onVideoDownloadAction(action) },
                            contentAlignment = Alignment.CenterEnd,
                        )
                    }
                }
            }
            if (translationStatus == ChapterTranslationStatus.Loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = extra.accent,
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = translationLoading },
                )
            } else if (
                translationStatus == ChapterTranslationStatus.Failed ||
                chapter.translationState.equals("FAILED", ignoreCase = true)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = onRetryTranslation),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.book_ch_retry_translation),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else if (chapter.translated ||
                translationStatus == ChapterTranslationStatus.Ready
            ) {
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = stringResource(R.string.book_ch_cd_translated),
                    tint = extra.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (queuedState == DownloadState.RUNNING) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    strokeWidth = 2.dp,
                    color = extra.accent,
                    modifier = Modifier.size(16.dp),
                )
            } else if (downloaded) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = stringResource(R.string.book_ch_cd_downloaded),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// READER NOTES: ONE card — header, hairline, then the
// reviews from the source separated by hairlines; long texts collapse behind
// the shared inline "Read more". The per-review translate icon is a stub
// toast; FULL translation mode would flip the flags in the data layer — the
// closing note says so. An empty list is a normal state (plugins may ship no
// reviews) and gets a quiet line inside the same card.
@Composable
private fun ReaderNotesSection(
    reviews: List<BookReview>,
    onTranslate: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    MiruSectionCard(
        title = stringResource(R.string.book_section_reviews),
        description = stringResource(R.string.book_section_reviews_desc),
    ) {
        if (reviews.isEmpty()) {
            Text(
                text = stringResource(R.string.book_reviews_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
            )
        } else {
            reviews.forEachIndexed { index, review ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = MiruSpace.M),
                    )
                }
                ReviewItem(review = review, onTranslate = onTranslate)
            }
            Spacer(modifier = Modifier.height(MiruSpace.M))
            Text(
                text = stringResource(R.string.book_reviews_auto_note),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
}

@Composable
private fun ReviewItem(review: BookReview, onTranslate: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    Text(
                        text = review.author,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (review.rating > 0f) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(
                                R.string.book_review_rating_cd_fmt,
                                formatRating(review.rating),
                            ),
                            tint = extra.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = formatRating(review.rating),
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.accent,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = review.dateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                )
            }
            if (review.translated) {
                Text(
                    text = stringResource(R.string.book_review_translated),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.muted,
                    modifier = Modifier.padding(start = MiruSpace.S),
                )
            } else {
                TranslateIconButton(onClick = onTranslate)
            }
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        ExpandableBody(text = review.text, collapsedLines = 4)
    }
}

// 48dp touch box, translate glyph in accent — the manual half of the
// review translation (FULL mode would do it automatically instead).
@Composable
private fun TranslateIconButton(onClick: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Box(
        modifier = Modifier
            .size(MiruSize.Touch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Translate,
            contentDescription = stringResource(R.string.book_review_translate_cd),
            tint = extra.accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

// "4.5" / "5" from a float, no trailing zeros (stat strip + review stars).
private fun formatRating(value: Float): String =
    String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')

// Compact chapter size for the row's secondary line: B under 1 KiB, one
// decimal below 10 KB/MB, whole numbers above. 0/unknown returns an empty
// string and the caller never renders it.
internal fun formatChapterSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes < kb -> "$bytes B"
        bytes < mb -> {
            val value = bytes / kb
            if (value < 10) String.format(Locale.US, "%.1f KB", value)
            else "${value.roundToInt()} KB"
        }
        else -> {
            val value = bytes / mb
            if (value < 10) String.format(Locale.US, "%.1f MB", value)
            else "${value.roundToInt()} MB"
        }
    }
}

// The READ badge is redundant next to the "last read"/"last chapter" badges:
// it only adds information on a plain, already-read chapter.
internal fun shouldShowReadBadge(
    isRead: Boolean,
    isLastRead: Boolean,
    isLast: Boolean,
): Boolean = isRead && !isLastRead && !isLast

// Two lines let the long RU labels ("ПОСЛЕДНЯЯ ПРОЧИТАННАЯ") wrap at 200%
// font scale instead of clipping; the ellipsis is only a last-resort guard.
internal const val ChapterBadgeMaxLines = 2

// A failed destructive action: message + the exact action to rerun. Kept in
// screen state (not a toast) until retry succeeds or the user dismisses it.
private data class ChapterDeleteFailure(
    val message: String,
    val retry: () -> Unit,
)

// Honest outcome of a destructive chapter action: only a positive count with
// zero failures is a success that may clear the user's selection. A partial
// delete keeps the selection and offers retry for the remaining chapters.
internal enum class ChapterDeleteOutcome { Success, Nothing, Failed }

internal fun chapterDeleteOutcome(deleted: Int?, failed: Int = 0): ChapterDeleteOutcome = when {
    deleted == null -> ChapterDeleteOutcome.Failed
    failed > 0 -> ChapterDeleteOutcome.Failed
    deleted > 0 -> ChapterDeleteOutcome.Success
    else -> ChapterDeleteOutcome.Nothing
}

// One explicit chapter refresh pass for the page: a typed failure keeps the
// rows already on screen (a failed source must never read as "No chapters")
// and names the class for the log; only a successful answer may replace the
// list, including a genuinely empty one.
internal data class ChapterRefreshView(
    val rows: List<BookChapter>,
    val failure: PluginError?,
) {
    val ok: Boolean get() = failure == null
    val errorClass: String? get() = failure?.let(::pluginErrorClass)
}

// The chapters section renders for stored rows and for a successful empty
// answer (the real empty state); a typed failure with no rows is replaced by
// PluginErrorState instead.
internal fun showChaptersSection(
    chapters: List<BookChapter>,
    failure: PluginErrorReport?,
): Boolean = chapters.isNotEmpty() || failure == null

internal fun chapterRefreshView(
    current: List<BookChapter>,
    result: Outcome<List<BookChapter>>,
): ChapterRefreshView = when (result) {
    is Outcome.Ok -> ChapterRefreshView(rows = result.value, failure = null)
    is Outcome.Err -> ChapterRefreshView(
        rows = current,
        failure = result.error as? PluginError
            ?: PluginError.ScriptError(result.error::class.simpleName ?: "chapters"),
    )
}

// Opening a book: persisted rows need no source call; only an empty store asks
// the plugin, and its typed failure must reach the page rather than read as an
// empty chapter list. Unexpected provider exceptions become a ScriptError.
internal suspend fun loadInitialChapters(
    bookUrl: String,
    cached: suspend (String) -> List<BookChapter>?,
    remote: suspend (String) -> Outcome<List<BookChapter>>,
): Outcome<List<BookChapter>> {
    val stored = try {
        cached(bookUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    if (stored != null) return Outcome.Ok(stored)
    return try {
        remote(bookUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(PluginError.ScriptError(e::class.simpleName ?: "chapters"))
    }
}

// Log field only: HTTP <code> for a status failure, otherwise the class name.
internal fun pluginErrorClass(error: PluginError): String = when (error) {
    is PluginError.Network -> error.status?.let { "HTTP $it" } ?: "Network"
    is PluginError.CfChallengeRequired -> "CfChallengeRequired"
    is PluginError.ResponseTooLarge -> "ResponseTooLarge"
    is PluginError.UnsupportedApi -> "UnsupportedApi"
    is PluginError.ScriptError -> "ScriptError"
    is PluginError.DnsFailure -> "DnsFailure"
    is PluginError.UserMessage -> "UserMessage"
}
