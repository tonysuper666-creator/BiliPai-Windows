package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.video.screen.AudioModeScreen
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.CancellationException

/** Physical typed leaves of the SAME original retained owner. Neither switching
 * to BV audio nor covering the video creates a ListenAudioSession/second player.
 * Root keeps this owner and every original domain when another leaf is on top.
 */
@Composable internal fun DesktopOriginalVideoPhysicalLeaf(
    key: BiliPaiNavKey,
    shell: DesktopOriginalVideoShellOwner,
    commands: DesktopOriginalRootRouteCommands,
    active: Boolean,
    windowBack: () -> Unit,
    openBilibiliLink: (String) -> Unit,
    immersivePlaybackChanged: (Boolean) -> Unit,
    personalLists: DesktopPersonalListsRoot,
    collectionPlaylist: DesktopOriginalVideoPlaylistBinding,
    pendingOwner: @Composable () -> Unit,
) {
    require(key is BiliPaiNavKey.VideoDetail || key is BiliPaiNavKey.AudioMode || key is BiliPaiNavKey.NativeMusic)
    val factoryReady by shell.slot.factoryReady.collectAsState()
    LaunchedEffect(shell, factoryReady, key) {
        if (factoryReady) shell.slot.requireAssembly()
    }
    val owner by shell.slot.assemblies.collectAsState()
    val platforms = LocalDesktopOriginalVideoRootPlatforms.current
    val current = owner?.takeIf { it.owns() }
    if (current == null || platforms == null) {
        // Genuine factory/owner installation in progress; no playback Success or
        // fallback controller is manufactured while Root drains the predecessor.
        pendingOwner()
        return
    }
    CompositionLocalProvider(LocalDesktopOriginalVideoNativeCarrierActive provides active) {
        if (key is BiliPaiNavKey.AudioMode && (key.collectionMediaId > 0L || key.collectionSeasonId > 0L)) {
            DesktopOriginalListenVideoCollectionHost(key, personalLists, collectionPlaylist,
                LocalDesktopOriginalVideoRootWindowEnvironment.current, active, windowBack) { collection, back ->
                DesktopOriginalVideoPhysicalLeafContent(key, current, platforms, shell, commands, active,
                    back, openBilibiliLink, immersivePlaybackChanged, collection)
            }
        } else {
            DesktopOriginalVideoPhysicalLeafContent(key, current, platforms, shell, commands, active,
                windowBack, openBilibiliLink, immersivePlaybackChanged)
        }
    }
}

@Composable private fun DesktopOriginalVideoPhysicalLeafContent(
    key: BiliPaiNavKey,
    current: DesktopOriginalVideoOwnerAssembly,
    platforms: DesktopOriginalVideoRootWindowPlatforms,
    shell: DesktopOriginalVideoShellOwner,
    commands: DesktopOriginalRootRouteCommands,
    active: Boolean,
    windowBack: () -> Unit,
    openBilibiliLink: (String) -> Unit,
    immersivePlaybackChanged: (Boolean) -> Unit,
    collection: DesktopOriginalListenVideoCollectionEntry? = null,
) {
    if (key is BiliPaiNavKey.VideoDetail) {
        val routeState = LocalDesktopOriginalRootVideoRouteState.current
        SideEffect { shell.publishVideoRouteState(current, routeState) }
        DesktopOriginalVideoHolderRoute(key, current, platforms,
            routeState,
            desktopOriginalRootVideoActions(key, commands, windowBack,
                openBilibiliLink, immersivePlaybackChanged))
        return
    }
    var collectionInitialTarget by remember(current, key, collection) {
        mutableStateOf<DesktopOriginalListenCollectionPlaybackTarget?>(null)
    }
    val audioBvid = collectionInitialTarget?.bvid ?: when (key) {
        is BiliPaiNavKey.AudioMode -> key.sourceBvid
        is BiliPaiNavKey.NativeMusic -> key.bvid
        else -> error("Expected an original audio route")
    }
    val audioCid = collectionInitialTarget?.cid ?: when (key) {
        is BiliPaiNavKey.AudioMode -> key.sourceCid
        is BiliPaiNavKey.NativeMusic -> key.cid
        else -> error("Expected an original audio route")
    }
    val audioResume = (key as? BiliPaiNavKey.AudioMode)?.sourceResumePositionMs ?: 0L
    val musicTitle = collection?.key?.collectionTitle?.takeIf(String::isNotBlank) ?:
        (key as? BiliPaiNavKey.NativeMusic)?.title?.ifEmpty { "背景音乐" }
    val windowEnvironment = LocalDesktopOriginalVideoRootWindowEnvironment.current
    val latestActive by rememberUpdatedState(active)
    var audioSubjectRequested by remember(current, key, collection) { mutableStateOf(false) }
    var collectionQueueUnavailable by remember(current, key, collection) { mutableStateOf(false) }
    // Android leaves have a route-scoped VM. Windows retains the SAME original
    // VM. Seed explicit NativeMusic and different AudioMode subjects; matching
    // AudioMode keeps the original paused/resume/reuse semantics unchanged.
    // Start the same real original request before native preparation. Its
    // required suspend stage owns pending/fatal/cancellation and manual retry.
    LaunchedEffect(current, platforms, key, active, collection) {
        if (!active || audioSubjectRequested) return@LaunchedEffect
        if (!latestActive || !windowEnvironment.owns() || windowEnvironment.currentKey() !== key || !current.owns())
            throw CancellationException("Original audio route retired")
        if (collection != null && originalCollectionOwnsPlayback(key, current)) {
            // Genuine accepted native/error provenance survives a covered page.
            // Recomposition must not restart the first song after an automatic successor.
            audioSubjectRequested = true
            return@LaunchedEffect
        }
        val collectionTarget = collection?.viewModel?.captureDesktopPlaybackTarget()
        if (collection != null && collectionTarget == null) {
            collectionQueueUnavailable = true
            return@LaunchedEffect
        }
        if (collectionTarget != null) collectionInitialTarget = collectionTarget
        val requestedBvid = collectionTarget?.bvid ?: audioBvid
        val requestedCid = collectionTarget?.cid ?: audioCid
        // Both raw read ports already belong to this same sole full original VM.
        // beginLoadRequest keeps the prior accepted currentCid, so Loading must
        // compare its currentRequest; a Ready page switch instead uses currentCid.
        val loading = current.playback.captureDesktopPlaybackState() is
            com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState.Loading
        val session = current.playback.captureDesktopLoadState()
        val targetBvid = if (loading) session.currentRequest?.bvid else session.currentBvid
        val targetCid = if (loading) session.currentRequest?.cid else session.currentCid
        val sameSubject = targetBvid == requestedBvid && (requestedCid <= 0L || targetCid == requestedCid)
        val seed = collectionTarget != null || key is BiliPaiNavKey.NativeMusic ||
            (key is BiliPaiNavKey.AudioMode && audioBvid.isNotBlank() && !sameSubject)
        if (seed) {
            val bootstrapSource = if (collectionTarget != null)
                DesktopVideoBootstrapSeed.captureCollection(windowEnvironment, current, key as BiliPaiNavKey.AudioMode, collectionTarget)
                else DesktopVideoBootstrapSeed.captureAudio(windowEnvironment, current, key)
            current.playback.attachPlayer(current.section)
            current.playback.loadVideo(bvid = requestedBvid, cid = requestedCid, autoPlay = true,
                fallbackResumePositionMs = audioResume,
                force = loading || collectionTarget != null, desktopBootstrapSource = bootstrapSource)
        }
        audioSubjectRequested = true
    }
    if (collectionQueueUnavailable) {
        DesktopOriginalListenCollectionQueueChanged(windowBack)
        return
    }
    if ((key is BiliPaiNavKey.NativeMusic || key is BiliPaiNavKey.AudioMode) && !audioSubjectRequested) {
        platforms.InitialNativeSurface(Modifier.fillMaxSize())
        return
    }
    // BV audio has no visual player Surface in AudioModeScreen. Keep the SAME
    // existing Section carrier mounted without covering loading/error UI; Root
    // still owns its native lifetime and the real request must await preparation.
    Box(Modifier.fillMaxSize()) {
        platforms.InitialNativeSurface(Modifier.size(1.dp))
        CompositionLocalProvider(
        LocalDesktopOriginalAudioModePlatform provides platforms.audio,
        LocalDesktopOriginalMusicUiPlatform provides platforms.music,
        LocalDesktopOriginalPlayerSettingsContext provides platforms.holder.settingsContext,
        LocalDesktopOriginalVideoContentBindings provides platforms.content,
        LocalDesktopCommentBindings provides platforms.holder.commentsPlatform,
        LocalDesktopOriginalSubtitleModeBinding provides platforms.subtitleMode,
    ) {
        AudioModeScreen(viewModel = remember(current, key, windowEnvironment) {
            DesktopOriginalVideoConsumedViews(current,
                manualAudioCurrent = { latestActive && windowEnvironment.owns() &&
                    windowEnvironment.currentKey() === key && current.owns() },
                manualAudioRetained = { windowEnvironment.owns() &&
                    windowEnvironment.commands.containsEntry(key) && current.owns() },
                audioBootstrapWindow = windowEnvironment, audioBootstrapEntry = key,
                audioInitialLoadManagedByRoot = true)
        },
            engagementViewModel = current.domains.engagement,
            composerViewModel = platforms.audio.composer,
            supplementViewModel = current.domains.supplement,
            onBack = windowBack,
            onVideoModeClick = { bvid, cid ->
                // Immutable original fields and the actual readback. This is a
                // typed route switch; no ordinary open/load precedes the Holder.
                val accepted = current.native.current()
                var position: Long? = null
                if (active && accepted != null && accepted.request.bvid == bvid && accepted.request.cid == cid &&
                    current.native.admitPlaybackDispatch(accepted) {
                        position = current.section.currentPosition.coerceAtLeast(0L)
                    }) {
                    val destination = BiliPaiNavKey.VideoDetail(bvid, cid,
                        resumePositionMs = checkNotNull(position), sourceRoute = "audio_mode")
                    if (key is BiliPaiNavKey.NativeMusic) {
                        if (latestActive && windowEnvironment.owns() && windowEnvironment.currentKey() == key &&
                            current.owns() && commands.back()) commands.video(destination)
                    } else commands.video(destination)
                }
            },
            isInPipMode = LocalDesktopOriginalVideoRootWindowEnvironment.current.let {
                // This is the same physical Root's PiP state, projected through
                // its actual navigation/window bindings below.
                it.inPictureInPicture()
            },
            initialBvid = audioBvid, initialCid = audioCid,
            initialResumePositionMs = audioResume, titleOverride = musicTitle,
            desktopInitialFailureContent = { displayedError ->
                val latestDisplayedError by rememberUpdatedState(displayedError)
                val failureLogin = DesktopVideoFailureLoginIntent.captureAudio(windowEnvironment,
                    shell, current, key, displayedError) {
                    latestActive && windowEnvironment.owns() && windowEnvironment.currentKey() === key &&
                        current.owns() && current.playback.captureDesktopPlaybackState() === latestDisplayedError
                }
                if (failureLogin != null) {
                    com.android.purebilibili.core.ui.components.AppTextButton(onClick = {
                        (commands as? DesktopOriginalRootRouteAssembly)?.loginFromReadFailure(failureLogin)
                    }) { com.android.purebilibili.core.ui.components.AppText("登录后重试") }
                }
            })
        }
    }
}

/** Reuse requires actual original request/native provenance for the SAME key.
 * A matching BV string or a surviving page object is insufficient. */
private fun originalCollectionOwnsPlayback(key: BiliPaiNavKey,
    assembly: DesktopOriginalVideoOwnerAssembly): Boolean {
    val accepted = assembly.native.current()
    val origin = accepted?.bootstrapOrigin
    if (accepted != null && origin != null && origin.seed.route === key && origin.seed.assembly === assembly &&
        origin.matchesResolvedRequest(accepted.request)) return true
    val failed = assembly.playback.desktopBootstrapReadFailure() ?: return false
    if (failed.source.accepted.seed.route !== key || failed.source.accepted.seed.assembly !== assembly) return false
    var displayed = false
    failed.source.admit(false) {
        val actual = assembly.captureLoadState()
        displayed = actual.currentRequest === failed.source.accepted.request &&
            actual.currentLoadRequestToken == failed.source.accepted.requestToken &&
            assembly.playback.captureDesktopPlaybackState() === failed.displayedError
    }
    return displayed
}
