package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.core.util.CardPositionManager
import androidx.compose.ui.geometry.Offset
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.core.ui.transition.VideoCardTransitionBackgroundPhase
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import kotlin.test.*

class DesktopWindowsNavigationDisplayTest {
    @Test fun `desktop navigation never slides or settles a native surface between client coordinates`() {
        val transition = DesktopWindowsInstantNavigationTransition
        assertEquals(NavSwipeDirection.None, transition.dismissDirection)
        assertEquals(0f, transition.opaqueDepth)
        for (spec in listOf(transition.motion.commit, transition.motion.cancel, transition.motion.programmatic))
            assertEquals(0, assertIs<NavSettleSpec.Tween>(spec).durationMillis)
    }
    @Test fun `ordinary video prepares the real return before the real pop without related restoration`() {
        val calls = mutableListOf<String>()
        completeDesktopWindowsNavigationBack(BiliPaiNavKey.VideoDetail("BVfixture", sourceRoute = "home"), false,
            { calls += "prepare"; true }, { calls += "back" }, { calls += "related" })
        assertEquals(listOf("prepare", "back"), calls)
    }
    @Test fun `related source with query restores original previous source only after back`() {
        val calls = mutableListOf<String>()
        completeDesktopWindowsNavigationBack(BiliPaiNavKey.VideoDetail("BVfixture", sourceRoute = "video/BVprior?from=related"), false,
            { calls += "prepare"; false }, { calls += "back" }, { calls += "related" })
        assertEquals(listOf("prepare", "back", "related"), calls)
    }
    @Test fun `explicit previous source restoration remains after nonvideo back`() {
        val calls = mutableListOf<String>()
        completeDesktopWindowsNavigationBack(BiliPaiNavKey.Settings, true,
            { calls += "prepare"; true }, { calls += "back" }, { calls += "related" })
        assertEquals(listOf("back", "related"), calls)
    }
    @Test fun `normal nonvideo back never performs a video return`() {
        val calls = mutableListOf<String>()
        completeDesktopWindowsNavigationBack(BiliPaiNavKey.Settings, false,
            { calls += "prepare"; true }, { calls += "back" }, { calls += "related" })
        assertEquals(listOf("back"), calls)
    }
    @Test fun `committed video finishes the armed opening without losing its frozen source`() {
        val clock = VideoCardTransitionClock().apply {
            beginOpening("home")
            reportSharedMorphProgress(0.4f, true)
        }
        settleDesktopWindowsCommittedNavigationClock(clock,
            listOf(BiliPaiNavKey.MainHost, BiliPaiNavKey.VideoDetail("BVfixture")))
        assertEquals(VideoCardTransitionBackgroundPhase.HELD, clock.phase)
        assertEquals("home", clock.sourceRoute)
        assertEquals(1f, clock.depthProgress())
        assertFalse(clock.hasActiveSharedMorphProgress())
    }
    @Test fun `a child above the same video keeps its source held and finishes gesture state`() {
        val clock = VideoCardTransitionClock().apply {
            beginOpening("home")
            beginGesture(0.5f)
            beginGestureRestore()
        }
        settleDesktopWindowsCommittedNavigationClock(clock, listOf(BiliPaiNavKey.MainHost,
            BiliPaiNavKey.VideoDetail("BVfixture"), BiliPaiNavKey.Settings))
        assertEquals(VideoCardTransitionBackgroundPhase.HELD, clock.phase)
        assertEquals("home", clock.sourceRoute)
        assertNull(clock.gestureBackProgress)
        assertFalse(clock.gestureRestoreInProgress)
        assertEquals(1f, clock.depthProgress())
    }
    @Test fun `actual pop to home clears the original source and pending return exposure`() {
        val clock = VideoCardTransitionClock().apply {
            beginReturning("home", 0.8f)
            reportSharedMorphProgress(0.6f, true)
        }
        settleDesktopWindowsCommittedNavigationClock(clock, listOf(BiliPaiNavKey.MainHost))
        assertEquals(VideoCardTransitionBackgroundPhase.IDLE, clock.phase)
        assertNull(clock.sourceRoute)
        assertNull(clock.returnDepthFloor)
        assertNull(clock.gestureBackProgress)
        assertFalse(clock.hasActiveSharedMorphProgress())
        assertEquals(0f, clock.depthProgress())
    }
    @Test fun `video navigation without a prearmed transition never invents one`() {
        val clock = VideoCardTransitionClock()
        settleDesktopWindowsCommittedNavigationClock(clock,
            listOf(BiliPaiNavKey.MainHost, BiliPaiNavKey.VideoDetail("BVfixture")))
        assertEquals(VideoCardTransitionBackgroundPhase.IDLE, clock.phase)
        assertNull(clock.sourceRoute)
        assertEquals(0f, clock.depthProgress())
    }
    @Test fun `repeated enter and real pop cannot carry a source into the next video`() {
        val clock = VideoCardTransitionClock()
        for (source in listOf("home", "search")) {
            clock.beginOpening(source)
            settleDesktopWindowsCommittedNavigationClock(clock,
                listOf(BiliPaiNavKey.MainHost, BiliPaiNavKey.VideoDetail("BVfixture")))
            assertEquals(VideoCardTransitionBackgroundPhase.HELD, clock.phase)
            assertEquals(source, clock.sourceRoute)
            settleDesktopWindowsCommittedNavigationClock(clock, listOf(BiliPaiNavKey.MainHost))
            assertEquals(VideoCardTransitionBackgroundPhase.IDLE, clock.phase)
            assertNull(clock.sourceRoute)
        }
    }
    @Test fun `real related pop restores the parent session even while its clock remains held`() {
        synchronized(CardPositionManager) {
            val oldSourceKey = CardPositionManager.lastClickedVideoSourceKey
            val clock = VideoCardTransitionClock()
            val stack = mutableListOf<BiliPaiNavKey>(BiliPaiNavKey.MainHost)
            val owner = DesktopHomeReturnNavigationOwner(
                stillOwned = { true }, commitIfCurrent = { action -> action(); true },
                admitRootNavigation = { action -> action(); true },
                hostOriginInRoot = { Offset.Zero }, monotonicMillis = { 123L }, clock = clock,
                sharedCardTransitionEnabled = { true }, relatedCardTransitionEnabled = { true },
                reduceMotion = { false },
            )
            try {
                val parent = BiliPaiNavKey.VideoDetail("BVparent", sourceRoute = "home")
                val child = BiliPaiNavKey.VideoDetail("BVchild", sourceRoute = "video/BVparent")
                assertTrue(owner.enterVideo(parent.bvid, "home", null, stack.last(), false,
                    setOf("home")) { _, _ -> stack += parent })
                val parentSession = assertNotNull(owner.session.value.transitionSession)
                assertTrue(owner.enterVideo(child.bvid, "video/BVparent", null, stack.last(), true,
                    setOf("home")) { _, _ -> stack += child })
                assertEquals(1, owner.session.value.previousTransitionSessions.size)
                assertEquals(1, owner.session.value.previousVideoSources.size)
                // Seed the same prearmed phase regardless of unrelated global card bounds.
                clock.beginOpening("video/BVparent")
                settleDesktopWindowsCommittedNavigationClock(clock, stack)
                assertEquals(VideoCardTransitionBackgroundPhase.HELD, clock.phase)
                completeDesktopWindowsNavigationBack(child, false, { false },
                    { assertTrue(owner.returnFromVideo(child, parent, true) { stack.removeAt(stack.lastIndex) }) },
                    { assertTrue(completeDesktopWindowsRelatedVideoReturn(owner, clock)) })
                settleDesktopWindowsCommittedNavigationClock(clock, stack)
                assertEquals(parent, stack.last())
                assertSame(parentSession, owner.session.value.transitionSession)
                assertEquals("home", owner.session.value.lastVideoSourceRoute)
                assertTrue(owner.session.value.previousTransitionSessions.isEmpty())
                assertTrue(owner.session.value.previousVideoSources.isEmpty())
                assertEquals(VideoCardTransitionBackgroundPhase.HELD, clock.phase)
                val restored = owner.session.value
                assertTrue(completeDesktopWindowsRelatedVideoReturn(owner, clock))
                assertSame(restored, owner.session.value)
            } finally {
                owner.close()
                CardPositionManager.restoreVideoSourceKey(oldSourceKey)
            }
        }
    }
    @Test fun `a deferred or rejected related back never restores a source before the physical pop`() {
        val calls = mutableListOf<String>()
        val child = BiliPaiNavKey.VideoDetail("BVchild", sourceRoute = "video/BVparent")
        completeDesktopWindowsNavigationBack(child, false,
            { calls += "prepare"; true }, { calls += "accepted-for-preparation" },
            { fail("related source restored before its real pop") }, backCommitted = { false })
        assertEquals(listOf("prepare", "accepted-for-preparation"), calls)
    }

    @Test fun `second back during a related card flight uses the already restored parent source`() {
        synchronized(CardPositionManager) {
            val previousGlobal = CardPositionManager.lastClickedVideoSourceKey
            val clock = VideoCardTransitionClock()
            val stack = mutableListOf<BiliPaiNavKey>(BiliPaiNavKey.MainHost)
            val owner = DesktopHomeReturnNavigationOwner(
                stillOwned = { true }, commitIfCurrent = { it(); true },
                admitRootNavigation = { it(); true }, hostOriginInRoot = { Offset.Zero },
                monotonicMillis = { 234L }, clock = clock, sharedCardTransitionEnabled = { true },
                relatedCardTransitionEnabled = { true }, reduceMotion = { false })
            try {
                val parent = BiliPaiNavKey.VideoDetail("BVparent", sourceRoute = "home")
                val child = BiliPaiNavKey.VideoDetail("BVchild", sourceRoute = "video/BVparent")
                assertTrue(owner.enterVideo(parent.bvid, "home", null, stack.last(), false, setOf("home")) { _, _ -> stack += parent })
                val parentSession = assertNotNull(owner.session.value.transitionSession)
                assertTrue(owner.enterVideo(child.bvid, "video/BVparent", null, stack.last(), true, setOf("home")) { _, _ -> stack += child })
                val capturedChild = assertNotNull(owner.session.value.transitionSession)
                // Actual clock state stays in flight while the real pop exposes its parent.
                clock.bindNavigationDriver { .6f }
                clock.followNavigationDriver(com.android.purebilibili.core.ui.transition.VideoCardTransitionSettleState.AutoReturn)
                assertTrue(owner.returnFromVideo(child, parent, true) { stack.removeAt(stack.lastIndex) })
                assertTrue(owner.completeRelatedReturnAfterWindowsPop())
                assertSame(parentSession, owner.session.value.transitionSession)
                assertEquals("BVchild", capturedChild.bvid)
                assertEquals(VideoCardTransitionBackgroundPhase.RETURNING, clock.phase)
                assertEquals(.6f, clock.depthProgress())
                // No animation-end callback has run; a second genuine back must use Home.
                assertTrue(owner.returnFromVideo(parent, BiliPaiNavKey.MainHost, false) { stack.removeAt(stack.lastIndex) })
                assertEquals(BiliPaiNavKey.MainHost, stack.single())
                assertEquals("home", owner.session.value.lastVideoSourceRoute)
                assertSame(parentSession, owner.session.value.transitionSession)
                assertTrue(owner.session.value.previousTransitionSessions.isEmpty())
                assertTrue(owner.session.value.previousVideoSources.isEmpty())
            } finally {
                clock.bindNavigationDriver(null)
                owner.close()
                CardPositionManager.restoreVideoSourceKey(previousGlobal)
            }
        }
    }

}
