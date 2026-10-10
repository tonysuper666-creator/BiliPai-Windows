package com.bilipai.desktop.ui

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** Borrowed UI callback only. The registered leaf retains all page/source admission. */
internal class DesktopWindowsVideoScreenshotShortcut {
    private val action = AtomicReference<(() -> Boolean)?>(null)
    fun register(callback: () -> Boolean): AutoCloseable {
        action.set(callback)
        return AutoCloseable { action.compareAndSet(callback, null) }
    }
    fun dispatch(): Boolean = action.get()?.invoke() ?: false
}

/** Actual Windows Section pipeline. Only native frame capture is platform-specific;
 * the existing gallery actor supplies staging/cleanup and final file admission. */
internal suspend fun captureDesktopWindowsVideoScreenshot(
    stillCaptured: () -> Boolean,
    capture: suspend () -> ByteArray,
    save: suspend (ByteArray, () -> Boolean, ((() -> Unit) -> Boolean)) -> Boolean,
    admission: ((() -> Unit) -> Boolean),
): ByteArray? {
    val caller = currentCoroutineContext()
    fun checkpoint() {
        caller.ensureActive()
        if (!stillCaptured()) throw CancellationException("Screenshot page/source retired")
    }
    checkpoint()
    val bytes = capture()
    checkpoint()
    require(bytes.size in 1..32*1024*1024)
    val saved = save(bytes, { caller.isActive && stillCaptured() }) { action ->
        admission { checkpoint(); action() }
    }
    checkpoint()
    return if (saved) bytes else null
}
