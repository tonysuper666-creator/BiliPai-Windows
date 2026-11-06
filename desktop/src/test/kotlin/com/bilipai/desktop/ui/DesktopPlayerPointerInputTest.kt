package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.player.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.awt.Point
import java.awt.EventQueue
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.test.*

class DesktopPlayerPointerInputTest {
    private class Fixture : AutoCloseable {
        var source = OwnedPlaybackSourceSnapshot(1, PlaybackSource("file:///synthetic-pointer.media"))
        var active = true
        var settings = DesktopWindowsUxSettings()
        var playing = true
        var toggles = 0
        var fullscreen = false
        var fullscreenChanges = 0
        var volume = 50
        var position = 30
        val queued = mutableListOf<() -> Unit>()
        val input = DesktopPlayerPointerInput({ settings },
            { active && it.sourceVersion == source.sourceVersion && it.source == source.source },
            { playing = !playing; toggles++ }, { fullscreen = !fullscreen; fullscreenChanges++ },
            { volume = (volume + it).coerceIn(0, 100) }, { position = (position + it).coerceIn(0, 120) },
            intervalMs = 400, schedule = { _, action ->
                // Deliberately deliver canceled callbacks as well: a queued EDT
                // timer cannot command a replacement source or a disposed page.
                queued += action
                AutoCloseable { }
            })
        fun click(x: Int = 0, y: Int = 0, time: Long = 0) = input.click(source, Point(x, y), time)
        fun expire() { val copy = queued.toList(); queued.clear(); copy.forEach { it() } }
        override fun close() = input.close()
    }

    @Test fun singleClickWaitsThenTogglesExactlyOnce() = Fixture().use { f ->
        f.click(); assertTrue(f.playing); assertEquals(0, f.toggles)
        f.expire(); assertFalse(f.playing); assertEquals(1, f.toggles)
        f.expire(); assertEquals(1, f.toggles)
    }
    @Test fun doubleClickAtTheToleranceAndSystemTimeBoundaryDoesNotPause() = Fixture().use { f ->
        f.click(); f.click(6, time = 400); f.expire()
        assertTrue(f.fullscreen); assertEquals(1, f.fullscreenChanges)
        assertTrue(f.playing); assertEquals(0, f.toggles)
    }
    @Test fun aPausedDoubleClickStaysPaused() = Fixture().use { f ->
        f.playing = false; f.click(); f.click(time = 100); f.expire()
        assertFalse(f.playing); assertEquals(1, f.fullscreenChanges); assertEquals(0, f.toggles)
    }
    @Test fun twoClicksBeyondSixDpAreTwoSingles() = Fixture().use { f ->
        f.click(); f.click(7, time = 100); f.expire()
        assertEquals(2, f.toggles); assertTrue(f.playing); assertEquals(0, f.fullscreenChanges)
    }
    @Test fun diagonalDistanceAndExpiredSystemIntervalAreNotDoubleClicks() {
        for ((point, time) in listOf(Point(5, 5) to 100L, Point(0, 0) to 401L)) Fixture().use { f ->
            f.click(); f.click(point.x, point.y, time); f.expire()
            assertEquals(2, f.toggles); assertEquals(0, f.fullscreenChanges)
        }
    }
    @Test fun noneSettingsKeepPlaybackAndFullscreenUnchanged() = Fixture().use { f ->
        f.settings = f.settings.copy(clickAction = DesktopWindowsUxClickAction.NONE,
            doubleClickAction = DesktopWindowsUxDoubleClickAction.NONE)
        f.click(); f.expire(); f.click(time = 1000); f.click(time = 1100); f.expire()
        assertEquals(0, f.toggles); assertEquals(0, f.fullscreenChanges)
    }
    @Test fun eachClickSettingIsIndependent() {
        Fixture().use { f ->
            f.settings = f.settings.copy(clickAction = DesktopWindowsUxClickAction.NONE)
            f.click(); f.click(time = 100); f.expire(); assertTrue(f.fullscreen); assertTrue(f.playing)
        }
        Fixture().use { f ->
            f.settings = f.settings.copy(doubleClickAction = DesktopWindowsUxDoubleClickAction.NONE)
            f.click(); f.expire(); assertFalse(f.playing)
            f.click(time = 1000); f.click(time = 1100); f.expire()
            assertEquals(1, f.toggles); assertEquals(0, f.fullscreenChanges)
        }
    }
    @Test fun equalFreshReceiptsCanCompleteTheSameDoubleClick() = Fixture().use { f ->
        f.click(); f.source = OwnedPlaybackSourceSnapshot(f.source.sourceVersion, f.source.source)
        f.click(time = 100); f.expire(); assertEquals(1, f.fullscreenChanges); assertEquals(0, f.toggles)
    }
    @Test fun replacingTheSameFileRejectsThePredecessorsDelayedSingle() = Fixture().use { f ->
        f.click(); f.source = OwnedPlaybackSourceSnapshot(2, f.source.source); f.expire()
        assertEquals(0, f.toggles)
        f.click(time = 100); f.expire(); assertEquals(1, f.toggles)
    }
    @Test fun losingOwnershipCancelAndDisposalRejectAlreadyQueuedTimers() {
        for (mode in 0..2) Fixture().use { f ->
            f.click()
            when (mode) { 0 -> f.active = false; 1 -> f.input.cancel(); else -> f.input.close() }
            f.expire(); assertEquals(0, f.toggles)
        }
    }
    @Test fun pictureWheelUsesFiveVolumePointsOrTenSecondsPerNotch() = Fixture().use { f ->
        f.input.wheel(-2); assertEquals(40, f.volume); assertEquals(30, f.position)
        f.input.wheel(1); assertEquals(45, f.volume)
        f.settings = f.settings.copy(wheelAction = DesktopWindowsUxWheelAction.SEEK)
        f.input.wheel(2); assertEquals(50, f.position); assertEquals(45, f.volume)
        f.input.wheel(-1); assertEquals(40, f.position)
        f.settings = f.settings.copy(wheelAction = DesktopWindowsUxWheelAction.NONE)
        f.input.wheel(10); assertEquals(40, f.position); assertEquals(45, f.volume)
    }
    @Test fun nativeChildClicksWheelAndKeysReachTheSameOwnedActions() {
        EventQueue.invokeAndWait {
            Fixture().use { f ->
                val surface = java.awt.Panel().apply { add(java.awt.Canvas()) }
                val canvas = surface.components.single()
                var reveals = 0; var focus = 0; var muted = false
                DesktopOfflineNativeInputBridge(surface, { f.source }, { f.active },
                    { reveals++ }, { f.playing = !f.playing; f.toggles++ },
                    canUseSurface = { f.active }, canReceiveKey = { true }, requestNativeFocus = { focus++ },
                    pointer = f.input, onShortcut = { code -> desktopPlayerPointerShortcut(code, f.fullscreen,
                        { f.fullscreen = !f.fullscreen }, { f.position += it }, { f.volume += it }, { muted = !muted }) }
                ).use { bridge ->
                    val lease = MpvNativeWindowInputLease(f.source, 1)
                    fun native(kind: MpvNativeWindowInputKind, time: Long = 0) =
                        bridge.nativeWindowInput(MpvNativeWindowInputEvent(lease, kind, Point(20, 20), time))
                    native(MpvNativeWindowInputKind.REVEAL); f.expire(); assertEquals(1, f.toggles)
                    native(MpvNativeWindowInputKind.REVEAL, 1000); native(MpvNativeWindowInputKind.REVEAL, 1100)
                    f.expire(); assertTrue(f.fullscreen); assertEquals(1, f.toggles)
                    native(MpvNativeWindowInputKind.WHEEL_DOWN); assertEquals(45, f.volume)
                    native(MpvNativeWindowInputKind.RIGHT); assertEquals(35, f.position)
                    native(MpvNativeWindowInputKind.MUTE); assertTrue(muted)
                    native(MpvNativeWindowInputKind.ESCAPE); assertFalse(f.fullscreen)
                    native(MpvNativeWindowInputKind.TOGGLE); assertEquals(2, f.toggles)
                    assertTrue(reveals >= 8); assertEquals(3, focus)
                    canvas.dispatchEvent(MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, 0, 0,
                        20, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -2))
                    assertEquals(55, f.volume)
                    native(MpvNativeWindowInputKind.REVEAL, 2000)
                    bridge.releaseHeldInput(); f.expire(); assertEquals(2, f.toggles)
                }
                assertTrue(canvas.mouseListeners.isEmpty()); assertTrue(canvas.mouseWheelListeners.isEmpty())
            }
        }
    }
    @Test fun lockedNativeBridgeRevealsControlsButRejectsPlaybackGesturesAndKeys() {
        EventQueue.invokeAndWait {
            Fixture().use { f ->
                val surface = java.awt.Panel().apply { add(java.awt.Canvas()) }
                val canvas = surface.components.single()
                var actionsEnabled = false
                var reveals = 0
                DesktopOfflineNativeInputBridge(surface, { f.source }, { f.active },
                    { reveals++ }, { f.toggles++ }, canUseSurface = { f.active },
                    canReceiveKey = { true }, requestNativeFocus = {}, pointer = f.input,
                    actionsEnabled = { actionsEnabled },
                    onShortcut = { f.fullscreenChanges++; true }).use { bridge ->
                    fun native(kind: MpvNativeWindowInputKind, time: Long = 0) =
                        bridge.nativeWindowInput(kind, Point(20, 20), time)
                    native(MpvNativeWindowInputKind.MOTION)
                    native(MpvNativeWindowInputKind.REVEAL, 1000)
                    native(MpvNativeWindowInputKind.REVEAL, 1100)
                    assertEquals(3, reveals)
                    native(MpvNativeWindowInputKind.TOGGLE)
                    native(MpvNativeWindowInputKind.WHEEL_DOWN)
                    native(MpvNativeWindowInputKind.FULLSCREEN)
                    assertFalse(bridge.windowKeyInput(KeyEvent(canvas, KeyEvent.KEY_PRESSED, 0, 0,
                        KeyEvent.VK_SPACE, ' ')))
                    assertFalse(bridge.windowKeyInput(KeyEvent(canvas, KeyEvent.KEY_PRESSED, 0, 0,
                        KeyEvent.VK_F, 'f')))
                    canvas.mouseWheelListeners.forEach { it.mouseWheelMoved(MouseWheelEvent(canvas,
                        MouseEvent.MOUSE_WHEEL, 0, 0, 20, 20, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -2)) }
                    canvas.mouseMotionListeners.forEach { it.mouseMoved(MouseEvent(canvas,
                        MouseEvent.MOUSE_MOVED, 0, 0, 20, 20, 20, 20, 0, false, 0)) }
                    canvas.mouseListeners.forEach { it.mousePressed(MouseEvent(canvas,
                        MouseEvent.MOUSE_PRESSED, 0, 0, 20, 20, 20, 20, 1, false, MouseEvent.BUTTON1)) }
                    canvas.mouseListeners.forEach { it.mouseReleased(MouseEvent(canvas,
                        MouseEvent.MOUSE_RELEASED, 0, 0, 20, 20, 20, 20, 1, false, MouseEvent.BUTTON1)) }
                    assertEquals(5, reveals)
                    f.expire()
                    assertEquals(0, f.toggles)
                    assertEquals(0, f.fullscreenChanges)
                    assertEquals(50, f.volume)
                    canvas.mouseListeners.forEach { it.mousePressed(MouseEvent(canvas,
                        MouseEvent.MOUSE_PRESSED, 0, 0, 20, 20, 20, 20, 1, false, MouseEvent.BUTTON1)) }
                    actionsEnabled = true
                    canvas.mouseListeners.forEach { it.mouseReleased(MouseEvent(canvas,
                        MouseEvent.MOUSE_RELEASED, 0, 0, 20, 20, 20, 20, 1, false, MouseEvent.BUTTON1)) }
                    f.expire()
                    assertEquals(0, f.toggles, "A press begun while locked must not toggle after unlock")
                    native(MpvNativeWindowInputKind.REVEAL, 2000)
                    f.expire()
                    assertEquals(1, f.toggles)
                }
            }
        }
    }
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    @Test fun bangumiLockKeepsTheActualNativeInputBridgeInstalled() {
        EventQueue.invokeAndWait {
            val scheduler = TestCoroutineScheduler()
            val errors = mutableListOf<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, error -> errors += error })
            val native = MpvPlayer()
            native.loadVersioned(PlaybackSource("file:///synthetic-bangumi-pointer.media"))
            val version = checkNotNull(native.currentSourceSnapshot()).sourceVersion
            val player = DesktopOriginalMpvSectionControl(native, { version }, { true },
                { it(); true }, { false }, {}, {}, { _, _, _, _ -> }, scope,
                MutableStateFlow<Long?>(null), { it(); true })
            val platform = java.lang.reflect.Proxy.newProxyInstance(
                DesktopOriginalBangumiPlayerScreenPlatform::class.java.classLoader,
                arrayOf(DesktopOriginalBangumiPlayerScreenPlatform::class.java)
            ) { _, method, _ ->
                if (method.name == "owns") true else error("Unexpected platform call: ${method.name}")
            } as DesktopOriginalBangumiPlayerScreenPlatform
            val canvas = native.surface.components.filterIsInstance<java.awt.Canvas>().single()
            val originalListeners = canvas.mouseListeners.toSet()
            val locked = mutableStateOf(false)
            val scene = ImageComposeScene(100, 60, Density(1f), coroutineContext = scope.coroutineContext) {
                CompositionLocalProvider(LocalDesktopOriginalBangumiPlayerScreenPlatform provides platform,
                    LocalDesktopDetailForeground provides true) {
                    Box(DesktopBangumiNativePointerInput(player, enabled = !locked.value,
                        onReveal = {}, onFullscreen = {}).fillMaxSize())
                }
            }
            fun render() {
                Snapshot.sendApplyNotifications()
                scheduler.runCurrent()
                scene.render(scheduler.currentTime * 1_000_000L).close()
                scheduler.runCurrent()
                assertTrue(errors.isEmpty(), "Unexpected Compose errors: $errors")
            }
            try {
                render()
                assertEquals(originalListeners.size + 1, canvas.mouseListeners.size)
                locked.value = true
                render()
                assertEquals(originalListeners.size + 1, canvas.mouseListeners.size,
                    "Lock must keep the production native input bridge installed")
                locked.value = false
                render()
                assertEquals(originalListeners.size + 1, canvas.mouseListeners.size)
            } finally {
                scene.close(); scope.cancel(); scheduler.runCurrent(); native.close()
            }
            assertEquals(originalListeners, canvas.mouseListeners.toSet())
        }
    }
    @Test fun arrowFullscreenAndMuteCommandsRemainAvailableAfterPointerFocus() {
        var position = 30; var volume = 50; var full = false; var muted = false
        fun key(code: Int) = desktopPlayerPointerShortcut(code, full, { full = !full },
            { position += it }, { volume += it }, { muted = !muted })
        assertTrue(key(KeyEvent.VK_RIGHT)); assertEquals(35, position)
        assertTrue(key(KeyEvent.VK_LEFT)); assertEquals(30, position)
        assertTrue(key(KeyEvent.VK_UP)); assertTrue(key(KeyEvent.VK_DOWN)); assertEquals(50, volume)
        assertTrue(key(KeyEvent.VK_F)); assertTrue(full)
        assertTrue(key(KeyEvent.VK_ESCAPE)); assertFalse(full)
        assertTrue(key(KeyEvent.VK_F11)); assertTrue(full)
        assertTrue(key(KeyEvent.VK_M)); assertTrue(muted)
        assertFalse(key(KeyEvent.VK_A))
    }
}
