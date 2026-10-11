package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.video.ui.components.VideoAspectRatio
import com.android.purebilibili.feature.video.ui.components.resolveSafeVideoAspectRatio

/** Display-only Windows entry to the original ratio/flip choices. The mounted Leaf owns every action. */
@Composable internal fun DesktopWindowsVideoViewportControls(
    ratio: VideoAspectRatio,
    flipHorizontal: Boolean,
    flipVertical: Boolean,
    enabled: Boolean,
    isVerticalVideo: Boolean,
    onRatioSelected: (VideoAspectRatio) -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("画面比例", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VideoAspectRatio.entries.forEach { option ->
                FilterChip(selected = ratio == option,
                    onClick = { onRatioSelected(option) },
                    enabled = enabled && resolveSafeVideoAspectRatio(option, isVerticalVideo) == option,
                    label = { Text(option.displayName) })
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = flipHorizontal, onClick = onFlipHorizontal,
                enabled = enabled, label = { Text("左右翻转") })
            FilterChip(selected = flipVertical, onClick = onFlipVertical,
                enabled = enabled, label = { Text("上下翻转") })
        }
    }
}
