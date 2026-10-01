// GENERATED from app/src/main/java/com/android/purebilibili/MainActivity.kt; do not edit.
// LF-normalized SHA-256: 9100a8fbea8e7e581f642653cca5a18429d9a1c55ee0bf3189d5a6a2fe725d90
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
