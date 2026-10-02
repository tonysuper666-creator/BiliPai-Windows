package com.android.purebilibili.feature.settings
import androidx.compose.runtime.Composable
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.theme.*
@Composable
internal fun DesktopOriginalNavigationBehaviorFields(
    isBottomBarFloating:Boolean,
    navigationIconCrossScaleEnabled:Boolean,
    bottomBarSearchEnabled:Boolean,
    linkedDockMergeOnScrollEnabled:Boolean,
    listScopedSearchEnabled:Boolean,
    setBottomBarFloating:(Boolean)->Unit,
    setNavigationIconCrossScaleEnabled:(Boolean)->Unit,
    setBottomBarSearchEnabled:(Boolean)->Unit,
    setLinkedDockMergeOnScrollEnabled:(Boolean)->Unit,
    setListScopedSearchEnabled:(Boolean)->Unit,
) {
    AppPreferenceGroup {
        AppSwitchPreference(
                                    icon = rememberSettingsSemanticIcon(SettingsIconRole.FLOATING_BOTTOM_BAR),
                                    title = "悬浮底栏",
                                    subtitle = "开启后底栏与屏幕边缘留出间距；关闭后贴近底部显示",
                                    checked = isBottomBarFloating,
                                    onCheckedChange = { enabled ->
                                        setBottomBarFloating(enabled)
                                    },
                                    iconTint = com.android.purebilibili.core.theme.iOSPurple,
                                )
        AppPreferenceDivider()
        AppSwitchPreference(
                                    icon = rememberSettingsSemanticIcon(SettingsIconRole.NAV_ICON_CROSS_SCALE),
                                    title = "导航图标交叉缩放",
                                    subtitle = "指示器滑动途中图标短暂放大，停稳后恢复原始大小",
                                    checked = navigationIconCrossScaleEnabled,
                                    onCheckedChange = { enabled ->
                                        setNavigationIconCrossScaleEnabled(enabled)
                                    },
                                    iconTint = com.android.purebilibili.core.theme.iOSBlue,
                                )
        AppPreferenceDivider()
        AppSwitchPreference(
                                    icon = rememberSettingsSemanticIcon(SettingsIconRole.BOTTOM_BAR_SEARCH),
                                    title = "底栏搜索联动",
                                    subtitle = "显示独立搜索胶囊，并与推荐和视频小横条随滚动自然收拢或展开",
                                    checked = bottomBarSearchEnabled,
                                    onCheckedChange = { enabled ->
                                        setBottomBarSearchEnabled(enabled)
                                    },
                                    iconTint = com.android.purebilibili.core.theme.iOSTeal,
                                )
        if (bottomBarSearchEnabled) {
        AppPreferenceDivider()
        AppSwitchPreference(
                                        icon = rememberSettingsSemanticIcon(SettingsIconRole.BOTTOM_BAR_SEARCH),
                                        title = "下滑合体",
                                        subtitle = "下滑时搜索胶囊与导航 dock 收拢合并；关闭后保持分体的圆钮与完整底栏",
                                        checked = linkedDockMergeOnScrollEnabled,
                                        onCheckedChange = { enabled ->
                                            setLinkedDockMergeOnScrollEnabled(enabled)
                                        },
                                        iconTint = com.android.purebilibili.core.theme.iOSTeal,
                                    )
        AppPreferenceDivider()
        AppSwitchPreference(
                                        icon = rememberSettingsSemanticIcon(SettingsIconRole.BOTTOM_BAR_SEARCH),
                                        title = "列表精简搜索",
                                        subtitle = "隐藏收藏、历史与稍后再看页顶部搜索栏；底栏搜索仅搜索当前页内容",
                                        checked = listScopedSearchEnabled,
                                        onCheckedChange = { enabled ->
                                            setListScopedSearchEnabled(enabled)
                                        },
                                        iconTint = com.android.purebilibili.core.theme.iOSTeal,
                                    )
        }

    }
}

@Composable
internal fun DesktopOriginalCardMotionFields(
    cardAnimationEnabled:Boolean,
    cardTransitionEnabled:Boolean,
    setCardAnimationEnabled:(Boolean)->Unit,
    setCardTransitionEnabled:(Boolean)->Unit,
) {
    AppPreferenceGroup {
        AppSwitchPreference(
        	                            icon = rememberSettingsSemanticIcon(SettingsIconRole.CARD_ENTRANCE_ANIMATION),
                                    title = "进场动画",
                                    subtitle = "打开首页时卡片依次淡入",
                                    checked = cardAnimationEnabled,
                                    onCheckedChange = { setCardAnimationEnabled(it) },
                                    iconTint = iOSPink
                                )
        AppPreferenceDivider()
        AppSwitchPreference(
                                    icon = rememberSettingsSemanticIcon(SettingsIconRole.CARD_TRANSITION_ANIMATION),
                                    title = "过渡动画",
                                    subtitle = "封面和标题平滑过渡到详情页",
                                    checked = cardTransitionEnabled,
                                    onCheckedChange = { setCardTransitionEnabled(it) },
                                    iconTint = iOSTeal
                                )
    }
}
