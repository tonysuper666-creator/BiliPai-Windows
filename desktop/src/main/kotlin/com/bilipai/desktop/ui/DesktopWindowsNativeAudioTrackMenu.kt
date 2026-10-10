package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.player.MpvPlayer
import java.util.concurrent.atomic.AtomicBoolean

/** A view of the existing MPV tracks, never another player or audio preference. */
@Composable
internal fun DesktopWindowsNativeAudioTrackMenu(
    nativePlayer: MpvPlayer,
    menuEnabled: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onMenuExpandedChanged: (Boolean) -> Unit = {},
) {
    val state by nativePlayer.state.collectAsState()
    val identity = state.nativeTrackIdentity
    val tracks = desktopWindowsNativeAudioTracks(state)
    val owner = LocalDesktopWindowsPlayerWindow.current
    val available = menuEnabled && identity != null && tracks.size > 1 &&
        state.ready && !state.loading && !state.ended && state.error == null && state.failure == null
    // Retire on source/recovery, selection, title/language or track-list changes.
    // Ordinary playback-position emissions never reopen an obsolete popup.
    val expansion = remember(nativePlayer, identity, tracks, menuEnabled, available, owner) { mutableStateOf(false) }
    val alive = remember(expansion) { AtomicBoolean(true) }
    val latestEnabled = rememberUpdatedState(menuEnabled)
    val latestExpansionChanged = rememberUpdatedState(onMenuExpandedChanged)
    DisposableEffect(expansion) {
        onDispose {
            alive.set(false)
            expansion.value = false
            latestExpansionChanged.value(false)
        }
    }
    SideEffect { latestExpansionChanged.value(expansion.value && available) }
    if (!available) return
    val expected = identity ?: return
    fun current(requireExpanded: Boolean): Boolean {
        if (!alive.get() || !latestEnabled.value || (requireExpanded && !expansion.value) || owner?.isShowing != true) return false
        val observed = nativePlayer.state.value
        return observed.ready && !observed.loading && !observed.ended &&
            observed.error == null && observed.failure == null && observed.nativeTrackIdentity == expected &&
            desktopWindowsNativeAudioTracks(observed) == tracks &&
            runCatching { nativePlayer.isNativeTrackIdentityCurrent(expected) }.getOrDefault(false)
    }
    Box(modifier) {
        // Only this leaf disables the Material layout minimum. Offline retains
        // its existing 36dp bar; the modeless menu keeps normal row targets.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            TextButton(
                onClick = { if (current(requireExpanded = false)) expansion.value = true },
                modifier = if (compact) Modifier.height(28.dp) else Modifier.fillMaxWidth().height(40.dp),
                contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 12.dp, vertical = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = if (compact) Color.White else MaterialTheme.colorScheme.primary),
            ) {
                val selected = tracks.firstOrNull { it.selected }
                Text(if (compact) "音轨" else "音轨 · ${selected?.let(::desktopWindowsNativeAudioTrackLabel) ?: "未选择"}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium)
            }
        }
        DesktopWindowsPlayerMenu(expanded = expansion.value,
            onDismissRequest = { expansion.value = false },
            preferredHeight = (48 * tracks.size + 8).coerceIn(104, 320).dp) {
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(desktopWindowsNativeAudioTrackLabel(track), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { RadioButton(selected = track.selected, onClick = null) },
                    onClick = {
                        val admittedByUi = current(requireExpanded = true)
                        expansion.value = false
                        if (admittedByUi && !track.selected) {
                            // These read predicates are extra UI constraints.
                            // MPV's existing final admission and OwnedTrackSelection
                            // actor reject a replaced source before changing aid.
                            runCatching { nativePlayer.selectAudioTrackForIdentity(expected, track.id) }
                        }
                    },
                )
            }
        }
    }
}
