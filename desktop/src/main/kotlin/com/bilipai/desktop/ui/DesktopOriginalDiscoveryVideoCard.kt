package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.android.purebilibili.core.store.DesktopHomeCardVisualSettings
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.HomeFeedCardLayout
import com.android.purebilibili.feature.home.components.cards.ElegantVideoCard
import com.android.purebilibili.feature.home.components.cards.LocalHomeCardDynamicTintEnabled
import com.android.purebilibili.feature.home.components.cards.LocalHomeCardFrostedGlassEnabled
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.discoveryVideoCard

/** Render the server item; the thin navigation card never becomes the display data source. */
@Composable
internal fun DesktopOriginalDiscoveryVideoCard(video:VideoItem,index:Int,layout:HomeFeedCardLayout,
    settings:DesktopHomeCardVisualSettings,onVideo:(VideoCard)->Unit,onUser:(Long)->Unit,
    onPreview:(VideoCard)->Unit,onNotInterested:()->Unit,onWatchLater:()->Unit) {
    CompositionLocalProvider(
        LocalFullVideoCardContentVisible provides settings.showFullVideoCardContent,
        LocalVideoCardLongPressEnabled provides settings.videoCardLongPressActionEnabled,
        LocalUpBadgeVisibility provides UpBadgeVisibility(settings.showHomeUpBadges,settings.showHomeUpAvatars),
        LocalHomeCardDynamicTintEnabled provides settings.homeCardDynamicTintEnabled,
        LocalHomeCardFrostedGlassEnabled provides settings.homeCardFrostedGlassEnabled,
    ) {
        ElegantVideoCard(video=video,index=index,isFollowing=video.isFollowed,
            animationEnabled=settings.cardAnimationEnabled,transitionEnabled=false,
            sharedElementSourceRoute="home",showPublishTime=settings.showHomePublishTime,
            compactStatsOnCover=settings.compactVideoStatsOnCover || layout.compactStatsOnCover,
            // Root has no wallpaper backdrop mounted. Original tint policy falls back to cover pixels.
            wallpaperTintEnabled=false,
            showUpBadge=settings.showHomeUpBadges,showUpAvatar=settings.showHomeUpAvatars,
            homeDurationStyle=settings.homeDurationStyle,coverAspectRatio=layout.coverAspectRatio,
            compactMetadata=layout.compactMetadata,titleMinLines=layout.titleMinLines,titleMaxLines=layout.titleMaxLines,
            showOnlineCount=settings.showOnlineCount,onDismiss=onNotInterested,onWatchLater=onWatchLater,
            onLongClick={onPreview(discoveryVideoCard(it))},onUpClick=onUser,
            onClick={bvid,cid->onVideo(discoveryVideoCard(video).copy(bvid=bvid,preferredCid=cid))})
    }
}
