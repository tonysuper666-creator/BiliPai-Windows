package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** The actual NavDisplay/Shell selects one native carrier owner in this frame.
 * Required at bootstrap and full Section; absence must fail instead of guessing
 * a background page is active. Outgoing leaves keep original Effects/content.
 */
internal val LocalDesktopOriginalVideoNativeCarrierActive = staticCompositionLocalOf<Boolean> {
    error("Original native carrier requires the actual physical route-active provider")
}
