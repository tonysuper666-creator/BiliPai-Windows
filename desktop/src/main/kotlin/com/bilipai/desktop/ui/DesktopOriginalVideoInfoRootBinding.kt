package com.bilipai.desktop.ui

import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.plugins.DesktopPluginContext

/** Reuses the detail's existing operations, image/comment/card platform and global context. */
internal class DesktopOriginalVideoInfoRootBinding(
    override val context: DesktopPluginContext,
    private val operations: DesktopDynamicCardOperations,
    private val platform: DesktopDynamicCardPlatform,
) : DesktopOriginalVideoInfoBindings {
    override suspend fun getCreatorCardStats(mid: Long) = operations.getVideoCreatorCardStats(mid)
    override fun copyText(text: String, label: String) {
        if (operations.isOwned() && platform.isOwned()) platform.copyText(text)
    }
    override fun showFeedback(message: String) {
        if (operations.isOwned() && platform.isOwned()) platform.showFeedback(message)
    }
}
