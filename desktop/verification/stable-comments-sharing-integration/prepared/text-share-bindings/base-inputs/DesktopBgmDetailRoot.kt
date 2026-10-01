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
    val session = checkNotNull(LocalDesktopDynamicCardSession.current)
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    if (!session.matches(repository, capturedEpoch)) return
    val preferences = checkNotNull(LocalDesktopDynamicTimelinePreferences.current)
    val locations = checkNotNull(LocalDesktopImageSaveLocations.current)
    val saveParent = LocalDesktopDynamicSaveParent.current
    val clipboard = LocalDesktopTextClipboard.current
    val uriHandler = LocalUriHandler.current
    key(request.musicId, request.aid, request.cid, session, capturedEpoch) {
        val alive = remember { AtomicBoolean(true) }
        val exportLock = remember { Any() }
        val guard = repository.dynamicCacheSessionGuard
        val owner = remember { checkNotNull(guard.dynamicCacheOwner()) }
        val parent = rememberCoroutineScope()
        val scope = remember { CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])) }
        fun owned() = alive.get() && locations.isActive() && owner.epoch == capturedEpoch &&
            repository.sessionEpoch == capturedEpoch && session.isOwned()
        val operations = remember { DesktopDynamicCardOperations(repository, capturedEpoch, ::owned, session.emotes) }
        val images = remember { DesktopDynamicEditorSelectedImages(::owned, operations::withOwnedEditorImageAdmission) }
        val snackbar = remember { SnackbarHostState() }
        fun feedback(message: String) { if (owned()) scope.launch { snackbar.showSnackbar(message) } }
        val pickers = remember(images, saveParent) {
            DesktopDynamicEditorWindowsPickers(images, ::owned, { saveParent }, ::feedback)
        }
        val assets = remember(operations, locations, saveParent) {
            DesktopDynamicImageAssets(repository.httpClient, ::owned, guard, owner,
                selectTarget = { name, mime -> selectDynamicSaveTarget(name, mime, saveParent) },
                selectDirectory = { selectDynamicSaveDirectory(saveParent) }, imageSaveLocations = locations)
        }
        // Reuse the already installed gallery platform, never a second renderer/client/store.
        val gallery = remember(operations, assets, clipboard, uriHandler) {
            DesktopSpaceAvatarPlatform(preferences.context, session.emotes, operations, assets,
                ::owned, clipboard, ::feedback, uriHandler::openUri)
        }
        val platform = remember(operations, clipboard, images, locations) {
            DesktopDynamicCommentPlatform(preferences.context, session.emotes, repository, community, operations,
                capturedEpoch, ::owned, clipboard, ::feedback, saveImage = { spec ->
                    val context = currentCoroutineContext()
                    val commitOwned: ((() -> Unit) -> Boolean) = { commit ->
                        guard.withCurrentDynamicCacheOwner(owner) {
                            synchronized(exportLock) {
                                if (!owned()) throw CancellationException("BGM reply export owner retired")
                                if (!locations.withCommit(commit)) throw CancellationException("Reply export settings retired")
                            }
                        }
                    }
                    locations.save("BiliPai_comment_${System.currentTimeMillis()}.png",
                        checkpoint = { context.ensureActive(); if (!owned()) throw CancellationException("BGM reply export owner retired") },
                        withOwnedCommit = commitOwned) { target ->
                        writeDesktopReplyCommentImage(spec, target.path, ::owned,
                            replaceExisting = target.replaceExisting, withOwnedCommit = commitOwned)
                    }
                }, pickImages = pickers::pickImages)
        }
        val requests = remember(operations, fraud) {
            DesktopVideoCommentOperationsBinding(operations, repository, images::read, fraud.records,
                operations::checkCommentStatus, fraud::recordPublished)
        }
        val detailRequests = remember(operations) { operations.bgmDetailRequests() }
        val vm = remember(operations) { BgmDetailViewModel(scope, detailRequests) }
        val comments = remember(operations) { VideoCommentViewModel(scope, requests) }
        var route by remember { mutableStateOf<DesktopBgmCommentRoute?>(null) }
        DisposableEffect(alive, assets, images, scope) {
            onDispose {
                synchronized(exportLock) { alive.set(false) }
                assets.close(); images.close(); scope.cancel()
            }
        }
        if (owned()) CompositionLocalProvider(LocalDesktopCommentBindings provides platform,
            LocalDesktopDynamicCardBindings provides gallery,
            LocalDesktopBgmLottieLoader provides operations::loadBgmEmptyAnimation) {
            Box(Modifier.fillMaxSize()) {
                DesktopCommentDialogNavigationHost {
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
                SnackbarHost(snackbar)
            }
        }
    }
}
