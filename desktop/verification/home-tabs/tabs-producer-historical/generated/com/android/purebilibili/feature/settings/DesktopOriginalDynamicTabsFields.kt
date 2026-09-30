// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt; do not edit.
// LF-normalized SHA-256: c8178e7a048512348678555b529180f7a969a658eca4019d484aff8603581a1b
package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem
import com.android.purebilibili.core.ui.components.AppIconButton as IconButton
import com.android.purebilibili.feature.dynamic.*
@Composable internal fun DesktopDynamicTabsSettingsFields(
 dynamicAllTabHorizontalUserListVisible:Boolean,
 onDynamicAllTabHorizontalUserListVisibleChange:(Boolean)->Unit,
 dynamicVisibleTabIds:Set<String>,onDynamicTabVisibilityChange:(String)->Unit,
 dynamicTabOrder:List<String>,onDynamicTabOrderChange:(List<String>)->Unit,
) {
    val siblingTints = remember { resolveSettingsSiblingIconTints(9, paletteOffset = 1) }
    val visibilityIcon = rememberSettingsSemanticIcon(SettingsIconRole.DYNAMIC_TAB_VISIBILITY)
 SettingsCardGroup {
SettingSwitchItem(
    icon = visibilityIcon,
    title = "“全部”页显示关注用户栏",
    subtitle = "关闭后隐藏顶部横向用户列表，“UP主”页仍可选择关注用户",
    checked = dynamicAllTabHorizontalUserListVisible,
    onCheckedChange = onDynamicAllTabHorizontalUserListVisibleChange,
    iconTint = siblingTints[4]
)
 SettingsAdaptiveDivider()
FeedDynamicTabVisibilityItem(
    icon = visibilityIcon,
    visibleTabIds = dynamicVisibleTabIds,
    onTabVisibilityChange = onDynamicTabVisibilityChange,
    tabOrder = dynamicTabOrder,
    onTabOrderChange = onDynamicTabOrderChange,
    iconTint = siblingTints[7]
)
 }
}

@Composable
private fun FeedDynamicTabVisibilityItem(
    icon: ImageVector,
    visibleTabIds: Set<String>,
    onTabVisibilityChange: (String) -> Unit,
    tabOrder: List<String>,
    onTabOrderChange: (List<String>) -> Unit,
    iconTint: Color
) {
    val listCapabilities = rememberAdaptiveListVisualCapabilities()
    val visualSpec = listCapabilities.componentSpec
    val rowSpec = listCapabilities.rowSpec
    val effectiveIconTint = rememberAdaptivePreferenceIconContainerColor(iconTint)
    val iconContentColor = rememberAdaptivePreferenceIconContentColor(effectiveIconTint)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = rowSpec.insideHorizontalPaddingDp.dp,
                vertical = rowSpec.insideVerticalPaddingDp.dp
            )
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(visualSpec.iconContainerSizeDp.dp)
                    .adaptiveSquircleBackground(
                        color = effectiveIconTint,
                        cornerRadius = visualSpec.iconCornerRadiusDp.dp,
                    ),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    icon,
                    contentDescription = null,
                    tint = iconContentColor,
                    modifier = Modifier.size(visualSpec.iconGlyphSizeDp.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = "动态栏位显示",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                AppText(
                    text = "选择动态页显示哪些栏位，至少保留 1 个。隐藏 UP 后，点侧栏用户会直接打开主页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val orderedTabs = remember(tabOrder) {
            allDynamicTabSpecs.sortedWith(
                compareBy(
                    { spec -> tabOrder.indexOf(spec.id).takeIf { it >= 0 } ?: tabOrder.size },
                    { it.logicalIndex }
                )
            )
        }
        orderedTabs.forEachIndexed { index, tab ->
            val checked = tab.id in visibleTabIds
            val enabled = shouldAllowDynamicTabVisibilityToggleOff(
                currentVisibleTabIds = visibleTabIds,
                targetTabId = tab.id
            )
            SettingSwitchItem(
                title = tab.title,
                checked = checked,
                onCheckedChange = { onTabVisibilityChange(tab.id) },
                enabled = enabled
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    enabled = index > 0,
                    onClick = {
                        val newOrder = orderedTabs.map { it.id }.toMutableList()
                        val target = newOrder[index - 1]
                        newOrder[index - 1] = newOrder[index]
                        newOrder[index] = target
                        onTabOrderChange(newOrder)
                    }
                ) {
                    AppIcon(
                        androidx.compose.material.icons.Icons.Default.KeyboardArrowUp,
                        contentDescription = "上移${tab.title}"
                    )
                }
                IconButton(
                    enabled = index < orderedTabs.lastIndex,
                    onClick = {
                        val newOrder = orderedTabs.map { it.id }.toMutableList()
                        val target = newOrder[index + 1]
                        newOrder[index + 1] = newOrder[index]
                        newOrder[index] = target
                        onTabOrderChange(newOrder)
                    }
                ) {
                    AppIcon(
                        androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                        contentDescription = "下移${tab.title}"
                    )
                }
            }
            if (index != orderedTabs.lastIndex) {
                SettingsAdaptiveDivider()
            }
        }
    }
}
