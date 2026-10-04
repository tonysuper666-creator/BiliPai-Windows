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
            DesktopWindowsSettingsGroup("更新与关于") {
                androidx.compose.material3.Text("BiliPai Windows · 上游基线 v0.2.5")
                androidx.compose.material3.OutlinedButton(onClick = { if (current()) windowsUpdateNow() }) {
                    androidx.compose.material3.Text("检查 Windows 更新")
                }
                androidx.compose.material3.TextButton(onClick = { uriHandler.openUri(OFFICIAL_GITHUB_URL) }) {
                    androidx.compose.material3.Text("上游源码与更新日志")
                }
            }
            DesktopWindowsSettingsGroup("帮助与许可") {
                androidx.compose.material3.TextButton(onClick = {
                    if (current()) routes.callbackFor(entryKey) { routes.push(BiliPaiNavKey.TipsSettings) }
                }) { androidx.compose.material3.Text("使用说明") }
                androidx.compose.material3.TextButton(onClick = {
                    if (current()) routes.callbackFor(entryKey) { routes.push(BiliPaiNavKey.OpenSourceLicenses) }
                }) { androidx.compose.material3.Text("开源许可证") }
                androidx.compose.material3.TextButton(onClick = { if (current()) agreement = true }) {
                    androidx.compose.material3.Text("用户协议与隐私政策")
                }
                androidx.compose.material3.TextButton(onClick = { if (current()) donateNow() }) {
                    androidx.compose.material3.Text("支持原作者")
                }
            }
            if (foreground && current() && agreement)
                com.android.purebilibili.feature.agreement.UserAgreementReviewDialog { agreement = false }
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
