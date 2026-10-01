package com.bilipai.desktop.ui

import coil3.ImageLoader
import com.android.purebilibili.core.lifecycle.BACKGROUND_IMAGE_TRIM_DELAY_MS
import com.android.purebilibili.core.lifecycle.resolveBackgroundImageCacheTrimTargetBytes
import com.android.purebilibili.core.lifecycle.shouldTrimImageCacheAfterBackgroundDelay
import com.android.purebilibili.feature.home.components.cards.VideoCardCoverColorStore
import com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Real Window background events replace Android BackgroundManager/Handler only.
 * The complete original 45-second/24-to-8 MiB policies remain the decision authority.
 * The actual main/floating/audio players are not stopped or trimmed by this adapter.
 */
internal class DesktopApplicationImageCacheTrim(
    private val imageLoader: ImageLoader,
    private val background: DesktopHomeWindowBackgroundPort,
    private val appScope: CoroutineScope,
    private val wallpaperStore: WallpaperPaletteStore,
    private val isPipActiveOrPending: () -> Boolean,
    private val canTrim: () -> Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var delayed: Job? = null
    private var enteredAtNanos = 0L
    private val listener = object : DesktopHomeWindowBackgroundPort.Listener {
        override fun onEnterBackground() { onEdt {
            if (closed.get()) return@onEdt
            enteredAtNanos = System.nanoTime()
            delayed?.cancel()
            if (canTrim() && !isPipActiveOrPending()) trimMemory(0)
            val capturedStart = enteredAtNanos
            delayed = appScope.launch {
                check(EventQueue.isDispatchThread()) { "Application cache trim requires the actual Window scope" }
                delay(BACKGROUND_IMAGE_TRIM_DELAY_MS)
                if (closed.get() || enteredAtNanos != capturedStart || !canTrim()) return@launch
                val elapsed = (System.nanoTime() - capturedStart) / 1_000_000L
                if (shouldTrimImageCacheAfterBackgroundDelay(background.isInBackground,
                        isPipActiveOrPending(), elapsed)) {
                    trimMemory(elapsed)
                    VideoCardCoverColorStore.trimToSize(16)
                    // Clear historical palettes only; the active original palette remains.
                    wallpaperStore.clearCache()
                }
            }
        } }
        override fun onEnterForeground() { onEdt { delayed?.cancel(); delayed = null } }
    }

    init {
        check(EventQueue.isDispatchThread())
        background.addListener(listener)
        if (background.isInBackground) listener.onEnterBackground()
    }

    private fun trimMemory(elapsed: Long) {
        imageLoader.memoryCache?.apply { trimToSize(resolveBackgroundImageCacheTrimTargetBytes(size, elapsed)) }
    }
    private fun onEdt(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) action() else EventQueue.invokeLater(action)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        background.removeListener(listener)
        onEdt { delayed?.cancel(); delayed = null }
    }
}
