package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.util.EasterEggs
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.awt.EventQueue
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/** Complete original About/support/release-channel bodies. Each effect captures the actual
 * current settings leaf; retention does not admit callbacks from a covered/replaced page. */
@Composable internal fun DesktopOriginalSystemAboutRootHost(
    routes: DesktopOriginalRootRouteAssembly,
    entryKey: BiliPaiNavKey,
    page: DesktopSettingsPage,
    navigator: DesktopSettingsNavigator,
    handleReference: AtomicReference<DesktopReadyOriginalRootHandle?>,
    context: DesktopPluginContext,
    imageLifetime: DesktopImageSaveLifetime,
    active: Boolean,
    onWindowsUpdate: () -> Unit,
    onDonate: () -> Unit,
    onFailure: (Throwable) -> Unit,
    onNotice: (String) -> Unit,
) {
    val captured = remember(routes) { requireNotNull(handleReference.get()) }
    val activeNow by rememberUpdatedState(active)
    val failureNow by rememberUpdatedState(onFailure)
    val noticeNow by rememberUpdatedState(onNotice)
    val windowsUpdateNow by rememberUpdatedState(onWindowsUpdate)
    val donateNow by rememberUpdatedState(onDonate)
    val compositionScope = rememberCoroutineScope()
    val pageIdentity = DesktopOriginalAboutPageIdentity(page)
    val scope = remember(pageIdentity, compositionScope) {
        CoroutineScope(compositionScope.coroutineContext + SupervisorJob(compositionScope.coroutineContext[Job]))
    }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val foreground = LocalDesktopDetailForeground.current
    val current = {
        activeNow && handleReference.get() === captured && captured.isActive() &&
            captured.route.get() === routes && captured.retainer.root.value === routes.root &&
            routes.root.entry.gate.scope.isActive && routes.currentKey == entryKey &&
            navigator.state.value.current === page && imageLifetime.isActive()
    }
    val admit: ((() -> Unit) -> Boolean) = remember(routes, captured, pageIdentity, imageLifetime) {
        { action ->
            var applied = false
            val accepted = routes.root.entry.gate.commit {
                imageLifetime.withCommit { if (current()) { action(); applied = true } }
            }
            accepted && applied
        }
    }
    val preferences = remember(routes, captured, pageIdentity, context, imageLifetime) {
        DesktopOriginalAboutPreferences(context, current, admit)
    }
    val bindings = remember(preferences, scope) { DesktopOriginalAboutBindings(preferences, scope, current) { failureNow(it) } }
    val metadata = LocalDesktopOriginalAboutReleaseMetadata.current
    val release by metadata.state.collectAsState()
    val icon by preferences.icon.state.collectAsState(preferences.icon.initialState)
    val autoCheck by preferences.autoCheck.collectAsState(preferences.initialAutoCheck)
    val channel by preferences.channel.collectAsState(preferences.initialChannel)
    val easterEgg by preferences.easterEgg.collectAsState(preferences.initialEasterEgg)
    var releaseNotes by remember(pageIdentity) { mutableStateOf<AppUpdateCheckResult?>(null) }
    var disclaimer by remember(pageIdentity) { mutableStateOf(false) }
    var agreement by remember(pageIdentity) { mutableStateOf(false) }
    var easterDialog by remember(pageIdentity) { mutableStateOf(false) }
    var versionClicks by remember(pageIdentity) { mutableIntStateOf(0) }
    val uriHandler = remember(bindings, pageIdentity) { object : UriHandler {
        override fun openUri(uri: String) {
            check(EventQueue.isDispatchThread()) { "About native actions require the owned Window EDT" }
            if (!current()) return
            try { openDesktopOriginalAboutUri(uri) }
            catch (failure: Exception) { if (current()) failureNow(failure) }
        }
    } }
    val hasDialog = disclaimer || agreement || easterDialog || releaseNotes != null
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state = backState, isBackEnabled = foreground && current() && hasDialog, onBackCompleted = {
        if (current()) when {
            releaseNotes != null -> releaseNotes = null
            disclaimer -> disclaimer = false
            agreement -> agreement = false
            easterDialog -> { easterDialog = false; versionClicks = 0 }
        }
    })
    key(pageIdentity) {
    CompositionLocalProvider(LocalDesktopOriginalAboutBindings provides bindings, LocalUriHandler provides uriHandler) {
        SettingsDetailGroup("帮助与工具") {
            SupportToolsSection(
                onTipsClick = { if (current()) routes.callbackFor(entryKey) { routes.push(BiliPaiNavKey.TipsSettings) } },
                onOpenLinksClick = { uriHandler.openUri(DESKTOP_ORIGINAL_DEFAULT_LINK_SETTINGS_URI) },
            )
        }
        SettingsDetailGroup("关于与更新") {
            AppPreference(
                icon = DesktopSettingsVectors.vector("ms_gavel_24"),
                title = "用户协议与隐私政策", value = "查看全文",
                onClick = { if (current()) agreement = true },
            )
            SettingsAdaptiveDivider()
            AboutSection(
                versionName = DESKTOP_ORIGINAL_ABOUT_VERSION,
                appIconKey = icon.appIcon,
                easterEggEnabled = easterEgg,
                onLicenseClick = { if (current()) routes.callbackFor(entryKey) { routes.push(BiliPaiNavKey.OpenSourceLicenses) } },
                onGithubClick = { uriHandler.openUri(OFFICIAL_GITHUB_URL) },
                onVerificationClick = { uriHandler.openUri(OFFICIAL_GITHUB_URL) },
                onBuildSourceClick = { uriHandler.openUri(OFFICIAL_GITHUB_URL) },
                onBuildFingerprintClick = { uriHandler.openUri(OFFICIAL_GITHUB_URL) },
                onCheckUpdateClick = { if (current()) windowsUpdateNow() },
                onViewReleaseNotesClick = {
                    if (current()) scope.launch {
                        try {
                            val result = metadata.check(DESKTOP_ORIGINAL_ABOUT_VERSION, DESKTOP_ORIGINAL_ABOUT_VERSION_CODE,
                                channel == DesktopOriginalAboutSettings.AppUpdateChannel.BETA, current, admit, silent = true)
                            ensureActive()
                            if (current()) result?.fold(
                                onSuccess = { releaseNotes = it },
                                onFailure = { failureNow(it) }) ?: noticeNow("正在检查更新，请稍候")
                        } catch (cancelled: CancellationException) { throw cancelled }
                    }
                },
                autoCheckUpdateEnabled = autoCheck,
                onAutoCheckUpdateChange = { value -> bindings.update { preferences.setAutoCheck(value) } },
                appUpdateChannel = channel,
                onAppUpdateChannelChange = { value -> bindings.update { preferences.setChannel(value) } },
                onVersionClick = {
                    if (current()) {
                        versionClicks++
                        val threshold = EasterEggs.VERSION_EASTER_EGG_THRESHOLD
                        val remaining = (threshold - versionClicks).coerceAtLeast(0)
                        val message = EasterEggs.getVersionClickMessage(versionClicks, threshold)
                        if (EasterEggs.isVersionEasterEggTriggered(versionClicks, threshold)) {
                            easterDialog = true; noticeNow(message)
                        } else if (versionClicks >= 2 || remaining <= 3) noticeNow(message)
                    }
                },
                onReplayOnboardingClick = { if (current()) routes.callbackFor(entryKey) { routes.push(BiliPaiNavKey.Onboarding) } },
                onEasterEggChange = { value -> bindings.update { preferences.setEasterEgg(value) } },
                updateStatusText = release.status,
                isCheckingUpdate = release.checking,
                verificationLabel = "未验证",
                verificationSubtitle = "Windows 构建尚未绑定公开发布证明",
                buildSourceValue = "Windows 本地构建",
                buildSourceSubtitle = "上游 v0.2.5；Windows 适配构建与上游 APK 分别验证",
                buildFingerprintSubtitle = "当前页面尚未获得可核对的 Windows 发布校验信息",
                versionClickCount = versionClicks,
            )
            AppText("更新日志来自上游项目；Windows 安装包由 Windows 更新器检查。", Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        ReleaseChannelPinnedCard(
            onGithubClick = { uriHandler.openUri(OFFICIAL_GITHUB_URL) },
            onTelegramClick = { uriHandler.openUri(OFFICIAL_TELEGRAM_CHANNEL_URL) },
            onTelegramGroupClick = { uriHandler.openUri(OFFICIAL_TELEGRAM_GROUP_URL) },
            onDisclaimerClick = { if (current()) disclaimer = true },
        )
        // Original action search results land on this category. Keep that policy, then make
        // its original targets reachable here without performing an action on search click.
        SettingsDetailGroup("关注作者") {
            SettingsDetailEntrySection(listOf(
                SettingsDetailEntry(SettingsSearchTarget.TWITTER,
                    settingsDestinationCopy(SettingsSearchTarget.TWITTER).title,
                    settingsDestinationCopy(SettingsSearchTarget.TWITTER).summary,
                    onClick = { uriHandler.openUri("https://x.com/YangY_0x00") }),
                SettingsDetailEntry(SettingsSearchTarget.DONATE,
                    settingsDestinationCopy(SettingsSearchTarget.DONATE).title,
                    settingsDestinationCopy(SettingsSearchTarget.DONATE).summary,
                    onClick = { if (current()) donateNow() }),
            ))
        }
        if (foreground && current()) {
            if (agreement) com.android.purebilibili.feature.agreement.UserAgreementReviewDialog { agreement = false }
            if (disclaimer) ReleaseChannelDisclaimerDialog(
                onDismiss = { disclaimer = false },
                onOpenGithub = { uriHandler.openUri(OFFICIAL_GITHUB_URL) },
                onOpenTelegram = { uriHandler.openUri(OFFICIAL_TELEGRAM_CHANNEL_URL) })
            if (easterDialog) AppAlertDialog(
                onDismissRequest = { easterDialog = false; versionClicks = 0 },
                title = { AppText("你发现了彩蛋！", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                text = { AppText("感谢你使用 BiliPai！这是一个用爱发电的开源项目。") },
                confirmButton = { AppDialogAction(onClick = { easterDialog = false; versionClicks = 0 }) { AppText("我知道了！") } })
            releaseNotes?.let { update -> DesktopOriginalAboutReleaseNotesDialog(update) { releaseNotes = null } }
        }
    }
    }
}

/** Value-equal newly opened local category is a different request lifetime. */
private class DesktopOriginalAboutPageIdentity(private val page: DesktopSettingsPage) {
    override fun equals(other: Any?) = other is DesktopOriginalAboutPageIdentity && page === other.page
    override fun hashCode() = System.identityHashCode(page)
}

/** Only metadata/notes are exposed; the original APK download/install host is excluded. */
@Composable internal fun DesktopOriginalAboutReleaseNotesDialog(update: AppUpdateCheckResult, dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val state = AppUpdateDialogState(update, AppUpdateDownloadState(), null, false, true)
    val actions = AppUpdateDialogActions(
        onPrimaryAction = { uriHandler.openUri(update.releaseUrl) },
        onCancelDownload = dismiss,
        onOpenRelease = { uriHandler.openUri(GITHUB_RELEASE_DOWNLOAD_URL) },
        onOpenTestRelease = { uriHandler.openUri(GITHUB_TEST_DOWNLOAD_URL) },
        onDismissRequest = dismiss)
    when (com.android.purebilibili.core.theme.LocalAppUiStyle.current) {
        com.android.purebilibili.core.theme.AppUiStyle.MATERIAL3 -> Material3AppUpdateDialog(state, actions)
        com.android.purebilibili.core.theme.AppUiStyle.MIUIX -> MiuixAppUpdateDialog(state, actions)
    }
}

internal const val DESKTOP_ORIGINAL_DEFAULT_LINK_SETTINGS_URI = "ms-settings:defaultapps"
internal fun openDesktopOriginalAboutUri(raw: String) {
    val uri = URI(raw)
    require((uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) ||
        raw == DESKTOP_ORIGINAL_DEFAULT_LINK_SETTINGS_URI) { "Unsupported About native URI" }
    require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { "无法打开设置或浏览器" }
    Desktop.getDesktop().browse(uri)
}
