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
                AppIconButton(
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
                AppIconButton(
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
