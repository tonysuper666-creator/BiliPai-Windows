package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.BuiltinPlugin
import com.android.purebilibili.plugin.sdk.PlayerPluginApi
import com.android.purebilibili.plugin.sdk.PlayerPluginSession
import com.android.purebilibili.plugin.sdk.SkipAction
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.DesktopPlayerPluginSessions
import com.bilipai.desktop.ui.DesktopOriginalVideoRootGate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Tests the product admission functions, real Root gate and real session host.
 * Full Runtime construction needs the Windows JS worker catalog; its setEnabled
 * and sponsor read paths are exercised by sponsorPresentationTests on Windows. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPluginVideoOwnershipTest {
    private class Definition : BuiltinPlugin, PlayerPluginApi {
        override val id = "ownership-player"
        override val name = id
        override val description = id
        override val version = "1"
        var loads = 0
        override fun createSession() = object : PlayerPluginSession {
            override suspend fun onVideoLoad(bvid: String, cid: Long) { loads++ }
            override suspend fun onPositionUpdate(positionMs: Long): SkipAction? = null
            override fun onVideoEnd() = Unit
        }
    }

    private class Fixture(scope: CoroutineScope) : AutoCloseable {
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val parent = SupervisorJob(scope.coroutineContext[Job])
        val gate = DesktopOriginalVideoRootGate(repository, repository.sessionEpoch, { true },
            CoroutineScope(scope.coroutineContext + parent))
        val video = Any()
        var currentVideo: Any? = video
        var generation = 1L
        var epoch = repository.sessionEpoch
        var closing = false
        var nativeOwned = true
        val load = Job(gate.scope.coroutineContext[Job])
        val stillOwned = { desktopPlaybackLoadStillOwned(load) { gate.owns() && nativeOwned } }

        fun current() = desktopPluginVideoIsCurrent(closing, currentVideo, video,
            generation, 1L, epoch, repository.sessionEpoch, stillOwned)

        fun assertRetired() {
            assertFalse(current())
            val failure = assertFailsWith<CancellationException> {
                requireDesktopPlaybackPluginDispatchCurrent(current())
            }
            assertEquals("Playback plugin dispatch retired", failure.message)
        }

        override fun close() {
            gate.retire()
            parent.cancel()
            repository.httpClient.dispatcher.executorService.shutdownNow()
            repository.httpClient.connectionPool.evictAll()
        }
    }

    @Test fun normallyCompletedLoadKeepsAdmissionForNewPlayerSession() = runTest {
        Fixture(this).use { fixture ->
            fixture.load.complete()
            assertFalse(fixture.load.isActive)
            assertFalse(fixture.load.isCancelled)
            assertTrue(fixture.gate.owns())
            assertTrue(fixture.current())
            val definition = Definition()
            val sessions = DesktopPlayerPluginSessions(this, "BV1", 1,
                load = { _, session, bvid, cid ->
                    requireDesktopPlaybackPluginDispatchCurrent(fixture.current())
                    session.onVideoLoad(bvid, cid)
                }, failed = { _, failure -> throw failure })
            try {
                sessions.update(emptyList())
                sessions.update(listOf(definition))
                runCurrent()
                sessions.awaitLoads()
                assertEquals(1, definition.loads)
                assertEquals(1, sessions.ready().size)
            } finally { sessions.close() }
        }
    }

    @Test fun gateRetirementInvalidatesNormallyCompletedLoad() = runTest {
        Fixture(this).use { fixture ->
            fixture.load.complete()
            fixture.gate.retire()
            assertFalse(fixture.load.isCancelled) // a completed caller cannot be canceled later
            fixture.assertRetired()
        }
    }

    @Test fun parentCancellationInvalidatesNormallyCompletedLoad() = runTest {
        Fixture(this).use { fixture ->
            fixture.load.complete()
            fixture.parent.cancel()
            assertFalse(fixture.load.isCancelled)
            assertFalse(fixture.gate.owns())
            fixture.assertRetired()
        }
    }

    @Test fun cancellationDuringLoadInvalidatesAdmissionEvenWithLiveGateAndNative() = runTest {
        Fixture(this).use { fixture ->
            fixture.load.cancel()
            assertTrue(fixture.gate.owns())
            assertTrue(fixture.nativeOwned)
            fixture.assertRetired()
        }
    }

    @Test fun nativeRetirementInvalidatesNormallyCompletedLoad() = runTest {
        Fixture(this).use { fixture ->
            fixture.load.complete()
            fixture.nativeOwned = false
            fixture.assertRetired()
        }
    }

    @Test fun replacedVideoGenerationAccountAndClosedRuntimeRejectOldDispatch() = runTest {
        Fixture(this).use { fixture ->
            fixture.load.complete()
            fixture.currentVideo = Any()
            fixture.assertRetired()
            fixture.currentVideo = fixture.video
            fixture.generation++
            fixture.assertRetired()
            fixture.generation = 1L
            fixture.epoch++
            fixture.assertRetired()
            fixture.epoch = fixture.repository.sessionEpoch
            fixture.closing = true
            fixture.assertRetired()
        }
    }

    @Test fun completedLoadDoesNotReviveReplacedSessionReceipt() = runTest {
        Fixture(this).use { fixture ->
            fixture.load.complete()
            val definition = Definition()
            val sessions = DesktopPlayerPluginSessions(this, "BV1", 1,
                load = { _, session, bvid, cid ->
                    requireDesktopPlaybackPluginDispatchCurrent(fixture.current())
                    session.onVideoLoad(bvid, cid)
                }, failed = { _, failure -> throw failure })
            try {
                sessions.update(listOf(definition)); runCurrent(); sessions.awaitLoads()
                val old = sessions.ready().single()
                val reloading = launch { sessions.reload(definition.id) }
                runCurrent(); reloading.join()
                assertTrue(fixture.current())
                assertFalse(sessions.isCurrent(old))
                assertFailsWith<CancellationException> { sessions.ensureLoaded(old) }
                assertEquals(2, definition.loads)
            } finally { sessions.close() }
        }
    }
}
