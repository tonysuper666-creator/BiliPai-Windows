package com.bilipai.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.purebilibili.feature.video.ui.overlay.FullscreenGestureMode
import com.android.purebilibili.feature.video.ui.overlay.shouldAutoHideFullscreenControls
import com.bilipai.desktop.player.PlayerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/** Compose remember keys compare by value; the navigation entry is an identity. */
internal class DesktopWindowsFullscreenChromeEntryKey(private val entry: Any) {
    override fun equals(other: Any?): Boolean = other is DesktopWindowsFullscreenChromeEntryKey && other.entry === entry
    override fun hashCode(): Int = System.identityHashCode(entry)
}

/** Stable scalar diagnostic codes; none of them changes playback admission. */
internal enum class DesktopWindowsFullscreenChromeActivity(val code: Int) {
    UNSPECIFIED(0), GATE_CHANGED(1), WINDOW_FOCUS_GAINED(2), WINDOW_FOCUS_LOST(3),
    NATIVE_FOCUS_GAINED(4), NATIVE_FOCUS_LOST(5), NATIVE_ENTER(6), NATIVE_MOVE(7),
    NATIVE_DRAG(8), NATIVE_PRESS(9), NATIVE_KEY(10), CONTROL_HOLD_CHANGED(11),
}

/** One mounted native host's physical pointer history, not event-local coordinates.
 * A resized/repositioned heavyweight peer can emit Move without OS cursor movement. */
internal class DesktopWindowsNativePointerMotion {
    private var lastScreenPosition: java.awt.Point? = null
    val lastScreenPoint: java.awt.Point? get() = lastScreenPosition?.let { java.awt.Point(it) }

    fun observe(screenPosition: java.awt.Point?, withinSurface: Boolean = true, departureOnly: Boolean = false): Boolean {
        // A delayed Exit can arrive after the cursor has returned. Do not consume
        // that real inside movement before the pending Enter/Move observes it.
        if (departureOnly && withinSurface) return false
        if (screenPosition == null) return false
        val moved = lastScreenPosition != screenPosition
        lastScreenPosition = java.awt.Point(screenPosition)
        return moved
    }
}

/** Called only by the mounted host's input/readback callbacks; no global listener. */
internal fun desktopWindowsNativePointerScreenPosition(): java.awt.Point? =
    runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()

/** Mount-local UI timing, never a playback/source/window authority. */
internal class DesktopWindowsFullscreenChromeState(private val clockNanos: () -> Long = System::nanoTime) {
    var visible by mutableStateOf(true)
        private set
    var activityRevision by mutableLongStateOf(0L)
        private set
    private var lastActivityNanos = clockNanos()
    var lastRevealReasonCode: Int = DesktopWindowsFullscreenChromeActivity.UNSPECIFIED.code
        private set

    fun reveal(reason: DesktopWindowsFullscreenChromeActivity = DesktopWindowsFullscreenChromeActivity.UNSPECIFIED) {
        lastRevealReasonCode = reason.code
        visible = true
        lastActivityNanos = clockNanos()
        activityRevision++
    }

    fun remainingIdleMillis(): Long {
        val elapsed = (clockNanos() - lastActivityNanos).coerceAtLeast(0L)
        val remaining = (IDLE_NANOS - elapsed).coerceAtLeast(0L)
        return (remaining + 999_999L) / 1_000_000L
    }

    /** Caller supplies the final existing Root/route/full-source admission. */
    fun hideIfIdle(expectedActivityRevision: Long, stillAllowed: Boolean): Boolean {
        if (!stillAllowed || !visible || activityRevision != expectedActivityRevision || remainingIdleMillis() > 0L) return false
        visible = false
        return true
    }

    companion object { private const val IDLE_NANOS = 4_000_000_000L }
}

/** A heavyweight Canvas can own input while Compose retains its last hover/focus.
 * Native ownership may supersede only those cached flags, never a popup or scrub. */
internal data class DesktopWindowsFullscreenChromeInteraction(
    val hovered: Boolean = false,
    val focused: Boolean = false,
    val operationHeld: Boolean = false,
) {
    fun held(nativePointerOnVideo: Boolean, nativeKeyboardOnVideo: Boolean): Boolean =
        operationHeld || (hovered && !nativePointerOnVideo) || (focused && !nativeKeyboardOnVideo)
}

/** Reconcile a local Compose pointer notification with this mounted AWT host.
 * Include the heavyweight Canvas child. An unavailable host/read publishes
 * nothing, so it cannot transfer ownership on the strength of a stale event. */
internal fun desktopWindowsObserveNativePointer(
    surface: java.awt.Container, onObserved: (java.awt.Point?) -> Unit,
) {
    if (!surface.isShowing) return
    val observed = runCatching { surface.getMousePosition(true)?.takeIf(surface::contains) }
    observed.onSuccess(onObserved)
}

/** A Compose notification prompts physical pointer reconciliation by its owner. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.desktopWindowsChromePointerInput(onPointer: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Enter) { onPointer() }
        .onPointerEvent(PointerEventType.Move) { onPointer() }

/** Original fullscreen playback rule plus Windows focus/interaction holds. */
internal fun desktopWindowsFullscreenChromeCanAutoHide(
    fullscreen: Boolean, active: Boolean, windowFocused: Boolean, interactionHeld: Boolean,
    state: PlayerState, playbackProblem: Boolean,
): Boolean = fullscreen && active && windowFocused && !interactionHeld && !playbackProblem &&
    shouldAutoHideFullscreenControls(showControls = true, gestureMode = FullscreenGestureMode.None,
        isPlaying = state.ready && state.firstVideoFrameReady && state.nativePaused == false && !state.audioOnly &&
            !state.loading && !state.paused && !state.ended &&
            !state.pausedForCache && state.error == null)
