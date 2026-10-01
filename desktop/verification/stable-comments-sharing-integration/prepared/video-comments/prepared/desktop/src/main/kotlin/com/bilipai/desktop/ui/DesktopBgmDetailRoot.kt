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

private data class DesktopBgmCommentRoute(val oid: Long, val root: Long, val target: Long, val type: Int)

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
        val operations = owner.operations
        val requests = owner.requests
        val scope = owner.scope
        fun owned() = owner.isOwned()
        val detailRequests = remember(operations) { operations.bgmDetailRequests() }
        val vm = remember(operations) { BgmDetailViewModel(scope, detailRequests) }
        val comments = remember(operations) { VideoCommentViewModel(scope, requests) }
        var route by remember { mutableStateOf<DesktopBgmCommentRoute?>(null) }
        val active = route
        if (active == null) BgmDetailScreen(request.musicId, request.aid, request.cid, request.showVideos,
            onBack = onBack, onVideosClick = onVideosClick, onVideoClick = onVideoClick,
            onUserClick = onUserClick, onCommentClick = { oid, root, target, type ->
                if (owned()) route = DesktopBgmCommentRoute(oid, root, target, type)
            }, onLinkClick = onLinkClick, onLogin = onLogin, viewModel = vm,
            commentViewModel = comments, requests = detailRequests,
            nowPlayingBarOverlayVisible = nowPlayingBarOverlayVisible, onMediaSearch = onMediaSearch)
        else key(active) {
            val detailScope = remember { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) }
            val detail = remember { CommentDetailViewModel(detailScope, requests) }
            DisposableEffect(detailScope) { onDispose { detailScope.cancel() } }
            CommentDetailScreen(active.oid, active.root, active.target, active.type,
                onBack = { route = null }, onOpenLink = onLinkClick, onUserClick = onUserClick,
                viewModel = detail, requests = requests)
        }
    }
}
