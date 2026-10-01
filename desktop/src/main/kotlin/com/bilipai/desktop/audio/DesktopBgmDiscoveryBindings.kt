package com.bilipai.desktop.audio

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal interface DesktopBgmDiscoveryRequests {
    suspend fun getBgmDetail(musicId: String, aid: Long, cid: Long): Result<BgmDetailData?>
    suspend fun getBgmRecommendVideos(musicId: String, aid: Long, cid: Long, page: Int, pageSize: Int): Result<List<BgmRecommendVideo>>
}
internal val LocalDesktopBgmDiscoveryRequests = staticCompositionLocalOf<DesktopBgmDiscoveryRequests> {
    error("Original BGM discovery owner is not mounted")
}

internal class DesktopBgmDiscoveryOperationsBinding(
    private val operations: DesktopDynamicCardOperations,
    private val currentTarget: () -> Boolean,
) : DesktopBgmDiscoveryRequests {
    private suspend fun owned() {
        currentCoroutineContext().ensureActive()
        if (!currentTarget() || !operations.isOwned()) throw CancellationException("BGM video part owner retired")
    }
    override suspend fun getBgmDetail(musicId: String, aid: Long, cid: Long): Result<BgmDetailData?> {
        owned(); return operations.getBgmDetail(musicId, aid, cid).also { owned() }
    }
    override suspend fun getBgmRecommendVideos(musicId: String, aid: Long, cid: Long, page: Int, pageSize: Int): Result<List<BgmRecommendVideo>> {
        owned(); return operations.getBgmRecommendVideos(musicId, aid, cid, page, pageSize).also { owned() }
    }
}

/** Windows binding of Android Uri.getQueryParameter: plus characters remain literal. */
internal fun desktopBgmQueryParameter(url: String, key: String): String? = runCatching {
    fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
    URI(url).rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
        val parts = pair.split('=', limit = 2)
        if (decode(parts[0]) == key) decode(parts.getOrElse(1) { "" }) else null
    }
}.getOrNull()
