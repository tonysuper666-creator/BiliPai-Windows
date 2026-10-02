package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicLong

/** Registration of the original Holder's rememberSaveable override. This is a
 * reference to its actual read/write closures, not a second mode preference or
 * state owner. Root retains this bridge with the ordinary entry; a covered or
 * disposed Holder rejects a mode command until its real registration returns. */
internal class DesktopOriginalSubtitleModeBinding(
    private val isCurrent: (VideoPlaybackViewModel) -> Boolean,
    private val admit: (VideoPlaybackViewModel, () -> Unit) -> Boolean,
) : AutoCloseable {
    private data class Registration(val token: Long, val owner: VideoPlaybackViewModel,
        val read: () -> SubtitleDisplayMode, val write: (SubtitleDisplayMode) -> Unit)
    private val sequence = AtomicLong()
    private var current: Registration? = null // Original Holder and Root controls are EDT-owned.
    private var closed = false
    private fun requireEdt() = check(EventQueue.isDispatchThread()) {
        "Original subtitle presentation belongs to the Root Window EDT"
    }
    fun register(viewModel: VideoPlaybackViewModel, read: () -> SubtitleDisplayMode,
        write: (SubtitleDisplayMode) -> Unit): AutoCloseable {
        requireEdt()
        check(!closed && isCurrent(viewModel)) { "Original subtitle Holder entry retired" }
        val registration = Registration(sequence.incrementAndGet(), viewModel, read, write)
        current = registration
        return AutoCloseable {
            requireEdt()
            if (current === registration) current = null
        }
    }
    fun set(viewModel: VideoPlaybackViewModel, value: SubtitleDisplayMode): Boolean {
        requireEdt()
        val registration = current?.takeIf { !closed && it.owner === viewModel && isCurrent(viewModel) }
            ?: return false
        var applied = false
        val accepted = admit(viewModel) {
            if (!closed && current === registration && isCurrent(viewModel)) {
                registration.write(value)
                applied = true
            }
        }
        return accepted && applied
    }
    fun read(viewModel: VideoPlaybackViewModel): SubtitleDisplayMode? {
        requireEdt()
        return current?.takeIf { !closed && it.owner === viewModel && isCurrent(viewModel) }?.read?.invoke()
    }
    override fun close() { requireEdt(); closed = true; current = null }
}

internal val LocalDesktopOriginalSubtitleModeBinding = staticCompositionLocalOf<DesktopOriginalSubtitleModeBinding> {
    error("Original Holder subtitle presentation requires its retained Root registration")
}
