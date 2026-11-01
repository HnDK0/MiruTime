package dev.mirutime.app.features.video.domain

import dev.mirutime.feature.reader.api.ReaderSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Records watched time as reading sessions, so the existing Book/Dashboard
 * aggregates include video books. Events are consumed sequentially: a pause
 * queued after a play must never reach the tracker first, or the session
 * would stay open and over-count background time.
 */
class VideoPlaybackSession(
    private val tracker: ReaderSessionTracker?,
    scope: CoroutineScope,
) {
    private sealed interface Event {
        data class Playing(val bookUrl: String, val chapterIndex: Int) : Event
        data object Stopped : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        if (tracker != null) {
            scope.launch {
                for (event in events) {
                    when (event) {
                        is Event.Playing -> tracker.start(event.bookUrl, event.chapterIndex)
                        Event.Stopped -> tracker.stop()
                    }
                }
            }
        }
    }

    fun onPlaying(bookUrl: String, chapterIndex: Int) {
        events.trySend(Event.Playing(bookUrl, chapterIndex))
    }

    fun onStopped() {
        events.trySend(Event.Stopped)
    }

    /** Queues a final stop and lets the consumer drain before it ends. */
    fun shutdown() {
        events.trySend(Event.Stopped)
        events.close()
    }
}
