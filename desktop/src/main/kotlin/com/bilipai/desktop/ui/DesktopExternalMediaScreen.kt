package com.bilipai.desktop.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.js.ExternalMediaLaunchRequest
import com.android.purebilibili.core.plugin.js.ExternalMediaLaunchStore
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.copyPlaybackStreamHeaders
import kotlinx.coroutines.CoroutineScope
import java.net.URI
import java.util.Collections

/** The original request/line selection stays owned by the window while its content page is unmounted. */
class DesktopExternalPageMemory(parent: CoroutineScope, player: MpvPlayer?) : DesktopMediaPageMemory(parent, player) {
    var request by mutableStateOf<ExternalMediaLaunchRequest?>(null)
        private set
    var selectedIndex by mutableIntStateOf(0)
        private set
    private var authority: () -> Boolean = { false }
    private var releaseLaunch: () -> Unit = {}
    private var completedSource: Long? = null
    val authorizationCurrent get() = request == null || authority()

    fun open(launchId: String, authorizationCurrent: () -> Boolean, releaseRequest: (String) -> Unit) {
        check(authorizationCurrent()) { "插件播放授权已经变化，请重新打开内容" }
        val original = ExternalMediaLaunchStore.get(launchId) ?: error("播放请求已失效，请从插件内容重新打开")
        val detached = detachDesktopExternalRequest(original)
        stopPlayback()
        authority = authorizationCurrent
        releaseLaunch = { releaseRequest(launchId) }
        release = { releaseLaunch(); request = null; completedSource = null }
        request = detached
        selectedIndex = detached.selectedStreamIndex
        selectStream(selectedIndex)
    }

    fun selectStream(index: Int) {
        if (!authority()) { stopPlayback(); error = "插件播放授权已经变化，请重新打开内容"; return }
        val current = request ?: return
        val stream = current.streams.getOrNull(index) ?: return
        try {
            val initialized = player ?: error("原生播放器未能初始化")
            sourceVersion = initialized.loadVersioned(PlaybackSource(videoUrl = stream.url,
                referer = "", cookieHeader = "", title = current.title, streamHeaders = stream.headers))
            selectedIndex = index; loaded = true; error = null; completedSource = null
        } catch (failure: Exception) {
            error = failure.message ?: "外部媒体无法播放"
        }
    }

    internal fun releaseCompletedLaunch() {
        if (ownsNativeSource && completedSource != sourceVersion) {
            completedSource = sourceVersion
            releaseLaunch()
        }
    }
}

internal fun detachDesktopExternalRequest(request: ExternalMediaLaunchRequest): ExternalMediaLaunchRequest {
    require(request.streams.isNotEmpty() && request.streams.size <= 128) { "外部媒体线路无效" }
    val streams = request.streams.map { stream ->
        val uri = runCatching { URI(stream.url) }.getOrNull()
        require(uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()) { "外部媒体需要 HTTP 或 HTTPS 地址" }
        stream.copy(headers = copyPlaybackStreamHeaders(stream.headers))
    }
    return request.copy(streams = Collections.unmodifiableList(streams),
        selectedStreamIndex = request.selectedStreamIndex.coerceIn(streams.indices))
}

@Composable
fun DesktopExternalMediaScreen(memory: DesktopExternalPageMemory, player: MpvPlayer?,
    playerContent: @Composable (MpvPlayer) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val request = memory.request
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text("‹ 返回插件内容") }
            Text(request?.title ?: "外部媒体", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = memory::stopPlayback) { Text("关闭播放") }
        }
        memory.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (request == null || player == null) {
            Text("播放请求已失效，请从插件内容重新打开")
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) { playerContent(player) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                request.streams.forEachIndexed { index, stream ->
                    FilterChip(memory.selectedIndex == index, { memory.selectStream(index) },
                        label = { Text(stream.title.ifBlank { "线路 ${index + 1}" }) })
                }
            }
        }
    }
}
