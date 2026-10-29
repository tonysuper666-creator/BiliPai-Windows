package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The actual Root diagnostic actor, shared by recoverable page errors; never a second log sink. */
internal val LocalDesktopPageErrorReporter = staticCompositionLocalOf<((Throwable) -> Unit)?> { null }

/** Contract player.overlay.error_card: 360dp, or the available width on a smaller stage. */
@Composable
internal fun DesktopWindowsPageErrorCard(message: String, onRetry: () -> Unit, onLogin: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 360.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text("暂时无法播放", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("重试") }
                    if (onLogin != null) TextButton(onClick = onLogin) { Text("登录并返回") }
                }
            }
        }
    }
}
