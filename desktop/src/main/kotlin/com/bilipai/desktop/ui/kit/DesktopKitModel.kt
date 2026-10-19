package com.bilipai.desktop.ui.kit

import kotlin.math.round

/*
 * Pure logic of the component kit: no Compose, AWT or Windows dependency. Mirrored by desktop/tools/ux/ux_kit_model.py
 * and checked against the "kit" tables of desktop/tools/ux/desktop-ux-contract.json by DesktopKitModelTest and
 * test_ux_kit_model.py. The composables only read these results.
 */

/** Disabled content alpha (the Material convention). Not a contract number. */
internal const val DESKTOP_KIT_DISABLED_ALPHA_PERCENT = 38

/** A focus gained within this many milliseconds after a pointer press on the same control is pointer focus. */
internal const val DESKTOP_KIT_POINTER_FOCUS_WINDOW_MS = 400L

/** Stands for "no pointer press yet": far enough in the past that no focus time is inside the window. */
internal const val DESKTOP_KIT_NEVER_PRESSED_MS = Long.MIN_VALUE / 2

/**
 * One physical pixel in dp at a density. A 1 dp line blurs at 1.5625, 1.875 and every 5 percent zoom step, while
 * this height times the density is 1 px again (layout rounds it to exactly one pixel).
 */
internal fun desktopKitHairlineDp(density: Float): Float = if (density > 0f) 1f / density else 1f

/** A stroke width in px snapped to a whole pixel, never below one pixel. kotlin.math.round ties to even. */
internal fun desktopKitSnapStrokePx(px: Float): Float = round(px).coerceAtLeast(1f)

/** The onSurface wash over a control, in percent. Pressed wins over hover, and a disabled control has none. */
internal fun desktopKitWashPercent(enabled: Boolean, hovered: Boolean, pressed: Boolean): Int = when {
    !enabled -> 0
    pressed -> DESKTOP_KIT_PRESSED_PERCENT
    hovered -> DESKTOP_KIT_HOVER_PERCENT
    else -> 0
}

/** Animation length: none under reduced motion (the contract says motion_reduced is none). */
internal fun desktopKitMotionMs(reducedMotion: Boolean, durationMs: Int): Int = if (reducedMotion) 0 else durationMs

/**
 * The next enabled segment when current moves by delta, wrapping at both ends. current is -1 when nothing is selected,
 * so a step forward lands on the first enabled segment and a step back on the last. Returns current when there is
 * nowhere else to go.
 */
internal fun desktopKitSegmentStep(enabled: List<Boolean>, current: Int, delta: Int): Int {
    val count = enabled.size
    if (count == 0 || delta == 0) return current
    var index = current
    repeat(count) {
        index = if (index < 0) {
            if (delta > 0) 0 else count - 1
        } else {
            ((index + delta) % count + count) % count
        }
        if (enabled[index]) return index
    }
    return current
}

/** The first (last = false) or last (last = true) enabled segment, or -1 when none is enabled. */
internal fun desktopKitSegmentEdge(enabled: List<Boolean>, last: Boolean): Int =
    if (last) enabled.indexOfLast { it } else enabled.indexOfFirst { it }

/**
 * Keyboard-only focus ring. The ring shows while the control is focused and the focus did not come from the pointer.
 * Focus counts as pointer focus when a press on the control happened just before it (either order of the two events
 * works), and a navigation key (Tab, arrows, Space, Enter, Home, End, Page keys) makes the ring visible again.
 */
internal data class DesktopKitFocusState(
    val focused: Boolean = false,
    val pointerDriven: Boolean = false,
    val lastPressMs: Long = DESKTOP_KIT_NEVER_PRESSED_MS,
) {
    val ringVisible: Boolean get() = focused && !pointerDriven

    fun onFocus(nowMs: Long): DesktopKitFocusState =
        copy(focused = true, pointerDriven = (nowMs - lastPressMs) in 0L..DESKTOP_KIT_POINTER_FOCUS_WINDOW_MS)

    fun onUnfocus(): DesktopKitFocusState = copy(focused = false, pointerDriven = false)

    fun onPointerPress(nowMs: Long): DesktopKitFocusState = copy(pointerDriven = pointerDriven || focused, lastPressMs = nowMs)

    fun onNavigationKey(): DesktopKitFocusState = copy(pointerDriven = false)
}
