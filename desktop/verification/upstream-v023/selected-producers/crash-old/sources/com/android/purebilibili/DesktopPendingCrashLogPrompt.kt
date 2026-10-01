// GENERATED from app/src/main/java/com/android/purebilibili/MainActivity.kt; do not edit.
// LF-normalized SHA-256: 9100a8fbea8e7e581f642653cca5a18429d9a1c55ee0bf3189d5a6a2fe725d90
package com.android.purebilibili
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
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
                                Text(text = "检测到上次闪退日志")
                            },
                            text = {
                                Text(
                                    text = "应用已在私有目录保存一份脱敏后的崩溃快照，不会自动上传或写入公共下载目录。现在可以主动分享给开发者排查，也可以关闭提示。"
                                )
                            },
                            confirmButton = {
                                AppDialogAction(onClick = {
                                    onAction(CrashLogPromptAction.SHARE)
                                }) { Text("分享") }
                            },
                            dismissButton = {
                                AppDialogAction(onClick = {
                                    onAction(CrashLogPromptAction.DISMISS)
                                }) { Text("关闭") }
                            }
                        )
    }
}
