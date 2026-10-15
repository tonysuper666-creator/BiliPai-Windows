package com.bilipai.desktop.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.*

/** Actual production timeline modifier on a raster scene, with a competing
 * parent tap handler, real mouse events and actual MPV source receipts.
 * No Window or native session is attached; no media is opened or decoded. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopOfflinePointerInputTest {
    @Test fun primaryClickCommitsOnceWithoutTogglingTheParentControls() = scene { s ->
        s.press(25f)
        assertTrue(s.commits.isEmpty())
        s.release(25f)
        assertEquals(listOf<Pair<Int?, Long>>(1 to 11_250L), s.commits)
        assertEquals(0, s.parentTaps)
        assertEquals(0, s.cancels)
    }

    @Test fun dragUsesOriginalClampingAndCommitsOnlyTheReleasePosition() = scene { s ->
        s.press(50f)
        s.move(-20f)
        s.move(125f)
        assertTrue(s.commits.isEmpty())
        assertEquals(1f, s.previews.last())
        s.release(125f)
        assertEquals(listOf<Pair<Int?, Long>>(1 to 45_000L), s.commits)
        assertTrue(s.previews.contains(0f))
        assertEquals(0, s.parentTaps)
    }

    @Test fun sourceRetirementCancelsTheGestureWithoutSeekingAndAllowsTheNextGesture() = scene { s ->
        s.press(10f)
        s.current.value = false
        s.render()
        s.move(70f)
        s.release(70f)
        assertTrue(s.commits.isEmpty())
        assertEquals(1, s.cancels)
        s.current.value = true
        s.render()
        s.press(20f)
        s.release(20f)
        assertEquals(listOf<Pair<Int?, Long>>(1 to 9_000L), s.commits)
    }

    @Test fun changingTheCapturedSourceCancelsRatherThanSeekingItsSuccessor() = scene { s ->
        s.press(10f)
        s.replaceSource(2)
        s.render()
        s.release(90f)
        assertTrue(s.commits.isEmpty())
        assertEquals(1, s.cancels)
        s.press(30f)
        s.release(30f)
        assertEquals(listOf<Pair<Int?, Long>>(2 to 13_500L), s.commits)
    }

    @Test fun repeatedActualSourceReceiptsDuringADragDoNotRetireTheGesture() = scene { s ->
        s.press(10f)
        repeat(6) { frame ->
            s.refreshSourceReceipt()
            s.render()
            s.move(20f + frame * 8f)
        }
        assertTrue(s.commits.isEmpty())
        assertEquals(0, s.cancels)
        s.release(75f)
        assertEquals(listOf<Pair<Int?, Long>>(1 to 33_750L), s.commits)
        assertEquals(0, s.cancels)
        assertEquals(0, s.parentTaps)
    }

    @Test fun secondaryMouseButtonDoesNotPreviewOrCommitASeek() = scene { s ->
        s.event(PointerEventType.Press, 40f, secondary = true)
        s.event(PointerEventType.Release, 40f, secondary = true)
        assertTrue(s.commits.isEmpty())
        assertTrue(s.previews.isEmpty())
    }

    @Test fun unavailableDurationOrSourceDoesNotConsumeTheParentsClick() {
        for (missingSource in listOf(false, true)) scene { s ->
            if (missingSource) s.source.value = null else s.duration.longValue = 0
            s.render()
            s.press(25f)
            s.release(25f)
            assertTrue(s.commits.isEmpty())
            assertEquals(1, s.parentTaps)
        }
    }

    private class PointerScene : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> errors += error })
        private val native = MpvPlayer()
        val source = mutableStateOf<OwnedPlaybackSourceSnapshot?>(run {
            native.loadVersioned(PlaybackSource("file:///C:/synthetic-pointer-source-1.media"))
            checkNotNull(native.currentSourceSnapshot())
        })
        val duration = mutableLongStateOf(45_000L)
        val current = mutableStateOf(true)
        val commits = mutableListOf<Pair<Int?, Long>>()
        val previews = mutableListOf<Float>()
        var parentTaps = 0
        var cancels = 0
        private val scene = ImageComposeScene(100, 60, Density(1f), coroutineContext = scope.coroutineContext) {
            val identity = source.value
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { parentTaps++ })
            }) {
                Box(Modifier.fillMaxSize().desktopOfflineSeekPointerInput(identity, duration.longValue,
                    { current.value && identity != null && native.ownsSourceSnapshot(identity) },
                    { previews += it }, { commits += identity?.sourceVersion?.toInt() to it }, { cancels++ }))
            }
        }
        init { render() }
        fun render() {
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            scene.render(scheduler.currentTime * 1_000_000L).close()
            scheduler.runCurrent()
            assertTrue(errors.isEmpty(), "Unexpected Compose/pointer errors: $errors")
        }
        fun refreshSourceReceipt() {
            val prior = assertNotNull(source.value)
            val next = assertNotNull(native.currentSourceSnapshot())
            assertNotSame(prior, next)
            assertEquals(prior.sourceVersion, next.sourceVersion)
            assertEquals(prior.source, next.source)
            assertTrue(native.ownsSourceSnapshot(prior))
            source.value = next
        }
        fun replaceSource(index: Int) {
            native.loadVersioned(PlaybackSource("file:///C:/synthetic-pointer-source-$index.media"))
            source.value = assertNotNull(native.currentSourceSnapshot())
        }
        fun press(x: Float) = event(PointerEventType.Press, x)
        fun move(x: Float) = event(PointerEventType.Move, x)
        fun release(x: Float) = event(PointerEventType.Release, x)
        fun event(type: PointerEventType, x: Float, secondary: Boolean = false) {
            scheduler.advanceTimeBy(20)
            scheduler.runCurrent()
            val held = type != PointerEventType.Release
            scene.sendPointerEvent(type, Offset(x, 30f), timeMillis = scheduler.currentTime,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = held && !secondary, isSecondaryPressed = held && secondary),
                button = if (type == PointerEventType.Move) null else if (secondary) PointerButton.Secondary else PointerButton.Primary)
            render()
        }
        override fun close() {
            try { scene.close() } finally { scope.cancel(); scheduler.runCurrent(); native.close() }
            assertTrue(errors.isEmpty(), "Unexpected close errors: $errors")
        }
    }

    private fun scene(block: (PointerScene) -> Unit) { PointerScene().use(block) }
}


/** Real existing AWT host/Canvas listeners and real native source receipts.
 * No Window is created, no native runtime is attached and no media is decoded. */
class DesktopOfflineNativeInputTest {
    @Test fun nativeCanvasPrimaryReleaseRevealsAndFocusesTheSameHost() = fixture { f ->
        f.press(); assertEquals(1, f.focusRequests); assertEquals(0, f.reveals)
        f.release(); assertEquals(1, f.reveals); assertEquals(0, f.toggles)
    }
    @Test fun nativeMotionRevealsOriginalControlsWithoutTogglingPlayback() = fixture { f ->
        f.move(); assertEquals(1, f.reveals); assertEquals(0, f.toggles)
    }
    @Test fun secondaryPressNeverCapturesFocusOrAControlAction() = fixture { f ->
        f.press(java.awt.event.MouseEvent.BUTTON3); f.release(java.awt.event.MouseEvent.BUTTON3)
        assertEquals(0, f.focusRequests); assertEquals(0, f.reveals)
    }
    @Test fun replacingActualNativeSourceBetweenPressAndReleaseRejectsTheOldClick() = fixture { f ->
        f.press(); f.replaceSource(); f.release(); assertEquals(0, f.reveals)
    }
    @Test fun freshlyAllocatedEqualActualReceiptsDoNotRejectAValidClick() = fixture { f ->
        val first=assertNotNull(f.native.currentSourceSnapshot())
        f.press(); val next=assertNotNull(f.native.currentSourceSnapshot())
        assertNotSame(first,next); assertEquals(first.source,next.source)
        f.release(); assertEquals(1,f.reveals)
    }
    @Test fun unavailableOrRetiredForegroundNeverRevealsOrToggles() = fixture { f ->
        f.active=false; f.move(); f.press(); f.release()
        assertFalse(f.key(java.awt.event.KeyEvent.KEY_PRESSED).isConsumed)
        assertEquals(0,f.reveals); assertEquals(0,f.toggles); assertEquals(0,f.focusRequests)
    }
    @Test fun focusedSpaceAutoRepeatTogglesOnlyOnceUntilRelease() = fixture { f ->
        assertTrue(f.key(java.awt.event.KeyEvent.KEY_PRESSED).isConsumed)
        assertTrue(f.key(java.awt.event.KeyEvent.KEY_PRESSED).isConsumed)
        assertEquals(1,f.toggles)
        assertTrue(f.key(java.awt.event.KeyEvent.KEY_RELEASED).isConsumed)
        f.key(java.awt.event.KeyEvent.KEY_PRESSED); assertEquals(2,f.toggles)
    }
    @Test fun editorFocusAndModifiedSpaceKeepTheirKeys() = fixture { f ->
        f.focused=false
        assertFalse(f.key(java.awt.event.KeyEvent.KEY_PRESSED).isConsumed)
        f.focused=true
        assertFalse(f.key(java.awt.event.KeyEvent.KEY_PRESSED,java.awt.event.InputEvent.CTRL_DOWN_MASK).isConsumed)
        assertEquals(0,f.toggles)
    }
    @Test fun sourceRetirementAndFocusLossDropHeldInput() = fixture { f ->
        f.press(); f.loseFocus(); f.release(); assertEquals(0,f.reveals)
        f.key(java.awt.event.KeyEvent.KEY_PRESSED); f.replaceSource()
        assertFalse(f.key(java.awt.event.KeyEvent.KEY_RELEASED).isConsumed)
        assertEquals(1,f.toggles)
        f.key(java.awt.event.KeyEvent.KEY_PRESSED); assertEquals(2,f.toggles)
    }
    @Test fun disposingTheOriginalEntryDetachesAllBorrowedListenersAndRestoresFocusability() = fixture { f ->
        f.closeBridge()
        assertEquals(f.oldMouse,f.canvas.mouseListeners.size)
        assertEquals(f.oldKeys,f.canvas.keyListeners.size)
        assertEquals(f.oldFocusable,f.surface.isFocusable)
        f.press(); f.release(); f.key(java.awt.event.KeyEvent.KEY_PRESSED)
        assertEquals(0,f.reveals); assertEquals(0,f.toggles)
    }

    @Test fun renderCanvasDefersInputToTheExistingHostWithoutReplacingTheSource() = fixture { f ->
        val source = assertNotNull(f.native.currentSourceSnapshot())
        assertFalse(f.canvas.isEnabled)
        assertSame(f.surface, f.canvas.parent)
        f.hostClick()
        assertEquals(1, f.reveals)
        assertEquals(1, f.focusRequests)
        assertTrue(f.native.ownsSourceSnapshot(source))
        f.closeBridge()
        assertTrue(f.canvas.isEnabled)
    }
    @Test fun releasePreservesAnOriginallyDisabledRenderCanvas() = fixture(initialCanvasEnabled = false) { f ->
        assertFalse(f.canvas.isEnabled)
        f.closeBridge()
        assertFalse(f.canvas.isEnabled)
        f.closeBridge()
        assertFalse(f.canvas.isEnabled)
    }
    @Test fun heldSpaceCannotToggleASuccessorBeforeARealKeyRelease() = fixture { f ->
        f.key(java.awt.event.KeyEvent.KEY_PRESSED)
        assertEquals(1, f.toggles)
        f.replaceSource()
        repeat(3) { assertTrue(f.key(java.awt.event.KeyEvent.KEY_PRESSED).isConsumed) }
        assertEquals(1, f.toggles)
        f.key(java.awt.event.KeyEvent.KEY_RELEASED)
        f.key(java.awt.event.KeyEvent.KEY_PRESSED)
        assertEquals(2, f.toggles)
    }

    private class NativeFixture(initialCanvasEnabled: Boolean) : AutoCloseable {
        val native=MpvPlayer()
        val surface=native.surface
        val canvas=surface.components.filterIsInstance<java.awt.Canvas>().single().also { it.isEnabled = initialCanvasEnabled }
        val oldFocusable=surface.isFocusable
        val oldMouse=canvas.mouseListeners.size
        val oldKeys=canvas.keyListeners.size
        var active=true; var focused=true; var reveals=0; var toggles=0; var focusRequests=0
        private var index=0
        init { replaceSource() }
        private val bridge=DesktopOfflineNativeInputBridge(surface,
            {native.currentSourceSnapshot()}, native::ownsSourceSnapshot,
            {reveals++},{toggles++},{active},{focused},{focusRequests++})
        fun replaceSource() { index++; native.loadVersioned(PlaybackSource("file:///C:/synthetic-native-input-$index.media")) }
        fun press(button:Int=java.awt.event.MouseEvent.BUTTON1)=mouse(java.awt.event.MouseEvent.MOUSE_PRESSED,button)
        fun release(button:Int=java.awt.event.MouseEvent.BUTTON1)=mouse(java.awt.event.MouseEvent.MOUSE_RELEASED,button)
        private fun mouse(id:Int,button:Int) {
            val e=java.awt.event.MouseEvent(canvas,id,1L,0,20,20,1,false,button)
            canvas.mouseListeners.forEach { if(id==java.awt.event.MouseEvent.MOUSE_PRESSED)it.mousePressed(e) else it.mouseReleased(e) }
        }
        fun hostClick() {
            for (id in listOf(java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.MouseEvent.MOUSE_RELEASED)) {
                val e=java.awt.event.MouseEvent(surface,id,1L,0,20,20,1,false,java.awt.event.MouseEvent.BUTTON1)
                surface.mouseListeners.forEach { if(id==java.awt.event.MouseEvent.MOUSE_PRESSED)it.mousePressed(e) else it.mouseReleased(e) }
            }
        }
        fun move() {
            val e=java.awt.event.MouseEvent(canvas,java.awt.event.MouseEvent.MOUSE_MOVED,1L,0,30,30,0,false)
            canvas.mouseMotionListeners.forEach {it.mouseMoved(e)}
        }
        fun key(id:Int,modifiers:Int=0):java.awt.event.KeyEvent {
            val e=java.awt.event.KeyEvent(surface,id,1L,modifiers,java.awt.event.KeyEvent.VK_SPACE,' ')
            surface.keyListeners.forEach {if(id==java.awt.event.KeyEvent.KEY_PRESSED)it.keyPressed(e) else it.keyReleased(e)}
            return e
        }
        fun loseFocus() { val e=java.awt.event.FocusEvent(surface,java.awt.event.FocusEvent.FOCUS_LOST);surface.focusListeners.forEach{it.focusLost(e)} }
        fun closeBridge()=bridge.close()
        override fun close(){bridge.close();native.close()}
    }
    private fun fixture(initialCanvasEnabled: Boolean = true, block:(NativeFixture)->Unit) {
        java.awt.EventQueue.invokeAndWait { NativeFixture(initialCanvasEnabled).use(block) }
    }
}
