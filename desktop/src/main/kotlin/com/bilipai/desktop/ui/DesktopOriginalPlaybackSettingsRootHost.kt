package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.feature.settings.PlaybackSettingsScreen
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.DesktopOriginalPlaybackSettingsBindings
import com.bilipai.desktop.settings.DesktopOriginalPlaybackSettingsState
import com.bilipai.desktop.settings.DesktopSettingsNavigator
import com.bilipai.desktop.settings.DesktopSettingsPage
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One actual Root/page owns the entire original screen. Original player requests and
 * Window hardware observation keep their own existing lifetimes; no account/store is created. */
@Composable
internal fun DesktopOriginalPlaybackSettingsRootHost(
    routes: DesktopOriginalRootRouteAssembly,
    handleReference: AtomicReference<DesktopReadyOriginalRootHandle?>,
    entryKey: BiliPaiNavKey,
    page: DesktopSettingsPage.Detail,
    navigator: DesktopSettingsNavigator,
    pluginContext: DesktopPluginContext,
    repository: DesktopRepository,
    imageLifetime: DesktopImageSaveLifetime,
    homeSettings: StateFlow<HomeSettings>,
    hardwareDecode: DesktopOriginalHardwareDecodePreferences,
    legacyHardwareDecodeFallback: Boolean,
    active: Boolean,
    pictureInPictureAvailable: () -> Boolean,
    onFailure: (Throwable) -> Unit,
    onNotice: (String) -> Unit,
    logSettingChange: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val pageIdentity = PlaybackSettingsPageIdentity(page)
    val compositionScope = rememberCoroutineScope()
    val pageJob = remember(routes, pageIdentity) { SupervisorJob(compositionScope.coroutineContext[Job]) }
    val pageScope = remember(pageJob) { CoroutineScope(compositionScope.coroutineContext + pageJob) }
    DisposableEffect(pageJob) { onDispose { pageJob.cancel() } }
    val activeNow by rememberUpdatedState(active)
    val failureNow by rememberUpdatedState(onFailure)
    val noticeNow by rememberUpdatedState(onNotice)
    val pipNow by rememberUpdatedState(pictureInPictureAvailable)
    val logNow by rememberUpdatedState(logSettingChange)
    val backNow by rememberUpdatedState(onBack)
    val capturedHandle = remember(routes, pageIdentity) { requireNotNull(handleReference.get()) }
    val current = {
        pageScope.isActive && activeNow && handleReference.get() === capturedHandle &&
            capturedHandle.isActive() && capturedHandle.route.get() === routes && routes.owns() &&
            routes.currentKey == entryKey && navigator.state.value.current === page && imageLifetime.isActive()
    }
    val commit = remember(routes, pageIdentity, capturedHandle, imageLifetime) {
        { action: () -> Unit ->
            var applied = false
            val accepted = routes.root.entry.gate.commit {
                // Only permit creation/short identity reads. Stage/fsync/CAS stay outside.
                imageLifetime.withCommit { if (current()) { action(); applied = true } }
            }
            accepted && applied
        }
    }
    val context = remember(routes, pageIdentity, capturedHandle, pluginContext, imageLifetime) {
        DesktopOriginalPlayerSettingsContext(pluginContext, current, commit,
            largeScreenOrFoldableConfiguration = {
                com.android.purebilibili.core.util.resolveLargeScreenOrFoldableConfiguration(
                    capturedHandle.preferencePlatform.deviceDefault.smallestConfigurationWidthDp,
                    hasHingeAngleSensor = false)
            }, isDebugBuild = { DesktopOriginalWindowsBuildPolicy.debuggerAttached() })
    }
    val readbacks = remember(context, repository) {
        DesktopOriginalVideoEntryReadbacks(repository, routes.root.entry.gate.epoch,
            pageScope, current, commit)
    }
    var initialState by remember(context) { mutableStateOf<DesktopOriginalPlaybackSettingsState?>(null) }
    var preparationFailed by remember(context) { mutableStateOf(false) }
    var retry by remember(context) { mutableIntStateOf(0) }
    LaunchedEffect(context, retry) {
        preparationFailed = false
        try {
            // Do not present the Android default for one frame before old Windows values
            // have migrated. Initial values come from the same original Flow functions.
            hardwareDecode.ensureMigrated(context, legacyHardwareDecodeFallback)
            val initial = withContext(Dispatchers.IO) {
                DesktopOriginalPlaybackSettingsState(
                    DesktopOriginalPlaybackSettingsPreferences.getHwDecode(context).first(),
                    DesktopOriginalPlaybackSettingsPreferences.getGestureSensitivity(context).first(),
                    DesktopOriginalPlaybackSettingsPreferences.getHeaderBlurEnabled(context).first(),
                    DesktopOriginalPlaybackSettingsPreferences.getAndroidNativeLiquidGlassEnabled(context).first(),
                    DesktopOriginalPlaybackSettingsPreferences.getAutoSkipOpEd(context).first(),
                    DesktopOriginalPlaybackSettingsPreferences.getDoubleTapLike(context).first())
            }
            ensureActive()
            if (current()) initialState = initial
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (current()) { preparationFailed = true; failureNow(failure) } }
    }
    // The local settings Detail is inside an actual NavDisplay entry. Register its same
    // original Back callback with that entry's existing child dispatcher before physical pop.
    val ownedBack: () -> Unit = {
        routes.callbackFor(entryKey) { if (current()) backNow() }
    }
    val backState = androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None)
    androidx.navigationevent.compose.NavigationBackHandler(state = backState,
        isBackEnabled = current(), onBackCompleted = ownedBack)
    val initial = initialState
    if (initial != null) {
        val bindings = remember(context, pageScope, initial) {
            DesktopOriginalPlaybackSettingsBindings(context, pageScope, initial, homeSettings,
                current, commit,
                onFailure = { failure -> java.awt.EventQueue.invokeLater { if (current()) failureNow(failure) } },
                onNotice = { message -> java.awt.EventQueue.invokeLater { if (current()) noticeNow(message) } },
                pictureInPicture = { pipNow() }, playbackLoggedIn = readbacks::isPlaybackLoggedIn,
                playbackVip = readbacks::isPlaybackVip, settingChange = { name, value -> logNow(name, value) })
        }
        CompositionLocalProvider(LocalDesktopOriginalPlayerSettingsContext provides context) {
            key(context) {
                DesktopWindowsPlaybackSettings(bindings, onBack = ownedBack)
            }
        }
    } else {
        com.android.purebilibili.feature.settings.ui.SettingsPageScaffold(
            title = "播放与画质", onBack = ownedBack,
            backContentDescription = "返回", bottomContentPadding = 0.dp) {
            com.android.purebilibili.core.ui.components.AppText(
                if (preparationFailed) "播放设置准备失败，请重试。" else "正在准备播放设置…")
            if (preparationFailed) com.android.purebilibili.core.ui.components.AppTextButton(
                onClick = { if (current()) retry++ }) {
                com.android.purebilibili.core.ui.components.AppText("重试")
            }
        }
    }
}

/** Detail is an original value class; repeated equal destinations still own distinct jobs. */
private class PlaybackSettingsPageIdentity(private val page: DesktopSettingsPage.Detail) {
    override fun equals(other: Any?) = other is PlaybackSettingsPageIdentity && other.page === page
    override fun hashCode() = System.identityHashCode(page)
}
