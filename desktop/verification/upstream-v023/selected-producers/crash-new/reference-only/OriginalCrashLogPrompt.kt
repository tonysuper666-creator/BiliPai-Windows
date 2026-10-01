                    if (
                        shouldShowPendingCrashLogPrompt(
                            hasPendingCrashSnapshot = pendingCrashSnapshotPath != null,
                            hasPromptBeenHandled = hasHandledCrashPrompt
                        )
                    ) {
                        AppAlertDialog(
                            onDismissRequest = {
                                hasHandledCrashPrompt = true
                                if (shouldClearPendingCrashLogAfterAction(CrashLogPromptAction.DISMISS)) {
                                    Logger.clearPendingCrashSnapshot(context)
                                    pendingCrashSnapshotPath = null
                                }
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
                                    hasHandledCrashPrompt = true
                                    Logger.sharePendingCrashSnapshot(context)
                                    if (shouldClearPendingCrashLogAfterAction(CrashLogPromptAction.SHARE)) {
                                        Logger.clearPendingCrashSnapshot(context)
                                        pendingCrashSnapshotPath = null
                                    }
                                }) { AppText("分享") }
                            },
                            dismissButton = {
                                AppDialogAction(onClick = {
                                    hasHandledCrashPrompt = true
                                    if (shouldClearPendingCrashLogAfterAction(CrashLogPromptAction.DISMISS)) {
                                        Logger.clearPendingCrashSnapshot(context)
                                        pendingCrashSnapshotPath = null
                                    }
                                }) { AppText("关闭") }
                            }
                        )
                    }
