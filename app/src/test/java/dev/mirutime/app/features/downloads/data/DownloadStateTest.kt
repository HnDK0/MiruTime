package dev.mirutime.app.features.downloads.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateTest {
    @Test
    fun `active states transition to terminal states`() {
        assertTrue(DownloadState.QUEUED.canTransitionTo(DownloadState.RUNNING))
        assertTrue(DownloadState.QUEUED.canTransitionTo(DownloadState.PAUSED))
        assertTrue(DownloadState.RUNNING.canTransitionTo(DownloadState.COMPLETED))
        assertTrue(DownloadState.RUNNING.canTransitionTo(DownloadState.FAILED))
        assertTrue(DownloadState.RUNNING.canTransitionTo(DownloadState.CANCELLED))
        assertTrue(DownloadState.PAUSED.canTransitionTo(DownloadState.QUEUED))
        assertTrue(DownloadState.FAILED.canTransitionTo(DownloadState.QUEUED))
        assertTrue(DownloadState.CANCELLED.canTransitionTo(DownloadState.QUEUED))
        assertTrue(DownloadState.RUNNING.canTransitionTo(DownloadState.WAITING_NETWORK))
        assertTrue(DownloadState.RUNNING.canTransitionTo(DownloadState.NEEDS_SOURCE_ACTION))
        assertTrue(DownloadState.WAITING_NETWORK.canTransitionTo(DownloadState.RUNNING))
        assertTrue(DownloadState.NEEDS_SOURCE_ACTION.canTransitionTo(DownloadState.QUEUED))
        assertTrue(DownloadState.NEEDS_SOURCE_ACTION.canTransitionTo(DownloadState.CANCELLED))
    }

    @Test
    fun `completed work never goes back to running without a new request`() {
        assertFalse(DownloadState.COMPLETED.canTransitionTo(DownloadState.RUNNING))
        assertFalse(DownloadState.COMPLETED.canTransitionTo(DownloadState.QUEUED))
        assertFalse(DownloadState.QUEUED.canTransitionTo(DownloadState.COMPLETED))
        assertFalse(DownloadState.PAUSED.canTransitionTo(DownloadState.COMPLETED))
    }

    @Test
    fun `wire parsing ignores unknown rows`() {
        assertEquals(DownloadState.RUNNING, DownloadState.from("RUNNING"))
        assertEquals(DownloadState.WAITING_NETWORK, DownloadState.from("WAITING_NETWORK"))
        assertEquals(
            DownloadState.NEEDS_SOURCE_ACTION,
            DownloadState.from("NEEDS_SOURCE_ACTION"),
        )
        assertEquals(null, DownloadState.from("downloading"))
        assertEquals(null, DownloadState.from(null))
    }
}

class DownloadPlannerTest {
    @Test
    fun `plan dedupes downloaded and active chapters in reading order`() {
        val plan = DownloadPlanner.pending(
            requested = listOf(3, 1, 2, 2, 5, 0),
            downloaded = setOf(1),
            active = setOf(5),
        )

        assertEquals(listOf(2, 3), plan)
    }

    @Test
    fun `next hundred starts at the first pending chapter`() {
        val all = (1..300).toList()
        val plan = DownloadPlanner.next100(
            allIndexes = all,
            downloaded = (1..120).toSet(),
            active = setOf(121),
        )

        assertEquals(100, plan.size)
        assertEquals(122, plan.first())
        assertEquals(221, plan.last())
    }
}

class DownloadRetryPolicyTest {
    @Test
    fun `retry capped by attempt count`() {
        assertTrue(DownloadRetryPolicy.shouldRetry(1))
        assertTrue(DownloadRetryPolicy.shouldRetry(2))
        assertFalse(DownloadRetryPolicy.shouldRetry(DownloadRetryPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun `backoff grows exponentially and never exceeds the cap`() {
        assertEquals(30_000L, DownloadRetryPolicy.backoffMillis(1))
        assertEquals(60_000L, DownloadRetryPolicy.backoffMillis(2))
        assertEquals(120_000L, DownloadRetryPolicy.backoffMillis(3))
        assertEquals(
            DownloadRetryPolicy.MAX_BACKOFF_MS,
            DownloadRetryPolicy.backoffMillis(40),
        )
    }
}

class DownloadWorkPolicyTest {
    @Test
    fun `retry only while a bounded attempt remains`() {
        val transient = DownloadRunOutcome(completed = 1, retryable = 2)
        assertEquals(
            DownloadWorkDecision.RETRY,
            DownloadWorkPolicy.decide(transient, attemptNumber = 1),
        )
        assertEquals(
            DownloadWorkDecision.RETRY,
            DownloadWorkPolicy.decide(transient, attemptNumber = 2),
        )
        assertEquals(
            DownloadWorkDecision.EXHAUSTED,
            DownloadWorkPolicy.decide(transient, attemptNumber = DownloadRetryPolicy.MAX_ATTEMPTS),
        )
    }

    @Test
    fun `fatal chapter failures never schedule a worker retry`() {
        val fatal = DownloadRunOutcome(completed = 1, failed = 68)

        assertEquals(
            DownloadWorkDecision.DONE,
            DownloadWorkPolicy.decide(fatal, attemptNumber = 1),
        )
    }

    @Test
    fun `source action stops the worker without spending a retry`() {
        val blocked = DownloadRunOutcome(completed = 57, needsSourceAction = true)

        assertEquals(
            DownloadWorkDecision.DONE,
            DownloadWorkPolicy.decide(blocked, attemptNumber = 1),
        )
    }

    @Test
    fun `network wait retries within the bounded attempts then parks`() {
        val waiting = DownloadRunOutcome(completed = 1, waitingNetwork = true)

        assertEquals(
            DownloadWorkDecision.RETRY,
            DownloadWorkPolicy.decide(waiting, attemptNumber = 1),
        )
        assertEquals(
            DownloadWorkDecision.EXHAUSTED,
            DownloadWorkPolicy.decide(waiting, attemptNumber = DownloadRetryPolicy.MAX_ATTEMPTS),
        )
    }
}

class DownloadNotificationPolicyTest {
    @Test
    fun `source action offers open source only with a URL`() {
        assertEquals(
            listOf(
                DownloadNotificationAction.RETRY,
                DownloadNotificationAction.OPEN_SOURCE,
                DownloadNotificationAction.CANCEL,
            ),
            DownloadNotificationPolicy.actionsFor(
                DownloadState.NEEDS_SOURCE_ACTION,
                hasSourceUrl = true,
            ),
        )
        assertEquals(
            listOf(DownloadNotificationAction.RETRY, DownloadNotificationAction.CANCEL),
            DownloadNotificationPolicy.actionsFor(
                DownloadState.NEEDS_SOURCE_ACTION,
                hasSourceUrl = false,
            ),
        )
    }

    @Test
    fun `network wait offers retry pause and cancel`() {
        assertEquals(
            listOf(
                DownloadNotificationAction.RETRY,
                DownloadNotificationAction.PAUSE,
                DownloadNotificationAction.CANCEL,
            ),
            DownloadNotificationPolicy.actionsFor(
                DownloadState.WAITING_NETWORK,
                hasSourceUrl = false,
            ),
        )
    }

    @Test
    fun `paused and failed notifications keep their recovery action`() {
        assertEquals(
            listOf(DownloadNotificationAction.RESUME, DownloadNotificationAction.CANCEL),
            DownloadNotificationPolicy.actionsFor(DownloadState.PAUSED, hasSourceUrl = false),
        )
        assertEquals(
            listOf(DownloadNotificationAction.RETRY, DownloadNotificationAction.CANCEL),
            DownloadNotificationPolicy.actionsFor(DownloadState.FAILED, hasSourceUrl = false),
        )
    }
}
