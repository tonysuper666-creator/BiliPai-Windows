package com.android.purebilibili.feature.message.feed
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppButton
@Composable
internal fun MessageFeedError(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppText(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            AppText("重试")
        }
    }
}
