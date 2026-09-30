package com.android.purebilibili.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Delegate to the actual platform feedback service; standard Windows mice have no haptic motor. */
@Composable
fun rememberHapticFeedback():(HapticType)->Unit {
    val feedback=LocalHapticFeedback.current
    return remember(feedback){{type -> feedback.performHapticFeedback(
        if(type==HapticType.SELECTION || type==HapticType.LIGHT) HapticFeedbackType.TextHandleMove
        else HapticFeedbackType.LongPress)}}
}
