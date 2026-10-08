package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.data.model.response.StoryItem
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.resolvePlaybackCompletionRepeatMode
import com.android.purebilibili.feature.video.state.shouldReuseMiniPlayerAtEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import java.awt.EventQueue

/** One concrete Holder adapter over the retained original VM and the SAME
 * Section. All required effects below are existing Root consumers. The adapter
 * does not resolve URLs, manufacture a domain VM or own another native surface.
 */
internal class DesktopOriginalVideoRootHolderPlatform(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val gate: DesktopOriginalVideoRootGate,
    override val section: DesktopOriginalVideoSectionPlatform,
    override val settingsContext: DesktopOriginalPlayerSettingsContext,
    override val homeSettings: DesktopHomeSettingsPort,
    override val favoritePreferences: DesktopFavoriteInteractionPreferences,
    override val accounts: DesktopProfileAccountPort,
    override val downloads: DesktopOriginalVideoOwnerDownload,
    override val portraitPlaybackOwner: DesktopOriginalPortraitPlaybackOwner,
    override val commentsPlatform: DesktopCommentPlatform,
    override val window: DesktopOriginalVideoHolderWindowPort,
    override val playlist: DesktopOriginalVideoHolderPlaylist,
    override val danmaku: DesktopOriginalPortraitDanmakuPort,
    override val mini: DesktopOriginalVideoHolderMini?,
    private val sourceVersions: StateFlow<Long?>,
    private val locked: () -> Boolean,
    private val userMuted: () -> Boolean,
    private val volume: (DesktopOriginalMpvSectionControl) -> Unit,
    private val feedback: (String) -> Unit,
    private val screenView: (String) -> Unit,
    private val pipEvent: (String, String) -> Unit,
    private val storyFeed: suspend (Long, String) -> Result<List<StoryItem>>,
    private val exportLogs: () -> Unit,
    private val setDiagnosticLogging: suspend (Boolean) -> Unit,
    private val danmakuClickListener: ((String, Long, String, Boolean) -> Unit) -> AutoCloseable,
    private val metadata: (String, String, String) -> Unit,
    private val awaitNative: suspend () -> Unit,
) : DesktopOriginalVideoHolderPlatform {
    init { require(section.settingsContext === settingsContext) }
    private fun owns() = gate.owns() && assembly.owns()
    private fun requireCurrent() { if (!owns()) throw CancellationException("Original Holder entry retired") }
    private fun commit(action: () -> Unit) = gate.commit { if (owns()) action() }
    private val playerState = DesktopOriginalMpvVideoPlayerState(assembly.section, gate.scope,
        sourceVersions, ::owns, ::commit, metadata)
    override val fullscreenPlayerLocked get() = locked()
    override fun elapsedRealtimeMillis() = DesktopHomeClock.elapsedRealtime()
    override fun isCurrent() = owns()
    override fun streamVolumeIsMuted() = userMuted()
    override fun applyPreferredVolume(player: DesktopOriginalMpvSectionControl) {
        require(player === assembly.section); requireCurrent(); volume(player)
    }
    override fun showFeedback(text: String) { requireCurrent(); feedback(text) }
    override fun logScreenView(name: String) { requireCurrent(); screenView(name) }
    override fun logPictureInPicture(videoId: String, action: String) { requireCurrent(); pipEvent(videoId, action) }
    override suspend fun getStoryFeed(aid: Long, bvid: String): Result<List<StoryItem>> {
        currentCoroutineContext().ensureActive(); requireCurrent()
        return storyFeed(aid, bvid).also { currentCoroutineContext().ensureActive(); requireCurrent() }
    }
    override fun exportAndShareLogs() { requireCurrent(); exportLogs() }
    override suspend fun setPlayerDiagnosticLoggingEnabled(enabled: Boolean) {
        currentCoroutineContext().ensureActive(); requireCurrent(); setDiagnosticLogging(enabled)
        currentCoroutineContext().ensureActive(); requireCurrent()
    }
    override fun acquireDanmakuClickListener(onClick: (String, Long, String, Boolean) -> Unit) =
        danmakuClickListener { text, position, user, mine -> if (owns()) onClick(text, position, user, mine) }

    @Composable override fun BindPlayerState(bvid: String, cid: Long, fallbackResumePositionMs: Long,
        startPaused: Boolean, entryTransitionFinished: Boolean,
        playbackSessionActive: Boolean,
        desktopLoadVideo: (suspend (Boolean) -> Unit)?): DesktopOriginalMpvVideoPlayerState {
        check(EventQueue.isDispatchThread())
        val reuseFromMiniPlayerAtEntry = remember(bvid, cid) {
            shouldReuseMiniPlayerAtEntry(mini?.isActive == true, mini?.currentBvid,
                mini?.currentCid ?: 0L, mini?.player != null, bvid, cid)
        }
        val completion by DesktopOriginalVideoControlSettings.getPlaybackCompletionBehavior(settingsContext)
            .collectAsState(initial = DesktopOriginalVideoControlSettings.getPlaybackCompletionBehaviorSync(settingsContext))
        LaunchedEffect(assembly, completion, sourceVersions.value) {
            if (owns() && assembly.section.isOwned())
                assembly.section.repeatMode = resolvePlaybackCompletionRepeatMode(completion)
        }
        val uiState by assembly.playback.uiState.collectAsState()
        LaunchedEffect(uiState) {
            val success = uiState as? VideoPlaybackUiState.Success ?: return@LaunchedEffect
            if (owns()) playerState.updateMediaMetadata(success.info.title, success.info.owner.name, success.info.pic)
        }
        // Original VideoPlayerState.kt attach/load effect (1510–1589). The
        // retained VM already owns its original Success; a second Mini cache is
        // unnecessary. Native initialization is awaited again outside gates so
        // a real detach cannot be hidden with an old decoder snapshot.
        LaunchedEffect(assembly, bvid, cid, reuseFromMiniPlayerAtEntry, fallbackResumePositionMs,
            entryTransitionFinished, playbackSessionActive) {
            if (!playbackSessionActive || (!entryTransitionFinished && !reuseFromMiniPlayerAtEntry))
                return@LaunchedEffect
            awaitNative(); currentCoroutineContext().ensureActive(); requireCurrent()
            assembly.playback.attachPlayer(assembly.section)
            if (!entryTransitionFinished) return@LaunchedEffect
            val autoPlay = !startPaused && DesktopOriginalPlayerSectionSettings.getClickToPlaySync(settingsContext)
            if (desktopLoadVideo == null) {
                assembly.playback.loadVideo(bvid = bvid, cid = cid,
                    fallbackResumePositionMs = fallbackResumePositionMs, autoPlay = autoPlay)
            } else desktopLoadVideo(autoPlay)
        }
        // Compose disposal only flushes the original retained VM. Root owns
        // cancellation/join, publication retirement and eventual Window cleanup.
        DisposableEffect(assembly, bvid, cid) {
            onDispose {
                if (owns()) {
                    assembly.playback.flushPlaybackHeartbeatSnapshot(reason = "dispose")
                    assembly.playback.saveCurrentPosition()
                }
            }
        }
        return playerState
    }
}
