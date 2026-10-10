package com.bilipai.desktop.ui

/**
 * The original settings store owns validation and persistence. The menu also keeps the
 * active speed visible when it is absent from that store, matching the original menu.
 */
internal fun resolveDesktopWindowsVideoSpeedOptions(
    configuredOptions: List<Float>,
    currentSpeed: Float,
): List<Float> = (configuredOptions + currentSpeed).distinct().sortedDescending()
