package dev.mirutime.feature.reader.api

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.app.features.reader.document.mangaPages
import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.MangaFeedPlanner
import dev.mirutime.feature.reader.R
import dev.mirutime.feature.reader.ReaderMode
import dev.mirutime.feature.reader.ReaderPalette
import dev.mirutime.feature.reader.SavedReading
import dev.mirutime.feature.reader.session.ReaderSessionStore
import dev.mirutime.feature.reader.session.RestorePhase
import dev.mirutime.feature.reader.session.TtsChapterCoordinator
import dev.mirutime.feature.reader.ui.ReaderAutoscrollEffect
import dev.mirutime.feature.reader.ui.ReaderChrome
import dev.mirutime.feature.reader.ui.ReaderFeed
import dev.mirutime.feature.reader.ui.ReaderFullscreenEffect
import dev.mirutime.feature.reader.ui.MangaScrubberState
import dev.mirutime.feature.reader.ui.ReaderMangaPager
import dev.mirutime.feature.reader.ui.ReaderPaged
import dev.mirutime.feature.reader.ui.ReaderPanel
import dev.mirutime.feature.reader.ui.ReaderPanelHost
import dev.mirutime.feature.reader.ui.ReaderTap
import dev.mirutime.feature.reader.ui.readerBackClosesOverlay
import dev.mirutime.feature.reader.ui.awaitReaderTap
import dev.mirutime.feature.reader.ui.isMenuDoubleTap
import dev.mirutime.feature.reader.ui.openWebSearch
import dev.mirutime.feature.reader.ui.readerBottomPaddingDp
import dev.mirutime.feature.reader.ui.readerTopPaddingDp
import dev.mirutime.feature.reader.ui.restoreReaderBarsNow
import dev.mirutime.feature.tts.R as TtsR
import dev.mirutime.feature.tts.TtsSession
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.feature.tts.ttsSentencePreviewInfo
import dev.mirutime.feature.tts.ui.TtsFloatingPlayer
import dev.mirutime.feature.tts.ui.TtsMiniPlayer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    bookUrl: String,
    startChapterIndex: Int,
    restoreSaved: Boolean,
    // True when the composable is restored after process death: the saved
    // anchor then wins over the chapter the route was first opened on.
    reopened: Boolean = false,
    onBack: () -> Unit,
    onOpenRegex: (String) -> Unit,
    onOpenWebView: (url: String, title: String) -> Unit,
    // Login/auth link from a typed plugin failure. Defaults to the shared
    // in-app WebView so the clearance lands in the same cookie store.
    onOpenAuthUrl: (url: String) -> Unit = { url -> onOpenWebView(url, "") },
    graph: ReaderUiGraph,
    translationPanel: @Composable (bookUrl: String, onDismiss: () -> Unit) -> Unit,
) {
    val store: ReaderSessionStore = graph.session
    val state by store.state.collectAsState()
    val config by graph.config.config.collectAsState()
    val translations by graph.translation.state.collectAsState()
    val metadata by graph.translation.metadata.collectAsState()
    val ttsState by graph.tts.state.collectAsState()
    val ttsSettings by graph.tts.settings.collectAsState()
    val ttsCapabilities by graph.tts.capabilities.collectAsState()
    val handoffInProgress by graph.ttsCoordinator.handoffInProgress.collectAsState()
    val ttsActive = ttsState.status == TtsStatus.Playing ||
        ttsState.status == TtsStatus.Loading
    val ttsErrorMessage = when (ttsState.message) {
        "TTS unavailable" -> stringResource(TtsR.string.rd_tts_unavailable)
        "Nothing to read" -> stringResource(TtsR.string.rd_tts_nothing)
        else -> stringResource(TtsR.string.rd_tts_failed)
    }
    val currentTtsActive by rememberUpdatedState(ttsActive)
    val style = config.style.sanitized()
    val mangaStyle = config.mangaStyle.sanitized()
    // Active paragraph is always marked while TTS has a cursor; the optional
    // word range follows the style switch.
    val ttsHighlight = ttsState.blockId?.let { blockId ->
        TtsHighlight(
            blockId = blockId,
            start = ttsState.rangeStart,
            end = ttsState.rangeEnd,
            colorArgb = style.highlightColor,
            translated = ttsState.translated,
            rangeEnabled = style.ttsHighlight,
        )
    }
    val autoTheme = style.backgroundId == "auto"
    val background = ReaderPalette.background(style.backgroundId)
    // "Auto" follows the app theme; the original style still goes to the
    // panels so picking a color or swatch keeps saving the user's choice.
    val pageBackground =
        if (autoTheme) MaterialTheme.colorScheme.background else Color(background.backgroundArgb)
    val displayStyle = if (autoTheme && style.textColor == null) {
        style.copy(
            textColor = MaterialTheme.colorScheme.onBackground.toArgb().toLong() and 0xFFFFFFFFL,
        )
    } else {
        style
    }
    val backgroundImage = remember(style.backgroundImagePath) {
        style.backgroundImagePath?.let(::File)?.takeIf { it.isFile }
    }
    val textColor = Color(displayStyle.effectiveTextColor())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayAvailable by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    val mangaScrubber = remember { MangaScrubberState() }
    // Bumped by the player's Focus action; the feed/pager scrolls to the
    // spoken block even when it did not change since the last follow.
    var ttsFocusToken by rememberSaveable { mutableStateOf(0) }
    var chromeVisible by rememberSaveable { mutableStateOf(false) }
    // Autoscroll is a session switch: speed is persisted in ReaderStyle, but
    // starting it is a deliberate per-chapter action.
    var autoScrollEnabled by rememberSaveable { mutableStateOf(false) }
    var autoScrollTouching by remember { mutableStateOf(false) }
    var readerResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val ttsSessionActive = ttsState.status == TtsStatus.Playing ||
        ttsState.status == TtsStatus.Loading ||
        ttsState.status == TtsStatus.Paused
    // While TTS owns a cursor, a stale feed scroll from the previous chapter
    // must not pull the session (and the guard) back. The TTS effect still
    // adopts its own chapter and within-chapter offsets.
    val onReaderAnchor: (ReadingAnchor, Int) -> Unit = { anchor, offset ->
        val spokenUrl = graph.tts.state.value.blockId?.chapterUrl
        if (shouldApplyReaderAnchor(
                ttsOwnsCursor = ttsSessionActive,
                handoffInProgress = handoffInProgress,
                spokenChapterUrl = spokenUrl,
                anchorChapterUrl = anchor.chapterUrl,
            )
        ) {
            store.onAnchorChanged(anchor, offset)
        }
    }
    // Captured before store.open() writes a newer reading position, so the
    // stale check compares against the previous session, not this open.
    var restoreSession by remember(bookUrl) { mutableStateOf<TtsSession?>(null) }
    var sessionCaptured by remember(bookUrl) { mutableStateOf(false) }

    LaunchedEffect(bookUrl, startChapterIndex, restoreSaved) {
        if (!sessionCaptured) {
            val progress = graph.positions.load(bookUrl)
            // A live controller already holds the session; restoring the
            // persisted copy here would pause playback on Activity recreation.
            val liveTts = graph.tts.state.value.blockId != null &&
                graph.tts.state.value.status != TtsStatus.Idle &&
                graph.tts.state.value.status != TtsStatus.Failed
            restoreSession = if (liveTts) {
                null
            } else {
                graph.ttsSessions.load()?.takeIf { session ->
                    session.bookUrl == bookUrl && isFreshTtsSession(session, progress)
                }
            }
            sessionCaptured = true
        }
        // Always honor the chapter computed by the caller (Continue uses
        // ReaderOpenResolver, rows pass their exact index). `restoreSaved`
        // only controls restoring the saved offset inside that chapter; the
        // old null target re-resolved a stale saved chapter and could open a
        // different chapter than the Continue label.
        store.open(
            bookUrl = bookUrl,
            requestedChapterIndex = startChapterIndex,
            restoreSaved = restoreSaved,
            reopened = reopened,
        )
    }

    // Original-stage cleanup rules changed: re-parse the open chapter so the
    // screen and TTS do not keep showing/speaking the old text. Translation
    // rules need no reload — the translation port recomputes on its revision.
    val rulesOriginalRevision by graph.rules.originalRevision.collectAsState()
    var seenRulesRevision by remember(bookUrl) { mutableStateOf(rulesOriginalRevision) }
    LaunchedEffect(rulesOriginalRevision) {
        if (rulesOriginalRevision == seenRulesRevision) return@LaunchedEffect
        seenRulesRevision = rulesOriginalRevision
        if (ttsActive) graph.tts.stop()
        store.reloadActiveChapter()
    }

    LaunchedEffect(
        chromeVisible,
        panel,
        ttsState.status,
        ttsSessionActive,
        mangaScrubber.dragging,
    ) {
        // A failed speaker keeps its retry affordance visible instead of
        // disappearing with the rest of the auto-hidden chrome. Otherwise the
        // menu always auto-hides, independent of the floating-player option.
        // A scrub in progress holds it open; hiding would dispose the slider
        // and leave the reader thinking a drag is still active.
        if (chromeVisible && panel == null && ttsState.status != TtsStatus.Failed &&
            !mangaScrubber.dragging
        ) {
            delay(3500)
            chromeVisible = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    readerResumed = false
                    store.pauseSession()
                }
                Lifecycle.Event.ON_START -> {
                    readerResumed = true
                    overlayAvailable = android.provider.Settings.canDrawOverlays(context)
                    if (!currentTtsActive) store.resumeSession()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Activity recreation must not kill playback; explicit Back and the
            // manga switch call the coordinator instead.
            store.flush()
        }
    }

    // Bars come back before the pop, not after it: the shell's Scaffold must
    // already see the real insets while the reader is leaving. The fullscreen
    // effect keeps its onDispose restore as the fallback for paths that skip
    // these buttons (route replacement, process teardown).
    val exitReader: () -> Unit = {
        restoreReaderBarsNow(context)
        graph.ttsCoordinator.stop()
        graph.stopTtsSession()
        onBack()
    }

    // Sheets are modal and consume back first; the reader menu is an overlay,
    // so with no sheet open back always leaves the reader.
    BackHandler {
        if (readerBackClosesOverlay(panel != null)) {
            panel = null
        } else {
            store.flush()
            exitReader()
        }
    }

    val activeDocument = state.documents[state.activeChapterIndex]
    val isManga = state.book?.isManga == true
    val mangaPages = remember(activeDocument, isManga) {
        if (isManga) activeDocument?.mangaPages().orEmpty() else emptyList()
    }
    // The webtoon is one endless strip: the active chapter plus whatever
    // neighbour is loaded, so scrolling continues across chapter seams.
    val mangaFeed = remember(isManga, state.documents, state.chapters, state.activeChapterIndex) {
        if (isManga) {
            MangaFeedPlanner.plan(state.chapters, state.documents, state.activeChapterIndex)
        } else {
            emptyList()
        }
    }
    // Chrome scrubber position and the bridge to whichever manga viewer is
    // active. Reset on chapter change so a stale counter never flashes.
    var mangaPage by remember(state.activeChapterIndex) { mutableIntStateOf(0) }
    val nextChapterFirstPage = remember(state.activeChapterIndex, state.documents) {
        state.documents[state.activeChapterIndex + 1]
            ?.mangaPages()
            ?.firstOrNull()
            ?.imageRef
    }
    val mangaPageFile: suspend (String) -> java.io.File? = remember(graph) {
        { url -> graph.mangaPageFile(url) }
    }
    val view = LocalView.current
    val keepScreenOn = style.keepScreenOn || isManga
    ReaderFullscreenEffect(
        fullscreen = style.fullscreen,
        chromeVisible = chromeVisible,
        panelOpen = panel != null,
    )
    DisposableEffect(view, keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(
        state.activeChapterIndex,
        state.documents[state.activeChapterIndex]?.chapterUrl,
        state.restorePhase,
        sessionCaptured,
        handoffInProgress,
    ) {
        // Wait for the restore candidate to be copied into memory: stop()
        // clears the persisted slot, and must not erase it before that.
        if (!sessionCaptured) return@LaunchedEffect
        // A recreate reopens the session and briefly has no document for the
        // active index; that is not a manual chapter change.
        if (state.restorePhase != RestorePhase.Ready) return@LaunchedEffect
        if (state.documents[state.activeChapterIndex] == null) return@LaunchedEffect
        // The coordinator owns the hand-off; a chapter change it caused must
        // not stop the speech it is about to restart. The key re-runs when the
        // hand-off finishes, so a manual change during it is still caught.
        if (handoffInProgress) return@LaunchedEffect
        val spokenUrl = graph.tts.state.value.blockId?.chapterUrl
        val visibleUrl = state.documents[state.activeChapterIndex]?.chapterUrl
        if (spokenUrl != null && spokenUrl != visibleUrl) graph.tts.stop()
    }
    LaunchedEffect(isManga) {
        if (isManga) {
            graph.ttsCoordinator.stop()
            graph.stopTtsSession()
        }
    }
    LaunchedEffect(ttsActive, state.activeChapterIndex) {
        if (ttsActive) {
            store.pauseSession()
            graph.ttsSessionTracker?.start(bookUrl, state.activeChapterIndex)
        } else {
            graph.ttsSessionTracker?.stop()
            if (state.restorePhase == RestorePhase.Ready) store.resumeSession()
        }
    }
    val chapterPosition = state.chapters.indexOfFirst { it.index == state.activeChapterIndex }
    val chapterCount = state.chapters.size
    val blockIndex = state.anchor?.blockId?.index ?: 0
    val chapterPercent = if (activeDocument != null && activeDocument.blocks.isNotEmpty()) {
        ((blockIndex + 1f) / activeDocument.blocks.size * 100f)
            .roundToInt()
            .coerceIn(0, 100)
    } else {
        0
    }
    val chapterUrl = activeDocument?.chapterUrl
        ?: state.chapters.firstOrNull { it.index == state.activeChapterIndex }?.chapterUrl.orEmpty()
    val metadataConfig = graph.translation.metadataConfigKey(bookUrl)
    // In FULL mode the reader warms the book's chapter titles itself, so the
    // chrome and the in-content header read translated even when the reader was
    // entered straight from "continue reading" without visiting the book page.
    LaunchedEffect(bookUrl, metadataConfig, state.book?.title, state.chapters.size) {
        if (metadataConfig == null) return@LaunchedEffect
        val title = state.book?.title.orEmpty()
        if (title.isBlank()) return@LaunchedEffect
        graph.translation.hydrateMetadata(bookUrl)
        graph.translation.submitChapterTitles(
            bookUrl = bookUrl,
            title = title,
            chapters = state.chapters.map { it.index to it.title },
        )
    }
    val metadataEntry = metadata[bookUrl]?.takeIf { it.configKey == metadataConfig }
    val chapterTitle = metadataEntry?.chapterTitles?.get(state.activeChapterIndex)
        ?: activeDocument?.title
        ?: state.chapters.firstOrNull { it.index == state.activeChapterIndex }?.title.orEmpty()
    val mode = graph.translation.activeMode(bookUrl)
    // A disabled mode must hide whatever is still cached from a previous
    // configuration instead of leaving translated text on screen.
    val visibleTranslations = visibleTranslations(mode, bookUrl, translations)
    // The player only shows text it can point at; during a chapter hand-off or
    // before restore lands the preview stays null and the sheet says so.
    // Translations live in the chapter cache, not in ReaderBlock.translation.
    val ttsPreviewTranslation = if (ttsState.translated) {
        ttsState.blockId?.let { blockId ->
            visibleTranslations[blockId.chapterUrl]?.translations?.get(blockId.index)
        }
    } else {
        null
    }
    val ttsPreviewInfo = ttsSentencePreviewInfo(activeDocument, ttsState, ttsPreviewTranslation)
    val ttsPreview = ttsPreviewInfo?.text
    // The floating player can show the whole spoken block plus the other
    // language, matching NoveLA's spoken/both/translation paragraph modes.
    val ttsBlock = ttsState.blockId?.let { activeDocument?.blockAt(it) }
    val ttsBlockTranslation = ttsState.blockId?.let { blockId ->
        visibleTranslations[blockId.chapterUrl]?.translations?.get(blockId.index)
    }
    val ttsParagraph = if (ttsState.translated) {
        ttsPreviewTranslation ?: ttsBlock?.translation
    } else {
        ttsBlock?.original
    }
    val ttsCounterpart = if (ttsState.translated) ttsBlock?.original else ttsBlockTranslation
    val ttsSentenceNumber = if (ttsState.blockId != null) ttsState.currentChunk + 1 else 0
    val ttsChunkCount = ttsState.chunkCount

    // One-shot: a process-death session only restores after the chapter is
    // ready, and only when it points at the chapter the reader actually opened.
    LaunchedEffect(sessionCaptured, state.restorePhase, state.activeChapterIndex, activeDocument) {
        val session = restoreSession ?: return@LaunchedEffect
        val document = activeDocument ?: return@LaunchedEffect
        if (state.restorePhase != RestorePhase.Ready) return@LaunchedEffect
        if (state.activeChapterIndex != session.chapterIndex ||
            document.chapterUrl != session.chapterUrl
        ) {
            restoreSession = null
            return@LaunchedEffect
        }
        val restored = graph.tts.restore(
            document = document,
            translations = visibleTranslations[document.chapterUrl]?.translations.orEmpty(),
            session = session,
        )
        restoreSession = null
        if (restored) {
            store.adoptAnchor(
                ReadingAnchor(
                    chapterUrl = document.chapterUrl,
                    blockId = BlockId(document.chapterUrl, session.blockIndex),
                    charOffset = session.charOffset,
                    sentenceIndex = session.sentenceIndex,
                ),
            )
        }
    }

    // The coordinator owns the chapter hand-off; the screen only reports its
    // user-visible outcomes.
    LaunchedEffect(graph.ttsCoordinator) {
        graph.ttsCoordinator.events.collect { event ->
            when (event) {
                TtsChapterCoordinator.Event.EndOfBook ->
                    Toast.makeText(
                        context,
                        R.string.rd_tts_end_of_book,
                        Toast.LENGTH_SHORT,
                    ).show()
                is TtsChapterCoordinator.Event.Failed ->
                    Toast.makeText(context, TtsR.string.rd_tts_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // TTS follows spoken units, not every word range, so saving stays debounced.
    LaunchedEffect(ttsState.status, ttsState.currentChunk, ttsState.blockId) {
        if (ttsState.status != TtsStatus.Playing && ttsState.status != TtsStatus.Paused) {
            return@LaunchedEffect
        }
        val blockId = ttsState.blockId ?: return@LaunchedEffect
        if (state.chapters.none { it.chapterUrl == blockId.chapterUrl }) return@LaunchedEffect
        store.adoptAnchor(
            ReadingAnchor(
                chapterUrl = blockId.chapterUrl,
                blockId = blockId,
                charOffset = ttsState.rangeStart,
                sentenceIndex = ttsState.sentenceIndex,
            ),
        )
    }
    // The badge states the configured mode plus the live status of the
    // chapter it sits above.
    val translationLabel = mode?.let { activeMode ->
        if (!activeMode.hasPair) {
            stringResource(R.string.rd_translation_on)
        } else {
            val pair = stringResource(
                R.string.st_pt_pair_fmt,
                activeMode.source.uppercase(Locale.ROOT),
                activeMode.target.uppercase(Locale.ROOT),
            )
            pair + " · " + graph.translation.providerLabel(activeMode.provider)
        }
    }
    val loadingSuffix = stringResource(R.string.rd_translation_loading)
    // A wait with no numbers reads as a hang: the badge counts the seconds the
    // active chapter has been translating, reset per chapter.
    val activeChapterUrl = state.documents[state.activeChapterIndex]?.chapterUrl
    val activeTranslating = activeChapterUrl
        ?.let { visibleTranslations[it]?.status } == ReaderTranslationStatus.Loading
    var translationElapsedSec by remember(activeChapterUrl) { mutableIntStateOf(0) }
    LaunchedEffect(activeChapterUrl, activeTranslating) {
        translationElapsedSec = 0
        if (!activeTranslating) return@LaunchedEffect
        while (true) {
            delay(1_000)
            translationElapsedSec++
        }
    }
    val translationElapsedText =
        stringResource(R.string.rd_translation_loading_seconds, translationElapsedSec)
    val failedSuffix = stringResource(R.string.rd_translation_failed)
    val localNetworkSuffix = stringResource(R.string.rd_translation_local_network)
    val cleartextSuffix = stringResource(R.string.rd_translation_cleartext)
    val labelFor: (Int) -> String? = { chapterIndex ->
        translationLabel?.let { base ->
            val translation = state.documents[chapterIndex]?.chapterUrl
                ?.let { visibleTranslations[it] }
            when (translation?.status) {
                ReaderTranslationStatus.Loading -> if (chapterIndex == state.activeChapterIndex && translationElapsedSec > 0) {
                    "$base · $loadingSuffix $translationElapsedText"
                } else {
                    "$base · $loadingSuffix"
                }
                ReaderTranslationStatus.Failed -> "$base · " + when (translation.failure) {
                    ReaderTranslationFailure.LocalNetworkRequired -> localNetworkSuffix
                    ReaderTranslationFailure.CleartextNotAllowed -> cleartextSuffix
                    null -> failedSuffix
                }
                ReaderTranslationStatus.Ready, null -> base
            }
        }
    }
    val pagedTranslations: Map<BlockId, String> = activeDocument?.let { document ->
        visibleTranslations[document.chapterUrl]?.translations
            ?.mapKeys { (index, _) -> BlockId(document.chapterUrl, index) }
    }.orEmpty()
    val bookTitle = metadataEntry?.title ?: state.book?.title.orEmpty()
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    // API 33+ needs a runtime grant before the ongoing playback notification is
    // visible. Playback itself stays in-app either way.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (ttsActive) graph.ttsCoordinator.ensurePlayback()
        } else {
            notificationDenied = true
            Toast.makeText(context, R.string.tts_notifications_off, Toast.LENGTH_LONG).show()
        }
    }
    // Start / pause / resume all go through the existing toggle. Permission
    // asks only on the first start; a denied grant leaves in-app playback on.
    val ensureTtsNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationDenied &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // One start path for "start"/"start here": begin at the visible anchor, not
    // the chapter top, so a restarted TTS (including after a sleep-timer stop)
    // continues where the reader is. The anchor is written by the feed/pager.
    fun startTtsAtAnchor() {
        val document = activeDocument ?: return
        val anchor = store.state.value.anchor?.takeIf { it.chapterUrl == document.chapterUrl }
        graph.tts.playFrom(
            document = document,
            translations = visibleTranslations[document.chapterUrl]?.translations.orEmpty(),
            blockIndex = anchor?.blockId?.index,
            charOffset = anchor?.charOffset ?: 0,
        )
    }
    val toggleTts: () -> Unit = {
        ensureTtsNotificationPermission()
        val document = activeDocument
        if (document != null) {
            val status = graph.tts.state.value.status
            if (status == TtsStatus.Idle || status == TtsStatus.Failed) {
                startTtsAtAnchor()
            } else {
                graph.tts.toggle(
                    document,
                    visibleTranslations[document.chapterUrl]?.translations.orEmpty(),
                )
            }
        }
    }
    val stopTts: () -> Unit = {
        graph.ttsCoordinator.stop()
    }
    // "Start here" uses the reader's own visible anchor.
    val startTtsHere: () -> Unit = {
        ensureTtsNotificationPermission()
        startTtsAtAnchor()
    }
    // Focus closes the sheet first: scrolling behind an open sheet would not
    // let the user see the paragraph that was just brought into view.
    val focusTtsBlock: () -> Unit = {
        if (panel != null) panel = null
        // The tap that triggered Focus can still reach the page gesture layer
        // for this frame; hide the chrome after the gesture settles so it is
        // not immediately reopened by the same tap.
        scope.launch {
            delay(600)
            chromeVisible = false
        }
        ttsFocusToken += 1
    }
    // Move speech to the neighbouring block (paragraph), not the next sentence.
    // A paused session only moves its cursor; a playing one continues from the
    // new block (FR-051).
    val ttsAdjacentParagraph: (Int) -> Unit = { delta ->
        val document = activeDocument
        if (document != null && document.blocks.isNotEmpty()) {
            val currentBlock = ttsState.blockId?.index ?: 0
            val target = (currentBlock + delta).coerceIn(0, document.blocks.lastIndex)
            if (target != currentBlock) {
                graph.tts.seekToBlock(
                    document = document,
                    translations = visibleTranslations[document.chapterUrl]
                        ?.translations.orEmpty(),
                    blockIndex = target,
                    charOffset = 0,
                )
            }
        }
    }
    // The persistent player belongs to the bottom reader menu, above the dock.
    // With "mini player always" it stays there even when TTS is stopped, so the
    // menu can start a new session without opening the settings sheet.
    val ttsDockMini: (@Composable () -> Unit)? =
        if ((ttsSessionActive || ttsSettings.miniPlayerAlways) && !isManga) {
            {
                TtsMiniPlayer(
                    status = ttsState.status,
                    onToggle = toggleTts,
                    onStop = stopTts,
                    onStartHere = startTtsHere,
                    onFocus = focusTtsBlock,
                    onPrevParagraph = { ttsAdjacentParagraph(-1) },
                    onNextParagraph = { ttsAdjacentParagraph(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            null
        }
    LaunchedEffect(ttsState.status) {
        if (ttsState.status == TtsStatus.Failed) chromeVisible = true
    }
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    // Chrome floats over the page: the reading area never changes when a menu
    // opens, so Feed does not scroll and Paged does not repaginate. Fullscreen
    // drops the safe insets entirely; showing the bars back does not reflow.
    val contentPadding = PaddingValues(
        start = style.marginStartDp.dp,
        end = style.marginEndDp.dp,
        top = readerTopPaddingDp(
            fullscreen = style.fullscreen,
            safeTopDp = safeInsets.calculateTopPadding().value,
        ).dp,
        bottom = readerBottomPaddingDp(
            fullscreen = style.fullscreen,
            safeBottomDp = safeInsets.calculateBottomPadding().value,
        ).dp,
    )
    val autoScrollSupported = !isManga && state.mode == ReaderMode.Feed
    val autoScrollSubtitle = when {
        isManga -> stringResource(R.string.rd_autoscroll_manga_off)
        state.mode == ReaderMode.Paged -> stringResource(R.string.rd_autoscroll_feed_only)
        autoScrollEnabled && ttsActive -> stringResource(R.string.rd_autoscroll_tts_paused)
        autoScrollEnabled && chromeVisible -> stringResource(R.string.rd_autoscroll_menu_paused)
        autoScrollEnabled -> stringResource(R.string.rd_autoscroll_hint)
        else -> stringResource(R.string.rd_autoscroll_hint_off)
    }
    LaunchedEffect(state.mode, isManga) {
        if (!autoScrollSupported) autoScrollEnabled = false
    }
    ReaderAutoscrollEffect(
        enabled = autoScrollEnabled &&
            autoScrollSupported &&
            !autoScrollTouching &&
            !chromeVisible &&
            panel == null &&
            !ttsActive &&
            readerResumed &&
            state.restorePhase == RestorePhase.Ready,
        listState = listState,
        speed = style.autoScrollSpeed,
        onReachedEnd = {
            autoScrollEnabled = false
            Toast.makeText(context, R.string.rd_autoscroll_end, Toast.LENGTH_SHORT).show()
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground),
    ) {
        // Custom art is ornamental: a palette-tinted scrim keeps the chosen
        // text/background pair readable, and the flat palette stays visible
        // underneath while the image loads or if the file is gone.
        if (backgroundImage != null) {
            AsyncImage(
                model = backgroundImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(pageBackground.copy(alpha = 0.64f)),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.mode, style.doubleTapMenu, isManga, chromeVisible) {
                    // With chrome shown, the full-screen content gesture layer
                    // must not steal taps from the floating bottom buttons.
                    if (isManga || state.mode == ReaderMode.Paged || chromeVisible) return@pointerInput
                    // SelectionContainer sits inside this Box, so taps are read
                    // at the Initial pass: handled short taps consume their up
                    // before selection sees them, while long presses and drags
                    // pass through untouched.
                    var lastTapAt = 0L
                    var lastHandledTap: ReaderTap? = null
                    awaitEachGesture {
                        val tap = awaitReaderTap(lastHandledTap) {
                            if (!style.doubleTapMenu) {
                                chromeVisible = !chromeVisible
                            } else {
                                val now = SystemClock.uptimeMillis()
                                val isDouble = isMenuDoubleTap(
                                    now = now,
                                    lastTapAt = lastTapAt,
                                    timeout = viewConfiguration.doubleTapTimeoutMillis,
                                )
                                if (isDouble) {
                                    lastTapAt = 0L
                                    chromeVisible = !chromeVisible
                                } else {
                                    lastTapAt = now
                                }
                            }
                        }
                        lastHandledTap = tap
                        if (tap == null) lastTapAt = 0L
                    }
                },
        ) {
            when {
                state.restorePhase == RestorePhase.Restoring -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = textColor)
                }
                state.restorePhase == RestorePhase.Failed -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = state.error ?: stringResource(R.string.rd_chapter_error),
                            color = textColor,
                        )
                    }
                }
                activeDocument != null && mangaPages.isNotEmpty() -> ReaderMangaPager(
                    document = activeDocument,
                    pages = mangaPages,
                    chapterIndex = state.activeChapterIndex,
                    anchor = state.anchor,
                    scrollToken = state.scrollToken,
                    style = mangaStyle,
                    chromeVisible = chromeVisible,
                    canPrevChapter = chapterPosition > 0,
                    canNextChapter = chapterPosition >= 0 && chapterPosition < chapterCount - 1,
                    onPrevChapter = {
                        state.chapters.getOrNull(chapterPosition - 1)?.let {
                            scope.launch { store.goToChapter(it.index, atEnd = true) }
                        }
                    },
                    onNextChapter = {
                        state.chapters.getOrNull(chapterPosition + 1)?.let {
                            scope.launch { store.goToChapter(it.index) }
                        }
                    },
                    onAnchor = onReaderAnchor,
                    onChapterFinished = { index ->
                        scope.launch { store.onChapterFinished(index) }
                    },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    scrubber = mangaScrubber,
                    onVisiblePage = { mangaPage = it },
                    nextChapterFirstPage = nextChapterFirstPage,
                    onNearEnd = {
                        // Do not race an already running prefetch; the open/anchor
                        // path usually loaded the next chapter long before this.
                        val next = state.activeChapterIndex + 1
                        if (state.statuses[next] != ChapterLoadStatus.Loading) {
                            scope.launch { store.prefetch() }
                        }
                    },
                    pageFile = mangaPageFile,
                    feed = mangaFeed,
                    onVisibleChapter = { index, chapterAnchor ->
                        scope.launch { store.adoptVisibleChapter(index, chapterAnchor) }
                    },
                    onNearStart = { index ->
                        scope.launch { store.ensurePrevious(index) }
                    },
                    // A page that still fails after 2×/1s means the chapter's
                    // CDN tokens rotated: reuse the session retry, no second
                    // reader data path.
                    onRefreshPages = {
                        scope.launch { store.retry(state.activeChapterIndex) }
                    },
                )
                state.mode == ReaderMode.Paged -> ReaderPaged(
                    document = activeDocument,
                    status = state.statuses[state.activeChapterIndex],
                    style = displayStyle,
                    contentPadding = contentPadding,
                    chapterIndex = state.activeChapterIndex,
                    chapterTitle = chapterTitle,
                    translationLabel = labelFor(state.activeChapterIndex),
                    translations = pagedTranslations,
                    parallel = graph.settings.parallelEnabled,
                    translationFirst = graph.settings.parallelOrder == "TRANSLATION_FIRST",
                    ttsHighlight = ttsHighlight,
                    focusToken = ttsFocusToken,
                    doubleTapMenu = style.doubleTapMenu,
                    anchor = state.anchor,
                    scrollToken = state.scrollToken,
                    canPrevChapter = chapterPosition > 0,
                    canNextChapter = chapterPosition >= 0 && chapterPosition < chapterCount - 1,
                    onPrevChapter = {
                        state.chapters.getOrNull(chapterPosition - 1)?.let {
                            scope.launch { store.goToChapter(it.index, atEnd = true) }
                        }
                    },
                    onNextChapter = {
                        state.chapters.getOrNull(chapterPosition + 1)?.let {
                            scope.launch { store.goToChapter(it.index) }
                        }
                    },
                    onRetry = { index -> scope.launch { store.retry(index) } },
                    onOpenAuth = onOpenAuthUrl,
                    onAnchor = onReaderAnchor,
                    onChapterFinished = { index ->
                        scope.launch { store.onChapterFinished(index) }
                    },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onSearchSelection = { text -> openWebSearch(context, text) },
                )
                else -> ReaderFeed(
                    state = state,
                    style = displayStyle,
                    listState = listState,
                    contentPadding = contentPadding,
                    translationLabelFor = labelFor,
                    translations = visibleTranslations,
                    chapterTitles = metadataEntry?.chapterTitles.orEmpty(),
                    parallel = graph.settings.parallelEnabled,
                    translationFirst = graph.settings.parallelOrder == "TRANSLATION_FIRST",
                    ttsHighlight = ttsHighlight,
                    focusToken = ttsFocusToken,
                    onSearchSelection = { text -> openWebSearch(context, text) },
                    onAnchor = onReaderAnchor,
                    onRetry = { index -> scope.launch { store.retry(index) } },
                    onLoadPrevious = { index -> scope.launch { store.ensurePrevious(index) } },
                    onOpenAuth = onOpenAuthUrl,
                    onChapterFinished = { index -> scope.launch { store.onChapterFinished(index) } },
                    onStartOver = {
                        state.chapters.firstOrNull()?.let { scope.launch { store.goToChapter(it.index) } }
                    },
                    onUserScrollChange = { autoScrollTouching = it },
                )
        }
        }

        if (chromeVisible && state.restorePhase == RestorePhase.Ready) {
            ReaderChrome(
                title = chapterTitle,
                bookTitle = bookTitle,
                chapterIndex = state.activeChapterIndex,
                chapterCount = chapterCount,
                chapterPercent = chapterPercent,
                canPrev = chapterPosition > 0,
                canNext = chapterPosition >= 0 && chapterPosition < chapterCount - 1,
                canOpenWebView = chapterUrl.isNotBlank(),
                ttsStatus = ttsState.status,
                sleepTimer = graph.tts.sleepTimer,
                ttsEnabled = !isManga,
                manga = isManga,
                mangaPage = mangaPage,
                mangaPageCount = mangaPages.size,
                onMangaPageChange = { page ->
                    chromeVisible = true
                    mangaScrubber.seek(page)
                },
                onMangaPageChangeFinished = { mangaScrubber.finish() },
                onBack = exitReader,
                onPrev = {
                    if (ttsSessionActive) {
                        graph.tts.skipPreviousChapter()
                    } else {
                        state.chapters.getOrNull(chapterPosition - 1)?.let {
                            scope.launch { store.goToChapter(it.index, atEnd = true) }
                        }
                    }
                },
                onNext = {
                    if (ttsSessionActive) {
                        graph.tts.skipNextChapter()
                    } else {
                        state.chapters.getOrNull(chapterPosition + 1)?.let {
                            scope.launch { store.goToChapter(it.index) }
                        }
                    }
                },
                onOpenWebView = {
                    if (chapterUrl.isNotBlank()) onOpenWebView(chapterUrl, chapterTitle)
                },
                onTts = {
                    chromeVisible = true
                    panel = ReaderPanel.Tts
                },
                onTranslation = {
                    chromeVisible = true
                    panel = ReaderPanel.Translation
                },
                onStyle = {
                    chromeVisible = true
                    panel = ReaderPanel.Style
                },
                onMore = {
                    chromeVisible = true
                    panel = ReaderPanel.More
                },
                onDismiss = { chromeVisible = false },
                ttsMiniPlayer = ttsDockMini,
            )
        }

        // Optional floating player: in-app only, no system overlay. Hidden
        // while the chrome or a sheet is open so it never duplicates the
        // bottom-menu player or sits under a modal scrim. With the overlay
        // permission the player lives in the service window; the state is
        // refreshed on every ON_START after the user returns from Settings.
        if (ttsSessionActive &&
            ttsSettings.floatingPlayer &&
            !overlayAvailable &&
            panel == null &&
            !chromeVisible
        ) {
            TtsFloatingPlayer(
                status = ttsState.status,
                chapterTitle = chapterTitle,
                sentencePreview = ttsPreview,
                paragraphText = ttsParagraph,
                counterpartText = ttsCounterpart,
                spokenIsTranslation = ttsState.translated,
                ttsHighlight = ttsHighlight,
                sentenceHighlightStart = ttsPreviewInfo?.start ?: 0,
                sentenceHighlightEnd = ttsPreviewInfo?.end ?: 0,
                sentenceNumber = ttsSentenceNumber,
                chunkCount = ttsChunkCount,
                chapterNumber = state.activeChapterIndex,
                chapterCount = chapterCount,
                chapterPercent = chapterPercent,
                remainingSeconds = ttsState.estimatedRemainingSeconds,
                sleepTimer = graph.tts.sleepTimer,
                onSleepTimerClick = { chromeVisible = true; panel = ReaderPanel.Tts },
                atEnd = ttsState.atEnd,
                glowColorArgb = style.highlightColor,
                settings = ttsSettings,
                onSettingsChange = graph.tts::updateSettings,
                onToggle = toggleTts,
                onStop = stopTts,
                onStartHere = startTtsHere,
                onFocus = focusTtsBlock,
                onPreviousChunk = graph.tts::skipPreviousChunk,
                onNextChunk = graph.tts::skipNextChunk,
                onOpenSettings = { panel = ReaderPanel.Tts },
            )
        }
    }

    ReaderPanelHost(
        panel = panel,
        style = style,
        onStyleChange = graph.config::setStyle,
        onDismiss = { panel = null },
        bookUrl = bookUrl,
        onOpenRegex = onOpenRegex,
        mode = state.mode,
        onModeChange = store::setMode,
        assets = graph.assets,
        translationPanel = translationPanel,
        showMode = !isManga,
        manga = isManga,
        mangaStyle = mangaStyle,
        onMangaStyleChange = graph.config::setMangaStyle,
        ttsSettings = ttsSettings,
        ttsCapabilities = ttsCapabilities,
        ttsStatus = ttsState.status,
        ttsMessage = ttsErrorMessage,
        ttsHighlight = style.ttsHighlight,
        highlightColor = style.highlightColor,
        onTtsSettingsChange = graph.tts::updateSettings,
        onTtsTest = graph.tts::testUtterance,
        onTtsReset = graph.tts::resetSettings,
        onTtsDiscoverVoices = graph.tts::refreshEngines,
        sleepTimer = graph.tts.sleepTimer,
        onSleepTimerMinutes = graph.tts::setSleepTimer,
        onSleepTimerChapters = graph.tts::setSleepTimerChapters,
        onSleepTimerCancel = graph.tts::cancelSleepTimer,
        onTtsRetry = graph.tts::retry,
        onTtsToggle = toggleTts,
        onTtsHighlightChange = { enabled ->
            graph.config.setStyle(style.copy(ttsHighlight = enabled).sanitized())
        },
        onHighlightColorChange = { color ->
            graph.config.setStyle(style.copy(highlightColor = color).sanitized())
        },
        autoScrollEnabled = autoScrollEnabled,
        autoScrollSupported = autoScrollSupported,
        autoScrollSubtitle = autoScrollSubtitle,
        onAutoScrollChange = { enabled ->
            autoScrollEnabled = enabled
            // The sheet is open, so the page is paused anyway; hiding the
            // chrome now means scrolling starts as soon as the sheet closes.
            if (enabled) chromeVisible = false
        },
    )
}

/**
 * Chapters the feed can show and therefore needs translated: the active one
 * first, then the prefetch window, then every previous chapter loaded behind
 * the anchor (nearest first). Translation state is per chapter, so submitting
 * the backlog cannot overwrite the active chapter.
 */
internal fun translationEnsureTargets(
    activeChapterIndex: Int,
    loadedChapterIndices: Collection<Int>,
): List<Int> = buildList {
    add(activeChapterIndex)
    add(activeChapterIndex + 1)
    addAll(loadedChapterIndices.filter { it < activeChapterIndex }.sortedDescending())
}.distinct()

/**
 * Translations stay hidden unless the active mode has a complete pair.
 * Disabling translation leaves the controller cache intact, so the reader must
 * filter it out instead of showing translated text after the switch is off.
 */
fun visibleTranslations(
    mode: ReaderTranslationMode?,
    bookUrl: String,
    translations: Map<String, ReaderChapterTranslation>,
): Map<String, ReaderChapterTranslation> {
    if (mode?.hasPair != true) return emptyMap()
    // Entries carry their owning book now; an entry from another book (or a
    // stale one with no owner) is never painted.
    return translations.filterValues { it.bookUrl == bookUrl }
}

/**
 * A TTS session older than manual reading is stale. Same-spot sessions win
 * regardless of timestamp because TTS itself saves the reading anchor, so the
 * debounced position write is normally a few hundred ms newer than the cursor.
 */
internal fun isFreshTtsSession(session: TtsSession, progress: SavedReading?): Boolean {
    val anchor = progress?.anchor ?: return true
    val sameSpot = anchor.chapterUrl == session.chapterUrl &&
        anchor.blockId.index == session.blockIndex &&
        (anchor.sentenceIndex ?: session.sentenceIndex) == session.sentenceIndex
    return sameSpot || session.updatedAt >= progress.updatedAt
}

/** True while a chapter hand-off still targets the chapter the reader shows. */
internal fun ttsHandoffApplies(activeChapterIndex: Int, target: Int): Boolean =
    activeChapterIndex == target

/**
 * While TTS owns a cursor, a feed anchor from another chapter is a stale scroll
 * position (the list has not reached the spoken chapter yet) and must not pull
 * the session back. Within-chapter offsets always apply.
 */
internal fun shouldApplyReaderAnchor(
    ttsOwnsCursor: Boolean,
    handoffInProgress: Boolean,
    spokenChapterUrl: String?,
    anchorChapterUrl: String,
): Boolean = !handoffInProgress &&
    (!ttsOwnsCursor || spokenChapterUrl == null || anchorChapterUrl == spokenChapterUrl)
