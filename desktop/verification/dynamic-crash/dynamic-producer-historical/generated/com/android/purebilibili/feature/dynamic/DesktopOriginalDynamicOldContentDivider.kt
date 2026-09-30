// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicScreen.kt; do not edit.
// LF-normalized SHA-256: 742b254c3555a6a7b7785c8f2c0ac6a1c260493671441151b70c07a60a566d0f
package com.android.purebilibili.feature.dynamic
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppText

@Composable
internal fun OldContentDivider(label: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacingTokens.Large, vertical = AppSpacingTokens.Small),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacingTokens.Medium, vertical = AppSpacingTokens.Small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppText(
                text = "上次刷新到这里",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            AppText(
                text = label,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
