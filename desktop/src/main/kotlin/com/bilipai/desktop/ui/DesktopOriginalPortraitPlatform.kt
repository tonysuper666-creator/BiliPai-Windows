package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.plugin.PlaybackCdnPlugin
import com.android.purebilibili.feature.video.ui.pager.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.data.PlaybackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Required view of the ORIGINAL full VM. No constructor/default implementation,
 * shadow UiState, store or actor exists here. Root/full-owner implements this view.
 */
internal interface DesktopOriginalPortraitPlaybackOwner {
    val uiState: StateFlow<VideoPlaybackUiState>
    val subjectSnapshot: StateFlow<VideoSubjectSnapshot?>
    val danmakuSentEvent: Flow<DesktopOriginalPortraitSentDanmakuView>
    val favoriteFolderSaveEvent: StateFlow<FavoriteFolderSaveEvent?>
    val showCommentDialog: StateFlow<Boolean>
    val isSendingComment: StateFlow<Boolean>
    val replyingToComment: StateFlow<ReplyItem?>
    val emotePackages: StateFlow<List<EmotePackage>>
    val commentMentionSearchState: StateFlow<CommentMentionSearchUiState>
    val composerDrafts: StateFlow<VideoComposerDraftState>
    val commentSentEvent: Flow<ReplyItem?>
    fun ensureFollowStatus(mid: Long, force: Boolean = false)
    fun toggleFavorite()
    fun showFavoriteFolderDialog(requestedAid: Long? = null)
    fun showDanmakuSendDialog()
    fun selectSubtitleTrack(trackKey: String)
    fun openRootCommentComposer()
    fun setReplyingTo(comment: ReplyItem?)
    fun showCommentInputDialog()
    fun hideCommentInputDialog()
    fun searchCommentMentionUsers(query: String)
    fun updateCommentDraft(text: String, imageUris: List<String>, syncToDynamic: Boolean)
    fun sendComment(inputMessage: String?, imageUris: List<String>, syncToDynamic: Boolean, targetAid: Long?)
}

/** Read view of the four original transient event fields, never another DTO/cache. */
internal interface DesktopOriginalPortraitSentDanmakuView {
    val text: String
    val color: Int
    val mode: Int
    val fontSize: Int
}

internal interface DesktopOriginalPortraitComposerOwner {
    fun bindSubject(subject: VideoSubjectSnapshot)
}

/** Extra original portrait operations on the SAME document/session as Section. */
internal interface DesktopOriginalPortraitDanmakuPort : DesktopOriginalSectionDanmakuPort {
    fun clearForVideoChange()
    fun addLocalDanmaku(text: String, color: Int, mode: Int, fontSize: Int)
    fun recoverAfterForeground(positionMs: Long, playWhenReady: Boolean, playbackState: Int)
}

/** Existing retained Home protocol instance, with its sole preload/list authority. */
internal interface DesktopOriginalPortraitRequests {
    val api: com.android.purebilibili.core.network.BilibiliApi
    val spaceApi: com.android.purebilibili.core.network.SpaceApi
    suspend fun getHomeVideos(idx: Int): Result<List<VideoItem>>
    suspend fun getWatchLaterList(): WatchLaterResponse
    suspend fun getRelatedVideos(bvid: String): List<RelatedVideo>
    suspend fun isVerticalVideo(bvid: String, aid: Long): Boolean
    fun hasPrimarySessionCookie(): Boolean
    fun isPlaybackLoggedIn(): Boolean
    fun isPlaybackVip(): Boolean
}

internal interface DesktopOriginalPortraitMediaFactory {
    /** Fresh captured receipt must belong to the SAME native subject. Returns false
     * if selection/epoch/source changed; never retag old URLs with new credentials.
     */
    fun replaceAudioSource(player: DesktopOriginalMpvSectionControl, videoUrl: String,
        audioUrl: String, mediaId: String, positionMs: Long, playWhenReady: Boolean): Boolean
}

internal interface DesktopOriginalPortraitPlatform {
    val section: DesktopOriginalVideoSectionPlatform
    val player: DesktopOriginalMpvSectionControl
    val composer: DesktopOriginalPortraitComposerOwner
    val supplement: VideoSupplementViewModel
    val comments: VideoCommentViewModel
    val danmaku: DesktopOriginalPortraitDanmakuPort
    val requests: DesktopOriginalPortraitRequests
    /** Same Assembly primary action invocation and Root account-tagged events. */
    val creatorTeam: DesktopCreatorTeamBindings
    val externalPlaylist: StateFlow<Boolean>
    val favoriteQuickSaveDefaultFolder: Flow<Boolean>
    val blockedUps: DesktopOriginalPortraitBlockedUps
    val mediaFactory: DesktopOriginalPortraitMediaFactory
    val codecs: DesktopOriginalPortraitCodecCapabilities
    val playbackCdnPlugin: PlaybackCdnPlugin?
    fun isWifi(): Boolean
    suspend fun playableDefaultQuality(isLoggedIn: Boolean, isVip: Boolean): Int
    /** Capture ONCE in each actual load coroutine; async info/playurl and publication
     * all retain that exact immutable receipt/request Job. Completed jobs cannot be reused.
     */
    suspend fun capturePlaybackRequest(): DesktopOriginalVideoRepositoryBinding
    suspend fun capturePageRequest(bvid: String, aid: Long, cid: Long): DesktopOriginalVideoRepositoryBinding
    fun currentMediaId(player: DesktopOriginalMpvSectionControl): String?
    fun clearPlaybackForReplacement(player: DesktopOriginalMpvSectionControl)
    fun releasePagerLease(player: DesktopOriginalMpvSectionControl)
    fun acquirePresentation(active: Boolean): AutoCloseable
    fun acquireDanmakuPlayer(player: DesktopOriginalMpvSectionControl): AutoCloseable
    fun recoverSurface(player: DesktopOriginalMpvSectionControl)
    fun prepareSource(request: DesktopOriginalVideoRepositoryBinding, info: ViewInfo,
        playData: PlayUrlData, videoUrl: String, audioUrl: String?, mediaId: String,
        selection: com.android.purebilibili.feature.video.ui.pager.PortraitPlaybackStreamUrls,
        recommendations: List<RelatedVideo>): PlaybackSource
    /** The only final native admission. Must stamp request.receipt and enqueue to
     * the existing source actor under SAME Store->entry gate. Never join under locks.
     */
    fun publishSource(request: DesktopOriginalVideoRepositoryBinding, source: PlaybackSource,
        expectedLoadGeneration: Int, playWhenReady: Boolean, stillCurrentLoad: () -> Boolean): Boolean
    /** Root must supply the real owned media-range transport/cache capability.
     * A raw PlayUrl cache alone is not disk-byte media cache parity. No no-op is valid.
     * Original Wi-Fi gate, video1536KiB/audio256KiB bounds and headers are retained.
     */
    fun captureMediaCache(request: DesktopOriginalVideoRepositoryBinding, playData: PlayUrlData,
        streamUrls: com.android.purebilibili.feature.video.ui.pager.PortraitPlaybackStreamUrls): DesktopOriginalPortraitByteCache
    fun shareText(text: String)
    fun showFeedback(text: String)
    @Composable fun Surface(player: DesktopOriginalMpvSectionControl, foreground: @Composable () -> Unit)
    @Composable fun Viewport(player: DesktopOriginalMpvSectionControl, modifier: Modifier,
        resizeMode: Int, keepAwake: Boolean, navigationTextureRequested: Boolean, requiresHdr: Boolean)
    @Composable fun DanmakuSurface(modifier: Modifier, videoWidth: Int, videoHeight: Int, resizeMode: Int)
}

/** View of the existing global blocked-UP authority, with owned writes. */
internal interface DesktopOriginalPortraitBlockedUps {
    fun isBlocked(mid: Long): Flow<Boolean>
    suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String): com.android.purebilibili.data.repository.BlockedUpWriteResult
    suspend fun unblockUpWithBilibiliSync(mid: Long): com.android.purebilibili.data.repository.BlockedUpWriteResult
}

/** Existing native capability readback, not an Android codec probe or fake default. */
internal interface DesktopOriginalPortraitCodecCapabilities {
    val hevcSupported: Boolean
    val av1Supported: Boolean
    val dolbyAudioSupported: Boolean
    val dolbyAudioSoftwareDecoded: Boolean
}

internal val LocalDesktopOriginalPortraitPlatform = staticCompositionLocalOf<DesktopOriginalPortraitPlatform> {
    error("Portrait pager requires the same original VM/Repository/MPV/Window entry owner")
}
