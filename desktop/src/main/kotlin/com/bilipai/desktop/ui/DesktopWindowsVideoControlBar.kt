package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.feature.video.ui.overlay.normalizeViewPointSegments
import com.android.purebilibili.feature.video.ui.overlay.findViewPointSegmentAt
import com.android.purebilibili.core.util.FormatUtils
import com.bilipai.desktop.player.PlayerState
import top.yukonga.miuix.kmp.basic.PlainTooltip as MiuixPlainTooltip
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox as MiuixTooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults as MiuixTooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState as rememberMiuixTooltipState

/** Shared original glass/fallback surface. The heavyweight video is never sampled by this layer. */
@Composable
internal fun DesktopWindowsPlayerSurface(modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp), content: @Composable () -> Unit) {
    DesktopWindowsGlassSurface(modifier = modifier, shape = shape, content = content)
}

/** Every operation is supplied by the existing source owner; this bar owns only popup/scrub UI state. */
@Composable
internal fun DesktopWindowsVideoControlBar(
    state: PlayerState, sourceVersion: Long, enabled: Boolean, fullscreen: Boolean,
    detailsOpen: Boolean, hasPrevious: Boolean, hasNext: Boolean, canPictureInPicture: Boolean,
    qualities: List<Pair<Int, String>>, selectedQuality: Int?,
    canOpenCollection: Boolean, canOpenPlaybackQueue: Boolean,
    onOpenCollection: () -> Unit, onOpenPlaybackQueue: () -> Unit,
    canOpenInteraction: Boolean, onSendDanmaku: () -> Unit, onShareVideo: () -> Unit,
    chapters: DesktopOriginalVideoChapterResult?, chaptersSource: DesktopOriginalVideoAcceptedPublication?,
    onChapterSeek: (DesktopOriginalVideoChapterResult, DesktopOriginalVideoAcceptedPublication, Long) -> Unit,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onMute: () -> Unit, onVolume: (Double) -> Unit, onSpeed: (Double) -> Unit,
    onQuality: (Int) -> Unit, onSeek: (Double) -> Unit, onPictureInPicture: () -> Unit,
    onFullscreen: () -> Unit, onDetails: () -> Unit, onOpenIntroduction: () -> Unit,
    sponsorSkip: @Composable () -> Unit,
    enhancement: @Composable () -> Unit,
) {
    val durationMs = state.durationSeconds.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L
    val segments = remember(chapters, durationMs) { normalizeViewPointSegments(chapters?.points.orEmpty(), durationMs) }
    DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            DesktopWindowsThinSeek(state.positionSeconds, state.durationSeconds, sourceVersion,
                enabled && state.durationSeconds > 0.0, chapters, chaptersSource, onChapterSeek, onSeek)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val expanded = maxWidth >= 940.dp
                val showEnhancementStatus = maxWidth >= 520.dp
                var more by remember { mutableStateOf(false) }
                var speedMenu by remember { mutableStateOf(false) }
                var qualityMenu by remember { mutableStateOf(false) }
                var volumeMenu by remember { mutableStateOf(false) }
                var chapterMenu by remember(chapters, chaptersSource, sourceVersion) { mutableStateOf(false) }
                LaunchedEffect(enabled) {
                    if (!enabled) { more = false; speedMenu = false; qualityMenu = false; volumeMenu = false; chapterMenu = false }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    DesktopWindowsPlayerIconButton(tooltip = if (state.paused || state.ended) "播放" else "暂停", onClick = onPlayPause, enabled = enabled && state.ready, modifier = Modifier.size(44.dp)) {
                        Icon(if (state.paused || state.ended) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (state.paused || state.ended) "播放" else "暂停")
                    }
                    if (expanded) {
                        DesktopWindowsPlayerIconButton(tooltip = "上一集", onClick = onPrevious, enabled = enabled && hasPrevious, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "上一集")
                        }
                        DesktopWindowsPlayerIconButton(tooltip = "下一集", onClick = onNext, enabled = enabled && hasNext, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "下一集")
                        }
                    }
                    Text("${FormatUtils.formatDuration((state.positionSeconds.coerceAtLeast(0.0) * 1000).toLong())} / " +
                        FormatUtils.formatDuration((state.durationSeconds.coerceAtLeast(0.0) * 1000).toLong()),
                        Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (expanded) {
                        Box {
                            DesktopWindowsPlayerIconButton(tooltip = "音量与静音", onClick = { volumeMenu = true }, enabled = enabled, modifier = Modifier.size(44.dp)) {
                                Icon(if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = "音量与静音")
                            }
                            DesktopWindowsPlayerMenu(volumeMenu, onDismissRequest = { volumeMenu = false }, preferredHeight = 144.dp) {
                                DropdownMenuItem(text = { Text(if (state.muted) "取消静音" else "静音") }, onClick = onMute)
                                Text("音量 ${state.volume.toInt()}%", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                                Slider(state.volume.toFloat().coerceIn(0f, 100f), { onVolume(it.toDouble()) },
                                    Modifier.width(180.dp).padding(horizontal = 12.dp), enabled = enabled && state.ready, valueRange = 0f..100f)
                            }
                        }
                        Box {
                            TextButton(onClick = { speedMenu = true }, enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = "倍速" }) { Text("${state.speed}×") }
                            DesktopWindowsPlayerMenu(speedMenu, onDismissRequest = { speedMenu = false }, preferredHeight = 256.dp) {
                                listOf(.75, 1.0, 1.25, 1.5, 2.0).forEach { speed ->
                                    DropdownMenuItem(text = { Text("${speed}×") }, onClick = { onSpeed(speed); speedMenu = false })
                                }
                            }
                        }
                        Box {
                            TextButton(onClick = { qualityMenu = true }, enabled = enabled && qualities.isNotEmpty(),
                                modifier = Modifier.semantics { contentDescription = "画质" }) {
                                Text(qualities.firstOrNull { it.first == selectedQuality }?.second ?: "画质", maxLines = 1)
                            }
                            DesktopWindowsPlayerMenu(qualityMenu, onDismissRequest = { qualityMenu = false },
                                preferredHeight = (qualities.size * 48 + 16).coerceIn(64, 420).dp) {
                                qualities.forEach { (id, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { onQuality(id); qualityMenu = false })
                                }
                            }
                        }
                    }
                    if (showEnhancementStatus && chapters != null && chaptersSource != null && segments.isNotEmpty()) Box {
                        DesktopWindowsPlayerIconButton(tooltip = "视频章节", onClick = { chapterMenu = true }, enabled = enabled, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.ListAlt, contentDescription = "视频章节")
                        }
                        DesktopWindowsPlayerMenu(chapterMenu, onDismissRequest = { chapterMenu = false },
                            preferredHeight = (segments.size * 48 + 16).coerceIn(64, 420).dp) {
                            segments.forEach { segment ->
                                DropdownMenuItem(text = {
                                    Text("${FormatUtils.formatDuration(segment.fromMs)} · ${segment.content}", maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }, enabled = enabled, onClick = { onChapterSeek(chapters, chaptersSource, segment.fromMs); chapterMenu = false })
                            }
                        }
                    }
                    sponsorSkip()
                    DesktopVideoEnhancementCompactSlot(showStatus = showEnhancementStatus) { enhancement() }
                    DesktopWindowsPlayerIconButton(tooltip = "详情", onClick = onDetails, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "详情",
                            tint = if (detailsOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                    if (expanded) DesktopWindowsPlayerIconButton(tooltip = "浮窗", onClick = onPictureInPicture,
                        enabled = enabled && canPictureInPicture, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "浮窗")
                    }
                    DesktopWindowsPlayerIconButton(tooltip = if (fullscreen) "退出全屏" else "全屏", onClick = onFullscreen, modifier = Modifier.size(44.dp)) {
                        Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (fullscreen) "退出全屏" else "全屏")
                    }
                    Box {
                        DesktopWindowsPlayerIconButton(tooltip = "更多播放操作", onClick = { more = true }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多播放操作")
                        }
                        DesktopWindowsPlayerMenu(more, onDismissRequest = { more = false },
                            preferredHeight = if (expanded) (64 + (if (canOpenCollection) 48 else 0) +
                                 (if (canOpenPlaybackQueue) 48 else 0) + 96).dp else 516.dp) {
                            if (!expanded) {
                                DropdownMenuItem(text = { Text("上一集") }, enabled = enabled && hasPrevious,
                                    onClick = { onPrevious(); more = false })
                                DropdownMenuItem(text = { Text("下一集") }, enabled = enabled && hasNext,
                                    onClick = { onNext(); more = false })
                                DropdownMenuItem(text = { Text(if (state.muted) "取消静音" else "静音") }, enabled = enabled,
                                    onClick = onMute)
                                Text("音量 ${state.volume.toInt()}%", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                                Slider(state.volume.toFloat().coerceIn(0f, 100f), { onVolume(it.toDouble()) },
                                    Modifier.width(200.dp).padding(horizontal = 12.dp), enabled = enabled && state.ready, valueRange = 0f..100f)
                                HorizontalDivider()
                                listOf(.75, 1.0, 1.25, 1.5, 2.0).forEach { speed ->
                                    DropdownMenuItem(text = { Text("倍速 ${speed}×") }, enabled = enabled,
                                        onClick = { onSpeed(speed); more = false })
                                }
                                HorizontalDivider()
                                qualities.forEach { (id, label) -> DropdownMenuItem(text = { Text("画质 · $label") }, enabled = enabled,
                                    onClick = { onQuality(id); more = false }) }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("浮窗") }, enabled = enabled && canPictureInPicture,
                                    onClick = { onPictureInPicture(); more = false })
                            }
                            if (!showEnhancementStatus && chapters != null && chaptersSource != null && segments.isNotEmpty())
                                DropdownMenuItem(text = { Text("视频章节") }, enabled = enabled,
                                    onClick = { more = false; chapterMenu = true })
                            if (canOpenCollection) DropdownMenuItem(text = { Text("视频合集") }, enabled = enabled,
                                onClick = { more = false; onOpenCollection() })
                            if (canOpenPlaybackQueue) DropdownMenuItem(text = { Text("播放队列") }, enabled = enabled,
                                onClick = { more = false; onOpenPlaybackQueue() })
                            DropdownMenuItem(text = { Text("发送弹幕") }, enabled = enabled && canOpenInteraction,
                                onClick = { more = false; onSendDanmaku() })
                            DropdownMenuItem(text = { Text("分享视频") }, enabled = enabled && canOpenInteraction,
                                onClick = { more = false; onShareVideo() })
                            DropdownMenuItem(text = { Text("简介、分P与播放设置") }, onClick = { onOpenIntroduction(); more = false })
                        }
                        if (!showEnhancementStatus && chapters != null && chaptersSource != null)
                            DesktopWindowsPlayerMenu(chapterMenu, onDismissRequest = { chapterMenu = false },
                                preferredHeight = (segments.size * 48 + 16).coerceIn(64, 420).dp) {
                                segments.forEach { segment ->
                                    DropdownMenuItem(text = { Text("${FormatUtils.formatDuration(segment.fromMs)} · ${segment.content}") },
                                        enabled = enabled, onClick = { onChapterSeek(chapters, chaptersSource, segment.fromMs); chapterMenu = false })
                                }
                            }
                    }
                }
            }
        }
    }
}

/** Reuse the original Miuix tooltip without adding a long-click action to the button. */
@Composable
private fun DesktopWindowsPlayerIconButton(
    tooltip: String, onClick: () -> Unit, enabled: Boolean = true,
    modifier: Modifier = Modifier, content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val tooltipState = rememberMiuixTooltipState(isPersistent = true)
    LaunchedEffect(hovered, focused, enabled) {
        if (enabled && (hovered || focused)) tooltipState.show() else tooltipState.dismiss()
    }
    MiuixTooltipBox(
        positionProvider = MiuixTooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above, 4.dp),
        tooltip = { MiuixPlainTooltip {
            Text(tooltip, color = MiuixTooltipDefaults.plainTooltipContentColor,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } },
        state = tooltipState, focusable = false, enableUserInput = false,
    ) {
        IconButton(onClick = { tooltipState.dismiss(); onClick() }, enabled = enabled,
            modifier = modifier, interactionSource = interactionSource, content = content)
    }
}

/** One 44dp input region: the thin rail retains precise seek; its chapter labels
 * jump to the original normalized start on click. Drag and keyboard always seek precisely. */
@Composable
private fun DesktopWindowsThinSeek(position: Double, duration: Double, sourceVersion: Long,
    enabled: Boolean, chapters: DesktopOriginalVideoChapterResult?, chaptersSource: DesktopOriginalVideoAcceptedPublication?,
    onChapterSeek: (DesktopOriginalVideoChapterResult, DesktopOriginalVideoAcceptedPublication, Long) -> Unit, onSeek: (Double) -> Unit) {
    val total = duration.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val durationMs = if (duration.isFinite() && duration > 0.0) (duration * 1000.0).toLong() else 0L
    val segments = remember(chapters, durationMs) { normalizeViewPointSegments(chapters?.points.orEmpty(), durationMs) }
    var scrub by remember(sourceVersion, chapters, chaptersSource) { mutableStateOf<Double?>(null) }
    val latestSeek by rememberUpdatedState(onSeek)
    val latestChapterSeek by rememberUpdatedState(onChapterSeek)
    val value = (scrub ?: position.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, total)
    val currentSegment = findViewPointSegmentAt(segments, (value * 1000.0).toLong())
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
    val active = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "播放进度"
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..total.toFloat())
            if (!enabled) disabled()
            setProgress { requested -> if (enabled) { latestSeek(requested.toDouble().coerceIn(0.0, total)); true } else false }
        }.onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) false else {
                val target = when (event.key) {
                    Key.DirectionLeft -> value - 5.0
                    Key.DirectionRight -> value + 5.0
                    Key.MoveHome -> 0.0
                    Key.MoveEnd -> total
                    else -> null
                }
                if (target == null) false else { latestSeek(target.coerceIn(0.0, total)); true }
            }
        }.focusable(enabled).pointerInput(enabled, total, sourceVersion, chapters, chaptersSource) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                fun seconds(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * total
                val chapterClick = segments.isNotEmpty() && down.position.y >= size.height / 2f
                var dragged = false
                scrub = seconds(down.position.x)
                down.consume()
                try {
                    val released = drag(down.id) { change ->
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                        scrub = seconds(change.position.x); change.consume()
                    }
                    if (released) scrub?.let { selected ->
                        if (chapterClick && !dragged && chapters != null && chaptersSource != null) {
                            findViewPointSegmentAt(segments, (selected * 1000.0).toLong())?.let {
                                latestChapterSeek(chapters, chaptersSource, it.fromMs)
                            }
                        } else latestSeek(selected)
                    }
                } finally { scrub = null }
            }
        }) {
            val y = if (segments.isEmpty()) size.height / 2f else 14.dp.toPx()
            val end = (size.width * (value / total)).toFloat()
            drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
            drawLine(active, Offset(0f, y), Offset(end, y), strokeWidth = 3.dp.toPx())
            segments.forEach { segment ->
                val x = size.width * (segment.fromMs.toDouble() / durationMs).toFloat()
                drawLine(inactive.copy(alpha = .8f), Offset(x, y - 3.dp.toPx()), Offset(x, y + 3.dp.toPx()), strokeWidth = 1.dp.toPx())
            }
            drawCircle(active, radius = (if (scrub == null) 3.dp else 5.dp).toPx(), center = Offset(end, y))
        }
        if (segments.isNotEmpty()) Row(Modifier.fillMaxWidth().height(18.dp).align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically) {
            var cursor = 0L
            segments.forEach { segment ->
                if (segment.fromMs > cursor) Spacer(Modifier.weight((segment.fromMs - cursor).toFloat()))
                Text(segment.content, Modifier.weight((segment.toMs - segment.fromMs).toFloat()).padding(horizontal = 2.dp),
                    color = if (currentSegment === segment) active else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                cursor = segment.toMs
            }
            if (cursor < durationMs) Spacer(Modifier.weight((durationMs - cursor).toFloat()))
        }
    }
}
