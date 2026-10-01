// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentInputBar.kt; do not edit.
// LF-normalized SHA-256: 97c0a524fb6df7685dda7efa45ba10cb7a39d8456f4dfffa90e978d25a8403ea
package com.android.purebilibili.feature.video.ui.components
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
@Composable
fun ReportReasonDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onReport: (Int) -> Unit
) {
    if (!visible) return

    val reasons = listOf(
        1 to "垃圾广告",
        2 to "色情",
        3 to "刷屏",
        4 to "引战",
        5 to "剧透",
        7 to "人身攻击",
        8 to "内容不相关",
        0 to "其他"
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { AppText("举报原因", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                reasons.forEach { (code, label) ->
                    AppTextButton(
                        onClick = { onReport(code) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AppText(
                            text = label,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            AppTextButton(onClick = onDismiss) {
                AppText("取消")
            }
        }
    )
}
