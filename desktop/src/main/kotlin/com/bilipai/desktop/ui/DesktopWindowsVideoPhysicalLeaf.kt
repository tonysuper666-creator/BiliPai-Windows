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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
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
    val login: () -> Unit, val danmakuSettings: () -> Unit, val toggleDanmaku: () -> Unit,
    val notice: (String) -> Unit,
    val focusChanged: (Boolean) -> Unit,
    val nativeKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
)

/** Windows renderer over the installed original VM/owner/MPV. No phone Holder, movable content or transition layout. */
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

    // The Canvas below is mounted once before waiting for the real native worker.
    // A covered route is canceled; no guessed ready/Success or duplicate load precedes decoder initialization.
    LaunchedEffect(assembly, route.bvid, route.cid, route.resumePositionMs, active) {
        if (!active) return@LaunchedEffect
        try {
            native.state.first { actual ->
                if (!current()) throw CancellationException("Windows video entry retired")
                if (!actual.ready && actual.error != null) error(actual.error!!)
                actual.ready
            }
            if (!current()) throw CancellationException("Windows video entry retired")
            shell.playback.openVideoDetail(VideoCard(route.bvid, "", route.coverUrl, "", 0, 0,
                preferredCid = route.cid), route.resumePositionMs, keepMatchingSource = true)
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

    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
    val showSidebar = !fullscreen && maxWidth >= 1050.dp
    val viewportHeight = if (fullscreen) (maxHeight - 172.dp).coerceAtLeast(80.dp)
        else minOf(380.dp, (maxHeight * .46f).coerceAtLeast(80.dp))
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (current()) actions.back() }) { Text("返回") }
                Text(success?.info?.title ?: "视频", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines=1, overflow=TextOverflow.Ellipsis)
                TextButton(onClick = { if (current()) actions.fullscreen() }) { Text(if (fullscreen) "退出全屏" else "全屏") }
            }
            // One unchanging native component slot: resize/fullscreen does not move it across layouts.
            var viewportSize by remember(native) { mutableStateOf(IntSize.Zero) }
            Box(Modifier.fillMaxWidth().height(viewportHeight).background(Color.Black).onSizeChanged { viewportSize = it }
                .focusRequester(viewportFocus).onFocusChanged { if(current()) actions.focusChanged(it.hasFocus) }.focusable()) {
                if (active && !pipActive) {
                    SwingPanel(factory = { native.surface }, background = Color.Black, modifier = Modifier.fillMaxSize())
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
            fun command(block: () -> Unit): Boolean {
                val accepted = assembly.native.current() ?: return false
                if (!current()) return false
                var dispatched = false
                val admitted = assembly.native.admitPlaybackDispatch(accepted) { if (current()) { block(); dispatched = true } }
                return admitted && dispatched
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { command { native.togglePause() } }, enabled = state.ready && success != null) {
                    Text(if (state.paused || state.ended) "播放" else "暂停")
                }
                TextButton(onClick = { if (current()) shell.playback.previous() }, enabled = shell.playback.hasPrevious) { Text("上一集") }
                TextButton(onClick = { if (current()) shell.playback.next() }, enabled = shell.playback.hasNext) { Text("下一集") }
                TextButton(onClick = { if(command { native.setMuted(!state.muted) }) preferencesChanged(preferences.copy(muted=!state.muted)) }) { Text(if (state.muted) "取消静音" else "静音") }
                Slider(value = state.volume.toFloat().coerceIn(0f, 100f), onValueChange = { value ->
                    if(command { native.setVolume(value.toDouble()) }) preferencesChanged(preferences.copy(volume = value.toDouble()))
                }, valueRange = 0f..100f, enabled=current() && state.ready && success!=null, modifier = Modifier.width(120.dp))
                TextButton(onClick = { if (current()) actions.pictureInPicture() }, enabled = !pipActive && success != null && state.videoCodec != null && !state.audioOnly) { Text("浮窗") }
            }
            var scrub by remember(assembly) { mutableStateOf<Float?>(null) }
            val duration = state.durationSeconds.toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
            Slider(value = (scrub ?: state.positionSeconds.toFloat()).coerceIn(0f, duration),
                onValueChange = { scrub = it }, onValueChangeFinished = {
                    scrub?.let { if (current()) shell.playback.seekTo(it.toDouble()) }; scrub = null
                }, valueRange = 0f..duration, enabled = success != null && state.durationSeconds > 0)
            if (!fullscreen) LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.75, 1.0, 1.25, 1.5, 2.0).forEach { speed ->
                    FilterChip(state.speed == speed, onClick = { if (current()) {
                        assembly.playback.applyPlaybackSpeedFromUi(speed.toFloat())
                        preferencesChanged(preferences.copy(speed = speed))
                    } }, label = { Text("${speed}×") })
                }
                FilterChip(state.audioOnly, onClick = { if(command { native.setAudioOnly(!state.audioOnly) }) preferencesChanged(preferences.copy(audioOnly=!state.audioOnly)) }, label = { Text("仅音频") })
                FilterChip(preferences.danmaku.enabled, onClick = { if (current()) actions.toggleDanmaku() }, label = { Text("弹幕") })
                TextButton(onClick = { if (current()) actions.danmakuSettings() }) { Text("弹幕设置") }
                SubtitleDisplayMode.entries.forEach { mode -> FilterChip(subtitleMode==mode,
                    onClick={if(current()) subtitleOverride=mode}, label={Text(when(mode) {SubtitleDisplayMode.OFF->"字幕关闭";SubtitleDisplayMode.PRIMARY_ONLY->"主字幕";SubtitleDisplayMode.SECONDARY_ONLY->"副字幕";SubtitleDisplayMode.BILINGUAL->"双语字幕"})}) }
            } }
            item { actions.enhancement() }
            item { bootstrapError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { playback.error?.let { failure ->
                Row { Text(failure, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { if (current()) shell.playback.retry() }) { Text("重试") } }
            } }
            if (success != null) {
                item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    success.qualityIds.forEachIndexed { i, quality ->
                        FilterChip(success.currentQuality == quality, onClick = { if (current()) shell.playback.switchQuality(quality) },
                            label = { Text(success.qualityLabels.getOrNull(i) ?: quality.toString()) })
                    }
                } }
                item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (current()) actions.user(success.info.owner.mid) }) { Text(success.info.owner.name) }
                    TextButton(onClick = { if (current()) assembly.domains.engagement.toggleLike() }) { Text(if(engagement.isLiked) "已点赞" else "点赞") }
                    actions.favorite(assembly, success, ::current)
                    TextButton(onClick = { if (current()) actions.download(assembly, success) }) { Text("下载当前画质") }
                    TextButton(onClick = { if (current()) assembly.domains.engagement.toggleFollow() }) { Text(if(engagement.isFollowing) "已关注" else "关注") }
                    TextButton(onClick = { if(current()) assembly.domains.engagement.toggleWatchLater() }) { Text("稍后再看") }
                    TextButton(onClick = { if(current()) assembly.domains.engagement.openCoinDialog() }) { Text("投币") }
                } }
                item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    success.availableAudioQualities.forEach { audio ->
                        FilterChip(success.requestedAudioQuality == audio.preferenceId,
                            onClick = { if (current()) shell.playback.selectAudioQuality(audio.preferenceId) }, label = { Text(audio.label) })
                    }
                    success.subtitleTracks.forEach { track -> TextButton(onClick={if(current()) assembly.playback.selectSubtitleTrack(track.trackKey)}) {Text(track.lanDoc)} }
                } }
                    item { Text(success.info.desc) }
                    if (success.info.pages.size > 1) items(success.info.pages.size) { i ->
                        TextButton(onClick = { if (current()) shell.playback.playPart(i) }) { Text("P${i + 1} · ${success.info.pages[i].part}") }
                    }
                    item { CompositionLocalProvider(LocalDesktopCommentBindings provides platforms.holder.commentsPlatform) {
                        DesktopWindowsVideoComments(assembly, current = ::current, onUser = actions.user, login = actions.login, openLink=actions.openLink, seek=shell.playback::seekTo)
                    } }
            }
            }
        }
        if(showSidebar) LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("相关推荐", style = MaterialTheme.typography.titleMedium) }
            items(playback.related, key = { it.bvid }) { video ->
                OutlinedButton(onClick = { if (current()) actions.video(video) }, modifier = Modifier.fillMaxWidth()) { Text(video.title) }
            }
        }
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
) {
    val vm = assembly.domains.comments
    val state by vm.commentState.collectAsState()
    val replies by vm.subReplyState.collectAsState()
    val composer by assembly.domains.composer.uiState.collectAsState()
    val platform=LocalDesktopCommentBindings.current
    val detailedCommentTimeEnabled = LocalDetailedCommentTimeEnabled.current
    var emotes by remember(assembly,platform) {mutableStateOf(platform.emotes.snapshot())}
    LaunchedEffect(assembly,platform) {val loaded=platform.emotes.ensureLoaded();if(current()&&platform.isOwned())emotes=loaded}
    var draft by remember(assembly) {mutableStateOf(TextFieldValue(composer.commentDraft))}
    LaunchedEffect(composer.commentDraft) {if(draft.text!=composer.commentDraft)draft=TextFieldValue(composer.commentDraft)}
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("评论 ${state.replyCount}", Modifier.weight(1f))
            CommentSortMode.entries.forEach { mode ->
                FilterChip(state.sortMode == mode, onClick = { if (current()) vm.setSortMode(mode) }, label = { Text(mode.label) })
            }
            TextButton(onClick = { if (current()) vm.refreshComments() }, enabled = !state.isRepliesRefreshing) { Text("刷新") }
        }
        state.repliesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        CommentEmoteTextField(draft, onValueChange={if(current()) {draft=it;assembly.domains.composer.updateCommentDraft(it.text)}},
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
