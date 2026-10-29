@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import java.awt.EventQueue

/** One app-owned notice for an uncaught window exception. It owns a fresh Compose
 * composition on purpose: the failing window's composition may already be dead, so
 * a dialog inside it would never render. This is still the desktop's own window,
 * not the system message box Compose's default handler opens. */
internal class DesktopWindowExceptionNotice : AutoCloseable {
    private val gate = Any()
    private var window: ComposeWindow? = null
    private var message: String? = null

    /** The text of the notice currently on screen; read by the取证 fixture. */
    fun currentMessage(): String? = synchronized(gate) { message }

    fun isShowing(): Boolean = synchronized(gate) { window?.isShowing == true }

    fun show(text: String) {
        val present = Runnable {
            runCatching {
                synchronized(gate) { window }?.dispose()
                val opened = ComposeWindow()
                opened.title = "BiliPai Windows"
                opened.setContent {
                    MaterialTheme {
                        Surface {
                            Column(Modifier.width(440.dp).padding(24.dp)) {
                                Text("客户端内部错误", style = MaterialTheme.typography.titleMedium)
                                Text(text, Modifier.padding(top = 12.dp))
                                Button(onClick = { opened.dispose() }, Modifier.padding(top = 20.dp)) {
                                    Text("知道了")
                                }
                            }
                        }
                    }
                }
                opened.setSize(500, 260)
                opened.setLocationRelativeTo(null)
                opened.isVisible = true
                synchronized(gate) { window = opened; message = text }
            }
        }
        if (EventQueue.isDispatchThread()) present.run() else EventQueue.invokeLater(present)
    }

    override fun close() {
        val dispose = Runnable { synchronized(gate) { window }?.dispose(); synchronized(gate) { window = null } }
        if (EventQueue.isDispatchThread()) dispose.run() else EventQueue.invokeLater(dispose)
    }
}
