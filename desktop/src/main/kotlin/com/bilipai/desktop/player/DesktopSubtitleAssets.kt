package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.android.purebilibili.feature.video.subtitle.isTrustedBilibiliSubtitleUrl
import com.android.purebilibili.feature.video.subtitle.normalizeBilibiliSubtitleUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Documents stay available while libmpv may reopen an external subtitle after a HWND rebuild. */
class DesktopSubtitleAssets(private val client: OkHttpClient) : AutoCloseable {
    private val directory = Files.createTempDirectory("bilipai-subtitles-")
    private val files = mutableSetOf<Path>()
    private var closed = false

    suspend fun import(track: SubtitleTrackMeta): Path = withContext(Dispatchers.IO) {
        require(isTrustedBilibiliSubtitleUrl(track.subtitleUrl)) { "服务器字幕地址不受支持" }
        val url = normalizeBilibiliSubtitleUrl(track.subtitleUrl)
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = directory.resolve("$digest.srt")
        synchronized(files) { check(!closed) { "字幕缓存已关闭" }; if (Files.isRegularFile(file)) return@withContext file }
        val request = Request.Builder().url(url).build()
        val text = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "字幕下载失败 (HTTP ${response.code})" }
            val body = response.body ?: error("字幕内容为空")
            require(body.contentLength() <= MAX_BYTES) { "字幕文件过大" }
            body.byteStream().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= MAX_BYTES) { "字幕文件过大" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8)
            }
        }
        val document = BiliSubtitleDocument.toSrt(text)
        require(document.isNotBlank()) { "服务器字幕没有可显示的内容" }
        synchronized(files) {
            check(!closed) { "字幕缓存已关闭" }
            Files.writeString(file, document)
            files.add(file)
        }
        file
    }

    override fun close() = synchronized(files) {
        closed = true
        files.forEach { runCatching { Files.deleteIfExists(it) } }
        files.clear()
        runCatching { Files.deleteIfExists(directory) }
        Unit
    }

    private companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
