package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.core.store.PlayHistoryEntry
import com.android.purebilibili.core.store.PlayLastSession
import com.android.purebilibili.feature.audio.viewmodel.MusicUiState
import com.android.purebilibili.feature.video.player.PlayMode
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import com.android.purebilibili.feature.video.viewmodel.VideoEngagementViewModel
import com.android.purebilibili.feature.video.viewmodel.VideoSupplementViewModel
import com.android.purebilibili.feature.video.viewmodel.VideoCommentViewModel
import kotlinx.coroutines.flow.StateFlow

/** A view of parent's full original VideoPlaybackViewModel and the SAME native
 * section control. No separate VM, player, URL resolver or coroutine actor lives here.
 */
internal interface DesktopOriginalAudioVideoOwner {
    val uiState: StateFlow<VideoPlaybackUiState>
    val subjectSnapshot: StateFlow<VideoSubjectSnapshot?>
    val sleepTimerMinutes: StateFlow<Int?>
    val currentPlayer: DesktopOriginalMpvSectionControl?
    fun loadVideo(bvid: String, cid: Long = 0L, autoPlay: Boolean? = null, fallbackResumePositionMs: Long = 0L)
    /** Only an actual initial AudioMode effect uses this suspending source-aware overload.
     * Compatibility owners retain their existing load operation. No scope/Job is created. */
    suspend fun loadInitialAudioVideo(bvid: String, cid: Long = 0L, autoPlay: Boolean? = null,
        fallbackResumePositionMs: Long = 0L) {
        loadVideo(bvid, cid, autoPlay, fallbackResumePositionMs)
    }
    fun retry()
    fun setAudioMode(value: Boolean)
    fun playPreviousAudioModeTrack()
    fun playNextAudioModeTrack()
    // Old no-argument implementations/callers stay compatible. The production
    // consumed view overrides both scope-aware overloads; only real UI supplies it.
    fun playPreviousAudioModeTrack(callerScope: kotlinx.coroutines.CoroutineScope) { playPreviousAudioModeTrack() }
    fun playNextAudioModeTrack(callerScope: kotlinx.coroutines.CoroutineScope) { playNextAudioModeTrack() }
    fun selectSubtitleTrack(trackKey: String)
    fun setSleepTimer(minutes: Int?)
    fun applyPlaybackSpeedFromUi(speed: Float)
    fun setAudioQuality(audioQuality: Int)
    fun showFavoriteFolderDialog()
    fun downloadAudio(context: DesktopOriginalPlayerSettingsContext)
    fun toast(text: String)
}
internal interface DesktopOriginalAudioComposerPort { fun bindSubject(subject: VideoSubjectSnapshot) }
internal interface DesktopOriginalAudioLyricsPort {
    val uiState: StateFlow<MusicUiState>
    fun initPlayer(context: DesktopOriginalPlayerSettingsContext)
    fun loadLyricsForVideo(title: String, artist: String, bvid: String, cid: Long, durationMs: Long)
    fun adjustLyricsOffset(offsetMs: Long)
    fun retryLyrics()
    fun searchLyrics(title: String)
    fun selectLyricsCandidate(index: Int)
}
internal interface DesktopOriginalAudioPlaylistPort {
    val playlist: StateFlow<List<PlaylistItem>>
    val currentIndex: StateFlow<Int>
    val playMode: StateFlow<PlayMode>
    val shuffleEnabled: StateFlow<Boolean>
    fun addToPlaylist(item: PlaylistItem)
    fun setPlaylist(items: List<PlaylistItem>)
    fun playAt(index: Int): PlaylistItem?
    fun setPlayMode(mode: PlayMode)
    fun setShuffleEnabled(enabled: Boolean)
}
internal interface DesktopOriginalAudioSessionPort { fun markListening(); fun dismiss() }
internal interface DesktopOriginalAudioMiniPort {
    fun setVideoInfo(bvid: String, title: String, cover: String, owner: String, cid: Long, aid: Long,
        externalPlayer: DesktopOriginalMpvSectionControl)
}
internal enum class DesktopOriginalAudioOrientation { PORTRAIT, LANDSCAPE }
internal interface DesktopOriginalAudioWindowPort {
    /** Actual enclosing Windows presentation, not an Android SDK/configuration shim. */
    val isLandscape: StateFlow<Boolean>
    val pipAvailable: Boolean
    fun enterPip()
    fun setRequestedOrientation(value: DesktopOriginalAudioOrientation)
    /** Restore the CURRENT Root presentation/chrome, never captured stale theme values. */
    fun orientationLease(): AutoCloseable
}
internal interface DesktopOriginalAudioModePlatform {
    val window: DesktopOriginalAudioWindowPort
    val playlist: DesktopOriginalAudioPlaylistPort
    val session: DesktopOriginalAudioSessionPort
    val mini: DesktopOriginalAudioMiniPort
    val lyrics: DesktopOriginalAudioLyricsPort
    val engagement: VideoEngagementViewModel
    val supplement: VideoSupplementViewModel
    val composer: DesktopOriginalAudioComposerPort
    val comments: VideoCommentViewModel
    val favoriteInteraction: DesktopFavoriteInteractionPreferences
    fun applyPreferredVolume(player: DesktopOriginalMpvSectionControl)
    @Composable fun standalonePlayerState(viewModel: DesktopOriginalAudioVideoOwner, bvid: String, cid: Long,
        fallbackResumePositionMs: Long): DesktopOriginalMpvVideoPlayerState
}
internal val LocalDesktopOriginalAudioModePlatform = staticCompositionLocalOf<DesktopOriginalAudioModePlatform> {
    error("Original audio mode requires the same full video/native/listen/queue/library entry")
}
