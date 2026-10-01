package com.android.purebilibili.core.ui.common
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings
fun Modifier.copyOnLongPress(
    text: String,
    label: String? = null,
    longPressTimeoutMillis: Long? = null,
): Modifier = this

fun Modifier.copyOnClick(
    text: String,
    label: String? = null
): Modifier = composed {
    val copyToClipboard = rememberVideoInfoClipboardCopyHandler()
    if (text.isBlank()) return@composed this

    clickable {
        copyToClipboard(text, label)
    }
}
@Composable
fun rememberVideoInfoClipboardCopyHandler(): (String, String?) -> Unit {
    val context = LocalDesktopOriginalVideoInfoBindings.current
    val haptic = LocalHapticFeedback.current
    return remember(context, haptic) {
        { rawText: String, label: String? ->
            val text = rawText.trim()
            if (text.isNotEmpty()) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                context.copyText(text, label ?: "BiliPai")
                val toastMsg = if (label != null) "已复制 $label" else "已复制到剪贴板"
                context.showFeedback(toastMsg)
            }
        }
    }
}
