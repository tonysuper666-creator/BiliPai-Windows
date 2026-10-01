package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import kotlinx.coroutines.flow.Flow

/** Read projection, not persisted state: values must come from Root's global settings. */
data class DesktopFavoriteHomeAppearance(
    val androidNativeLiquidGlassEnabled: Boolean,
    val cardAnimationEnabled: Boolean,
    val cardTransitionEnabled: Boolean,
    val pinchToChangeGridColumnsEnabled: Boolean,
    val commonListHeaderCollapseMode: CommonListHeaderCollapseMode,
    val isBottomBarSearchEnabled: Boolean,
    val listScopedSearchEnabled: Boolean,
    val homeDurationStyle: HomeDurationStyle,
    val headerBlurMode: HomeHeaderBlurMode,
    val isBottomBarBlurEnabled: Boolean,
)
data class DesktopFavoriteNavigationAppearance(val bottomBarVisibilityMode: DesktopFavoriteNavigationTypes.BottomBarVisibilityMode)
class DesktopFavoriteQueueToken(val owner: Any)
class DesktopFavoriteBindings(
    val showOnlineCount: Flow<Boolean>,
    val homeSettings: Flow<DesktopFavoriteHomeAppearance>,
    val initialHomeSettings: DesktopFavoriteHomeAppearance,
    val navigationSettings: Flow<DesktopFavoriteNavigationAppearance>,
    val initialNavigationSettings: DesktopFavoriteNavigationAppearance,
    val categoryViewModel: com.android.purebilibili.feature.list.FavoriteCategoryViewModel,
    /** Root opens its owned video or Listen queue and applies original SEQUENTIAL mode.
     * The following original navigation callback must retain this selected queue owner. */
    val openQueue: (List<PlaylistItem>, Int, Boolean) -> DesktopFavoriteQueueToken?,
    private val appendQueue: (List<PlaylistItem>, DesktopFavoriteQueueToken) -> Unit,
    private val share: (String,String,String) -> Unit,
    val homeFeedCardStyle:Flow<HomeFeedCardStyle>,
    val hazeEffectSupported:Boolean,
    val backToTopEnabled:Flow<Boolean>,
    val initialBackToTopEnabled:Boolean,
    val backToTopOffset:Flow<Pair<Float,Float>>,
    val initialBackToTopOffset:Pair<Float,Float>,
    val setBackToTopOffset:suspend(Float,Float)->Unit,
    val updateBackToTopOffset:(Float,Float)->Unit,
) {
    fun appendQueueIfCurrent(items:List<PlaylistItem>,session:DesktopFavoriteQueueToken)=appendQueue(items,session)
    fun shareText(context:Any?,subject:String,text:String,chooserTitle:String)=share(subject,text,chooserTitle)
}
val LocalDesktopFavoriteBindings=staticCompositionLocalOf<DesktopFavoriteBindings> {
    error("Favorites requires the current Root owner, shared settings, queue and share binding")
}
data class DesktopFavoriteViewport(val screenWidthDp:Int,val screenHeightDp:Int)
val LocalDesktopFavoriteViewport=staticCompositionLocalOf<DesktopFavoriteViewport> {
    error("Favorites requires the actual measured window viewport")
}
