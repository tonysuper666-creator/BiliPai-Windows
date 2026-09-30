package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Retained by the window for one account; navigation never owns the loaded pages. */
class DesktopBrowseMemory {
    internal val feeds = CommunityFeedMemory()
    private val screens = linkedMapOf<Any?, Any>()
    fun invalidateCloudHistory() = feeds.invalidate(PersonalSection.HISTORY)

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> screen(key: Any?, create: () -> T): T = screens.getOrPut(key) {
        if (screens.size >= 48) screens.remove(screens.keys.first())
        create()
    } as T
}

val LocalDesktopBrowseMemory = staticCompositionLocalOf<DesktopBrowseMemory?> { null }
