@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import java.awt.Point

/** Starts a client restart. The caller reports a restart that did not happen through
 * `onFailure`; a restart that works ends this process, so nothing is reported then. */
internal typealias DesktopRestartAction = (onFailure: (String) -> Unit) -> Unit

/** One app-owned notice for an uncaught window exception. It owns a fresh Compose
 * composition on purpose: the failing window's composition may already be dead, so
 * a dialog inside it would never render. This is still the desktop's own window,
 * not the system message box Compose's default handler opens.
 *
 * A dead composition cannot be reopened from inside, so the way back is a client
 * restart offered right here; the notice states nothing else as a way back. */
internal class DesktopWindowExceptionNotice(
    private val restartTimeoutMillis: Int = 20_000,
) : AutoCloseable {
    private val gate = Any()
    private var window: ComposeWindow? = null
    private var message: String? = null
    private var restartStatus by mutableStateOf<String?>(null)
    private var restarting by mutableStateOf(false)
    @Volatile private var restartButtonCenter: Point? = null

    /** The text of the notice currently on screen; read by the取证 fixture. */
    fun currentMessage(): String? = synchronized(gate) { message }

    /** The line under the text after a restart was requested, if any. */
    fun currentRestartStatus(): String? = restartStatus

    fun isShowing(): Boolean = synchronized(gate) { window?.isShowing == true }

    /** The notice window, for the取证 fixture to deliver real input to. */
    internal fun noticeWindow(): ComposeWindow? = synchronized(gate) { window }

    /** Center of the restart button in AWT units relative to the Compose layer, once laid out. */
    internal fun restartButtonCenter(): Point? = restartButtonCenter

    fun show(text: String, restart: DesktopRestartAction? = null) {
        val present = Runnable {
            runCatching {
                synchronized(gate) { window }?.dispose()
                restartStatus = null
                restarting = false
                restartButtonCenter = null
                val opened = ComposeWindow()
                opened.title = "BiliPai Windows"
                opened.setContent {
                    val density = LocalDensity.current
                    MaterialTheme {
                        Surface {
                            Column(Modifier.width(440.dp).padding(24.dp)) {
                                Text("客户端内部错误", style = MaterialTheme.typography.titleMedium)
                                Text(text, Modifier.padding(top = 12.dp))
                                restartStatus?.let { Text(it, Modifier.padding(top = 8.dp)) }
                                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (restart != null) Button(
                                        onClick = { requestRestart(restart) },
                                        enabled = !restarting,
                                        modifier = Modifier.onGloballyPositioned { placed ->
                                            val bounds = placed.boundsInWindow()
                                            restartButtonCenter = with(density) {
                                                Point(bounds.center.x.toDp().value.toInt(), bounds.center.y.toDp().value.toInt())
                                            }
                                        },
                                    ) { Text("重启客户端") }
                                    OutlinedButton(onClick = { opened.dispose() }) { Text("知道了") }
                                }
                            }
                        }
                    }
                }
                opened.setSize(500, 320)
                opened.setLocationRelativeTo(null)
                opened.isVisible = true
                synchronized(gate) { window = opened; message = text }
            }
        }
        if (EventQueue.isDispatchThread()) present.run() else EventQueue.invokeLater(present)
    }

    private fun requestRestart(restart: DesktopRestartAction) {
        restarting = true
        restartStatus = "正在重启客户端…"
        val failed = { reason: String ->
            restarting = false
            restartStatus = "重启未成功：$reason"
        }
        // A shutdown stuck behind the broken window must not leave a button that says
        // "restarting" forever: after the timeout the notice says it did not happen.
        val watchdog = javax.swing.Timer(restartTimeoutMillis) {
            if (restarting) failed("${restartTimeoutMillis / 1000} 秒内没有完成。请从任务栏关闭 BiliPai Windows 后重新打开。")
        }.apply { isRepeats = false; start() }
        // The action runs on the UI thread; a throw must become a visible state, not a silent dead button.
        runCatching { restart { reason -> watchdog.stop(); failed(reason) } }
            .onFailure { watchdog.stop(); failed(it.message ?: it.javaClass.simpleName) }
    }

    override fun close() {
        val dispose = Runnable { synchronized(gate) { window }?.dispose(); synchronized(gate) { window = null } }
        if (EventQueue.isDispatchThread()) dispose.run() else EventQueue.invokeLater(dispose)
    }
}
