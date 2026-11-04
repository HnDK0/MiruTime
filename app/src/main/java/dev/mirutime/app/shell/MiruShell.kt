package dev.mirutime.app.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import android.net.Uri
import android.content.Intent
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.mirutime.app.R
import dev.mirutime.uikit.R as UiKitR
import dev.mirutime.core.log.MiruLog
import kotlinx.coroutines.launch
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruTabItem
import dev.mirutime.uikit.ThemeMode
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.design.components.ThemePickerRails
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruPlateBorder
import dev.mirutime.uikit.miruTopVeilBrush
import dev.mirutime.uikit.title
import dev.mirutime.app.features.book.data.PluginBookIndex
import dev.mirutime.app.features.book.ui.BookScreen
import dev.mirutime.app.features.dashboard.data.RoomDashboardDataProvider
import dev.mirutime.app.features.dashboard.ui.DashboardScreen
import dev.mirutime.app.features.dashboard.ui.HistoryScreen
import dev.mirutime.app.features.dashboard.ui.UpdatesScreen
import dev.mirutime.app.features.downloads.ui.DownloadsScreen
import dev.mirutime.app.features.finder.data.FinderSearchCache
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.finder.ui.FinderScreen
import dev.mirutime.app.features.finder.ui.GlobalSearchScreen
import dev.mirutime.app.features.finder.ui.ManageExtensionsScreen
import dev.mirutime.app.features.finder.ui.SourceCatalogScreen
import dev.mirutime.app.features.library.data.LibraryUpdateService
import dev.mirutime.app.features.library.data.RoomLibraryDataProvider
import dev.mirutime.app.features.library.ui.LibraryScreen
import dev.mirutime.app.features.migration.ui.MigrationRoutes
import dev.mirutime.app.features.migration.ui.MigrationScreen
import dev.mirutime.app.features.network.cf.CfSolveActivity
import dev.mirutime.app.features.reader.ui.AppReaderTranslationPanel
import dev.mirutime.feature.reader.ReaderOpenResolver
import dev.mirutime.feature.reader.api.ReaderScreen
import dev.mirutime.app.features.settings.backup.BackupOperationKind
import dev.mirutime.app.features.settings.backup.BackupOperationMonitor
import dev.mirutime.app.features.settings.ui.AboutSettingsScreen
import dev.mirutime.app.features.settings.ui.AppearanceSettingsScreen
import dev.mirutime.app.features.settings.ui.BackupSettingsScreen
import dev.mirutime.app.features.settings.ui.DataSettingsScreen
import dev.mirutime.app.features.settings.ui.LibrarySettingsScreen
import dev.mirutime.app.features.settings.ui.NetworkSettingsScreen
import dev.mirutime.app.features.settings.ui.NovelPromptsScreen
import dev.mirutime.app.features.settings.ui.NovelTranslationScreen
import dev.mirutime.app.features.settings.ui.PluginTranslationScreen
import dev.mirutime.app.features.settings.ui.ReaderSettingsScreen
import dev.mirutime.app.features.settings.ui.RegexRulesScreen
import dev.mirutime.app.features.settings.ui.SettingsRoutes
import dev.mirutime.app.features.settings.ui.SettingsScreen
import dev.mirutime.app.features.settings.ui.TranslationSettingsScreen
import dev.mirutime.app.features.settings.ui.UpdatesSettingsScreen
import dev.mirutime.app.features.settings.ui.settingsHeaderRes
import dev.mirutime.app.features.video.VideoPlayerActivity
import dev.mirutime.app.features.video.data.VideoSourceRepository
import dev.mirutime.app.features.video.domain.VideoRoutes
import dev.mirutime.app.features.video.domain.chapterRoute
import dev.mirutime.app.features.webview.ui.WebViewRoute
import dev.mirutime.app.features.webview.ui.WebViewScreen

private data class NavTab(val destination: MiruDestination, val icon: ImageVector)

private fun navigateFrom(navController: NavController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private val Tabs = listOf(
    NavTab(MiruDestination.Dashboard, Icons.Filled.Home),
    NavTab(MiruDestination.Library, Icons.Filled.MenuBook),
    NavTab(MiruDestination.Finder, Icons.Filled.Explore),
    NavTab(MiruDestination.Settings, Icons.Filled.Settings),
)

// App shell: pinned header on top, four-tab bottom navigation, standard
// system-plus-header back behavior. Extra themes live behind the header
// palette button; extra screens behind the overflow button.
@Composable
fun MiruShell(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    customBg: String?,
    bgLibrary: List<String>,
    onCustomBg: (String?) -> Unit,
    onRemoveBg: (String) -> Unit,
    // Notification "Open source": route straight to the in-app WebView so the
    // CF/login clearance lands in the same cookie store the solver uses.
    openWebViewUrl: String? = null,
    openWebViewTitle: String = "",
    onOpenWebViewHandled: () -> Unit = {},
    // Library-update notification: open the Updates list.
    openUpdates: Boolean = false,
    onOpenUpdatesHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val readerGraph = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).readerGraph
    }
    val pluginProvider = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).finderProvider
    }
    val pluginBookProvider = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).pluginBookProvider
    }
    val downloadEngine = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).downloadEngine
    }
    val videoDownloadManager = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).videoDownloadManager
    }
    val videoRepository = remember(context) {
        val app = context.applicationContext as dev.mirutime.app.MiruApp
        VideoSourceRepository(
            provider = app.pluginBookProvider,
            library = app.libraryRepository,
            pluginHeaders = app.pluginGraph::streamHeadersFor,
            offlineStream = app.videoDownloadManager::completedStream,
        )
    }
    val bookEpubExporter = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).bookEpubExporter
    }
    val migrationGraph = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).migrationGraph
    }
    val localBooksName = stringResource(R.string.finder_local_books_title)
    val localMangaName = stringResource(R.string.finder_local_manga_title)
    val libraryProvider = remember(context, localBooksName, localMangaName) {
        val app = context.applicationContext as dev.mirutime.app.MiruApp
        RoomLibraryDataProvider(
            library = app.libraryRepository,
            coverStore = app.coverStore,
            importer = app.localBookImporter,
            localFiles = app.localBookProvider,
            onBooksDeleted = { bookUrls -> app.removeVideoDownloads(bookUrls) },
            coverRefresh = { bookUrl -> pluginBookProvider.refreshCover(bookUrl) },
            sourceName = { id ->
                sourceDisplayName(
                    id = id,
                    pluginName = { pluginId ->
                        app.pluginGraph.installed.value.firstOrNull { it.id == pluginId }?.name
                    },
                    localBooks = localBooksName,
                    localManga = localMangaName,
                )
            },
        )
    }
    val dashboardProvider = remember(context, localBooksName, localMangaName) {
        val app = context.applicationContext as dev.mirutime.app.MiruApp
        RoomDashboardDataProvider(
            library = app.libraryRepository,
            coverStore = app.coverStore,
            sourceName = { id ->
                sourceDisplayName(
                    id = id,
                    pluginName = { pluginId ->
                        app.pluginGraph.installed.value.firstOrNull { it.id == pluginId }?.name
                    },
                    localBooks = localBooksName,
                    localManga = localMangaName,
                )
            },
        )
    }
    val updateService = remember(context) {
        LibraryUpdateService(
            provider = BookRefreshPortAdapter(pluginBookProvider),
            library = (context.applicationContext as dev.mirutime.app.MiruApp).libraryRepository,
        )
    }
    val shellScope = rememberCoroutineScope()
    // Route transitions only: one line per committed back-stack entry.
    val navEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(navEntry) {
        navEntry?.destination?.route?.let { route ->
            MiruLog.event("nav", "route", "route" to route)
        }
    }
    LaunchedEffect(openWebViewUrl) {
        val url = openWebViewUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        MiruLog.event(
            "nav",
            "open-webview",
            "host" to Uri.parse(url).host.orEmpty(),
            "source" to PluginBookIndex.sourceFor(url).orEmpty(),
        )
        navController.navigate(
            WebViewRoute.build(
                url = url,
                title = openWebViewTitle.ifBlank { Uri.parse(url).host.orEmpty() },
                sourceId = PluginBookIndex.sourceFor(url).orEmpty(),
            ),
        )
        // Consume the request so the same notification action can open it again.
        onOpenWebViewHandled()
    }
    LaunchedEffect(openUpdates) {
        if (!openUpdates) return@LaunchedEffect
        MiruLog.event("nav", "open-updates")
        navController.navigate("updates")
        onOpenUpdatesHandled()
    }
    var themeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var extrasSheetOpen by rememberSaveable { mutableStateOf(false) }
    // Bumped after a live restore so the already-composed library/dashboard
    // screens reload their provider reads without a restart. The operation
    // service owns restore, so the shell observes its result instead of
    // relying on the settings screen still being on screen.
    var dataRevision by remember { mutableIntStateOf(0) }
    // One invalidation path for every committed membership write: cached
    // catalogue/search snapshots carry inLibrary flags, so drop them and let
    // library/dashboard/Finder reload on the new revision. Never called for a
    // failed write.
    val onLibraryChanged: () -> Unit = {
        FinderSearchCache.clear()
        dataRevision++
        MiruLog.event("nav", "data-revision", "rev" to dataRevision, "reason" to "library")
    }
    LaunchedEffect(Unit) {
        var handledRestoreAt = 0L
        BackupOperationMonitor.state.collect { state ->
            if (state.kind == BackupOperationKind.Restore &&
                state.success == true &&
                state.finishedAt > handledRestoreAt
            ) {
                handledRestoreAt = state.finishedAt
                // A restored library invalidates cached inLibrary flags.
                FinderSearchCache.clear()
                dataRevision++
                MiruLog.event("nav", "data-revision", "rev" to dataRevision, "reason" to "restore")
            }
        }
    }
    // Registered by the web screen so the shared header back button can walk
    // the WebView history before leaving the route.
    var webViewBackAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Live page URL for the header subtitle; the route argument is only the
    // starting point, in-page links and history move it.
    var webViewCurrentUrl by remember { mutableStateOf("") }
    // One chrome for every theme: the header and the tab bar are floating
    // plates. Over a photo the screen behind them is transparent (the art
    // shows through); on classic themes it is the plain background.
    val hasBg = themeMode == ThemeMode.Custom && customBg != null

    // Read BEFORE the Scaffold consumes the insets; this is the only strip
    // the root overlay needs. A fixed 48dp covered the tab bar and reader.
    val navBottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = if (hasBg) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.background
        },
        topBar = {
            val entry by navController.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            // The reader ships its own chrome so fullscreen can free the top.
            // The video route is only a hand-off to VideoPlayerActivity; the
            // shell drops its chrome while that hand-off is on screen too.
            val onReader = route?.startsWith("reader/") == true ||
                VideoRoutes.isVideoRoute(route)
            val tab = Tabs.firstOrNull { it.destination.route == route }
            // The browser route carries its own source name and URL; the header
            // shows the name with the host underneath.
            val onWebView = route == WebViewRoute.Pattern
            val webViewTitle = if (onWebView) {
                entry?.arguments?.getString("title")?.let(Uri::decode).orEmpty()
            } else {
                ""
            }
            val webViewUrl = if (onWebView) {
                entry?.arguments?.getString("url")?.let(Uri::decode).orEmpty()
            } else {
                ""
            }
            val webViewDisplayUrl = webViewCurrentUrl.ifBlank { webViewUrl }
            val webViewHost = Uri.parse(webViewDisplayUrl).host.orEmpty()
            val webViewHeaderTitle = if (onWebView) {
                webViewTitle.ifBlank { webViewHost.ifBlank { webViewUrl } }
            } else {
                null
            }
            // The book route carries the encoded bookUrl; the header resolves
            // its display title through the shared catalog so every entry
            // point opens one book state.
            val bookUrl = entry?.arguments?.getString("bookUrl")?.let(Uri::decode)
            // Real plugin books cache their title when opened; fixtures fall
            // back to the shared catalogue (URL slug), so the header never
            // shows a synthesized title for a plugin book.
            val bookTitle = bookUrl?.let {
                PluginBookIndex.titleFor(it) ?: dev.mirutime.app.features.book.data.BookCatalog.titleFor(it)
            }
            // Source catalogue: header shows the source name.
            val sourceId = entry?.arguments?.getString("sourceId")?.let(Uri::decode)
            val sourceName = sourceId?.let { id ->
                sourceDisplayName(
                    id = id,
                    pluginName = { pluginId ->
                        (context.applicationContext as dev.mirutime.app.MiruApp)
                            .pluginGraph.installed.value.firstOrNull { it.id == pluginId }?.name
                    },
                    localBooks = stringResource(R.string.finder_local_books_title),
                    localManga = stringResource(R.string.finder_local_manga_title),
                )
            }
            val onGlobalSearch = route == "finder/search"
            val onManageExt = route == "finder/extensions"
            val settingsHeader = settingsHeaderRes(route)
            val onUpdates = route == "updates"
            val onHistory = route == "history"
            val onMigration = route == MigrationRoutes.Screen
            // Chrome layer: an eased veil under the floating plate so page
            // content sliding past the top fades out instead of reading raw
            // in the status-bar strip (photo and classic themes alike).
            if (!onReader) Box {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(miruTopVeilBrush(opaque = onMigration)),
                )
                AppHeader(
                    title = webViewHeaderTitle ?: bookTitle ?: sourceName ?: settingsHeader?.let { stringResource(it.first) } ?: if (onGlobalSearch) {
                        stringResource(R.string.finder_gs_title)
                    } else if (onManageExt) {
                        stringResource(R.string.finder_manage_extensions)
                    } else if (onUpdates) {
                        stringResource(R.string.header_updates)
                    } else if (onHistory) {
                        stringResource(R.string.header_history)
                    } else if (onMigration) {
                        stringResource(R.string.migration_title)
                    } else {
                        tab?.destination?.let { stringResource(it.titleRes) }
                            ?: stringResource(MiruDestination.Dashboard.titleRes)
                    },
                    description = if (onWebView) {
                        webViewHost.ifBlank { webViewDisplayUrl }
                    } else if (bookTitle != null) {
                        stringResource(R.string.header_book_desc)
                    } else if (settingsHeader != null) {
                        stringResource(settingsHeader.second)
                    } else if (sourceName != null) {
                        stringResource(R.string.finder_catalog_desc)
                    } else if (onGlobalSearch) {
                        stringResource(R.string.finder_gs_desc)
                    } else if (onManageExt) {
                        stringResource(R.string.finder_manage_desc)
                    } else if (onUpdates) {
                        stringResource(R.string.header_updates_desc)
                    } else if (onHistory) {
                        stringResource(R.string.header_history_desc)
                    } else if (onMigration) {
                        stringResource(R.string.migration_desc)
                    } else {
                        tab?.destination?.let { stringResource(it.descriptionRes) }
                            ?: stringResource(MiruDestination.Dashboard.descriptionRes)
                    },
                    isMainScreen = tab != null,
                    onBack = {
                        if (onWebView) {
                            webViewBackAction?.invoke() ?: navController.popBackStack()
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onTheme = { themeSheetOpen = true },
                    onExtras = { extrasSheetOpen = true },
                    opaque = onMigration,
                )
            }
        },
        bottomBar = {
            val entry by navController.currentBackStackEntryAsState()
            val currentRoute = entry?.destination?.route
            // The floating panel belongs to the four main tabs ONLY. Every
            // drill-in (book, updates, history, and any future one) is a
            // back-only flow and stays edge-to-edge: anything that is not a
            // main-tab route hides the panel by default, so new screens
            // inherit the rule automatically.
            val onMainTab = currentRoute == null ||
                Tabs.any { it.destination.route == currentRoute }
            AnimatedVisibility(
                visible = onMainTab,
                enter = fadeIn(animationSpec = tween(200)) +
                    expandVertically(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(150)) +
                    shrinkVertically(animationSpec = tween(150)),
            ) {
                Column {
                    // Fade the last content above the opaque plate instead of
                    // letting a card edge sit hard against the tab bar.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background,
                                    ),
                                ),
                            ),
                    )
                    MiruTabBar(
                        currentRoute = currentRoute,
                        navigate = { route -> navigateFrom(navController, route) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MiruDestination.Dashboard.route,
            // Every destination (not only bottom sheets) lifts above the IME, so
            // full-screen text forms never have their fields/actions covered.
            modifier = Modifier.imePadding(),
            // Tab switches read as a quick settle, not a muddy blend: the old
            // screen fades out fast while shrinking a touch, the new one
            // fades in at full size. Same curve everywhere incl. drill-ins.
            enterTransition = {
                fadeIn(animationSpec = tween(250)) +
                    scaleIn(initialScale = 0.98f, animationSpec = tween(250))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(150)) +
                    scaleOut(targetScale = 0.98f, animationSpec = tween(150))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(250)) +
                    scaleIn(initialScale = 0.98f, animationSpec = tween(250))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(150)) +
                    scaleOut(targetScale = 0.98f, animationSpec = tween(150))
            },
        ) {
            composable(MiruDestination.Dashboard.route) {
                DashboardScreen(
                    contentPadding = padding,
                    provider = dashboardProvider,
                    refreshKey = dataRevision,
                    onBookOpen = { title ->
                        navController.navigate("book/${Uri.encode(title)}")
                    },
                    onUpdatesOpen = {
                        navController.navigate("updates")
                    },
                    onHistoryOpen = {
                        navController.navigate("history")
                    },
                )
            }
            composable(MiruDestination.Library.route) {
                LibraryScreen(
                    contentPadding = padding,
                    readerGraph = readerGraph,
                    provider = libraryProvider,
                    refreshKey = dataRevision,
                    onBookOpen = { title ->
                        navController.navigate("book/${Uri.encode(title)}")
                    },
                )
            }
            composable(MiruDestination.Finder.route) {
                FinderScreen(
                    contentPadding = padding,
                    provider = pluginProvider,
                    refreshKey = dataRevision,
                    onSourceOpen = { id ->
                        navController.navigate("finder/source/${Uri.encode(id)}")
                    },
                    onLocalOpen = { kind ->
                        navController.navigate(
                            "finder/source/${Uri.encode(LocalSources.idOfKind(kind))}",
                        )
                    },
                    onSearchOpen = { navController.navigate("finder/search") },
                    onManageExtensions = { navController.navigate("finder/extensions") },
                    onLibraryOpen = { navigateFrom(navController, MiruDestination.Library.route) },
                )
            }
            composable("finder/extensions") {
                ManageExtensionsScreen(contentPadding = padding, provider = pluginProvider)
            }
            composable(MiruDestination.Settings.route) {
                val readerConfig by readerGraph.config.config.collectAsState()
                SettingsScreen(
                    contentPadding = padding,
                    onOpen = { route -> navController.navigate(route) },
                    readerStyle = readerConfig.style,
                )
            }
            composable(SettingsRoutes.Appearance) {
                AppearanceSettingsScreen(
                    contentPadding = padding,
                    themeMode = themeMode,
                    onThemeMode = onThemeMode,
                    customBg = customBg,
                    bgLibrary = bgLibrary,
                    onCustomBg = onCustomBg,
                    onRemoveBg = onRemoveBg,
                )
            }
            composable(SettingsRoutes.Library) { LibrarySettingsScreen(contentPadding = padding) }
            composable(SettingsRoutes.Updates) { UpdatesSettingsScreen(contentPadding = padding) }
            composable(SettingsRoutes.Downloads) {
                DownloadsScreen(
                    contentPadding = padding,
                    onOpenBook = { bookUrl -> navController.navigate("book/${Uri.encode(bookUrl)}") },
                    onOpenSource = { bookId, sourceUrl ->
                        // Same CF/login bridge as the notification action:
                        // solve in the shared WebView, store the clearance and
                        // re-arm this download. No second solver path.
                        context.startActivity(
                            Intent(context, CfSolveActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra(CfSolveActivity.EXTRA_BOOK_ID, bookId)
                                putExtra(CfSolveActivity.EXTRA_SOURCE_URL, sourceUrl)
                            },
                        )
                    },
                )
            }
            composable(SettingsRoutes.Reader) {
                ReaderSettingsScreen(
                    contentPadding = padding,
                    readerGraph = readerGraph,
                )
            }
            composable(SettingsRoutes.Translation) {
                TranslationSettingsScreen(
                    contentPadding = padding,
                    onOpen = { route -> navController.navigate(route) },
                )
            }
            composable(SettingsRoutes.NovelPrompts) { NovelPromptsScreen(contentPadding = padding) }
            composable(SettingsRoutes.NovelTranslation) {
                NovelTranslationScreen(contentPadding = padding, provider = libraryProvider)
            }
            composable(SettingsRoutes.PluginTranslation) {
                PluginTranslationScreen(contentPadding = padding, provider = pluginProvider)
            }
            composable(
                route = SettingsRoutes.RegexPattern,
                arguments = listOf(
                    navArgument("novel") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                RegexRulesScreen(
                    novelUrl = entry.arguments?.getString("novel")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(Uri::decode),
                    contentPadding = padding,
                )
            }
            composable(SettingsRoutes.Network) { NetworkSettingsScreen(contentPadding = padding) }
            composable(SettingsRoutes.Data) { DataSettingsScreen(contentPadding = padding) }
            composable(SettingsRoutes.Backup) {
                BackupSettingsScreen(contentPadding = padding)
            }
            composable(SettingsRoutes.About) { AboutSettingsScreen(contentPadding = padding) }
            composable(
                route = "finder/search?q={q}",
                arguments = listOf(
                    navArgument("q") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                GlobalSearchScreen(
                    contentPadding = padding,
                    provider = pluginProvider,
                    initialQuery = entry.arguments?.getString("q")?.let(Uri::decode).orEmpty(),
                    onBookOpen = { bookUrl ->
                        navController.navigate("book/${Uri.encode(bookUrl)}")
                    },
                    onOpenCatalogue = { id, query ->
                        navController.navigate(
                            "finder/source/${Uri.encode(id)}?q=${Uri.encode(query)}",
                        )
                    },
                    onLibraryChanged = onLibraryChanged,
                )
            }
            composable(
                route = "finder/source/{sourceId}?q={q}",
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("q") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val sourceId = entry.arguments?.getString("sourceId")?.let(Uri::decode).orEmpty()
                val installed by (context.applicationContext as dev.mirutime.app.MiruApp)
                    .pluginGraph.installed.collectAsState()
                val sourceName = installed.firstOrNull { it.id == sourceId }?.name
                SourceCatalogScreen(
                    sourceId = sourceId,
                    sourceName = sourceName,
                    initialQuery = entry.arguments?.getString("q")?.let(Uri::decode).orEmpty(),
                    contentPadding = padding,
                    provider = pluginProvider,
                    readerGraph = readerGraph,
                    onBookOpen = { bookUrl ->
                        navController.navigate("book/${Uri.encode(bookUrl)}")
                    },
                    onSiteOpen = { siteUrl ->
                        navController.navigate(
                            WebViewRoute.build(
                                siteUrl,
                                sourceName ?: sourceId,
                                sourceId = sourceId,
                            ),
                        )
                    },
                    onLibraryChanged = onLibraryChanged,
                    onSolveCf = { challenge ->
                        val graph = (context.applicationContext as dev.mirutime.app.MiruApp)
                            .pluginGraph
                        val target = challenge.url ?: graph.baseUrlOverride(sourceId)
                        if (target == null) {
                            false
                        } else {
                            val solution = graph.cfSolver.solveVisible(
                                dev.mirutime.core.host.CfChallenge(
                                    host = challenge.host,
                                    url = target,
                                    status = challenge.status ?: 0,
                                    markers = emptyList(),
                                    referer = challenge.referer,
                                    userAgent = graph.userAgentFor(target),
                                    isXhr = challenge.isXhr,
                                    cfMitigated = false,
                                ),
                            )
                            solution != null
                        }
                    },
                )
            }
            composable(
                route = WebViewRoute.Pattern,
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType },
                    navArgument("title") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("bookUrl") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("sourceId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val startUrl = entry.arguments?.getString("url")?.let(Uri::decode).orEmpty()
                val bookUrl = entry.arguments?.getString("bookUrl")?.let(Uri::decode).orEmpty()
                val sourceId = entry.arguments?.getString("sourceId")?.let(Uri::decode).orEmpty()
                // Read the current manager on navigation: a provider or key
                // change rebuilds it and the next browser session picks it up.
                val translationManager = (context.applicationContext as dev.mirutime.app.MiruApp)
                    .translationGraph
                    .manager
                WebViewScreen(
                    url = startUrl,
                    contentPadding = padding,
                    translationManager = translationManager,
                    bookUrl = bookUrl,
                    sourceId = sourceId,
                    onClose = {
                        webViewCurrentUrl = ""
                        navController.popBackStack()
                    },
                    onBackAction = { webViewBackAction = it },
                    onUrlChange = { webViewCurrentUrl = it },
                )
            }
            composable(
                route = "book/{bookUrl}",
                arguments = listOf(navArgument("bookUrl") { type = NavType.StringType }),
            ) { entry ->
                val bookUrl = entry.arguments?.getString("bookUrl")?.let(Uri::decode).orEmpty()
                BookScreen(
                    bookUrl = bookUrl,
                    contentPadding = padding,
                    readerGraph = readerGraph,
                    provider = pluginBookProvider,
                    exporter = bookEpubExporter,
                    downloadEngine = downloadEngine,
                    videoDownloads = videoDownloadManager,
                    videoRepository = videoRepository,
                    libraryProvider = libraryProvider,
                    onOpenRegex = { novelUrl ->
                        navController.navigate("${SettingsRoutes.Regex}?novel=${Uri.encode(novelUrl)}")
                    },
                    onOpenWebView = { url, title ->
                        navController.navigate(
                            WebViewRoute.build(
                                url,
                                title,
                                bookUrl = bookUrl,
                                sourceId = PluginBookIndex.sourceFor(bookUrl).orEmpty(),
                            ),
                        )
                    },
                    // Tapping a book title searches its ORIGINAL name and starts
                    // immediately, even when the title is displayed translated.
                    onOpenSearch = { query ->
                        navController.navigate("finder/search?q=${Uri.encode(query)}")
                    },
                    migration = migrationGraph,
                    onOpenBook = { targetUrl ->
                        navController.navigate("book/${Uri.encode(targetUrl)}")
                    },
                    onOpenReader = { url, index, restore ->
                        MiruLog.event(
                            "nav",
                            "open-reader",
                            "book" to MiruLog.idHash(url),
                            "chapter" to index,
                            "restore" to restore,
                        )
                        navController.navigate("reader/${Uri.encode(url)}/$index?restore=$restore")
                    },
                    onOpenVideo = { url, index ->
                        MiruLog.event(
                            "nav",
                            "open-video",
                            "book" to MiruLog.idHash(url),
                            "chapter" to index,
                        )
                        navController.navigate(chapterRoute(url, index, isVideo = true))
                    },
                    // One bump per committed membership change; library and
                    // dashboard reload from their providers on this key.
                    // Catalogue/search snapshots carry inLibrary flags, so
                    // drop them rather than replay a stale badge.
                    onLibraryChanged = onLibraryChanged,
                    // A successful book open clears its new-chapter badge and
                    // refreshes the library/dashboard/updates views.
                    onBookOpened = { openedUrl ->
                        shellScope.launch {
                            updateService.markSeen(openedUrl)
                            dataRevision++
                            MiruLog.event(
                                "nav",
                                "data-revision",
                                "rev" to dataRevision,
                                "reason" to "book-opened",
                            )
                        }
                    },
                )
            }
            composable(
                route = VideoRoutes.Pattern,
                arguments = listOf(
                    navArgument("bookUrl") { type = NavType.StringType },
                    navArgument("chapterIndex") { type = NavType.IntType },
                ),
            ) { entry ->
                val bookUrl = entry.arguments?.getString("bookUrl")?.let(Uri::decode).orEmpty()
                val index = entry.arguments?.getInt("chapterIndex") ?: 1
                val videoContext = LocalContext.current
                // The route exists for navigation/back-stack semantics; the
                // actual playback lives in the configChanges activity. Pop the
                // route immediately so back from the player lands on the book
                // or dashboard that launched it.
                LaunchedEffect(bookUrl, index) {
                    runCatching {
                        videoContext.startActivity(
                            VideoPlayerActivity.intent(videoContext, bookUrl, index),
                        )
                    }
                    navController.popBackStack()
                }
                Box(modifier = Modifier.fillMaxSize())
            }
            composable(
                route = "reader/{bookUrl}/{chapterIndex}?restore={restore}",
                arguments = listOf(
                    navArgument("bookUrl") { type = NavType.StringType },
                    navArgument("chapterIndex") { type = NavType.IntType },
                    navArgument("restore") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                val bookUrl = entry.arguments?.getString("bookUrl")?.let(Uri::decode).orEmpty()
                // rememberSaveable survives process death: on re-entry the flag
                // is already true, so the reader restores its saved anchor
                // instead of the chapter the route was first opened on.
                var openedBefore by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) { openedBefore = true }
                ReaderScreen(
                    bookUrl = bookUrl,
                    startChapterIndex = entry.arguments?.getInt("chapterIndex") ?: 1,
                    restoreSaved = entry.arguments?.getBoolean("restore") ?: false,
                    reopened = openedBefore,
                    onBack = { navController.popBackStack() },
                    onOpenRegex = { novelUrl ->
                        navController.navigate("${SettingsRoutes.Regex}?novel=${Uri.encode(novelUrl)}")
                    },
                    onOpenWebView = { url, title ->
                        navController.navigate(
                            WebViewRoute.build(
                                url,
                                title,
                                bookUrl = bookUrl,
                                sourceId = PluginBookIndex.sourceFor(bookUrl).orEmpty(),
                            ),
                        )
                    },
                    graph = readerGraph,
                    translationPanel = { url, dismiss ->
                        AppReaderTranslationPanel(url, dismiss)
                    },
                )
            }
            composable("updates") {
                UpdatesScreen(
                    contentPadding = padding,
                    provider = dashboardProvider,
                    refreshKey = dataRevision,
                    onRefresh = { onProgress -> updateService.checkAll(onProgress) },
                    onOpenSettings = { navController.navigate(SettingsRoutes.Updates) },
                    onBookOpen = { url ->
                        navController.navigate("book/${Uri.encode(url)}")
                    },
                )
            }
            composable(MigrationRoutes.Screen) {
                MigrationScreen(
                    contentPadding = padding,
                    graph = migrationGraph,
                    onLibraryChanged = onLibraryChanged,
                    onOpenBook = { targetUrl ->
                        navController.navigate("book/${Uri.encode(targetUrl)}")
                    },
                    onOpenCatalogue = { id, query ->
                        navController.navigate(
                            "finder/source/${Uri.encode(id)}?q=${Uri.encode(query)}",
                        )
                    },
                )
            }
            composable("history") {
                HistoryScreen(
                    contentPadding = padding,
                    provider = dashboardProvider,
                    onBookOpen = { title ->
                        navController.navigate("book/${Uri.encode(title)}")
                    },
                )
            }
        }
    }
    // Root-level opaque strip over the gesture inset. Scaffold's content can
    // draw above its bottomBar in this layout; this strip is composed after
    // the Scaffold, so a card edge can never show under the navigation area.
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(navBottom)
            .background(MaterialTheme.colorScheme.surface),
    )
    }

    if (themeSheetOpen) {
        AppBottomSheet(
            title = stringResource(UiKitR.string.theme_title),
            description = stringResource(UiKitR.string.theme_desc),
            onClose = { themeSheetOpen = false },
            doneLabel = stringResource(R.string.lib_done),
            onDone = { themeSheetOpen = false },
        ) {
            SheetSection(title = stringResource(UiKitR.string.theme_section_scheme)) {
                ThemePickerRails(
                    selected = themeMode,
                    customBg = customBg,
                    bgLibrary = bgLibrary,
                    onSelect = { onThemeMode(it) },
                    onCustomBg = onCustomBg,
                    onRemoveBg = onRemoveBg,
                )
            }
        }
    }

    if (extrasSheetOpen) {
        AppBottomSheet(
            title = stringResource(R.string.header_more),
            description = stringResource(R.string.header_more_desc),
            onClose = { extrasSheetOpen = false },
        ) {
            MiruActionRow(
                icon = Icons.Filled.Download,
                title = stringResource(R.string.st_downloads),
                subtitle = stringResource(R.string.st_downloads_summary),
                onClick = {
                    extrasSheetOpen = false
                    navController.navigate(SettingsRoutes.Downloads)
                },
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.SwapHoriz,
                title = stringResource(R.string.extra_migration),
                subtitle = stringResource(R.string.extra_migration_desc),
                onClick = {
                    extrasSheetOpen = false
                    navController.navigate(MigrationRoutes.Screen)
                },
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Code,
                title = stringResource(R.string.extra_regex),
                subtitle = stringResource(R.string.st_regex_header_desc),
                onClick = {
                    extrasSheetOpen = false
                    navController.navigate(SettingsRoutes.Regex)
                },
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Extension,
                title = stringResource(R.string.extra_extensions),
                subtitle = stringResource(R.string.finder_manage_desc),
                onClick = {
                    extrasSheetOpen = false
                    navController.navigate("finder/extensions")
                },
            )
        }
    }
}

// Floating tab bar, icons only: ONE plate holding the four destinations.
// The selected item is a 40dp circle behind the 22dp icon; no text labels
// (content descriptions keep TalkBack covered). Each item keeps a full 48dp
// touch height so the bar stays easy to hit.
@Composable
private fun MiruTabBar(
    currentRoute: String?,
    navigate: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            // Floating plate: 8dp above the gesture bar, with the nav inset
            // inside the surface. The root strip paints only the inset.
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        shape = RoundedCornerShape(MiruRadius.Sheet),
        // Opaque backing: content scrolling past the floating panel must
        // never be readable under it. The navigation inset lives INSIDE the
        // surface so the plate also covers the gesture-bar strip.
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = miruCardElevation(),
        border = miruPlateBorder(),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tabs.forEach { tab ->
                val selected = tab.destination.route == currentRoute
                MiruTabItem(
                    label = stringResource(tab.destination.titleRes),
                    icon = tab.icon,
                    selected = selected,
                    onClick = { navigate(tab.destination.route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
