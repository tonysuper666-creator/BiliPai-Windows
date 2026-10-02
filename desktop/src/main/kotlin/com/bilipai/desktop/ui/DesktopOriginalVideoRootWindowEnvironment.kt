package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import com.bilipai.desktop.settings.*
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.awt.Window

/** Read-only references to the physical ReadyRoot's actual resources. No second
 * Home VM, settings model, palette, HWND policy, image actor or navigation stack
 * is constructed for the ordinary video. The captured Home gate remains the
 * recommendation/TodayWatch authority independently of the ordinary lifetime.
 */
internal class DesktopOriginalVideoRootWindowEnvironment(
    val root: DesktopHomeRetainedRoot,
    val window: Window,
    val scope: CoroutineScope,
    val resources: DesktopHomeActualWindowResources,
    val preferencesPlatform: DesktopHomeWindowsPreferencesPlatform,
    val configuration: StateFlow<DesktopProfileWindowConfiguration>,
    val chrome: DesktopWindowsProfileChrome,
    val haze: HazeState,
    val settings: DesktopHomeSettingsPort,
    val appearance: DesktopThemePrefs,
    val repository: DesktopRepository,
    val runtime: DesktopPluginRuntime,
    val navigation: DesktopRootWindowNavigationOwner,
    val commands: DesktopOriginalRootRouteCommands,
    val imageLocations: DesktopImageSaveLocations,
    val imageLifetime: DesktopImageSaveLifetime,
    val inPictureInPicture: () -> Boolean,
    val currentKey: () -> com.android.purebilibili.navigation3.BiliPaiNavKey,
) {
    val gallery: DesktopHomeGalleryBindings get() =
        (root.entry.embeddedPages as DesktopOriginalHomeEmbeddedAggregate).gallery
    fun owns() = root.isCurrentOwner() && navigation.owns() && imageLifetime.isActive()
}

internal val LocalDesktopOriginalVideoRootWindowEnvironment = androidx.compose.runtime.staticCompositionLocalOf<DesktopOriginalVideoRootWindowEnvironment> {
    error("Ordinary video requires the actual retained Home/Root Window environment")
}
