package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.feature.video.screen.VideoDetailScreenStateHolder
import com.android.purebilibili.data.model.response.BgmInfo
import kotlinx.coroutines.CancellationException

/** Same Root Window implementations, assembled over the one retained Assembly.
 * The bootstrap is the sole existing surface, before the original request starts.
 * No second Canvas, decoder, ownership token or guessed capability is created.
 */
internal interface DesktopOriginalVideoRootWindowPlatforms {
    val holder: DesktopOriginalVideoHolderPlatform
    val fullscreen: DesktopOriginalFullscreenPlatform
    val tablet: DesktopOriginalTabletAudioPlatform
    val audio: DesktopOriginalAudioModePlatform
    val music: DesktopOriginalMusicUiPlatform
    val content: DesktopOriginalVideoContentBindings
    val portrait: DesktopOriginalPortraitPlatform
    val storyFeeds: DesktopOriginalStoryFeedOwners
    val subtitleMode: DesktopOriginalSubtitleModeBinding
    @Composable fun InitialNativeSurface(modifier: Modifier)
    /** Await the SAME MPV session's actual decoder-list initialization outside
     * Store/entry/native monitors. Retirement must cancel this wait. */
    suspend fun awaitNativeInitialization()
}

/** Set by the actual NavDisplay entry using the original stack/return policies. */
internal val LocalDesktopOriginalRootVideoRouteState = staticCompositionLocalOf<DesktopOriginalVideoHolderRouteState> {
    error("Original video route requires its physical Root stack transition state")
}

/** These fields come from the actual Navigation3 entry transition/return owner,
 * including back preview and its real cancellation generation. */
internal class DesktopOriginalVideoHolderRouteState(
    val keepLoadedForBackPreview: Boolean,
    val bindLivePlayerForBackPreview: Boolean,
    val predictiveBackCancelRecoveryGeneration: Int,
    val returning: Boolean,
    val quickReturning: Boolean,
    val transitionEnabled: Boolean,
    val transitionEnterDurationMillis: Int,
    val inPip: Boolean,
    val visible: Boolean,
    val playbackSessionActive: Boolean,
)

/** All original actions are required; the Root stack supplies these closures.
 * Replacement returns the original stack mutation outcome, never a fixed false. */
internal class DesktopOriginalVideoHolderRouteActions(
    val markReturning: () -> Unit,
    val clearReturning: () -> Unit,
    val back: () -> Unit,
    val home: () -> Unit,
    val audio: () -> Unit,
    val search: () -> Unit,
    val searchKeyword: (String) -> Unit,
    val openBilibiliLink: (String) -> Unit,
    val video: (String, Long, String?) -> Unit,
    val replaceVideo: (String, Long, String, Long) -> Boolean,
    val up: (Long) -> Unit,
    val upWithVideo: (Long, String) -> Unit,
    val immersivePlaybackChanged: (Boolean) -> Unit,
    val bgm: (BgmInfo) -> Unit,
)

/** Direct call to the complete installed original Holder and its four retained
 * domain VMs. The key's immutable CID/resume/comment/portrait/source route fields
 * pass through unchanged. Covering this Compose entry never closes Assembly.
 */
@Composable internal fun DesktopOriginalVideoHolderRoute(
    route: BiliPaiNavKey.VideoDetail,
    assembly: DesktopOriginalVideoOwnerAssembly,
    platforms: DesktopOriginalVideoRootWindowPlatforms,
    state: DesktopOriginalVideoHolderRouteState,
    actions: DesktopOriginalVideoHolderRouteActions,
) {
    check(platforms.holder.settingsContext === platforms.holder.section.settingsContext)
    var initialized by remember(assembly, platforms) { mutableStateOf(false) }
    LaunchedEffect(assembly, platforms) {
        platforms.awaitNativeInitialization()
        if (!assembly.owns()) throw CancellationException("Original video initial surface retired")
        initialized = true
    }
    if (!initialized) {
        platforms.InitialNativeSurface(Modifier.fillMaxSize())
        return
    }
    CompositionLocalProvider(
        LocalDesktopOriginalVideoHolderPlatform provides platforms.holder,
        LocalDesktopOriginalVideoSectionPlatform provides platforms.holder.section,
        LocalDesktopOriginalPlayerSettingsContext provides platforms.holder.settingsContext,
        LocalDesktopOriginalVideoContentBindings provides platforms.content,
        LocalDesktopOriginalFullscreenPlatform provides platforms.fullscreen,
        LocalDesktopOriginalTabletAudioPlatform provides platforms.tablet,
        LocalDesktopOriginalAudioModePlatform provides platforms.audio,
        LocalDesktopOriginalMusicUiPlatform provides platforms.music,
        LocalDesktopOriginalPortraitPlatform provides platforms.portrait,
        LocalDesktopOriginalSubtitleModeBinding provides platforms.subtitleMode,
        LocalDesktopCommentBindings provides platforms.holder.commentsPlatform,
    ) {
        VideoDetailScreenStateHolder(
            bvid = route.bvid, cid = route.cid, coverUrl = route.coverUrl,
            startInFullscreen = route.fullscreen, startAudioFromRoute = route.startAudio,
            autoEnterPortraitFromRoute = route.autoPortrait,
            initialVerticalFromRoute = route.initialVertical,
            directPortraitEntryFromRoute = route.directPortraitEntry,
            resumePositionMsFromRoute = route.resumePositionMs,
            openCommentRootRpidFromRoute = route.commentRootRpid,
            openCommentTargetRpidFromRoute = route.commentTargetRpid,
            sourceRouteForSharedElement = route.sourceRoute,
            keepLoadedContentForBackPreview = state.keepLoadedForBackPreview,
            bindLivePlayerForBackPreview = state.bindLivePlayerForBackPreview,
            predictiveBackCancelRecoveryGeneration = state.predictiveBackCancelRecoveryGeneration,
            isReturningFromDetail = state.returning, isQuickReturningFromDetail = state.quickReturning,
            onMarkReturningFromDetail = actions.markReturning, onClearReturningFromDetail = actions.clearReturning,
            transitionEnabled = state.transitionEnabled, transitionEnterDurationMillis = state.transitionEnterDurationMillis,
            onBack = actions.back, onHomeClick = actions.home,
            onNavigateToAudioMode = actions.audio, onNavigateToSearch = actions.search,
            onSearchKeywordClick = actions.searchKeyword, onOpenBilibiliLink = actions.openBilibiliLink,
            onVideoClick = actions.video, onReplaceVideoDetail = actions.replaceVideo,
            onUpClick = actions.up, onUpClickWithVideo = actions.upWithVideo,
            miniPlayerManager = platforms.holder.mini,
            isInPipMode = state.inPip, isVisible = state.visible,
            isPlaybackSessionActive = state.playbackSessionActive,
            onImmersivePlaybackChanged = actions.immersivePlaybackChanged,
            viewModel = assembly.playback, engagementViewModel = assembly.domains.engagement,
            composerViewModel = assembly.domains.composer, supplementViewModel = assembly.domains.supplement,
            commentViewModel = assembly.domains.comments, onBgmClick = actions.bgm,
        )
    }
}
