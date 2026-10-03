package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.feature.video.player.shouldContinueBackgroundAudioByPolicy
import com.android.purebilibili.feature.video.player.shouldKeepPlaybackForAudioNowPlayingBar
import com.android.purebilibili.feature.video.playback.session.resolvePlaybackPauseDecision
import com.android.purebilibili.feature.video.playback.session.resolvePlaybackResumeDecision
import com.android.purebilibili.feature.video.state.isPlaybackActiveForLifecycle
import com.bilipai.desktop.player.DesktopBackgroundPlaybackPauseToken
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates
import java.awt.EventQueue

internal data class DesktopOriginalBackgroundPlaybackSettings(
    val backgroundPlaybackEnabled: Boolean,
    val mode: DesktopOriginalPlaybackSettingsPreferences.MiniPlayerMode,
    val stopPlaybackOnExit: Boolean,
    val audioNowPlayingBarEnabled: Boolean,
)

/** Only the original transient foreground-resume intent is retained. No queue/source,
 * credentials, preference namespace or independent media owner is created here.
 */
internal class DesktopOriginalBackgroundPlaybackController(
    private val player: MpvPlayer,
    private val live: () -> Boolean,
) : AutoCloseable {
    internal enum class Result { NONE, PAUSED, RESUMED, REJECTED }
    private var resume: DesktopBackgroundPlaybackPauseToken? = null
    private var closed = false

    fun update(hidden: Boolean, settings: DesktopOriginalBackgroundPlaybackSettings,
        isMiniMode: Boolean, isPip: Boolean, isInAudioMode: Boolean, originalAudioSessionActive: Boolean): Result {
        check(EventQueue.isDispatchThread()) { "Background lifecycle belongs to the actual Window event thread" }
        if (closed) return Result.REJECTED
        if (!live()) { resume = null; player.invalidateBackgroundPauseIntents(); return Result.REJECTED }
        val state = player.state.value
        val expected = player.currentSourceSnapshot()
        val active = expected != null && state.ready && !state.ended && state.error == null
        val playbackState = when {
            state.ended -> DesktopMedia3PlayerStates.STATE_ENDED
            state.loading -> DesktopMedia3PlayerStates.STATE_BUFFERING
            state.ready && state.durationSeconds > 0 -> DesktopMedia3PlayerStates.STATE_READY
            else -> DesktopMedia3PlayerStates.STATE_IDLE
        }
        val wasPlaying = active && isPlaybackActiveForLifecycle(
            isPlaying = state.durationSeconds > 0 && !state.loading && !(state.nativePaused ?: state.paused),
            playWhenReady = !state.paused, playbackState = playbackState)
        val backgroundAllowed = shouldContinueBackgroundAudioByPolicy(
            backgroundPlaybackEnabled = settings.backgroundPlaybackEnabled, mode = settings.mode,
            isActive = active, isLeavingByNavigation = false, stopPlaybackOnExit = settings.stopPlaybackOnExit,
            shouldKeepPlaybackForPipTransition = isPip,
            keepForAudioNowPlaying = shouldKeepPlaybackForAudioNowPlayingBar(originalAudioSessionActive,
                settings.audioNowPlayingBarEnabled))
        val pause = resolvePlaybackPauseDecision(isMiniMode = isMiniMode, isPip = isPip,
            isInAudioMode = isInAudioMode, isBackgroundAudio = backgroundAllowed,
            wasPlaybackActive = wasPlaying, hasRecentUserLeaveHint = hidden, isLeavingByNavigation = false)
        if (hidden && pause.shouldPausePlayback) {
            if (!wasPlaying) return Result.NONE
            val source = expected ?: return Result.REJECTED
            val publication = source.source.nativePublication ?: return Result.REJECTED
            var token: DesktopBackgroundPlaybackPauseToken? = null
            val accepted = publication.admit {
                if (live() && !closed) token = player.pauseForBackground(source)
            }
            if (!accepted || token == null) return Result.REJECTED
            resume = token.takeIf { pause.shouldPersistTransientResumeIntent }
            return Result.PAUSED
        }
        val token = resume ?: return Result.NONE
        if (!player.ownsBackgroundPauseToken(token) || !live() || closed) {
            resume = null; return Result.REJECTED
        }
        // The original lifecycle policy includes playWhenReady while BUFFERING.
        // Retain intent only until the same native owner is initialized; never
        // infer an idle or ended source as ready, or seek to an old position.
        if (!active) return Result.NONE
        resume = null // consume once when an actually ready same source can resume
        val decision = resolvePlaybackResumeDecision(wasPlaybackActive = true, hasTransientResumeIntent = true,
            hasForegroundResumeIntent = false, isPlaying = wasPlaying,
            playWhenReady = !state.paused, playbackState = playbackState,
            currentVolume = (state.volume / 100).toFloat(), shouldEnsureAudibleOnForeground = false,
            isLeavingByNavigation = false)
        if (!decision.shouldResumePlayback) return Result.NONE
        val publication = token.source.source.nativePublication ?: return Result.REJECTED
        var resumed = false
        val accepted = publication.admit {
            if (live() && !closed) resumed = player.resumeAfterBackground(token)
        }
        // Windows pause never applies a lifecycle mute, so keep the user's native
        // volume/mute. The original shouldRestoreVolume flag has no transient mute to undo.
        return if (accepted && resumed) Result.RESUMED else Result.REJECTED
    }
    override fun close() {
        check(EventQueue.isDispatchThread())
        if (!closed) { closed = true; resume = null; player.invalidateBackgroundPauseIntents() }
    }
}
