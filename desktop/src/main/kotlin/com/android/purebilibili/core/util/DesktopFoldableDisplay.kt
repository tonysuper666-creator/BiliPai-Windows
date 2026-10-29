package com.android.purebilibili.core.util

import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.ui.DesktopOriginalSettingsAndroidContext

/** Desktop implementation of the function upstream's desktop-owned FoldableDisplayPolicy.kt exports: the large-screen
 * default is Root's actual physical monitor answer, held by the page's settings owner. */
internal fun android.content.Context.isLargeScreenOrFoldableConfiguration(): Boolean =
    (this as? DesktopOriginalSettingsAndroidContext)?.isLargeScreenOrFoldableConfiguration()
        ?: throw UnsupportedDesktopCapability("isLargeScreenOrFoldableConfiguration on ${this::class.java.name}",
            "only a page's settings owner (LocalContext) knows the actual monitor")
