package com.bilipai.desktop.appearance

/*
 * Pure model of the window bounds restore (spec 3.5, slice W3, hunk RH8). No Compose, AWT or Windows dependency.
 *
 * Units. Every number is in the units java.awt.Window reports (AWT user space). They equal the Compose dp of the
 * window state, because Compose writes window.width as Dp, and they are NOT scaled by the app zoom: a 5120x2160
 * panel at Windows scale 150% is 3413x1440 units, and the window opens 1360x900 units wide at any app zoom.
 *
 * What it decides.
 *   * planDesktopWindowsUxWindow: the size, the position and the maximised state the window opens with, from the
 *     saved bounds, the monitors that exist now and the app zoom. The result is a pure function of its inputs.
 *   * DesktopWindowsUxWindowTracker: which bounds to save, from samples of the window state. A maximised or
 *     fullscreen window reports the size of the monitor, so the rectangle that a restore needs is the last
 *     floating one; the maximised state is kept beside it.
 *
 * The numbers between the UX-WINDOW-BOUNDS markers are mirrored by the section window_bounds of
 * desktop/tools/ux/desktop-ux-contract.json. The Python parity suite and DesktopWindowsUxWindowBoundsModelTest
 * check that they agree and run the golden rows of the contract through this code.
 */

// UX-WINDOW-BOUNDS-BEGIN
internal const val WINDOWS_UX_WINDOW_DEFAULT_WIDTH = 1360
internal const val WINDOWS_UX_WINDOW_DEFAULT_HEIGHT = 900
internal const val WINDOWS_UX_WINDOW_MIN_BASE_WIDTH = 960
internal const val WINDOWS_UX_WINDOW_MIN_BASE_HEIGHT = 680
internal const val WINDOWS_UX_WINDOW_EDGE_TOLERANCE = 16
internal const val WINDOWS_UX_WINDOW_SETTLE_MS = 400L
// UX-WINDOW-BOUNDS-END

/** A rectangle in AWT user space units. The origin may be negative (a monitor left of or above the primary one). */
internal data class DesktopWindowsUxRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/** One monitor: the area a window may occupy (the monitor bounds minus the taskbar and other reserved bars). */
internal data class DesktopWindowsUxScreen(val usable: DesktopWindowsUxRect, val primary: Boolean = false)

internal enum class DesktopWindowsUxPlanKind {
    /** Nothing usable was saved: the default size, the platform picks the position. */
    DEFAULT,

    /** Bounds were saved but no monitor is known: the saved size, the platform picks the position. */
    UNVALIDATED,

    /** The saved rectangle is on a monitor and needed no change. */
    KEPT,

    /** The saved rectangle overlaps a monitor and was moved or shrunk to fit it. */
    ADJUSTED,

    /** The saved rectangle is on no monitor now (one was unplugged): centred on the primary monitor. */
    RELOCATED,
}

/** What the window opens with. x and y are null together: the platform chooses the position. */
internal data class DesktopWindowsUxWindowPlan(
    val width: Int,
    val height: Int,
    val x: Int?,
    val y: Int?,
    val maximized: Boolean,
    val kind: DesktopWindowsUxPlanKind,
    /** Index into the list of monitors that was passed in, or null when no monitor was involved. */
    val screenIndex: Int?,
) {
    /** The bounds the window will have once it is shown, or null while the platform chooses the position. */
    fun bounds(): DesktopWindowsUxWindowBounds? {
        val left = x
        val top = y
        return if (left != null && top != null) DesktopWindowsUxWindowBounds(left, top, width, height, maximized) else null
    }
}

/** The plan and what the tracker starts from. */
internal data class DesktopWindowsUxStartup(
    val plan: DesktopWindowsUxWindowPlan,
    /** The bounds the window will have, or the saved bounds while the position is left to the platform, or null. */
    val seed: DesktopWindowsUxWindowBounds?,
)

/** The smallest window extent for an app zoom: the base extent times the zoom percent, rounded half up. */
internal fun desktopWindowsUxMinimumExtent(base: Int, zoomPercent: Int): Int =
    (base * zoomPercent.coerceIn(WINDOWS_DISPLAY_MIN_PERCENT, WINDOWS_DISPLAY_MAX_PERCENT) + 50) / 100

/** The area of the overlap of two rectangles, zero when they only touch or do not meet. */
internal fun desktopWindowsUxOverlapArea(a: DesktopWindowsUxRect, b: DesktopWindowsUxRect): Long {
    val width = minOf(a.right, b.right).toLong() - maxOf(a.x, b.x)
    val height = minOf(a.bottom, b.bottom).toLong() - maxOf(a.y, b.y)
    return if (width > 0L && height > 0L) width * height else 0L
}

/**
 * The window the saved bounds ask for, made to fit the monitors that exist now.
 *
 *  1. Nothing saved, or bounds that are not sane: the default size and the platform position (DEFAULT).
 *  2. The size is at least the minimum for the zoom, and never larger than the monitor it lands on.
 *  3. The monitor is the one that shares the largest area with the saved rectangle; on a tie the first one listed.
 *  4. A rectangle that shares nothing with any monitor (one was unplugged) is centred on the primary monitor, or on
 *     the first monitor when none is marked primary (RELOCATED).
 *  5. Otherwise the position is the nearest one that keeps the window on the monitor: the title bar is never above
 *     the monitor, and the sides and the bottom may overhang by the edge tolerance, because Windows frames carry
 *     invisible borders and a window snapped to a monitor edge reports a few units outside it.
 *  6. With no monitor information at all the saved size is used and the position is left to the platform (UNVALIDATED).
 * The maximised state is carried through unchanged: the rectangle is the one the window returns to.
 */
internal fun planDesktopWindowsUxWindow(
    saved: DesktopWindowsUxWindowBounds?,
    screens: List<DesktopWindowsUxScreen>,
    zoomPercent: Int,
): DesktopWindowsUxWindowPlan {
    if (saved == null || !saved.isSane()) {
        return DesktopWindowsUxWindowPlan(
            width = WINDOWS_UX_WINDOW_DEFAULT_WIDTH, height = WINDOWS_UX_WINDOW_DEFAULT_HEIGHT, x = null, y = null,
            maximized = false, kind = DesktopWindowsUxPlanKind.DEFAULT, screenIndex = null)
    }
    val minWidth = desktopWindowsUxMinimumExtent(WINDOWS_UX_WINDOW_MIN_BASE_WIDTH, zoomPercent)
    val minHeight = desktopWindowsUxMinimumExtent(WINDOWS_UX_WINDOW_MIN_BASE_HEIGHT, zoomPercent)
    val candidates = screens.withIndex().filter { it.value.usable.width > 0 && it.value.usable.height > 0 }
    if (candidates.isEmpty()) {
        return DesktopWindowsUxWindowPlan(
            width = maxOf(saved.width, minWidth), height = maxOf(saved.height, minHeight), x = null, y = null,
            maximized = saved.maximized, kind = DesktopWindowsUxPlanKind.UNVALIDATED, screenIndex = null)
    }
    val savedRect = DesktopWindowsUxRect(saved.x, saved.y, saved.width, saved.height)
    var best: IndexedValue<DesktopWindowsUxScreen>? = null
    var bestArea = 0L
    for (candidate in candidates) {
        val area = desktopWindowsUxOverlapArea(savedRect, candidate.value.usable)
        if (area > bestArea) {
            best = candidate
            bestArea = area
        }
    }
    val vanished = best == null
    val target = best ?: candidates.firstOrNull { it.value.primary } ?: candidates.first()
    val usable = target.value.usable
    val width = minOf(maxOf(saved.width, minWidth), usable.width)
    val height = minOf(maxOf(saved.height, minHeight), usable.height)
    val edge = WINDOWS_UX_WINDOW_EDGE_TOLERANCE
    val x: Int
    val y: Int
    if (vanished) {
        x = usable.x + (usable.width - width) / 2
        y = usable.y + (usable.height - height) / 2
    } else {
        x = minOf(maxOf(saved.x, usable.x - edge), usable.right + edge - width)
        y = minOf(maxOf(saved.y, usable.y), usable.bottom + edge - height)
    }
    val kind = when {
        vanished -> DesktopWindowsUxPlanKind.RELOCATED
        x == saved.x && y == saved.y && width == saved.width && height == saved.height -> DesktopWindowsUxPlanKind.KEPT
        else -> DesktopWindowsUxPlanKind.ADJUSTED
    }
    return DesktopWindowsUxWindowPlan(
        width = width, height = height, x = x, y = y, maximized = saved.maximized, kind = kind, screenIndex = target.index)
}

/** The plan for the saved bounds together with the starting point of the tracker. */
internal fun desktopWindowsUxStartup(
    saved: DesktopWindowsUxWindowBounds?,
    screens: List<DesktopWindowsUxScreen>,
    zoomPercent: Int,
): DesktopWindowsUxStartup {
    val plan = planDesktopWindowsUxWindow(saved, screens, zoomPercent)
    val fromPlan = plan.bounds()
    val seed = if (fromPlan != null) fromPlan else if (plan.kind == DesktopWindowsUxPlanKind.UNVALIDATED) saved else null
    return DesktopWindowsUxStartup(plan, seed)
}

internal enum class DesktopWindowsUxPlacement { FLOATING, MAXIMIZED, FULLSCREEN }

/** One reading of the window state, in AWT units. */
internal data class DesktopWindowsUxWindowSample(
    val placement: DesktopWindowsUxPlacement,
    val minimized: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Keeps the bounds worth saving. Samples are accepted in order.
 *  * A minimised window is ignored: Windows moves it far off screen.
 *  * A fullscreen window is ignored: the next start must not open fullscreen, and the earlier state stays.
 *  * A floating sample with sane extents replaces the rectangle and clears the maximised flag.
 *  * A maximised sample sets the flag and keeps the floating rectangle. Only when no floating rectangle was ever
 *    seen does the (monitor sized) maximised rectangle stand in for it.
 */
internal class DesktopWindowsUxWindowTracker(seed: DesktopWindowsUxWindowBounds?) {
    private var floating: DesktopWindowsUxRect? = null
    private var maximized: Boolean = false

    init {
        if (seed != null && seed.isSane()) {
            floating = DesktopWindowsUxRect(seed.x, seed.y, seed.width, seed.height)
            maximized = seed.maximized
        }
    }

    fun accept(sample: DesktopWindowsUxWindowSample) {
        if (sample.minimized) return
        val sane = DesktopWindowsUxWindowBounds(sample.x, sample.y, sample.width, sample.height).isSane()
        if (sample.placement == DesktopWindowsUxPlacement.FLOATING) {
            if (sane) {
                floating = DesktopWindowsUxRect(sample.x, sample.y, sample.width, sample.height)
                maximized = false
            }
        } else if (sample.placement == DesktopWindowsUxPlacement.MAXIMIZED) {
            maximized = true
            if (floating == null && sane) floating = DesktopWindowsUxRect(sample.x, sample.y, sample.width, sample.height)
        }
    }

    /** The bounds to save, or null while nothing sane is known. */
    fun bounds(): DesktopWindowsUxWindowBounds? {
        val rect = floating ?: return null
        val bounds = DesktopWindowsUxWindowBounds(rect.x, rect.y, rect.width, rect.height, maximized)
        return if (bounds.isSane()) bounds else null
    }
}
