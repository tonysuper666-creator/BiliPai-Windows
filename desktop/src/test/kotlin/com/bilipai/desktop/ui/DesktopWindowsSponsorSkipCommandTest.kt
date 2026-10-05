package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.plugin.SkipAction
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** The UI dispatcher used by the facade calls the COMPLETE installed VM, its
 * original UseCase and the existing source-owned MPV command queue. The existing
 * in-memory Session fixture supplies readback; no native DLL, window, media IO,
 * remote Sponsor service, credentials or real profile is opened. Queue admission
 * is deliberately not claimed as native seek ACK or recorded skip history. */
class DesktopWindowsSponsorSkipCommandTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private class PendingDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() {
            repeat(256) { (pending.pollFirst() ?: return).run() }
            error("Unexpected unbounded test coroutine dispatch")
        }
    }
    private fun onUi(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else {
            var error: Throwable? = null
            SwingUtilities.invokeAndWait { try { action() } catch (failure: Throwable) { error = failure } }
            error?.let { throw it }
        }
    }
    private inner class Harness(paused: Boolean = true) : AutoCloseable {
        val folder = Files.createTempDirectory("bp-manual-sponsor-command-")
        val dispatcher = PendingDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        val player = MpvPlayer()
        val actor: PremiumRecoveryActor
        var account = 1L
        var entry = true
        var active = true
        var accepted: DesktopOriginalVideoAcceptedPublication
        val original: DesktopOriginalVideoAcceptedPublication
        var beforeAdmission: () -> Unit = {}
        var beforeQueuedSeek: () -> Unit = {}
        val segment = SponsorSegment(listOf(20f, 42.5f), "manual-fixture", "sponsor")
        val action = SkipAction.ShowButton(segment.endTimeMs, "跳过恰饭", segment.UUID)
        val logs = mutableListOf<Long>()

        init {
            player.load(PlaybackSource("https://fixture.invalid/no-media", startPaused = paused))
            actor = PremiumRecoveryActor(player)
            actor.native.mpv_set_property_string(com.sun.jna.Pointer(1L), "pause", if (paused) "yes" else "no")
            actor.loaded()
            original = DesktopOriginalVideoAcceptedPublication(PlaybackRequest.create("BVfixture", 17L, 70L),
                assertNotNull(player.currentSourceSnapshot()))
            accepted = original
        }
        fun owned() = entry && account == 1L
        fun sourceCurrent(expected: DesktopOriginalVideoAcceptedPublication) =
            owned() && accepted === expected && player.ownsSourceSnapshot(expected.nativeSource)
        fun admit(expected: DesktopOriginalVideoAcceptedPublication, block: () -> Unit): Boolean {
            beforeAdmission()
            if (!sourceCurrent(expected)) return false
            var applied = false
            val admitted = player.admitSourceSnapshot(expected.nativeSource) {
                if (sourceCurrent(expected)) { block(); applied = true }
            }
            return admitted && applied
        }
        fun replacement() = DesktopOriginalVideoAcceptedPublication(original.request, original.nativeSource)
        private val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
            arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, args ->
            when (method.name) {
                "enabledPlayerPlugins" -> emptyList<com.android.purebilibili.core.plugin.PlayerPlugin>()
                "capturePlaybackDispatch" -> accepted.takeIf { owned() }
                "isPlaybackDispatchCurrent" -> sourceCurrent(args!![0] as DesktopOriginalVideoAcceptedPublication)
                "admitPlaybackDispatch" -> {
                    beforeQueuedSeek()
                    @Suppress("UNCHECKED_CAST")
                    admit(args!![0] as DesktopOriginalVideoAcceptedPublication, args[1] as () -> Unit)
                }
                else -> error("No remote/provider execution: ${method.name}")
            }
        } as DesktopOriginalVideoOwnerPlugins
        private val repository = unused<DesktopOriginalVideoOwnerRepository>()
        private val media = unused<DesktopOriginalVideoMediaPort>()
        private val invocations = DesktopOriginalVideoPlaybackInvocationPorts(scope, ::owned, {
            val caller = currentCoroutineContext().job
            val epoch = account
            DesktopOriginalVideoPlaybackInvocation(repository, media) {
                if (!entry || account != epoch || !caller.isActive) throw CancellationException("Fixture entry retired")
            }
        }, unused(), { media })
        private val api = unused<BilibiliApi>()
        private val interaction = VideoInteractionUseCase(DesktopOriginalVideoEngagementProtocol(api,
            { "fixture-csrf" }, { 1L }, { "fixture-session" }, { null }, {}, {},
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, {})), unused())
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::owned,
            { block -> if (!owned()) false else { block(); true } },
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, repository, unused(), unused(), unused(),
            media, {}, { emptyMap() }, {}, { false }, { target, _, _, _ -> logs += target }, ::owned)
        val vm = VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(scope, settings, invocations,
            repository, unused(), unused(), useCase, interaction, unused(), unused(), unused(), plugins,
            unused(), unused(), unused(), { null }, unused(), unused(), unused(), unused(), MutableStateFlow(false),
            ::owned, { block -> if (!owned()) false else { block(); true } },
            DesktopTodayWatchFeedbackWriteBinding(context, ::owned, { it(); true })))
        private val section = DesktopOriginalMpvSectionControl(player, { original.sourceVersion }, ::owned,
            { block -> admit(original, block) }, { false }, {}, {}, { target, _, _, _ -> logs += target }, scope,
            // No native event loop is fabricated. The real synchronous seek listener
            // is registered; unrelated asynchronous decoder state callbacks are absent.
            MutableStateFlow<Long?>(null), { block -> if (!owned()) false else { block(); true } })
        init {
            vm.attachPlayer(section)
            seed(segment, action)
        }
        @Suppress("UNCHECKED_CAST") private fun <T> state(name: String) =
            VideoPlaybackViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
        fun seed(next: SponsorSegment?, shown: SkipAction.ShowButton?) {
            // Controlled provider output enters the ORIGINAL reducer. This is only
            // the fixture input; the command, target clamp and resume are original VM.
            state<VideoPlaybackUiState>("_uiState").value = VideoPlaybackUiState.Success(
                ViewInfo(bvid = "BVfixture", aid = 17L, cid = 70L), "https://fixture.invalid/no-media")
            state<SponsorSegment?>("_currentSponsorSegment").value = next
            state<SponsorSkipUiState>("_sponsorSkipUiState").value = reduceSponsorSkipUiState(SponsorSkipUiState(), shown)
            state<Boolean>("_showSkipButton").value = shown != null
            state<String?>("_currentSkipReason").value = shown?.label
        }
        fun submit(expectedAction: SkipAction.ShowButton = action, expectedSegment: SponsorSegment = segment): Boolean =
            DesktopUnifiedPlaybackFacade.consumeManualSponsorSkip(expectedAction, expectedSegment,
                { active && sourceCurrent(original) }, { block -> admit(original, block) }, {
                    val current = vm.currentSponsorSegment.value
                    (if (vm.showSkipButton.value && current != null) SkipAction.ShowButton(current.endTimeMs,
                        vm.currentSkipReason.value ?: current.category, current.UUID) else null) to current
                }, vm::skipCurrentSponsorSegment)
        @Suppress("UNCHECKED_CAST") fun commands(): List<Any> {
            val session = MpvPlayer::class.java.getDeclaredField("session").apply { isAccessible = true }.get(player)
            return (session.javaClass.getDeclaredField("commands").apply { isAccessible = true }.get(session) as LinkedBlockingQueue<Any>).toList()
        }
        fun field(command: Any, name: String): Any = command.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(command)
        override fun close() {
            scope.cancel(); dispatcher.drain(); actor.close(); player.close(); folder.toFile().deleteRecursively()
        }
    }

    @Test fun completeVmQueuesOriginalSeekAndResumesPausedOrPlayingVideo(): Unit = onUi {
        for (paused in listOf(true, false)) Harness(paused).use { h ->
            assertTrue(h.submit()); assertTrue(h.commands().isEmpty())
            h.dispatcher.drain()
            val seeks = h.commands().filter { it.javaClass.simpleName == "Seek" }
            assertEquals(1, seeks.size)
            assertEquals(42.5, h.field(seeks.single(), "seconds"))
            assertEquals(h.original.sourceVersion, h.field(seeks.single(), "sourceVersion"))
            assertEquals(listOf(42_500L), h.logs)
            assertFalse(h.vm.showSkipButton.value); assertNull(h.vm.currentSponsorSegment.value)
            assertFalse(h.player.state.value.paused, "Fixed a4b original manual skip resumes even when previously paused")
        }
    }

    @Test fun completeVmUsesOriginalEndGuardAndInvalidActionNeverCallsIt(): Unit = onUi {
        Harness().use { h ->
            val end = h.segment.copy(segment = listOf(110f, 150f))
            val action = h.action.copy(skipToMs = end.endTimeMs)
            h.seed(end, action)
            assertTrue(h.submit(action, end)); h.dispatcher.drain()
            val seek = h.commands().single { it.javaClass.simpleName == "Seek" }
            assertEquals(119.0, h.field(seek, "seconds")) // Actual readback duration=120s, original non-outro guard=1s.
        }
        Harness().use { h ->
            assertFalse(h.submit(h.action.copy(skipToMs = 0)))
            assertFalse(h.submit(h.action.copy(segmentId = "")))
            assertFalse(h.submit(h.action.copy(skipToMs = 43_000)))
            h.dispatcher.drain(); assertTrue(h.commands().isEmpty()); assertTrue(h.vm.showSkipButton.value)
        }
    }

    @Test fun changedSegmentOrHiddenActionCannotConsumeOldRenderedButton(): Unit = onUi {
        Harness().use { h ->
            val replacement = h.segment.copy()
            h.seed(replacement, h.action)
            assertSame(h.segment, h.vm.currentSponsorSegment.value, "StateFlow conflates equal provider values")
            h.seed(null, null)
            h.seed(replacement, h.action)
            assertNotSame(h.segment, h.vm.currentSponsorSegment.value, "The provider receipt really changed")
            assertFalse(h.submit()); assertTrue(h.commands().isEmpty())
            h.seed(h.segment, null); assertFalse(h.submit())
            h.seed(h.segment, h.action); h.active = false; assertFalse(h.submit())
            h.dispatcher.drain(); assertTrue(h.commands().isEmpty())
        }
    }

    @Test fun accountAndSameNumericAcceptedReplacementAtFinalGateRejectBeforeVmCall(): Unit = onUi {
        for (retire in listOf<(Harness) -> Unit>({ it.account = 2L }, { it.accepted = it.replacement() })) {
            Harness().use { h ->
                h.beforeAdmission = { retire(h) }
                assertFalse(h.submit()); h.dispatcher.drain()
                assertTrue(h.commands().isEmpty()); assertTrue(h.vm.showSkipButton.value)
            }
        }
    }

    @Test fun existingInvocationRejectsSourceRetirementBetweenClickAndQueuedCommand(): Unit = onUi {
        Harness().use { h ->
            assertTrue(h.submit())
            h.accepted = h.replacement()
            h.dispatcher.drain()
            assertTrue(h.commands().isEmpty()); assertTrue(h.vm.showSkipButton.value)
        }
        Harness().use { h ->
            assertTrue(h.submit()); h.beforeQueuedSeek = { h.accepted = h.replacement() }
            h.dispatcher.drain(); assertTrue(h.commands().isEmpty()); assertTrue(h.logs.isEmpty())
        }
    }

    @Test fun backgroundCallerCannotReadOrStartUiOwnedCommand() {
        assertFalse(SwingUtilities.isEventDispatchThread())
        val segment = SponsorSegment(listOf(1f, 2f), "manual", "sponsor")
        assertFailsWith<IllegalStateException> {
            DesktopUnifiedPlaybackFacade.consumeManualSponsorSkip(SkipAction.ShowButton(2_000, "跳过", "manual"), segment,
                { fail("No background ownership read") }, { fail("No admission") }, { fail("No provider read") }, { fail("No invocation") })
        }
    }
}
