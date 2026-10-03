package com.bilipai.desktop.ui

/** Borrowed publication predicate, not an independent source/session authority. */
internal class DesktopOriginalAmbientSourceLease internal constructor(private val current: () -> Boolean) {
    fun isCurrent(): Boolean = current()
}

/** Exact PNG bytes successfully saved by the one capture/gallery actor, with its original source lease. */
internal class DesktopOriginalSavedVideoScreenshot internal constructor(bytes: ByteArray, private val current: () -> Boolean) {
    private val png = bytes.copyOf()
    fun isCurrent(): Boolean = current()
    internal fun copyPngBytes(): ByteArray {
        if (!isCurrent()) throw kotlinx.coroutines.CancellationException("Saved screenshot source retired")
        return png.copyOf()
    }
}
