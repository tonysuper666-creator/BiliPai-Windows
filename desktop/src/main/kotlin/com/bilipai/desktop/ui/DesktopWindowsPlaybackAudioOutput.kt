package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.player.DesktopWindowsAudioDevice
import com.bilipai.desktop.player.DesktopWindowsAudioOutputStatus
import com.bilipai.desktop.player.PlayerState

/** This is only a view of the existing actor's readback. A previous file or
 * recovery attempt must never supply the current video's output badge. */
internal fun desktopWindowsPlaybackAudioOutputStatus(
    state: PlayerState,
    status: DesktopWindowsAudioOutputStatus,
): DesktopWindowsAudioOutputStatus? {
    val identity = state.nativeTrackIdentity ?: return null
    if (!state.ready || state.loading || state.ended || state.error != null || state.failure != null ||
        status.sourceVersion != identity.sourceVersion || status.playbackRevision != identity.playbackRevision) return null
    return status
}

internal fun desktopWindowsPlaybackAudioDeviceLabel(id: String?, devices: List<DesktopWindowsAudioDevice>): String = when (id) {
    null -> "尚未读回"
    "auto" -> "跟随 Windows 默认输出"
    "wasapi" -> "Windows 默认 WASAPI"
    else -> devices.singleOrNull { it.name == id }?.description?.takeIf { it.isNotBlank() }
        ?: "指定的 WASAPI 设备"
}

/** Compact, read-only presentation in the existing audio popup. Its caller
 * already owns the actual native track identity and modeless menu lifetime. */
@Composable
internal fun DesktopWindowsPlaybackAudioOutputObservation(
    status: DesktopWindowsAudioOutputStatus?,
    devices: List<DesktopWindowsAudioDevice>,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("当前音频输出", style = MaterialTheme.typography.titleSmall)
        Text(status?.let(::desktopWindowsAudioOutputPhaseLabel) ?: "等待本次播放的音频输出读回。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp))
        if (status != null) {
            Text("后端：${status.activeDriver ?: "尚未读回"}", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp))
            Text("设备配置：${desktopWindowsPlaybackAudioDeviceLabel(status.configuredDeviceId, devices)}",
                style = MaterialTheme.typography.bodySmall)
            Text("输出：${desktopWindowsAudioPcmLabel(status.outputPcm)}", style = MaterialTheme.typography.bodySmall)
            Text("源音频：${desktopWindowsAudioPcmLabel(status.sourcePcm)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
