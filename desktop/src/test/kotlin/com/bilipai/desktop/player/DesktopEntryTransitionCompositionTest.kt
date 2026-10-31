package com.bilipai.desktop.player

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.android.purebilibili.feature.video.screen.rememberVideoDetailEntryTransitionFinished
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.*
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the actual migrated observer in Compose, without mounting a window or decoder. */
class DesktopEntryTransitionCompositionTest {
    @Test
    fun completedEntryDoesNotRecloseWhenDeferChanges() = runBlocking {
        val host = Host(this, initiallyDeferred = false)
        try {
            assertTrue(host.finished)
            host.deferred.value = true
            host.frames()
            assertTrue(host.finished)
            host.deferred.value = false
            host.frames()
            host.deferred.value = true
            host.frames()
            assertTrue(host.finished)
        } finally { host.close() }
    }

    @Test
    fun coveredAndRunningEntriesWaitAndCompletedEntryKeepsItsLatch() = runBlocking {
        val navigation = Navigation()
        val host = Host(this, initiallyDeferred = true, navigation = navigation)
        try {
            host.frames()
            assertFalse(host.finished)
            navigation.currentRole.value = NavRole.Top
            navigation.running.value = true
            host.frames()
            assertFalse(host.finished)
            navigation.running.value = false
            host.frames()
            assertTrue(host.finished)
            navigation.currentRole.value = NavRole.Covered
            navigation.running.value = true
            host.frames()
            assertTrue(host.finished)
        } finally { host.close() }
    }

    private class Navigation : NavTransitionScope {
        val currentRole = mutableStateOf(NavRole.Covered)
        val running = mutableStateOf(false)
        override val role get() = currentRole.value
        override val isRunning get() = running.value
        override val relativeDepth get() = if (role == NavRole.Top) 0f else 1f
        override val change = NavChange.None
        override val gesture: NavGesture? = null
        override val layoutSize = IntSize(1280, 720)
        override val layoutDirection = LayoutDirection.Ltr
        override val density = Density(1f)
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    private class Host(scope: CoroutineScope, initiallyDeferred: Boolean, navigation: NavTransitionScope? = null) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val runner = scope.launch(clock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private val composition = Composition(EmptyApplier(), recomposer)
        private val tick = mutableStateOf(0)
        val deferred = mutableStateOf(initiallyDeferred)
        var finished = false
            private set
        init {
            composition.setContent {
                tick.value
                val value = rememberVideoDetailEntryTransitionFinished(
                    deferLoad = deferred.value,
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                    fallbackDurationMillis = 60_000,
                    navigationTransitionScope = navigation,
                )
                SideEffect { finished = value }
            }
        }
        suspend fun frames() {
            repeat(3) {
                tick.value++
                Snapshot.sendApplyNotifications()
                withTimeout(10_000) { while (!clock.hasAwaiters) yield() }
                clock.sendFrame(System.nanoTime())
                withTimeout(10_000) {
                    // A snapshotFlow effect can enqueue another composition after this
                    // frame. Keep supplying frames until it commits instead of waiting
                    // for idle while withholding the frame that makes idle possible.
                    while (recomposer.currentState.value > Recomposer.State.Idle) {
                        Snapshot.sendApplyNotifications()
                        if (clock.hasAwaiters) clock.sendFrame(System.nanoTime())
                        yield()
                    }
                }
                yield()
            }
        }
        suspend fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }
}
