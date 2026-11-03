package com.bilipai.desktop.appearance

import androidx.compose.runtime.staticCompositionLocalOf
import com.bilipai.desktop.platform.share.AwtDesktopShare
import com.bilipai.desktop.platform.share.DesktopShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface DesktopTextClipboard {
    /** True only after the platform clipboard accepts the requested text. */
    fun copyText(value: String): Boolean
}

internal class ReportingDesktopTextClipboard(
    private val share: DesktopShare,
    private val report: (String?) -> Unit,
) : DesktopTextClipboard {
    override fun copyText(value: String): Boolean = try {
        share.copyLink(value)
        report(null)
        true
    } catch (_: Exception) {
        report("无法写入系统剪贴板，请稍后重试")
        false
    }
}

object WindowsTextClipboard : DesktopTextClipboard {
    private val failure = MutableStateFlow<String?>(null)
    val lastFailure = failure.asStateFlow()
    private val delegate = ReportingDesktopTextClipboard(AwtDesktopShare()) { failure.value = it }
    override fun copyText(value: String): Boolean = delegate.copyText(value)
    internal fun reportFailure(message: String) { failure.value = message }
    fun clearFailure() { failure.value = null }
}

val LocalDesktopTextClipboard = staticCompositionLocalOf<DesktopTextClipboard> { WindowsTextClipboard }
