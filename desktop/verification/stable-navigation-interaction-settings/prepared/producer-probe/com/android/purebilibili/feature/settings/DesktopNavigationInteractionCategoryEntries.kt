package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
/** Exact original NAVIGATION_INTERACTION two entry groups; existing Tree owns scrolling. */
@Composable
internal fun SettingsNavigationInteractionCategoryEntrySection(onBottomBarClick:()->Unit,onAnimationClick:()->Unit) {
    SettingsDetailGroup(title = "导航") {
            SettingsDetailEntrySection(
                entries = listOf(
                    SettingsDetailEntry(
                        target = SettingsSearchTarget.NAVIGATION,
                        title = settingsDestinationCopy(SettingsSearchTarget.BOTTOM_BAR).title,
                        value = settingsDestinationCopy(SettingsSearchTarget.BOTTOM_BAR).summary,
                        openFocus = SettingsSceneDetailFocus(
                            SettingsSearchTarget.BOTTOM_BAR,
                            SettingsSearchFocusIds.BOTTOM_BAR_START,
                        ),
                        onClick = onBottomBarClick,
                    ),
                ),
            )
        }

    Spacer(modifier = Modifier.height(12.dp))

        SettingsDetailGroup(title = "交互与动效") {
            SettingsDetailEntrySection(
                entries = listOf(
                    SettingsDetailEntry(
                        target = SettingsSearchTarget.ANIMATION,
                        title = settingsDestinationCopy(SettingsSearchTarget.ANIMATION).title,
                        value = settingsDestinationCopy(SettingsSearchTarget.ANIMATION).summary,
                        openFocus = SettingsSceneDetailFocus(
                            SettingsSearchTarget.ANIMATION,
                            SettingsSearchFocusIds.ANIMATION_START,
                        ),
                        onClick = onAnimationClick,
                    ),
                ),
            )
        }
}
