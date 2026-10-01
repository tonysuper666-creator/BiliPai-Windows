// GENERATED from app/src/main/java/com/android/purebilibili/MainActivity.kt; do not edit.
// LF-normalized SHA-256: ddfdafed5f2eb7ad153894dd253e3cf079cfae4e5d670a8365ba6e9fb6b4d33a
package com.android.purebilibili
internal enum class CrashLogPromptAction {
    SHARE,
    DISMISS,
    IGNORE
}

internal fun shouldShowPendingCrashLogPrompt(
    hasPendingCrashSnapshot: Boolean,
    hasPromptBeenHandled: Boolean
): Boolean = hasPendingCrashSnapshot && !hasPromptBeenHandled

internal fun shouldClearPendingCrashLogAfterAction(
    action: CrashLogPromptAction
): Boolean = action != CrashLogPromptAction.IGNORE
