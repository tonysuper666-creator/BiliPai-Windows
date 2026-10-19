package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.feature.video.ui.overlay.normalizeViewPointSegments
import com.android.purebilibili.feature.video.ui.overlay.findViewPointSegmentAt
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.feature.video.progress.PbpRidgeDensity
import com.android.purebilibili.feature.video.progress.PbpRidgeSample
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_CONTROL_HEIGHT_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ICON_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_LIST_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_TOOLTIP_DELAY_PLAYER_MS
import com.bilipai.desktop.ui.kit.DesktopIconButton
import com.bilipai.desktop.ui.kit.DesktopKitDivider
import com.bilipai.desktop.ui.kit.DesktopMenuRow
import com.bilipai.desktop.ui.kit.DesktopSliderRow
import com.bilipai.desktop.ui.kit.DesktopTooltipBox
import com.bilipai.desktop.ui.kit.desktopKitDisabledAlpha
import com.bilipai.desktop.ui.kit.desktopKitInteraction
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition

/*
 * The LEGACY control bar, restyled with the component kit (slice V2b, spec 4.13 and 5.1): 32 dp icon buttons with a 20 dp glyph
 * and a tooltip that names the key, text pills for speed and quality, kit menu rows and a kit slider row in the menu windows.
 * The function signature, the accessible names, the breakpoints and every operation are the ones of the bar before the restyle;
 * the texts live in DesktopWindowsVideoControlBarStrings and the pure rules (breakpoints, hints, menu heights) in
 * DesktopWindowsVideoControlBarModel. The NVIDIA trigger is the existing compact slot, untouched. The rows that
 * sponsorMoreActions adds come from another file and keep their own look.
 */

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
    onInteractionHoldChanged: (DesktopWindowsFullscreenChromeInteraction) -> Unit,
    onChromePointerInput: () -> Unit,
    speedOptions: List<Float>,
    qualities: List<Pair<Int, String>>, selectedQuality: Int?,
    canOpenCollection: Boolean, canOpenPlaybackQueue: Boolean,
    onOpenCollection: () -> Unit, onOpenPlaybackQueue: () -> Unit,
    canOpenInteraction: Boolean, onSendDanmaku: () -> Unit, onShareVideo: () -> Unit,
    canDownloadAudio: Boolean, canSaveCover: Boolean, onDownloadAudio: () -> Unit, onSaveCover: () -> Unit,
    canScreenshot: Boolean, screenshotBusy: Boolean, onScreenshot: () -> Unit,
    chapters: DesktopOriginalVideoChapterResult?, chaptersSource: DesktopOriginalVideoAcceptedPublication?,
    onChapterSeek: (DesktopOriginalVideoChapterResult, DesktopOriginalVideoAcceptedPublication, Long) -> Unit,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onMute: () -> Unit, onVolume: (Double) -> Unit, onSpeed: (Double) -> Unit,
    onQuality: (Int) -> Unit, onSeek: (Double) -> Unit, onPictureInPicture: () -> Unit,
    onFullscreen: () -> Unit, onDetails: () -> Unit, onOpenIntroduction: () -> Unit,
    sponsorSkip: @Composable () -> Unit,
    enhancement: @Composable () -> Unit,
    sponsorMoreActions: @Composable (() -> Unit) -> Unit = {},
    pbpRidgeSamples: List<PbpRidgeSample> = emptyList(),
    seekPreviewSource: DesktopOriginalVideoAcceptedPublication? = null,
    seekPreviewEnabled: Boolean = false,
    onSeekPreviewChanged: (DesktopOriginalVideoAcceptedPublication, DesktopWindowsVideoSeekPreview?) -> Unit = { _, _ -> },
) {
    val durationMs = state.durationSeconds.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L
    val menuSpeeds = remember(speedOptions, state.speed) {
        resolveDesktopWindowsVideoSpeedOptions(speedOptions, state.speed.toFloat())
    }
    val segments = remember(chapters, durationMs) { normalizeViewPointSegments(chapters?.points.orEmpty(), durationMs) }
    val hints = remember { DesktopControlBarHintTarget.entries.associateWith { desktopControlBarShortcutHint(it) } }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var scrubbing by remember(seekPreviewSource, sourceVersion, chapters, chaptersSource) { mutableStateOf(false) }
    val latestHold by rememberUpdatedState(onInteractionHoldChanged)
    DisposableEffect(Unit) { onDispose { latestHold(DesktopWindowsFullscreenChromeInteraction()) } }
    DesktopWindowsPlayerSurface(Modifier.fillMaxWidth().desktopWindowsChromePointerInput(onChromePointerInput)
        .onFocusChanged { focused = it.hasFocus }
        .focusGroup().hoverable(interactions)) {
        Column(Modifier.padding(horizontal = DESKTOP_CONTROL_BAR_SIDE_PADDING_DP.dp)) {
            DesktopWindowsThinSeek(state.positionSeconds, state.durationSeconds, sourceVersion,
                enabled && state.durationSeconds > 0.0, chapters, chaptersSource, onChapterSeek, { scrubbing = it }, onSeek,
                pbpRidgeSamples, seekPreviewSource, seekPreviewEnabled, onSeekPreviewChanged)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val expanded = desktopControlBarExpanded(maxWidth.value)
                val showEnhancementStatus = desktopControlBarShowsStatus(maxWidth.value)
                var more by remember { mutableStateOf(false) }
                var speedMenu by remember { mutableStateOf(false) }
                var qualityMenu by remember { mutableStateOf(false) }
                var volumeMenu by remember { mutableStateOf(false) }
                var chapterMenu by remember(chapters, chaptersSource, sourceVersion) { mutableStateOf(false) }
                var enhancementMenu by remember(sourceVersion) { mutableStateOf(false) }
                SideEffect { latestHold(DesktopWindowsFullscreenChromeInteraction(hovered, focused,
                    scrubbing || more || speedMenu || qualityMenu || volumeMenu || chapterMenu || enhancementMenu)) }
                LaunchedEffect(enabled) {
                    if (!enabled) { more = false; speedMenu = false; qualityMenu = false; volumeMenu = false; chapterMenu = false; enhancementMenu = false }
                }
                val showPlay = state.paused || state.ended
                val chapterRowInMore = !showEnhancementStatus && chapters != null && chaptersSource != null && segments.isNotEmpty()
                Row(Modifier.fillMaxWidth().heightIn(min = DESKTOP_CONTROL_BAR_ROW_MIN_HEIGHT_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(DESKTOP_CONTROL_BAR_BUTTON_GAP_DP.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DesktopControlBarButton(label = if (showPlay) DesktopWindowsVideoControlBarStrings.PLAY else DesktopWindowsVideoControlBarStrings.PAUSE,
                        onClick = onPlayPause, enabled = enabled && state.ready, hint = hints[DesktopControlBarHintTarget.PLAY_PAUSE]) {
                        DesktopControlBarGlyph(if (showPlay) Icons.Default.PlayArrow else Icons.Default.Pause)
                    }
                    if (expanded) {
                        DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.PREVIOUS, onClick = onPrevious,
                            enabled = enabled && hasPrevious, hint = hints[DesktopControlBarHintTarget.PREVIOUS]) {
                            DesktopControlBarGlyph(Icons.Default.SkipPrevious)
                        }
                        DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.NEXT, onClick = onNext,
                            enabled = enabled && hasNext, hint = hints[DesktopControlBarHintTarget.NEXT]) {
                            DesktopControlBarGlyph(Icons.Default.SkipNext)
                        }
                    }
                    Text("${FormatUtils.formatDuration((state.positionSeconds.coerceAtLeast(0.0) * 1000).toLong())} / " +
                        FormatUtils.formatDuration((state.durationSeconds.coerceAtLeast(0.0) * 1000).toLong()),
                        Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (expanded) {
                        Box {
                            DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.VOLUME_AND_MUTE, onClick = { volumeMenu = true },
                                enabled = enabled, hint = hints[DesktopControlBarHintTarget.MUTE]) {
                                DesktopControlBarGlyph(if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp)
                            }
                            DesktopWindowsPlayerMenu(volumeMenu, onDismissRequest = { volumeMenu = false },
                                preferredHeight = desktopControlBarMenuHeightDp(rows = 1, sliderRows = 1).dp) {
                                DesktopControlBarMenuRow(if (state.muted) DesktopWindowsVideoControlBarStrings.UNMUTE else DesktopWindowsVideoControlBarStrings.MUTE,
                                    onClick = onMute)
                                DesktopControlBarVolumeRow(state, enabled, onVolume)
                            }
                        }
                        Box {
                            DesktopControlBarPill(text = DesktopWindowsVideoControlBarStrings.speedValue(playbackSpeedLabel(state.speed)),
                                label = DesktopWindowsVideoControlBarStrings.SPEED, onClick = { speedMenu = true }, enabled = enabled)
                            DesktopWindowsPlayerMenu(speedMenu, onDismissRequest = { speedMenu = false },
                                preferredHeight = desktopControlBarMenuHeightDp(rows = menuSpeeds.size).dp) {
                                menuSpeeds.forEach { speed ->
                                    DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.speedValue(speed.toString()),
                                        onClick = { onSpeed(speed.toDouble()); speedMenu = false },
                                        marksCurrent = true, current = speed == state.speed.toFloat())
                                }
                            }
                        }
                        Box {
                            DesktopControlBarPill(text = desktopControlBarQualityLabel(qualities, selectedQuality),
                                label = DesktopWindowsVideoControlBarStrings.QUALITY, onClick = { qualityMenu = true },
                                enabled = enabled && qualities.isNotEmpty())
                            DesktopWindowsPlayerMenu(qualityMenu, onDismissRequest = { qualityMenu = false },
                                preferredHeight = desktopControlBarMenuHeightDp(rows = qualities.size).dp) {
                                qualities.forEach { (id, label) ->
                                    DesktopControlBarMenuRow(label, onClick = { onQuality(id); qualityMenu = false },
                                        marksCurrent = true, current = id == selectedQuality)
                                }
                            }
                        }
                    }
                    if (showEnhancementStatus && chapters != null && chaptersSource != null && segments.isNotEmpty()) Box {
                        DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.CHAPTERS, onClick = { chapterMenu = true },
                            enabled = enabled) {
                            DesktopControlBarGlyph(Icons.Default.ListAlt)
                        }
                        DesktopWindowsPlayerMenu(chapterMenu, onDismissRequest = { chapterMenu = false },
                            preferredHeight = desktopControlBarMenuHeightDp(rows = segments.size).dp) {
                            segments.forEach { segment ->
                                DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.chapterRow(
                                    FormatUtils.formatDuration(segment.fromMs), segment.content), enabled = enabled,
                                    onClick = { onChapterSeek(chapters, chaptersSource, segment.fromMs); chapterMenu = false })
                            }
                        }
                    }
                    sponsorSkip()
                    DesktopVideoEnhancementCompactSlot(showStatus = showEnhancementStatus, menuEnabled = enabled,
                        onMenuExpandedChanged = { enhancementMenu = it }) { enhancement() }
                    DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.DETAILS, onClick = onDetails,
                        active = detailsOpen, hint = hints[DesktopControlBarHintTarget.DETAILS]) {
                        DesktopControlBarGlyph(Icons.Default.Info,
                            tint = if (detailsOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                    if (expanded) DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.MINI_PLAYER, onClick = onPictureInPicture,
                        enabled = enabled && canPictureInPicture, hint = hints[DesktopControlBarHintTarget.MINI_PLAYER]) {
                        DesktopControlBarGlyph(Icons.Default.PictureInPictureAlt)
                    }
                    DesktopControlBarButton(label = if (fullscreen) DesktopWindowsVideoControlBarStrings.EXIT_FULLSCREEN else DesktopWindowsVideoControlBarStrings.FULLSCREEN,
                        onClick = onFullscreen, hint = hints[DesktopControlBarHintTarget.FULLSCREEN]) {
                        DesktopControlBarGlyph(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen)
                    }
                    Box {
                        DesktopControlBarButton(label = DesktopWindowsVideoControlBarStrings.MORE_ACTIONS, onClick = { more = true }) {
                            DesktopControlBarGlyph(Icons.Default.MoreVert)
                        }
                        DesktopWindowsPlayerMenu(more, onDismissRequest = { more = false },
                            preferredHeight = desktopControlBarMoreMenuHeightDp(expanded, menuSpeeds.size, qualities.size,
                                chapterRow = chapterRowInMore, collectionRow = canOpenCollection,
                                queueRow = canOpenPlaybackQueue).dp) {
                            if (!expanded) {
                                DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.PREVIOUS, enabled = enabled && hasPrevious,
                                    onClick = { onPrevious(); more = false })
                                DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.NEXT, enabled = enabled && hasNext,
                                    onClick = { onNext(); more = false })
                                DesktopControlBarMenuRow(if (state.muted) DesktopWindowsVideoControlBarStrings.UNMUTE else DesktopWindowsVideoControlBarStrings.MUTE,
                                    enabled = enabled, onClick = onMute)
                                DesktopControlBarVolumeRow(state, enabled, onVolume)
                                DesktopControlBarMenuDivider()
                                menuSpeeds.forEach { speed ->
                                    DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.speedRow(speed.toString()), enabled = enabled,
                                        onClick = { onSpeed(speed.toDouble()); more = false })
                                }
                                DesktopControlBarMenuDivider()
                                qualities.forEach { (id, label) -> DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.qualityRow(label),
                                    enabled = enabled, onClick = { onQuality(id); more = false }) }
                                DesktopControlBarMenuDivider()
                                DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.MINI_PLAYER, enabled = enabled && canPictureInPicture,
                                    onClick = { onPictureInPicture(); more = false })
                            }
                            if (!showEnhancementStatus && chapters != null && chaptersSource != null && segments.isNotEmpty())
                                DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.CHAPTERS, enabled = enabled,
                                    onClick = { more = false; chapterMenu = true })
                            if (canOpenCollection) DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.COLLECTION, enabled = enabled,
                                onClick = { more = false; onOpenCollection() })
                            if (canOpenPlaybackQueue) DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.PLAYBACK_QUEUE, enabled = enabled,
                                onClick = { more = false; onOpenPlaybackQueue() })
                            DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.SEND_DANMAKU, enabled = enabled && canOpenInteraction,
                                onClick = { more = false; onSendDanmaku() })
                            DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.SHARE_VIDEO, enabled = enabled && canOpenInteraction,
                                onClick = { more = false; onShareVideo() })
                            DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.DOWNLOAD_AUDIO, enabled = enabled && canDownloadAudio,
                                onClick = { more = false; onDownloadAudio() })
                            DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.SAVE_COVER, enabled = enabled && canSaveCover,
                                onClick = { more = false; onSaveCover() })
                            DesktopControlBarMenuRow(if (screenshotBusy) DesktopWindowsVideoControlBarStrings.SCREENSHOT_BUSY else DesktopWindowsVideoControlBarStrings.SCREENSHOT,
                                enabled = enabled && canScreenshot && !screenshotBusy,
                                onClick = { more = false; onScreenshot() })
                            sponsorMoreActions { more = false }
                            DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.INTRODUCTION_AND_SETTINGS,
                                onClick = { onOpenIntroduction(); more = false })
                        }
                        if (!showEnhancementStatus && chapters != null && chaptersSource != null)
                            DesktopWindowsPlayerMenu(chapterMenu, onDismissRequest = { chapterMenu = false },
                                preferredHeight = desktopControlBarMenuHeightDp(rows = segments.size).dp) {
                                segments.forEach { segment ->
                                    DesktopControlBarMenuRow(DesktopWindowsVideoControlBarStrings.chapterRow(
                                        FormatUtils.formatDuration(segment.fromMs), segment.content),
                                        enabled = enabled, onClick = { onChapterSeek(chapters, chaptersSource, segment.fromMs); chapterMenu = false })
                                }
                            }
                    }
                }
            }
        }
    }
}

/**
 * One icon button of the bar: the kit icon button (32 dp hit, 20 dp glyph, one clickable named by the label) inside the kit tooltip
 * with the player settings, above the button and after the player delay. The tooltip reads the label, then the key the shortcut table
 * binds to the button (null when none). The kit button itself runs without a tooltip, so the two never show twice.
 */
@Composable
private fun DesktopControlBarButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean? = null,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    DesktopTooltipBox(text = label, shortcut = hint, enabled = enabled, delayMillis = DESKTOP_KIT_TOOLTIP_DELAY_PLAYER_MS,
        positioning = TooltipAnchorPosition.Above) {
        DesktopIconButton(onClick = onClick, label = label, enabled = enabled, active = active, tooltip = null, content = content)
    }
}

/** The glyph of an icon button: a vector at the kit icon size. The button names itself, so the glyph is decorative. */
@Composable
private fun DesktopControlBarGlyph(icon: ImageVector, tint: Color = LocalContentColor.current) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(DESKTOP_KIT_ICON_DP.dp), tint = tint)
}

/**
 * A text pill (speed, quality): a 32 dp rounded control that shows the current value, with the kit hover wash, hand cursor and
 * keyboard focus ring. One clickable; the accessible name is the label, the shown text is the value, as the text button it replaces.
 * The pill has no tooltip, as the text button had none: it already shows its value, and the Windows fixtures that step through the
 * bar by keyboard know a tooltip layer for the icon buttons only.
 */
@Composable
private fun DesktopControlBarPill(text: String, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .height(DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)
            .defaultMinSize(minWidth = DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)
            .alpha(desktopKitDisabledAlpha(enabled))
            .desktopKitInteraction(source, (DESKTOP_KIT_CONTROL_HEIGHT_DP / 2).dp, enabled)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = DESKTOP_CONTROL_BAR_PILL_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge,
            maxLines = 1, softWrap = false)
    }
}

/**
 * The volume slider row of a menu window, exactly the 40 dp kit list row height that the menu heights count. Under the default
 * Material 3 style the slider would make the row taller in two ways: it enforces a 48 dp minimum interactive size, and its own
 * handle is 44 dp tall. The enforcement is switched off for this row, as the other player menus do, and the row is given its
 * height outright, so the handle is laid out inside it and the menu is not left a few dp short.
 */
@Composable
private fun DesktopControlBarVolumeRow(state: PlayerState, enabled: Boolean, onVolume: (Double) -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        DesktopSliderRow(title = DesktopWindowsVideoControlBarStrings.VOLUME,
            value = state.volume.toFloat().coerceIn(0f, 100f), onValueChange = { onVolume(it.toDouble()) },
            modifier = Modifier.padding(start = DESKTOP_CONTROL_BAR_MENU_START_INSET_DP.dp).height(DESKTOP_KIT_ROW_LIST_DP.dp),
            enabled = enabled && state.ready, valueRange = 0f..100f,
            valueLabel = DesktopWindowsVideoControlBarStrings.volumePercent(state.volume.toInt()))
    }
}

/**
 * A row of a menu window: the kit menu row, inset to match the 12 dp end padding of the menu host. marksCurrent reserves the 20 dp
 * glyph slot, so a list that marks its current row (speed, quality) keeps every label on one line; current draws the check.
 */
@Composable
private fun DesktopControlBarMenuRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    marksCurrent: Boolean = false,
    current: Boolean = false,
) {
    val mark: (@Composable () -> Unit)? =
        if (marksCurrent) ({ if (current) DesktopControlBarGlyph(Icons.Default.Check, MaterialTheme.colorScheme.primary) }) else null
    DesktopMenuRow(label = label, onClick = onClick,
        modifier = Modifier.padding(start = DESKTOP_CONTROL_BAR_MENU_START_INSET_DP.dp), enabled = enabled, icon = mark)
}

/** A divider between two groups of a menu window: the one-pixel kit line with 4 dp above and below. */
@Composable
private fun DesktopControlBarMenuDivider() {
    DesktopKitDivider(Modifier.padding(start = DESKTOP_CONTROL_BAR_MENU_START_INSET_DP.dp, top = 4.dp, bottom = 4.dp))
}

/** A 24dp input region expands to 44dp for chapter labels. The thin rail retains
 * precise seek; labels jump to the original normalized start on click.
 * Drag and keyboard always seek precisely. */
@Composable
private fun DesktopWindowsThinSeek(position: Double, duration: Double, sourceVersion: Long,
    enabled: Boolean, chapters: DesktopOriginalVideoChapterResult?, chaptersSource: DesktopOriginalVideoAcceptedPublication?,
    onChapterSeek: (DesktopOriginalVideoChapterResult, DesktopOriginalVideoAcceptedPublication, Long) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit, onSeek: (Double) -> Unit, pbpRidgeSamples: List<PbpRidgeSample>,
    previewSource: DesktopOriginalVideoAcceptedPublication?, previewEnabled: Boolean,
    onPreviewChanged: (DesktopOriginalVideoAcceptedPublication, DesktopWindowsVideoSeekPreview?) -> Unit) {
    val total = duration.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val durationMs = if (duration.isFinite() && duration > 0.0) (duration * 1000.0).toLong() else 0L
    val segments = remember(chapters, durationMs) { normalizeViewPointSegments(chapters?.points.orEmpty(), durationMs) }
    var scrub by remember(previewSource, sourceVersion, chapters, chaptersSource) { mutableStateOf<Double?>(null) }
    var hoverPreview by remember(previewSource, sourceVersion, chapters, chaptersSource) { mutableStateOf<Double?>(null) }
    var keyboardPreview by remember(previewSource, sourceVersion, chapters, chaptersSource) { mutableStateOf<Double?>(null) }
    val latestPreview by rememberUpdatedState(onPreviewChanged)
    val previewPosition = if (enabled && previewEnabled) scrub ?: keyboardPreview ?: hoverPreview else null
    val projectedPreview = remember(previewSource, previewPosition, total) {
        if (previewSource != null && previewPosition != null) DesktopWindowsVideoSeekPreview(
            previewSource, (previewPosition.coerceIn(0.0, total) * 1000.0).toLong(),
            (previewPosition / total).toFloat().coerceIn(0f, 1f)) else null
    }
    DisposableEffect(previewSource) {
        onDispose { previewSource?.let { latestPreview(it, null) } }
    }
    SideEffect { previewSource?.let { latestPreview(it, projectedPreview) } }
    LaunchedEffect(enabled, previewEnabled) {
        if (!enabled || !previewEnabled) { hoverPreview = null; keyboardPreview = null }
    }
    val latestSeek by rememberUpdatedState(onSeek)
    val latestScrubbing by rememberUpdatedState(onScrubbingChanged)
    val latestChapterSeek by rememberUpdatedState(onChapterSeek)
    val value = (scrub ?: position.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, total)
    val currentSegment = findViewPointSegmentAt(segments, (value * 1000.0).toLong())
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
    val active = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(if (segments.isEmpty()) 24.dp else 44.dp)) {
        Canvas(Modifier.fillMaxSize().drawWithCache {
            val trackHeightPx = 3.dp.toPx()
            val centerY = if (segments.isEmpty()) size.height / 2f else 14.dp.toPx()
            // The existing 24dp rail band remains above the chapter labels in the 44dp layout.
            val ridgeHeight = (24.dp.toPx() * 0.42f).coerceAtMost(18.dp.toPx())
            val ridgePoints = pbpRidgeSamples.map { sample ->
                val intensity = when (sample.density) {
                    PbpRidgeDensity.QUIET -> sample.intensity * 0.78f
                    PbpRidgeDensity.NORMAL -> sample.intensity
                    PbpRidgeDensity.HOT -> sample.intensity * 1.14f
                }.coerceIn(0f, 1f)
                Offset(size.width * sample.fraction.coerceIn(0f, 1f), centerY - ridgeHeight * intensity)
            }
            val ridgePath = Path().apply {
                moveTo(0f, centerY)
                ridgePoints.forEach { lineTo(it.x, it.y) }
                lineTo(size.width, centerY)
                close()
            }
            val ridgeLinePath = Path().apply {
                ridgePoints.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            val hotSegments = (0 until (ridgePoints.size - 1).coerceAtLeast(0)).filter { index ->
                pbpRidgeSamples[index].density == PbpRidgeDensity.HOT ||
                    pbpRidgeSamples[index + 1].density == PbpRidgeDensity.HOT
            }
            val ridgeBrush = Brush.verticalGradient(
                listOf(active.copy(alpha = 0.18f), active.copy(alpha = 0.03f)),
                startY = centerY - ridgeHeight, endY = centerY
            )
            val ridgeGlow = Stroke(trackHeightPx * 2.8f, cap = StrokeCap.Round)
            val ridgeStroke = Stroke(trackHeightPx * 0.9f, cap = StrokeCap.Round)
            onDrawBehind {
                if (ridgePoints.size >= 2 && size.width > 0f) {
                    drawPath(ridgePath, ridgeBrush)
                    drawPath(ridgeLinePath, active.copy(alpha = 0.14f), style = ridgeGlow)
                    drawPath(ridgeLinePath, active.copy(alpha = 0.46f), style = ridgeStroke)
                    hotSegments.forEach { index ->
                        drawLine(active.copy(alpha = 0.68f), ridgePoints[index],
                            ridgePoints[index + 1], trackHeightPx * 1.25f, StrokeCap.Round)
                    }
                }
            }
        }.semantics {
            contentDescription = DesktopWindowsVideoControlBarStrings.SEEK_BAR
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
                if (target == null) false else {
                    val selected = target.coerceIn(0.0, total)
                    hoverPreview = null
                    keyboardPreview = selected.takeIf { previewEnabled }
                    latestSeek(selected); true
                }
            }
        }.onFocusChanged { if (!it.isFocused) keyboardPreview = null }
            .focusable(enabled)
            .pointerInput(enabled, previewEnabled, total, previewSource, sourceVersion) {
                if (enabled && previewEnabled) awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Exit -> hoverPreview = null
                            PointerEventType.Enter, PointerEventType.Move -> if (!event.buttons.isPrimaryPressed) {
                                event.changes.firstOrNull()?.position?.x?.let { x ->
                                    keyboardPreview = null
                                    hoverPreview = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * total
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
            .pointerInput(enabled, total, previewSource, sourceVersion, chapters, chaptersSource) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                fun seconds(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * total
                val chapterClick = segments.isNotEmpty() && down.position.y >= size.height / 2f
                var dragged = false
                hoverPreview = null; keyboardPreview = null
                scrub = seconds(down.position.x)
                down.consume()
                latestScrubbing(true)
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
                } finally { scrub = null; latestScrubbing(false) }
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
