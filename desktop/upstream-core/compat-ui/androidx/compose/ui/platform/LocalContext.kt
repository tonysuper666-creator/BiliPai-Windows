package androidx.compose.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop LocalContext for upstream app code: the Android Context of the settings owner the desktop page already
 * provides (LocalDesktopOriginalPlayerSettingsContext), so upstream stores read and write Root's settings document
 * with that page's write admission. A composition without a settings owner fails with that local's own error instead
 * of reading a detached default store.
 */
object LocalContext {
    val current: android.content.Context
        @Composable get() {
            val settings = com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext.current
            return remember(settings) { com.bilipai.desktop.ui.DesktopOriginalSettingsAndroidContext(settings) }
        }
}
