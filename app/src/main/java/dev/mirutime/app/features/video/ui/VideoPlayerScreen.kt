package dev.mirutime.app.features.video.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.video.data.VideoDownloadManager
import dev.mirutime.app.features.video.data.VideoSourceRepository
import dev.mirutime.app.features.video.data.VideoVariantProbe
import dev.mirutime.app.features.video.domain.GestureConfig
import dev.mirutime.app.features.video.domain.VideoChapterSource
import dev.mirutime.app.features.video.domain.VideoLoadResult
import dev.mirutime.app.features.video.domain.VideoPlaybackErrorKind
import dev.mirutime.app.features.video.domain.VideoVariantStatus
import dev.mirutime.app.features.video.domain.autoNextEpisode
import dev.mirutime.app.features.video.domain.initialVideoStreamIndex
import dev.mirutime.app.features.video.domain.isDecoderFallbackError
import dev.mirutime.app.features.video.domain.pendingVariantProbes
import dev.mirutime.app.features.video.domain.shouldAskVideoQuality
import dev.mirutime.app.features.video.domain.subtitleMimeType
import dev.mirutime.app.features.video.domain.videoExternalMimeType
import dev.mirutime.app.features.video.domain.videoMimeType
import dev.mirutime.app.features.video.domain.videoPlaybackErrorKind
import dev.mirutime.app.features.video.domain.videoQualityKey
import dev.mirutime.app.features.video.domain.videoRestartPositionMs
import dev.mirutime.core.api.VideoStream
import dev.mirutime.uikit.ChoiceOption
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.ChoiceSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen video surface. Used by [dev.mirutime.app.features.video.VideoPlayerActivity];
 * the shell only owns the route and hands off to that activity.
 */
@Composable
fun VideoPlayerScreen(
    repository: VideoSourceRepository,
    videoDownloads: VideoDownloadManager,
    bookUrl: String,
    chapterIndex: Int,
    onBack: () -> Unit,
    onPlayer: (ExoPlayer?) -> Unit,
    onPlayerChapter: (Int) -> Unit,
    onChapterIndex: (Int) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    // Active playback only: buffering, pause and background stop the clock.
    onPlaybackActive: (Boolean, Int) -> Unit = { _, _ -> },
    // Progress writes outlive a chapter switch: the screen's own coroutine
    // scope is cancelled when the surface leaves composition.
    onPersistProgress: (
        bookUrl: String,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
    ) -> Unit,
) {
    var result by remember(bookUrl) { mutableStateOf<VideoLoadResult>(VideoLoadResult.Loading) }
    var retry by remember(bookUrl) { mutableIntStateOf(0) }
    // Player settings survive episode switches inside this player session.
    var speed by remember(bookUrl) { mutableFloatStateOf(1f) }
    var keepScreenOn by rememberSaveable(bookUrl) { mutableStateOf(true) }

    LaunchedEffect(bookUrl, chapterIndex, retry) {
        result = VideoLoadResult.Loading
        result = repository.load(bookUrl, chapterIndex)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val loaded = result) {
            VideoLoadResult.Loading -> VideoLoadingOverlay()
            VideoLoadResult.Empty -> VideoMessageOverlay(
                message = stringResource(R.string.video_empty),
                retry = { retry++ },
                onBack = onBack,
            )
            is VideoLoadResult.Error -> VideoMessageOverlay(
                message = loaded.message ?: stringResource(R.string.video_error),
                retry = { retry++ },
                onBack = onBack,
            )
            is VideoLoadResult.Ready -> VideoReadyContent(
                source = loaded.source,
                repository = repository,
                videoDownloads = videoDownloads,
                onPlayer = onPlayer,
                onPlayerChapter = onPlayerChapter,
                onChapterIndex = onChapterIndex,
                onFullscreen = onFullscreen,
                onPlaybackActive = onPlaybackActive,
                speed = speed,
                keepScreenOn = keepScreenOn,
                onSpeed = { speed = it },
                onKeepScreenOn = { keepScreenOn = it },
                onPersistProgress = onPersistProgress,
            )
        }
    }
}

@Composable
private fun VideoReadyContent(
    source: VideoChapterSource,
    repository: VideoSourceRepository,
    videoDownloads: VideoDownloadManager,
    onPlayer: (ExoPlayer?) -> Unit,
    onPlayerChapter: (Int) -> Unit,
    onChapterIndex: (Int) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onPlaybackActive: (Boolean, Int) -> Unit,
    speed: Float,
    keepScreenOn: Boolean,
    onSpeed: (Float) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onPersistProgress: (
        bookUrl: String,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
    ) -> Unit,
) {
    // Saved per book; blank means the player has to ask when there is a choice.
    var chosenQuality by rememberSaveable(source.bookUrl) {
        mutableStateOf(AppSettings.videoVariantForBook(source.bookUrl).orEmpty())
    }
    var qualityPickerOpen by remember(source.bookUrl) {
        mutableStateOf(shouldAskVideoQuality(source.streams, chosenQuality))
    }
    // Quality changes keep the same chapter but move the position into the
    // rebuilt player; a chapter change resets this via the remember key.
    var qualitySeekMs by remember(source.chapterIndex) { mutableStateOf<Long?>(null) }

    // Probe cache: URL -> status. The screen owns it; the probe itself caches
    // nothing. DEAD entries are re-probed each time the chooser opens.
    val probeStatuses = remember(source.bookUrl) {
        mutableStateMapOf<String, VideoVariantStatus>()
    }
    val probeInFlight = remember(source.bookUrl) { mutableSetOf<String>() }
    val probeScope = rememberCoroutineScope()
    var probeRequest by remember(source.bookUrl) { mutableIntStateOf(0) }

    fun CoroutineScope.probeVariants(includeDead: Boolean) {
        pendingVariantProbes(source.streams, probeStatuses, includeDead).forEach { item ->
            if (!probeInFlight.add(item.url)) return@forEach
            launch {
                try {
                    val status = VideoVariantProbe.probe(
                        url = item.url,
                        headers = repository.headersFor(item),
                    )
                    // A late UNKNOWN must not overwrite an earlier DEAD.
                    if (status != VideoVariantStatus.UNKNOWN || probeStatuses[item.url] == null) {
                        probeStatuses[item.url] = status
                    }
                } finally {
                    probeInFlight.remove(item.url)
                }
            }
        }
    }

    // Warm the cache as soon as streams arrive; the chooser re-probes DEAD.
    LaunchedEffect(source.streams.map { it.url }) {
        probeScope.probeVariants(includeDead = false)
    }
    LaunchedEffect(qualityPickerOpen) {
        if (qualityPickerOpen) probeScope.probeVariants(includeDead = true)
    }
    LaunchedEffect(probeRequest) {
        if (probeRequest > 0) probeScope.probeVariants(includeDead = true)
    }

    val streamIndex = initialVideoStreamIndex(source.streams, chosenQuality)
        .coerceIn(0, source.streams.lastIndex)
    val stream = source.streams[streamIndex]
    var headers by remember(stream.url) { mutableStateOf<Map<String, String>?>(null) }

    LaunchedEffect(stream) {
        headers = repository.headersFor(stream)
    }

    val resolvedHeaders = headers
    if (resolvedHeaders == null) {
        VideoLoadingOverlay()
    } else {
        // One player per chapter: switching episodes releases and rebuilds.
        key(source.chapterIndex) {
            VideoSurface(
                source = source,
                stream = stream,
                streamIndex = streamIndex,
                headers = resolvedHeaders,
                startPositionMs = qualitySeekMs ?: source.startPositionMs,
                videoDownloads = videoDownloads,
                probeStatuses = probeStatuses,
                onProbeVariants = { probeRequest++ },
                onPlayer = onPlayer,
                onPlayerChapter = onPlayerChapter,
                onChapterIndex = onChapterIndex,
                onFullscreen = onFullscreen,
                onPlaybackActive = onPlaybackActive,
                speed = speed,
                keepScreenOn = keepScreenOn,
                onSpeed = onSpeed,
                onKeepScreenOn = onKeepScreenOn,
                onQualitySelected = { quality, positionMs ->
                    qualitySeekMs = positionMs
                    chosenQuality = quality
                    AppSettings.setVideoVariantForBook(source.bookUrl, quality)
                },
                onPersistProgress = onPersistProgress,
            )
        }
    }

    // Ask once per player session, and only when there is more than one source
    // and no saved quality. Dismissing leaves the current (first) source
    // playing and asks again next session.
    if (qualityPickerOpen) {
        VideoVariantSheet(
            streams = source.streams,
            selectedStreamIndex = streamIndex,
            probeStatuses = probeStatuses,
            onSelect = { index ->
                val quality = videoQualityKey(source.streams[index])
                chosenQuality = quality
                AppSettings.setVideoVariantForBook(source.bookUrl, quality)
                qualityPickerOpen = false
            },
            onClose = { qualityPickerOpen = false },
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoSurface(
    source: VideoChapterSource,
    stream: VideoStream,
    streamIndex: Int,
    headers: Map<String, String>,
    startPositionMs: Long,
    videoDownloads: VideoDownloadManager,
    probeStatuses: Map<String, VideoVariantStatus>,
    onProbeVariants: () -> Unit,
    onPlayer: (ExoPlayer?) -> Unit,
    onPlayerChapter: (Int) -> Unit,
    onChapterIndex: (Int) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onPlaybackActive: (Boolean, Int) -> Unit,
    speed: Float,
    keepScreenOn: Boolean,
    onSpeed: (Float) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onQualitySelected: (String, Long) -> Unit,
    onPersistProgress: (
        bookUrl: String,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
    ) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    // Same cache the offline downloads wrote into; misses still hit the
    // network through the OkHttp upstream.
    val dataSourceFactory = remember(stream.url, headers, videoDownloads) {
        videoDownloads.cacheDataSourceFactory(headers)
    }

    var settingsOpen by remember { mutableStateOf(false) }
    var variantChooserOpen by remember { mutableStateOf(false) }
    var resolutionChooserOpen by remember { mutableStateOf(false) }
    var audioChooserOpen by remember { mutableStateOf(false) }
    var subtitleChooserOpen by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<VideoPlaybackFailure?>(null) }
    var playerRetry by remember { mutableIntStateOf(0) }
    var trackRevision by remember { mutableIntStateOf(0) }
    // Live because the settings sheet writes it while the player is open.
    val controllerAutoHideMs = AppSettings.videoControllerAutoHideMs
    // One automatic software-decoder retry per URL, then the error screen.
    var forceSoftwareDecoder by remember(stream.url) { mutableStateOf(false) }
    var resumePositionMs by remember(stream.url) { mutableLongStateOf(startPositionMs) }
    val gestureHud = remember { mutableStateOf<GestureHud?>(null) }

    var player by remember(stream.url, headers, playerRetry, forceSoftwareDecoder) {
        mutableStateOf<ExoPlayer?>(null)
    }

    // Same contract as NoveLA's ChapterSwitchingPlayer: the lower media3
    // controls switch to the neighbouring episode and are disabled at the
    // ends of the list. No top-bar episode controls.
    val chapterPlayer = remember(player, source.previousEpisode, source.nextEpisode) {
        player?.let { raw ->
            ChapterSwitchingPlayer(
                player = raw,
                previousIndex = source.previousEpisode?.index,
                nextIndex = source.nextEpisode?.index,
                onSwitchChapter = { index ->
                    val current = player
                    if (current != null) {
                        onPersistProgress(
                            source.bookUrl,
                            source.chapterIndex,
                            current.currentPosition,
                            current.duration,
                        )
                    }
                    onChapterIndex(index)
                },
            )
        }
    }

    val persist: (Long, Long) -> Unit = { position, duration ->
        onPersistProgress(
            source.bookUrl,
            source.chapterIndex,
            position,
            duration,
        )
    }

    val activity = context as? Activity
    val gestureDispatcher = remember(activity) {
        activity?.let { host ->
            PlayerGestureDispatcher(
                activity = host,
                playerProvider = { player },
                configProvider = ::currentGestureConfig,
                // The Compose header paints over the video but does not
                // consume touches on its empty area; exclude it explicitly.
                exclusionTopPx = { playerView ->
                    val inset = ViewCompat.getRootWindowInsets(playerView)
                        ?.getInsets(WindowInsetsCompat.Type.systemBars())
                        ?.top
                        ?: 0
                    val portrait = playerView.resources.configuration.orientation ==
                        Configuration.ORIENTATION_PORTRAIT
                    if (portrait) {
                        inset + (HeaderHeightDp * playerView.resources.displayMetrics.density)
                            .toInt()
                    } else {
                        inset
                    }
                },
                exclusionBottomPx = { playerView ->
                    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                        ?.takeIf { it.visibility == View.VISIBLE }
                        ?.height
                        ?: 0
                },
                onToggleControls = { playerView ->
                    (playerView as? PlayerView)?.toggleController()
                },
                onHud = { gestureHud.value = it },
            )
        }
    }

    DisposableEffect(gestureDispatcher) {
        onDispose { gestureDispatcher?.dispose() }
    }

    LaunchedEffect(stream.url, headers, playerRetry, forceSoftwareDecoder) {
        playbackError = null
        player = try {
            ExoPlayer.Builder(
                context,
                createVideoRenderersFactory(context, forceSoftwareDecoder),
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
        } catch (e: Exception) {
            playbackError = VideoPlaybackFailure(VideoPlaybackErrorKind.Other)
            null
        }
    }

    DisposableEffect(player) {
        val current = player
        if (current != null) {
            onPlayer(current)
            onPlayerChapter(source.chapterIndex)
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    onPlaybackActive(playing, source.chapterIndex)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    trackRevision++
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (isDecoderFallbackError(error.errorCode) && !forceSoftwareDecoder) {
                        // Persist and keep the position before the decoder is
                        // rebuilt; the retried player resumes here.
                        val position = current.currentPosition
                        persist(position, current.duration)
                        resumePositionMs = videoRestartPositionMs(position, resumePositionMs)
                        forceSoftwareDecoder = true
                    } else {
                        playbackError = VideoPlaybackFailure(
                            videoPlaybackErrorKind(error.errorCode),
                        )
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        // Persist first: the chapter row must be marked read
                        // before the request moves to the next episode.
                        persist(current.currentPosition, current.duration)
                        autoNextEpisode(source, AppSettings.videoAutoNext)
                            ?.let { next -> onChapterIndex(next.index) }
                    }
                }
            }
            current.addListener(listener)
            onDispose {
                current.removeListener(listener)
                onPlaybackActive(false, source.chapterIndex)
                onPlayer(null)
                current.release()
            }
        } else {
            onDispose { }
        }
    }

    // Media source is rebuilt on a quality/stream change; the position to
    // resume was captured by the caller before the rebuild.
    LaunchedEffect(player, stream.url, headers, playerRetry, forceSoftwareDecoder) {
        val current = player ?: return@LaunchedEffect
        try {
            current.setMediaSource(buildMediaSource(context, stream, dataSourceFactory))
            current.seekTo(resumePositionMs.coerceAtLeast(0))
            current.setPlaybackSpeed(speed)
            current.prepare()
            current.playWhenReady = true
        } catch (e: Exception) {
            playbackError = VideoPlaybackFailure(VideoPlaybackErrorKind.Other)
        }
    }

    LaunchedEffect(variantChooserOpen) {
        if (variantChooserOpen) onProbeVariants()
    }

    LaunchedEffect(player, speed) {
        player?.setPlaybackSpeed(speed)
    }

    // Only playing keeps the screen awake: the setting is a ceiling, not a
    // permanent flag (paused video must still lock/blank normally).
    DisposableEffect(view, keepScreenOn, isPlaying) {
        view.keepScreenOn = keepScreenOn && isPlaying
        onDispose { view.keepScreenOn = false }
    }

    val tracks = player?.currentTracks
    val audioOptions = remember(tracks, trackRevision) {
        trackOptions(tracks, C.TRACK_TYPE_AUDIO)
    }
    val videoOptions = remember(tracks, trackRevision) {
        trackOptions(tracks, C.TRACK_TYPE_VIDEO, ::videoTrackLabel)
    }
    val subtitleOptions = remember(tracks, trackRevision) {
        trackOptions(tracks, C.TRACK_TYPE_TEXT)
    }
    val subtitlesOff = player?.trackSelectionParameters
        ?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            // Portrait keeps the controller clear of the gesture bar;
            // landscape is the immersive fullscreen surface with no padding.
            modifier = Modifier
                .fillMaxSize()
                .then(if (isPortrait) Modifier.navigationBarsPadding() else Modifier),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    // NoveLA keeps episode navigation in the lower playback
                    // controls, not in the top bar. The wrapper below maps the
                    // buttons to the neighbouring chapter.
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    // Track pickers live in our sheet; the built-in subtitle
                    // button opens a media3 AlertDialog, so keep it off.
                    setShowSubtitleButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setControllerShowTimeoutMs(controllerAutoHideMs)
                    setControllerHideOnTouch(true)
                    setFullscreenButtonClickListener { enter ->
                        onFullscreen(enter)
                    }
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        },
                    )
                    // Media3's own gear opens an AlertDialog track picker.
                    // Replace its click listener with the NoveLA settings
                    // panel: one bottom sheet, no dialog.
                    post {
                        findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                            ?.setOnClickListener { settingsOpen = true }
                    }
                }
            },
            update = { playerView ->
                playerView.player = chapterPlayer
                // Live apply: the factory runs once per surface.
                playerView.setControllerShowTimeoutMs(controllerAutoHideMs)
                // Gestures live inside the View: a Compose pointer layer over
                // AndroidView would fight PlayerView's own controller taps.
                playerView.setOnTouchListener(gestureDispatcher)
            },
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            VideoHeader(
                title = source.chapterTitle,
                variantLabel = stream.quality
                    ?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.video_source_fmt, streamIndex + 1),
                hasVariantChoice = source.streams.size > 1,
                showResolution = videoOptions.size > 1,
                onVariantClick = { variantChooserOpen = true },
                onResolutionClick = { resolutionChooserOpen = true },
                onOpenInOther = { openInOtherApp(context, stream) },
            )
        }

        // Read only here so drag frames repaint the HUD, not the whole player.
        GestureHudOverlay(gestureHud)

        val error = playbackError
        if (error != null) {
            VideoPlaybackErrorOverlay(
                kind = error.kind,
                canOpenInBrowser = stream.url.startsWith("http://", ignoreCase = true) ||
                    stream.url.startsWith("https://", ignoreCase = true),
                variantCount = source.streams.size,
                onRetry = {
                    val current = player
                    val position = current?.currentPosition ?: resumePositionMs
                    if (current != null) {
                        persist(position, current.duration)
                        resumePositionMs = videoRestartPositionMs(position, resumePositionMs)
                    }
                    playbackError = null
                    playerRetry++
                },
                onOpenInBrowser = { openInBrowser(context, stream.url) },
                onOpenInOtherApp = { openInOtherApp(context, stream) },
                onChooseVariant = {
                    playbackError = null
                    variantChooserOpen = true
                },
            )
        }
    }

    val currentVariantLabel = stream.quality
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.video_source_fmt, streamIndex + 1)
    val currentVideo = videoOptions.firstOrNull { it.selected }
    val currentAudio = audioOptions.firstOrNull { it.selected }
    val currentSubtitle = subtitleOptions.firstOrNull { it.selected }
    val currentAudioLabel = if (audioOptions.isEmpty()) {
        stringResource(R.string.video_audio_none)
    } else {
        currentAudio?.label?.takeIf { it.isNotBlank() }.orEmpty()
    }
    val currentVideoLabel = currentVideo?.let { option ->
        option.label.ifBlank {
            stringResource(R.string.video_track_fmt, option.trackIndex + 1)
        }
    }
    val currentSubtitleLabel = when {
        subtitleOptions.isEmpty() -> stringResource(R.string.video_subtitles_none)
        subtitlesOff -> stringResource(R.string.video_subtitles_off)
        else -> currentSubtitle?.let { option ->
            option.label.ifBlank {
                stringResource(R.string.video_track_fmt, option.trackIndex + 1)
            }
        } ?: stringResource(R.string.video_subtitles_off)
    }

    fun chooseVariant(index: Int) {
        val selected = source.streams.getOrNull(index) ?: return
        val current = player
        val position = current?.currentPosition ?: startPositionMs
        // Persist before the player is rebuilt on the new variant.
        if (current != null) persist(position, current.duration)
        onQualitySelected(videoQualityKey(selected), position)
    }

    fun chooseVideoTrack(option: VideoTrackOption) {
        val current = player
        val group = option.trackGroup
        if (current != null && group != null) {
            // HLS level switch inside the same stream: no restart.
            current.trackSelectionParameters = current.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setOverrideForType(
                    TrackSelectionOverride(group, option.trackIndex),
                )
                .build()
            trackRevision++
        }
    }

    fun chooseAudioTrack(option: VideoTrackOption) {
        val current = player
        val group = option.trackGroup
        if (current != null && group != null) {
            current.trackSelectionParameters = current.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setOverrideForType(
                    TrackSelectionOverride(group, option.trackIndex),
                )
                .build()
            trackRevision++
        }
    }

    fun chooseSubtitle(option: VideoTrackOption?) {
        val current = player
        if (current != null) {
            val builder = current.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            val group = option?.trackGroup
            if (group == null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                builder
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(
                        TrackSelectionOverride(group, option.trackIndex),
                    )
            }
            current.trackSelectionParameters = builder.build()
            trackRevision++
        }
    }

    if (settingsOpen) {
        VideoSettingsSheet(
            currentVariantLabel = currentVariantLabel,
            currentResolutionLabel = currentVideoLabel?.takeIf { videoOptions.size > 1 },
            currentAudioLabel = currentAudioLabel,
            audioEnabled = audioOptions.isNotEmpty(),
            currentSubtitleLabel = currentSubtitleLabel,
            subtitlesEnabled = subtitleOptions.isNotEmpty(),
            speed = speed,
            keepScreenOn = keepScreenOn,
            onSpeed = onSpeed,
            onVariantClick = {
                settingsOpen = false
                variantChooserOpen = true
            },
            onResolutionClick = {
                settingsOpen = false
                resolutionChooserOpen = true
            },
            onAudioClick = {
                settingsOpen = false
                audioChooserOpen = true
            },
            onSubtitlesClick = {
                settingsOpen = false
                subtitleChooserOpen = true
            },
            onKeepScreenOn = onKeepScreenOn,
            onClose = { settingsOpen = false },
        )
    }

    if (variantChooserOpen) {
        VideoVariantSheet(
            streams = source.streams,
            selectedStreamIndex = streamIndex,
            probeStatuses = probeStatuses,
            onSelect = { index ->
                variantChooserOpen = false
                chooseVariant(index)
            },
            onClose = { variantChooserOpen = false },
        )
    }

    if (resolutionChooserOpen) {
        ChoiceSheet(
            title = stringResource(R.string.video_resolution),
            description = "",
            options = videoOptions.map { option ->
                ChoiceOption(
                    key = option.key,
                    label = option.label.ifBlank {
                        stringResource(R.string.video_track_fmt, option.trackIndex + 1)
                    },
                )
            },
            selected = currentVideo?.key.orEmpty(),
            onSelect = { key ->
                videoOptions.firstOrNull { it.key == key }?.let { chooseVideoTrack(it) }
            },
            onClose = { resolutionChooserOpen = false },
        )
    }

    if (audioChooserOpen) {
        ChoiceSheet(
            title = stringResource(R.string.video_audio),
            description = "",
            options = audioOptions.map { option ->
                ChoiceOption(
                    key = option.key,
                    label = option.label.ifBlank {
                        stringResource(R.string.video_track_fmt, option.trackIndex + 1)
                    },
                )
            },
            selected = currentAudio?.key.orEmpty(),
            onSelect = { key ->
                audioOptions.firstOrNull { it.key == key }?.let { chooseAudioTrack(it) }
            },
            onClose = { audioChooserOpen = false },
        )
    }

    if (subtitleChooserOpen) {
        ChoiceSheet(
            title = stringResource(R.string.video_subtitles),
            description = "",
            options = listOf(
                ChoiceOption(
                    key = "",
                    label = stringResource(R.string.video_subtitles_off),
                ),
            ) + subtitleOptions.map { option ->
                ChoiceOption(
                    key = option.key,
                    label = option.label.ifBlank {
                        stringResource(R.string.video_track_fmt, option.trackIndex + 1)
                    },
                )
            },
            selected = if (subtitlesOff) "" else currentSubtitle?.key.orEmpty(),
            onSelect = { key ->
                chooseSubtitle(subtitleOptions.firstOrNull { it.key == key })
            },
            onClose = { subtitleChooserOpen = false },
        )
    }
}

@Composable
private fun VideoHeader(
    title: String,
    variantLabel: String,
    hasVariantChoice: Boolean,
    showResolution: Boolean,
    onVariantClick: () -> Unit,
    onResolutionClick: () -> Unit,
    onOpenInOther: () -> Unit,
) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
                ),
            )
            .then(if (isPortrait) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = MiruSpace.Xs, vertical = MiruSpace.Xs),
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    horizontal = MiruSpace.S,
                    vertical = MiruSpace.Xs,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val whiteButton = ButtonDefaults.textButtonColors(contentColor = Color.White)
            if (hasVariantChoice) {
                TextButton(
                    onClick = onVariantClick,
                    modifier = Modifier.weight(1f, fill = false),
                    colors = whiteButton,
                ) {
                    Text(
                        text = variantLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                    )
                }
            } else {
                Text(
                    text = variantLabel,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = MiruSpace.S),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showResolution) {
                TextButton(onClick = onResolutionClick, colors = whiteButton) {
                    Text(text = stringResource(R.string.video_resolution))
                }
            }
            TextButton(onClick = onOpenInOther, colors = whiteButton) {
                Text(text = stringResource(R.string.video_open_other))
            }
        }
    }
}

@Composable
private fun VideoLoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(modifier = Modifier.height(MiruSpace.M))
            Text(
                text = stringResource(R.string.video_loading),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Playback failure surfaced after the automatic decoder retry. */
private data class VideoPlaybackFailure(val kind: VideoPlaybackErrorKind)

@Composable
private fun VideoPlaybackErrorOverlay(
    kind: VideoPlaybackErrorKind,
    canOpenInBrowser: Boolean,
    variantCount: Int,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onOpenInOtherApp: () -> Unit,
    onChooseVariant: () -> Unit,
) {
    val message = when (kind) {
        VideoPlaybackErrorKind.Source -> stringResource(R.string.video_error_source)
        VideoPlaybackErrorKind.Codec -> stringResource(R.string.video_error_codec)
        VideoPlaybackErrorKind.Other -> stringResource(R.string.video_error)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = MiruSpace.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(MiruSpace.S))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.video_retry))
            }
            if (canOpenInBrowser) {
                TextButton(onClick = onOpenInBrowser) {
                    Text(stringResource(R.string.video_open_browser))
                }
            }
            TextButton(onClick = onOpenInOtherApp) {
                Text(stringResource(R.string.video_open_other))
            }
            if (variantCount > 1) {
                TextButton(onClick = onChooseVariant) {
                    Text(stringResource(R.string.video_choose_variant))
                }
            }
        }
    }
}

@Composable
private fun VideoMessageOverlay(
    message: String,
    retry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = MiruSpace.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(MiruSpace.L))
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
                Button(onClick = onBack) {
                    Text(stringResource(R.string.header_back))
                }
                Button(onClick = retry) {
                    Text(stringResource(R.string.video_retry))
                }
            }
        }
    }
}

/** External player: HLS gets its MIME, everything else the video wildcard. */
private fun openInOtherApp(context: Context, stream: VideoStream) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(stream.url.toUri(), videoExternalMimeType(stream))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, R.string.video_no_app_to_play, Toast.LENGTH_SHORT).show()
    }
}

private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, R.string.video_no_app_to_play, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Fallback on every player, and hardware decoders go last after the first
 * decoder init/decoding failure (some vendor decoders reject the stream).
 * On an emulator goldfish decoders are dropped: they paint HLS with pink/green
 * stripes without ever failing, so the retry path never kicks in.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun createVideoRenderersFactory(
    context: Context,
    preferSoftwareDecoder: Boolean,
): DefaultRenderersFactory {
    val onEmulator = isEmulatorDevice(Build.FINGERPRINT, Build.PRODUCT, Build.HARDWARE)
    return DefaultRenderersFactory(context).apply {
        setEnableDecoderFallback(true)
        if (onEmulator || preferSoftwareDecoder) {
            setMediaCodecSelector { mimeType, secure, tunneling ->
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                val keptNames = filterEmulatorDecoders(all.map { it.name }, onEmulator)
                val filtered = all.filter { it.name in keptNames }
                val sorted = if (preferSoftwareDecoder) {
                    filtered.sortedByDescending { it.name.startsWith("c2.android.") }
                } else {
                    filtered
                }
                if (sorted.isEmpty()) all else sorted
            }
        }
    }
}

// Tap/gesture exclusion matching the Compose header height: episode title plus
// the variant / resolution / open-in-other row.
private val HeaderHeightDp = (MiruSize.Row + MiruSize.Touch + MiruSpace.L).value

/**
 * Lower-control episode navigation, matching NoveLA's
 * [ChapterSwitchingPlayer]: media3's previous/next buttons switch to the
 * neighbouring episode directly and are unavailable at the ends of the list.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ChapterSwitchingPlayer(
    player: Player,
    private val previousIndex: Int?,
    private val nextIndex: Int?,
    private val onSwitchChapter: (Int) -> Unit,
) : ForwardingPlayer(player) {

    override fun seekToPrevious() {
        previousIndex?.let(onSwitchChapter)
    }

    override fun seekToNext() {
        nextIndex?.let(onSwitchChapter)
    }

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_PREVIOUS ->
            previousIndex != null || super.isCommandAvailable(command)
        Player.COMMAND_SEEK_TO_NEXT ->
            nextIndex != null || super.isCommandAvailable(command)
        else -> super.isCommandAvailable(command)
    }
}

private fun currentGestureConfig(widthPx: Float): GestureConfig = GestureConfig(
    seekStepMs = AppSettings.videoSeekStepMs,
    dragThresholdPx = AppSettings.videoGestureDragThreshold * widthPx,
    swappedSides = AppSettings.videoGestureSwappedSides,
    doubleTapEnabled = AppSettings.videoGestureDoubleTap,
    verticalSwipeEnabled = AppSettings.videoGestureVerticalSwipe,
    longPressSpeedEnabled = AppSettings.videoGestureLongPressSpeed,
)

@Composable
private fun GestureHudOverlay(state: State<GestureHud?>) {
    val hud = state.value
    // Keep the last content during the exit fade.
    var lastHud by remember { mutableStateOf<GestureHud?>(null) }
    if (hud != null) lastHud = hud
    AnimatedVisibility(
        visible = hud != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        val shown = lastHud
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(MiruRadius.Card),
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = Color.White,
                modifier = Modifier.padding(MiruSpace.Xl),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MiruSpace.L, vertical = MiruSpace.M),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    when (shown) {
                        is GestureHud.Seek -> {
                            Icon(Icons.Filled.AccessTime, contentDescription = null)
                            Text(
                                text = formatSeekTime(shown.positionMs) + "/" +
                                    formatSeekTime(shown.durationMs),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        is GestureHud.Brightness -> {
                            Icon(Icons.Filled.Brightness6, contentDescription = null)
                            Text(
                                text = "${(shown.fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        is GestureHud.Volume -> {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                            Text(
                                text = "${(shown.fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        is GestureHud.Speed -> {
                            Icon(Icons.Filled.Speed, contentDescription = null)
                            Text(
                                text = if (shown.factor % 1f == 0f) {
                                    "${shown.factor.toInt()}x"
                                } else {
                                    "${shown.factor}x"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        null -> Unit
                    }
                }
            }
        }
    }
}

private fun formatSeekTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun buildMediaSource(
    context: android.content.Context,
    stream: VideoStream,
    dataSourceFactory: DataSource.Factory,
): MediaSource {
    val subtitles = stream.subtitles.map { track ->
        MediaItem.SubtitleConfiguration.Builder(track.url.toUri())
            .setMimeType(subtitleMimeType(track))
            .setLabel(track.label)
            .setLanguage(track.language?.takeIf { it.isNotBlank() })
            .setSelectionFlags(C.SELECTION_FLAG_AUTOSELECT)
            .build()
    }
    val item = MediaItem.Builder()
        .setUri(stream.url)
        .apply { videoMimeType(stream)?.let(::setMimeType) }
        .setSubtitleConfigurations(subtitles)
        .build()
    return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item)
}

private fun trackOptions(
    tracks: Tracks?,
    type: Int,
    labelFor: (Format) -> String = ::trackOptionLabel,
): List<VideoTrackOption> {
    if (tracks == null) return emptyList()
    val result = mutableListOf<VideoTrackOption>()
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != type) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            result += VideoTrackOption(
                key = "$groupIndex:$trackIndex",
                label = labelFor(group.getTrackFormat(trackIndex)),
                trackGroup = group.mediaTrackGroup,
                trackIndex = trackIndex,
                selected = group.isTrackSelected(trackIndex),
            )
        }
    }
    return result
}
