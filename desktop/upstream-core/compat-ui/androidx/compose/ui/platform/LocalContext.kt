package androidx.compose.ui.platform

import androidx.compose.runtime.Composable

/**
 * Desktop LocalContext for upstream app code: the Android Context the window root provides
 * (LocalDesktopUpstreamAndroidContext), whose settings_prefs is Root's settings document. Upstream UI code reads
 * settings through it; desktop writes pass their page's own Context (DesktopOriginalPlayerSettingsContext.androidContext).
 * A composition outside the root fails loudly instead of reading a detached default store.
 */
object LocalContext {
    val current: android.content.Context
        @Composable get() = checkNotNull(com.bilipai.desktop.ui.LocalDesktopUpstreamAndroidContext.current) {
            "Upstream code needs the window root's LocalDesktopUpstreamAndroidContext"
        }
}
