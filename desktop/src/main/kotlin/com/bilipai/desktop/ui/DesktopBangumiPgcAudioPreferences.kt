package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings

/** The original PGC VM reads these original cached getters on every load. Root startup seeds
 * both canonical keys and these mirrors atomically from its failure-aware legacy read, before
 * constructing video resources. This port has no migration, source authority or local cache. */
internal class DesktopBangumiPgcAudioPreferences(private val settings: DesktopOriginalPlayerSettingsContext) {
    fun defaultQuality(): Int = DesktopOriginalVideoPlayerSettings.getCachedDefaultAudioQuality(settings)
    fun rememberedQuality(): Int = DesktopOriginalVideoPlayerSettings.getCachedLastSelectedAudioQuality(settings)
}
