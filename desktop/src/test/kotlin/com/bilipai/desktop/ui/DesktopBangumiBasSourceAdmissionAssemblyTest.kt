package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.model.response.BangumiEpisode
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.data.BiliApiException
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.EventQueue
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

import com.android.purebilibili.core.store.DesktopOriginalPortraitSettings
import com.android.purebilibili.feature.video.playback.audio.*
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.serialization.json.JsonObject

import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasTarget
import com.bilipai.desktop.danmaku.*
import java.awt.Font
import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual production BAS component used by Entry, real Overlay XML/Mode9 processing,
 * Assembly/Store/EntryGate/native acceptance, original generated PGC VM and action mapper.
 * BasRegistration only observes actual private Overlay callback objects for stale dispatch.
 * No copied owns/acquire algorithm, standalone permit, fabricated Success or navigation counter.
 * Physical Entry/Factory/Runtime/Compose/PiP/route/window, native DLL/GPU and seek ACK excluded. */
class DesktopBangumiBasSourceAdmissionAssemblyTest {
    @Test fun actualPresentationRetirementRejectsBeforeActionButAdmittedErrorsRemainVisible(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(); val before = f.accepted()
            f.ui {
                var calls = 0
                assertTrue(f.withPresentationAdmission(before) { calls++ })
                assertEquals(1, calls)
                for (apiCode in listOf(-101, -412)) {
                    val expected = BiliApiException(apiCode, "actual admitted callback failure")
                    val propagated = assertFailsWith<BiliApiException> {
                        f.withPresentationAdmission(before) { calls++; throw expected }
                    }
                    assertSame(expected, propagated)
                }
                assertEquals(3, calls); assertSame(before, f.owner.native.current())
                val serials = f.player.capturePlaybackIntentSerials()
                val epoch = f.repository.sessionEpoch
                val revision = f.repository.playbackAuthorizationRevision.value
                assertEquals(41L, f.sessions.activeAccountMid())
                val admittedFailure = BiliApiException(-101, "callback retired its own actual receipt")
                val propagatedAfterRetirement = assertFailsWith<BiliApiException> {
                    f.withPresentationAdmission(before) {
                        calls++
                        assertTrue(f.sessions.setPlaybackAccountMid(42, epoch, f.gate::owns))
                        throw admittedFailure
                    }
                }
                assertSame(admittedFailure, propagatedAfterRetirement); assertEquals(4, calls)
                assertEquals(epoch, f.repository.sessionEpoch)
                assertTrue(f.repository.playbackAuthorizationRevision.value > revision)
                assertFalse(f.repository.isPlaybackReceiptCurrent(checkNotNull(before.nativeSource.source.authorizationReceipt)))
                assertFalse(f.withPresentationAdmission(before) { calls++ })
                assertEquals(4, calls); assertNull(f.owner.native.current())
                var physical = assertNotNull(f.player.currentSourceSnapshot())
                assertEquals(before.sourceVersion, physical.sourceVersion)
                assertEquals(before.nativeSource.source, physical.source)
                assertEquals(serials, f.player.capturePlaybackIntentSerials())
                // Actual generation changes: Store rejects before evaluating its presentation predicate.
                assertTrue(f.sessions.activateAccount(42))
                assertEquals(42L, f.sessions.activeAccountMid())
                assertTrue(f.repository.sessionEpoch > epoch); assertFalse(f.gate.owns())
                val actualSlot = f.reflect(f, "slot") as DesktopOriginalVideoRootOwnerSlot
                assertSame(f.owner, actualSlot.assemblies.value); assertNull(actualSlot.currentAssembly())
                assertFalse(f.withPresentationAdmission(before) { calls++ })
                assertEquals(4, calls)
                physical = assertNotNull(f.player.currentSourceSnapshot())
                assertEquals(before.sourceVersion, physical.sourceVersion)
                assertEquals(before.nativeSource.source, physical.source)
                assertEquals(serials, f.player.capturePlaybackIntentSerials())
            }
            assertTrue(f.urls.isEmpty()); assertTrue(f.preferenceWrites.isEmpty())
        }
    }

    @Test fun actualLoadingRejectsFormalSceneUntilOriginalNextEpisodeSuccess(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load()
            val old = f.accepted(); val protocol = ProtocolGate()
            f.detailGate = protocol
            try {
                f.ui { f.vm.loadBangumiPlay(19, 42) }
                f.awaitEntered(protocol.entered)
                f.ui {
                    assertSame(BangumiPlayerState.Loading, f.vm.uiState.value)
                    assertSame(old, f.owner.native.current())
                    assertTrue(f.bas.isBasPresenterCurrent()); assertFalse(f.bas.ownsBasSource(old))
                }
                val rejected = f.acquire(old)
                assertFalse(f.uiResult { rejected.owned() })
                f.loadDocument(old); f.drainBas()
                assertEquals(1, f.xmlCalls.get())
                f.ui {
                    assertNull(f.installation()); assertTrue(f.rawItems().isEmpty())
                    assertEquals(0, f.overlay.commentCount.value)
                    assertFalse(rejected.activate(BasTarget.Seek(12_345)))
                }
                protocol.release.countDown()
                f.awaitEpisode(42)
                val next = f.accepted()
                assertNotSame(old, next); assertEquals(105L, next.request.cid)
                f.ui { assertTrue(f.bas.ownsBasSource(next)); assertFalse(rejected.owned()) }
                val fresh = f.acquire(next)
                f.loadDocument(next); f.awaitInstalled()
                f.ui {
                    assertTrue(fresh.owned()); assertEquals("actual PGC BAS", f.items().single().content)
                    assertEquals(BAS_SCRIPT, f.items().single().source)
                    assertTrue(f.overlay.basRejections.value.isEmpty())
                    assertFalse(rejected.activate(BasTarget.Seek(1)))
                }
                assertEquals(2, f.xmlCalls.get()); assertTrue(f.urls.isEmpty())
            } finally { protocol.release.countDown() }
        }
    }

    @Test fun sameNumericVersionRecoveryRetiresOldBindingAndReinstallsSameRawBas(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(); val before = f.accepted(); val original = f.state()
            val prior = f.acquire(before); f.loadDocument(before); f.awaitInstalled()
            val raw = f.uiResult { f.rawItems() }; val installed = f.uiResult { assertNotNull(f.installation()) }
            val calls = f.xmlCalls.get(); val requests = f.playCalls.get()
            f.failAndQueue(); f.awaitAac(); withTimeout(8_000) { f.playbackJob().join() }
            val next = f.accepted(); assertNotSame(before, next)
            assertEquals(before.sourceVersion, next.sourceVersion)
            assertNotEquals(before.nativeSource.source, next.nativeSource.source)
            assertSame(before.nativeSource.source.authorizationReceipt, next.nativeSource.source.authorizationReceipt)
            val observed = f.acceptedBasObservations.single { it.publication === next }
            // Actual recovery accepts its child before updating original UI metadata.
            assertSame(original, observed.originalState); assertFalse(observed.current)
            f.ui {
                assertTrue(f.bas.ownsBasSource(next)); assertFalse(prior.owned())
                assertFalse(prior.activate(BasTarget.Seek(12_345)))
                assertFalse(f.installationCurrent())
                assertSame(raw, f.rawItems())
            }
            val fresh = f.acquire(next); f.awaitInstalled()
            f.ui {
                assertTrue(fresh.owned()); assertNotSame(installed, f.installation())
                assertSame(raw, f.rawItems()); assertSame(raw.single(), f.items().single())
                assertEquals(next.nativeSource.source, f.installationSource().source)
                // Closing the original registration cannot unregister its successor.
                prior.close(); assertTrue(fresh.owned()); assertTrue(f.installationCurrent())
                assertSame(fresh.actualBinding, f.actualBasBinding())
            }
            assertEquals(calls, f.xmlCalls.get()); assertEquals(requests, f.playCalls.get())
            assertTrue(f.urls.isEmpty()); assertTrue(f.preferenceWrites.isEmpty())
        }
    }

    @Test fun actualTokenEpisodeAndAccountRetirementRejectPublicationAndOldActions(): Unit = runBlocking {
        for (mutation in listOf("request", "episode", "account")) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val processor = DesktopBasPluginProcessor(filter = { item ->
                entered.countDown(); check(release.await(8, TimeUnit.SECONDS)); item
            }, style = { null })
            Fixture(basProcessor = processor).useSuspending { f ->
                val protocol = ProtocolGate()
                try {
                    f.load(); val before = f.accepted(); val load = f.owner.captureLoadState()
                    val registered = f.acquire(before); f.loadDocument(before); f.awaitEntered(entered)
                    val worker = f.uiResult { f.basJob() }
                    val emptyBeforeMutation = f.uiResult {
                        f.installation().also { installation ->
                            if (installation != null) {
                                assertTrue((f.reflect(installation, "raw") as List<*>).isEmpty())
                                assertTrue((f.reflect(installation, "items") as List<*>).isEmpty())
                            }
                        }
                    }
                    f.playGate = protocol
                    f.ui {
                        when (mutation) {
                            "request" -> f.vm.reloadCurrentEpisode()
                            "episode" -> f.vm.switchEpisode(f.state().seasonDetail.episodes!![1])
                            "account" -> {
                                val epoch = f.repository.sessionEpoch
                                val revision = f.repository.playbackAuthorizationRevision.value
                                assertTrue(f.sessions.setPlaybackAccountMid(42, epoch, f.gate::owns))
                                assertTrue(f.sessions.setPlaybackAccountMid(null, epoch, f.gate::owns))
                                assertEquals(epoch, f.repository.sessionEpoch)
                                assertTrue(f.repository.playbackAuthorizationRevision.value > revision)
                            }
                        }
                    }
                    if (mutation != "account") f.awaitEntered(protocol.entered)
                    f.ui {
                        when (mutation) {
                            "request" -> {
                                val actual = f.owner.captureLoadState()
                                // beginLoadRequest advances tokens first; StateFlow retains an equal request value.
                                assertSame(load.currentRequest, actual.currentRequest)
                                assertTrue(actual.currentLoadRequestToken > load.currentLoadRequestToken)
                                assertEquals(before.request.cid, actual.currentRequest!!.cid)
                                // The actual Portrait replacement synchronously stops the prior full source.
                                assertNull(f.owner.native.current()); assertFalse(f.owner.native.isCurrent(before))
                                assertNull(f.player.currentSourceSnapshot())
                                assertTrue(f.player.currentSourceVersion > before.sourceVersion)
                                assertFalse(f.player.ownsSourceSnapshot(before.nativeSource))
                                assertSame(before, f.publications.last()) // Held playURL has not accepted a successor.
                            }
                            "episode" -> {
                                assertEquals(42L, f.state().currentEpisode.id)
                                assertEquals(105L, f.owner.captureLoadState().currentRequest!!.cid)
                                assertFalse(f.bas.ownsBasSource(before))
                            }
                            "account" -> {
                                assertNull(f.owner.native.current())
                                assertFalse(f.repository.isPlaybackReceiptCurrent(checkNotNull(before.nativeSource.source.authorizationReceipt)))
                                val snapshot = assertNotNull(f.player.currentSourceSnapshot())
                                assertEquals(before.sourceVersion, snapshot.sourceVersion)
                                assertEquals(before.nativeSource.source, snapshot.source)
                            }
                        }
                        assertFalse(registered.owned(), mutation)
                        val serials = f.player.capturePlaybackIntentSerials()
                        assertFalse(registered.activate(BasTarget.Seek(12_345)), mutation)
                        assertFalse(registered.activate(BasTarget.Video(1, "BV1xx411c7mD", 3, 12_345)), mutation)
                        assertEquals(serials, f.player.capturePlaybackIntentSerials())
                    }
                    release.countDown(); f.drainBas(worker)
                    f.ui {
                        val installation = f.installation()
                        assertTrue(installation == null || installation === emptyBeforeMutation, mutation)
                        if (installation != null) assertTrue((f.reflect(installation, "raw") as List<*>).isEmpty(), mutation)
                        assertFalse(f.installationCurrent(), mutation); assertTrue(f.items().isEmpty())
                        assertEquals(0, f.overlay.commentCount.value)
                        assertEquals(1, f.rawItems().size)
                    }
                    assertTrue(f.urls.isEmpty()); assertTrue(f.preferenceWrites.isEmpty())
                    protocol.release.countDown()
                    if (mutation != "account") withTimeout(8_000) { f.playbackJob().join() }
                } finally { release.countDown(); protocol.release.countDown() }
            }
        }
    }

    @Test fun closingActualBindingRejectsLateProcessedBasWithoutStandaloneFallback(): Unit = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val processor = DesktopBasPluginProcessor(filter = { item ->
            entered.countDown(); check(release.await(8, TimeUnit.SECONDS)); item
        }, style = { null })
        Fixture(basProcessor = processor).useSuspending { f ->
            try {
                f.load(); val before = f.accepted(); val registered = f.acquire(before)
                f.loadDocument(before); f.awaitEntered(entered)
                val worker = f.uiResult { f.basJob() }
                val emptyBeforeClose = f.uiResult {
                    f.installation().also { installation ->
                        if (installation != null) {
                            assertTrue((f.reflect(installation, "raw") as List<*>).isEmpty())
                            assertTrue((f.reflect(installation, "items") as List<*>).isEmpty())
                        }
                    }
                }
                f.ui {
                    registered.close(); assertNull(f.actualBasBinding())
                    assertFalse(registered.owned()); assertTrue(f.bas.ownsBasSource(before))
                    assertTrue(f.player.ownsSourceSnapshot(before.nativeSource))
                    assertFalse(registered.activate(BasTarget.Seek(1)))
                }
                release.countDown(); f.drainBas(worker)
                f.ui {
                    val installation = f.installation()
                    assertTrue(installation == null || installation === emptyBeforeClose)
                    if (installation != null) assertTrue((f.reflect(installation, "raw") as List<*>).isEmpty())
                    assertFalse(f.installationCurrent()); assertTrue(f.items().isEmpty())
                    assertEquals(1, f.rawItems().size); assertEquals(0, f.overlay.commentCount.value)
                }
                val fresh = f.acquire(before); f.awaitInstalled()
                f.ui {
                    assertTrue(fresh.owned()); assertTrue(f.installationCurrent())
                    assertNotSame(emptyBeforeClose, f.installation())
                    assertSame(f.rawItems().single(), f.items().single())
                    assertEquals(before.nativeSource.source, f.installationSource().source)
                    assertEquals("actual PGC BAS", f.items().single().content)
                    assertFalse(registered.owned()); assertSame(fresh.actualBinding, f.actualBasBinding())
                }
                assertEquals(1, f.xmlCalls.get()); assertTrue(f.urls.isEmpty())
            } finally { release.countDown() }
        }
    }

    @Test fun actualBasMapperAdmitsOriginalSeekAndPausesBeforeExternalCallbackOutsideGates(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(); val before = f.accepted(); val registered = f.acquire(before)
            f.loadDocument(before); f.awaitInstalled()
            f.ui {
                val serials = f.player.capturePlaybackIntentSerials()
                assertTrue(registered.activate(BasTarget.Seek(12_345)))
                val queued = f.lastQueuedSeek()
                assertEquals(12.345, f.reflect(queued, "seconds") as Double)
                assertTrue((f.reflect(queued, "id") as Long) > serials.second)
                assertTrue(f.urls.isEmpty()); assertSame(before, f.owner.native.current())
                // Do not drain this command or manufacture a native seek ACK.
                f.owner.section.play(); assertFalse(f.player.state.value.paused)
                assertTrue(registered.activate(BasTarget.Video(1, "BV1xx411c7mD", 3, 12_345)))
                assertTrue(f.player.state.value.paused)
                assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=3&t=12.345", f.urls.single().toASCIIString())
                f.owner.section.play()
                assertTrue(registered.activate(BasTarget.Bangumi(7, 42, 1_000)))
                assertEquals("https://www.bilibili.com/bangumi/play/ep42?t=1", f.urls.last().toASCIIString())
                assertTrue(f.player.ownsSourceSnapshot(before.nativeSource)); assertTrue(registered.owned())
                val operations = f.player.capturePlaybackIntentSerials(); val count = f.urls.size
                registered.close()
                assertFalse(registered.activate(BasTarget.Video(1, "BV1xx411c7mD", 3, 12_345)))
                assertEquals(operations, f.player.capturePlaybackIntentSerials()); assertEquals(count, f.urls.size)
            }
            assertTrue(f.preferenceWrites.isEmpty())
        }
    }

    // These cases use the production mount marker and real generated VM/Assembly.
    // Fixture.initialize already attaches its real player. Actual Entry/Compose
    // remounts are excluded; the complete Screen producer has a separate byte test.
    @Test fun screenRemountKeepsTheActualPendingNewEpisodeInsteadOfReplayingTheOldRoute(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val mount = DesktopBangumiPgcScreenMount()
            f.ui { assertTrue(mount.runInitialLoad { f.vm.loadBangumiPlay(19, 41) }) }
            f.awaitEpisode(41)
            val old = f.accepted(); val protocol = ProtocolGate()
            f.detailGate = protocol
            try {
                f.ui { f.vm.loadBangumiPlay(19, 42) }
                f.awaitEntered(protocol.entered)
                val pending = f.playbackJob()
                f.ui {
                    val revision = f.vm.desktopPlaybackIntentRevision
                    assertSame(BangumiPlayerState.Loading, f.vm.uiState.value)
                    assertEquals(42L, f.reflect(f.vm, "currentEpId"))
                    assertFalse(mount.runInitialLoad { f.vm.loadBangumiPlay(19, 41) })
                    assertEquals(revision, f.vm.desktopPlaybackIntentRevision)
                    assertSame(pending, f.playbackJob()); assertFalse(pending.isCancelled)
                    assertSame(old, f.owner.native.current())
                }
                protocol.release.countDown()
                f.awaitEpisode(42)
                f.ui { assertEquals(42L, f.state().currentEpisode.id) }
            } finally { protocol.release.countDown() }
        }
    }

    @Test fun screenRemountKeepsTheActualErrorAndDirectEpisodeRetryStillWorks(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val mount = DesktopBangumiPgcScreenMount()
            f.ui { assertTrue(mount.runInitialLoad { f.vm.loadBangumiPlay(19, 41) }) }
            f.awaitEpisode(41)
            f.detailFailure = true
            f.ui { f.vm.loadBangumiPlay(19, 42) }
            withTimeout(8_000) { f.playbackJob().join() }
            f.ui {
                val failure = assertIs<BangumiPlayerState.Error>(f.vm.uiState.value)
                val revision = f.vm.desktopPlaybackIntentRevision
                val completed = f.playbackJob()
                assertEquals(42L, f.reflect(f.vm, "currentEpId"))
                assertFalse(mount.runInitialLoad { f.vm.loadBangumiPlay(19, 41) })
                assertSame(failure, f.vm.uiState.value)
                assertEquals(revision, f.vm.desktopPlaybackIntentRevision)
                assertSame(completed, f.playbackJob())
            }
            f.detailFailure = false
            // The actual Error button command retains current episode 42 and forces reload.
            f.ui { f.vm.retry() }
            f.awaitEpisode(42)
        }
    }

    @Test fun repeatedScreenMountKeepsActualPublicationAndPendingSeekWithoutReload(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val mount = DesktopBangumiPgcScreenMount()
            f.ui { assertTrue(mount.runInitialLoad { f.vm.loadBangumiPlay(19, 41) }) }
            f.awaitEpisode(41)
            f.ui {
                f.owner.section.seekTo(12_345L)
                val publication = f.accepted(); val state = f.state()
                val serials = f.player.capturePlaybackIntentSerials()
                val queued = f.lastQueuedSeek(); val job = f.playbackJob()
                val revision = f.vm.desktopPlaybackIntentRevision; val calls = f.playCalls.get()
                repeat(3) {
                    // Same retained Entry may cover/uncover its Screen or change presentation.
                    assertFalse(mount.runInitialLoad { f.vm.loadBangumiPlay(19, 41, 0) })
                }
                assertSame(publication, f.accepted()); assertSame(state, f.state())
                assertSame(queued, f.lastQueuedSeek()); assertSame(job, f.playbackJob())
                assertEquals(serials, f.player.capturePlaybackIntentSerials())
                assertEquals(revision, f.vm.desktopPlaybackIntentRevision)
                assertEquals(calls, f.playCalls.get())
            }
        }
    }

    @Test fun failedSynchronousMountPropagatesCancellationAndOnlyThatEntryCanRetry(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val mount = DesktopBangumiPgcScreenMount()
            f.ui {
                val cancellation = CancellationException("synthetic synchronous Screen cancellation")
                val propagated = assertFailsWith<CancellationException> {
                    mount.runInitialLoad {
                        assertFalse(mount.runInitialLoad { error("reentrant mount must not run") })
                        throw cancellation
                    }
                }
                assertSame(cancellation, propagated)
                assertTrue(mount.runInitialLoad { f.vm.loadBangumiPlay(19, 41) })
                assertFalse(mount.runInitialLoad { error("completed Entry must not replay") })
                // Marker history belongs to its instance; no process-global session bit.
                var otherEntryCalls = 0
                val otherEntry = DesktopBangumiPgcScreenMount()
                assertTrue(otherEntry.runInitialLoad { otherEntryCalls++ })
                assertEquals(1, otherEntryCalls)
                assertFalse(otherEntry.runInitialLoad { otherEntryCalls++ })
                assertEquals(1, otherEntryCalls)
            }
            f.awaitEpisode(41)
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
    private class ProtocolGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() { entered.countDown(); check(release.await(8, TimeUnit.SECONDS)) }
    }
    private data class AcceptedBasObservation(val publication: DesktopOriginalVideoAcceptedPublication,
        val originalState: BangumiPlayerState, val current: Boolean)
    private class BasRegistration(val lease: AutoCloseable, val actualBinding: Any,
        val owned: () -> Boolean, val activate: (BasTarget) -> Boolean) : AutoCloseable {
        override fun close() = lease.close()
    }
    private inner class Fixture(private val hevcSupported: Boolean = false, private val hiRes: Boolean = true,
        private val basProcessor: DesktopBasPluginProcessor? = null) {
        val directory = Files.createTempDirectory("pgc-bas-real-owner-")
        private val createdAbsolute = directory.toAbsolutePath().normalize()
        private val createdReal = directory.toRealPath()
        private val tempAbsolute = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        private val tempReal = tempAbsolute.toRealPath()
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        private val parentJob = SupervisorJob()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val parentScope = CoroutineScope(parentJob + Dispatchers.Swing + CoroutineExceptionHandler { _, error -> failures += error })
        private val slot = DesktopOriginalVideoRootOwnerSlot(repository, parentScope, parentJob::isActive)
        lateinit var gate: DesktopOriginalVideoRootGate
        lateinit var owner: DesktopOriginalVideoOwnerAssembly
        val readOnlyFacts = CopyOnWriteArrayList<Triple<BangumiDetail, BangumiEpisode, Int>>()
        lateinit var native: DesktopOriginalBangumiNativePresenter
        lateinit var media: DesktopOriginalVideoRootMediaFactory
        lateinit var vm: BangumiPlayerViewModel
        lateinit var settings: DesktopOriginalPlayerSettingsContext
        private lateinit var requests: DesktopOriginalVideoOwnerRequestFactory
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("settings")))
        val player = MpvPlayer()
        lateinit var actor: PremiumRecoveryActor
        lateinit var recovery: DesktopOriginalPlaybackRecovery
        lateinit var component: DesktopOriginalBangumiPremiumAudioRecovery
        lateinit var bas: DesktopOriginalBangumiBasSourceBinding
        lateinit var overlay: DanmakuOverlay
        val acceptedBasObservations = CopyOnWriteArrayList<AcceptedBasObservation>()
        val registrations = CopyOnWriteArrayList<BasRegistration>()
        val urls = CopyOnWriteArrayList<URI>()
        val xmlCalls = AtomicInteger()
        @Volatile var detailGate: ProtocolGate? = null
        @Volatile var detailFailure = false
        @Volatile var playGate: ProtocolGate? = null
        val preparations = CopyOnWriteArrayList<DesktopOriginalNativeRecoveryTicket>()
        val publications = CopyOnWriteArrayList<DesktopOriginalVideoAcceptedPublication>()
        val preferenceWrites = CopyOnWriteArrayList<Int>()
        val launchedJobs = CopyOnWriteArrayList<Job>()
        val playCalls = AtomicInteger()
        val playQualities = CopyOnWriteArrayList<Int>()
        var replyMode = "initial"
        private val audioPreference = if (hiRes) AUDIO_QUALITY_HI_RES else AUDIO_QUALITY_AUTO
        var beforePreparation: ((DesktopOriginalNativeRecoveryTicket) -> Unit)? = null
        private lateinit var cache: DesktopMediaByteCache
        private lateinit var subtitles: DesktopSubtitleAssets
        private val privacy = DesktopSearchPreferences(directory.resolve("privacy"))
        val toasts = CopyOnWriteArrayList<String>()
        private val versions = MutableStateFlow<Long?>(null)
        // Exact object received from the real Assembly accepted callback, matching
        // RuntimeBinding.accepted's published-reference projection. No manufactured source.
        private val published = AtomicReference<DesktopOriginalVideoAcceptedPublication?>()
        private val transport: OkHttpClient
        private val epoch: Long
        private fun rootOwns() = parentJob.isActive && gate.owns()
        private fun owns() = rootOwns() && slot.currentAssembly() === owner && native.owns()
        private fun assertOwned() { if (!owns()) throw CancellationException("Actual retained CPU slot retired") }
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) {
                check(bvid in setOf("BV1GJ411x7h7", "BV17x411w7KC")); check(cid in setOf(104L, 105L))
            }
        }
        private val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            override fun isHevcSupported() = hevcSupported
            override fun isAv1Supported() = true
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = false
            override fun isDolbySoftwareAudioDecoderRequired() = false
        }
        private val network = object : DesktopOriginalVideoOwnerNetwork {
            override fun isMobileData() = false
            override fun isWifi() = true
            override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                DesktopHomeNetworkObservation(true, 3, 71L, false, "cpu-memory-transport"),
                downstreamBandwidthKbps = null,
            )
            override fun cdnNetwork() = DesktopCdnNetworkObservation(true, "cpu-memory-transport")
        }
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-secondary", "bili_jct" to "synthetic-secondary-csrf"),
                AccountSummary(42, "Secondary", "", isVip = true))
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"),
                AccountSummary(41, "Fixture", "", isVip = true))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor", "buvid4" to "synthetic-visitor4"), sessions.generation)
            epoch = repository.sessionEpoch
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    check(request.url.isHttps && request.url.port == 443)
                    when (request.url.host) {
                        "www.bilibili.com" -> {
                            check(request.method == "GET" && request.url.encodedPath == "/")
                            response(request, "in-memory visitor bootstrap")
                        }
                        "api.bilibili.com" -> {
                            check(request.method == "GET")
                            val body = when (request.url.encodedPath) {
                                "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                                "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":41,"vip":{"status":1},"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                                "/x/space/bangumi/follow/list" -> """{"code":0,"data":{"list":[],"total":0}}"""
                                "/pgc/view/web/season" -> {
                                    detailGate?.hold()
                                    if (detailFailure) """{"code":-404,"message":"synthetic missing episode"}""" else seasonJson()
                                }
                                "/pgc/player/web/v2/playurl", "/pgc/player/web/playurl", "/pugv/player/web/playurl" -> {
                                    playCalls.incrementAndGet()
                                    playQualities.add(checkNotNull(request.url.queryParameter("qn")).toInt())
                                    playGate?.hold(); playJson(replyMode)
                                }
                                else -> error("Unexpected CPU protocol ${request.url.encodedPath}")
                            }
                            response(request, body)
                        }
                        else -> error("Unexpected CPU origin ${request.url.host}")
                    }
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
            context.store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(true),
                "default_audio_quality" to JsonPrimitive(audioPreference), "audio_quality" to JsonPrimitive(audioPreference),
                "video_codec" to JsonPrimitive("av01"),
                "video_second_codec" to JsonPrimitive(if (hevcSupported) "hev1" else "avc1")))
            context.store.update("settings", mapOf("audio_quality_preference" to JsonPrimitive(audioPreference),
                "video_codec_preference" to JsonPrimitive("av01"),
                "video_second_codec_preference" to JsonPrimitive(if (hevcSupported) "hev1" else "avc1")))
        }

        suspend fun initialize() {
            privacy.setPrivacyMode(true)
            cache = DesktopMediaByteCache(directory.resolve("bytes"), parentScope, repository)
            slot.install(epoch, { actualGate ->
                gate = actualGate
                settings = DesktopOriginalPlayerSettingsContext(context, gate::owns, gate::commitEntry,
                    largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
                val readbacks = DesktopOriginalVideoEntryReadbacks(repository, epoch, gate.scope, gate::owns, gate::commitEntry)
                val operations = DesktopDynamicCardOperations(repository, epoch, gate::owns)
                val comments = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                    arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, _ ->
                    when (method.name) { "isOwned" -> gate.owns(); "currentMid" -> 41L
                        else -> error("Unexpected comment effect ${method.name}") }
                } as DesktopVideoCommentRequests
                val commentRoot = DesktopOriginalCommentRootOwner(operations, comments, gate.scope, operations.emotes, gate::owns,
                    { error("No comment feedback") })
                val mini = Proxy.newProxyInstance(DesktopOriginalVideoOwnerMini::class.java.classLoader,
                    arrayOf(DesktopOriginalVideoOwnerMini::class.java)) { _, method, args ->
                    when {
                        method.name == "syncCurrentVideoInfo" -> {
                            val projected = args!![0] as VideoPlaybackUiState.Success
                            check(projected.info.cid in setOf(104L, 105L)); Unit
                        }
                        method.name.startsWith("setOn") -> { check(args!![0] == null); Unit }
                        method.name.startsWith("getOn") -> null
                        else -> error("Unexpected Mini effect ${method.name}")
                    }
                } as DesktopOriginalVideoOwnerMini
                val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
                    arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, _ ->
                    when (method.name) { "capturePlaybackDispatch" -> null
                        "enabledPlayerPlugins" -> emptyList<com.android.purebilibili.core.plugin.PlayerPlugin>()
                        else -> error("Unexpected plugin effect ${method.name}") }
                } as DesktopOriginalVideoOwnerPlugins
                val effects = DesktopOriginalVideoOwnerEffects(mini, unused(), unused(), network,
                    object : DesktopOriginalVideoOwnerCache {
                        override fun get(bvid: String, cid: Long): PlayUrlData? = null
                        override fun invalidate(bvid: String, cid: Long) = error("No cache invalidation")
                    }, unused(), unused(), unused(), MutableStateFlow(false))
                media = DesktopOriginalVideoRootMediaFactory(repository, cache, gate, { owner }, {})
                requests = DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                    gate.scope, gate::owns, gate::commitEntry, { PlayerPreferences(defaultAudioQuality = audioPreference, lastSelectedAudioQuality = audioPreference) }, capabilities,
                    { true }, { false }, { false }, subtitles, privacy,
                    { _, _ -> error("Preseeded CPU identity must not retire") }, media::request)
                DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, gate.scope,
                    gate::owns, gate::commitEntry, player, DesktopRepositoryPlaybackPublication(repository), { false }, versions,
                    { published.set(it); publications.add(it); versions.value = it.sourceVersion
                        if (::bas.isInitialized) acceptedBasObservations.add(
                            AcceptedBasObservation(it, vm.uiState.value, bas.ownsBasSource(it)))
                    }, media::admission,
                    captureMetadataBinding = { error("No readonly metadata recapture in this fixture") }, captureInvocation = { state, nativeOwner -> requests.capture(state, nativeOwner, mediaPreparation = {
                        // Fixed-capability headless fixture; no physical NativeReady claim.
                        currentCoroutineContext().ensureActive()
                        if (!gate.owns()) throw CancellationException("Fixture preparation entry retired")
                    }) },
                    status = readbacks, prepareAcceptedMedia = { media.accepted(it) },
                    createPlugins = { _, _ -> plugins }, confirmFollow = { error("No follow write") },
                    feedback = { error("No ordinary feedback") }, readHasPrimarySession = readbacks::hasPrimarySession,
                    readHasPrimaryAccessToken = readbacks::hasPrimaryAccessToken, readPrimaryMid = readbacks::primaryMid,
                    readOwnedCooldown = readbacks::cooldownRemainingMs, progress = progress, capabilities = capabilities,
                    applyPreferredVolume = {}, dashSegmentRequestsEnabled = { false },
                    logSeek = { _, _, _, _ -> }, diagnosticLoggingEnabled = { false }, resumeEndedSource = { error("No replay") },
                    root = commentRoot, interactionAnalytics = unused(), effects = effects,
                    todayWatchFeedback = DesktopTodayWatchFeedbackWriteBinding(context, gate::owns, gate::commitEntry))
            }, { }, { }, { })
            ui {
                owner = slot.requireAssembly()
                val portrait = DesktopOriginalPortraitPlatformBinding(owner, slot::currentAssembly, unused(), unused(), unused(), unused(),
                    storyFeed = { _, _, _, _ -> error("Unexpected Story feed in DesktopBangumiBasSourceAdmissionAssemblyTest fixture") },
                    captureRequest = { requests.captureBinding(it.captureLoadState()) },
                    captureMetadataRequest = { value ->
                        require(value === owner)
                        requests.captureBinding(value.captureLoadState())
                    },
                    createPageRequestRepository = { value, binding ->
                        require(value === owner)
                        requestRepository(binding).also { raw ->
                            raw.installMediaPreparation {
                                // Same explicit fixed-capability headless port as
                                // this fixture's ordinary Invocation; no physical readiness claim.
                                currentCoroutineContext().ensureActive()
                                if (!gate.owns()) throw CancellationException("Fixture preparation entry retired")
                            }
                        }
                    },
                    creatorTeamFollowStateChanges = emptyFlow(), mediaCache = cache,
                    captureCookieHeader = { binding, url -> binding.captureMediaCookieHeader(url) },
                    favoriteQuickSaveDefaultFolder = flowOf(false), blockedUps = unused(), danmaku = unused(),
                    currentCdnPlugin = { null }, presentationLease = { _, _ -> error("No surface") },
                    danmakuLease = { _, _ -> error("No surface") }, releasePager = { _, _ -> error("No surface") },
                    recover = { _, _ -> error("No surface") }, share = { _, _ -> error("No share") },
                    feedback = { _, _ -> error("No feedback") }, surface = { _, _, _ -> error("No rendering") },
                    viewport = { _, _, _, _, _, _, _ -> error("No viewport") }, danmakuSurface = { _, _, _, _, _ -> error("No rendering") },
                    onPreparation = {}, prepareAcceptedMedia = { expected, plan, current, failure ->
                        // Observational hook BEFORE media creation, outside all real gates.
                        // The ticket is created/caller-captured by actual NativePresenter.
                        if (failure != null) {
                            assertFalse(Thread.holdsLock(storeLock())); assertFalse(Thread.holdsLock(entryLock()))
                            assertFalse(Thread.holdsLock(nativeLock()))
                            preparations.add(failure)
                            val observer = beforePreparation; beforePreparation = null
                            observer?.invoke(failure)
                        }
                        owner.native.acceptedMedia({ media.accepted(it, plan) }, failure, current)
                    })
                native = DesktopOriginalBangumiNativePresenter(owner, slot::currentAssembly, portrait, gate.scope,
                    { if (::vm.isInitialized) vm.uiState.value as? BangumiPlayerState.Success else null },
                    { value, notify -> requests.captureBinding(value.captureLoadState(), onAuthorizationRetired = notify) },
                    { repository.sessionEpoch }, { repository.playbackAuthorizationRevision.value })
                val api = repository.ownedHomeService(BangumiApi::class.java, "https://api.bilibili.com/", epoch, gate::owns)
                val nav = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, gate::owns)
                val hub = DesktopOriginalBangumiHubRepository(api, nav, unused(), { "synthetic-csrf" }, { 41L })
                val pages = DesktopOriginalBangumiPagesRequests(api, hub, { "synthetic-csrf" }, { "synthetic-session" }, gate::owns, gate::owns)
                val account = DesktopOriginalBangumiPlayerAccountView(owner, repository, privacy, native::assertCurrent,
                    ::assertSubject, native::capturedPlaybackBindingOrNull, ::requestRepository)
                val base = object : DesktopOriginalBangumiBaseRequests {
                    override suspend fun getSponsorSegments(bvid: String): List<SponsorSegment> = emptyList()
                    override fun findSegmentAtPosition(segments: List<SponsorSegment>, positionMs: Long): SponsorSegment? = null
                    override suspend fun getDanmakuRawData(cid: Long): ByteArray? = error("No danmaku")
                }
                val environment = DesktopOriginalBangumiPlayerEnvironment(gate.scope, owner.section, settings,
                    DesktopOriginalBangumiPlayerRequestsView(pages, native::capturedPlaybackBinding, native::assertCurrent),
                    account, object : DesktopOriginalBangumiPlayerActions {
                        override suspend fun checkLikeStatus(aid: Long) = false
                        override suspend fun checkCoinStatus(aid: Long) = 0
                    }, owner.environment.interactionUseCase, nav, progress, base, native, flowOf(false),
                    { settings.getSharedPreferences("quality_settings", 0).getInt("default_audio_quality", AUDIO_QUALITY_HI_RES) },
                    { settings.getSharedPreferences("quality_settings", 0).getInt("audio_quality", AUDIO_QUALITY_HI_RES) },
                    { value -> DesktopOriginalPortraitSettings.setAudioQuality(settings, value); preferenceWrites.add(value) },
                    { false }, { false }, { false }, { false }, { null },
                    { _, _, _ -> error("No CDN plugin") }, { _, _, _ -> check(gate.owns()) },
                    { error("No download effect") }, {}, { block ->
                        native.launch(block = block, start = CoroutineStart.LAZY).also { job ->
                            launchedJobs.add(job); job.start()
                        }
                    }, native::commit, native::owns, native::ownsPlaybackSource, network, { false },
                    publishReadOnlyDetail = { detail, episode, index, _, postBegin, isLoadCurrent ->
                        currentCoroutineContext().ensureActive(); native.assertCurrent()
                        val launchCaller = native.captureInitialDetailLaunchCaller()
                        if (!isLoadCurrent(launchCaller)) throw CancellationException("Fixture readonly PGC load replaced")
                        require(detail.episodes?.getOrNull(index) === episode)
                        val binding = if (postBegin) native.capturedPlaybackBinding()
                            else (owner.invocations.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository).binding
                        binding.assertCurrent()
                        if (!binding.admitCurrentMutation {
                                native.assertCurrent()
                                if (!isLoadCurrent(launchCaller)) throw CancellationException("Fixture readonly PGC load replaced")
                                readOnlyFacts += Triple(detail, episode, index)
                            }) throw CancellationException("Fixture readonly PGC admission retired")
                    },
                    prepareMedia = {
                        // Existing fixed-capability headless fixture. Use the actual
                        // captured PGC Binding and required raw stage, not fake NativeReady.
                        currentCoroutineContext().ensureActive(); native.assertCurrent()
                        val binding = native.capturedPlaybackBinding()
                        requestRepository(binding).also { raw ->
                            raw.installMediaPreparation {
                                currentCoroutineContext().ensureActive(); native.assertCurrent(); binding.assertCurrent()
                            }
                        }.awaitMediaPreparation()
                        currentCoroutineContext().ensureActive(); native.assertCurrent(); binding.assertCurrent()
                    },
                    captureLoadCaller = native::captureInitialDetailLaunchCaller)
                vm = BangumiPlayerViewModel(environment)
                vm.attachPlayer(owner.section)
                val xmlSource = object : DesktopDanmakuSource {
                    override suspend fun metadata(cid: Long, aid: Long): ByteArray = throw IOException("Memory-only XML")
                    override suspend fun segment(cid: Long, index: Int): ByteArray = throw IOException("Memory-only XML")
                    override suspend fun special(url: String): ByteArray = error("No BAS special/network source")
                    override suspend fun xml(cid: Long): ByteArray { xmlCalls.incrementAndGet(); return BAS_XML.toByteArray() }
                }
                val platform = object : DesktopOriginalDanmakuRenderPlatform {
                    override fun resolveTypeface(fontWeight: Int) = Font("Dialog", Font.PLAIN, 15)
                    override fun systemChromeInsetPx() = 0
                    override fun maximumDisplayShortSidePx() = 1080f
                }
                overlay = DanmakuOverlay(player, platform, source = xmlSource, basStandaloneAdmission = null)
                overlay.applySettings(DanmakuSettings(mergeDuplicates = false, smartOcclusionEnabled = false, displayAreaRatio = 1f))
                overlay.setPluginDanmakuProcessors(null, basProcessor)
                bas = DesktopOriginalBangumiBasSourceBinding(owner, native, owner.section, overlay,
                    { vm.uiState.value as? BangumiPlayerState.Success }, ::owns,
                    { 0L }, ::isPresentationCurrent, ::withPresentationAdmission)
                recovery = DesktopOriginalPlaybackRecovery(gate.scope,
                    { action -> owner.environment.commit { assertOwned(); action() } }, ::assertOwned)
                component = DesktopOriginalBangumiPremiumAudioRecovery(owner, native, owner.section, repository,
                    { vm }, recovery, { vm.uiState.value as? BangumiPlayerState.Success }, ::owns,
                    ::withPresentationAdmission)
                gate.scope.launch { vm.toastEvent.collect { toasts.add(it) } }
                player.loadVersioned(PlaybackSource("file:///synthetic-bootstrap.avi"))
                actor = PremiumRecoveryActor(player)
            }
        }
        private fun requestRepository(binding: DesktopOriginalVideoRepositoryBinding) =
            createDesktopOriginalVideoOwnerRequestRepository(repository, binding, subtitles, privacy,
                { receipt, current -> repository.ownedHomeVisitorInitialized(receipt.accountEpoch, current) })
        private fun assertSubject(bvid: String, cid: Long, ep: Long, season: Long) {
            assertOwned()
            val state = vm.uiState.value as? BangumiPlayerState.Success ?: throw CancellationException("No PGC state")
            require(state.currentEpisode.bvid == bvid && state.currentEpisode.cid == cid &&
                state.currentEpisode.id == ep && state.seasonDetail.seasonId == season)
            if (!native.ownsPlaybackSource()) throw CancellationException("Real PGC source retired")
        }
        // CPU wiring of Factory.isPresentationCurrent / withPresentationAdmission:
        // actual Store -> actual entry -> short actual NativeOwner identity read.
        // Physical Factory construction and Runtime PluginBridge are excluded.
        private fun isPresentationCurrent(expected: DesktopOriginalVideoAcceptedPublication): Boolean {
            if (slot.currentAssembly() !== owner) return false
            val receipt = expected.nativeSource.source.authorizationReceipt ?: return false
            return published.get() === expected && gate.owns() && owner.owns() &&
                repository.sessionEpochFlow.value == receipt.accountEpoch &&
                repository.playbackAuthorizationRevision.value == receipt.revision
        }
        fun withPresentationAdmission(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
            if (slot.assemblies.value !== owner) return false
            var applied = false
            var enteredAdmission = false
            var presentationRejected = false
            return try {
                repository.withPrimaryPlaybackAdmission(gate.capturedEpoch,
                    { isPresentationCurrent(expected).also { if (!it) presentationRejected = true } }) {
                    enteredAdmission = true
                    gate.commitEntry {
                        if (isPresentationCurrent(expected) && owner.native.isCurrent(expected)) {
                            action() // No native lock remains held after isCurrent.
                            applied = true
                        }
                    } && applied
                }
            } catch (_: CancellationException) {
                false
            } catch (rejected: BiliApiException) {
                // Store retirement is rejection; an admitted callback failure must remain visible.
                if (!enteredAdmission && rejected.apiCode == -101 &&
                    (repository.sessionEpoch != gate.capturedEpoch || presentationRejected)) false else throw rejected
            }
        }
        fun accepted() = checkNotNull(owner.native.current())
        suspend fun load() {
            ui { vm.loadBangumiPlay(19, 41) }
            withTimeout(8_000) { while (owner.native.current() == null) delay(5) }
            ui {
                actor.drain(); actor.loaded()
                player.setPaused(true); actor.drain()
                assertEquals(120, state().quality)
                assertEquals(audioPreference, state().selectedAudioQuality)
                assertEquals(audioPreference, state().requestedAudioQuality)
                assertEquals(if (hiRes) HI_RES else AAC, state().audioUrl)
                assertEquals(AV1, state().playUrl)
                assertTrue(native.ownsPlaybackSource()); assertFalse(player.state.value.ready)
                assertTrue(actor.native.loads.isNotEmpty())
                val snapshot = checkNotNull(player.currentSourceSnapshot())
                assertEquals(accepted().nativeSource.sourceVersion, snapshot.sourceVersion)
                assertEquals(accepted().nativeSource.source, snapshot.source)
                assertTrue(player.ownsSourceSnapshot(accepted().nativeSource))
            }
        }
        fun state() = vm.uiState.value as BangumiPlayerState.Success
        fun preferences(): JsonObject = JsonObject(mapOf(
            "quality_settings" to context.store.preferences("quality_settings"),
            "settings" to context.store.preferences("settings")))
        fun playbackJob(): Job = BangumiPlayerViewModel::class.java.getDeclaredField("playbackLoadJob")
            .apply { isAccessible = true }.get(vm) as Job
        suspend fun failAndQueue(network: Boolean = false) = ui {
            if (network) networkDiagnostic()
            val failure = actor.fail(if (network) -13 else -14)
            assertSame(failure, player.state.value.failure)
            assertEquals(if (network) PlayerFailureKind.NETWORK else PlayerFailureKind.AUDIO_OUTPUT, failure.kind)
            assertEquals(if (network) -13 else -14, failure.nativeCode)
            val state = state(); val expected = accepted()
            assertTrue(component.failureSourceCurrent(state, expected, failure, vm.desktopPlaybackIntentRevision))
            val premium = component.isCurrentPremiumAudioFailure(state, expected, failure)
            assertEquals(!network, premium)
            component.queuePlaybackFailure(state, expected, failure, vm.desktopPlaybackIntentRevision,
                desktopOriginalNativeFailure(failure, premium), failure)
        }
        suspend fun awaitAac() = withTimeout(8_000) {
            while ((vm.uiState.value as? BangumiPlayerState.Success)?.audioFallbackReason != AudioFallbackReason.DECODER_ERROR ||
                recovery.state.value.status != PlaybackStatus.Ready || !native.ownsPlaybackSource()) delay(5)
        }
        suspend fun failVideoAndQueue(message: String = "Could not open codec.\n", evictPositive: Boolean = false): PlayerFailure = uiResult {
            diagnostic("vd", message)
            if (evictPositive) repeat(40) { diagnostic("fixture", "bounded native observation $it\n") }
            val failure = actor.fail(-13)
            assertSame(failure, player.state.value.failure)
            assertEquals(-13, failure.nativeCode); assertEquals(PlayerFailureKind.DECODER, failure.kind)
            assertTrue(failure.videoDecoderFailed)
            val state = state(); val expected = accepted()
            assertTrue(component.failureSourceCurrent(state, expected, failure, vm.desktopPlaybackIntentRevision))
            assertTrue(component.isCurrentVideoCodecFailure(state, expected, failure))
            assertFalse(component.isCurrentPremiumAudioFailure(state, expected, failure))
            component.queuePlaybackFailure(state, expected, failure, vm.desktopPlaybackIntentRevision,
                desktopOriginalNativeFailure(failure, false), failure)
            failure
        }
        suspend fun awaitVideo(url: String, quality: Int) = withTimeout(8_000) {
            while ((vm.uiState.value as? BangumiPlayerState.Success)?.playUrl != url || state().quality != quality ||
                recovery.state.value.status != PlaybackStatus.Ready || !native.ownsPlaybackSource()) delay(5)
        }
        fun assertVideoPreservesAudio(original: BangumiPlayerState.Success, url: String, quality: Int,
            expectedPreferences: JsonObject, expectedCalls: Int, expectedAudio: String? = original.audioUrl) {
            val actual = state(); val source = accepted().nativeSource.source
            assertEquals(url, actual.playUrl); assertEquals(quality, actual.quality)
            assertEquals(original.currentEpisode, actual.currentEpisode)
            assertEquals(original.seasonDetail.seasonId, actual.seasonDetail.seasonId)
            assertEquals(original.requestedAudioQuality, actual.requestedAudioQuality)
            assertEquals(original.selectedAudioQuality, actual.selectedAudioQuality)
            assertEquals(original.audioFallbackReason, actual.audioFallbackReason)
            assertEquals(expectedAudio, actual.audioUrl); assertEquals(url, source.videoUrl)
            assertEquals(actual.audioUrl, source.audioUrl)
            assertEquals("https://www.bilibili.com/cheese/play/ep41", source.referer)
            assertEquals(27_750L, owner.section.currentPosition); assertFalse(owner.section.playWhenReady)
            assertEquals(expectedPreferences, preferences()); assertTrue(preferenceWrites.isEmpty())
            assertEquals(expectedCalls, playCalls.get())
        }
        suspend fun manualQuality(quality: Int) {
            val before = accepted()
            val job = uiResult { vm.changeQuality(quality); launchedJobs.last() }
            withTimeout(8_000) { job.join() }
            assertFalse(job.isCancelled)
            assertNotSame(before, accepted()); assertTrue(native.ownsPlaybackSource())
        }
        fun endFile(entry: Long, code: Int) = Memory(24).use { data ->
            data.clear(); data.setInt(0, 4); data.setInt(4, code); data.setLong(8, entry)
            PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                .apply { isAccessible = true }.invoke(actor, 7, data)
            Unit
        }
        fun diagnostic(module: String, content: String) {
            fun text(value: String): Memory = value.toByteArray(Charsets.UTF_8).let { bytes ->
                Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
            }
            text(module).use { prefix -> text("error").use { level -> text(content).use { message ->
                Memory(32).use { data ->
                    data.clear(); data.setPointer(0, prefix); data.setPointer(8, level); data.setPointer(16, message)
                    data.setInt(24, 20)
                    PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                        .apply { isAccessible = true }.invoke(actor, 2, data)
                }
            } } }
        }
        fun reflect(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(receiver)
        fun actualBasBinding(): Any? = reflect(overlay, "basActionBinding")
        suspend fun acquire(expected: DesktopOriginalVideoAcceptedPublication = accepted()): BasRegistration = uiResult {
            val lease = bas.acquireBasActions(expected, { isPresentationCurrent(expected) }) { uri ->
                assertTrue(EventQueue.isDispatchThread())
                assertFalse(Thread.holdsLock(storeLock())); assertFalse(Thread.holdsLock(entryLock()))
                assertFalse(Thread.holdsLock(nativeLock())); assertFalse(Thread.holdsLock(reflect(overlay, "requestLock")))
                assertTrue(player.state.value.paused); urls.add(uri); true
            }
            val actual = assertNotNull(actualBasBinding())
            @Suppress("UNCHECKED_CAST") val owned = reflect(actual, "owned") as () -> Boolean
            @Suppress("UNCHECKED_CAST") val activate = reflect(actual, "activate") as (BasTarget) -> Boolean
            BasRegistration(lease, actual, owned, activate).also { registrations.add(it) }
        }
        suspend fun loadDocument(expected: DesktopOriginalVideoAcceptedPublication) {
            overlay.load(expected.request.cid, aid = expected.request.aid, durationSeconds = 0.0,
                expectedSourceVersion = expected.sourceVersion, maskSource = null, stillOwned = ::owns)
        }
        fun installation(): Any? = reflect(overlay, "basInstallation")
        @Suppress("UNCHECKED_CAST") fun rawItems(): List<BasDanmaku> = (reflect(overlay, "rawDocument") as DanmakuDocument).bas
        @Suppress("UNCHECKED_CAST") fun items(): List<BasDanmaku> = installation()?.let {
            reflect(it, "items") as List<BasDanmaku> } ?: emptyList()
        fun installationSource(): OwnedPlaybackSourceSnapshot = reflect(assertNotNull(installation()), "source") as OwnedPlaybackSourceSnapshot
        fun installationCurrent(): Boolean {
            val actual = installation() ?: return false
            return DanmakuOverlay::class.java.getDeclaredMethod("basCurrent", actual.javaClass)
                .apply { isAccessible = true }.invoke(overlay, actual) as Boolean
        }
        fun basJob(): Job = assertNotNull(reflect(overlay, "basFilterJob") as Job?)
        suspend fun drainBas(job: Job? = null) {
            val actual = job ?: uiResult { basJob() }
            withTimeout(8_000) { actual.join() }; ui { }
        }
        suspend fun awaitInstalled() = withTimeout(8_000) {
            while (!uiResult { installationCurrent() && items().size == 1 && overlay.commentCount.value == 1 }) delay(5)
        }
        suspend fun awaitEntered(latch: CountDownLatch) = withContext(Dispatchers.IO) {
            assertTrue(latch.await(8, TimeUnit.SECONDS))
        }
        suspend fun awaitEpisode(id: Long) {
            withTimeout(8_000) { while ((vm.uiState.value as? BangumiPlayerState.Success)?.currentEpisode?.id != id ||
                (vm.uiState.value as? BangumiPlayerState.Success)?.playUrl == null || !native.ownsPlaybackSource()) delay(5) }
            withTimeout(8_000) { playbackJob().join() }
            ui { actor.drain(); actor.loaded(); assertEquals(id, state().currentEpisode.id) }
        }
        fun lastQueuedSeek(): Any {
            val session = assertNotNull(reflect(player, "session"))
            val commands = reflect(session, "commands") as java.util.concurrent.LinkedBlockingQueue<*>
            return commands.last { it!!.javaClass.simpleName == "Seek" }!!
        }
        private fun networkDiagnostic() {
            fun text(value: String): Memory = value.toByteArray(Charsets.UTF_8).let { bytes ->
                Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
            }
            text("ffmpeg").use { prefix -> text("error").use { level -> text("connection failed").use { message ->
                Memory(32).use { data ->
                    data.clear(); data.setPointer(0, prefix); data.setPointer(8, level); data.setPointer(16, message)
                    data.setInt(24, 20)
                    PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                        .apply { isAccessible = true }.invoke(actor, 2, data)
                }
            } } }
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun <T> uiResult(action: () -> T): T = withContext(Dispatchers.Swing) { action() }
        private fun storeLock() = DesktopSessionStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(sessions)
        private fun entryLock() = DesktopOriginalVideoRootGate::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(gate)
        private fun nativeLock() = MpvPlayer::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(player)
        suspend fun close() {
            detailGate?.release?.countDown(); playGate?.release?.countDown()
            if (::overlay.isInitialized) ui { registrations.forEach { it.close() }; overlay.close() }
            if (::recovery.isInitialized) recovery.close()
            if (::vm.isInitialized) ui { try { vm.close() } catch (retired: CancellationException) { check(!gate.owns()) } }
            if (::actor.isInitialized) actor.close()
            slot.closeAndJoin()
            parentJob.cancelAndJoin()
            if (::cache.isInitialized) cache.close()
            subtitles.close(); player.close()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            assertTrue(failures.isEmpty(), failures.toString())
            deleteOnlyCreatedDirectory()
        }
        suspend fun useSuspending(block: suspend (Fixture) -> Unit) {
            try { initialize(); withTimeout(30_000) { block(this@Fixture) } }
            finally { withContext(NonCancellable) { close() } }
        }
        private fun deleteOnlyCreatedDirectory() {
            check(directory.toAbsolutePath().normalize() == createdAbsolute)
            check(createdAbsolute.parent == tempAbsolute && createdReal.parent == tempReal)
            check(createdAbsolute != tempAbsolute && createdAbsolute.fileName.toString().startsWith("pgc-bas-real-owner-"))
            fun validate(path: Path, attributes: BasicFileAttributes) {
                val absolute = path.toAbsolutePath().normalize()
                check(absolute.startsWith(createdAbsolute)); check(!attributes.isSymbolicLink && !attributes.isOther)
                check(path.toRealPath() == absolute)
                if (System.getProperty("os.name").startsWith("Windows"))
                    check((Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int) and 0x400 == 0)
            }
            check(directory.toRealPath() == createdReal)
            val nodes = mutableListOf<Path>()
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validate(dir, attrs); nodes.add(dir); return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validate(file, attrs); nodes.add(file); return FileVisitResult.CONTINUE
                }
            })
            for (path in nodes.sortedByDescending { it.nameCount }) {
                check(directory.toRealPath() == createdReal)
                validate(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
                Files.delete(path)
            }
        }
        private fun seasonJson(): String {
            return """{"code":0,"result":{"season_id":19,"season_type":10,"title":"Synthetic course","cover":"https://media.invalid/cover.jpg","up_info":{"mid":7,"uname":"Fixture UP","avatar":"https://media.invalid/face.jpg"},"section":[],"episodes":[{"id":41,"aid":101,"cid":104,"bvid":"BV1GJ411x7h7","title":"One","cover":"https://media.invalid/cover.jpg","duration":60000},{"id":42,"aid":102,"cid":105,"bvid":"BV17x411w7KC","title":"Two","duration":60000}],"user_status":{"vip":1,"follow":0,"follow_status":0}}}"""
        }
    }
    companion object {
        private const val BAS_SCRIPT = "def text actual {content=\"actual PGC BAS\" duration=60s} "
        private const val BAS_XML = "<i><d p=\"0,9,25,16777215,0,0,private,91\">$BAS_SCRIPT</d></i>"
        private const val AV1 = "https://media.invalid/av1_120.m4s?synthetic=1"
        private const val AV1_64 = "https://media.invalid/av1_64.m4s?synthetic=1"
        private const val AVC_HIGH = "https://media.invalid/avc_112.m4s?synthetic=1"
        private const val AVC_LOW = "https://media.invalid/avc_80.m4s?synthetic=1"
        private const val HEVC_HIGH = "https://media.invalid/hevc_116.m4s?synthetic=1"
        private const val AVC_NETWORK = "https://media.invalid/avc_112.m4s?synthetic=2"
        private const val HEVC_NETWORK = "https://media.invalid/hevc_116.m4s?synthetic=2"
        private const val AAC = "https://media.invalid/pgc-audio.m4s?synthetic=1"
        private const val AAC_NETWORK = "https://media.invalid/pgc-audio.m4s?synthetic=2"
        private const val HI_RES = "https://media.invalid/hires.flac?synthetic=1"
        private fun response(request: Request, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("memory").body(body.toResponseBody("application/json".toMediaType())).build()
        private fun videoRow(id: Int, url: String, codec: String, bandwidth: Int = 100000): String =
            """{"id":$id,"baseUrl":"$url","bandwidth":$bandwidth,"mime_type":"video/mp4","codecs":"$codec","width":3840,"height":2160,"frameRate":"30","codecid":${if (codec.startsWith("av01")) 13 else if (codec.startsWith("hev")) 12 else 7},"segment_base":{"initialization":"0-31","index_range":"32-63"}}"""
        private fun playJson(mode: String): String {
            if (mode == "network-durl") return """{"code":0,"result":{"quality":112,"timelength":60000,"accept_quality":[112,80],"durl":[{"order":1,"length":60000,"size":1000,"url":"https://media.invalid/legacy.flv?synthetic=2"}]}}"""
            val rows = when (mode) {
                "nearest-higher" -> listOf(videoRow(64, AV1_64, "av01.0.08M.08"), videoRow(80, AVC_LOW, "avc1.640028"))
                "manual-avc" -> listOf(videoRow(80, AVC_LOW, "avc1.640028"))
                "network-no-avc" -> listOf(videoRow(120, AV1, "av01.0.08M.08"), videoRow(116, HEVC_NETWORK, "hev1.1.6.L153.B0"))
                "network-avc", "network-hevc" -> listOf(
                    videoRow(120, "https://media.invalid/av1_120.m4s?synthetic=2", "av01.0.08M.08"),
                    videoRow(116, HEVC_NETWORK, "hev1.1.6.L153.B0"),
                    videoRow(112, AVC_NETWORK, "avc1.640033"), videoRow(80, AVC_LOW, "avc1.640028"))
                "initial" -> listOf(
                    videoRow(120, AV1, "av01.0.08M.08"),
                    videoRow(112, "https://media.invalid/av1_112.m4s?synthetic=1", "av01.0.08M.08"),
                    videoRow(116, HEVC_HIGH, "hev1.1.6.L153.B0"),
                    videoRow(116, "https://media.invalid/unknown_116.m4s?synthetic=1", "vp09.00.51.08"),
                    videoRow(112, "https://media.invalid/avc_112_highrate.m4s?synthetic=1", "avc1.640033", 300000),
                    videoRow(112, AVC_HIGH, "avc1.640033"), videoRow(80, AVC_LOW, "avc1.640028"),
                    videoRow(125, "https://media.invalid/hdr_125.m4s?synthetic=1", "avc1.640033"),
                    videoRow(126, "https://media.invalid/dv_126.m4s?synthetic=1", "hev1.2.6.L153.B0"))
                else -> error("Unknown raw protocol mode $mode")
            }
            val quality = when (mode) { "nearest-higher" -> 64; "manual-avc" -> 80; else -> 120 }
            val aacUrl = if (mode.startsWith("network-")) AAC_NETWORK else AAC
            val aac = """{"id":30280,"baseUrl":"$aacUrl","bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}"""
            return """{"code":0,"result":{"quality":$quality,"timelength":60000,"accept_quality":[120,116,112,80,64],"accept_description":["4K","3K","1080P+","1080P","720P"],"dash":{"duration":60,"video":[${rows.joinToString(",")}],"audio":[$aac],"flac":{"audio":{"id":30251,"baseUrl":"$HI_RES","bandwidth":800000,"mime_type":"audio/flac","codecs":"fLaC","segment_base":{"initialization":"0-31","index_range":"32-63"}}}}}}"""
        }
    }
}
