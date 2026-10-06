package com.android.purebilibili.feature.video.share

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.AdaptiveLoadingIndicator
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppDialogAction
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextField
import com.android.purebilibili.data.repository.VideoDynamicShareRepository
import kotlinx.coroutines.launch

@Composable
internal fun VideoShareToDynamicDialog(
    payload: VideoSharePayload,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(payload.bvid) { mutableStateOf("") }
    var posting by remember(payload.bvid) { mutableStateOf(false) }
    var error by remember(payload.bvid) { mutableStateOf<String?>(null) }

    AppAlertDialog(
        onDismissRequest = { if (!posting) onDismiss() },
        title = { AppText("分享到动态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = payload.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                    AppText(
                        text = payload.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AppTextField(
                    value = text,
                    onValueChange = { if (!posting) { text = it.take(2000); error = null } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "说点什么吧…（可选）",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 5,
                    isError = error != null,
                    supportingText = {
                        AppText(error ?: "${text.length}/2000", color = if (error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        })
                    },
                )
            }
        },
        dismissButton = {
            AppDialogAction(onClick = { if (!posting) onDismiss() }) { AppText("取消") }
        },
        confirmButton = {
            AppDialogAction(onClick = {
                if (!posting) {
                    posting = true
                    error = null
                    scope.launch {
                        try {
                            VideoDynamicShareRepository.share(payload.bvid, text).fold(
                                onSuccess = {
                                    VideoShareFeedbackEvents.prepared(payload.bvid)
                                    Toast.makeText(context, "已分享到动态", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                },
                                onFailure = { error = it.message ?: "分享失败，请重试" },
                            )
                        } finally {
                            posting = false
                        }
                    }
                }
            }) {
                if (posting) AdaptiveLoadingIndicator(size = 20.dp) else AppText("发布")
            }
        },
    )
}
