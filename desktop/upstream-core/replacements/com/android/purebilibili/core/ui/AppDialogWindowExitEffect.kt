// Replaces upstream design-system/src/main/java/com/android/purebilibili/core/ui/AppDialogWindowExitEffect.kt sha256=7c1cec7f055f40b60e95199fb701bbee6b72b6a5ba867002c0bcccbbe416d07b
// Android dialog-window animation and dim have no desktop counterpart.
package com.android.purebilibili.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Upstream zeroes the Android window animation of a dialog and fades its window dim with the card.
 * A desktop dialog has neither a platform window animation nor a platform dim (its scrim is composed content that
 * already follows the exit progress), so there is nothing to disable or fade.
 */
@Composable
internal fun AppDialogWindowExitEffect(progress: State<Float>) = Unit
