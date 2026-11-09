package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A window token belongs to one opening of the editor, never to a later opening. */
internal class DesktopProfileFeedback(private val pageFeedback: (String) -> Unit) {
    internal class Window
    internal class Operation(val window: Window)
    internal data class Notice(val window: Window, val message: String, val sequence: Long)

    private var current: Window? = null
    private var currentOperation: Operation? = null
    private var sequence = 0L
    internal var notice by mutableStateOf<Notice?>(null)
        private set

    fun open(): Window = Window().also { current = it; currentOperation = null; notice = null }

    fun close(window: Window) {
        if (current === window) { current = null; currentOperation = null; notice = null }
    }

    fun begin(window: Window): Operation = Operation(window).also {
        if (current === window) { currentOperation = it; notice = null }
    }

    fun show(window: Window, message: String) {
        if (current === window) notice = Notice(window, message, ++sequence)
    }

    fun route(origin: Any?, message: String) {
        when (origin) {
            is Operation -> if (currentOperation === origin) show(origin.window, message)
            is Window -> show(origin, message)
            else -> pageFeedback(message)
        }
    }

    fun dismiss(expected: Notice) {
        if (notice == expected) notice = null
    }
}

/** Drawn in the editor's dialog window, after its content, so it is above the editor. */
@Composable
internal fun DesktopProfileFeedbackHost(feedback: DesktopProfileFeedback, content: @Composable () -> Unit) {
    val notice = feedback.notice
    LaunchedEffect(notice) {
        if (notice != null) { delay(4_000); feedback.dismiss(notice) }
    }
    Box(Modifier.fillMaxWidth()) {
        content()
        if (notice != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 6.dp,
            ) { Text(notice.message, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        }
    }
}
