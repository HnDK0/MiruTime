package dev.mirutime.feature.tts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import dev.mirutime.core.log.MiruLog
import dev.mirutime.feature.tts.R
import dev.mirutime.feature.tts.TtsController
import dev.mirutime.feature.tts.TtsNotificationInfo
import dev.mirutime.feature.tts.TtsPlaybackIntent
import dev.mirutime.feature.tts.TtsSleepTimerMode
import dev.mirutime.feature.tts.TtsState
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.TtsTransportAction
import dev.mirutime.feature.tts.TtsTransportCommand
import dev.mirutime.feature.tts.ttsNotificationInfo
import dev.mirutime.feature.tts.ttsProgressPercent
import dev.mirutime.feature.tts.ttsSleepTimerNotificationKey
import dev.mirutime.feature.tts.ttsTransportCommand
import java.io.File
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground wrapper around the in-app TtsController: keeps speech alive while
 * the reader is backgrounded, exposes transport controls through a platform
 * MediaSession, and owns audio focus, noisy-route handling and the wake lock.
 * Platform service only; no media3 dependency.
 */
class TtsPlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession
    private lateinit var wakeLock: PowerManager.WakeLock

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var started = false
    private var foregroundStarted = false
    private var bookTitle = ""
    private var chapterTitle = ""
    private var chapterNumber = 0
    private var chapterCount = 0
    private var coverUrl = ""
    private var coverKey: String? = null
    private var coverBitmap: Bitmap? = null
    private var coverJob: Job? = null
    private var publishedKey: String? = null
    private var mediaKey: String? = null

    // Set from TtsServiceHost in onCreate. The app-scoped graph attaches the
    // controller before starting this service; after process death the host is
    // empty and the service stops instead of resurrecting a dead session.
    private lateinit var ttsController: TtsController
    private var overlay: TtsOverlayController? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isActive()) {
                MiruLog.event("tts", "noisy-receiver")
                ttsController.pauseFromSystem()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        MiruLog.event("tts", "audio-focus", "change" to change)
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss: stop speaking, but do not auto-resume later.
                if (isActive()) ttsController.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isActive()) ttsController.pauseForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            -> {
                // Resume only the pause WE caused; a user pause stays a pause.
                if (
                    ttsController.pausedByFocusLoss &&
                    ttsController.state.value.status == TtsStatus.Paused
                ) {
                    ttsController.resume()
                }
            }
            // Speech ducking has no lower-volume meaning here: keep reading.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
        }
    }

    override fun onCreate() {
        super.onCreate()
        val controller = TtsServiceHost.current
        if (controller == null) {
            MiruLog.event("tts", "service-no-controller")
            stopSelf()
            return
        }
        ttsController = controller
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiruTime:TtsPlayback")
            .apply { setReferenceCounted(false) }
        createChannel()
        mediaSession = MediaSession(this, "MiruTimeTts").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        ttsController.resume()
                    }

                    override fun onPause() {
                        ttsController.pause()
                    }

                    override fun onStop() {
                        ttsController.stop()
                    }

                    // Media buttons skip chapters: the reader owns the chapter
                    // list and continues speech after loading the document.
                    override fun onSkipToNext() {
                        ttsController.skipNextChapter()
                    }

                    override fun onSkipToPrevious() {
                        ttsController.skipPreviousChapter()
                    }

                    // Some devices deliver the raw media-button KeyEvent here
                    // instead of routing it to onPlay/onPause/onSkip*.
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val key = mediaButtonIntent.mediaButtonKey()
                            ?: return super.onMediaButtonEvent(mediaButtonIntent)
                        if (key.action != KeyEvent.ACTION_DOWN) return true
                        handleMediaButton(key)
                        return true
                    }
                },
            )
            setSessionActivity(contentIntent())
            // Explicit media-button receiver so headsets/Bluetooth route to this
            // session even when it is not the platform's default one (FR-044).
            setMediaButtonReceiver(TtsMediaButtonReceiver.pendingIntent(this@TtsPlaybackService))
        }
        registerNoisyReceiver()
        observeState()
        observeOverlay()
        observeSleepTimer()
        MiruLog.event("tts", "service-create")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::ttsController.isInitialized) return START_NOT_STICKY
        val command = ttsTransportCommand(intent?.action)
        MiruLog.event(
            "tts",
            "service-command",
            "command" to (command?.name ?: "start"),
            "status" to ttsController.state.value.status,
        )
        when (command) {
            // Explicit play/pause/stop is a user action: the controller clears
            // the focus-loss marker, so a later regain cannot re-resume it.
            TtsTransportCommand.Play -> ttsController.resume()
            TtsTransportCommand.Pause -> ttsController.pause()
            TtsTransportCommand.Stop -> {
                ttsController.stop()
                stopPlayback()
            }
            TtsTransportCommand.NextChapter -> ttsController.skipNextChapter()
            TtsTransportCommand.PreviousChapter -> ttsController.skipPreviousChapter()
            null -> {
                bookTitle = intent?.getStringExtra(TtsPlaybackIntent.ExtraBookTitle)
                    ?: bookTitle
                chapterTitle = intent?.getStringExtra(TtsPlaybackIntent.ExtraChapterTitle)
                    ?: chapterTitle
                coverUrl = intent?.getStringExtra(TtsPlaybackIntent.ExtraCoverUrl)
                    ?: coverUrl
                chapterNumber = intent?.getIntExtra(TtsPlaybackIntent.ExtraChapterNumber, 0)
                    ?: chapterNumber
                chapterCount = intent?.getIntExtra(TtsPlaybackIntent.ExtraChapterCount, 0)
                    ?: chapterCount
                overlay?.updateChapter(overlayChapter())
                started = true
                // A start intent also re-posts the notification after a late
                // POST_NOTIFICATIONS grant, so force one publish.
                publishedKey = null
                mediaKey = null
                loadCover()
                // Android requires startForeground within 5s of
                // startForegroundService. The controller may already be
                // Idle/Stopped by the time this intent arrives (auto-next /
                // last-chapter race); handleState would call stopPlayback
                // before startForeground and trigger
                // ForegroundServiceDidNotStartInTimeException. Always publish
                // the foreground notification first, then let handleState
                // decide whether to keep playing or stop.
                ensureForeground()
                // StateFlow already holds the active state, so the collector
                // will not emit it again; handle the current value directly.
                handleState(ttsController.state.value)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        MiruLog.event("tts", "tts-task-removed")
        if (::ttsController.isInitialized) {
            ttsController.stop()
            stopPlayback()
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        started = false
        serviceScope.cancel()
        if (!::ttsController.isInitialized) {
            MiruLog.event("tts", "service-destroy")
            super.onDestroy()
            return
        }
        overlay?.destroy()
        overlay = null
        releaseWakeLock()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        mediaSession.isActive = false
        mediaSession.release()
        if (isActive()) ttsController.pauseFromSystem()
        MiruLog.event("tts", "service-destroy")
        super.onDestroy()
    }

    private fun observeState() {
        serviceScope.launch {
            ttsController.state.collect { state ->
                overlay?.updateChapter(overlayChapter())
                if (!started) return@collect
                handleState(state)
            }
        }
    }

    /**
     * The system overlay exists only with the user's permission. It is created
     * lazily: a session may start before the floating switch is turned on.
     */
    private fun observeOverlay() {
        serviceScope.launch {
            ttsController.settings.collect { settings ->
                // Re-check on every emission: the user may grant the overlay
                // permission from the settings sheet while this service runs.
                if (settings.floatingPlayer &&
                    overlay == null &&
                    Settings.canDrawOverlays(this@TtsPlaybackService)
                ) {
                    overlay = TtsOverlayController(this@TtsPlaybackService, ttsController).also {
                        it.updateChapter(overlayChapter())
                        it.start()
                    }
                }
            }
        }
    }

    private fun overlayChapter() = TtsOverlayChapter(
        bookTitle = bookTitle,
        chapterTitle = chapterTitle,
        chapterNumber = chapterNumber,
        chapterCount = chapterCount,
    )

    /**
     * The timer emits every second, but the notification label is static.
     * Republish only when the stable key (deadline / chapter boundary) changes.
     */
    private fun observeSleepTimer() {
        serviceScope.launch {
            var lastKey: String? = null
            ttsController.sleepTimer.collect { timer ->
                val key = ttsSleepTimerNotificationKey(timer)
                if (key == lastKey) return@collect
                lastKey = key
                if (started && foregroundStarted) ensureForeground()
            }
        }
    }

    private fun handleState(state: TtsState) {
        when (state.status) {
            TtsStatus.Playing, TtsStatus.Loading -> {
                ensureAudioFocus()
                acquireWakeLock()
                mediaSession.isActive = true
                updateMediaSession()
                ensureForeground()
            }
            TtsStatus.Paused -> {
                // A chapter hand-off pauses the engine while the next document
                // loads. Keep the wake lock so the load still completes with
                // the screen off; a real pause releases it.
                if (ttsController.isHandoffPending) {
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }
                // A pause caused by audio-focus loss must keep the request:
                // abandoning it means the system never delivers AUDIOFOCUS_GAIN,
                // so the "resume what we paused" path could never run. A user
                // pause still drops the request so other apps get clean focus.
                if (!ttsController.pausedByFocusLoss && !ttsController.isHandoffPending) {
                    abandonAudioFocus()
                }
                updateMediaSession()
                ensureForeground()
            }
            TtsStatus.Idle, TtsStatus.Failed -> stopPlayback()
        }
    }

    private fun ensureForeground() {
        val info = ttsNotificationInfo(ttsController.state.value, bookTitle, chapterTitle)
        val key = contentKey(info)
        if (foregroundStarted) {
            // Range callbacks fire per word on some engines; only content
            // changes are worth a notification update.
            if (key != publishedKey) {
                publishedKey = key
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }
            return
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        publishedKey = key
        MiruLog.event("tts", "service-foreground", "status" to ttsController.state.value.status)
    }

    private fun contentKey(info: TtsNotificationInfo): String {
        val state = ttsController.state.value
        return "${state.status}|${info.action}|${info.title}|${info.text}|" +
            "${ttsProgressPercent(state)}|$chapterNumber|$chapterCount|$coverKey|" +
            ttsSleepTimerNotificationKey(ttsController.sleepTimer.value)
    }

    private fun stopPlayback() {
        if (!started && !foregroundStarted) return
        MiruLog.event("tts", "service-stop", "status" to ttsController.state.value.status)
        started = false
        releaseWakeLock()
        abandonAudioFocus()
        mediaSession.isActive = false
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build(),
        )
        if (foregroundStarted) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun isActive(): Boolean = when (ttsController.state.value.status) {
        TtsStatus.Playing, TtsStatus.Loading -> true
        else -> false
    }

    // Shared mapping for keys delivered through onMediaButtonEvent; the
    // BroadcastReceiver path uses the same rules.
    private fun handleMediaButton(key: KeyEvent) {
        when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> ttsController.resume()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> ttsController.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (ttsController.state.value.status == TtsStatus.Playing) {
                    ttsController.pause()
                } else {
                    ttsController.resume()
                }
            KeyEvent.KEYCODE_MEDIA_STOP -> ttsController.stop()
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            -> ttsController.skipNextChapter()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            -> ttsController.skipPreviousChapter()
            else -> Unit
        }
        MiruLog.event("tts", "media-button", "code" to key.keyCode)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tts_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tts_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val info = ttsNotificationInfo(ttsController.state.value, bookTitle, chapterTitle)
        val fallback = getString(R.string.tts_notification_title)
        val playPause = if (info.action == TtsTransportAction.Pause) {
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_pause),
                getString(R.string.tts_action_pause),
                serviceIntent(TtsPlaybackIntent.ActionPause, REQUEST_PAUSE),
            ).build()
        } else {
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_play),
                getString(R.string.tts_action_play),
                serviceIntent(TtsPlaybackIntent.ActionPlay, REQUEST_PLAY),
            ).build()
        }
        val actions = mutableListOf<Notification.Action>()
        var playPauseIndex = 0
        if (info.canSkip) {
            actions += Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_previous_chapter),
                getString(R.string.tts_action_prev_chapter),
                serviceIntent(TtsPlaybackIntent.ActionPreviousChapter, REQUEST_PREVIOUS),
            ).build()
        }
        playPauseIndex = actions.size
        actions += playPause
        if (info.canSkip) {
            actions += Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_next_chapter),
                getString(R.string.tts_action_next_chapter),
                serviceIntent(TtsPlaybackIntent.ActionNextChapter, REQUEST_NEXT),
            ).build()
        }
        actions += Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_stop),
            getString(R.string.tts_action_stop),
            serviceIntent(TtsPlaybackIntent.ActionStop, REQUEST_STOP),
        ).build()
        val compact = if (info.canSkip) {
            intArrayOf(playPauseIndex - 1, playPauseIndex, playPauseIndex + 1)
        } else {
            intArrayOf(playPauseIndex)
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(info.title.ifEmpty { fallback })
            .setContentText(info.text.ifEmpty { fallback })
            .setContentIntent(contentIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setActions(*actions.toTypedArray())
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(*compact),
            )
        playbackSubtitle()?.let(builder::setSubText)
        coverBitmap?.let(builder::setLargeIcon)
        return builder.build()
    }

    /** "Chapter 3 / 24 · 42%" under the title when the data is available. */
    private fun playbackSubtitle(): String? {
        val state = ttsController.state.value
        val parts = mutableListOf<String>()
        sleepTimerNotificationLabel()?.let(parts::add)
        if (chapterNumber > 0 && chapterCount > 0) {
            parts += getString(R.string.tts_chapter_fmt, chapterNumber, chapterCount)
        }
        if (state.chunkCount > 0) {
            parts += getString(R.string.tts_progress_fmt, ttsProgressPercent(state))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * Static sleep-timer label. Minutes show the wall-clock end time so the
     * notification never has to tick; chapters change only at a boundary.
     */
    private fun sleepTimerNotificationLabel(): String? =
        when (val timer = ttsController.sleepTimer.value) {
            null -> null
            else -> when (timer.mode) {
                TtsSleepTimerMode.Minutes -> getString(
                    R.string.tts_sleep_timer_until_fmt,
                    android.text.format.DateFormat.getTimeFormat(this)
                        .format(Date(timer.endsAtEpochMillis)),
                )
                TtsSleepTimerMode.Chapters -> getString(
                    R.string.tts_sleep_timer_chapters_fmt,
                    timer.chaptersRemaining,
                )
            }
        }

    private fun updateMediaSession() {
        val info = ttsNotificationInfo(ttsController.state.value, bookTitle, chapterTitle)
        val key = contentKey(info)
        if (key == mediaKey) return
        mediaKey = key
        val fallback = getString(R.string.tts_notification_title)
        val chapter = chapterTitle.trim().ifEmpty { info.title.ifEmpty { fallback } }
        val book = bookTitle.trim()
        // Feed the system media controls a real duration/position from the
        // time estimate, so they are not a flat card with no progress (FR-048).
        val tts = ttsController.state.value
        val totalSeconds = tts.estimatedTotalSeconds.coerceAtLeast(0)
        val durationMs = totalSeconds * 1000L
        val positionMs = (totalSeconds - tts.estimatedRemainingSeconds)
            .coerceIn(0, totalSeconds) * 1000L
        val state = when (ttsController.state.value.status) {
            TtsStatus.Playing -> PlaybackState.STATE_PLAYING
            TtsStatus.Loading -> PlaybackState.STATE_BUFFERING
            TtsStatus.Paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, chapter)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, book)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, book)
            .putString(
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                chapter,
            )
            .putString(
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                book,
            )
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        coverBitmap?.let { bitmap ->
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
        }
        mediaSession.setMetadata(metadata.build())
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        val skipActions = if (info.canSkip) {
            actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        } else {
            actions
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(skipActions)
                .setState(
                    state,
                    positionMs,
                    if (state == PlaybackState.STATE_PLAYING) 1f else 0f,
                )
                .build(),
        )
    }

    // Cover is optional decoration: a failed fetch must never delay or break
    // playback. The bitmap lands on both the notification large icon and the
    // MediaSession album art used by lock screens and bluetooth devices.
    private fun loadCover() {
        val url = coverUrl.trim()
        if (url.isEmpty()) {
            coverBitmap = null
            coverKey = null
            return
        }
        if (url == coverKey) return
        coverJob?.cancel()
        coverJob = serviceScope.launch {
            val bitmap = loadCoverBitmap(url) ?: return@launch
            if (coverUrl.trim() != url) return@launch
            coverBitmap = bitmap
            coverKey = url
            MiruLog.event("tts", "cover-loaded", "bytes" to bitmap.byteCount)
            updateMediaSession()
            if (foregroundStarted) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private suspend fun loadCoverBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(this@TtsPlaybackService)
                .data(coverData(url))
                .size(Size(512, 512))
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(this@TtsPlaybackService).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
            // Notification/meta need a software bitmap; Coil may hand back a
            // hardware-backed one on some devices.
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
        }.getOrNull()
    }

    private fun coverData(raw: String): Any = when {
        raw.startsWith("http", ignoreCase = true) -> raw
        raw.startsWith("content:", ignoreCase = true) ||
            raw.startsWith("file:", ignoreCase = true) -> Uri.parse(raw)
        else -> File(raw)
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TtsPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun contentIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureAudioFocus() {
        if (hasAudioFocus) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        audioFocusRequest = request
        hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        hasAudioFocus = false
        audioManager.abandonAudioFocusRequest(request)
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    @Suppress("DEPRECATION")
    private fun registerNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
    }

    private fun unregisterNoisyReceiver() {
        runCatching { unregisterReceiver(noisyReceiver) }
    }

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 4101

        private const val REQUEST_CONTENT = 1
        private const val REQUEST_PLAY = 2
        private const val REQUEST_PAUSE = 3
        private const val REQUEST_STOP = 4
        private const val REQUEST_PREVIOUS = 5
        private const val REQUEST_NEXT = 6

        fun start(
            context: Context,
            bookTitle: String,
            chapterTitle: String,
            coverUrl: String? = null,
            chapterNumber: Int = 0,
            chapterCount: Int = 0,
        ) {
            val intent = Intent(context, TtsPlaybackService::class.java)
                .setAction(TtsPlaybackIntent.ActionStart)
                .putExtra(TtsPlaybackIntent.ExtraBookTitle, bookTitle)
                .putExtra(TtsPlaybackIntent.ExtraChapterTitle, chapterTitle)
                .putExtra(TtsPlaybackIntent.ExtraCoverUrl, coverUrl)
                .putExtra(TtsPlaybackIntent.ExtraChapterNumber, chapterNumber)
                .putExtra(TtsPlaybackIntent.ExtraChapterCount, chapterCount)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsPlaybackService::class.java))
        }
    }
}
