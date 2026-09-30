package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class PlayerOwnershipTest {
    @Test fun `casting snapshot preserves an immutable owned source without exposing credentials in default logging`() {
        MpvPlayer().use { player ->
            assertNull(player.currentSourceSnapshot())
            val segments = mutableListOf(PlaybackSegment("https://media.example/part1.mp4?sign=fixture", 3.0),
                PlaybackSegment("https://media.example/part2.mp4?sign=fixture", 4.0))
            val version = player.loadVersioned(PlaybackSource(segments.first().url, cookieHeader = "SESSDATA=fixture-secret",
                title = "Fixture", progressiveSegments = segments))
            val snapshot = assertNotNull(player.currentSourceSnapshot())
            segments.clear()
            assertEquals(version, snapshot.sourceVersion)
            assertEquals(2, snapshot.source.progressiveSegments.size)
            assertEquals(2, assertNotNull(player.currentSourceSnapshot()).source.progressiveSegments.size)
            assertEquals("SESSDATA=fixture-secret", snapshot.source.cookieHeader)
            assertFalse(snapshot.toString().contains("fixture-secret"))
            assertFalse(snapshot.toString().contains("media.example"))
            assertFailsWith<UnsupportedOperationException> { (snapshot.source.progressiveSegments as MutableList<PlaybackSegment>).clear() }
            assertTrue(player.stopIfSourceVersion(version))
            assertNull(player.currentSourceSnapshot())
        }
    }

    @Test fun `a disposed old screen cannot stop a newer source and replay preserves ownership`() {
        MpvPlayer().use { player ->
            val first = player.loadVersioned(PlaybackSource("file:///C:/first.mp4", title = "First"))
            val second = player.loadVersioned(PlaybackSource("file:///C:/second.mp4", title = "Second"))
            assertTrue(second > first)
            assertFalse(player.stopIfSourceVersion(first))
            assertEquals("Second", player.state.value.sourceTitle)
            player.replay()
            assertEquals(second, player.currentSourceVersion)
            assertTrue(player.stopIfSourceVersion(second))
            assertEquals("BiliPai", player.state.value.sourceTitle)
            assertFalse(player.stopIfSourceVersion(second))
        }
    }

    @Test fun `recovery retains one owner and requested pause position while a new item resets software fallback`() {
        MpvPlayer().use { player ->
            val first = player.loadVersioned(PlaybackSource("https://first.example/video.mp4", title = "First"))
            assertTrue(player.recoverSource(first, replacement = PlaybackSource("https://backup.example/video.mp4", title = "First"),
                positionSeconds = 37.5, paused = true, forceSoftwareDecoding = true))
            assertEquals(first, player.currentSourceVersion)
            assertEquals(37.5, player.state.value.positionSeconds)
            assertTrue(player.state.value.paused)
            assertTrue(player.state.value.softwareDecodingRequested)
            val second = player.loadVersioned(PlaybackSource("https://second.example/video.mp4", title = "Second"))
            assertFalse(player.recoverSource(first, positionSeconds = 1.0))
            assertEquals(second, player.currentSourceVersion)
            assertEquals("Second", player.state.value.sourceTitle)
            assertFalse(player.state.value.softwareDecodingRequested)
            assertFalse(player.recoverSource(second, expectedFailureAttemptId = 10))
        }
    }
}
