package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
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
import com.android.purebilibili.core.util.FormatUtils
import com.bilipai.desktop.player.PlayerState

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
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onMute: () -> Unit, onVolume: (Double) -> Unit, onSpeed: (Double) -> Unit,
    onQuality: (Int) -> Unit, onSeek: (Double) -> Unit, onPictureInPicture: () -> Unit,
    onFullscreen: () -> Unit, onDetails: () -> Unit, onOpenIntroduction: () -> Unit,
    enhancement: @Composable () -> Unit,
) {
    DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            DesktopWindowsThinSeek(state.positionSeconds, state.durationSeconds, sourceVersion,
                enabled && state.durationSeconds > 0.0, onSeek)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val expanded = maxWidth >= 940.dp
                val showEnhancementStatus = maxWidth >= 520.dp
                var more by remember { mutableStateOf(false) }
                var speedMenu by remember { mutableStateOf(false) }
                var qualityMenu by remember { mutableStateOf(false) }
                var volumeMenu by remember { mutableStateOf(false) }
                LaunchedEffect(enabled) {
                    if (!enabled) { more = false; speedMenu = false; qualityMenu = false; volumeMenu = false }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlayPause, enabled = enabled && state.ready, modifier = Modifier.size(44.dp)) {
                        Icon(if (state.paused || state.ended) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (state.paused || state.ended) "播放" else "暂停")
                    }
                    if (expanded) {
                        IconButton(onClick = onPrevious, enabled = enabled && hasPrevious, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "上一集")
                        }
                        IconButton(onClick = onNext, enabled = enabled && hasNext, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "下一集")
                        }
                    }
                    Text("${FormatUtils.formatDuration((state.positionSeconds.coerceAtLeast(0.0) * 1000).toLong())} / " +
                        FormatUtils.formatDuration((state.durationSeconds.coerceAtLeast(0.0) * 1000).toLong()),
                        Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (expanded) {
                        Box {
                            IconButton(onClick = { volumeMenu = true }, enabled = enabled, modifier = Modifier.size(44.dp)) {
                                Icon(if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = "音量与静音")
                            }
                            DropdownMenu(volumeMenu, onDismissRequest = { volumeMenu = false }) {
                                DropdownMenuItem(text = { Text(if (state.muted) "取消静音" else "静音") }, onClick = onMute)
                                Text("音量 ${state.volume.toInt()}%", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                                Slider(state.volume.toFloat().coerceIn(0f, 100f), { onVolume(it.toDouble()) },
                                    Modifier.width(180.dp).padding(horizontal = 12.dp), enabled = enabled && state.ready, valueRange = 0f..100f)
                            }
                        }
                        Box {
                            TextButton(onClick = { speedMenu = true }, enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = "倍速" }) { Text("${state.speed}×") }
                            DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }) {
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
                            DropdownMenu(qualityMenu, onDismissRequest = { qualityMenu = false }) {
                                qualities.forEach { (id, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { onQuality(id); qualityMenu = false })
                                }
                            }
                        }
                    }
                    DesktopVideoEnhancementCompactSlot(showStatus = showEnhancementStatus) { enhancement() }
                    IconButton(onClick = onDetails, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "详情",
                            tint = if (detailsOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                    if (expanded) IconButton(onClick = onPictureInPicture,
                        enabled = enabled && canPictureInPicture, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "浮窗")
                    }
                    IconButton(onClick = onFullscreen, modifier = Modifier.size(44.dp)) {
                        Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (fullscreen) "退出全屏" else "全屏")
                    }
                    Box {
                        IconButton(onClick = { more = true }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多播放操作")
                        }
                        DropdownMenu(more, onDismissRequest = { more = false }) {
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
                            DropdownMenuItem(text = { Text("简介、分P与播放设置") }, onClick = { onOpenIntroduction(); more = false })
                        }
                    }
                }
            }
        }
    }
}

/** A 3dp rail inside a full 44dp input region. Drag previews locally and commits once on release. */
@Composable
private fun DesktopWindowsThinSeek(position: Double, duration: Double, sourceVersion: Long,
    enabled: Boolean, onSeek: (Double) -> Unit) {
    val total = duration.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    var scrub by remember(sourceVersion) { mutableStateOf<Double?>(null) }
    val latestSeek by rememberUpdatedState(onSeek)
    val value = (scrub ?: position.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, total)
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
    val active = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(44.dp).semantics {
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
    }.focusable(enabled).pointerInput(enabled, total, sourceVersion) {
        if (enabled) awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            fun seconds(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * total
            scrub = seconds(down.position.x)
            down.consume()
            try {
                val released = drag(down.id) { change -> scrub = seconds(change.position.x); change.consume() }
                if (released) scrub?.let(latestSeek)
            } finally { scrub = null }
        }
    }) {
        val y = size.height / 2f
        val end = (size.width * (value / total)).toFloat()
        drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
        drawLine(active, Offset(0f, y), Offset(end, y), strokeWidth = 3.dp.toPx())
        drawCircle(active, radius = (if (scrub == null) 3.dp else 5.dp).toPx(), center = Offset(end, y))
    }
}
