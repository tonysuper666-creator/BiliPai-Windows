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
    var nextDonateToken by remember { mutableLongStateOf(0L) }
    var donateEntry by remember { mutableStateOf<DesktopSettingsDonateEntry?>(null) }
    val requestDonate: () -> Unit = {
        val token = ++nextDonateToken
        donateEntry = DesktopSettingsDonateEntry(token, page) {
            donateEntry?.entryToken == token && navigator.state.value.current === page
        }
    }
    LaunchedEffect(page) { if (donateEntry?.parentPage !== page) donateEntry = null }
    DisposableEffect(Unit) { onDispose { donateEntry = null } }
    val groups = listOf(
        "播放与音频" to SettingsRootCategory.PLAYBACK_QUALITY,
        "首页与推荐" to SettingsRootCategory.HOME_RECOMMENDATION,
        "外观与显示" to SettingsRootCategory.APPEARANCE_THEME,
        "缓存与备份" to SettingsRootCategory.STORAGE_BACKUP,
        "隐私与屏蔽" to SettingsRootCategory.PRIVACY_PERMISSION,
        "插件与扩展" to SettingsRootCategory.PLUGINS_EXTENSIONS,
        "更新与诊断" to SettingsRootCategory.SYSTEM_ABOUT,
    )
    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (page != DesktopSettingsPage.Root) androidx.compose.material3.TextButton(onClick = onPageBack) {
                    androidx.compose.material3.Text("返回")
                }
                androidx.compose.material3.Text("Windows 设置", style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onOpenSearch) { androidx.compose.material3.Text("搜索设置") }
            }
            androidx.compose.material3.HorizontalDivider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(160.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { (title, category) ->
                        androidx.compose.material3.TextButton(onClick = { onCategoryOpen(category) }, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Text(title)
                        }
                    }
                }
                androidx.compose.material3.VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (page) {
                        DesktopSettingsPage.Root -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            androidx.compose.material3.Text("适合鼠标、键盘与桌面窗口的设置。")
                            groups.forEach { (title, category) ->
                                androidx.compose.material3.OutlinedButton(onClick = { onCategoryOpen(category) }, modifier = Modifier.fillMaxWidth()) {
                                    androidx.compose.material3.Text(title)
                                }
                            }
                        }
                        is DesktopSettingsPage.Search -> DesktopSettingsSearchScreen(search, onBack = onPageBack,
                            onCategoryClick = onCategoryOpen, onResultClick = onSearchResult, historyWritesScope = historyWritesScope)
                        is DesktopSettingsPage.CommentFraudHistory -> commentFraudHistoryContent(page) { navigator.pop() }
                        is DesktopSettingsPage.Detail -> when (page.target) {
                            SettingsSearchTarget.PLAYBACK -> playbackContent(page) { onDetailBack(page.target) }
                            SettingsSearchTarget.HOME_FEED -> homeContent(page) { onDetailBack(page.target) }
                            SettingsSearchTarget.APPEARANCE -> appearanceContent()
                            SettingsSearchTarget.PLUGINS -> pluginsContent()
                            SettingsSearchTarget.MESSAGE_NOTIFICATION -> DesktopMessageNotificationSettingsContent(onFailure)
                            SettingsSearchTarget.BLOCKED_LIST -> blockedListContent()
                            SettingsSearchTarget.TIPS -> com.bilipai.desktop.ui.DesktopWindowsTipsSettings(onBack = { onDetailBack(page.target) })
                            SettingsSearchTarget.OPEN_SOURCE_LICENSES -> OpenSourceLicensesScreen(onBack = { onDetailBack(page.target) })
                            SettingsSearchTarget.WEBDAV_BACKUP, SettingsSearchTarget.SETTINGS_SHARE -> backupContent(page.target) { navigator.pop() }
                            SettingsSearchTarget.DOWNLOAD_PATH, SettingsSearchTarget.CLEAR_CACHE -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                                storageContent(page.target)
                            }
                            SettingsSearchTarget.IMAGE_SAVE_PATH -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) { imageSavePathContent(true) }
                            else -> androidx.compose.material3.Text("此入口不属于当前 Windows 设置。", Modifier.padding(20.dp))
                        }
                        is DesktopSettingsPage.Category -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (canonicalSettingsRootCategory(page.category)) {
                                SettingsRootCategory.PLAYBACK_QUALITY -> androidx.compose.material3.OutlinedButton(onClick = {
                                    navigator.openDetail(SettingsSearchTarget.PLAYBACK, null)
                                }) { androidx.compose.material3.Text("打开播放与 Windows 音频设置") }
                                SettingsRootCategory.HOME_RECOMMENDATION -> {
                                    androidx.compose.material3.OutlinedButton(onClick = { navigator.openDetail(SettingsSearchTarget.HOME_FEED, null) }) {
                                        androidx.compose.material3.Text("首页布局、轮播与背景")
                                    }
                                    DesktopHomeRecommendationSettings(discovery, onFailure)
                                }
                                SettingsRootCategory.PRIVACY_PERMISSION -> DesktopPrivacySection(privacy,
                                    onPermissionClick = {},
                                    onMessageNotificationClick = { navigator.openDetail(SettingsSearchTarget.MESSAGE_NOTIFICATION, null) },
                                    onBlockedListClick = { navigator.openDetail(SettingsSearchTarget.BLOCKED_LIST, null) },
                                    onCommentFraudHistoryClick = navigator::openCommentFraudHistory)
                                SettingsRootCategory.STORAGE_BACKUP -> storageContent(null)
                                SettingsRootCategory.SYSTEM_ABOUT -> systemContent(page, requestDonate)
                                SettingsRootCategory.APPEARANCE_THEME -> androidx.compose.material3.OutlinedButton(onClick = { navigator.openDetail(SettingsSearchTarget.APPEARANCE, null) }) {
                                    androidx.compose.material3.Text("打开外观设置")
                                }
                                SettingsRootCategory.PLUGINS_EXTENSIONS -> androidx.compose.material3.OutlinedButton(onClick = { navigator.openDetail(SettingsSearchTarget.PLUGINS, null) }) {
                                    androidx.compose.material3.Text("打开插件设置")
                                }
                                else -> androidx.compose.material3.Text("此分类已从 Windows 设置中移除。")
                            }
                        }
                    }
                }
            }
        }
        donateEntry?.takeIf { it.parentPage === page }?.let { entry ->
            donateContent(entry) { if (donateEntry === entry) donateEntry = null }
        }
    }
}
