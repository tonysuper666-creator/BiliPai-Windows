package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.store.AppNavigationSettings
import com.android.purebilibili.feature.video.player.PlaylistItem
import kotlinx.coroutines.flow.StateFlow

/** These are the same already-retained global Home preferences and concrete queue actor;
 * no default model or fake supported flag is provided. */
internal class DesktopWatchLaterBindings(
    val homeSettings: StateFlow<HomeSettings>,
    val navigationSettings: StateFlow<AppNavigationSettings>,
    val renderEffectsSupported: Boolean,
    private val queueStart: (List<PlaylistItem>, Int, Boolean, Long) -> DesktopFavoriteQueueToken?,
) {
    /** Original API rows carry CID/progress outside PlaylistItem. Forward those immutable
     * fields into the existing same queue's initial load before the original reveal callback. */
    fun openQueue(items: List<PlaylistItem>, index: Int, audio: Boolean,
        rows: List<com.android.purebilibili.data.model.response.VideoItem>): DesktopFavoriteQueueToken? {
        if (index !in items.indices) return null
        val selected = com.android.purebilibili.feature.watchlater.resolveWatchLaterPlaybackTarget(rows, items[index].bvid) ?: return null
        val playlist = items.map { item ->
            val target = com.android.purebilibili.feature.watchlater.resolveWatchLaterPlaybackTarget(rows, item.bvid) ?: return null
            item.copy(cid = target.cid)
        }
        return queueStart(playlist, index, audio, selected.resumePositionMs)
    }
    val initialHomeSettings: HomeSettings get() = homeSettings.value
    val initialNavigationSettings: AppNavigationSettings get() = navigationSettings.value
    /** The existing sole original History-filter policy is compiled with Favorites'
     * settings view. Project its exact fields from the current full global Home model. */
    fun filterAppearance(value: HomeSettings) = DesktopFavoriteHomeAppearance(
        value.androidNativeLiquidGlassEnabled, value.cardAnimationEnabled,
        value.cardTransitionEnabled, value.pinchToChangeGridColumnsEnabled,
        value.commonListHeaderCollapseMode, value.isBottomBarSearchEnabled,
        value.listScopedSearchEnabled, value.homeDurationStyle,
        value.headerBlurMode, value.isBottomBarBlurEnabled,
    )
}
internal val LocalDesktopWatchLaterBindings = staticCompositionLocalOf<DesktopWatchLaterBindings> {
    error("WatchLater requires the current global Home settings, effects capability and owned queue actor")
}
