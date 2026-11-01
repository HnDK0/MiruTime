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
import dev.mirutime.feature.tts.TtsState
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.TtsTransportAction
import dev.mirutime.feature.tts.TtsTransportCommand
import dev.mirutime.feature.tts.ttsNotificationInfo
import dev.mirutime.feature.tts.ttsProgressPercent
import dev.mirutime.feature.tts.ttsTransportCommand
import java.io.File
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

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isActive()) {
                ttsController.pause()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (isActive()) ttsController.pause()
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
                },
            )
            setSessionActivity(contentIntent())
        }
        registerNoisyReceiver()
        observeState()
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

    override fun onDestroy() {
        started = false
        serviceScope.cancel()
        if (!::ttsController.isInitialized) {
            MiruLog.event("tts", "service-destroy")
            super.onDestroy()
            return
        }
        releaseWakeLock()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        mediaSession.isActive = false
        mediaSession.release()
        if (isActive()) ttsController.pause()
        MiruLog.event("tts", "service-destroy")
        super.onDestroy()
    }

    private fun observeState() {
        serviceScope.launch {
            ttsController.state.collect { state ->
                if (!started) return@collect
                handleState(state)
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
                releaseWakeLock()
                abandonAudioFocus()
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
            "${ttsProgressPercent(state)}|$chapterNumber|$chapterCount|$coverKey"
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
        if (chapterNumber > 0 && chapterCount > 0) {
            parts += getString(R.string.tts_chapter_fmt, chapterNumber, chapterCount)
        }
        if (state.chunkCount > 0) {
            parts += getString(R.string.tts_progress_fmt, ttsProgressPercent(state))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun updateMediaSession() {
        val info = ttsNotificationInfo(ttsController.state.value, bookTitle, chapterTitle)
        val key = contentKey(info)
        if (key == mediaKey) return
        mediaKey = key
        val fallback = getString(R.string.tts_notification_title)
        val chapter = chapterTitle.trim().ifEmpty { info.title.ifEmpty { fallback } }
        val book = bookTitle.trim()
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
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
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
