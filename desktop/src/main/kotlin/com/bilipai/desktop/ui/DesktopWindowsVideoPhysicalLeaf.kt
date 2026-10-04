package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.input.TextFieldValue
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.android.purebilibili.feature.video.screen.VideoDetailDomainEffects
import com.android.purebilibili.feature.video.ui.components.CommentEmoteTextField
import com.android.purebilibili.feature.video.ui.components.RichCommentText
import com.android.purebilibili.feature.video.subtitle.*
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.core.ui.LocalDetailedCommentTimeEnabled
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.feature.video.viewmodel.resolvePlaybackCompletionRepeatMode
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Required Root actions. Each callback belongs to the same physical Window/source; no new business authority. */
internal class DesktopWindowsVideoActions(
    val back: () -> Unit, val fullscreen: () -> Unit, val pictureInPicture: () -> Unit,
    val user: (Long) -> Unit, val video: (VideoCard) -> Unit,
    val download: (DesktopOriginalVideoOwnerAssembly, VideoPlaybackUiState.Success) -> Unit,
    val favorite: @Composable (DesktopOriginalVideoOwnerAssembly, VideoPlaybackUiState.Success, () -> Boolean) -> Unit,
    val overlay: @Composable () -> Unit,
    val enhancement: @Composable () -> Unit,
    val openLink: (String) -> Unit,
    val honorLink: (DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication, String) -> Unit,
    val login: () -> Unit, val danmakuSettings: () -> Unit, val toggleDanmaku: () -> Unit,
    val notice: (String) -> Unit,
    val focusChanged: (Boolean) -> Unit,
    val nativeKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
    val collectionQueue: @Composable (DesktopWindowsVideoCollectionQueuePresentation) -> Unit,
    val bgm: @Composable (DesktopWindowsVideoBgmPresentation) -> Unit,
)

/** Windows renderer over the installed original VM/owner/MPV. Native peer stays in Root; this leaf reports its viewport only. */
@Composable internal fun DesktopWindowsVideoPhysicalLeaf(
    route: BiliPaiNavKey.VideoDetail,
    shell: DesktopOriginalVideoShellOwner,
    active: Boolean,
    fullscreen: Boolean,
    pipActive: Boolean,
    preferences: PlayerPreferences,
    preferencesChanged: (PlayerPreferences) -> Unit,
    actions: DesktopWindowsVideoActions,
) {
    val ready by shell.slot.factoryReady.collectAsState()
    LaunchedEffect(shell, ready, route) { if (ready && active) shell.slot.requireAssembly() }
    val owner by shell.slot.assemblies.collectAsState()
    val assembly = owner?.takeIf { it.owns() }
    val platforms = LocalDesktopOriginalVideoRootPlatforms.current
    if (assembly == null || platforms == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("正在准备播放器")
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = actions.back) { Text("返回") }
        }
        return
    }
    val routeState = LocalDesktopOriginalRootVideoRouteState.current
    SideEffect { shell.publishVideoRouteState(assembly, routeState) }
    val native = assembly.section.nativePlayer
    val nativeSurface = checkNotNull(LocalDesktopWindowsNativeVideoSurface.current) {
        "Windows video requires its retained Root native surface"
    }
    check(nativeSurface.player === native) { "Windows video native surface belongs to another player" }
    val rootEnvironment = LocalDesktopOriginalVideoRootWindowEnvironment.current
    val viewportLease = remember(nativeSurface) { Any() }
    DisposableEffect(nativeSurface, route, viewportLease) { onDispose {
        nativeSurface.releaseViewport(route, viewportLease)
    } }
    val currentActive by rememberUpdatedState(active)
    fun current(): Boolean = currentActive && shell.slot.currentAssembly() === assembly && assembly.owns()
    val latestActions by rememberUpdatedState(actions)
    val latestPip by rememberUpdatedState(pipActive)
    val viewportFocus = remember(assembly) { androidx.compose.ui.focus.FocusRequester() }
    // Listen only to this actual native host and its one non-focusable MPV Canvas.
    // A comment editor is outside this component tree and retains its native input.
    DisposableEffect(assembly, native.surface) {
        val surface = native.surface
        val oldFocusable = surface.isFocusable
        surface.isFocusable = true
        val focus = object : java.awt.event.FocusAdapter() {
            override fun focusGained(event: java.awt.event.FocusEvent) {
                if(current() && !latestPip) latestActions.focusChanged(true)
            }
            override fun focusLost(event: java.awt.event.FocusEvent) {
                if(current()) latestActions.focusChanged(false)
            }
        }
        val mouse = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(event: java.awt.event.MouseEvent) {
                if(current() && !latestPip && surface.isShowing) {
                    viewportFocus.requestFocus()
                    surface.requestFocusInWindow()
                }
            }
        }
        val key = object : java.awt.event.KeyAdapter() {
            override fun keyPressed(event: java.awt.event.KeyEvent) {
                if(!event.isConsumed && current() && !latestPip && surface.isFocusOwner && surface.isShowing &&
                    latestActions.nativeKey(desktopWindowsNativeVideoKey(event))) event.consume()
            }
        }
        surface.addFocusListener(focus);surface.addMouseListener(mouse);surface.addKeyListener(key)
        val canvas = surface.components.filterIsInstance<java.awt.Canvas>().single()
        canvas.addMouseListener(mouse)
        onDispose {
            canvas.removeMouseListener(mouse);surface.removeMouseListener(mouse)
            surface.removeFocusListener(focus);surface.removeKeyListener(key);surface.isFocusable=oldFocusable
            latestActions.focusChanged(false)
        }
    }
    val state by native.state.collectAsState()
    val playback by shell.playback.state.collectAsState()
    val original by assembly.playback.uiState.collectAsState()
    val subject by assembly.playback.subjectSnapshot.collectAsState()
    val favoriteEvent by assembly.playback.favoriteFolderSaveEvent.collectAsState()
    val success = original as? VideoPlaybackUiState.Success
    val playlistItems by assembly.environment.playlist.playlist.collectAsState()
    val collectionQueueSource = assembly.native.current()?.takeIf { accepted ->
        current() && success?.info?.let { it.bvid == accepted.request.bvid && it.cid == accepted.request.cid } == true
    }
    var showCollection by remember(assembly, collectionQueueSource) { mutableStateOf(false) }
    var showPlaybackQueue by remember(assembly, collectionQueueSource) { mutableStateOf(false) }
    fun collectionQueueCurrent(): Boolean = current() && collectionQueueSource != null &&
        assembly.native.isCurrent(collectionQueueSource) && assembly.playback.captureDesktopPlaybackState().let {
            it is VideoPlaybackUiState.Success && it.info.bvid == collectionQueueSource.request.bvid &&
                it.info.cid == collectionQueueSource.request.cid
        }
    val bgmResult by assembly.playback.desktopBgmResult.collectAsState(null)
    val canOpenCollection = success?.info?.ugc_season != null && collectionQueueSource != null
    val canOpenPlaybackQueue = collectionQueueSource != null && playlistItems.isNotEmpty()
    val chapterResult by assembly.playback.desktopChapterResult.collectAsState(null)
    // The list is stamped by its accepted player-info result, never by the current screen.
    val chaptersSource = assembly.native.current()
    val chapters = chapterResult?.takeIf { result ->
        val accepted = chaptersSource
        current() && assembly.playback.captureDesktopChapterResult() === result && accepted != null &&
            accepted.request.bvid == result.bvid && accepted.request.cid == result.cid &&
            success != null && success.info.bvid == result.bvid && success.info.cid == result.cid
    }
    val resumeSuggestion by assembly.playback.resumePlaybackSuggestion.collectAsState()
    val engagement by assembly.domains.engagement.uiState.collectAsState()
    var bootstrapError by remember(assembly, route) { mutableStateOf<String?>(null) }
    val completion by remember(platforms.holder.settingsContext) {
        DesktopOriginalVideoControlSettings.getPlaybackCompletionBehavior(platforms.holder.settingsContext)
    }.collectAsState(DesktopOriginalVideoControlSettings.getPlaybackCompletionBehaviorSync(platforms.holder.settingsContext))
    LaunchedEffect(assembly, completion, state.sourceTitle, active) {
        if (current() && assembly.section.isOwned()) assembly.section.repeatMode = resolvePlaybackCompletionRepeatMode(completion)
    }
    var subtitleOverride by remember(assembly) { mutableStateOf<SubtitleDisplayMode?>(null) }
    val subtitleMode = subtitleOverride ?: resolveSubtitleDisplayModeByAutoPreference(preferences.subtitleAutoPreference,
        success?.subtitlePrimaryCues?.isNotEmpty() == true, success?.subtitleSecondaryCues?.isNotEmpty() == true,
        success?.subtitlePrimaryLikelyAi == true, success?.subtitleSecondaryLikelyAi == true, state.muted)
    val latestSubtitleMode by rememberUpdatedState(subtitleMode)
    DisposableEffect(assembly, platforms.subtitleMode) {
        val registration = platforms.subtitleMode.register(assembly.playback, { latestSubtitleMode }, { subtitleOverride = it })
        onDispose { registration.close() }
    }

    // Root keeps the Canvas peer while an opaque detail route covers this leaf.
    // A returning actual entry may retain only its exact admitted publication;
    // a new same-value/BV-only entry still follows the original load protocol.
    fun samePhysicalEntry(): Boolean = current() && rootEnvironment.currentKey() === route
    // Mount-local only: on Back, verify the saved exact receipt BEFORE any
    // current-source projection may replace it. This flag never grants ownership.
    var presentationVerified by remember(assembly, nativeSurface, route.openId, route.bvid, route.cid) { mutableStateOf(false) }
    SideEffect {
        if (presentationVerified && samePhysicalEntry())
            nativeSurface.recordPresentedSource(route, assembly, ::samePhysicalEntry)
    }
    LaunchedEffect(assembly, route.openId, route.bvid, route.cid, route.resumePositionMs, active) {
        if (!active) return@LaunchedEffect
        try {
            native.state.first { actual ->
                if (!samePhysicalEntry()) throw CancellationException("Windows video entry retired")
                if (!actual.ready && actual.error != null) error(actual.error!!)
                actual.ready
            }
            if (!samePhysicalEntry()) throw CancellationException("Windows video entry retired")
            if (nativeSurface.retainPresentedEntry(route, assembly, ::samePhysicalEntry)) {
                presentationVerified = true
                return@LaunchedEffect
            }
            val prior = assembly.native.current()
            val retained = shell.playback.openVideoDetail(VideoCard(route.bvid, "", route.coverUrl, "", 0, 0,
                preferredCid = route.cid), route.resumePositionMs, keepMatchingSource = true)
            if (samePhysicalEntry()) {
                nativeSurface.recordBootstrap(route, assembly, prior, retained)
                nativeSurface.recordPresentedSource(route, assembly, ::samePhysicalEntry)
                presentationVerified = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (current()) bootstrapError = failure.message ?: "播放器初始化失败" }
    }
    CompositionLocalProvider(LocalDesktopOriginalVideoHolderPlatform provides platforms.holder,
        LocalDesktopCommentBindings provides platforms.holder.commentsPlatform) {
        // These are state-domain effects only. They bind the existing four VMs without mounting the phone screen.
        VideoDetailDomainEffects(platforms.holder.settingsContext, active, original, subject, favoriteEvent,
            assembly.playback, assembly.domains.engagement, assembly.domains.composer, assembly.domains.supplement)
    }
    val preferredSort = DesktopOriginalReplySettings.getCommentDefaultSortModeSync(platforms.holder.settingsContext.pluginContext)
    LaunchedEffect(assembly, success?.info?.aid, success?.info?.owner?.mid, active, preferredSort) {
        if (active && current() && success != null) assembly.domains.comments.init(success.info.aid,
            success.info.owner.mid, preferredSortMode=CommentSortMode.fromApiMode(preferredSort), expectedReplyCount = success.info.stat.reply)
    }
    LaunchedEffect(assembly, route.commentRootRpid, route.commentTargetRpid, success?.info?.aid, active) {
        if (active && current() && success != null && route.commentRootRpid > 0L)
            assembly.domains.comments.openSubReplyFromRoute(route.commentRootRpid, route.commentTargetRpid)
    }
    LaunchedEffect(assembly, success?.info?.bvid, success?.info?.cid, route.startAudio, active) {
        if (route.startAudio && current() && success?.info?.bvid == route.bvid) {
            assembly.native.current()?.takeIf { it.request.bvid == success.info.bvid && it.request.cid == success.info.cid }?.let { expected ->
                assembly.native.admitPlaybackDispatch(expected) { if(current()) native.setAudioOnly(true) }
            }
        }
    }

    // The original Nav entry may leave composition while a detail route covers
    // it. Preserve its panel intent through the entry's existing saveable owner.
    var detailsOpen by rememberSaveable(assembly) { mutableStateOf(false) }
    var detailsTab by rememberSaveable(assembly, stateSaver = Saver<DesktopWindowsVideoDetailsTab, String>(
        save = { it.name }, restore = { DesktopWindowsVideoDetailsTab.valueOf(it) },
    )) { mutableStateOf(DesktopWindowsVideoDetailsTab.INTRODUCTION) }
    LaunchedEffect(assembly, route.commentRootRpid, route.commentTargetRpid) {
        if (current() && route.commentRootRpid > 0L) {
            detailsTab = DesktopWindowsVideoDetailsTab.COMMENTS
            detailsOpen = true
        }
    }
    val composerState by assembly.domains.composer.uiState.collectAsState()
    // UI selection survives closing the panel; the existing composer remains the sole text authority.
    var commentDraft by remember(assembly) { mutableStateOf(TextFieldValue(composerState.commentDraft)) }
    LaunchedEffect(composerState.commentDraft) {
        if (commentDraft.text != composerState.commentDraft) commentDraft = TextFieldValue(composerState.commentDraft)
    }
    fun command(block: () -> Unit): Boolean {
        val accepted = assembly.native.current() ?: return false
        if (!current()) return false
        var dispatched = false
        val admitted = assembly.native.admitPlaybackDispatch(accepted) { if (current()) { block(); dispatched = true } }
        return admitted && dispatched
    }
    fun seekChapter(expected: DesktopOriginalVideoChapterResult, accepted: DesktopOriginalVideoAcceptedPublication, positionMs: Long) {
        if (!current() || !assembly.native.isCurrent(accepted)) return
        assembly.native.admitPlaybackDispatch(accepted) {
            if (!current()) return@admitPlaybackDispatch
            val session = assembly.playback.captureDesktopLoadState()
            val duration = native.state.value.durationSeconds
            val durationMs = if (duration.isFinite() && duration > 0.0) (duration * 1000.0).toLong() else 0L
            if (accepted.request.bvid == expected.bvid && accepted.request.cid == expected.cid &&
                isDesktopOriginalVideoChapterSeekCurrent(expected, assembly.playback.captureDesktopChapterResult(),
                    session.currentBvid, session.currentCid, session.currentLoadRequestToken, positionMs, durationMs)) {
                assembly.playback.seekTo(positionMs)
            }
        }
    }
    fun setSpeed(speed: Double) {
        if (current()) {
            assembly.playback.applyPlaybackSpeedFromUi(speed.toFloat())
            preferencesChanged(preferences.copy(speed = speed))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        // Keep one Row and one native slot through every width/fullscreen/panel change.
        // A narrow window still reserves real sibling space: a heavyweight Canvas cannot be covered by a Compose sheet.
        val detailsWidth = minOf(360.dp, maxWidth * .43f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (current()) actions.back() }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                        Text(success?.info?.title ?: "视频", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Fill all remaining height; there is no phone-derived fraction or maximum 380dp video height.
                var viewportSize by remember(native) { mutableStateOf(IntSize.Zero) }
                Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).onSizeChanged { viewportSize = it }
                    .onGloballyPositioned { coordinates ->
                        // Store pixels only, never a LayoutCoordinates object.
                        // Host-hidden still keeps the peer; outgoing entries may
                        // not overwrite the current physical route's viewport.
                        if (rootEnvironment.currentKey() === route && rootEnvironment.owns() &&
                            shell.slot.currentAssembly() === assembly && assembly.owns() && !pipActive) {
                            val origin = coordinates.positionInWindow()
                            nativeSurface.reportViewport(route, viewportLease,
                                Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height))
                        }
                    }
                    .focusRequester(viewportFocus).onFocusChanged { if(current()) actions.focusChanged(it.hasFocus) }.focusable()) {
                    if (active && !pipActive) {
                        DesktopVideoCommandPopup(viewportSize, {
                            Box(Modifier.fillMaxSize()) {
                                actions.overlay()
                                val positionMs = (state.positionSeconds * 1000.0).toLong()
                                val primary = if (subtitleMode == SubtitleDisplayMode.PRIMARY_ONLY || subtitleMode == SubtitleDisplayMode.BILINGUAL)
                                    success?.subtitlePrimaryCues?.let { resolveSubtitleTextAt(it, positionMs) } else null
                                val secondary = if (subtitleMode == SubtitleDisplayMode.SECONDARY_ONLY || subtitleMode == SubtitleDisplayMode.BILINGUAL)
                                    success?.subtitleSecondaryCues?.let { resolveSubtitleTextAt(it, positionMs) } else null
                                if (current() && success?.subtitleOwnerBvid == success?.info?.bvid &&
                                    success?.subtitleOwnerCid == success?.info?.cid && (primary != null || secondary != null))
                                    Column(Modifier.align(Alignment.BottomCenter).padding(14.dp).background(Color.Black.copy(alpha=.65f)).padding(6.dp)) {
                                        primary?.let { Text(it, color=Color.White, fontSize=20.sp) }
                                        secondary?.let { Text(it, color=Color.White, fontSize=16.sp) }
                                    }
                            }
                        }, native.surface)
                    } else Text("正在浮窗播放", color=Color.White, modifier=Modifier.align(Alignment.Center))
                }
                if (bootstrapError != null || playback.error != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(bootstrapError ?: playback.error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { if (current()) shell.playback.retry() }) { Text("重试") }
                }
                DesktopWindowsVideoControlBar(
                    state = state, sourceVersion = native.currentSourceSnapshot()?.sourceVersion ?: 0L,
                    enabled = current() && success != null, fullscreen = fullscreen, detailsOpen = detailsOpen,
                    hasPrevious = shell.playback.hasPrevious, hasNext = shell.playback.hasNext,
                    canPictureInPicture = !pipActive && success != null && state.videoCodec != null && !state.audioOnly,
                    qualities = success?.let { value -> value.qualityIds.mapIndexed { index, id -> id to (value.qualityLabels.getOrNull(index) ?: id.toString()) } }.orEmpty(),
                    selectedQuality = success?.currentQuality,
                    canOpenCollection = canOpenCollection, canOpenPlaybackQueue = canOpenPlaybackQueue,
                    onOpenCollection = { if (collectionQueueCurrent()) { showPlaybackQueue = false; showCollection = true } },
                    onOpenPlaybackQueue = { if (collectionQueueCurrent()) { showCollection = false; showPlaybackQueue = true } },
                    chapters = chapters, chaptersSource = chaptersSource, onChapterSeek = ::seekChapter,
                    onPlayPause = { command { native.togglePause() } },
                    onPrevious = { if (current()) shell.playback.previous() }, onNext = { if (current()) shell.playback.next() },
                    onMute = { if(command { native.setMuted(!state.muted) }) preferencesChanged(preferences.copy(muted=!state.muted)) },
                    onVolume = { value -> if(command { native.setVolume(value) }) preferencesChanged(preferences.copy(volume=value)) },
                    onSpeed = ::setSpeed,
                    onQuality = { quality -> if (current()) shell.playback.switchQuality(quality) },
                    onSeek = { seconds -> if (current()) shell.playback.seekTo(seconds) },
                    onPictureInPicture = { if (current()) actions.pictureInPicture() },
                    onFullscreen = { if (current()) actions.fullscreen() },
                    onDetails = { if (current()) detailsOpen = !detailsOpen },
                    onOpenIntroduction = { if (current()) {
                        detailsTab = DesktopWindowsVideoDetailsTab.INTRODUCTION
                        detailsOpen = true
                    } },
                    enhancement = actions.enhancement,
                )
            }
            if (detailsOpen) DesktopWindowsVideoDetailsPanel(
                modifier = Modifier.width(detailsWidth).fillMaxHeight(), selectedTab = detailsTab,
                onTabChange = { detailsTab = it }, onClose = { if (current()) detailsOpen = false },
                current = ::current, related = playback.related, onVideo = actions.video,
                introduction = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (success != null) {
                            Text(success.info.title, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { if (current()) actions.user(success.info.owner.mid) }) { Text(success.info.owner.name) }
                            collectionQueueSource?.let { source ->
                                DesktopWindowsVideoMetadataSection(assembly, success.info, source,
                                    platforms.holder.settingsContext, platforms.portrait.creatorTeam,
                                    ::current, actions.user) { url -> latestActions.honorLink(assembly, source, url) }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { if (current()) assembly.domains.engagement.toggleLike() }) { Text(if(engagement.isLiked) "已点赞" else "点赞") }
                                actions.favorite(assembly, success, ::current)
                                TextButton(onClick = { if (current()) assembly.domains.engagement.toggleFollow() }) { Text(if(engagement.isFollowing) "已关注" else "关注") }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { if(current()) assembly.domains.engagement.toggleWatchLater() }) { Text("稍后再看") }
                                TextButton(onClick = { if(current()) assembly.domains.engagement.openCoinDialog() }) { Text("投币") }
                                TextButton(onClick = { if (current()) actions.download(assembly, success) }) { Text("下载当前画质") }
                            }
                            collectionQueueSource?.let { source ->
                                bgmResult?.takeIf { desktopWindowsVideoBgmMatchesSource(it,
                                    assembly.playback.captureDesktopBgmResult(), source.request) }?.let { music ->
                                    actions.bgm(DesktopWindowsVideoBgmPresentation(assembly, source, music, ::current))
                                }
                            }
                            Text(success.info.desc, style = MaterialTheme.typography.bodyMedium)
                            if (success.info.pages.size > 1) {
                                Text("分P", style = MaterialTheme.typography.titleSmall)
                                success.info.pages.forEachIndexed { index, part ->
                                    TextButton(onClick = { if (current()) shell.playback.playPart(index) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("P${index + 1} · ${part.part}")
                                    }
                                }
                            }
                        } else Text("正在读取视频信息", style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                        Text("播放设置", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(state.audioOnly, onClick = { if(command { native.setAudioOnly(!state.audioOnly) }) preferencesChanged(preferences.copy(audioOnly=!state.audioOnly)) }, label = { Text("仅音频") })
                            FilterChip(preferences.danmaku.enabled, onClick = { if (current()) actions.toggleDanmaku() }, label = { Text("弹幕") })
                            TextButton(onClick = { if (current()) actions.danmakuSettings() }) { Text("弹幕设置") }
                        }
                        Text("视频编码", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("hev1" to "HEVC", "av01" to "AV1", "avc1" to "H.264").forEach { (codec, label) ->
                                FilterChip(preferences.videoCodecPreference == codec,
                                    onClick = { if(current()) preferencesChanged(preferences.copy(videoCodecPreference=codec)) }, label = { Text(label) })
                            }
                        }
                        Text("字幕显示", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SubtitleDisplayMode.entries.forEach { mode -> FilterChip(subtitleMode==mode,
                                onClick={if(current()) subtitleOverride=mode}, label={Text(when(mode) {SubtitleDisplayMode.OFF->"字幕关闭";SubtitleDisplayMode.PRIMARY_ONLY->"主字幕";SubtitleDisplayMode.SECONDARY_ONLY->"副字幕";SubtitleDisplayMode.BILINGUAL->"双语字幕"})}) }
                        }
                        success?.let { value ->
                            if (value.availableAudioQualities.isNotEmpty()) {
                                Text("音轨画质", style = MaterialTheme.typography.labelLarge)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    value.availableAudioQualities.forEach { audio ->
                                        FilterChip(value.requestedAudioQuality == audio.preferenceId,
                                            onClick = { if (current()) shell.playback.selectAudioQuality(audio.preferenceId) }, label = { Text(audio.label) })
                                    }
                                }
                            }
                            if (value.subtitleTracks.isNotEmpty()) {
                                Text("字幕轨道", style = MaterialTheme.typography.labelLarge)
                                value.subtitleTracks.forEach { track -> TextButton(onClick={if(current()) assembly.playback.selectSubtitleTrack(track.trackKey)}) {Text(track.lanDoc)} }
                            }
                        }
                        Text("当前视频：${state.videoCodec ?: "—"} · ${state.videoWidth}×${state.videoHeight}\n音频：${state.audioCodec ?: "—"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                comments = {
                    if (success != null) CompositionLocalProvider(LocalDesktopCommentBindings provides platforms.holder.commentsPlatform) {
                        DesktopWindowsVideoComments(assembly, current = ::current, onUser = actions.user, login = actions.login,
                            openLink=actions.openLink, seek=shell.playback::seekTo, draft=commentDraft, onDraftChange={commentDraft=it})
                    } else Text("正在读取评论", style = MaterialTheme.typography.bodyMedium)
                },
            )
        }
    }

    if (success != null && collectionQueueSource != null && collectionQueueCurrent() &&
        (showCollection || showPlaybackQueue)) {
        CompositionLocalProvider(LocalDesktopOriginalVideoHolderPlatform provides platforms.holder) {
            actions.collectionQueue(DesktopWindowsVideoCollectionQueuePresentation(assembly, success,
                collectionQueueSource, showCollection && canOpenCollection, showPlaybackQueue && canOpenPlaybackQueue,
                ::collectionQueueCurrent, { showCollection = false }, { showPlaybackQueue = false }))
        }
    }

    // Consume only the original VM suggestion; its coordinator owns the threshold,
    // saved-history lookup, preference and once-per-target acknowledgement.
    val resumeAnchor = assembly.native.current()?.takeIf { expected ->
        success?.info?.let { it.bvid == route.bvid && it.bvid == expected.request.bvid && it.cid == expected.request.cid } == true
    }
    resumeSuggestion?.takeIf { current() && resumeAnchor != null }?.let { suggestion ->
        fun stillSuggested(): Boolean = current() &&
            assembly.playback.resumePlaybackSuggestion.value === suggestion &&
            assembly.native.isCurrent(checkNotNull(resumeAnchor))
        AlertDialog(
            onDismissRequest = { if(stillSuggested()) assembly.playback.dismissResumePlaybackSuggestion() },
            title = { Text("继续播放") },
            text = { Text("检测到上次播放到 ${suggestion.targetLabel}（${FormatUtils.formatDuration(suggestion.positionMs)}），是否跳转继续播放？") },
            confirmButton = { TextButton(onClick = {
                // The original method can save history before launching its owned load;
                // do not execute that IO while holding the native/source admission locks.
                if(stillSuggested()) assembly.playback.continueResumePlaybackSuggestion()
            }) { Text("跳转") } },
            dismissButton = { TextButton(onClick = {
                if(stillSuggested()) assembly.playback.dismissResumePlaybackSuggestion()
            }) { Text("稍后") } })
    }
    if(engagement.coinDialogVisible) AlertDialog(
        onDismissRequest={if(current()) assembly.domains.engagement.setCoinDialogVisible(false)},
        title={Text("投币")}, text={Text("选择投币数量")},
        confirmButton={Row {listOf(1,2).forEach {count-> TextButton(onClick={if(current()) {
            assembly.domains.engagement.doCoin(count,false);assembly.domains.engagement.setCoinDialogVisible(false)
        }}) {Text("${count}枚")} }}},
        dismissButton={TextButton(onClick={if(current()) assembly.domains.engagement.setCoinDialogVisible(false)}) {Text("取消")}})
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
private fun desktopWindowsNativeVideoKey(event:java.awt.event.KeyEvent):androidx.compose.ui.input.key.KeyEvent =
    androidx.compose.ui.input.key.KeyEvent(
        androidx.compose.ui.input.key.Key(event.keyCode, event.keyLocation.takeUnless {
            it == java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN
        } ?: java.awt.event.KeyEvent.KEY_LOCATION_STANDARD),
        androidx.compose.ui.input.key.KeyEventType.KeyDown, event.keyChar.code,
        isAltPressed = event.isAltDown, isCtrlPressed = event.isControlDown,
        isMetaPressed = event.isMetaDown, isShiftPressed = event.isShiftDown, nativeEvent = event,
    )

@Composable private fun DesktopWindowsVideoComments(assembly: DesktopOriginalVideoOwnerAssembly,
    current: () -> Boolean, onUser: (Long) -> Unit, login: () -> Unit, openLink:(String)->Unit, seek:(Double)->Unit,
    draft: TextFieldValue, onDraftChange: (TextFieldValue) -> Unit,
) {
    val vm = assembly.domains.comments
    val state by vm.commentState.collectAsState()
    val replies by vm.subReplyState.collectAsState()
    val composer by assembly.domains.composer.uiState.collectAsState()
    val platform=LocalDesktopCommentBindings.current
    val detailedCommentTimeEnabled = LocalDetailedCommentTimeEnabled.current
    var emotes by remember(assembly,platform) {mutableStateOf(platform.emotes.snapshot())}
    LaunchedEffect(assembly,platform) {val loaded=platform.emotes.ensureLoaded();if(current()&&platform.isOwned())emotes=loaded}
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("评论 ${state.replyCount}", Modifier.weight(1f))
            TextButton(onClick = { if (current()) vm.refreshComments() }, enabled = !state.isRepliesRefreshing) { Text("刷新") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CommentSortMode.entries.forEach { mode ->
                FilterChip(state.sortMode == mode, onClick = { if (current()) vm.setSortMode(mode) }, label = { Text(mode.label) })
            }
        }
        state.repliesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        CommentEmoteTextField(draft, onValueChange={if(current()) {onDraftChange(it);assembly.domains.composer.updateCommentDraft(it.text)}},
            emoteUrls=emotes,enabled=current(),readOnly=false,hint=state.replyTarget?.let {"回复 ${it.member.uname}"}?:"评论",
            textStyle=MaterialTheme.typography.bodyMedium.copy(color=MaterialTheme.colorScheme.onSurface),hintColor=MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor=MaterialTheme.colorScheme.primary,onBeginEditing={},modifier=Modifier.fillMaxWidth().height(64.dp))
        Row {
            TextButton(onClick = { if (current()) vm.cancelReply() }) { Text("取消回复") }
            Button(onClick = { if (current()) {
                if (state.currentMid <= 0L) login() else vm.sendComment(composer.commentDraft)
            } }, enabled = current() && !state.isSending && composer.commentDraft.isNotBlank()) { Text(if (state.isSending) "发送中" else "发送") }
        }
        state.replies.forEach { reply ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    TextButton(onClick = { if (current()) onUser(reply.mid) }) { Text(reply.member.uname) }
                    // Original PiliPlus rule: top-level comments are always precise to the second.
                    Text(FormatUtils.formatPrecisePublishTime(reply.ctime, pattern="yyyy-MM-dd HH:mm:ss"),
                        style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                    RichCommentText(reply.content.message,14.sp,emoteMap=emotes,content=reply.content,
                        onUserClick={if(current())onUser(it)},onUrlClick={if(current())openLink(it)},onTimestampClick={if(current())seek(it/1000.0)})
                    Row {
                        TextButton(onClick = { if (current()) vm.likeComment(reply.rpid) }) { Text("赞 ${reply.like}") }
                        TextButton(onClick = { if (current()) vm.replyTo(reply) }) { Text("回复") }
                        TextButton(onClick = { if (current()) vm.openSubReply(reply) }) { Text("楼中楼 ${reply.rcount}") }
                    }
                }
            }
        }
        if (!state.isRepliesEnd) TextButton(onClick = { if (current()) vm.loadComments() }, enabled = !state.isRepliesLoading) { Text("加载更多") }
        if (replies.visible) {
            TextButton(onClick = { if (current()) vm.closeSubReply() }) { Text("关闭楼中楼") }
            replies.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            replies.items.forEach { child ->
                Column(Modifier.fillMaxWidth()) {
                    Text("${child.member.uname}: ${child.content.message}")
                    Text(FormatUtils.formatCommentTime(child.ctime, detailedTimeEnabled=detailedCommentTimeEnabled),
                        style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { if (current()) vm.refreshSubReplies() }) { Text("刷新楼中楼") }
            if (!replies.isEnd) TextButton(onClick = { if (current()) vm.loadMoreSubReplies() }) { Text("加载更多回复") }
        }
    }
}
