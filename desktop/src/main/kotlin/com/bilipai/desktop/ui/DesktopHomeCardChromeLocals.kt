package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.android.purebilibili.core.store.DesktopHomeCardVisualSettings
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.ui.LocalFullVideoCardContentVisible
import com.android.purebilibili.core.ui.LocalUpBadgeVisibility
import com.android.purebilibili.core.ui.LocalVideoCardLongPressEnabled
import com.android.purebilibili.core.ui.UpBadgeVisibility
import com.android.purebilibili.feature.home.components.cards.LocalHomeCardDynamicTintEnabled
import com.android.purebilibili.feature.home.components.cards.LocalHomeCardFrostedGlassEnabled

/**
 * The five home-card locals [com.android.purebilibili.navigation.AppNavigation] provides.
 * Their static defaults are off, so a missing provider disables cover sampling, frosted
 * card alpha, full titles, long-press, and the UP badge fallback.
 */
internal data class DesktopHomeCardChromeLocals(
    val dynamicTintEnabled: Boolean,
    val frostedGlassEnabled: Boolean,
    val showUpBadges: Boolean,
    val showUpAvatars: Boolean,
    val showFullVideoCardContent: Boolean,
    val longPressEnabled: Boolean,
)

internal fun resolveDesktopHomeCardChromeLocals(settings: HomeSettings) = DesktopHomeCardChromeLocals(
    dynamicTintEnabled = settings.homeCardDynamicTintEnabled,
    frostedGlassEnabled = settings.homeCardFrostedGlassEnabled,
    showUpBadges = settings.showHomeUpBadges,
    showUpAvatars = settings.showHomeUpAvatars,
    showFullVideoCardContent = settings.showFullVideoCardContent,
    longPressEnabled = settings.videoCardLongPressActionEnabled,
)

internal fun resolveDesktopHomeCardChromeLocals(settings: DesktopHomeCardVisualSettings) = DesktopHomeCardChromeLocals(
    dynamicTintEnabled = settings.homeCardDynamicTintEnabled,
    frostedGlassEnabled = settings.homeCardFrostedGlassEnabled,
    showUpBadges = settings.showHomeUpBadges,
    showUpAvatars = settings.showHomeUpAvatars,
    showFullVideoCardContent = settings.showFullVideoCardContent,
    longPressEnabled = settings.videoCardLongPressActionEnabled,
)

@Composable
internal fun ProvideDesktopHomeCardChromeLocals(
    locals: DesktopHomeCardChromeLocals,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalHomeCardDynamicTintEnabled provides locals.dynamicTintEnabled,
        LocalHomeCardFrostedGlassEnabled provides locals.frostedGlassEnabled,
        LocalUpBadgeVisibility provides UpBadgeVisibility(locals.showUpBadges, locals.showUpAvatars),
        LocalFullVideoCardContentVisible provides locals.showFullVideoCardContent,
        LocalVideoCardLongPressEnabled provides locals.longPressEnabled,
        content = content,
    )
}
