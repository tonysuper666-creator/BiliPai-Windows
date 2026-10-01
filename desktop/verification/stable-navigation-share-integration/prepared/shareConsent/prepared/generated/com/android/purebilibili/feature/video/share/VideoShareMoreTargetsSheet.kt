package com.android.purebilibili.feature.video.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppListItem
import com.android.purebilibili.core.ui.components.AppText
import kotlinx.coroutines.launch

internal data class VideoShareAppTarget(
    val target: VideoShareTarget,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

internal fun findVideoShareAppTargets(context: com.bilipai.desktop.ui.DesktopVideoShareBindings, mimeType: String): List<VideoShareAppTarget> =
    context.availableTargets(mimeType)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoShareMoreTargetsSheet(
    shareMedia: VideoShareCoverFile?,
    onDismiss: () -> Unit,
    onTargetClick: (VideoShareAppTarget) -> Unit,
    onSystemChooserClick: () -> Unit,
) {
    val context = com.bilipai.desktop.ui.LocalDesktopVideoShareBindings.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isLandscape = isLandscapeVideoShare()
    val sheetBounce = rememberVideoShareSheetBounce(sheetState, isLandscape)
    val maxListHeight = (com.bilipai.desktop.ui.LocalDesktopVideoShareBindings.current.screenHeightDp - 220).coerceIn(160, 440).dp
    val mimeType = shareMedia?.mimeType ?: "text/plain"
    val targets = remember(context, mimeType) { findVideoShareAppTargets(context, mimeType) }
    var openingTarget by remember { mutableStateOf(false) }

    AppModalBottomSheet(
        onDismissRequest = { if (!openingTarget) onDismiss() },
        sheetState = sheetState,
        presentationOverride = videoSharePresentation(isLandscape),
        sheetSurfaceModifier = sheetBounce,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(
                horizontal = AppSpacingTokens.Large,
                vertical = AppSpacingTokens.Small,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small),
        ) {
            AppText("更多分享方式", style = MaterialTheme.typography.headlineSmall)
            AppText("选择实际可用的 Windows 分享方式", style = MaterialTheme.typography.bodySmall)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)) {
                items(targets, key = { it.target.name }) { target ->
                    AppListItem(
                        headlineContent = { AppText(target.label) },
                        leadingContent = {
                            com.android.purebilibili.core.ui.components.AppIcon(
                                imageVector = target.icon,
                                contentDescription = target.label,
                                modifier = Modifier.size(48.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !openingTarget) {
                            openingTarget = true
                            scope.launch {
                                hideVideoShareSheet(sheetState)
                                onTargetClick(target)
                            }
                        },
                    )
                }
                if (targets.isEmpty()) item { AppText("没有找到可接收此内容的应用") }
            }
            AppButton(
                onClick = {
                    openingTarget = true
                    scope.launch {
                        hideVideoShareSheet(sheetState)
                        onSystemChooserClick()
                    }
                },
                enabled = !openingTarget,
                modifier = Modifier.fillMaxWidth(),
            ) { AppText("系统分享面板") }
            AppText(
                "系统面板列出由 Windows 注册的接收应用；是否接收由该应用决定",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppButton(onClick = onDismiss, enabled = !openingTarget, modifier = Modifier.fillMaxWidth()) {
                AppText("取消")
            }
        }
    }
}
