package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.android.purebilibili.feature.audio.bgm.*
import com.android.purebilibili.feature.comment.CommentDetailScreen
import com.android.purebilibili.feature.comment.CommentDetailViewModel
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.appearance.LocalDesktopTextClipboard
import com.bilipai.desktop.audio.DesktopBgmMusicTarget
import com.bilipai.desktop.data.*
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopBgmCommentRoute(val oid: Long, val root: Long, val target: Long, val type: Int)

/** The page variants and nested route borrow one Root owner's requests and scope. */
internal class DesktopBgmDetailState(private val owner: DesktopOriginalCommentRootOwner) {
    val detailRequests = owner.operations.bgmDetailRequests()
    val detail = BgmDetailViewModel(owner.scope, detailRequests)
    val comments = VideoCommentViewModel(owner.scope, owner.requests)
    var route by mutableStateOf<DesktopBgmCommentRoute?>(null)
        private set

    fun openComment(oid: Long, root: Long, target: Long, type: Int) {
        if (owner.isOwned()) route = DesktopBgmCommentRoute(oid, root, target, type)
    }

    fun closeComment() {
        if (owner.isOwned()) route = null
    }
}

@Composable
internal fun rememberDesktopBgmDetailState(owner: DesktopOriginalCommentRootOwner): DesktopBgmDetailState =
    remember(owner) { DesktopBgmDetailState(owner) }

/** Original BGM and CommentDetail owners; all HTTP, images, emotes and records use Root bindings. */
@Composable internal fun DesktopBgmDetailRootHost(
    request: DesktopBgmMusicTarget.Detail,
    repository: DesktopRepository,
    community: DesktopCommunityRepository,
    fraud: DesktopCommentFraudRoot,
    nowPlayingBarOverlayVisible: Boolean,
    onBack: () -> Unit,
    onVideosClick: () -> Unit,
    onVideoClick: (String, Long, String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onLogin: () -> Unit,
    onMediaSearch: (String, String, String) -> Boolean,
) {
    DesktopOriginalCommentRootBindings(repository, community, fraud,
        Triple(request.musicId, request.aid, request.cid), { true }, Modifier.fillMaxSize()) { owner ->
        val requests = owner.requests
        val scope = owner.scope
        val page = rememberDesktopBgmDetailState(owner)
        val active = page.route
        if (active == null) BgmDetailScreen(request.musicId, request.aid, request.cid, request.showVideos,
            onBack = onBack, onVideosClick = onVideosClick, onVideoClick = onVideoClick,
            onUserClick = onUserClick, onCommentClick = page::openComment,
            onLinkClick = onLinkClick, onLogin = onLogin, viewModel = page.detail,
            commentViewModel = page.comments, requests = page.detailRequests,
            nowPlayingBarOverlayVisible = nowPlayingBarOverlayVisible, onMediaSearch = onMediaSearch)
        else key(owner, active) {
            val detailScope = remember { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) }
            val detail = remember { CommentDetailViewModel(detailScope, requests) }
            DisposableEffect(detailScope) { onDispose { detailScope.cancel() } }
            CommentDetailScreen(active.oid, active.root, active.target, active.type,
                onBack = page::closeComment, onOpenLink = onLinkClick, onUserClick = onUserClick,
                viewModel = detail, requests = requests)
        }
    }
}
