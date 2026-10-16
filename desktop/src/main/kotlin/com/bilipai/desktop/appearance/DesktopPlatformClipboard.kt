package com.bilipai.desktop.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

// A new Compose dialog scene replaces platform locals. Keep the Window-owned
// boundary in a separate inherited local and restore it inside the modal host.
internal val LocalDesktopPlatformClipboard = staticCompositionLocalOf<Clipboard?> { null }

@Composable
internal fun DesktopPlatformClipboardScope(content: @Composable () -> Unit) {
    val clipboard = LocalDesktopPlatformClipboard.current
    if (clipboard == null) content()
    else CompositionLocalProvider(LocalClipboard provides clipboard, content = content)
}

/** A transient clipboard read must not cancel the Window's Compose scope. */
internal class DesktopPlatformClipboard(
    private val delegate: Clipboard,
    private val isActive: () -> Boolean = { true },
    private val onFailure: (String) -> Unit = WindowsTextClipboard::reportFailure,
    private val pause: suspend () -> Unit = { delay(25) },
) : Clipboard {
    // Preserve the platform write contract. Legacy Compose cut mutates its
    // editor before this call, so suppressing a failed write cannot restore it.
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = delegate.setClipEntry(clipEntry)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard get() = delegate.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? {
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            if (!isActive()) throw CancellationException("Clipboard owner retired")
            try {
                return delegate.getClipEntry()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                if (attempt < 2) pause()
            }
        }
        currentCoroutineContext().ensureActive()
        if (!isActive()) throw CancellationException("Clipboard owner retired")
        onFailure("剪贴板暂被占用，粘贴未完成，请稍后重试")
        return null
    }
}
