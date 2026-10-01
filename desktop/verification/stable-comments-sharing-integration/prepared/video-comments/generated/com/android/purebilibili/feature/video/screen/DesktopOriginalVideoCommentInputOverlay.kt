package com.android.purebilibili.feature.video.screen
import androidx.compose.runtime.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.video.ui.components.CommentInputDialog

@Immutable
private data class CommentInputSnapshot(
    val visible: Boolean,
    val isSending: Boolean,
    val replyToName: String?,
    val inputHint: String,
    val canInputComment: Boolean,
    val emotePackages: List<EmotePackage>,
    val mentionUsers: List<MentionSearchUser>,
    val isMentionSearching: Boolean,
    val mentionSearchError: String?,
    val draft: CommentComposerDraft,
)

@Immutable
private data class CommentInputActions(
    val dismiss: () -> Unit,
    val searchMentions: (String) -> Unit,
    val updateDraft: (String, List<String>, Boolean) -> Unit,
    val send: (String, List<String>, Boolean) -> Unit,
)


@Composable internal fun DesktopOriginalVideoCommentInputOverlay(
    composer: DesktopOriginalVideoCommentComposer,
    commentState: CommentUiState,
    currentVideoPositionMsProvider: () -> Long,
) {
    val showCommentInput by composer.showCommentDialog.collectAsState()
    val composerDrafts by composer.composerDrafts.collectAsState()
    val isSendingComment by composer.isSendingComment.collectAsState()
    val replyingToComment by composer.replyingToComment.collectAsState()
    val emotePackages by composer.emotePackages.collectAsState()
    val mentionSearchState by composer.commentMentionSearchState.collectAsState()
    val commentDraft = composerDrafts.comments[commentComposerDraftKey(replyingToComment?.rpid)]
        ?: CommentComposerDraft()
    VideoDetailCommentInputOverlayContent(
        snapshot = CommentInputSnapshot(
            visible = showCommentInput,
            isSending = isSendingComment,
            replyToName = replyingToComment?.member?.uname,
            inputHint = if (replyingToComment != null) commentState.childInputHint else commentState.rootInputHint,
            canInputComment = commentState.canInputComment,
            emotePackages = emotePackages,
            mentionUsers = mentionSearchState.users,
            isMentionSearching = mentionSearchState.isLoading,
            mentionSearchError = mentionSearchState.errorMessage,
            draft = commentDraft,
        ),
        actions = CommentInputActions(
            dismiss = composer::hideCommentInputDialog,
            searchMentions = composer::searchCommentMentionUsers,
            updateDraft = composer::updateCommentDraft,
            send = { message, imageUris, syncToDynamic ->
                composer.sendComment(message, imageUris, syncToDynamic)
            },
        ),
        currentVideoPositionMsProvider = currentVideoPositionMsProvider,
    )
}

@Composable
private fun VideoDetailCommentInputOverlayContent(
    snapshot: CommentInputSnapshot,
    actions: CommentInputActions,
    currentVideoPositionMsProvider: () -> Long,
) {
    CommentInputDialog(
        visible = snapshot.visible,
        onDismiss = actions.dismiss,
        isSending = snapshot.isSending,
        replyToName = snapshot.replyToName,
        inputHint = snapshot.inputHint,
        canInputComment = snapshot.canInputComment,
        emotePackages = snapshot.emotePackages,
        mentionUsers = snapshot.mentionUsers,
        isMentionSearching = snapshot.isMentionSearching,
        mentionSearchError = snapshot.mentionSearchError,
        onMentionSearchQueryChange = actions.searchMentions,
        initialText = snapshot.draft.text,
        initialImageUris = snapshot.draft.imageUris,
        initialSyncToDynamic = snapshot.draft.syncToDynamic,
        onDraftChange = actions.updateDraft,
        currentVideoPositionMsProvider = currentVideoPositionMsProvider,
        onSend = actions.send,
    )
}
