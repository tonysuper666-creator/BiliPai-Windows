package com.bilipai.desktop.appearance

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.FutureTask

fun interface DesktopTextClipboard {
    /** True only after the platform clipboard accepts the requested text. */
    fun copyText(value: String): Boolean
}

object WindowsTextClipboard : DesktopTextClipboard {
    private val failure = MutableStateFlow<String?>(null)
    val lastFailure = failure.asStateFlow()
    override fun copyText(value: String): Boolean = try {
        fun copy() = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
        if (EventQueue.isDispatchThread()) copy()
        else {
            val task = FutureTask { copy() }
            EventQueue.invokeAndWait(task)
            task.get()
        }
        failure.value = null
        true
    } catch (_: Exception) {
        failure.value = "无法写入系统剪贴板，请稍后重试"
        false
    }
    fun clearFailure() { failure.value = null }
}

val LocalDesktopTextClipboard = staticCompositionLocalOf<DesktopTextClipboard> { WindowsTextClipboard }
