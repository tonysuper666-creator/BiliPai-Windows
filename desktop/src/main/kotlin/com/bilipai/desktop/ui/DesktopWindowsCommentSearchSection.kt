package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.ui.components.CommentSearchSheet
import com.android.purebilibili.feature.video.viewmodel.VideoCommentViewModel
import java.util.concurrent.atomic.AtomicBoolean

/** Search borrows the installed comment VM. This lease only owns its optional
 * pagination request; closing never cancels the comment owner or playback. */
internal class DesktopWindowsCommentSearchBinding(
    private val viewModel: VideoCommentViewModel,
    private val sourceLease: Any,
    private val stillOwned: () -> Boolean,
    private val admission: ((() -> Unit) -> Boolean),
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private var request: Any? = null
    fun isOwned(): Boolean = alive.get() && stillOwned()
    fun admit(action: () -> Unit): Boolean {
        if (!isOwned()) return false
        var applied = false
        return admission { if (isOwned()) { action(); applied = true } } && applied
    }
    fun load() {
        if (isOwned()) request = viewModel.loadAllCommentsForSearch(sourceLease, ::isOwned, ::admit)
    }
    override fun close() {
        if (alive.compareAndSet(true, false)) viewModel.cancelFullCommentSearch(request, sourceLease)
    }
}

/** Only the container changes; the complete original search/filter/sort,
 * loading, retry, highlight and reply actions render in the owned native peer. */
@Composable internal fun DesktopWindowsCommentSearchModalSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DesktopWindowsPlayerDialog("搜索评论", onDismissRequest, preferredHeightDp = 720) {
        Column(modifier.fillMaxSize().background(containerColor), content = content)
    }
}

@Composable internal fun DesktopWindowsCommentSearchSection(
    viewModel: VideoCommentViewModel,
    sourceOwner: DesktopOriginalVideoAcceptedPublication,
    upMid: Long,
    stillOwned: () -> Boolean,
    admission: ((() -> Unit) -> Boolean),
    onComment: (ReplyItem) -> Unit,
) {
    key(viewModel, sourceOwner) {
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestAdmission by rememberUpdatedState(admission)
        val latestComment by rememberUpdatedState(onComment)
        var visible by remember { mutableStateOf(false) }
        val owned: () -> Boolean = { latestOwned() }
        TextButton(onClick = {
            if (owned()) latestAdmission { if (owned()) visible = true }
        }, enabled = owned()) { Text("搜索评论") }
        if (visible && owned()) {
            val binding = remember { DesktopWindowsCommentSearchBinding(viewModel, sourceOwner,
                owned, { action -> latestAdmission(action) }) }
            DisposableEffect(binding) { onDispose { binding.close() } }
            val state by viewModel.commentState.collectAsState()
            val fullState by viewModel.fullSearchState.collectAsState()
            val fullReplies by viewModel.fullSearchReplies.collectAsState()
            val platform = LocalDesktopCommentBindings.current
            val searchPlatform = remember(binding, platform) {
                object : DesktopCommentPlatform by platform {
                    override fun isOwned(): Boolean = binding.isOwned() && platform.isOwned()
                    override fun copyText(text: String, label: String) {
                        // The gate receives the action; OS clipboard I/O stays outside it.
                        if (platform.isOwned() && binding.admit {}) platform.copyText(text, label)
                    }
                    override fun showFeedback(message: String) {
                        binding.admit { if (platform.isOwned()) platform.showFeedback(message) }
                    }
                }
            }
            CompositionLocalProvider(LocalDesktopCommentBindings provides searchPlatform) {
                CommentSearchSheet(replies = state.replies, fullReplies = fullReplies,
                    fullSearchState = fullState, onLoadAllComments = binding::load,
                    upMid = upMid, onCommentClick = { reply -> binding.admit { latestComment(reply) } },
                    onSubReplyClick = { root -> binding.admit { latestComment(root) } },
                    onDismiss = { binding.admit { visible = false } })
            }
        }
    }
}
