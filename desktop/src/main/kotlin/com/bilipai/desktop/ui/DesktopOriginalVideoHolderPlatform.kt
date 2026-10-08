package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntRect
import com.android.purebilibili.core.util.AppDisplayContext
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.player.PlaylistUiState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.data.model.response.StoryItem
import com.android.purebilibili.feature.video.screen.VideoDetailSystemBarsApplySpec
import kotlinx.coroutines.flow.StateFlow

/** Physical rotation is optional and explicit. Windows monitor orientation or
 * client aspect MUST NOT be passed here as Android accelerometer degrees.
 * A null sensor means this host has no physical rotation capability.
 */
internal interface DesktopOriginalVideoHolderRotationSensor {
    val automaticRotationEnabled: StateFlow<Boolean>
    fun observeDegrees(onDegrees: (Int) -> Unit): AutoCloseable
}

/** Actual same Root Window placement/presentation owner. Orientation numbers are
 * inputs to the original pure policy; the Windows adapter maps an explicit user
 * request to the supported fullscreen presentation, not OS display rotation.
 */
internal interface DesktopOriginalVideoHolderPresentation {
    val currentRequestedOrientation: Int
    val isLandscape: Boolean
    val isInMultiWindowMode: Boolean
    val isChangingConfigurations: Boolean
    val rotationSensor: DesktopOriginalVideoHolderRotationSensor?
    fun requestOrientation(requestedOrientation: Int, displayContext: AppDisplayContext?)
}

/** Root supplies its same native PiP/window/wake/viewport resources. Every lease
 * captures the entry and native source token; restoration may affect only its
 * own unchanged registration. Native await/join never occurs under Store/entry.
 */
internal interface DesktopOriginalVideoHolderWindowPort {
    val presentation: DesktopOriginalVideoHolderPresentation
    val supportsPictureInPicture: Boolean
    fun acquireKeepAwake(enabled: Boolean): AutoCloseable
    fun updatePictureInPicture(player: DesktopOriginalMpvSectionControl?, sourceBounds: IntRect?,
        autoEnterEnabled: Boolean, seamlessResizeEnabled: Boolean)
    fun resetAutoEnterPictureInPicture()
    fun releaseEntryBrightness()
    fun releaseEntryKeepAwake()
    fun restoreEntryWindowChrome()
    fun captureEntryWindowChrome(): AutoCloseable
    fun applySystemBars(spec: VideoDetailSystemBarsApplySpec)
    fun acquireEdgeToEdge(): AutoCloseable
    /** Deferred exit is still the original entry's operation. It must reject a
     * replacement native subject or a transferred publication, never stop it.
     */
    fun deferEntryExit(navigationExit: Boolean, originalRequestedOrientation: Int?)
}

/** One view of the existing playlist/Listen authority; no list or cursor cache. */
internal interface DesktopOriginalVideoHolderPlaylist {
    val uiState: StateFlow<PlaylistUiState>
    fun playAt(index: Int): PlaylistItem?
    fun togglePlayMode()
}

/** Mini/PiP metadata and navigation over the same existing retained subject. */
internal interface DesktopOriginalVideoHolderMini : DesktopOriginalFullscreenMiniOwner {
    val isActive: Boolean
    val isMiniMode: Boolean
    var isNavigatingToVideo: Boolean
    fun markLeavingByNavigation(expectedBvid: String, deferPlaybackStop: Boolean = false)
    fun setVideoInfo(bvid: String, title: String, cover: String, owner: String,
        cid: Long, aid: Long, externalPlayer: DesktopOriginalMpvSectionControl, fromLeft: Boolean = false)
    fun cacheUiState(state: VideoPlaybackUiState.Success)
    fun enterMiniMode(forced: Boolean)
}

/** Required composition/window and same-native projections. It does not acquire
 * any of the five domain VMs; Root passes those existing objects to the Holder.
 */
internal interface DesktopOriginalVideoHolderPlatform {
    val section: DesktopOriginalVideoSectionPlatform
    val settingsContext: DesktopOriginalPlayerSettingsContext
    val homeSettings: DesktopHomeSettingsPort
    val favoritePreferences: DesktopFavoriteInteractionPreferences
    val accounts: DesktopProfileAccountPort
    val downloads: DesktopOriginalVideoOwnerDownload
    val portraitPlaybackOwner: DesktopOriginalPortraitPlaybackOwner
    val commentsPlatform: DesktopCommentPlatform
    val window: DesktopOriginalVideoHolderWindowPort
    val playlist: DesktopOriginalVideoHolderPlaylist
    val danmaku: DesktopOriginalPortraitDanmakuPort
    val mini: DesktopOriginalVideoHolderMini?
    val fullscreenPlayerLocked: Boolean
    fun elapsedRealtimeMillis(): Long
    fun isCurrent(): Boolean
    fun streamVolumeIsMuted(): Boolean
    fun applyPreferredVolume(player: DesktopOriginalMpvSectionControl)
    fun showFeedback(text: String)
    fun logScreenView(name: String)
    fun logPictureInPicture(videoId: String, action: String)
    suspend fun getStoryFeed(aid: Long, bvid: String): Result<List<StoryItem>>
    fun exportAndShareLogs()
    suspend fun setPlayerDiagnosticLoggingEnabled(enabled: Boolean)
    fun acquireDanmakuClickListener(onClick: (String, Long, String, Boolean) -> Unit): AutoCloseable
    /** Root binds the SAME NativeOwner projection. This call cannot construct,
     * load or close a second player; original request and transition arguments
     * participate in the existing owner/VM's source admission.
     */
    @Composable fun BindPlayerState(bvid: String, cid: Long, fallbackResumePositionMs: Long,
        startPaused: Boolean, entryTransitionFinished: Boolean,
        playbackSessionActive: Boolean,
        desktopLoadVideo: (suspend (Boolean) -> Unit)? = null): DesktopOriginalMpvVideoPlayerState
}

internal val LocalDesktopOriginalVideoHolderPlatform = staticCompositionLocalOf<DesktopOriginalVideoHolderPlatform> {
    error("Original detail Holder requires its retained Root Window/native/domain owner")
}
