package com.bilipai.desktop.settings
import androidx.compose.runtime.Composable
import com.android.purebilibili.feature.settings.*

/** Entire original navigation editor and applicable animation controls use Root's global context.
 * Tree remains the sole category/detail owner; each original finite LazyColumn owns detail scroll. */
@Composable
internal fun DesktopNavigationInteractionSettings(target:SettingsSearchTarget,onFailure:(Throwable)->Unit) {
    when(target) {
        SettingsSearchTarget.BOTTOM_BAR -> DesktopOriginalBottomBarSettingsContent(onFailure=onFailure)
        SettingsSearchTarget.ANIMATION -> DesktopOriginalAnimationSettingsContent(onFailure=onFailure)
        else -> error("Unsupported navigation detail")
    }
}

/** Expanded original sections now genuinely exist, so their original focus tokens can be consumed. */
internal fun desktopNavigationInteractionFocusIndex(target:SettingsSearchTarget,focusId:String):Int?=when(target) {
    SettingsSearchTarget.BOTTOM_BAR -> resolveBottomBarSettingsScrollIndex(focusId)
    SettingsSearchTarget.ANIMATION -> resolveAnimationSettingsScrollIndex(focusId)
    else -> null
}
