package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import com.android.purebilibili.core.store.DesktopHomeCardVisualSettings
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.components.cards.ElegantVideoCard
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.discoveryVideoCard

/** Render the server item; the thin navigation card never becomes the display data source. */
@Composable
internal fun DesktopOriginalDiscoveryVideoCard(video:VideoItem,index:Int,frame:DesktopHomeGridCardFrame,
    settings:DesktopHomeCardVisualSettings,sampling:DesktopHomeGridSampling,onVideo:(VideoCard)->Unit,
    onUser:(Long)->Unit,onPreview:(VideoCard)->Unit,onNotInterested:()->Unit,onWatchLater:()->Unit) {
    val layout=frame.layout
    ProvideDesktopHomeCardChromeLocals(resolveDesktopHomeCardChromeLocals(settings)) {
        ElegantVideoCard(video=video,index=index,isFollowing=video.isFollowed,
            animationEnabled=settings.cardAnimationEnabled,transitionEnabled=false,
            sharedElementSourceRoute="home",showPublishTime=settings.showHomePublishTime,
            isDataSaverActive=sampling.isDataSaverActive,preferLowQualityCover=sampling.preferLowQualityCover,
            coverRequestSpec=frame.coverRequestSpec,
            compactStatsOnCover=settings.compactVideoStatsOnCover || layout.compactStatsOnCover,
            showCoverGlassBadges=sampling.showCoverGlassBadges,showInfoGlassBadges=sampling.showInfoGlassBadges,
            badgeEffectMode=sampling.badgeEffectMode,infoGlassMode=sampling.infoGlassMode,
            wallpaperTintEnabled=sampling.wallpaperTintEnabled,wallpaperEffectMode=sampling.wallpaperEffectMode,
            showUpBadge=settings.showHomeUpBadges,showUpAvatar=settings.showHomeUpAvatars,
            homeDurationStyle=settings.homeDurationStyle,coverAspectRatio=layout.coverAspectRatio,
            compactMetadata=layout.compactMetadata,titleMinLines=layout.titleMinLines,titleMaxLines=layout.titleMaxLines,
            showOnlineCount=settings.showOnlineCount,onDismiss=onNotInterested,onWatchLater=onWatchLater,
            onLongClick={onPreview(discoveryVideoCard(it))},onUpClick=onUser,cardWidthDp=frame.cardWidthDp,
            onClick={bvid,cid->onVideo(discoveryVideoCard(video).copy(bvid=bvid,preferredCid=cid))})
    }
}
