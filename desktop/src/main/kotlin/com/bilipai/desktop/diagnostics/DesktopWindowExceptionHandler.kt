@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory

/** Compose's default factory answers an uncaught window exception with a system
 * message box that shows the raw message and needs a click before the player
 * continues. The desktop records the failure and keeps one notice inside its own
 * window instead. Nothing is rethrown, so the window keeps composing exactly as
 * it did under the default handler; a fatal failure still ends the process. */
internal class DesktopWindowExceptionHandler(
    private val record: (Throwable) -> Unit,
    private val notify: (String) -> Unit,
) : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler =
        object : WindowExceptionHandler {
            override fun onException(failure: Throwable) = handle(failure)
        }

    /** The whole reaction, so it is exercised without an AWT window. */
    fun handle(failure: Throwable) {
        runCatching { record(failure) }
        runCatching { notify(unexpectedMessage(failure)) }
    }

    companion object {
        /** Stable user-facing text only; no exception text, URL or account detail. */
        fun unexpectedMessage(failure: Throwable): String =
            "界面遇到一个内部错误（${failure.javaClass.simpleName}），已记录到诊断日志。" +
                "回到首页重新打开即可继续使用；反复出现请在设置里导出日志。"
    }
}
