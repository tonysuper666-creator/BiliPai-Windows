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
    playbackContent: @Composable (page: DesktopSettingsPage.Detail, onBack: () -> Unit) -> Unit,
    backupContent: @Composable (target: SettingsSearchTarget, onDismiss: () -> Unit) -> Unit,
    blockedListContent: @Composable () -> Unit,
    donateContent: @Composable (entry: DesktopSettingsDonateEntry, onDismiss: () -> Unit) -> Unit,
    commentFraudHistoryContent: @Composable (page: DesktopSettingsPage.CommentFraudHistory, onBack: () -> Unit) -> Unit,
    homeContent: @Composable (page: DesktopSettingsPage.Detail, onBack: () -> Unit) -> Unit,
    systemContent: @Composable (page: DesktopSettingsPage, onDonate: () -> Unit) -> Unit,
    onCategoryOpen: (SettingsRootCategory) -> Unit = navigator::openCategory,
    onOpenSearch: () -> Unit = navigator::openSearch,
    onSearchResult: (SettingsSearchResult) -> Unit = navigator::openSearchResult,
    onPageBack: () -> Unit = { navigator.pop() },
    storageContent: @Composable (target: SettingsSearchTarget?) -> Unit,
    imageSavePathContent: @Composable (openInitially: Boolean) -> Unit = {
        AppText("图片保存位置设置仍在移植中。", Modifier.padding(12.dp))
    },
) {
    val navigation by navigator.state.collectAsState()
    val page = navigation.current
    var boundary by remember { mutableStateOf<String?>(null) }
    var nextDonateToken by remember { mutableLongStateOf(0L) }
    var donateEntry by remember { mutableStateOf<DesktopSettingsDonateEntry?>(null) }
    val requestDonate: () -> Unit = {
        val token = ++nextDonateToken
        val parent = page
        donateEntry = DesktopSettingsDonateEntry(token, parent) {
            donateEntry?.entryToken == token && navigator.state.value.current === parent
        }
    }
    LaunchedEffect(page) { if (donateEntry?.parentPage !== page) donateEntry = null }
    DisposableEffect(Unit) { onDispose { donateEntry = null } }
    CompositionLocalProvider(
        LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
        LocalAppPreferenceGroupPresentation provides if (isMiuixNonGlassEnabled())
            AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT,
    ) {
        AppSurface(Modifier.fillMaxSize()) {
            if (page is DesktopSettingsPage.CommentFraudHistory) {
                commentFraudHistoryContent(page) { navigator.pop() }
            } else if (page is DesktopSettingsPage.Search) {
                DesktopSettingsSearchScreen(search, onBack = onPageBack,
                    onCategoryClick = onCategoryOpen,
                    onResultClick = onSearchResult,
                    historyWritesScope = historyWritesScope)
            } else if (page is DesktopSettingsPage.Detail && page.target == SettingsSearchTarget.HOME_FEED) {
                homeContent(page) { onDetailBack(page.target) }
            } else if (page is DesktopSettingsPage.Detail && page.target == SettingsSearchTarget.TIPS) {
                TipsSettingsScreen(onBack = { onDetailBack(page.target) })
            } else if (page is DesktopSettingsPage.Detail && page.target == SettingsSearchTarget.OPEN_SOURCE_LICENSES) {
                OpenSourceLicensesScreen(onBack = { onDetailBack(page.target) })
            } else if (page is DesktopSettingsPage.Detail && page.target == SettingsSearchTarget.PLAYBACK) {
                playbackContent(page) { onDetailBack(page.target) }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (page != DesktopSettingsPage.Root) AppTextButton(onClick = onPageBack) { AppText("返回") }
                        Spacer(Modifier.weight(1f))
                        AppTextButton(onClick = onOpenSearch) { AppText("搜索设置") }
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
                                SettingsRootCategoryListSection(resolveSettingsRootCategoryOrder(), onCategoryOpen,
                                    onDonateClick = requestDonate)
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
                                        DesktopOriginalHomeSettingsCategoryEntry {
                                            navigator.openDetail(SettingsSearchTarget.HOME_FEED,
                                                SettingsSearchFocusController.request.value?.focusId)
                                        }
                                        SettingsDetailGroup("推荐流与动态") {
                                            DesktopHomeRecommendationSettings(discovery, onFailure)
                                        }
                                        AppText("动态布局和标签设置尚未完整移植。", Modifier.padding(vertical = 12.dp))
                                    }
                                    SettingsRootCategory.PRIVACY_PERMISSION -> SettingsDetailGroup("隐私与权限") {
                                        DesktopPrivacySection(privacy,
                                            onPermissionClick = { navigator.openDetail(SettingsSearchTarget.PERMISSION, null) },
                                            onMessageNotificationClick = { navigator.openDetail(SettingsSearchTarget.MESSAGE_NOTIFICATION, null) },
                                            onBlockedListClick = { navigator.openDetail(SettingsSearchTarget.BLOCKED_LIST, null) },
                                            onCommentFraudHistoryClick = navigator::openCommentFraudHistory)
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
                                        systemContent(page, requestDonate)
                                    }
                                    else -> AppText("该分类的原版设置和消费行为仍在移植中。", Modifier.padding(16.dp))
                                }
                            }
                            is DesktopSettingsPage.Detail -> {
                                SettingsCategoryHeader(settingsDestinationCopy(page.target).title)
                                when (page.target) {
                                    SettingsSearchTarget.APPEARANCE -> appearanceContent()
                                    SettingsSearchTarget.PLUGINS -> pluginsContent()
                                    SettingsSearchTarget.PLAYBACK -> Unit // Whole original page owns its scaffold above.
                                    SettingsSearchTarget.BOTTOM_BAR -> DesktopNavigationInteractionSettings(SettingsSearchTarget.BOTTOM_BAR, onFailure)
                                    SettingsSearchTarget.ANIMATION -> DesktopNavigationInteractionSettings(SettingsSearchTarget.ANIMATION, onFailure)
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
                            is DesktopSettingsPage.Search, is DesktopSettingsPage.CommentFraudHistory -> Unit
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
        donateEntry?.takeIf { it.parentPage === page }?.let { entry ->
            donateContent(entry) { if (donateEntry === entry) donateEntry = null }
        }
        boundary?.let { message ->
            AlertDialog(onDismissRequest = { boundary = null }, title = { Text("功能尚未接入") },
                text = { Text(message) }, confirmButton = { TextButton(onClick = { boundary = null }) { Text("关闭") } })
        }
    }
}
