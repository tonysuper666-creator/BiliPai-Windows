package com.bilipai.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.data.DesktopDiscoveryRepository
import com.bilipai.desktop.ui.DESKTOP_SETTINGS_CONTENT_MAX_WIDTH_DP
import com.bilipai.desktop.ui.DESKTOP_SETTINGS_HEADER_HEIGHT_DP
import com.bilipai.desktop.ui.DESKTOP_SETTINGS_PAGE_PADDING_DP
import com.bilipai.desktop.ui.DESKTOP_SETTINGS_RAIL_WIDTH_DP
import com.bilipai.desktop.ui.DesktopSettingsBackButton
import com.bilipai.desktop.ui.DesktopSettingsHeaderState
import com.bilipai.desktop.ui.DesktopWindowsSettingsGroup
import com.bilipai.desktop.ui.DesktopWindowsSettingsStrings
import com.bilipai.desktop.ui.LocalDesktopSettingsHeader
import com.bilipai.desktop.ui.LocalDesktopSettingsPanePrelude
import com.bilipai.desktop.ui.kit.DesktopKitDivider
import com.bilipai.desktop.ui.kit.DesktopKitVerticalScrollbar
import com.bilipai.desktop.ui.kit.DesktopMenuRow
import com.bilipai.desktop.ui.kit.DesktopNavRow
import com.bilipai.desktop.ui.kit.desktopKitHairlineDp
import kotlinx.coroutines.CoroutineScope

/**
 * The one header row of the tree: Back (when a page is open), the title of the page, and the search action. The title is
 * the title of the Pane that is mounted below, else the rail title of the page's category, else the tree title. Back
 * is the Pane's own Back when a Pane is mounted (it knows how its route is left), else the page Back of the tree.
 * The four settings fixtures click this one Back by its accessible name, left of the title and on its row.
 */
@Composable
private fun DesktopSettingsTreeHeader(title: String, showBack: Boolean, onBack: () -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(DESKTOP_SETTINGS_HEADER_HEIGHT_DP.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) DesktopSettingsBackButton(onBack)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        DesktopMenuRow(DesktopWindowsSettingsStrings.SEARCH, onSearch, Modifier.width(120.dp))
    }
}

/** The category rail: one kit navigation row per category, the category of the open page selected. */
@Composable
private fun DesktopSettingsRail(selected: SettingsRootCategory?, onSelect: (SettingsRootCategory) -> Unit) {
    Column(
        Modifier.width(DESKTOP_SETTINGS_RAIL_WIDTH_DP.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DESKTOP_SETTINGS_RAIL_CATEGORIES.forEach { category ->
            val title = DesktopWindowsSettingsStrings.railTitle(category) ?: return@forEach
            DesktopNavRow(label = title, selected = category == selected, onClick = { onSelect(category) })
        }
    }
}

/** The root page holds nothing but a hint, and not even that while the landing on the last category is still to come. */
@Composable
private fun DesktopSettingsRootHint(landingPending: Boolean) {
    if (!landingPending) Text(DesktopWindowsSettingsStrings.EMPTY_HINT, Modifier.padding(DESKTOP_SETTINGS_PAGE_PADDING_DP.dp))
}

/** A page that the tree itself scrolls: content at most 960 dp wide, left aligned, with the shared overlay scrollbar. */
@Composable
private fun DesktopSettingsTreePage(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(
                Modifier.widthIn(max = DESKTOP_SETTINGS_CONTENT_MAX_WIDTH_DP.dp).fillMaxWidth().padding(DESKTOP_SETTINGS_PAGE_PADDING_DP.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
        DesktopKitVerticalScrollbar(scroll)
    }
}

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
    /** A rail click. The system category is a route of its own and goes through onCategoryOpen; the others replace the page. */
    onCategorySelect: (SettingsRootCategory) -> Unit = { category ->
        if (canonicalSettingsRootCategory(category) == SettingsRootCategory.SYSTEM_ABOUT) onCategoryOpen(category)
        else navigator.replaceCategory(category)
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
    // A fresh visit opens the last used category; a category page that has a direct detail is replaced by it.
    LaunchedEffect(page, navigation.landingPending) {
        if (page === DesktopSettingsPage.Root && navigation.landingPending) navigator.landOnLastCategory()
        else if (page is DesktopSettingsPage.Category && desktopSettingsDirectDetail(page.category) != null) navigator.replaceCategory(page.category)
    }
    val selected = desktopSettingsPageCategory(page)
    val header = remember { DesktopSettingsHeaderState() }
    val homePrelude: @Composable ColumnScope.() -> Unit = {
        DesktopWindowsSettingsGroup(DesktopWindowsSettingsStrings.HOME_RECOMMENDATION_GROUP) {
            DesktopHomeRecommendationSettings(discovery, onFailure)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DesktopSettingsTreeHeader(
                title = header.title ?: selected?.let { DesktopWindowsSettingsStrings.railTitle(it) } ?: DesktopWindowsSettingsStrings.TREE_TITLE,
                showBack = page != DesktopSettingsPage.Root,
                onBack = { (header.onBack ?: onPageBack)() },
                onSearch = onOpenSearch,
            )
            DesktopKitDivider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                DesktopSettingsRail(selected) { category ->
                    // A click on the entry whose page is open does nothing; from a page under it, it opens the category page.
                    if (!desktopSettingsRailClickIsRedundant(page, category)) onCategorySelect(category)
                }
                Box(Modifier.width(desktopKitHairlineDp(LocalDensity.current.density).dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalDesktopSettingsHeader provides header) {
                        when (page) {
                            DesktopSettingsPage.Root -> DesktopSettingsRootHint(navigation.landingPending)
                            is DesktopSettingsPage.Search -> DesktopSettingsSearchScreen(search, onBack = onPageBack,
                                onCategoryClick = onCategoryOpen, onResultClick = onSearchResult, historyWritesScope = historyWritesScope)
                            is DesktopSettingsPage.CommentFraudHistory -> commentFraudHistoryContent(page) { navigator.pop() }
                            is DesktopSettingsPage.Detail -> when (page.target) {
                                SettingsSearchTarget.PLAYBACK -> playbackContent(page) { onDetailBack(page.target) }
                                SettingsSearchTarget.HOME_FEED -> CompositionLocalProvider(LocalDesktopSettingsPanePrelude provides homePrelude) {
                                    homeContent(page) { onDetailBack(page.target) }
                                }
                                SettingsSearchTarget.APPEARANCE -> appearanceContent()
                                SettingsSearchTarget.PLUGINS -> pluginsContent()
                                SettingsSearchTarget.MESSAGE_NOTIFICATION -> DesktopMessageNotificationSettingsContent(onFailure)
                                SettingsSearchTarget.BLOCKED_LIST -> blockedListContent()
                                SettingsSearchTarget.TIPS -> com.bilipai.desktop.ui.DesktopWindowsTipsSettings(onBack = { onDetailBack(page.target) })
                                SettingsSearchTarget.OPEN_SOURCE_LICENSES -> OpenSourceLicensesScreen(onBack = { onDetailBack(page.target) })
                                SettingsSearchTarget.WEBDAV_BACKUP, SettingsSearchTarget.SETTINGS_SHARE -> backupContent(page.target) { navigator.pop() }
                                SettingsSearchTarget.DOWNLOAD_PATH, SettingsSearchTarget.CLEAR_CACHE -> DesktopSettingsTreePage {
                                    storageContent(page.target)
                                }
                                SettingsSearchTarget.IMAGE_SAVE_PATH -> DesktopSettingsTreePage { imageSavePathContent(true) }
                                else -> Text("此入口不属于当前 Windows 设置。", Modifier.padding(20.dp))
                            }
                            is DesktopSettingsPage.Category -> DesktopSettingsTreePage {
                                when (canonicalSettingsRootCategory(page.category)) {
                                    SettingsRootCategory.PRIVACY_PERMISSION -> DesktopPrivacySection(privacy,
                                        onPermissionClick = {},
                                        onMessageNotificationClick = { navigator.openDetail(SettingsSearchTarget.MESSAGE_NOTIFICATION, null) },
                                        onBlockedListClick = { navigator.openDetail(SettingsSearchTarget.BLOCKED_LIST, null) },
                                        onCommentFraudHistoryClick = navigator::openCommentFraudHistory)
                                    SettingsRootCategory.STORAGE_BACKUP -> storageContent(null)
                                    SettingsRootCategory.SYSTEM_ABOUT -> systemContent(page, requestDonate)
                                    // These categories open straight to their detail; the effect above replaces this page at once.
                                    SettingsRootCategory.PLAYBACK_QUALITY, SettingsRootCategory.HOME_RECOMMENDATION,
                                    SettingsRootCategory.APPEARANCE_THEME, SettingsRootCategory.PLUGINS_EXTENSIONS -> Unit
                                    else -> Text("此分类已从 Windows 设置中移除。")
                                }
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
