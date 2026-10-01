// GENERATED from app/src/main/java/com/android/purebilibili/MainActivity.kt; do not edit.
// LF-normalized SHA-256: ddfdafed5f2eb7ad153894dd253e3cf079cfae4e5d670a8365ba6e9fb6b4d33a
package com.android.purebilibili
import androidx.compose.runtime.Composable
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppDialogAction
@Composable
internal fun DesktopPendingCrashLogPrompt(hasPendingCrashSnapshot:Boolean,hasPromptBeenHandled:Boolean,onAction:(CrashLogPromptAction)->Unit) {
    if(shouldShowPendingCrashLogPrompt(hasPendingCrashSnapshot,hasPromptBeenHandled)) {
AppAlertDialog(
                            onDismissRequest = {
                                onAction(CrashLogPromptAction.DISMISS)
                            },
                            title = {
                                AppText(text = "检测到上次闪退日志")
                            },
                            text = {
                                AppText(
                                    text = "应用已在私有目录保存一份脱敏后的崩溃快照，不会自动上传或写入公共下载目录。现在可以主动分享给开发者排查，也可以关闭提示。"
                                )
                            },
                            confirmButton = {
                                AppDialogAction(onClick = {
                                    onAction(CrashLogPromptAction.SHARE)
                                }) { AppText("分享") }
                            },
                            dismissButton = {
                                AppDialogAction(onClick = {
                                    onAction(CrashLogPromptAction.DISMISS)
                                }) { AppText("关闭") }
                            }
                        )
    }
}
