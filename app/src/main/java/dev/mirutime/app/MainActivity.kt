package dev.mirutime.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
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
        val (savedMode, savedBg) = loadThemePrefs(applicationContext)
        val savedLibrary = loadBgLibrary(applicationContext, savedBg)
        setContent {
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

    companion object {
        const val EXTRA_OPEN_URL = "dev.mirutime.app.OPEN_URL"
        const val EXTRA_OPEN_TITLE = "dev.mirutime.app.OPEN_TITLE"
        const val EXTRA_OPEN_UPDATES = "dev.mirutime.app.OPEN_UPDATES"
    }
}
