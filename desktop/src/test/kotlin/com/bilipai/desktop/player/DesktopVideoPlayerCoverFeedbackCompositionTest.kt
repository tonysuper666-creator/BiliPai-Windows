package com.bilipai.desktop.player

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.feature.video.screen.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import com.android.purebilibili.feature.video.ui.section.VideoPlayerCoverFeedback
import com.android.purebilibili.feature.video.ui.section.resolveVideoPlayerCoverMotionSpec
import com.android.purebilibili.feature.video.viewmodel.PlayerToastMessage
import com.android.purebilibili.feature.video.viewmodel.PlayerToastPresentation
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import com.bilipai.desktop.ui.DesktopOriginalPortraitDanmakuPort
import com.bilipai.desktop.ui.DesktopOriginalVideoHolderPlatform
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.*

/** Exercises the production Holder provider/event-state and Section animation/feedback.
 * One test also invokes the real VM cache-restore publisher. Full Root/VM lifecycle and
 * native playback are outside these fixtures; unrelated ports fail immediately. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopVideoPlayerCoverFeedbackCompositionTest {
    @Test fun feedbackWaitsForTheActualCoverExitCompletion() = withHost {
        notice()
        assertCover(current = true, target = true, idle = true)
        assertFeedback(false)
        assertNull(events.resumeMessageDeadlineMs)

        visible.value = false
        frames(2)
        assertCover(current = true, target = false, idle = false)
        assertFeedback(false)
        assertNull(events.resumeMessageDeadlineMs)
        frames(5)
        assertFalse(cover.isIdle)
        assertFeedback(false)

        frames(20)
        assertCover(current = false, target = false, idle = true)
        assertFeedback(true)
        assertNotNull(events.resumeMessageDeadlineMs)
    }

    @Test fun realReturnBudgetSuppressesFeedbackBeforeAndAfterExit() = withHost {
        notice()
        phase.value = VideoDetailReturnSessionPhase.Preview
        visible.value = false
        frames(2)
        assertTrue(suppressed)
        assertFeedback(false)

        // Removing the real budget suppression cannot bypass an unfinished fade.
        phase.value = VideoDetailReturnSessionPhase.Idle
        frames(2)
        assertFalse(suppressed)
        assertFalse(cover.isIdle)
        assertFeedback(false)

        phase.value = VideoDetailReturnSessionPhase.Morph
        frames(24)
        assertTrue(suppressed)
        assertCover(current = false, target = false, idle = true)
        assertFeedback(false)
        assertNull(events.resumeMessageDeadlineMs)

        phase.value = VideoDetailReturnSessionPhase.Idle
        frames(2)
        assertFeedback(true)
        phase.value = VideoDetailReturnSessionPhase.Restoring
        frames(2)
        assertFeedback(false)
        phase.value = VideoDetailReturnSessionPhase.Idle
        frames(2)
        assertFeedback(true)
    }

    @Test fun rapidReshowCancelsExitAndRevokesPreviouslyReadyFeedback() = withHost {
        notice()
        visible.value = false
        frames(5)
        assertFalse(cover.isIdle)
        visible.value = true
        frames(2)
        assertTrue(cover.targetState)
        assertFeedback(false)
        // Compose uses its interruption spring when reversing a tween. Drive
        // actual completion, checking feedback on every frame, rather than
        // assuming the original non-interrupted tween duration still applies.
        finishCoverWithFeedbackHidden()
        assertCover(current = true, target = true, idle = true)

        visible.value = false
        frames(26)
        assertFeedback(true)
        visible.value = true
        frames(2)
        assertFeedback(false)
        assertTrue(cover.targetState)
    }

    @Test fun identityChangeWithTheSameRootDanmakuPortResetsAnimationAndPendingNotice() = withHost {
        notice()
        visible.value = false
        frames(26)
        assertFeedback(true)
        val previousEvents = events
        val previousCover = cover
        assertNotNull(previousEvents.resumeMessageDeadlineMs)

        identity.value = "BV-next:2"
        visible.value = true
        frames(2)
        assertNotSame(previousCover, cover)
        assertNotSame(previousEvents, events)
        assertSame(previousEvents.danmakuManager, events.danmakuManager)
        assertNull(events.resumeMessage)
        assertNull(events.resumeMessageDeadlineMs)
        assertCover(current = true, target = true, idle = true)
        assertFeedback(false)

        // Even an old queued notice cannot render under the new media identity.
        events.resumeMessage = previousEvents.resumeMessage
        visible.value = false
        frames(26)
        assertCover(current = false, target = false, idle = true)
        assertFeedback(false)
        notice()
        assertFeedback(true)
    }

    @Test fun identityChangeDuringExitDoesNotInheritThePreviousTransition() = withHost {
        notice()
        visible.value = false
        frames(5)
        val previousCover = cover
        assertFalse(previousCover.isIdle)
        identity.value = "BV-next:2"
        visible.value = true
        frames(2)
        assertNotSame(previousCover, cover)
        assertCover(current = true, target = true, idle = true)
        assertNull(events.resumeMessage)
        assertFeedback(false)
        notice()
        visible.value = false
        frames(2)
        assertFalse(cover.isIdle)
        assertFeedback(false)
        frames(24)
        assertFeedback(true)
    }

    @Test fun retainedNativeSurfaceWithoutFadeStillHonorsTheRealReturnBudget() =
        withHost(surfaceCanCrossfade = false) {
            notice()
            assertCover(current = true, target = true, idle = true)
            assertFeedback(false)
            phase.value = VideoDetailReturnSessionPhase.Morph
            visible.value = false
            frames(4)
            assertCover(current = false, target = false, idle = true)
            assertTrue(suppressed)
            assertFeedback(false)
            assertNull(events.resumeMessageDeadlineMs)
            phase.value = VideoDetailReturnSessionPhase.Idle
            frames(2)
            assertFeedback(true)
            visible.value = true
            frames(4)
            assertCover(current = true, target = true, idle = true)
            assertFeedback(false)
        }

    @Test fun sameVideoPartChangeClearsPendingFeedbackWithoutReplacingTheRetainedPort() = withHost {
        notice()
        visible.value = false
        frames(26)
        assertFeedback(true)
        val previousEvents = events
        val previousCover = cover
        assertNotNull(events.resumeMessageDeadlineMs)

        // A part change retains the BVID-scoped container. Before the subject
        // observer catches up, the real feedback consumer rejects the old key.
        identity.value = "BV-first:2"
        visible.value = true
        frames(2)
        assertSame(previousEvents, events)
        assertNotSame(previousCover, cover)
        assertCover(current = true, target = true, idle = true)
        assertNotNull(events.resumeMessage)
        assertFeedback(false)

        subject.value = snapshot("BV-first:2")
        frames(2)
        assertSame(previousEvents, events)
        assertSame(previousEvents.danmakuManager, events.danmakuManager)
        assertNull(events.resumeMessage)
        assertNull(events.resumeMessageDeadlineMs)
        notice()
        assertFeedback(false)
        visible.value = false
        frames(2)
        assertCover(current = true, target = false, idle = false)
        assertFeedback(false)
        assertNull(events.resumeMessageDeadlineMs)
        frames(24)
        assertCover(current = false, target = false, idle = true)
        assertFeedback(true)

        // Going back to part 1 cannot resurrect either previous part's notice.
        identity.value = "BV-first:1"
        subject.value = snapshot("BV-first:1")
        frames(2)
        assertNull(events.resumeMessage)
        assertNull(events.resumeMessageDeadlineMs)
        assertFeedback(false)
    }

    @Test fun liveMorphUsesTheRealKeepBudgetRatherThanSyntheticSuppression() = withHost {
        notice()
        phase.value = VideoDetailReturnSessionPhase.Morph
        visible.value = false
        frames(26)
        assertTrue(suppressed)
        assertFeedback(false)
        hasRenderableLiveFrame.value = true
        frames(2)
        assertFalse(suppressed)
        assertFeedback(true)
    }

    @Test fun vmCacheRestoreEmitsNewPartAndClearsOldFeedback() {
        VmPublisher().use { publisher ->
            publisher.restore(1)
            val first = assertNotNull(publisher.vm.subjectSnapshot.value)
            assertEquals("BV-first", first.bvid)
            assertEquals(1L, first.cid)
            Host(surfaceCanCrossfade = true, productionSubject = publisher.vm.subjectSnapshot).use { host ->
                host.notice()
                host.visible.value = false
                host.frames(26)
                host.assertFeedback(true)
                val previousEvents = host.events
                assertNotNull(previousEvents.resumeMessageDeadlineMs)

                // Real VM restore publishes the subject; no test writes to its flow.
                publisher.restore(2)
                val second = assertNotNull(publisher.vm.subjectSnapshot.value)
                assertEquals("BV-first", second.bvid)
                assertEquals(2L, second.cid)
                assertTrue(second.generation > first.generation)
                host.identity.value = "BV-first:2"
                host.frames(2)
                assertSame(previousEvents, host.events)
                assertNull(host.events.resumeMessage)
                assertNull(host.events.resumeMessageDeadlineMs)
                host.assertFeedback(false)

                // Switching back cannot resurrect the first part's deadline or notice.
                publisher.restore(1)
                host.identity.value = "BV-first:1"
                host.frames(2)
                assertNull(host.events.resumeMessage)
                assertNull(host.events.resumeMessageDeadlineMs)
                host.assertFeedback(false)
            }
        }
    }

    /** Actual desktop VM and cache-restore producer. All external ports fail closed;
     * queued unrelated plugin polling is never executed and no native surface exists. */
    private class VmPublisher : AutoCloseable {
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java)
        ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
        private val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        }
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val folder = Files.createTempDirectory("bp-resume-subject-")
        private val context = DesktopPluginContext(DesktopPluginStore(folder))
        private fun owns() = true
        private fun commit(block: () -> Unit): Boolean { block(); return true }
        private val repository = unused<DesktopOriginalVideoOwnerRepository>()
        private val media = unused<DesktopOriginalVideoMediaPort>()
        private val actions = unused<DesktopOriginalVideoOwnerActions>()
        private val invocations = DesktopOriginalVideoPlaybackInvocationPorts(scope, ::owns, {
            error("No network or media invocation in cache-restore fixture")
        }, unused(), { media }, captureMetadataBinding = { error("No metadata request") })
        private val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
            arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, _ ->
            when (method.name) {
                "enabledPlayerPlugins" -> emptyList<DesktopPlayerPluginSessionHandle>()
                "capturePlaybackDispatch" -> null
                else -> error("Unexpected plugin work: ${method.name}")
            }
        } as DesktopOriginalVideoOwnerPlugins
        private val api = unused<BilibiliApi>()
        private val interaction = VideoInteractionUseCase(
            com.android.purebilibili.data.repository.createTestVideoEngagementProtocol(api,
                { "fixture" }, { 1L }, { "fixture" }, { null }, {}, {},
                DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture" }, {})), unused())
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::owns, ::commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, repository, actions,
            unused(), unused(), media, {}, { emptyMap() }, {}, { false }, { _, _, _, _ -> }, ::owns)
        val vm = VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(scope, settings, invocations,
            repository, unused(), actions, useCase, interaction, unused(), unused(), unused(), plugins,
            unused(), unused(), unused(), { null }, unused(), unused(), unused(), unused(), MutableStateFlow(false),
            ::owns, ::commit, DesktopTodayWatchFeedbackWriteBinding(context, ::owns, ::commit),
            captureNativePublication = { null }, admitNativePublication = { _, _ -> false },
            readRelatedPlaybackState = { null }))
        fun restore(cid: Long) = vm.restoreFromCache(VideoPlaybackUiState.Success(
            ViewInfo(bvid = "BV-first", aid = 17L, cid = cid, title = "fixture"), ""))
        override fun close() { scope.cancel(); folder.toFile().deleteRecursively() }
    }

    private fun withHost(surfaceCanCrossfade: Boolean = true, test: Host.() -> Unit) {
        Host(surfaceCanCrossfade).use { it.test() }
    }

    private class Host(surfaceCanCrossfade: Boolean, productionSubject: StateFlow<VideoSubjectSnapshot?>? = null) : AutoCloseable {
        val identity = mutableStateOf("BV-first:1")
        fun snapshot(key: String) = VideoSubjectSnapshot(
            bvid = key.substringBefore(':'), cid = key.substringAfter(':').toLong(),
            aid = 1L, ownerMid = 1L, title = "fixture", coverUrl = "", durationMs = 60_000L,
            generation = key.substringAfter(':').toLong(),
        )
        val subject = MutableStateFlow<VideoSubjectSnapshot?>(snapshot(identity.value))
        val visible = mutableStateOf(true)
        val phase = mutableStateOf(VideoDetailReturnSessionPhase.Idle)
        val hasRenderableLiveFrame = mutableStateOf(false)
        private val motion = resolveVideoPlayerCoverMotionSpec(
            forceCoverDuringReturnAnimation = false,
            surfaceCanCrossfade = surfaceCanCrossfade,
        )
        lateinit var cover: MutableTransitionState<Boolean>
        lateinit var events: VideoDetailPlaybackEventState
        var suppressed = false
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> errors += error })
        private val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        // Root's retained port is intentionally stable while the media identity changes.
        private val danmaku = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(DesktopOriginalPortraitDanmakuPort::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fixture-retained-danmaku-port"
                else -> error("Fixture must not perform native/danmaku work: ${method.name}")
            }
        } as DesktopOriginalPortraitDanmakuPort
        private val holder = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(DesktopOriginalVideoHolderPlatform::class.java)) { _, method, _ ->
            when (method.name) {
                "getDanmaku" -> danmaku
                else -> error("Fixture must not acquire another Root resource: ${method.name}")
            }
        } as DesktopOriginalVideoHolderPlatform
        private val scene = ImageComposeScene(480, 320, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDesktopOriginalVideoHolderPlatform provides holder,
                    LocalLifecycleOwner provides owner,
                ) {
                    // Match the retained Holder: event state is keyed by currentBvid;
                    // the Section transition and feedback message carry bvid:cid.
                    val state = rememberVideoDetailPlaybackEventState(identity.value.substringBefore(':'))
                    VideoDetailResumeIdentityEffect(productionSubject ?: subject, state)
                    val returnBudget = resolveVideoDetailReturnVisualBudget(
                        phase = phase.value,
                        hasRenderableLiveFrame = hasRenderableLiveFrame.value,
                    )
                    val suppress = shouldSuppressOverlayControlsForReturnBudget(returnBudget)
                    VideoDetailPlaybackFeedbackProvider(state) {
                        Box {
                            val transition = VideoPlayerCoverFeedback(
                                playbackIdentity = identity.value,
                                visible = visible.value,
                                showCover = visible.value,
                                keepCoverForManualStart = false,
                                suppressTransientOverlaysForTransition = suppress,
                                forceCoverDuringReturnAnimation = false,
                                isInPipMode = false,
                                isPortraitFullscreen = false,
                                mediaKey = identity.value,
                                fullscreen = false,
                                controlsVisible = false,
                                enter = if (motion.shouldAnimateFade) {
                                    fadeIn(tween(motion.enterFadeDurationMillis))
                                } else EnterTransition.None,
                                exit = if (motion.shouldAnimateFade) {
                                    fadeOut(tween(motion.exitFadeDurationMillis))
                                } else ExitTransition.None,
                            ) { Box(Modifier.size(80.dp)) { Text("cover") } }
                            SideEffect {
                                cover = transition
                                events = state
                                suppressed = suppress
                            }
                        }
                    }
                }
            }
        }

        init { frames(3) }

        fun frames(count: Int) {
            repeat(count) {
                Snapshot.sendApplyNotifications()
                scheduler.runCurrent()
                scene.render(scheduler.currentTime * 1_000_000L).close()
                scheduler.advanceTimeBy(16)
                scheduler.runCurrent()
                assertTrue(errors.isEmpty(), errors.joinToString { it.stackTraceToString() })
            }
        }

        fun notice() {
            events.resumeMessage = PlayerToastMessage("resume:${identity.value}",
                PlayerToastPresentation.PlaybackResume, identity.value)
            events.resumeMessageDeadlineMs = null
            frames(2)
        }

        fun finishCoverWithFeedbackHidden() {
            repeat(120) { frame ->
                assertFeedback(false)
                if (cover.isIdle) {
                    println("interrupted cover settled after $frame frames at ${scheduler.currentTime}ms")
                    return
                }
                frames(1)
            }
            fail("Interrupted cover did not settle within the bounded test clock")
        }

        fun assertCover(current: Boolean, target: Boolean, idle: Boolean) {
            println("cover identity=${identity.value} t=${scheduler.currentTime}ms " +
                "current=${cover.currentState} target=${cover.targetState} idle=${cover.isIdle}")
            assertEquals(current, cover.currentState, "currentState")
            assertEquals(target, cover.targetState, "targetState")
            assertEquals(idle, cover.isIdle, "isIdle")
        }

        private fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)

        fun assertFeedback(expected: Boolean) {
            val labels = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            println("feedback identity=${identity.value} t=${scheduler.currentTime}ms " +
                "current=${cover.currentState} target=${cover.targetState} idle=${cover.isIdle} " +
                "phase=${phase.value} liveFrame=${hasRenderableLiveFrame.value} " +
                "suppressed=$suppressed labels=$labels")
            assertEquals(expected, labels.any { it.startsWith("resume:") },
                "t=${scheduler.currentTime}ms current=${cover.currentState} target=${cover.targetState} " +
                    "idle=${cover.isIdle} suppressed=$suppressed labels=$labels")
        }

        override fun close() {
            scene.close()
            scope.cancel()
            scheduler.runCurrent()
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }
}
