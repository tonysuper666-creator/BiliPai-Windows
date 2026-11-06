package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
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
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Upstream 540d86f92: changing speed only updates the playback rate. With a
 * premium audio track requested, the complete installed VM must not schedule an
 * audio-source rebuild, which would drop the buffer and cover the first frame.
 * The fixture MPV session is in memory; no native DLL, window or media IO. */
class DesktopVideoPlaybackSpeedContinuityTest {
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
    private inner class Harness : AutoCloseable {
        val folder = Files.createTempDirectory("bp-speed-continuity-")
        val dispatcher = PendingDispatcher()
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> failures += failure })
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        val player = MpvPlayer()
        val actor: PremiumRecoveryActor
        val original: DesktopOriginalVideoAcceptedPublication

        init {
            player.load(PlaybackSource("https://fixture.invalid/no-media", startPaused = false))
            actor = PremiumRecoveryActor(player)
            actor.native.mpv_set_property_string(com.sun.jna.Pointer(1L), "pause", "no")
            actor.loaded()
            original = DesktopOriginalVideoAcceptedPublication(PlaybackRequest.create("BVfixture", 17L, 70L),
                assertNotNull(player.currentSourceSnapshot()))
        }
        fun owned() = true
        fun admit(expected: DesktopOriginalVideoAcceptedPublication, block: () -> Unit): Boolean {
            if (expected !== original || !player.ownsSourceSnapshot(expected.nativeSource)) return false
            var applied = false
            val admitted = player.admitSourceSnapshot(expected.nativeSource) { block(); applied = true }
            return admitted && applied
        }
        private val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
            arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, args ->
            when (method.name) {
                "enabledPlayerPlugins" -> emptyList<com.android.purebilibili.core.plugin.DesktopSingleSessionPlayerPlugin>()
                "capturePlaybackDispatch" -> original
                "isPlaybackDispatchCurrent" -> args!![0] === original
                "admitPlaybackDispatch" -> {
                    @Suppress("UNCHECKED_CAST")
                    admit(args!![0] as DesktopOriginalVideoAcceptedPublication, args[1] as () -> Unit)
                }
                else -> error("No remote/provider execution: ${method.name}")
            }
        } as DesktopOriginalVideoOwnerPlugins
        private val repository = unused<DesktopOriginalVideoOwnerRepository>()
        private val media = unused<DesktopOriginalVideoMediaPort>()
        private val invocations = DesktopOriginalVideoPlaybackInvocationPorts(scope, ::owned, {
            DesktopOriginalVideoPlaybackInvocation(repository, media, assertCurrentPort = {},
                mediaPreparation = { error("No media preparation in speed fixture") })
        }, unused(), { media }, captureMetadataBinding = { error("Readonly metadata retry is outside this fixture") })
        private val api = unused<BilibiliApi>()
        private val interaction = VideoInteractionUseCase(com.android.purebilibili.data.repository.createTestVideoEngagementProtocol(api,
            { "fixture-csrf" }, { 1L }, { "fixture-session" }, { null }, {}, {},
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, {})), unused())
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::owned,
            { block -> block(); true }, largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, repository, unused(), unused(), unused(),
            media, {}, { emptyMap() }, {}, { false }, { _, _, _, _ -> }, ::owned)
        val vm = VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(scope, settings, invocations,
            repository, unused(), unused(), useCase, interaction, unused(), unused(), unused(), plugins,
            unused(), unused(), unused(), { null }, unused(), unused(), unused(), unused(), MutableStateFlow(false),
            ::owned, { block -> block(); true },
            DesktopTodayWatchFeedbackWriteBinding(context, ::owned, { it(); true }),
            captureNativePublication = { original.takeIf { player.ownsSourceSnapshot(it.nativeSource) } },
            admitNativePublication = ::admit,
            readRelatedPlaybackState = { expected ->
                var observed: com.bilipai.desktop.player.PlayerState? = null
                if (admit(expected) { observed = player.state.value }) observed else null
            }))
        val section = DesktopOriginalMpvSectionControl(player, { original.sourceVersion }, ::owned,
            { block -> admit(original, block) }, { false }, {}, {}, { _, _, _, _ -> }, scope,
            MutableStateFlow<Long?>(null), { block -> block(); true })
        init { vm.attachPlayer(section) }
        @Suppress("UNCHECKED_CAST") fun uiState() =
            VideoPlaybackViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<VideoPlaybackUiState>
        override fun close() {
            scope.cancel(); dispatcher.drain(); actor.close(); player.close(); folder.toFile().deleteRecursively()
        }
    }

    @Test fun premiumAudioSpeedChangeOnlyUpdatesRate(): Unit = onUi {
        for (audio in listOf(30251, 30250)) Harness().use { h ->
            h.uiState().value = VideoPlaybackUiState.Success(
                ViewInfo(bvid = "BVfixture", aid = 17L, cid = 70L), "https://fixture.invalid/no-media",
                requestedAudioQuality = audio)
            h.dispatcher.drain()
            val sourceBefore = assertNotNull(h.player.currentSourceSnapshot())
            assertEquals(1f, h.section.playbackSpeed)

            assertTrue(h.vm.applyPlaybackSpeedFromUi(2.0f))
            assertEquals(2f, h.section.playbackSpeed)
            // Rebuilding the audio source would reach the media/repository ports, which fail here.
            h.dispatcher.drain()
            assertEquals(emptyList(), h.failures, "A speed change does not rebuild the audio source")
            assertEquals(2f, h.section.playbackSpeed)
            assertTrue(h.player.ownsSourceSnapshot(sourceBefore), "The playing source is kept")
            assertEquals(sourceBefore.sourceVersion, h.player.currentSourceSnapshot()?.sourceVersion)
            assertFalse(h.player.state.value.paused)
        }
    }
}
