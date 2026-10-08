package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Interface views of the ONE retained original playback VM. No StateFlow,
 * coroutine scope, decoder, request/subject generation or player is constructed.
 * Root must reuse this view for the matching Assembly's Portrait and Audio UI;
 * it must never obtain a different/latest Assembly inside an old view command.
 */
internal class DesktopOriginalVideoConsumedViews(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val manualAudioCurrent: (() -> Boolean)? = null,
    private val manualAudioRetained: (() -> Boolean)? = null,
) : DesktopOriginalPortraitPlaybackOwner, DesktopOriginalAudioVideoOwner {
    private val original = assembly.playback
    private fun assertOwned() {
        if (!assembly.owns() || assembly.playback !== original)
            throw CancellationException("Original video consumed view retired")
    }
    private inline fun command(action: () -> Unit) { assertOwned(); action() }

    override val uiState get() = original.uiState
    override val subjectSnapshot get() = original.subjectSnapshot
    override val favoriteFolderSaveEvent get() = original.favoriteFolderSaveEvent
    override val showCommentDialog get() = original.showCommentDialog
    override val isSendingComment get() = original.isSendingComment
    override val replyingToComment get() = original.replyingToComment
    override val emotePackages get() = original.emotePackages
    override val commentMentionSearchState get() = original.commentMentionSearchState
    override val composerDrafts get() = original.composerDrafts
    override val commentSentEvent = original.commentSentEvent.filter { assembly.owns() }
    override val sleepTimerMinutes get() = original.sleepTimerMinutes
    override val currentPlayer: DesktopOriginalMpvSectionControl?
        get() = assembly.section.takeIf { assembly.owns() && original.currentPlayer === it }

    // Required interface changes only the read view type. The original Channel /
    // receiveAsFlow remains the event authority; no relay collector or replay is added.
    override val danmakuSentEvent: Flow<DesktopOriginalPortraitSentDanmakuView> = original.danmakuSentEvent
        .filter { assembly.owns() }
        .map { SentView(it) }
    private class SentView(private val original: VideoPlaybackViewModel.DanmakuSentData) :
        DesktopOriginalPortraitSentDanmakuView {
        override val text get() = original.text
        override val color get() = original.color
        override val mode get() = original.mode
        override val fontSize get() = original.fontSize
    }

    override fun ensureFollowStatus(mid: Long, force: Boolean) = command { original.ensureFollowStatus(mid, force) }
    override fun toggleFavorite() = command { original.toggleFavorite() }
    override fun showFavoriteFolderDialog(requestedAid: Long?) = command { original.showFavoriteFolderDialog(requestedAid) }
    override fun showFavoriteFolderDialog() = command { original.showFavoriteFolderDialog() }
    override fun showDanmakuSendDialog() = command { original.showDanmakuSendDialog() }
    override fun selectSubtitleTrack(trackKey: String) = command { original.selectSubtitleTrack(trackKey) }
    override fun openRootCommentComposer() = command { original.openRootCommentComposer() }
    override fun setReplyingTo(comment: ReplyItem?) = command { original.setReplyingTo(comment) }
    override fun showCommentInputDialog() = command { original.showCommentInputDialog() }
    override fun hideCommentInputDialog() = command { original.hideCommentInputDialog() }
    override fun searchCommentMentionUsers(query: String) = command { original.searchCommentMentionUsers(query) }
    override fun updateCommentDraft(text: String, imageUris: List<String>, syncToDynamic: Boolean) =
        command { original.updateCommentDraft(text, imageUris, syncToDynamic) }
    override fun sendComment(inputMessage: String?, imageUris: List<String>, syncToDynamic: Boolean, targetAid: Long?) =
        command { original.sendComment(inputMessage, imageUris, syncToDynamic, targetAid) }

    override fun loadVideo(bvid: String, cid: Long, autoPlay: Boolean?, fallbackResumePositionMs: Long) =
        command { original.loadVideo(bvid, cid = cid, autoPlay = autoPlay, fallbackResumePositionMs = fallbackResumePositionMs) }
    override fun retry() = command { original.retry() }
    override fun setAudioMode(value: Boolean) = command { original.setAudioMode(value) }
    override fun playPreviousAudioModeTrack() = command { original.playPreviousAudioModeTrack() }
    override fun playNextAudioModeTrack() = command { original.playNextAudioModeTrack() }
    override fun playPreviousAudioModeTrack(callerScope: kotlinx.coroutines.CoroutineScope) =
        navigateAudioFromClick(callerScope, false)
    override fun playNextAudioModeTrack(callerScope: kotlinx.coroutines.CoroutineScope) =
        navigateAudioFromClick(callerScope, true)
    private fun navigateAudioFromClick(callerScope: kotlinx.coroutines.CoroutineScope, forward: Boolean) {
        val current = manualAudioCurrent ?: return
        val retained = manualAudioRetained ?: return
        if (!assembly.owns() || !current() || !retained()) return
        val captured = assembly.native.current() ?: return
        launchDesktopOriginalManualAudioNavigation(assembly, captured, callerScope, forward, current, retained)
    }
    override fun setSleepTimer(minutes: Int?) = command { original.setSleepTimer(minutes) }
    override fun applyPlaybackSpeedFromUi(speed: Float) = command { original.applyPlaybackSpeedFromUi(speed) }
    override fun setAudioQuality(audioQuality: Int) = command { original.setAudioQuality(audioQuality) }
    override fun downloadAudio(context: DesktopOriginalPlayerSettingsContext) = command { original.downloadAudio(context) }
    override fun toast(text: String) = command { original.toast(text) }
}
