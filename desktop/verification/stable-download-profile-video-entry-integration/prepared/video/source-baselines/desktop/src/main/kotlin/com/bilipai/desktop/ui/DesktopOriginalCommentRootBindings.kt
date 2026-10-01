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

/** One owned Root adapter for the already installed full original comment states. */
internal class DesktopOriginalCommentRootOwner(
    val operations: DesktopDynamicCardOperations,
    val requests: DesktopVideoCommentRequests,
    val scope: CoroutineScope,
    val emotes: DesktopDynamicEmotes,
    val isOwned: () -> Boolean,
    val feedback: (String) -> Unit,
)

@Composable internal fun DesktopOriginalCommentRootBindings(
    repository: DesktopRepository,
    community: DesktopCommunityRepository,
    fraud: DesktopCommentFraudRoot,
    contentIdentity: Any,
    stillOwned: () -> Boolean,
    modifier: Modifier,
    content: @Composable (DesktopOriginalCommentRootOwner) -> Unit,
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
    val textShare = LocalDesktopTextShareBindings.current
    val currentStillOwned by rememberUpdatedState(stillOwned)
    key(contentIdentity, session, capturedEpoch) {
        val alive = remember { AtomicBoolean(true) }
        val exportLock = remember { Any() }
        val guard = repository.dynamicCacheSessionGuard
        val owner = remember { checkNotNull(guard.dynamicCacheOwner()) }
        val parent = rememberCoroutineScope()
        val scope = remember { CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])) }
        fun owned() = alive.get() && currentStillOwned() && locations.isActive() && owner.epoch == capturedEpoch &&
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
        val gallery = remember(operations, assets, clipboard, uriHandler, textShare) {
            DesktopSpaceAvatarPlatform(preferences.context, session.emotes, operations, assets,
                ::owned, clipboard, ::feedback, uriHandler::openUri, textShare = textShare, shareScope = scope)
        }
        val platform = remember(operations, clipboard, images, locations, textShare) {
            DesktopDynamicCommentPlatform(preferences.context, session.emotes, repository, community, operations,
                capturedEpoch, ::owned, clipboard, ::feedback, saveImage = { spec ->
                    val context = currentCoroutineContext()
                    val commitOwned: ((() -> Unit) -> Boolean) = { commit ->
                        guard.withCurrentDynamicCacheOwner(owner) {
                            synchronized(exportLock) {
                                if (!owned()) throw CancellationException("Original comment export owner retired")
                                if (!locations.withCommit(commit)) throw CancellationException("Reply export settings retired")
                            }
                        }
                    }
                    locations.save("BiliPai_comment_${System.currentTimeMillis()}.png",
                        checkpoint = { context.ensureActive(); if (!owned()) throw CancellationException("Original comment export owner retired") },
                        withOwnedCommit = commitOwned) { target ->
                        writeDesktopReplyCommentImage(spec, target.path, ::owned,
                            replaceExisting = target.replaceExisting, withOwnedCommit = commitOwned)
                    }
                }, pickImages = pickers::pickImages, textShare = textShare, shareScope = scope)
        }
        val requests = remember(operations, fraud) {
            DesktopVideoCommentOperationsBinding(operations, repository, images::read, fraud.records,
                operations::checkCommentStatus, fraud::recordPublished)
        }
        DisposableEffect(alive, assets, images, scope) {
            onDispose {
                synchronized(exportLock) { alive.set(false) }
                assets.close(); images.close(); scope.cancel()
            }
        }
        val ownerBindings = remember(operations, requests, scope) {
            DesktopOriginalCommentRootOwner(operations, requests, scope, session.emotes, ::owned, ::feedback)
        }
        if (owned()) CompositionLocalProvider(LocalDesktopCommentBindings provides platform,
            LocalDesktopDynamicCardBindings provides gallery,
            LocalDesktopBgmLottieLoader provides operations::loadBgmEmptyAnimation) {
            Box(modifier) {
                DesktopCommentDialogNavigationHost { content(ownerBindings) }
                SnackbarHost(snackbar)
            }
        }
    }
}
