package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Rect
import com.android.purebilibili.core.ui.transition.VideoCardTransitionBackgroundPhase
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.core.util.CardPositionManager
import com.android.purebilibili.navigation3.BiliPaiNavKey
import java.io.Closeable
import java.lang.reflect.Modifier
import kotlin.test.*

/** Exercises the actual return owner before it changes transition/session state. */
class DesktopHomeReturnSourceAdmissionTest {
    private val sourceBounds = Rect(80f, 240f, 400f, 440f)

    /**
     * Borrow only the actual singleton's Rect backing field, then restore the exact old reference.
     * recordCardPosition would clear native layers/bitmap and overwrite private screen dimensions;
     * none of those globals are changed here. The real transition capture/prearm reads this Rect.
     * No replacement CardPositionManager, clock, transition policy or navigation owner is supplied.
     */
    private fun withRealCardGeometry(block: () -> Unit) = synchronized(CardPositionManager) {
        val field = CardPositionManager::class.java.getDeclaredField("lastClickedCardBounds")
        val receiver = if (Modifier.isStatic(field.modifiers)) null else CardPositionManager
        check(field.trySetAccessible()) { "Actual card-bounds fixture must be reversible" }
        val previous = field.get(receiver)
        field.set(receiver, sourceBounds)
        try { block() } finally { field.set(receiver, previous) }
    }

    private class Fixture : Closeable {
        val events = mutableListOf<String>()
        val clock = VideoCardTransitionClock().apply {
            beginOpening("previous-source")
            reportSharedMorphProgress(0.4f, true)
        }
        var sourceCurrent = true
        var allowSource = true
        var retireAtCheckpoint = false
        var retireAtSourceAdmission = false
        var navigations = 0
        var navigationClockSnapshot: List<Any?>? = null
        val owner = DesktopHomeReturnNavigationOwner(
            stillOwned = { true },
            commitIfCurrent = { action -> events += "root-entry"; action(); true },
            admitRootNavigation = { action ->
                events += "checkpoint"
                if (retireAtCheckpoint) sourceCurrent = false
                action(); true
            },
            hostOriginInRoot = { androidx.compose.ui.geometry.Offset.Zero },
            monotonicMillis = { 123L }, clock = clock,
            sharedCardTransitionEnabled = { true }, relatedCardTransitionEnabled = { false },
            reduceMotion = { false },
        )
        private fun navigate() {
            events += "navigate"
            navigations++
            navigationClockSnapshot = clockSnapshot()
        }
        fun guarded() = owner.enterVideoFromSource("BV1xx411c7mD", "home", "", BiliPaiNavKey.MainHost,
            false, setOf("home"), { sourceCurrent }, { action ->
                events += "source-entry"
                if (retireAtSourceAdmission) sourceCurrent = false
                if (allowSource) { action(); true } else false
            }) { _, _ -> navigate() }
        fun original() = owner.enterVideo("BV1xx411c7mD", "home", "", BiliPaiNavKey.MainHost,
            false, setOf("home")) { _, _ -> navigate() }
        fun clockSnapshot(): List<Any?> = listOf(clock.phase, clock.sourceRoute, clock.gestureBackProgress,
            clock.gestureStartDepth, clock.gestureRestoreInProgress, clock.returnDepthFloor,
            clock.initialVelocity, clock.fallbackValue, clock.settleState, clock.depthProgress(),
            clock.hasActiveSharedMorphProgress())
        override fun close() { owner.close() }
    }

    @Test fun deniedSourceCannotChangeReturnSessionOrPrearmedClock() = withRealCardGeometry {
        Fixture().use { f ->
            val session = f.owner.session.value
            val clock = f.clockSnapshot()
            f.allowSource = false
            assertFalse(f.guarded())
            assertEquals(session, f.owner.session.value)
            assertEquals(clock, f.clockSnapshot())
            assertEquals(0, f.navigations)
            assertNull(f.navigationClockSnapshot)
            assertEquals(listOf("checkpoint", "root-entry", "source-entry"), f.events)
        }
    }

    @Test fun sourceRetiringDuringCheckpointCannotEnterReturnMutation() = withRealCardGeometry {
        Fixture().use { f ->
            val session = f.owner.session.value
            val clock = f.clockSnapshot()
            f.retireAtCheckpoint = true
            assertFalse(f.guarded())
            assertEquals(session, f.owner.session.value)
            assertEquals(clock, f.clockSnapshot())
            assertEquals(0, f.navigations)
            assertNull(f.navigationClockSnapshot)
            assertEquals(listOf("checkpoint"), f.events)
        }
    }

    @Test fun sourceRetiringInsideFinalAdmissionCannotChangeStateBeforeNavigation() = withRealCardGeometry {
        Fixture().use { f ->
            val session = f.owner.session.value
            val clock = f.clockSnapshot()
            f.retireAtSourceAdmission = true
            assertFalse(f.guarded())
            assertEquals(session, f.owner.session.value)
            assertEquals(clock, f.clockSnapshot())
            assertEquals(0, f.navigations)
            assertNull(f.navigationClockSnapshot)
            assertEquals(listOf("checkpoint", "root-entry", "source-entry"), f.events)
        }
    }

    @Test fun acceptedSourceCommitsAfterCheckpointAndOriginalEntryStillWorks() = withRealCardGeometry {
        Fixture().use { guarded ->
            val initial = guarded.owner.session.value
            val clockBefore = guarded.clockSnapshot()
            assertTrue(guarded.guarded())
            assertNotEquals(initial, guarded.owner.session.value)
            assertEquals(sourceBounds, guarded.owner.session.value.transitionSession?.cardBounds)
            assertNotEquals(clockBefore, guarded.clockSnapshot())
            assertEquals("home", guarded.clock.sourceRoute)
            assertEquals(VideoCardTransitionBackgroundPhase.OPENING, guarded.clock.phase)
            assertFalse(guarded.clock.hasActiveSharedMorphProgress())
            assertEquals(guarded.clockSnapshot(), guarded.navigationClockSnapshot)
            assertEquals(1, guarded.navigations)
            assertEquals(listOf("checkpoint", "root-entry", "source-entry", "navigate"), guarded.events)
            Fixture().use { original ->
                val originalInitial = original.owner.session.value
                val originalClockBefore = original.clockSnapshot()
                assertTrue(original.original())
                assertNotEquals(originalInitial, original.owner.session.value)
                assertEquals(sourceBounds, original.owner.session.value.transitionSession?.cardBounds)
                assertNotEquals(originalClockBefore, original.clockSnapshot())
                assertEquals("home", original.clock.sourceRoute)
                assertEquals(VideoCardTransitionBackgroundPhase.OPENING, original.clock.phase)
                assertFalse(original.clock.hasActiveSharedMorphProgress())
                assertEquals(original.clockSnapshot(), original.navigationClockSnapshot)
                assertEquals(1, original.navigations)
                assertEquals(listOf("checkpoint", "root-entry", "navigate"), original.events)
                assertEquals(original.owner.session.value.lastVideoSourceRoute,
                    guarded.owner.session.value.lastVideoSourceRoute)
            }
        }
    }
}
