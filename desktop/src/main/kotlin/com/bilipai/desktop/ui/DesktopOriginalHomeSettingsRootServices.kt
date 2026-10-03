package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope

/** Same actual Root services. The factory creates page-owned platform effects and keeps the
 * existing Root APIs, store, gallery assets, native media lifetime and Windows window.
 * It does not construct another Profile VM or account/player/client authority.
 */
internal class DesktopOriginalHomeSettingsRootServices(
    val home: DesktopHomeSettingsPort,
    val backToTop: DesktopFavoritePreferences,
    val notice: (String) -> Unit,
    val createProfileEnvironment: (
        scope: CoroutineScope,
        owns: () -> Boolean,
        admit: ((() -> Unit) -> Boolean),
        writeHomeWallpaper: suspend (String) -> Unit,
    ) -> DesktopProfileEnvironment,
)

internal val LocalDesktopOriginalHomeSettingsRootServices = staticCompositionLocalOf<DesktopOriginalHomeSettingsRootServices> {
    error("Actual Root Home settings services are required")
}
