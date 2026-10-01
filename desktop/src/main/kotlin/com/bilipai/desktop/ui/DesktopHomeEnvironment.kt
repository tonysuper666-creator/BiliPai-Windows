package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.home.components.cards.WallpaperPalette
import com.android.purebilibili.feature.video.share.VideoSharePayload
import com.android.purebilibili.data.model.response.VideoItem
import com.bilipai.desktop.plugins.DesktopPluginContext
import dev.chrisbanes.haze.HazeState

/** One Root's actual global-store settings projection, ready before first composition.
 * The complete ORIGINAL mapHomeSettingsFromPreferences must decode values; constructor defaults
 * alone are different (notably hero carousel and consent) and are not a persistence adapter. */
interface DesktopHomeSettingsPort {
 val homeSettings:StateFlow<HomeSettings>
 val topTabs:StateFlow<HomeTopTabSettings>
 val navigation:StateFlow<AppNavigationSettings>
 val showOnlineCount:StateFlow<Boolean>
 val homeFeedCardStyle:StateFlow<HomeFeedCardStyle>
 val homeWallpaperUri:StateFlow<String>
 val splashWallpaperUri:StateFlow<String>
 fun isDataSaverActive():Boolean
 suspend fun setEasterEggEnabled(enabled:Boolean)
 suspend fun setTabletUseSidebar(enabled:Boolean)
 suspend fun setGridColumnCountCompact(columns:Int)
 suspend fun setGridColumnCount(columns:Int)
}
interface DesktopHomeGlobalNamespace { fun getBoolean(key:String,default:Boolean):Boolean }
interface DesktopHomeAnalyticsPort { fun logScreenView(name:String); fun logCategoryView(categoryName:String,categoryId:Int)
 fun logHomeReturnAnimationPerformance(actualDurationMs:Long,plannedSuppressionMs:Long,sharedTransitionEnabled:Boolean,sharedTransitionReady:Boolean,isQuickReturn:Boolean,isTabletLayout:Boolean,cardAnimationEnabled:Boolean,builtinPluginEnabledCount:Int,playerPluginEnabledCount:Int,feedPluginEnabledCount:Int,danmakuPluginEnabledCount:Int,jsonPluginEnabledCount:Int,jsonFeedPluginEnabledCount:Int,jsonDanmakuPluginEnabledCount:Int) }
/** Embedded pages are the existing Root's single data/epoch owners, not fake Home-specific repositories. */
interface DesktopHomeEmbeddedPages {
 @Composable fun PartitionContent(contentPadding:PaddingValues,onVideoClick:(VideoItem)->Unit,onBangumiClick:(Int)->Unit,scrollToTopRequestId:Int)
 @Composable fun SubscriptionFeedPage(contentPadding:PaddingValues,articleContentPadding:PaddingValues,scrollToTopRequestId:Int,listState:LazyStaggeredGridState,gridColumns:Int,pinchEnabled:Boolean,pinchBounds:IntRange,onColumnsChange:(Int)->Unit,onPinchEnd:(Int)->Unit,onArticleOpenChanged:(Boolean)->Unit,onOpenPluginSettings:()->Unit)
 @Composable fun LiveListScreen(onBack:()->Unit,onLiveClick:(Long,String,String)->Unit,onSearchClick:()->Unit,onAreaListClick:()->Unit,onFollowingClick:()->Unit,onAreaDetailClick:(Int,Int,String)->Unit,showNavigationBack:Boolean,embeddedInHome:Boolean,contentTopPadding:Dp,scrollToTopRequestId:Int)
 @Composable fun HomeBangumiTabPage(contentPadding:PaddingValues,onBangumiClick:(Long)->Unit,onBangumiEpisodeClick:(Long,Long)->Unit,scrollToTopRequestId:Int)
}
/** All effects are required real Root ports. The Home page owns no client/settings/account store. */
internal class DesktopHomeEnvironment(
 val settings:DesktopHomeSettingsPort,
 val pluginContext:DesktopPluginContext,
 val lifecycleOwner:LifecycleOwner,
 val subscriptionPluginEnabled:StateFlow<Boolean>,
 val analytics:DesktopHomeAnalyticsPort,
 val globalNamespace:(String)->DesktopHomeGlobalNamespace,
 val wallpaperPalette:StateFlow<WallpaperPalette?>,
 val loadWallpaperPalette:(String,CoroutineScope)->Unit,
 val ensureEdgeToEdge:()->Unit,
 val applyHomeSystemBars:(Boolean,Boolean)->Unit,
 val pages:DesktopHomeEmbeddedPages,
 val saveCover:suspend(String,String)->Boolean,
 val feedback:(String)->Unit,
 val overlays:DesktopHomeOverlayPorts,
)
internal val LocalDesktopHomeEnvironment=staticCompositionLocalOf<DesktopHomeEnvironment>{error("Full HomeScreen requires the retained Root owner, complete settings and actual platform consumers")}

internal interface DesktopHomeOverlayPorts {
 @Composable fun VideoShareSheetHost(payload:VideoSharePayload?,onDismiss:()->Unit)
 @Composable fun CrashTrackingConsentDialog(onDismiss:()->Unit)
}

internal val LocalDesktopHomeErrorAnimation=staticCompositionLocalOf<@Composable (String,Dp,Int)->Unit>{error("Root original Lottie consumer is required") }
