// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt; do not edit.
// LF-normalized SHA-256: 300346d94ff55caf2a974c87602c9e067f8ed875a0cb90b68e41643999befaf1
package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppText
@Composable
internal fun DesktopOriginalImageSavePathDialog(
    imageSaveTreeUri: String?, onDismiss: () -> Unit,
    onSelectDirectory: () -> Unit, onRestoreDefault: () -> Unit,
) {
    com.android.purebilibili.core.ui.AppAlertDialog(
        onDismissRequest = { onDismiss() },
        title = { AppText("图片保存位置", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                AppText(
                    "默认保存到系统图片目录的 BiliPai 文件夹。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppText(
                    "可通过系统文件夹选择动态图片、头像和评论图片的保存目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.android.purebilibili.core.theme.iOSOrange
                )
                if (!imageSaveTreeUri.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AppText(
                        "当前图片目录：已选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            com.android.purebilibili.core.ui.AppDialogAction(onClick = {
                onDismiss()
                onSelectDirectory()
            }) { AppText("选择图片目录") }
        },
        dismissButton = {
            com.android.purebilibili.core.ui.AppDialogAction(onClick = {
                onRestoreDefault()
                onDismiss()
            
            }) { AppText("恢复默认") }
        }
    )
}
