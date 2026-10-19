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

/* Menus and popovers (slice X2b). All values are pixels of the window. */

/** Where a menu opens: at the pointer, or under its anchor control with the start or the end edges aligned. */
internal enum class DesktopKitMenuPlacement { AT_POINT, BELOW_START, BELOW_END }

internal data class DesktopKitMenuPosition(val x: Int, val y: Int)

/**
 * Flip, then clamp, along one axis. The preferred start wins when the menu fits there between low and high, otherwise
 * the alternative wins when it fits, otherwise the preferred start is clamped into the free range. A menu longer than
 * the free range starts at low. Never throws.
 */
private fun desktopKitFitAxis(preferred: Int, alternative: Int, size: Int, low: Int, high: Int): Int {
    val preferredFits = preferred >= low && preferred + size <= high
    val alternativeFits = alternative >= low && alternative + size <= high
    val chosen = if (preferredFits || !alternativeFits) preferred else alternative
    return chosen.coerceIn(low, maxOf(low, high - size))
}

/**
 * The top-left corner of a menu inside the window. The anchor is a rectangle (a pointer is a point: right and bottom
 * equal left and top and are not read). The gap is both the margin kept to the window edges and, for the placements
 * under a control, the distance to that control. AT_POINT opens right and down of the pointer and flips to its left
 * and above; BELOW_START and BELOW_END open under the control and flip to above it, and swap the aligned edge.
 */
internal fun desktopKitPlaceMenu(
    placement: DesktopKitMenuPlacement,
    anchorLeft: Int, anchorTop: Int, anchorRight: Int, anchorBottom: Int,
    menuWidth: Int, menuHeight: Int,
    windowWidth: Int, windowHeight: Int,
    gap: Int,
): DesktopKitMenuPosition {
    val atPoint = placement == DesktopKitMenuPlacement.AT_POINT
    val x = when (placement) {
        DesktopKitMenuPlacement.AT_POINT ->
            desktopKitFitAxis(anchorLeft, anchorLeft - menuWidth, menuWidth, gap, windowWidth - gap)
        DesktopKitMenuPlacement.BELOW_START ->
            desktopKitFitAxis(anchorLeft, anchorRight - menuWidth, menuWidth, gap, windowWidth - gap)
        DesktopKitMenuPlacement.BELOW_END ->
            desktopKitFitAxis(anchorRight - menuWidth, anchorLeft, menuWidth, gap, windowWidth - gap)
    }
    val y = if (atPoint) {
        desktopKitFitAxis(anchorTop, anchorTop - menuHeight, menuHeight, gap, windowHeight - gap)
    } else {
        desktopKitFitAxis(anchorBottom + gap, anchorTop - gap - menuHeight, menuHeight, gap, windowHeight - gap)
    }
    return DesktopKitMenuPosition(x, y)
}

/** The tallest a menu may be: the window height less the gap above and below, and never less than one pixel. */
internal fun desktopKitMenuMaxHeightPx(windowHeight: Int, gap: Int): Int = (windowHeight - 2 * gap).coerceAtLeast(1)

/* Overlay scrollbar (slice X2b). */

/** Thumb alpha while idle and while hovered or dragged, in percent of onSurface (the two private copies used 28 and 60). */
internal const val DESKTOP_KIT_SCROLLBAR_IDLE_ALPHA_PERCENT = 28
internal const val DESKTOP_KIT_SCROLLBAR_HOVER_ALPHA_PERCENT = 60

/** The scrollbar shows when the content is more than one pixel taller than the viewport (estimates never flicker it). */
internal fun desktopKitScrollbarNeeded(contentSize: Double, viewportSize: Double): Boolean = contentSize - viewportSize > 1.0

/** The visible thumb width: 8 dp snapped to whole pixels, never wider than the strip it sits in. */
internal fun desktopKitScrollbarThumbPx(density: Float, stripPx: Float): Float =
    minOf(desktopKitSnapStrokePx(DESKTOP_KIT_SCROLLBAR_THUMB_DP * density), stripPx)

/** The space left of the thumb in the strip: half of the free width, in whole pixels, never negative. */
internal fun desktopKitScrollbarThumbInsetPx(stripPx: Float, thumbPx: Float): Float = round((stripPx - thumbPx) / 2f).coerceAtLeast(0f)

/* Toast (slice X2b). */

/** How long a toast stays: the plain time, or the longer one when it carries an action. */
internal fun desktopKitToastDurationMs(hasAction: Boolean): Long =
    (if (hasAction) DESKTOP_KIT_TOAST_ACTION_MS else DESKTOP_KIT_TOAST_PLAIN_MS).toLong()

internal data class DesktopKitToastEntry(val id: Long, val message: String, val actionLabel: String?, val durationMs: Long)

/** One toast at a time. The callbacks of an action stay with the host, so the state is plain data. */
internal data class DesktopKitToastState(val current: DesktopKitToastEntry? = null, val lastId: Long = 0L) {
    /** A new toast replaces the shown one. Blank text is ignored and takes no id; a blank action label means no action. */
    fun show(message: String, actionLabel: String? = null): DesktopKitToastState {
        val text = message.trim()
        if (text.isEmpty()) return this
        val label = actionLabel?.trim()?.takeIf { it.isNotEmpty() }
        val id = lastId + 1
        return DesktopKitToastState(DesktopKitToastEntry(id, text, label, desktopKitToastDurationMs(label != null)), id)
    }

    /** Only the toast shown now can be dismissed, so the timer of a replaced toast never closes its successor. */
    fun dismiss(id: Long): DesktopKitToastState = if (current?.id == id) copy(current = null) else this
}
