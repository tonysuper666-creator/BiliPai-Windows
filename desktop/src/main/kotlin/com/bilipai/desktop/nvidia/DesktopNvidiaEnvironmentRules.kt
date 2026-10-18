package com.bilipai.desktop.nvidia

import kotlin.math.abs

// Pure rules of the environment layer: game yield, battery, remote or virtual output and the display
// adapter. No I/O, no clock, no JNA: the probe reads raw facts and hands them in as plain values, so
// every rule is testable with plain numbers. An unknown input is never a pause reason. A failed read
// can only keep enhancement running, never switch it off.

// ---- Game yield: the monitor-cover input model --------------------------------------------

/** A rectangle in virtual-screen pixels as GetWindowRect and GetMonitorInfo report it (right and bottom are exclusive). */
internal data class DesktopNvidiaScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Long get() = right.toLong() - left.toLong()
    val height: Long get() = bottom.toLong() - top.toLong()
}

/**
 * A window covers a monitor when its rectangle matches the monitor rectangle edge for edge. Two pixels
 * absorb the rounding of a scaled monitor. A maximized window keeps its taskbar strip and its invisible
 * resize border, so it does not match.
 */
internal const val NVIDIA_MONITOR_COVER_TOLERANCE_PIXELS: Int = 2

/** False when either rectangle is unknown or the monitor rectangle is empty. */
internal fun desktopNvidiaCoversWholeMonitor(
    window: DesktopNvidiaScreenRect?,
    monitor: DesktopNvidiaScreenRect?,
    tolerancePixels: Int = NVIDIA_MONITOR_COVER_TOLERANCE_PIXELS,
): Boolean {
    if (window == null || monitor == null) return false
    if (monitor.width <= 0L || monitor.height <= 0L) return false
    val slack = tolerancePixels.coerceAtLeast(0).toLong()
    return abs(window.left.toLong() - monitor.left.toLong()) <= slack &&
        abs(window.top.toLong() - monitor.top.toLong()) <= slack &&
        abs(window.right.toLong() - monitor.right.toLong()) <= slack &&
        abs(window.bottom.toLong() - monitor.bottom.toLong()) <= slack
}

/** Window classes of the shell: the desktop and the taskbars. Their windows are never a game. */
internal val NVIDIA_SHELL_WINDOW_CLASSES: List<String> =
    listOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")

internal fun desktopNvidiaIsShellWindowClass(className: String?): Boolean {
    if (className == null) return false
    return NVIDIA_SHELL_WINDOW_CLASSES.any { it.equals(className, ignoreCase = true) }
}

/**
 * The raw facts one poll reads about the foreground window. Every part may be unknown (null). The
 * snapshot builder turns them into the input of the game rule: it decides "shell or desktop" and
 * "covers a whole monitor", while the process id comparison stays in desktopNvidiaGameLike.
 */
internal data class DesktopNvidiaForegroundFacts(
    val foregroundProcessId: Int?,
    /** SHQueryUserNotificationState result, or null when the call failed. */
    val notificationState: Int?,
    /** True when the window handle is the shell window or the desktop window. */
    val windowIsShellOrDesktopHandle: Boolean,
    val windowClassName: String?,
    val windowRect: DesktopNvidiaScreenRect?,
    /** Rectangle of the monitor the window is on (nearest monitor). */
    val monitorRect: DesktopNvidiaScreenRect?,
)

internal fun desktopNvidiaForegroundSnapshot(
    facts: DesktopNvidiaForegroundFacts,
    ownProcessId: Int,
): DesktopNvidiaForegroundSnapshot = DesktopNvidiaForegroundSnapshot(
    foregroundProcessId = facts.foregroundProcessId,
    ownProcessId = ownProcessId,
    notificationState = facts.notificationState,
    isShellOrDesktopWindow = facts.windowIsShellOrDesktopHandle || desktopNvidiaIsShellWindowClass(facts.windowClassName),
    coversWholeMonitor = desktopNvidiaCoversWholeMonitor(facts.windowRect, facts.monitorRect),
)

// ---- Battery ---------------------------------------------------------------------------------

/**
 * On battery means the AC line is offline (0) and a battery is present. A battery flag with bit 128
 * ("no system battery") or the unknown value 255, and an unknown AC line (255), never count.
 * The values are the raw BYTE fields of SYSTEM_POWER_STATUS; only the low eight bits are read.
 */
internal fun desktopNvidiaOnBattery(acLineStatus: Int, batteryFlag: Int): Boolean {
    if ((acLineStatus and 0xFF) != 0) return false
    return (batteryFlag and 0x80) == 0
}

// ---- Remote or virtual output ---------------------------------------------------------------

/** D3DKMDT_VIDEO_OUTPUT_TECHNOLOGY values of a remote or indirect output. */
internal object DesktopNvidiaOutputTechnology {
    const val MIRACAST: Int = 15
    const val INDIRECT_WIRED: Int = 16
    const val INDIRECT_VIRTUAL: Int = 17
}

internal fun desktopNvidiaRemoteOutputTechnology(outputTechnology: Int?): Boolean =
    outputTechnology == DesktopNvidiaOutputTechnology.MIRACAST ||
        outputTechnology == DesktopNvidiaOutputTechnology.INDIRECT_WIRED ||
        outputTechnology == DesktopNvidiaOutputTechnology.INDIRECT_VIRTUAL

private const val PCI_ENUMERATED_ADAPTER_PATH_PREFIX = "\\\\?\\PCI#"

/** True only for a known, non-empty adapter path that is not PCI enumerated. An unknown path is false. */
internal fun desktopNvidiaAdapterPathIsNonPci(adapterDevicePath: String?): Boolean {
    if (adapterDevicePath == null) return false
    val path = adapterDevicePath.trim().uppercase()
    if (path.isEmpty()) return false
    return !path.startsWith(PCI_ENUMERATED_ADAPTER_PATH_PREFIX)
}

/**
 * A remote or virtual output: an output technology of Miracast or an indirect display, or a monitor
 * adapter whose device path is not PCI enumerated (a ROOT or SWD adapter such as a remote-play display).
 */
internal fun desktopNvidiaRemoteOrVirtualOutput(outputTechnology: Int?, adapterDevicePath: String?): Boolean =
    desktopNvidiaRemoteOutputTechnology(outputTechnology) || desktopNvidiaAdapterPathIsNonPci(adapterDevicePath)

// ---- Display adapter ---------------------------------------------------------------------------

/**
 * Applies the cross-adapter step-down once the video window's monitor is known: when it belongs to
 * another adapter than the one that was selected, the tier drops one step and the CROSS_ADAPTER note is
 * added. An unknown or malformed LUID, an adapter that already carries the note, or the same adapter
 * changes nothing. The LUID comparison accepts either 32-bit half order, like the player-logged LUID check.
 */
internal fun nvidiaCapabilityForDisplayAdapter(
    capable: NvidiaCapability.Capable,
    displayAdapterLuidHex: String?,
): NvidiaCapability.Capable {
    if (NvidiaCapabilityNote.CROSS_ADAPTER in capable.notes) return capable
    if (displayAdapterLuidHex == null) return capable
    if (parseNvidiaLuidHalves(displayAdapterLuidHex) == null) return capable
    if (parseNvidiaLuidHalves(capable.gpu.luidHex) == null) return capable
    if (luidMatches(capable.gpu.luidHex, displayAdapterLuidHex)) return capable
    return capable.copy(tier = capable.tier.stepDown(), notes = capable.notes + NvidiaCapabilityNote.CROSS_ADAPTER)
}
