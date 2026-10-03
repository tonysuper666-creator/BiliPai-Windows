package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.compose.LocalPlatformContext
import com.android.purebilibili.core.store.DesktopOriginalHomeSettingsManager
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.feature.settings.DesktopOriginalHomeSettingsContent
import com.android.purebilibili.feature.settings.DesktopOriginalHomeSettingsViewModel
import com.android.purebilibili.core.theme.LocalSettingsLiquidGlassEnabled
import com.android.purebilibili.feature.settings.SettingsPageScrollHost
import com.android.purebilibili.feature.settings.ui.SettingsPageScaffold
import com.bilipai.desktop.appearance.LocalDesktopStrings
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/** Caller supplies exact settings Detail object identity and the actual Root Source->Image
 * bounded admission. Physical/local Back stays the caller's existing route transaction.
 * All payload IO, preference staging and native chooser pumping remain outside admission.
 */
@Composable
internal fun DesktopOriginalHomeSettingsRootHost(
    entryIdentity: Any,
    services: DesktopOriginalHomeSettingsRootServices,
    pluginContext: DesktopPluginContext,
    owns: () -> Boolean,
    admit: ((() -> Unit) -> Boolean),
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
    onFailure: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageIdentity = HomeSettingsPageIdentity(entryIdentity)
    key(pageIdentity, services, pluginContext) {
        val scope = rememberCoroutineScope()
        val ownsNow by rememberUpdatedState(owns)
        val backNow by rememberUpdatedState(onBack)
        val noticeNow by rememberUpdatedState(onNotice)
        val failureNow by rememberUpdatedState(onFailure)
        val alive = remember { AtomicBoolean(true) }
        val current = { alive.get() && scope.coroutineContext[Job]?.isActive == true && ownsNow() }
        val context = remember(pageIdentity, services, pluginContext) {
            DesktopOriginalPlayerSettingsContext(pluginContext, current, admit)
        }
        val actions = remember(scope, context) {
            DesktopOriginalHomeSettingsActions(scope, context, current,
                { if (current()) noticeNow(it) }, { if (current()) failureNow(it) })
        }
        val environment = remember(pageIdentity, services, context, scope) {
            services.createProfileEnvironment(scope, current, admit) { uri ->
                // This is the original HOME target's only preference write. The shared final
                // publisher stages/fsyncs outside admission and rejects late page/Root owners.
                DesktopOriginalPlaybackPreferenceOperation.run(context, current) {
                    DesktopOriginalHomeSettingsManager.setHomeWallpaperUri(context, uri)
                }
            }
        }
        DisposableEffect(alive) { onDispose { alive.set(false) } }
        val state by services.home.homeSettings.collectAsState()
        val imageContext = LocalPlatformContext.current
        val actualWindowSizeClass = LocalWindowSizeClass.current
        val platform = LocalDesktopHomePlatform.current
        val strings = LocalDesktopStrings.current
        val ownedBack: () -> Unit = { if (current()) backNow() }
        val navigationBackState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(state = navigationBackState, isBackEnabled = current(), onBackCompleted = ownedBack)
        val viewModel = remember(context, actions) { DesktopOriginalHomeSettingsViewModel(context, actions) }
        val bottomContentPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        SettingsPageScaffold(
            title = "首页设置",
            onBack = ownedBack,
            backContentDescription = strings["common_back"],
            bottomContentPadding = bottomContentPadding,
            scrollHost = SettingsPageScrollHost.External,
            externalContentHandlesTopPadding = true,
            topBarBlurEnabled = state.isHeaderBlurEnabled,
        ) {
            CompositionLocalProvider(
                LocalSettingsLiquidGlassEnabled provides (state.androidNativeLiquidGlassEnabled && platform.supportsHomeChromeLiquidGlass),
                LocalDesktopOriginalPlayerSettingsContext provides context,
                LocalDesktopProfileEnvironment provides environment,
            ) {
                DesktopOriginalHomeSettingsContent(modifier, state, viewModel, context, imageContext,
                    actions, services.backToTop, actualWindowSizeClass)
            }
        }
    }
}

/** Equal-valued Detail destinations still represent distinct mounted page lifetimes. */
private class HomeSettingsPageIdentity(private val page: Any) {
    override fun equals(other: Any?) = other is HomeSettingsPageIdentity && other.page === page
    override fun hashCode() = System.identityHashCode(page)
}
