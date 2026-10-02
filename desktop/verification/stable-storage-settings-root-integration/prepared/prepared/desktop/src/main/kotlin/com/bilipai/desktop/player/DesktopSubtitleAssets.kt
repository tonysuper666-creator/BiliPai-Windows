package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.subtitle.isTrustedBilibiliSubtitleUrl
import com.android.purebilibili.feature.video.subtitle.normalizeBilibiliSubtitleUrl
import com.android.purebilibili.feature.video.subtitle.parseBiliSubtitleBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CacheControl
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Documents stay available while libmpv may reopen an external subtitle after a HWND rebuild. */
class DesktopSubtitleAssets(private val client: OkHttpClient) : AutoCloseable {
    private val directory = Files.createTempDirectory("bilipai-subtitles-")
    private val files = mutableSetOf<Path>()
    private val fileCues = mutableMapOf<Path, List<SubtitleCue>>()
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private var activeImports = 0

    suspend fun import(track: SubtitleTrackMeta): Path = import(track, client)

    /** Same application-owned files/document/cancellation; caller supplies the
     * already captured Repository Call.Factory. This creates no downloader/client. */
    internal suspend fun import(track: SubtitleTrackMeta, ownedCalls: Call.Factory): Path = withContext(Dispatchers.IO) {
        synchronized(files) {check(!closed);activeImports++}
        try {
        require(isTrustedBilibiliSubtitleUrl(track.subtitleUrl)) { "服务器字幕地址不受支持" }
        val url = normalizeBilibiliSubtitleUrl(track.subtitleUrl)
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = directory.resolve("$digest.srt")
        synchronized(files) { check(!closed) { "字幕缓存已关闭" }; if (Files.isRegularFile(file)) return@withContext file }
        val call = ownedCalls.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).get()
            .header("Referer", "https://www.bilibili.com").header("Cache-Control", "no-cache").header("Pragma", "no-cache").build())
        synchronized(files) { check(!closed) { "字幕缓存已关闭" }; calls.add(call) }
        try {
            // Keep cancellation attached while headers AND the body are pending. A returned Response
            // would otherwise detach the cancellable continuation before a blocking body read.
            val text = download(call)
            ensureActive()
            val document = BiliSubtitleDocument.toSrt(text)
            require(document.isNotBlank()) { "服务器字幕没有可显示的内容" }
            val cues = parseBiliSubtitleBody(text)
            synchronized(files) {
                check(!closed) { "字幕缓存已关闭" }
                ensureActive()
                if (Files.isRegularFile(file)) return@withContext file
                val temporary = Files.createTempFile(directory, ".subtitle-", ".tmp")
                var committed = false
                try {
                    Files.writeString(temporary, document)
                    ensureActive()
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
                    committed = true
                    ensureActive()
                    files.add(file)
                    fileCues[file] = cues
                } catch (failure: Throwable) {
                    if (committed) Files.deleteIfExists(file)
                    throw failure
                } finally { Files.deleteIfExists(temporary) }
            }
            file
        } finally { synchronized(files) { calls.remove(call) } }
        } finally { synchronized(files) {activeImports--} }
    }

    /** The original parsed cue list is retained with its application-owned native file. */
    internal fun cues(file: Path): List<SubtitleCue> = synchronized(files) {
        check(!closed) { "字幕缓存已关闭" }
        fileCues[file]?.toList() ?: error("字幕文档不属于当前缓存")
    }

    private suspend fun download(call: Call): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                continuation.resumeWithException(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
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
                    continuation.resume(text)
                } catch (failure: Throwable) { continuation.resumeWithException(failure) }
            }
        })
    }

    internal fun cacheSizes(): Pair<Long,Long> = synchronized(files) {
        files.sumOf { if(Files.isRegularFile(it))Files.size(it) else 0L } to
            fileCues.values.sumOf { cues -> cues.sumOf { cue -> cue.content.length*2L + cue.words.sumOf { word -> word.text.length*2L+24L }+32L } }
    }
    /** Caller holds the actual native idle-maintenance lease. No in-flight import may publish here. */
    internal fun clearIdle(checkRequest: () -> Unit) = synchronized(files) {
        checkRequest();check(!closed);require(calls.isEmpty() && activeImports==0) { "字幕仍在下载或提交，请稍后重试" }
        val ownedFiles=files.toList()
        com.bilipai.desktop.update.UpdateStorage.existingPathWithoutLinks(directory)
        ownedFiles.forEach { path ->
            require(path.toAbsolutePath().normalize().parent==directory.toAbsolutePath().normalize())
            if(Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                com.bilipai.desktop.update.UpdateStorage.existingPathWithoutLinks(path)
                require(Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))
            }
        }
        ownedFiles.forEach { path -> checkRequest();Files.deleteIfExists(path);files.remove(path);fileCues.remove(path) }
    }
    override fun close() {
        val pending = synchronized(files) {
            if (closed) return
            closed = true
            val active = calls.toList()
            calls.clear()
            files.forEach { runCatching { Files.deleteIfExists(it) } }
            files.clear()
            fileCues.clear()
            runCatching { Files.deleteIfExists(directory) }
            active
        }
        pending.forEach(Call::cancel)
    }

    private companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
