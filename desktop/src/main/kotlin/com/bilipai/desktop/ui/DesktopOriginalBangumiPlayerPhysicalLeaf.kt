package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.bangumi.BangumiPlayerScreen
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.CancellationException

/** Complete original physical Player leaf on the existing retained Assembly.
 * Exact original NavKey parameters are consumed here, with the shared comment,
 * Window/Section/settings/share bindings. No browser or substitute player. */
@Composable internal fun DesktopOriginalBangumiPlayerPhysicalLeaf(
    key: BiliPaiNavKey.BangumiPlayer,
    shell: DesktopOriginalVideoShellOwner,
    routes: DesktopOriginalRootRouteAssembly,
    active: Boolean,
    openBilibiliLink: (String) -> Unit,
    pendingOwner: @Composable () -> Unit,
) {
    val factoryReady by shell.slot.factoryReady.collectAsState()
    LaunchedEffect(shell, factoryReady, key) { if (factoryReady) shell.slot.requireAssembly() }
    val owner by shell.slot.assemblies.collectAsState()
    val platforms = LocalDesktopOriginalVideoRootPlatforms.current
    val current = owner?.takeIf { it.owns() }
    if (current == null || platforms == null) { pendingOwner(); return }
    val pgc = platforms.bangumiPlayer
    val entry by pgc.entries.collectAsState()
    var initialized by remember(current, platforms, key) { mutableStateOf(false) }
    LaunchedEffect(current, platforms, key) {
        platforms.awaitNativeInitialization()
        if (!current.owns() || !routes.containsEntry(key)) throw CancellationException("Original PGC leaf retired")
        pgc.open(key)
        initialized = true
    }
    if (!initialized) {
        CompositionLocalProvider(LocalDesktopOriginalVideoNativeCarrierActive provides active) {
            platforms.InitialNativeSurface(Modifier.fillMaxSize())
        }
        return
    }
    val bound = entry?.takeIf { it.key == key && it.owns() }
    if (bound == null) {
        com.android.purebilibili.core.ui.components.AppText("播放已切换，请重新打开此剧集")
        return
    }
    fun navigate(action: () -> Unit) { if (active && bound.owns()) routes.callbackFor(key, action) }
    CompositionLocalProvider(
        LocalDesktopOriginalVideoNativeCarrierActive provides active,
        LocalDesktopOriginalBangumiPlayerScreenPlatform provides bound,
        LocalDesktopOriginalBangumiPlayerShare provides bound.share,
        LocalDesktopOriginalPlayerSettingsContext provides bound.section.settingsContext,
        LocalDesktopOriginalVideoSectionPlatform provides bound.section,
        LocalDesktopCommentBindings provides platforms.holder.commentsPlatform,
    ) {
        DesktopDetailWindow(precisePointerConnected = true, hardwareKeyboardConnected = true) {
            bound.section.RenderPlayerForeground {
                if (bound.owns()) BangumiPlayerScreen(
                    seasonId = key.seasonId, epId = key.epId, resumePositionMs = key.resumePositionMs,
                    isCourse = key.isCourse, preferredAid = key.preferredAid,
                    onBack = { navigate { routes.back() } },
                    onNavigateToLogin = { navigate { routes.push(BiliPaiNavKey.Login) } },
                    onUserClick = { mid -> navigate { routes.push(BiliPaiNavKey.Space(mid)) } },
                    onOpenBilibiliLink = { url -> navigate { openBilibiliLink(url) } },
                    viewModel = bound.viewModel, commentViewModel = bound.comments)
            }
        }
    }
}
