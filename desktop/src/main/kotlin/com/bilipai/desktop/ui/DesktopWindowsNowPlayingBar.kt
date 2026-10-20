package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ICON_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_PANEL_DP
import com.bilipai.desktop.ui.kit.DesktopIconButton
import com.bilipai.desktop.ui.kit.DesktopTooltipBox
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition

/*
 * The now-playing strip of the desktop shell (spec 3.4): the audio bar of today, with the same title and the same five
 * actions, restyled as a 56 dp glass strip with icon buttons and tooltips. It reads no state of its own and calls no
 * player: the caller passes the title, the playing flag and one plain lambda per action (the Root chrome binds them to
 * the audio binding and keeps the ownership guard there). The conditions that decide whether it is shown stay with the
 * caller too. Each button is one clickable named by its label, and the tooltip wrapper around it adds no action.
 */
@Composable
internal fun DesktopWindowsNowPlayingBar(
    title: String,
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // The 8 dp around the strip keep it from touching the rail glass and the page above it.
    Box(modifier.fillMaxWidth().padding(DESKTOP_RAIL_MARGIN_DP.dp)) {
        DesktopWindowsGlassSurface(
            Modifier.fillMaxWidth().height(DESKTOP_RAIL_NOW_PLAYING_BAR_DP.dp),
            shape = RoundedCornerShape(DESKTOP_KIT_RADIUS_PANEL_DP.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title, Modifier.weight(1f).padding(end = 8.dp), color = colors.onSurface,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                DesktopNowPlayingButton(DesktopShellStrings.NOW_PLAYING_PREVIOUS, Icons.Filled.SkipPrevious, onPrevious)
                DesktopNowPlayingButton(
                    if (playing) DesktopShellStrings.NOW_PLAYING_PAUSE else DesktopShellStrings.NOW_PLAYING_PLAY,
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, onPlayPause, large = true,
                )
                DesktopNowPlayingButton(DesktopShellStrings.NOW_PLAYING_NEXT, Icons.Filled.SkipNext, onNext)
                DesktopNowPlayingButton(DesktopShellStrings.NOW_PLAYING_OPEN, Icons.Filled.OpenInFull, onOpen)
                DesktopNowPlayingButton(DesktopShellStrings.NOW_PLAYING_CLOSE, Icons.Filled.Close, onClose)
            }
        }
    }
}

/**
 * One icon button of the strip: the kit icon button (one clickable named by the label) inside the kit tooltip above it. The
 * kit button runs without a tooltip of its own, so the two never show twice.
 */
@Composable
private fun DesktopNowPlayingButton(label: String, icon: ImageVector, onClick: () -> Unit, large: Boolean = false) {
    DesktopTooltipBox(text = label, positioning = TooltipAnchorPosition.Above) {
        DesktopIconButton(onClick = onClick, label = label, large = large, tooltip = null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(DESKTOP_KIT_ICON_DP.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
