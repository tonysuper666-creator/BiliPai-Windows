package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.data.repository.resolveCommentFraudLightMessage
import com.android.purebilibili.data.repository.shouldShowCommentFraudResultDialog
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.feature.video.screen.DesktopOriginalVideoCommentInputOverlay
import com.android.purebilibili.feature.video.screen.VideoCommentTab
import com.android.purebilibili.feature.video.screen.shouldUseLightweightCommentRendering
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.ensureActive

/** One explicit Nav-entry intent. Its handled bit survives Tab composition and
 * source replacement; it does not own requests or the retained comment state. */
internal class DesktopWindowsVideoRoutedCommentRequest(
    val rootReplyId: Long, val targetReplyId: Long, handled: Boolean = false,
) {
    var handled by mutableStateOf(handled)
        internal set
    companion object {
        val Saver = Saver<DesktopWindowsVideoRoutedCommentRequest, List<Long>>(
            save = { listOf(it.rootReplyId, it.targetReplyId, if (it.handled) 1L else 0L) },
            restore = { DesktopWindowsVideoRoutedCommentRequest(it[0], it[1], it[2] == 1L) },
        )
    }
}

/** The UI dispatch port borrows the sole existing comment VM. Its lifetime only
 * guards NEW callbacks; the VM retains its original typed-subject/account
 * policy for a same-AID mutation that has already been dispatched. */
internal class DesktopWindowsVideoCommentActions(
    val viewModel: VideoCommentViewModel,
    private val presentation: DesktopWindowsCommentPresentation,
) {
    var threadVisible by mutableStateOf(false)
        private set
    var routedThread by mutableStateOf<DesktopWindowsVideoRoutedCommentRequest?>(null)
        private set
    fun refresh() = presentation.dispatch { viewModel.refreshComments() }
    fun loadMore() = presentation.dispatch { viewModel.loadComments() }
    fun sort(mode: CommentSortMode) = presentation.dispatch { viewModel.setSortMode(mode) }
    fun thread(reply: ReplyItem, target: Long) = presentation.dispatch { routedThread = null; viewModel.openSubReply(reply, target); threadVisible = true }
    fun threadFromRoute(request: DesktopWindowsVideoRoutedCommentRequest): Boolean {
        var started = false
        presentation.dispatch {
            if (!request.handled && viewModel.openSubReplyFromRoute(request.rootReplyId, request.targetReplyId)) {
                threadVisible = true
                routedThread = request
                request.handled = true
                started = true
            }
        }
        return started
    }
    fun retryRoutedThread(captured: DesktopWindowsVideoRoutedCommentRequest): Boolean {
        var started = false
        presentation.dispatch {
            val state = viewModel.subReplyState.value
            if (threadVisible && routedThread === captured && !state.visible &&
                !state.isLoading && state.error != null) {
                started = viewModel.openSubReplyFromRoute(captured.rootReplyId, captured.targetReplyId)
            }
        }
        return started
    }
    fun closeThread() = presentation.dispatch { threadVisible = false; routedThread = null; viewModel.closeSubReply() }
    fun refreshThread() = presentation.dispatch { viewModel.refreshSubReplies() }
    fun loadThread() = presentation.dispatch { viewModel.loadMoreSubReplies() }
    fun sortThread(mode: SubReplySortMode) = presentation.dispatch { viewModel.setSubReplySortMode(mode) }
    fun conversation(reply: ReplyItem) = presentation.dispatch { viewModel.openSubReplyConversation(reply) }
    fun conversationBack() = presentation.dispatch { viewModel.closeSubReplyConversation() }
    fun reply(reply: ReplyItem) = presentation.dispatch { viewModel.replyTo(reply) }
    fun replyingRoot() = presentation.dispatch { viewModel.cancelReply() }
    fun like(id: Long) = presentation.dispatch { viewModel.likeComment(id) }
    fun hate(id: Long) = presentation.dispatch { viewModel.hateComment(id) }
    fun report(id: Long, reason: Int) = presentation.dispatch { viewModel.reportComment(id, reason) }
    fun top(reply: ReplyItem) = presentation.dispatch { viewModel.toggleTopComment(reply) }
    fun fraud(reply: ReplyItem) = presentation.dispatch { viewModel.checkCommentFraud(reply) }
    fun dissolve(id: Long) = presentation.dispatch { viewModel.startDissolve(id) }
    fun delete(id: Long) = presentation.dispatch { viewModel.deleteComment(id) }
    fun dissolveThread(id: Long) = presentation.dispatch { viewModel.startSubDissolve(id) }
    fun deleteThread(id: Long) = presentation.dispatch { viewModel.deleteSubComment(id) }
}

private data class DesktopWindowsCommentPreview(
    val images: List<String>, val index: Int, val anchor: ImagePreviewSourceAnchor?,
    val text: ImagePreviewTextContent?,
)

/** Complete original root/thread renderers, with the actual domain VMs and
 * bounded viewport. This host never initializes, closes or replaces those VMs. */
@Composable
internal fun DesktopWindowsVideoCommentsSection(
    assembly: DesktopOriginalVideoOwnerAssembly,
    success: VideoPlaybackUiState.Success,
    source: DesktopOriginalVideoAcceptedPublication,
    routedComment: DesktopWindowsVideoRoutedCommentRequest?,
    current: () -> Boolean,
    admission: (() -> Unit) -> Boolean,
    onUser: (Long) -> Unit, login: () -> Unit, openLink: (String) -> Unit, seek: (Double) -> Unit,
    search: @Composable ((ReplyItem) -> Unit) -> Unit,
) {
    val parent = LocalDesktopWindowsPlayerWindow.current
    key(assembly, source, parent) {
        val latestCurrent by rememberUpdatedState(current)
        val latestAdmission by rememberUpdatedState(admission)
        val presentation = remember { DesktopWindowsCommentPresentation(source, parent,
            current = { latestCurrent() }, admission = { action -> latestAdmission(action) }) }
        val composerVm = assembly.domains.composer
        DisposableEffect(presentation, composerVm) { onDispose { composerVm.retireCommentPresentation(presentation); presentation.close() } }
        val vm = assembly.domains.comments
        val ui = remember { DesktopWindowsVideoCommentActions(vm, presentation) }
        LaunchedEffect(presentation, routedComment) {
            routedComment?.let { ui.threadFromRoute(it) }
        }
        val basePlatform = LocalDesktopCommentBindings.current
        val baseGallery = LocalDesktopDynamicCardBindings.current
        val platform = remember(basePlatform) { desktopWindowsCommentPlatform(basePlatform, presentation) }
        val gallery = remember(baseGallery) { desktopWindowsCommentGallery(baseGallery, presentation) }
        val state by vm.commentState.collectAsState()
        val replies by vm.subReplyState.collectAsState()
        val composerStamp by composerVm.commentStamp.collectAsState()
        val decorations by remember(platform) { DesktopOriginalReplySettings.getCommentMemberDecorationsEnabled(platform.context) }.collectAsState(false)
        val fraudEnabled by remember(platform) { DesktopOriginalReplySettings.getCommentFraudDetectionEnabled(platform.context) }.collectAsState(true)
        val listState = rememberLazyListState()
        var emotes by remember { mutableStateOf(platform.emotes.snapshot()) }
        var preview by remember { mutableStateOf<DesktopWindowsCommentPreview?>(null) }
        var fraudStatus by remember { mutableStateOf<CommentFraudStatus?>(null) }
        LaunchedEffect(presentation, platform) {
            val loaded = platform.emotes.ensureLoaded()
            ensureActive()
            presentation.dispatch { emotes = loaded }
        }
        LaunchedEffect(presentation, vm) {
            vm.fraudEvent.collect { status ->
                val light = resolveCommentFraudLightMessage(status)
                if (light != null) platform.showFeedback(light)
                else if (shouldShowCommentFraudResultDialog(status)) presentation.dispatch { fraudStatus = status }
            }
        }
        val previewClick: (List<String>, Int, ImagePreviewSourceAnchor?, ImagePreviewTextContent?) -> Unit = { images, index, anchor, text ->
            presentation.dispatch { preview = DesktopWindowsCommentPreview(images.toList(), index, anchor, text) }
        }
        val userClick: (Long) -> Unit = { id -> if (presentation.allowsEffect()) onUser(id) }
        val urlClick: (String) -> Unit = { url -> if (presentation.allowsEffect()) openLink(url) }
        val timeClick: (Long) -> Unit = { ms -> if (presentation.allowsEffect()) seek(ms / 1000.0) }
        val replyClick: (ReplyItem) -> Unit = { reply ->
            if (presentation.allowsEffect()) {
                if (state.currentMid <= 0L) login() else {
                    ui.reply(reply); composerVm.openCommentComposer(presentation, reply)
                }
            }
        }
        val input: @Composable () -> Unit = {
            TextButton(onClick = {
                if (presentation.allowsEffect()) {
                    if (state.currentMid <= 0L) login()
                    else { ui.replyingRoot(); composerVm.openCommentComposer(presentation) }
                }
            }, enabled = presentation.isCurrent() && state.canInputComment) {
                Text(if (state.canInputComment) "发表评论" else state.rootInputHint)
            }
        }
        CompositionLocalProvider(LocalDesktopCommentBindings provides platform,
            LocalDesktopDynamicCardBindings provides gallery,
            LocalDesktopWindowsCommentPresentation provides presentation) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { search { reply -> ui.thread(reply, 0L) } }
                if (!ui.threadVisible || !replies.visible) input()
                VideoCommentTab(listState, Modifier.weight(1f), success.info, state.replies, state.replyCount,
                    emotes, state.isRepliesLoading, state.isRepliesRefreshing, state.repliesError, state.isRepliesEnd,
                    state.voteCard, success.videoTags, onUpClick = userClick,
                    onSubReplyClick = { reply, target -> ui.thread(reply, target) }, onCommentReplyClick = replyClick,
                    onLoadMoreReplies = { ui.loadMore() }, onRefreshReplies = { ui.refresh() }, onImagePreview = previewClick,
                    onTimestampClick = timeClick, contentPadding = PaddingValues(bottom = 12.dp),
                    currentMid = state.currentMid, showUpFlag = state.showUpFlag, dissolvingIds = state.dissolvingIds,
                    onDeleteComment = { ui.delete(it) }, onDissolveStart = { ui.dissolve(it) },
                    onCommentLike = { ui.like(it) }, onCommentHate = { ui.hate(it) },
                    likedComments = state.likedComments, hatedComments = state.hatedComments,
                    onCommentUrlClick = urlClick, onReportComment = { id, reason -> ui.report(id, reason) },
                    onToggleTopComment = { ui.top(it) }, onCheckCommentFraud = { ui.fraud(it) },
                    showIdentityDecorations = decorations,
                    lightweightCommentRendering = shouldUseLightweightCommentRendering(1,
                        !assembly.section.nativePlayer.state.value.paused, listState.isScrollInProgress),
                    sortMode = state.sortMode, onSortModeChange = { ui.sort(it) },
                    // Windows has no separate original floating sort dock;
                    // retain the complete original sort control in this header.
                    showNativeSortHeader = true,
                    showSortControlInHeader = true)
            }
            val capturedRoutedThread = ui.routedThread
            if (ui.threadVisible && (replies.visible || capturedRoutedThread != null) && presentation.isCurrent()) {
                DesktopWindowsPlayerDialog("评论回复", { ui.closeThread() }) {
                    DesktopCommentDialogNavigationHost {
                        if (replies.visible && replies.rootReply != null) {
                        Column(Modifier.fillMaxSize()) {
                            VideoInlineSubReplyDetailContent(replies, state, emotes,
                                success.info.pages.firstOrNull { it.cid == success.info.cid }?.duration?.times(1000L),
                                onLoadMore = { ui.loadThread() }, onRefresh = { ui.refreshThread() },
                                onSortModeChange = { ui.sortThread(it) }, onDismiss = { ui.closeThread() },
                                // The original inline layout hides its separate root entry;
                                // returning from a conversation uses this same VM's root thread.
                                onRootCommentClick = { ui.conversationBack() }, onTimestampClick = timeClick,
                                onImagePreview = previewClick, onReplyClick = replyClick,
                                onConversationClick = { ui.conversation(it) }, onConversationBack = { ui.conversationBack() },
                                onDissolveStart = { ui.dissolveThread(it) }, onDeleteComment = { ui.deleteThread(it) },
                                onCheckCommentFraud = { ui.fraud(it) }, onCommentLike = { ui.like(it) },
                                onCommentHate = { ui.hate(it) }, onReportComment = { id, reason -> ui.report(id, reason) },
                                onUrlClick = urlClick, showIdentityDecorations = decorations,
                                onAvatarClick = { id -> id.toLongOrNull()?.let(userClick) }, modifier = Modifier.weight(1f))
                            input()
                        }
                        } else if (capturedRoutedThread != null) {
                            Column(Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                if (replies.isLoading) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载评论回复…")
                                } else {
                                    Text(replies.error ?: "回复加载失败")
                                    Spacer(Modifier.height(12.dp))
                                    TextButton(onClick = { ui.retryRoutedThread(capturedRoutedThread) }) {
                                        Text("重试")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            composerStamp?.takeIf { it.presentation === presentation && presentation.isCurrent() }?.let { stamp ->
                key(stamp) {
                    DesktopOriginalVideoCommentInputOverlay(composerVm, stamp, state,
                        currentVideoPositionMsProvider = {
                            if (presentation.isCurrent()) (assembly.section.nativePlayer.state.value.positionSeconds * 1000.0).toLong() else 0L
                        })
                }
            }
            fraudStatus?.takeIf { presentation.isCurrent() }?.let { captured ->
                DesktopWindowsPlayerDialog("评论检测", { presentation.dispatch { fraudStatus = null; vm.dismissFraudResult() } }, preferredHeightDp = 440) {
                    CommentFraudResultDialog(captured,
                        onDismiss = { presentation.dispatch { fraudStatus = null; vm.dismissFraudResult() } },
                        onDeleteComment = if (captured == CommentFraudStatus.SHADOW_BANNED) ({ if (state.fraudDetectRpid > 0L) ui.dissolve(state.fraudDetectRpid); Unit }) else null)
                }
            }
            preview?.takeIf { presentation.isCurrent() }?.let { captured ->
                key(captured) {
                    ImagePreviewDialog(captured.images, captured.index, sourceRect = captured.anchor?.rect,
                        sourceRects = captured.anchor?.galleryRects ?: emptyMap(), sourceKey = captured.anchor?.sourceKey,
                        sourceCornerRadiusDp = captured.anchor?.cornerRadiusDp ?: 0f, textContent = captured.text,
                        onDismiss = { if (preview === captured) preview = null })
                }
            }
        }
    }
}
