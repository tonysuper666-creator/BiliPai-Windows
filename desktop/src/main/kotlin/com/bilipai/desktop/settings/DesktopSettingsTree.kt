package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.data.DesktopDiscoveryRepository
import kotlinx.coroutines.CoroutineScope

/** Windows navigation chrome; category rows, fields and search remain original source. */
@Composable
internal fun DesktopSettingsTree(
    navigator: DesktopSettingsNavigator,
    search: DesktopSettingsSearchController,
    historyWritesScope: CoroutineScope,
    discovery: DesktopDiscoveryRepository,
    privacy: DesktopPrivacySectionBindings,
    onFailure: (Throwable) -> Unit,
    onDetailBack: (SettingsSearchTarget) -> Unit = { navigator.pop() },
    appearanceContent: @Composable () -> Unit,
    pluginsContent: @Composable () -> Unit,
    playbackContent: @Composable (onDismiss: () -> Unit) -> Unit,
    backupContent: @Composable (target: SettingsSearchTarget, onDismiss: () -> Unit) -> Unit,
    blockedListContent: @Composable () -> Unit,
    systemContent: @Composable () -> Unit,
    storageContent: @Composable (target: SettingsSearchTarget?) -> Unit,
    imageSavePathContent: @Composable (openInitially: Boolean) -> Unit = {
        AppText("图片保存位置设置仍在移植中。", Modifier.padding(12.dp))
    },
) {
    val navigation by navigator.state.collectAsState()
    val page = navigation.current
    var boundary by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(
        LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
        LocalAppPreferenceGroupPresentation provides if (isMiuixNonGlassEnabled())
            AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT,
    ) {
        AppSurface(Modifier.fillMaxSize()) {
            if (page is DesktopSettingsPage.Search) {
                DesktopSettingsSearchScreen(search, onBack = { navigator.pop() },
                    onCategoryClick = navigator::openCategory,
                    onResultClick = navigator::openSearchResult,
                    historyWritesScope = historyWritesScope)
            } else if (page is DesktopSettingsPage.Detail && page.target == SettingsSearchTarget.TIPS) {
                TipsSettingsScreen(onBack = { onDetailBack(page.target) })
            } else if (page is DesktopSettingsPage.Detail && page.target == SettingsSearchTarget.OPEN_SOURCE_LICENSES) {
                OpenSourceLicensesScreen(onBack = { onDetailBack(page.target) })
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (page != DesktopSettingsPage.Root) AppTextButton(onClick = { navigator.pop() }) { AppText("返回") }
                        Spacer(Modifier.weight(1f))
                        AppTextButton(onClick = navigator::openSearch) { AppText("搜索设置") }
                    }
                    val nestedPageOwnsScroll = page is DesktopSettingsPage.Detail &&
                        page.target in setOf(SettingsSearchTarget.APPEARANCE, SettingsSearchTarget.PLUGINS, SettingsSearchTarget.BLOCKED_LIST,
                            SettingsSearchTarget.BOTTOM_BAR, SettingsSearchTarget.ANIMATION)
                    Column(Modifier.weight(1f).fillMaxWidth()
                        .then(if (nestedPageOwnsScroll) Modifier else Modifier.verticalScroll(rememberScrollState()))
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        when (page) {
                            DesktopSettingsPage.Root -> {
                                SettingsCategoryHeader("设置")
                                SettingsRootCategoryListSection(resolveSettingsRootCategoryOrder(), navigator::openCategory,
                                    onDonateClick = { boundary = "原版赞助页面尚未移植。" })
                            }
                            is DesktopSettingsPage.Category -> {
                                SettingsCategoryHeader(page.category.title)
                                when (canonicalSettingsRootCategory(page.category)) {
                                    SettingsRootCategory.PLAYBACK_QUALITY -> SettingsPlaybackCategoryEntrySection {
                                        // The original entry supplies DECODER or INTERACTION before this callback.
                                        navigator.openDetail(SettingsSearchTarget.PLAYBACK,
                                            SettingsSearchFocusController.request.value?.focusId)
                                    }
                                    SettingsRootCategory.HOME_RECOMMENDATION -> {
                                        SettingsDetailGroup("推荐流与动态") {
                                            DesktopHomeRecommendationSettings(discovery, onFailure)
                                        }
                                        AppText("首页布局、动态布局和标签设置尚未完整移植。", Modifier.padding(vertical = 12.dp))
                                    }
                                    SettingsRootCategory.PRIVACY_PERMISSION -> SettingsDetailGroup("隐私与权限") {
                                        DesktopPrivacySection(privacy,
                                            onPermissionClick = { navigator.openDetail(SettingsSearchTarget.PERMISSION, null) },
                                            onMessageNotificationClick = { navigator.openDetail(SettingsSearchTarget.MESSAGE_NOTIFICATION, null) },
                                            onBlockedListClick = { navigator.openDetail(SettingsSearchTarget.BLOCKED_LIST, null) },
                                            onCommentFraudHistoryClick = { boundary = "原版发评反诈历史的记录、复查和导入导出尚未移植。" })
                                    }
                                    SettingsRootCategory.STORAGE_BACKUP -> {
                                        storageContent(null)
                                    }
                                    SettingsRootCategory.NAVIGATION_INTERACTION -> SettingsNavigationInteractionCategoryEntrySection(
                                        onBottomBarClick = { navigator.openDetail(SettingsSearchTarget.BOTTOM_BAR,
                                            SettingsSearchFocusController.request.value?.focusId) },
                                        onAnimationClick = { navigator.openDetail(SettingsSearchTarget.ANIMATION,
                                            SettingsSearchFocusController.request.value?.focusId) })
                                    SettingsRootCategory.SYSTEM_ABOUT -> {
                                        systemContent()
                                        SettingsDetailGroup("帮助与工具") {
                                            SettingsDetailEntrySection(listOf(SettingsDetailEntry(
                                                target = SettingsSearchTarget.TIPS,
                                                title = settingsDestinationCopy(SettingsSearchTarget.TIPS).title,
                                                value = settingsDestinationCopy(SettingsSearchTarget.TIPS).summary,
                                                onClick = { navigator.openDetail(SettingsSearchTarget.TIPS, null) },
                                            )))
                                        }
                                        SettingsDetailGroup("关于与更新") {
                                            SettingsDetailEntrySection(listOf(SettingsDetailEntry(
                                                target = SettingsSearchTarget.OPEN_SOURCE_LICENSES,
                                                title = settingsDestinationCopy(SettingsSearchTarget.OPEN_SOURCE_LICENSES).title,
                                                value = settingsDestinationCopy(SettingsSearchTarget.OPEN_SOURCE_LICENSES).summary,
                                                onClick = { navigator.openDetail(SettingsSearchTarget.OPEN_SOURCE_LICENSES, null) },
                                            )))
                                        }
                                    }
                                    else -> AppText("该分类的原版设置和消费行为仍在移植中。", Modifier.padding(16.dp))
                                }
                            }
                            is DesktopSettingsPage.Detail -> {
                                SettingsCategoryHeader(settingsDestinationCopy(page.target).title)
                                when (page.target) {
                                    SettingsSearchTarget.APPEARANCE -> appearanceContent()
                                    SettingsSearchTarget.PLUGINS -> pluginsContent()
                                    SettingsSearchTarget.PLAYBACK -> {
                                        if (page.focusId != null && page.focusId !in supportedDesktopPlaybackFocusIds) {
                                            AppText("搜索命中的具体设置尚未移植；当前播放设置中没有对应控件。", Modifier.padding(12.dp))
                                        }
                                        playbackContent { navigator.pop() }
                                    }
                                    SettingsSearchTarget.BOTTOM_BAR -> DesktopNavigationInteractionSettings(SettingsSearchTarget.BOTTOM_BAR, onFailure)
                                    SettingsSearchTarget.ANIMATION -> DesktopNavigationInteractionSettings(SettingsSearchTarget.ANIMATION, onFailure)
                                    SettingsSearchTarget.HOME_FEED -> DesktopHomeRecommendationSettings(discovery, onFailure)
                                    SettingsSearchTarget.WEBDAV_BACKUP, SettingsSearchTarget.SETTINGS_SHARE -> {
                                        AppText("此处使用 Windows 的 WebDAV 和 ZIP 备份窗口；原版全部存储设置仍在移植中。", Modifier.padding(12.dp))
                                        backupContent(page.target) { navigator.pop() }
                                    }
                                    SettingsSearchTarget.PERMISSION -> AppText("Windows 权限状态与检查尚未接入，当前不能确认权限是否可用。", Modifier.padding(12.dp))
                                    SettingsSearchTarget.MESSAGE_NOTIFICATION -> AppText("原版消息通知调度和 Windows 通知权限尚未接入。", Modifier.padding(12.dp))
                                    SettingsSearchTarget.BLOCKED_LIST -> blockedListContent()
                                    SettingsSearchTarget.DOWNLOAD_PATH, SettingsSearchTarget.CLEAR_CACHE -> storageContent(page.target)
                                    SettingsSearchTarget.IMAGE_SAVE_PATH -> imageSavePathContent(true)
                                    else -> AppText("该原版设置页面仍在移植中。", Modifier.padding(12.dp))
                                }
                            }
                            is DesktopSettingsPage.Search -> Unit
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
        boundary?.let { message ->
            AlertDialog(onDismissRequest = { boundary = null }, title = { Text("功能尚未接入") },
                text = { Text(message) }, confirmButton = { TextButton(onClick = { boundary = null }) { Text("关闭") } })
        }
    }
}

private val supportedDesktopPlaybackFocusIds = setOf(
    SettingsSearchFocusIds.PLAYBACK_DECODER, SettingsSearchFocusIds.PLAYBACK_NETWORK,
    SettingsSearchFocusIds.PLAYBACK_INTERACTION, SettingsSearchFocusIds.PLAYBACK_SPEED,
)
