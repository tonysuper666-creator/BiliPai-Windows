package com.bilipai.desktop.appearance

import java.util.concurrent.TimeUnit

/** Windows HotSpot's monotonic performance counter, independent of wall-clock adjustments. */
object DesktopMonotonicClock {
    fun elapsedRealtime(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
}
