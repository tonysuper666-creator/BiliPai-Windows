// GENERATED from app/src/main/java/com/android/purebilibili/core/ui/common/Modifiers.kt; do not edit.
// LF-normalized SHA-256: ffb0a934822dc39744a56ec031875e813f8e2a055ed857f5dfc1db4e1e5a5fa2
package com.android.purebilibili.core.ui.common
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.bilipai.desktop.ui.LocalDesktopCommentBindings
@Composable
fun rememberClipboardCopyHandler(): (String, String?) -> Unit {
    val context = LocalDesktopCommentBindings.current
    val haptic = LocalHapticFeedback.current
    return remember(context, haptic) {
        { rawText: String, label: String? ->
            val text = rawText.trim()
            if (text.isNotEmpty()) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (context.isOwned()) context.copyText(text, label ?: "BiliPai")
                val toastMsg = if (label != null) "已复制 $label" else "已复制到剪贴板"
                if (context.isOwned()) context.showFeedback(toastMsg)
            }
        }
    }
}
