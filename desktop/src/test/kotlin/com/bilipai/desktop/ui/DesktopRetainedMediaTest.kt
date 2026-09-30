package com.bilipai.desktop.ui

import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopRetainedMediaTest {
    @Test fun `page memory retains background work and closing account cancels its entire lifetime`(): Unit = runBlocking {
        MpvPlayer().use { player ->
            val media = DesktopRetainedMedia(this, player) {}
            val scopeJob = media.live.scope.coroutineContext[Job]!!
            val started = CompletableDeferred<Unit>()
            val child = media.live.scope.launch { started.complete(Unit); awaitCancellation() }
            started.await()
            // Hiding a page performs no lifetime/owner operation; only a window or account closes it.
            assertTrue(child.isActive)
            assertTrue(scopeJob.isActive)
            media.close(); child.join()
            assertFalse(scopeJob.isActive)
            assertTrue(child.isCancelled)
        }
    }

    @Test fun `closing a replaced media page releases its jobs without stopping the new native owner`(): Unit = runBlocking {
        MpvPlayer().use { player ->
            val media = DesktopRetainedMedia(this, player) {}
            val old = player.loadVersioned(PlaybackSource("file:///C:/media-fixture-a.avi", title = "Old"))
            media.bangumi.sourceVersion = old; media.bangumi.loaded = true
            var clearedOverlay = false
            media.bangumi.onBeforeStop = { clearedOverlay = true }
            val replacement = player.loadVersioned(PlaybackSource("file:///C:/media-fixture-b.avi", title = "Replacement"))
            media.bangumi.close()
            assertEquals(replacement, player.currentSourceVersion)
            assertEquals("Replacement", player.state.value.sourceTitle)
            assertNull(media.bangumi.sourceVersion)
            assertFalse(media.bangumi.loaded)
            assertFalse(clearedOverlay)
            media.close()
            assertEquals(replacement, player.currentSourceVersion)
        }
    }

    @Test fun `switching media domains cancels pending old requests and checkpoints before source release`(): Unit = runBlocking {
        MpvPlayer().use { player ->
            var acquired = 0
            val media = DesktopRetainedMedia(this, player) { acquired++ }
            val started = CompletableDeferred<Unit>()
            val request = media.live.scope.launch { started.complete(Unit); awaitCancellation() }
            media.live.playJob = request; started.await()
            val version = player.loadVersioned(PlaybackSource("file:///C:/media-fixture.avi", title = "Offline"))
            media.offline.sourceVersion = version; media.offline.loaded = true
            var checkpointed = false
            media.offline.checkpoint = { assertEquals(version, player.currentSourceVersion); checkpointed = true }
            var clearedOverlay = false
            media.offline.onBeforeStop = { assertEquals(version, player.currentSourceVersion); clearedOverlay = true }
            media.acquire(media.bangumi)
            request.join()
            assertTrue(request.isCancelled)
            assertTrue(checkpointed)
            assertEquals(1, acquired)
            assertNull(player.currentSourceSnapshot())
            assertFalse(player.stopIfSourceVersion(version))
            assertTrue(clearedOverlay)
            assertNull(media.current)
            media.close()
        }
    }

    @Test fun `same source recovery keeps retained ownership and shutdown releases it once`(): Unit = runBlocking {
        MpvPlayer().use { player ->
            val media = DesktopRetainedMedia(this, player) {}
            val version = player.loadVersioned(PlaybackSource("file:///C:/media-fixture.avi", title = "Owned"))
            media.offline.sourceVersion = version; media.offline.loaded = true
            assertSame(media.offline, media.current)
            assertTrue(player.recoverSource(version, positionSeconds = 17.0, paused = true))
            assertSame(media.offline, media.current)
            var snapshots = 0
            media.offline.checkpoint = { snapshots++; assertEquals(17.0, player.state.value.positionSeconds) }
            media.close(); media.close()
            assertEquals(1, snapshots)
            assertNull(player.currentSourceSnapshot())
            assertFalse(player.stopIfSourceVersion(version))
        }
    }

    @Test fun `a cancelled old request cannot clear the replacement opening gate`(): Unit = runBlocking {
        MpvPlayer().use { player ->
            val media = DesktopRetainedMedia(this, player) {}
            val firstReady = CompletableDeferred<Unit>()
            val secondReady = CompletableDeferred<Unit>()
            val oldFinished = CompletableDeferred<Boolean>()
            media.live.opening = true
            media.live.launchRequest {
                try { firstReady.complete(Unit); awaitCancellation() }
                finally {
                    val current = media.live.isCurrentRequest()
                    if (current) media.live.opening = false
                    oldFinished.complete(current)
                }
            }
            firstReady.await()
            media.live.launchRequest { secondReady.complete(Unit); awaitCancellation() }
            secondReady.await()
            assertFalse(oldFinished.await())
            assertTrue(media.live.opening)
            assertTrue(media.live.playJob!!.isActive)
            media.close()
            assertFalse(media.live.opening)
        }
    }
}
