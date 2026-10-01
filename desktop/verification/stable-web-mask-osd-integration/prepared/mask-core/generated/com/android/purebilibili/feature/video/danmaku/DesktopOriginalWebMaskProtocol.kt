package com.android.purebilibili.feature.video.danmaku
import kotlinx.coroutines.*

internal suspend fun getDesktopOriginalWebMask(source: com.bilipai.desktop.danmaku.DesktopDanmakuSource, url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val resolvedUrl = if (url.startsWith("//")) "https:$url" else url
        try {
            source.special(resolvedUrl).takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Optional mask failure: existing source transport keeps bounded-body ownership.
            null
        }
    }
