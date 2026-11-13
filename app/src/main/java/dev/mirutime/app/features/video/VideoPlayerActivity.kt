package dev.mirutime.app.features.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import dev.mirutime.app.MiruApp
import dev.mirutime.app.design.loadThemePrefs
import dev.mirutime.app.features.reader.domain.ReadingSessionTracker
import dev.mirutime.app.features.video.data.VideoSourceRepository
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.ui.uiLocale
import dev.mirutime.app.features.video.domain.VideoPlaybackSession
import dev.mirutime.app.features.video.ui.VideoPlayerScreen
import dev.mirutime.uikit.MiruTheme
import dev.mirutime.uikit.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Playback host. The app stays portrait; the PlayerView fullscreen button
 * flips this activity to sensor-landscape, and configChanges keeps the player
 * alive across that toggle. There is no background playback/service: the
 * player pauses and saves on onPause/onStop.
 */
class VideoPlayerActivity : ComponentActivity() {
    private data class VideoRequest(val bookUrl: String, val chapterIndex: Int)

    private var request by mutableStateOf(VideoRequest("", 1))
    private var player: ExoPlayer? = null
    // The chapter the live player belongs to; `request` may already point at
    // the next episode while the old player is disposing.
    private var playerChapterIndex by mutableIntStateOf(1)
    private var repository: VideoSourceRepository? = null

    // Watch time lands in reading_sessions as "reading" so the existing Book
    // and Dashboard aggregates include video without a new kind/schema.
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackSession: VideoPlaybackSession by lazy {
        val repo = (application as MiruApp).libraryRepository
        VideoPlaybackSession(
            tracker = ReadingSessionTracker(
                resolveIds = { bookUrl, chapterIndex ->
                    repo.book(bookUrl)?.let { book ->
                        book.bookId to repo.chapterByIndex(book.bookId, chapterIndex)?.chapterId
                    }
                },
                saveSession = { repo.saveSession(it) },
            ),
            scope = sessionScope,
        )
    }

    // Same per-app language wrap MainActivity uses; otherwise the player
    // would fall back to the system locale while the rest of the app is RU/EN.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase, AppSettings.appLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        val next = readRequest(intent)
        if (next == null) {
            finish()
            return
        }
        request = next

        val app = application as MiruApp
        repository = VideoSourceRepository(
            provider = app.pluginBookProvider,
            library = app.libraryRepository,
            pluginHeaders = app.pluginGraph::streamHeadersFor,
            offlineStream = app.videoDownloadManager::completedStream,
        )
        val theme = loadThemePrefs(applicationContext).first
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.Night

        setContent {
            MiruTheme(mode = theme, locale = uiLocale()) {
                VideoPlayerScreen(
                    repository = repository!!,
                    videoDownloads = app.videoDownloadManager,
                    bookUrl = request.bookUrl,
                    chapterIndex = request.chapterIndex,
                    onBack = { finish() },
                    onPlayer = { player = it },
                    onPlayerChapter = { playerChapterIndex = it },
                    // Prev/next must move the request state: it is the
                    // screen's chapter key, so the source reloads.
                    onChapterIndex = { request = request.copy(chapterIndex = it) },
                    onFullscreen = ::setFullscreen,
                    onPlaybackActive = { playing, chapter ->
                        if (playing) {
                            playbackSession.onPlaying(request.bookUrl, chapter)
                        } else {
                            playbackSession.onStopped()
                        }
                    },
                    // The player surface leaves composition on a chapter
                    // switch; lifecycleScope keeps the progress write alive.
                    onPersistProgress = { bookUrl, index, position, duration ->
                        val repo = repository
                        if (repo != null) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                repo.saveProgress(bookUrl, index, position, duration)
                            }
                        }
                    },
                )
            }
        }
        applySystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = readRequest(intent) ?: return
        persistProgress()
        request = next
    }

    override fun onPause() {
        player?.pause()
        playbackSession.onStopped()
        persistProgress()
        super.onPause()
    }

    override fun onStop() {
        player?.pause()
        playbackSession.onStopped()
        persistProgress()
        super.onStop()
    }

    override fun onDestroy() {
        playbackSession.shutdown()
        persistProgress()
        player = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
    }

    private fun setFullscreen(enter: Boolean) {
        requestedOrientation = if (enter) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun applySystemBars() {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // The player is always a dark surface: light system-bar icons in
        // portrait, hidden bars in fullscreen landscape.
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (landscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun persistProgress() {
        val current = player ?: return
        val repo = repository ?: return
        val bookUrl = request.bookUrl
        if (bookUrl.isBlank()) return
        val index = playerChapterIndex
        val position = current.currentPosition.coerceAtLeast(0)
        val duration = current.duration.takeIf { it > 0 } ?: 0
        if (position == 0L && duration == 0L) return
        lifecycleScope.launch(Dispatchers.IO) {
            repo.saveProgress(bookUrl, index, position, duration)
        }
    }

    private fun readRequest(intent: Intent?): VideoRequest? {
        val bookUrl = intent?.getStringExtra(EXTRA_BOOK_URL)?.takeIf { it.isNotBlank() }
            ?: return null
        val chapter = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0).takeIf { it > 0 } ?: return null
        return VideoRequest(bookUrl, chapter)
    }

    companion object {
        private const val EXTRA_BOOK_URL = "dev.mirutime.app.video.BOOK_URL"
        private const val EXTRA_CHAPTER_INDEX = "dev.mirutime.app.video.CHAPTER_INDEX"

        fun intent(context: Context, bookUrl: String, chapterIndex: Int): Intent =
            Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_BOOK_URL, bookUrl)
                putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
            }
    }
}
