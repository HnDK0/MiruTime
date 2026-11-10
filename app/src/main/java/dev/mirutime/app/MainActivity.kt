package dev.mirutime.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import dev.mirutime.core.log.MiruFileLogger
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruTheme
import dev.mirutime.uikit.ThemeBackground
import dev.mirutime.uikit.ThemeMode
import dev.mirutime.app.design.deleteBackgroundFile
import dev.mirutime.app.design.isPresetBackgroundPath
import dev.mirutime.app.design.loadBgLibrary
import dev.mirutime.app.design.loadThemePrefs
import dev.mirutime.app.design.rememberBackgroundBitmap
import dev.mirutime.app.design.saveBgLibrary
import dev.mirutime.app.design.saveThemePrefs
import dev.mirutime.app.design.themePrefsChanges
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.webview.ui.LocalWebUserAgent
import dev.mirutime.app.shell.MiruShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Real entry: theme state hoisted here so the header sheet and the settings
// screen drive the same app theme. Mode + custom background URI persist in
// SharedPreferences (stdlib, no datastore dep); saved backgrounds are copied
// into app storage and listed in the same prefs. Portrait is locked.
class MainActivity : ComponentActivity() {
    // Notification "Open source" arrives as a new intent; Compose observes
    // this state and routes the in-app WebView once per value.
    private val pendingOpenUrl = mutableStateOf<String?>(null)
    private val pendingOpenTitle = mutableStateOf("")
    // Library-update notification: route to the Updates screen once per intent.
    private val pendingOpenUpdates = mutableStateOf(false)
    private var navController: androidx.navigation.NavHostController? = null

    // The app language is applied before the activity's resources are built,
    // so the first frame already uses the saved locale (no AppCompat).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase, AppSettings.appLanguage))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        navController?.currentBackStackEntry?.destination?.route?.let { route ->
            outState.putString("saved_route", route)
        }
    }

    private fun openFromIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATES, false) == true) {
            pendingOpenUpdates.value = true
        }
        val url = intent?.getStringExtra(EXTRA_OPEN_URL)?.takeIf { it.isNotBlank() } ?: return
        pendingOpenTitle.value = intent.getStringExtra(EXTRA_OPEN_TITLE).orEmpty()
        pendingOpenUrl.value = url
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: ПРОЗРАЧНЫЕ системные бары без окраски ОС (которая до
        // этого «накладывала цвет» поверх темы над навигацией Android) и без
        // 3-кнопочного контраст-скрима. Insets поглощают родительские панели
        // (AppHeader → statusBars, MiruTabBar → navigationBars).
        WindowCompat.enableEdgeToEdge(window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        openFromIntent(intent)
        val initialRoute = savedInstanceState?.getString("saved_route")
        val (savedMode, savedBg) = loadThemePrefs(applicationContext)
        val savedLibrary = loadBgLibrary(applicationContext, savedBg)
        setContent {
            // Hoisted so the NavController is created before the DB gate and
            // survives process death / restore (otherwise the back stack resets
            // to the start destination on restore).
            val navController = rememberNavController()
            this.navController = navController
            // Route persistence handled by onSaveInstanceState + initialRoute.
            // Release builds no longer have a destructive DB fallback. Probe on
            // IO so an incompatible file shows a blocking message instead of
            // crashing in composition (and without blocking onCreate).
            var databaseFailure by remember { mutableStateOf<Throwable?>(null) }
            var databaseChecked by remember { mutableStateOf(false) }
            var databaseAttempt by remember { mutableStateOf(0) }
            LaunchedEffect(databaseAttempt) {
                databaseChecked = false
                databaseFailure = withContext(Dispatchers.IO) {
                    runCatching {
                        (application as MiruApp).probeLibraryDatabase()
                    }.exceptionOrNull()
                }
                databaseChecked = true
            }
            if (!databaseChecked) {
                DatabaseLoadingScreen()
                return@setContent
            }
            if (databaseFailure != null) {
                DatabaseUnavailableScreen(
                    error = databaseFailure!!,
                    onRetry = { databaseAttempt++ },
                )
                return@setContent
            }
            // Restore applies the saved language in memory only; a restart is
            // required for the wrapped resources to pick it up. Compare with
            // the locale actually applied so this never loops.
            val appLanguage = AppSettings.appLanguage
            LaunchedEffect(appLanguage) {
                val current = this@MainActivity.resources.configuration.locales[0]
                if (AppLanguage.needsRecreate(current, appLanguage)) recreate()
            }
            var themeMode by rememberSaveable {
                mutableStateOf(savedMode?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.Paper)
            }
            var customBg by rememberSaveable { mutableStateOf(savedBg) }
            // Saved backgrounds (local copies, see ThemeStorage.kt). The
            // active one is always a member; the order is the order of adding.
            var bgLibrary by remember { mutableStateOf(savedLibrary) }
            fun persist() = saveThemePrefs(applicationContext, themeMode.name, customBg)
            // A live restore writes theme prefs outside this composition.
            val themeRevision by themePrefsChanges.collectAsState()
            LaunchedEffect(themeRevision) {
                if (themeRevision == 0L) return@LaunchedEffect
                val (mode, bg) = loadThemePrefs(applicationContext)
                themeMode = mode
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.Paper
                customBg = bg
                bgLibrary = loadBgLibrary(applicationContext, bg)
            }
            val bgSource = if (themeMode == ThemeMode.Custom) customBg else null
            val bgBitmap = rememberBackgroundBitmap(bgSource)
            MiruTheme(
                mode = themeMode,
                background = bgSource?.let { ThemeBackground.Image(it) } ?: ThemeBackground.None,
                backgroundBitmap = bgBitmap,
            ) {
                // Иконки системных баров следуют ЗА темой приложения (она
                // живёт здесь, минуя day/night флаги окна): светлый фон темы
                // → тёмные иконки баров, тёмный → светлые. Считаем от живой
                // схемы (внутри MaterialTheme), так верно для всех 24 тем
                // (вкл. Custom от фото), без поддержания ручных списков.
                val bgLum = MaterialTheme.colorScheme.background.luminance()
                val lightTheme = bgLum > 0.5f
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = lightTheme
                    controller.isAppearanceLightNavigationBars = lightTheme
                }
                // Covers resolve their own cookies/headers in the Coil
                // NetworkClient; only UI scale is provided here.
                val baseDensity = LocalDensity.current
                val textScale = AppSettings.textScale
                val uiScale = AppSettings.uiScale
                val scaledDensity = remember(baseDensity, textScale, uiScale) {
                    Density(
                        density = baseDensity.density * uiScale / 100f,
                        fontScale = baseDensity.fontScale * textScale / 100f,
                    )
                }
                // The in-app browser presents the same UA the plugin client
                // sends, or its cf_clearance is bound to a different fingerprint.
                val userAgentFor = remember {
                    val graph = (application as MiruApp).pluginGraph
                    graph::userAgentFor
                }
                CompositionLocalProvider(
                    LocalDensity provides scaledDensity,
                    LocalWebUserAgent provides userAgentFor,
                ) {
                    MiruShell(
                        navController = navController,
                        initialRoute = initialRoute,
                        themeMode = themeMode,
                        onThemeMode = {
                            themeMode = it
                            persist()
                        },
                        openWebViewUrl = pendingOpenUrl.value,
                        openWebViewTitle = pendingOpenTitle.value,
                        onOpenWebViewHandled = { pendingOpenUrl.value = null },
                        openUpdates = pendingOpenUpdates.value,
                        onOpenUpdatesHandled = { pendingOpenUpdates.value = false },
                        customBg = customBg,
                        bgLibrary = bgLibrary,
                        onCustomBg = { source ->
                            // Presets are applied in place and never join the library.
                            if (
                                source != null &&
                                !isPresetBackgroundPath(applicationContext, source) &&
                                source !in bgLibrary
                            ) {
                                bgLibrary = bgLibrary + source
                                saveBgLibrary(applicationContext, bgLibrary)
                            }
                            customBg = source
                            persist()
                        },
                        onRemoveBg = { source ->
                            bgLibrary = bgLibrary - source
                            saveBgLibrary(applicationContext, bgLibrary)
                            deleteBackgroundFile(applicationContext, source)
                            if (customBg == source) {
                                customBg = null
                                // Only the active Custom theme loses its picture;
                                // any other theme the user is on stays.
                                if (themeMode == ThemeMode.Custom) themeMode = ThemeMode.Paper
                                persist()
                            }
                        },
                    )
                }
            }
        }
    }

@Composable
private fun DatabaseLoadingScreen() {
    MiruTheme(mode = ThemeMode.Paper) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun DatabaseUnavailableScreen(
    error: Throwable,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val logPath = remember {
        MiruFileLogger.logFile()?.absolutePath
            ?: MiruFileLogger.currentDirectory()?.absolutePath
    }
    MiruTheme(mode = ThemeMode.Paper) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MiruSpace.L),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.db_error_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(MiruSpace.S))
            Text(
                text = stringResource(R.string.db_error_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(MiruSpace.S))
            Text(
                text = error.message ?: error::class.java.simpleName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (logPath != null) {
                Spacer(Modifier.height(MiruSpace.S))
                Text(
                    text = logPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(MiruSpace.S))
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                MiruButton(
                    onClick = {
                        val text = runCatching {
                            MiruFileLogger.logFile()?.readText()?.takeLast(64 * 1024)
                        }.getOrNull().orEmpty()
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text.ifBlank { logPath.orEmpty() })
                        }
                        context.startActivity(Intent.createChooser(share, null))
                    },
                    enabled = logPath != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.db_error_share_log))
                }
                MiruButton(
                    onClick = onRetry,
                    style = MiruButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.db_error_retry))
                }
            }
        }
    }
}

    companion object {
        const val EXTRA_OPEN_URL = "dev.mirutime.app.OPEN_URL"
        const val EXTRA_OPEN_TITLE = "dev.mirutime.app.OPEN_TITLE"
        const val EXTRA_OPEN_UPDATES = "dev.mirutime.app.OPEN_UPDATES"
    }
}
