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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.bilipai.desktop.plugins.js.DesktopJsPluginHost
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.danmaku.DanmakuDocument
import com.bilipai.desktop.danmaku.DanmakuComment
import com.android.purebilibili.feature.plugin.js.mapJsDanmuCommentsToItems
import com.android.purebilibili.core.plugin.js.InstalledBiliPaiJsPlugin
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
    private var jsHost: DesktopJsPluginHost? = null
    private var danmaku: DanmakuOverlay? = null
    private var danmakuPlugin: InstalledBiliPaiJsPlugin? = null
    private var executionRevision: Long = -1L
    private var danmakuJob: Job? = null
    private var danmakuSource: Long? = null

    private fun clearDanmaku() {
        danmakuJob?.cancel(); danmakuJob = null
        danmakuSource?.let { danmaku?.clearOwnedDocument(it) }
        danmakuSource = null
    }

    private fun loadDanmaku(current: ExternalMediaLaunchRequest, version: Long) {
        val plugin = danmakuPlugin ?: return
        val host = jsHost ?: return
        val overlay = danmaku ?: return
        if (current.danmakuPluginId != plugin.manifest.id) return
        fun owned() = authority() && ownsNativeSource && sourceVersion == version && request === current &&
            host.executionRevision.value == executionRevision
        danmakuSource = version
        danmakuJob = scope.launch {
            try {
                val comments = host.loadDanmuComments(plugin, current.title, executionRevision).getOrThrow()
                ensureActive(); if (!owned()) return@launch
                val items = mapJsDanmuCommentsToItems(comments)
                val document = DanmakuDocument(items.mapIndexed { index, item ->
                    DanmakuComment(index + 1, item.showAtTime / 1000.0,
                        when (item.layerType) {
                            com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_TOP -> 5
                            com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_BOTTOM -> 4
                            else -> 1
                        }, 25, (item.textColor ?: 0xffffff) and 0xffffff, item.text.orEmpty(), originalLocalItem = item)
                })
                overlay.setOwnedDocument(document, version, ::owned)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (owned()) error = failure.message ?: "JS 插件弹幕加载失败" }
        }
    }
    val authorizationCurrent get() = request == null || authority()

    fun open(launchId: String, authorizationCurrent: () -> Boolean, releaseRequest: (String) -> Unit,
        host: DesktopJsPluginHost? = null, overlay: DanmakuOverlay? = null,
        plugin: InstalledBiliPaiJsPlugin? = null, expectedRevision: Long = -1L) {
        check(authorizationCurrent()) { "插件播放授权已经变化，请重新打开内容" }
        val original = ExternalMediaLaunchStore.get(launchId) ?: error("播放请求已失效，请从插件内容重新打开")
        val detached = detachDesktopExternalRequest(original)
        stopPlayback()
        authority = authorizationCurrent
        jsHost = host; danmaku = overlay; danmakuPlugin = plugin; executionRevision = expectedRevision
        onBeforeStop = ::clearDanmaku
        releaseLaunch = { releaseRequest(launchId) }
        release = { clearDanmaku(); releaseLaunch(); request = null; completedSource = null
            jsHost = null; danmaku = null; danmakuPlugin = null; executionRevision = -1L }
        request = detached
        selectedIndex = detached.selectedStreamIndex
        selectStream(selectedIndex)
    }

    fun selectStream(index: Int) {
        if (!authority()) { stopPlayback(); error = "插件播放授权已经变化，请重新打开内容"; return }
        val current = request ?: return
        val stream = current.streams.getOrNull(index) ?: return
        clearDanmaku()
        try {
            val initialized = player ?: error("原生播放器未能初始化")
            sourceVersion = initialized.loadVersioned(PlaybackSource(videoUrl = stream.url,
                referer = "", cookieHeader = "", title = current.title, streamHeaders = stream.headers))
            selectedIndex = index; loaded = true; error = null; completedSource = null
            loadDanmaku(current, requireNotNull(sourceVersion))
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
