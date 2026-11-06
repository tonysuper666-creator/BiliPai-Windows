package com.bilipai.desktop.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.MpvRuntimeProbe
import com.bilipai.desktop.player.MpvRuntimeProbeStage
import com.android.purebilibili.feature.download.resolveOfflineSeekPositionFromTouch
import com.android.purebilibili.feature.download.resolveOfflineSeekProgressFromTouch

/** One owner for click and drag. Two competing recognizers can cancel a mouse
 * click before the original timeline commits it. Mapping stays in the complete
 * original offline policy; source admission remains in the existing player. */
internal suspend fun PointerInputScope.detectDesktopOfflineSeekGestures(
    durationMs: Long,
    isCurrent: () -> Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    if (durationMs <= 0L) return
    awaitEachGesture {
        val down = awaitFirstDown()
        if (!isCurrent() || !down.position.x.isFinite() ||
            down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        var committed = false
        down.consume()
        try {
            onPreview(resolveOfflineSeekProgressFromTouch(down.position.x, size.width.toFloat()))
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed || !isCurrent() || !change.position.x.isFinite()) return@awaitEachGesture
                if (change.type == PointerType.Mouse && change.pressed && !event.buttons.isPrimaryPressed) return@awaitEachGesture
                change.consume()
                onPreview(resolveOfflineSeekProgressFromTouch(change.position.x, size.width.toFloat()))
                if (!change.pressed) {
                    if (!isCurrent()) return@awaitEachGesture
                    onCommit(resolveOfflineSeekPositionFromTouch(change.position.x, size.width.toFloat(), durationMs))
                    committed = true
                    return@awaitEachGesture
                }
            }
        } finally {
            if (!committed) onCancel()
        }
    }
}

/** Rekey on the captured native source, not on changing progress/UI callbacks.
 * A source replacement cancels an in-flight gesture instead of seeking its successor. */
@Composable
internal fun Modifier.desktopOfflineSeekPointerInput(
    sourceIdentity: OwnedPlaybackSourceSnapshot?,
    durationMs: Long,
    isCurrent: () -> Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Long) -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val latestCurrent = rememberUpdatedState(isCurrent)
    val latestPreview = rememberUpdatedState(onPreview)
    val latestCommit = rememberUpdatedState(onCommit)
    val latestCancel = rememberUpdatedState(onCancel)
    // Reads allocate a fresh receipt wrapper even while the same media stays
    // loaded. Key by its complete immutable value, retaining final source admission.
    val sourceKey = sourceIdentity?.let { it.sourceVersion to it.source }
    return pointerInput(sourceKey, durationMs) {
        if (sourceKey != null) detectDesktopOfflineSeekGestures(durationMs,
            { latestCurrent.value() }, { latestPreview.value(it) },
            { latestCommit.value(it) }, { latestCancel.value() })
    }
}


/** Native Canvas input can land below the transparent Compose foreground.
 * Like the ordinary video leaf, listen only on this one existing host/Canvas;
 * original controls retain their own pointer handlers and native source admission. */
internal class DesktopOfflineNativeInputBridge(
    private val surface: java.awt.Container,
    private val currentSource: () -> OwnedPlaybackSourceSnapshot?,
    private val ownsSource: (OwnedPlaybackSourceSnapshot) -> Boolean,
    private val onRevealControls: () -> Unit,
    private val onTogglePlayback: () -> Unit,
    private val canUseSurface: () -> Boolean = {
        val owner = javax.swing.SwingUtilities.getWindowAncestor(surface)
        surface.isShowing && owner != null &&
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === owner
    },
    private val canReceiveKey: () -> Boolean = {
        val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        focus === surface || focus != null && javax.swing.SwingUtilities.isDescendingFrom(focus, surface)
    },
    private val requestNativeFocus: () -> Unit = { surface.requestFocusInWindow(); Unit },
    private val pointer: DesktopPlayerPointerInput? = null,
    private val pointerTolerance: () -> Double = { 6.0 },
    private val onShortcut: (Int) -> Boolean = { false },
    private val handleAwtKeys: Boolean = true,
    private val actionsEnabled: () -> Boolean = { true },
) : AutoCloseable {
    private val canvas = surface.components.filterIsInstance<java.awt.Canvas>().single()
    private val oldFocusable = surface.isFocusable
    private var closed = false
    private var press: OwnedPlaybackSourceSnapshot? = null
    private var pressPoint: java.awt.Point? = null
    private var pressAllowsAction = false
    private val heldKeys = mutableSetOf<Int>()
    private var heldSpace: OwnedPlaybackSourceSnapshot? = null
    private fun current(): OwnedPlaybackSourceSnapshot? =
        if (closed || !canUseSurface()) null else currentSource()?.takeIf(ownsSource)
    private fun matches(receipt: OwnedPlaybackSourceSnapshot): Boolean {
        val now = current() ?: return false
        return now.sourceVersion == receipt.sourceVersion && now.source == receipt.source && ownsSource(receipt)
    }
    /** MPV owns a real child HWND during playback; its input does not bubble to
     * AWT Canvas listeners. Called on the EDT after the same native publication
     * has admitted this exact load. The original screen still owns all actions.
     */
    internal fun nativeWindowInput(event: com.bilipai.desktop.player.MpvNativeWindowInputEvent) =
        nativeWindowInput(event.kind, event.point, event.timeMs)
    internal fun nativeWindowInput(
        kind: com.bilipai.desktop.player.MpvNativeWindowInputKind,
        point: java.awt.Point? = null,
        timeMs: Long = System.nanoTime() / 1_000_000,
    ) {
        check(java.awt.EventQueue.isDispatchThread())
        val captured = current() ?: return
        if (!actionsEnabled()) {
            releaseHeldInput()
            if (kind == com.bilipai.desktop.player.MpvNativeWindowInputKind.MOTION ||
                kind == com.bilipai.desktop.player.MpvNativeWindowInputKind.REVEAL) {
                if (kind == com.bilipai.desktop.player.MpvNativeWindowInputKind.REVEAL) requestNativeFocus()
                onRevealControls()
            }
            return
        }
        if (kind == com.bilipai.desktop.player.MpvNativeWindowInputKind.REVEAL) requestNativeFocus()
        onRevealControls()
        if (kind == com.bilipai.desktop.player.MpvNativeWindowInputKind.TOGGLE && matches(captured)) onTogglePlayback()
        if (!matches(captured)) return
        when (kind) {
            com.bilipai.desktop.player.MpvNativeWindowInputKind.REVEAL ->
                if (point != null) pointer?.click(captured, point, timeMs)
            com.bilipai.desktop.player.MpvNativeWindowInputKind.WHEEL_UP -> pointer?.wheel(1)
            com.bilipai.desktop.player.MpvNativeWindowInputKind.WHEEL_DOWN -> pointer?.wheel(-1)
            else -> if (kind.keyCode != 0) onShortcut(kind.keyCode)
        }
    }
    /** The actual Root Window offers preview first, retaining the unhandled
     * callback as a fallback. This offline page has no inline text editor; its
     * focusable menu dialogs fail current()'s active-window admission. Consumed
     * text-input events and modified keys still keep their original behavior. */
    internal fun windowKeyInput(event: java.awt.event.KeyEvent): Boolean {
        if (!actionsEnabled()) { releaseHeldInput(); return false }
        // A consumed key-up cannot command playback, but it still ends this
        // physical press. Otherwise an editor/IME-consumed release leaves the
        // repeat guard latched and suppresses the next real Space press.
        if (event.id == java.awt.event.KeyEvent.KEY_RELEASED && event.keyCode == java.awt.event.KeyEvent.VK_SPACE) {
            val alreadyConsumed = event.isConsumed
            keyReleased(event, false)
            return !alreadyConsumed && event.isConsumed
        }
        if (event.isConsumed) return false
        if (event.id == java.awt.event.KeyEvent.KEY_PRESSED) keyPressed(event, false)
        else if (event.id == java.awt.event.KeyEvent.KEY_RELEASED) keyReleased(event, false)
        return event.isConsumed
    }
    /** Real Window events contain Compose's InternalKeyEvent wrapper. Use the
     * public metadata rather than casting the wrapper to an AWT event. This
     * offline page has no inline editor; focusable menus fail current(). */
    internal fun windowKeyInput(event: KeyEvent): Boolean {
        check(java.awt.EventQueue.isDispatchThread())
        if (!actionsEnabled()) { releaseHeldInput(); return false }
        if (event.key != Key.Spacebar) {
            val code = when (event.key) {
                Key.DirectionLeft -> java.awt.event.KeyEvent.VK_LEFT
                Key.DirectionRight -> java.awt.event.KeyEvent.VK_RIGHT
                Key.DirectionUp -> java.awt.event.KeyEvent.VK_UP
                Key.DirectionDown -> java.awt.event.KeyEvent.VK_DOWN
                Key.F -> java.awt.event.KeyEvent.VK_F
                Key.M -> java.awt.event.KeyEvent.VK_M
                Key.F11 -> java.awt.event.KeyEvent.VK_F11
                Key.Escape -> java.awt.event.KeyEvent.VK_ESCAPE
                else -> return false
            }
            if (event.type == KeyEventType.KeyUp) { heldKeys.remove(code); return false }
            if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed ||
                event.isMetaPressed || event.isShiftPressed || current() == null) return false
            if (code in heldKeys) return true
            if (!onShortcut(code)) return false
            heldKeys += code
            return true
        }
        if (event.key != Key.Spacebar) return false
        MpvRuntimeProbe.record(MpvRuntimeProbeStage.WINDOW_SPACE,
            if (event.type == KeyEventType.KeyDown) 401L else if (event.type == KeyEventType.KeyUp) 402L else 0L)
        if (event.type == KeyEventType.KeyUp) return releaseSpace(false)
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed ||
            event.isMetaPressed || event.isShiftPressed) return false
        return pressSpace()
    }
    private fun pressSpace(): Boolean {
        val captured = current() ?: return false
        // Suppress repeats until release, also across native source replacement.
        if (heldSpace != null) return true
        heldSpace = captured
        onRevealControls()
        if (matches(captured)) onTogglePlayback()
        return true
    }
    private fun releaseSpace(requireSurfaceFocus: Boolean): Boolean {
        val captured = heldSpace; heldSpace = null
        return captured != null && (!requireSurfaceFocus || canReceiveKey()) && matches(captured)
    }
    private fun keyPressed(event: java.awt.event.KeyEvent, requireSurfaceFocus: Boolean) {
        if (!actionsEnabled()) { releaseHeldInput(); return }
        if (event.keyCode != java.awt.event.KeyEvent.VK_SPACE) {
            if (!event.isConsumed && !event.isControlDown && !event.isAltDown && !event.isMetaDown &&
                !event.isShiftDown && (!requireSurfaceFocus || canReceiveKey()) && current() != null &&
                (event.keyCode in heldKeys || onShortcut(event.keyCode))) {
                heldKeys += event.keyCode; event.consume()
            }
            return
        }
        if (event.isConsumed || event.keyCode != java.awt.event.KeyEvent.VK_SPACE ||
            event.isControlDown || event.isAltDown || event.isMetaDown || event.isShiftDown ||
            requireSurfaceFocus && !canReceiveKey()) return
        MpvRuntimeProbe.record(if (requireSurfaceFocus) MpvRuntimeProbeStage.AWT_SPACE else MpvRuntimeProbeStage.WINDOW_SPACE)
        if (pressSpace()) event.consume()
    }
    private fun keyReleased(event: java.awt.event.KeyEvent, requireSurfaceFocus: Boolean) {
        heldKeys.remove(event.keyCode)
        if (event.keyCode != java.awt.event.KeyEvent.VK_SPACE) return
        if (releaseSpace(requireSurfaceFocus)) event.consume()
    }
    private val mouse = object : java.awt.event.MouseAdapter() {
        override fun mouseEntered(event: java.awt.event.MouseEvent) { reveal() }
        override fun mouseMoved(event: java.awt.event.MouseEvent) { reveal() }
        private fun reveal() { if (current() != null) onRevealControls() }
        override fun mousePressed(event: java.awt.event.MouseEvent) {
            MpvRuntimeProbe.record(MpvRuntimeProbeStage.AWT_MOUSE, event.button.toLong(), 1)
            press = null
            if (event.isConsumed || event.button != java.awt.event.MouseEvent.BUTTON1 ||
                event.isControlDown || event.isAltDown || event.isMetaDown) return
            press = current() ?: return
            pressPoint = event.locationOnScreen
            pressAllowsAction = actionsEnabled()
            requestNativeFocus()
        }
        override fun mouseReleased(event: java.awt.event.MouseEvent) {
            MpvRuntimeProbe.record(MpvRuntimeProbeStage.AWT_MOUSE, event.button.toLong(), 0)
            val captured = press; press = null
            if (!event.isConsumed && event.button == java.awt.event.MouseEvent.BUTTON1 &&
                captured != null && matches(captured)) {
                onRevealControls()
                val origin = pressPoint
                if (pressAllowsAction && actionsEnabled() && origin != null && origin.distance(event.locationOnScreen) <= pointerTolerance())
                    pointer?.click(captured, event.locationOnScreen, System.nanoTime() / 1_000_000)
                else if (!pressAllowsAction || !actionsEnabled()) pointer?.cancel()
            }
            pressPoint = null; pressAllowsAction = false
        }
        override fun mouseWheelMoved(event: java.awt.event.MouseWheelEvent) {
            if (!actionsEnabled()) { releaseHeldInput(); return }
            if (!event.isConsumed && !event.isControlDown && !event.isAltDown && !event.isMetaDown && current() != null) {
                onRevealControls()
                pointer?.wheel(-event.wheelRotation)
                if (pointer != null) event.consume()
            }
        }
    }
    internal fun releaseHeldInput() {
        check(java.awt.EventQueue.isDispatchThread())
        press = null; pressPoint = null; pressAllowsAction = false; heldSpace = null; heldKeys.clear(); pointer?.cancel()
    }
    private val focus = object : java.awt.event.FocusAdapter() {
        override fun focusLost(event: java.awt.event.FocusEvent) { releaseHeldInput() }
    }
    private val key = object : java.awt.event.KeyAdapter() {
        override fun keyPressed(event: java.awt.event.KeyEvent) {
            keyPressed(event, true)
        }
        override fun keyReleased(event: java.awt.event.KeyEvent) {
            keyReleased(event, true)
        }
    }
    init {
        check(java.awt.EventQueue.isDispatchThread())
        if (handleAwtKeys) surface.isFocusable = true
        // Preserve the render Canvas input state. Its real MPV child forwards
        // native input through the existing client event loop; AWT remains the
        // fallback when that child has unloaded, including the EOF controls.
        for (component in listOf(surface, canvas)) {
            component.addMouseListener(mouse); component.addMouseMotionListener(mouse)
            component.addMouseWheelListener(mouse)
            if (handleAwtKeys) component.addKeyListener(key)
            component.addFocusListener(focus)
        }
    }
    override fun close() {
        check(java.awt.EventQueue.isDispatchThread())
        if (closed) return
        closed = true; press = null; heldSpace = null
        pointer?.cancel(); heldKeys.clear()
        for (component in listOf(surface, canvas)) {
            component.removeMouseListener(mouse); component.removeMouseMotionListener(mouse)
            component.removeMouseWheelListener(mouse)
            component.removeKeyListener(key); component.removeFocusListener(focus)
        }
        if (handleAwtKeys) surface.isFocusable = oldFocusable
    }
}

/** Same Root native host and the original screen's actual state; no new player,
 * Store, window, system-wide hook or alternate playback policy. */
@Composable
internal fun DesktopOfflineNativeInput(
    player: DesktopOfflineMpvControl,
    enabled: Boolean,
    onRevealControls: () -> Unit,
    onTogglePlayback: () -> Unit,
): Modifier {
    val native = player.nativePlayer
    val bindings = LocalDesktopOriginalOfflineBindings.current
    fun seek(delta: Int) {
        val target = (player.currentPosition + delta * 1000L).coerceAtLeast(0L)
        player.seekTo(if (player.duration > 0) target.coerceAtMost(player.duration) else target)
    }
    fun volume(delta: Int) {
        val target = (player.volumePercent + delta).coerceIn(0, 100)
        player.volumePercent = target
        bindings?.feedback?.invoke("音量 $target%")
    }
    fun fullscreen() { bindings?.window?.let { it.applyFullscreen(!it.fullscreen) } }
    return DesktopNativePlaybackPointerInput(native,
        enabled = { enabled && player.isOwned() },
        onReveal = { MpvRuntimeProbe.record(MpvRuntimeProbeStage.ORIGINAL_REVEAL); onRevealControls() },
        onToggle = onTogglePlayback, onFullscreen = ::fullscreen,
        onVolume = ::volume, onSeek = ::seek,
        onShortcut = { code -> desktopPlayerPointerShortcut(code, bindings?.window?.fullscreen == true,
            ::fullscreen, ::seek, ::volume, { native.toggleMuted() }) },
        handleWindowKeys = true)
}

/** All playback surfaces use one click arbiter. Delay a single click until the
 * OS double-click interval expires, so a double click never pauses playback.
 * Timers retain the exact native source receipt, never a future player load. */
internal class DesktopPlayerPointerInput(
    private val settings: () -> com.bilipai.desktop.appearance.DesktopWindowsUxSettings,
    private val owns: (OwnedPlaybackSourceSnapshot) -> Boolean,
    private val togglePlayback: () -> Unit,
    private val toggleFullscreen: () -> Unit,
    private val volume: (Int) -> Unit,
    private val seek: (Int) -> Unit,
    private val intervalMs: Int = (java.awt.Toolkit.getDefaultToolkit()
        .getDesktopProperty("awt.multiClickInterval") as? Number)?.toInt()?.coerceAtLeast(1) ?: 500,
    private val tolerance: () -> Double = { 6.0 },
    private val schedule: (Int, () -> Unit) -> AutoCloseable = { delay, action ->
        val timer = javax.swing.Timer(delay) { action() }.apply { isRepeats = false; start() }
        AutoCloseable { timer.stop() }
    },
) : AutoCloseable {
    private data class Click(val source: OwnedPlaybackSourceSnapshot, val point: java.awt.Point, val time: Long)
    private var pending: Click? = null
    private var timer: AutoCloseable? = null
    private var closed = false
    fun click(source: OwnedPlaybackSourceSnapshot, point: java.awt.Point, timeMs: Long) {
        if (closed || !owns(source)) return
        val previous = pending
        cancel()
        if (previous != null && previous.source.sourceVersion == source.sourceVersion &&
            previous.source.source == source.source && owns(previous.source) &&
            timeMs - previous.time in 0..intervalMs.toLong() &&
            previous.point.distance(point) <= tolerance()) {
            if (settings().doubleClickAction == com.bilipai.desktop.appearance.DesktopWindowsUxDoubleClickAction.FULLSCREEN)
                toggleFullscreen()
            return
        }
        if (previous != null) single(previous)
        val click = Click(source, java.awt.Point(point), timeMs)
        pending = click
        timer = schedule(intervalMs) {
            if (!closed && pending === click) {
                pending = null; timer = null
                single(click)
            }
        }
    }
    private fun single(click: Click) {
        if (!closed && owns(click.source) &&
            settings().clickAction == com.bilipai.desktop.appearance.DesktopWindowsUxClickAction.PAUSE) togglePlayback()
    }
    fun wheel(notches: Int) {
        if (closed || notches == 0) return
        when (settings().wheelAction) {
            com.bilipai.desktop.appearance.DesktopWindowsUxWheelAction.VOLUME -> volume(notches * 5)
            com.bilipai.desktop.appearance.DesktopWindowsUxWheelAction.SEEK -> seek(notches * 10)
            com.bilipai.desktop.appearance.DesktopWindowsUxWheelAction.NONE -> Unit
        }
    }
    fun cancel() { timer?.close(); timer = null; pending = null }
    override fun close() { cancel(); closed = true }
}

/** One registration per presented native player, shared by video, PGC and offline. */
@Composable
internal fun DesktopNativePlaybackPointerInput(
    native: com.bilipai.desktop.player.MpvPlayer,
    enabled: () -> Boolean,
    onReveal: () -> Unit,
    onToggle: () -> Unit,
    onFullscreen: () -> Unit,
    onVolume: (Int) -> Unit,
    onSeek: (Int) -> Unit,
    onShortcut: (Int) -> Boolean,
    handleAwtKeys: Boolean = true,
    handleWindowKeys: Boolean = false,
    actionsEnabled: () -> Boolean = { true },
): Modifier {
    val window = LocalDesktopWindowsPlayerWindow.current
    val ux = com.bilipai.desktop.appearance.LocalDesktopWindowsUx.current
    val settings = ux.settings.collectAsState()
    val latestEnabled = rememberUpdatedState(enabled)
    val latestActionsEnabled = rememberUpdatedState(actionsEnabled)
    val latestReveal = rememberUpdatedState(onReveal)
    val latestToggle = rememberUpdatedState(onToggle)
    val latestFullscreen = rememberUpdatedState(onFullscreen)
    val latestVolume = rememberUpdatedState(onVolume)
    val latestSeek = rememberUpdatedState(onSeek)
    val latestShortcut = rememberUpdatedState(onShortcut)
    val density = rememberUpdatedState(androidx.compose.ui.platform.LocalDensity.current.density)
    val fallback = LocalDesktopWindowKeyFallback.current
    val presented = enabled()
    val actionsActive = actionsEnabled()
    fun owns(source: OwnedPlaybackSourceSnapshot) = latestEnabled.value() && native.ownsSourceSnapshot(source)
    fun ownsAction(source: OwnedPlaybackSourceSnapshot) = latestActionsEnabled.value() && owns(source)
    fun tolerance() = 6.0 * density.value / (window?.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0)
    val pointer = remember(native, window, fallback, handleAwtKeys, handleWindowKeys, presented, actionsActive) {
        DesktopPlayerPointerInput({ settings.value }, ::ownsAction,
            { latestToggle.value() }, { latestFullscreen.value() },
            { latestVolume.value(it) }, { latestSeek.value(it) }, tolerance = ::tolerance)
    }
    androidx.compose.runtime.DisposableEffect(native, window, fallback, handleAwtKeys, handleWindowKeys, presented, actionsActive) {
        if (!presented) return@DisposableEffect onDispose { pointer.close() }
        val input = DesktopOfflineNativeInputBridge(native.surface,
            { if (latestEnabled.value()) native.currentSourceSnapshot() else null }, ::owns,
            { latestReveal.value() }, { latestToggle.value() },
            pointer = pointer, pointerTolerance = ::tolerance,
            onShortcut = { latestShortcut.value(it) }, handleAwtKeys = handleAwtKeys,
            actionsEnabled = { latestActionsEnabled.value() })
        val registration = native.observeNativeWindowInput { event ->
            native.admitNativeWindowInput(event) { if (latestEnabled.value()) input.nativeWindowInput(event) }
        }
        val keys = if (handleWindowKeys) fallback?.registerLeaf { input.windowKeyInput(it) } else null
        val focus = object : java.awt.event.WindowAdapter() {
            override fun windowLostFocus(event: java.awt.event.WindowEvent) { input.releaseHeldInput() }
        }
        window?.addWindowFocusListener(focus)
        onDispose {
            window?.removeWindowFocusListener(focus)
            keys?.close(); registration.close(); input.close(); pointer.close()
        }
    }
    // Original buttons and menus consume first. Only the empty picture area
    // reaches this owner; the native child uses the very same click arbiter.
    return Modifier.pointerInput(pointer, presented) {
        if (!presented) return@pointerInput
        awaitPointerEventScope {
            var pressed: OwnedPlaybackSourceSnapshot? = null
            var origin: java.awt.Point? = null
            while (true) {
                val event = awaitPointerEvent()
                if (!latestEnabled.value()) { pressed = null; pointer.cancel(); continue }
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) { pressed = null; continue }
                val source = native.currentSourceSnapshot()?.takeIf(::owns) ?: continue
                val point = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Move, PointerEventType.Enter -> latestReveal.value()
                    PointerEventType.Press -> if (event.buttons.isPrimaryPressed) {
                        pressed = source; origin = point
                        native.surface.requestFocusInWindow(); latestReveal.value(); change.consume()
                    }
                    PointerEventType.Release -> {
                        val captured = pressed; pressed = null
                        if (captured != null && owns(captured) && origin?.distance(point)?.let { it <= tolerance() } == true) {
                            latestReveal.value()
                            if (latestActionsEnabled.value()) pointer.click(captured, point, System.nanoTime() / 1_000_000)
                            else pointer.cancel()
                            change.consume()
                        }
                    }
                    PointerEventType.Scroll -> {
                        if (!latestActionsEnabled.value()) { pointer.cancel(); continue }
                        val notches = kotlin.math.round(-change.scrollDelta.y).toInt()
                        if (notches != 0) { latestReveal.value(); pointer.wheel(notches); change.consume() }
                    }
                }
            }
        }
    }
}

/** Native child HWND keys use the same owned callbacks as pointer actions. */
internal fun desktopPlayerPointerShortcut(
    code: Int, fullscreen: Boolean, toggleFullscreen: () -> Unit,
    seek: (Int) -> Unit, volume: (Int) -> Unit, mute: () -> Unit,
): Boolean {
    when (code) {
        java.awt.event.KeyEvent.VK_LEFT -> seek(-5)
        java.awt.event.KeyEvent.VK_RIGHT -> seek(5)
        java.awt.event.KeyEvent.VK_UP -> volume(5)
        java.awt.event.KeyEvent.VK_DOWN -> volume(-5)
        java.awt.event.KeyEvent.VK_F, java.awt.event.KeyEvent.VK_F11 -> toggleFullscreen()
        java.awt.event.KeyEvent.VK_ESCAPE -> if (fullscreen) toggleFullscreen() else return false
        java.awt.event.KeyEvent.VK_M -> mute()
        else -> return false
    }
    return true
}
