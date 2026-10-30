// Transition compat (TON-1109): the migrated message feed pages call upstream's MessageConfirmDialog, which upstream
// declares in feature/message/MessageAuxiliaryPages.kt. That file still waits in upstream-core/m4d/later-steps.tsv (it
// needs the IM repository), so its dialog is declared here with upstream's body. Delete this file when
// MessageAuxiliaryPages.kt migrates: the two declarations then conflict and the build fails until it is gone.
package com.android.purebilibili.feature.message

import androidx.compose.runtime.Composable
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton

@Composable
internal fun MessageConfirmDialog(title: String, content: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppAlertDialog(onDismissRequest = onDismiss, title = { AppText(title) }, text = { AppText(content) },
        confirmButton = { AppTextButton(onClick = onConfirm) { AppText("确定") } },
        dismissButton = { AppTextButton(onClick = onDismiss) { AppText("取消") } })
}
