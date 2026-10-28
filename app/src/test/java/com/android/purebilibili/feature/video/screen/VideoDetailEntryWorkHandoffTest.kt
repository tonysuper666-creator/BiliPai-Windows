package com.android.purebilibili.feature.video.screen

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoDetailEntryWorkHandoffTest {
    @Test
    fun bodyPlaybackAndCommentsEachGetAFrameBeforeNextWorkStarts() = runTest {
        val clock = BroadcastFrameClock()
        val events = mutableListOf<String>()
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            runVideoDetailEntryWorkHandoff(
                onPlaybackReady = { events += "playback" },
                onCommentsReady = { events += "comments" },
                onRelatedReady = { events += "related" },
            )
        }
        clock.sendFrame(1L)
        runCurrent()
        assertTrue(events.isEmpty())
        clock.sendFrame(2L)
        runCurrent()
        assertEquals(listOf("playback"), events)
        clock.sendFrame(3L)
        runCurrent()
        assertEquals(listOf("playback"), events)
        clock.sendFrame(4L)
        runCurrent()
        assertEquals(listOf("playback", "comments"), events)
        clock.sendFrame(5L)
        runCurrent()
        assertEquals(listOf("playback", "comments"), events)
        clock.sendFrame(6L)
        runCurrent()
        assertEquals(listOf("playback", "comments", "related"), events)
        assertTrue(job.isCompleted)
    }

    @Test
    fun leavingBeforeBodyFrameDoesNotStartMediaOrComments() = runTest {
        val clock = BroadcastFrameClock()
        val events = mutableListOf<String>()
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            runVideoDetailEntryWorkHandoff(
                onPlaybackReady = { events += "playback" },
                onCommentsReady = { events += "comments" },
                onRelatedReady = { events += "related" },
            )
        }
        clock.sendFrame(1L)
        runCurrent()
        job.cancel()
        runCurrent()
        clock.sendFrame(2L)
        runCurrent()
        assertTrue(events.isEmpty())
    }

    @Test
    fun leavingAfterMediaReleaseCancelsPendingCommentWork() = runTest {
        val clock = BroadcastFrameClock()
        val events = mutableListOf<String>()
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            runVideoDetailEntryWorkHandoff(
                onPlaybackReady = { events += "playback" },
                onCommentsReady = { events += "comments" },
                onRelatedReady = { events += "related" },
            )
        }
        repeat(2) { index ->
            clock.sendFrame(index.toLong() + 1L)
            runCurrent()
        }
        job.cancel()
        runCurrent()
        repeat(2) { index ->
            clock.sendFrame(index.toLong() + 3L)
            runCurrent()
        }
        assertEquals(listOf("playback"), events)
    }
    @Test
    fun leavingAfterCommentReleaseCancelsRecommendationMount() = runTest {
        val clock = BroadcastFrameClock()
        val events = mutableListOf<String>()
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            runVideoDetailEntryWorkHandoff(
                onPlaybackReady = { events += "playback" },
                onCommentsReady = { events += "comments" },
                onRelatedReady = { events += "related" },
            )
        }
        repeat(4) { index ->
            clock.sendFrame(index.toLong() + 1L)
            runCurrent()
        }
        job.cancel()
        runCurrent()
        repeat(2) { index ->
            clock.sendFrame(index.toLong() + 5L)
            runCurrent()
        }
        assertEquals(listOf("playback", "comments"), events)
    }

}
