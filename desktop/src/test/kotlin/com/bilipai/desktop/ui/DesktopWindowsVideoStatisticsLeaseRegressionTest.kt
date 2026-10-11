package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.android.purebilibili.feature.video.ui.section.resolvePublishTimeRowText
import com.android.purebilibili.feature.video.ui.section.resolveVideoDetailOnlineCountText
import com.android.purebilibili.feature.video.ui.section.shouldEmphasizePrecisePublishTime
import com.bilipai.desktop.appearance.DesktopTextClipboard
import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.*
import java.awt.EventQueue
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Instant
import java.util.Comparator
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.Call
import retrofit2.Retrofit

/** Backend qualification for the shared effect lease used by the statistics BV
 * callback. Admission uses the real Repository/SessionStore receipt, RootGate,
 * NativeOwner, original PlaybackSessionStore and existing MPV memory ABI actor.
 * The final gate is never replaced with a Boolean grant. A private Clipboard is
 * the controlled effect target; no system clipboard, DLL, HWND, HTTP or profile
 * is used. These tests do not compose the statistics host or construct its full
 * Factory/VM, and do not prove physical UI or hardware acceptance. */
class DesktopWindowsVideoStatisticsLeaseRegressionTest {
    private fun <T> edt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val result = AtomicReference<Result<T>?>(null)
        EventQueue.invokeAndWait { result.set(runCatching(block)) }
        return requireNotNull(result.get()).getOrThrow()
    }

    private class Fixture(commitMetadata: Boolean = true) : AutoCloseable {
        val folder = Files.createTempDirectory("bilipai-statistics-lease-")
        val sessions = DesktopSessionStore(folder.resolve("synthetic-session.json"), persistent = false)
        val repository = DesktopRepository(sessions)
        val httpAttempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate: DesktopOriginalVideoRootGate
        val player = MpvPlayer()
        val actor: PremiumRecoveryActor
        val initialJob = Job()
        val request = PlaybackRequest.create("BVstatistics", 17L, 70L)
        val info = ViewInfo(bvid = request.bvid, aid = request.aid, cid = request.cid)
        val playback = PlaybackSessionStore()
        val loadToken = playback.beginLoadRequest(request).requestToken
        val clipboard = Clipboard("BiliPai private statistics fixture")
        val effects = AtomicInteger()
        val completedAdmissions = AtomicInteger()
        var admissionDepth = 0
        var beforeReceiptAdmission: (() -> Unit)? = null
        private val effectProbe = Executors.newSingleThreadExecutor()
        private val leases = mutableListOf<DesktopWindowsVideoInteractionLease>()
        val publication: DesktopPlaybackPublication
        val owner: DesktopOriginalVideoNativeOwner
        val origin: DesktopOriginalVideoAcceptedPublication
        val textClipboard = DesktopTextClipboard { value ->
            check(EventQueue.isDispatchThread())
            check(admissionDepth == 0) { "Clipboard effect ran within source admission" }
            check(completedAdmissions.get() > 0) { "Clipboard effect bypassed final admission" }
            // Separate-worker access would block if the caller still held either
            // the actual Store monitor or the actual MPV source monitor.
            val epoch = repository.sessionEpoch
            check(effectProbe.submit<Boolean> {
                repository.withPrimaryPlaybackAdmission(epoch, gate::owns) {
                    player.currentSourceSnapshot() != null
                }
            }.get(2L, TimeUnit.SECONDS))
            clipboard.setContents(StringSelection(value), null)
            effects.incrementAndGet()
            true
        }

        init {
            check(EventQueue.isDispatchThread())
            saveSyntheticAccount("initial")
            // Seal all precreated repository transports. The native memory actor
            // does not fetch the synthetic source; an accidental API call fails.
            val closedTransport = repository.httpClient.newBuilder().addInterceptor {
                httpAttempts.incrementAndGet()
                error("Statistics backend qualification cannot open HTTP")
            }.build()
            repository.javaClass.getDeclaredField("client").apply {
                check(trySetAccessible()); set(repository, closedTransport)
            }
            for (name in listOf("api", "searchApi", "passportApi", "validationPassportApi", "buvidApi", "dynamicApi")) {
                repository.javaClass.getDeclaredField(name).apply {
                    check(trySetAccessible()); check(type.isInterface)
                    set(repository, Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                        httpAttempts.incrementAndGet()
                        error("No API service is allowed: $name.${method.name}")
                    })
                }
            }
            repository.javaClass.getDeclaredField("validationPassportRetrofit").apply {
                check(trySetAccessible())
                set(repository, (get(repository) as Retrofit).newBuilder().client(closedTransport).build())
            }
            gate = DesktopOriginalVideoRootGate(repository, repository.sessionEpoch, { true }, scope)
            val actualPublication = DesktopRepositoryPlaybackPublication(repository)
            publication = object : DesktopPlaybackPublication {
                override val requiresAccountReceipt get() = actualPublication.requiresAccountReceipt
                override fun isCurrent(source: PlaybackSource) = actualPublication.isCurrent(source)
                override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
                    beforeReceiptAdmission?.also { beforeReceiptAdmission = null }?.invoke()
                    return actualPublication.admit(source, stillOwned) {
                        admissionDepth++
                        try { block() } finally { admissionDepth-- }
                    }
                }
                override fun calls(delegate: Call.Factory, source: PlaybackSource,
                    stillOwned: () -> Boolean, callerJob: Job?): Call.Factory =
                    error("This fixture cannot open byte transport")
            }
            owner = DesktopOriginalVideoNativeOwner(player, publication, { repository.sessionEpoch },
                { false }, gate::owns, gate::commitEntry, {},
                { _, _ -> error("This fixture cannot attach byte transport") })
            player.loadVersioned(PlaybackSource("file:///C:/statistics-bootstrap.avi"))
            actor = PremiumRecoveryActor(player)
            val receipt = repository.capturePlaybackAuthorization(repository.sessionEpoch, gate::owns).receipt
            origin = owner.publish(request,
                PlaybackSource("file:///C:/statistics-origin.avi", startPaused = true, authorizationReceipt = receipt),
                player.currentSourceVersion, initialJob) { true }
            actor.drain(); actor.loaded()
            check(actor.native.loads.size == 1)
            check(!player.state.value.loading)
            check(owner.isCurrent(origin))
            if (commitMetadata) playback.updateCurrentMedia(cid = request.cid)
            clipboard.setContents(StringSelection("unchanged"), null)
        }

        fun saveSyntheticAccount(marker: String) {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-statistics-$marker", "bili_jct" to "synthetic-only"),
                AccountSummary(47L, "Statistics synthetic fixture", ""))
        }

        fun lease(capturedInfo: ViewInfo = info): DesktopWindowsVideoInteractionLease =
            DesktopWindowsVideoInteractionLease(
                {
                    gate.owns() && owner.isCurrent(origin) &&
                        desktopWindowsVideoMetadataMatchesSource(capturedInfo, origin.request, playback.state.value, loadToken)
                },
                { action ->
                    var applied = false
                    admissionDepth++
                    val admitted = try {
                        gate.commit {
                            if (owner.admitPlaybackDispatch(origin) { action(); applied = true })
                                completedAdmissions.incrementAndGet()
                        } && applied
                    } finally { admissionDepth-- }
                    admitted
                },
            ).also(leases::add)

        fun copy(lease: DesktopWindowsVideoInteractionLease): Boolean = lease.effect {
            check(textClipboard.copyText(info.bvid))
        }
        fun copied(): String = clipboard.getData(DataFlavor.stringFlavor) as String

        fun replaceAtSameNativeVersion() {
            check(player.recoverSource(origin.sourceVersion,
                origin.nativeSource.source.copy(videoUrl = "file:///C:/statistics-replacement.avi"),
                positionSeconds = 12.0, paused = true))
            check(player.currentSourceVersion == origin.sourceVersion)
            check(!owner.isCurrent(origin))
        }

        override fun close() {
            check(EventQueue.isDispatchThread())
            try {
                leases.forEach { it.close() }
                owner.close(); gate.retire(); scope.cancel(); initialJob.cancel()
                actor.close(); player.close()
                assertEquals(0, httpAttempts.get())
                assertFalse(Files.exists(folder.resolve("synthetic-session.json")))
            } finally {
                effectProbe.shutdownNow()
                try { check(effectProbe.awaitTermination(2L, TimeUnit.SECONDS)) }
                finally {
                    Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            }
        }
    }

    private fun withFixture(commitMetadata: Boolean = true, block: (Fixture) -> Unit) =
        edt { Fixture(commitMetadata).use(block) }

    @Test fun acknowledgedSourceCopiesExactBvOnEdtOutsideActualAdmissionAfterRequestJobRetires() = withFixture { f ->
        val lease = f.lease()
        // The actual loadfile ACK has consumed the transient request guard.
        // Closing that old job cannot revoke a live accepted native source.
        f.initialJob.cancel()
        assertTrue(checkNotNull(f.origin.nativeSource.source.nativePublication).admit {})
        assertTrue(f.owner.isCurrent(f.origin))
        assertTrue(f.copy(lease))
        assertEquals("BVstatistics", f.copied())
        assertEquals(1, f.effects.get())
        assertEquals(1, f.completedAdmissions.get())
    }

    @Test fun pendingOriginalMetadataAndWrongPartCannotWriteClipboardDespiteLiveNativeSource() = withFixture(false) { f ->
        val pending = f.lease()
        assertTrue(f.owner.isCurrent(f.origin))
        assertFalse(f.copy(pending))
        f.playback.updateCurrentMedia(cid = f.request.cid)
        assertFalse(f.copy(f.lease(f.info.copy(cid = f.request.cid + 1L))))
        assertFalse(f.copy(f.lease(f.info.copy(bvid = "BVother"))))
        assertEquals("unchanged", f.copied())
        assertEquals(0, f.effects.get())
        assertTrue(f.copy(pending))
        assertEquals(f.request.bvid, f.copied())
    }

    @Test fun forcedSameBvCidReloadRejectsCapturedClipboardCallbackUsingOriginalLoadToken() = withFixture { f ->
        val captured = f.lease()
        val replacement = f.playback.beginLoadRequest(f.request.copy(force = true))
        f.playback.updateCurrentMedia(cid = f.request.cid)
        assertTrue(replacement.requestToken > f.loadToken)
        assertTrue(f.owner.isCurrent(f.origin))
        assertFalse(f.copy(captured))
        assertEquals("unchanged", f.copied())
        assertEquals(0, f.effects.get())
    }

    @Test fun queuedEdtCallbackCannotUseOldSnapshotAfterSameVersionNativeReplacement() {
        val f = edt { Fixture() }
        val result = AtomicReference<Result<Boolean>?>(null)
        try {
            edt {
                val captured = f.lease()
                EventQueue.invokeLater { result.set(runCatching { f.copy(captured) }) }
                // Callback is already pending on the real EDT before replacement.
                f.replaceAtSameNativeVersion()
            }
            edt {
                assertFalse(requireNotNull(result.get()).getOrThrow())
                assertEquals("unchanged", f.copied())
                assertEquals(0, f.effects.get())
            }
        } finally { edt { f.close() } }
    }

    @Test fun permanentlyClosedLeaseAndCancelledRootScopeCannotReviveEqualSourceCallback() {
        withFixture { f ->
            val closed = f.lease()
            closed.close()
            assertTrue(f.owner.isCurrent(f.origin))
            assertFalse(f.copy(closed))
            // A fresh sibling lifetime can still use the same accepted source.
            assertTrue(f.copy(f.lease()))
            assertEquals(1, f.effects.get())
        }
        withFixture { f ->
            val captured = f.lease()
            f.scope.cancel()
            assertFalse(f.gate.owns())
            assertFalse(f.copy(captured))
            assertEquals("unchanged", f.copied())
            assertEquals(0, f.effects.get())
        }
    }

    @Test fun panelClosingInsideRealFinalReceiptAdmissionSuppressesEffectAfterPreflight() = withFixture { f ->
        val captured = f.lease()
        assertTrue(captured.isOwned())
        f.beforeReceiptAdmission = captured::close
        assertFalse(f.copy(captured))
        // Store/entry/native still admitted this source; only the permanent
        // lexical panel lease retired between preflight and final callback.
        assertTrue(f.owner.isCurrent(f.origin))
        assertEquals(1, f.completedAdmissions.get())
        assertEquals("unchanged", f.copied())
        assertEquals(0, f.effects.get())
    }

    @Test fun sameMidCredentialSwitchAtFinalReceiptAdmissionRejectsPreviouslyValidCopy() = withFixture { f ->
        val captured = f.lease()
        assertTrue(captured.isOwned())
        val epoch = f.repository.sessionEpoch
        f.beforeReceiptAdmission = { f.saveSyntheticAccount("replacement") }
        assertFalse(f.copy(captured))
        assertTrue(f.repository.sessionEpoch > epoch)
        assertFalse(f.publication.isCurrent(f.origin.nativeSource.source))
        assertEquals("unchanged", f.copied())
        assertEquals(0, f.effects.get())
        assertEquals(0, f.completedAdmissions.get())
    }

    @Test fun originalOnlineVisibilityAndNewsTimestampRetainMeaningAtExplicitTimeZone() {
        assertEquals("", resolveVideoDetailOnlineCountText(false, "  123人在看  "))
        assertEquals("123人在看", resolveVideoDetailOnlineCountText(true, "  123人在看  "))
        assertEquals("", resolveVideoDetailOnlineCountText(true, " \t "))
        val published = Instant.parse("2026-10-01T12:00:00Z").epochSecond
        val now = published * 1_000L + 3_600_000L
        assertTrue(shouldEmphasizePrecisePublishTime("新闻资讯", "普通标题"))
        assertTrue(shouldEmphasizePrecisePublishTime("生活", "发布会回应"))
        assertFalse(shouldEmphasizePrecisePublishTime("音乐", "现场演奏"))
        assertEquals("发布时间 1小时前  ·  2026-10-01 12:00", resolvePublishTimeRowText(
            published, "新闻资讯", "普通标题", now, Locale.ROOT, TimeZone.getTimeZone("UTC")))
        assertEquals("发布时间 1小时前  ·  2026-10-01 20:00", resolvePublishTimeRowText(
            published, "生活", "发布会回应", now, Locale.ROOT, TimeZone.getTimeZone("Asia/Shanghai")))
        assertEquals("1小时前", resolvePublishTimeRowText(
            published, "音乐", "现场演奏", now, Locale.ROOT, TimeZone.getTimeZone("UTC")))
        assertEquals("", resolvePublishTimeRowText(
            0L, "新闻资讯", "普通标题", now, Locale.ROOT, TimeZone.getTimeZone("UTC")))
    }
}
