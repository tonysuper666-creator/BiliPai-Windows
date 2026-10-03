package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.data.model.response.AicuCategory
import com.android.purebilibili.feature.aicu.AicuRoute
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.appearance.LocalDesktopTextClipboard
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

/** Required leaf ports from the actual Root; no fallback settings, protocol or navigation. */
internal class DesktopAicuBindings(
    val settingsContext: DesktopOriginalPlayerSettingsContext,
    val homeSettings: StateFlow<HomeSettings>,
    val emoteMap: suspend () -> Map<String, String>,
    val copyText: (String) -> Unit,
)
internal val LocalDesktopAicuBindings = staticCompositionLocalOf<DesktopAicuBindings> {
    error("Original Aicu route requires actual Root bindings")
}

@Composable internal fun DesktopOriginalAicuRootHost(
    key: BiliPaiNavKey.AicuQuery,
    routes: DesktopOriginalRootRouteAssembly,
    repository: DesktopRepository,
) {
    val clipboard = LocalDesktopTextClipboard.current
    val owned = remember(routes, key) { { routes.owns() && routes.containsEntry(key) } }
    val context = remember(routes, key) {
        DesktopOriginalPlayerSettingsContext(routes.root.environment.pluginContext, owned, { block ->
            var applied = false
            val accepted = routes.root.entry.gate.commit { if (owned()) { block(); applied = true } }
            accepted && applied
        })
    }
    val operations = remember(routes, key, repository) {
        DesktopDynamicCardOperations(repository, routes.root.entry.gate.epoch, owned)
    }
    val emotes = remember(operations) { DesktopOriginalAicuEmotes(operations::getBgmEmotePackages) }
    val bindings = remember(routes, key, context, emotes, clipboard) {
        DesktopAicuBindings(context, routes.root.environment.settings.homeSettings, {
            currentCoroutineContext().ensureActive()
            if (!owned()) throw CancellationException("Aicu Root entry retired")
            emotes.getEmoteMap().also {
                currentCoroutineContext().ensureActive()
                if (!owned()) throw CancellationException("Aicu Root entry retired")
            }
        }, { text -> routes.callbackFor(key) {
            if (!clipboard.copyText(text)) routes.root.environment.feedback("无法写入系统剪贴板，请稍后重试")
        } })
    }
    if (owned()) CompositionLocalProvider(LocalDesktopAicuBindings provides bindings) {
        AicuRoute(uid = key.uid.takeIf { it > 0L }, initialCategory = AicuCategory.fromRoute(key.category),
            onBack = { routes.callbackFor(key) { routes.back() } },
            onOpenTarget = { target -> routes.callbackFor(key) { routes.push(target) } })
    }
}
