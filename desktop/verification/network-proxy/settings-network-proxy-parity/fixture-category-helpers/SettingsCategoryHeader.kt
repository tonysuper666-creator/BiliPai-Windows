// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt; do not edit.
// LF-normalized SHA-256: 300346d94ff55caf2a974c87602c9e067f8ed875a0cb90b68e41643999befaf1
package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppText

@Composable
internal fun SettingsCategoryHeader(title: String) {
    AppText(
        text = title,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = AppSpacingTokens.Small)
    )
}
