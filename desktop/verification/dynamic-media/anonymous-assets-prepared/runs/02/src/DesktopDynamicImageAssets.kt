package com.bilipai.desktop.ui

import com.android.purebilibili.feature.dynamic.components.normalizeImageUrl
import com.android.purebilibili.feature.dynamic.components.normalizeLivePhotoVideoUrl
import com.android.purebilibili.feature.dynamic.components.resolveImageShareMimeType
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.bilipai.desktop.data.DesktopDynamicCacheOwner
import com.bilipai.desktop.data.DesktopDynamicCacheSessionGuard
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Existing card save owner, anonymous transport and chooser. Final movement is
 * admitted under the actual repository SessionStore guard, then this owner's
 * close lock. External card/page bool retirement is not a second transaction.
 */
internal class DesktopDynamicImageAssets(
    client: OkHttpClient,
    private val stillOwned: () -> Boolean,
    private val sessionGuard: DesktopDynamicCacheSessionGuard,
    private val expectedOwner: DesktopDynamicCacheOwner,
    private val selectTarget: suspend (String, String) -> DesktopDynamicSaveTarget? = ::selectDynamicSaveTarget,
    private val selectDirectory: suspend () -> Path? = ::selectDynamicSaveDirectory,
) : AutoCloseable {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        // Existing inherited interceptors may accept a forced account header.
        // Strip both at the final network boundary as well as using no jar.
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().removeHeader("Cookie").removeHeader(FORCE_COOKIE_HEADER).build())
        }.build()
    private val lock = Any()
    @Volatile private var closed = false
    private val calls = mutableSetOf<Call>()
    private val saveMutex = Mutex()
    private fun isOwned() = !closed && stillOwned()
    private fun assertOwned() { if (!isOwned()) throw CancellationException("图片操作已结束，请重新打开预览") }
    private suspend fun checkpoint() { currentCoroutineContext().ensureActive(); assertOwned() }
    private fun commitOwned(block: () -> Unit): Boolean = sessionGuard.withCurrentDynamicCacheOwner(expectedOwner) {
        synchronized(lock) { assertOwned(); block() }
    }
    private val exif = DesktopDynamicMotionPhotoExif(::isOwned)
    private val motionPhoto = DesktopDynamicMotionPhotoFiles(::isOwned, ::commitOwned, writeExif = exif::write)

    suspend fun saveImage(rawUrl: String): Boolean = saveMutex.withLock {
        checkpoint()
        val url = normalizeImageUrl(rawUrl)
        require(url.isNotBlank()) { "图片地址为空" }
        val mime = resolveImageShareMimeType(url)
        val target = selectTarget("BiliPai-${System.currentTimeMillis()}.${extension(mime)}", mime) ?: return@withLock false
        checkpoint(); write(url, target, MAX_IMAGE_BYTES)
    }
    suspend fun saveImages(rawUrls: List<String>): Boolean = saveMutex.withLock {
        checkpoint()
        if (rawUrls.isEmpty()) return@withLock false
        val parent = selectDirectory() ?: return@withLock false
        checkpoint()
        val stamp = System.currentTimeMillis()
        for ((index, raw) in rawUrls.withIndex()) {
            val url = normalizeImageUrl(raw)
            val target = parent.resolve("BiliPai-$stamp-${index + 1}.${extension(resolveImageShareMimeType(url))}")
            write(url, DesktopDynamicSaveTarget(target, false), MAX_IMAGE_BYTES)
        }
        true
    }
    suspend fun saveLivePhotoVideo(rawUrl: String): Boolean = saveMutex.withLock {
        checkpoint()
        val url = normalizeLivePhotoVideoUrl(rawUrl) ?: error("实况视频地址不受支持")
        val target = selectTarget("BiliPai-live-${System.currentTimeMillis()}.mp4", "video/mp4") ?: return@withLock false
        checkpoint(); write(url, target, MAX_VIDEO_BYTES)
    }
    suspend fun saveMotionPhoto(rawImageUrl: String, rawVideoUrl: String): Boolean = saveMutex.withLock {
        checkpoint()
        val imageUrl = normalizeImageUrl(rawImageUrl)
        require(imageUrl.isNotBlank()) { "图片地址为空" }
        val videoUrl = normalizeLivePhotoVideoUrl(rawVideoUrl) ?: error("实况视频地址不受支持")
        val target = selectTarget("BiliPai-motion-${System.currentTimeMillis()}.jpg", "image/jpeg") ?: return@withLock false
        checkpoint()
        withContext(Dispatchers.IO) {
            val parent = validateTarget(target)
            var image: Path? = null
            var video: Path? = null
            try {
                image = downloadTo(imageUrl, parent, MAX_IMAGE_BYTES)
                video = downloadTo(videoUrl, parent, MAX_VIDEO_BYTES)
                checkpoint()
                motionPhoto.composeDownloaded(image, video, target)
            } finally {
                image?.let(Files::deleteIfExists)
                video?.let(Files::deleteIfExists)
            }
        }
    }

    private suspend fun write(url: String, target: DesktopDynamicSaveTarget, limit: Long): Boolean = withContext(Dispatchers.IO) {
        checkpoint()
        val parent = validateTarget(target)
        val staged = downloadTo(url, parent, limit)
        try {
            checkpoint()
            val context = currentCoroutineContext()
            val admitted = commitOwned {
                context.ensureActive()
                validateTarget(target)
                val absolute = target.path.toAbsolutePath().normalize()
                if (target.replaceExisting) Files.move(staged, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                else Files.move(staged, absolute)
            }
            if (!admitted) throw CancellationException("图片会话已切换，保存未提交")
            true
        } finally { Files.deleteIfExists(staged) }
    }
    private fun validateTarget(target: DesktopDynamicSaveTarget): Path {
        val absolute = target.path.toAbsolutePath().normalize()
        val parent = requireNotNull(absolute.parent)
        UpdateStorage.existingPathWithoutLinks(parent)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "请选择有效的保存目录" }
        if (Files.exists(absolute, NOFOLLOW_LINKS)) {
            UpdateStorage.existingPathWithoutLinks(absolute)
            require(target.replaceExisting && Files.isRegularFile(absolute, NOFOLLOW_LINKS)) { "所选文件已存在" }
        }
        return parent
    }

    /** The callback owns its output stream until finished. Cancellation first
     * cancels the Call, then waits for callback drain before deleting its path.
     * No 200MiB video heap buffer and no callback-vs-file-delete race.
     */
    private suspend fun downloadTo(url: String, parent: Path, limit: Long): Path {
        checkpoint()
        UpdateStorage.existingPathWithoutLinks(parent)
        val call = client.newCall(Request.Builder().url(url).header("Referer", "https://www.bilibili.com/").get().build())
        val staged = Files.createTempFile(parent, ".bilipai-download-", ".tmp")
        val context = currentCoroutineContext()
        val finished = CompletableDeferred<Result<Unit>>()
        var delivered = false
        var enqueued = false
        try {
            synchronized(lock) { assertOwned(); calls.add(call) }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) { finished.complete(Result.failure(error)) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful) { "图片下载失败 (HTTP ${it.code})" }
                            val body = it.body ?: error("图片内容为空")
                            val expected = body.contentLength()
                            require(expected <= limit) { "图片或视频文件过大" }
                            UpdateStorage.existingPathWithoutLinks(parent)
                            UpdateStorage.existingPathWithoutLinks(staged)
                            body.byteStream().use { input ->
                                Files.newOutputStream(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var copied = 0L
                                    while (true) {
                                        context.ensureActive(); assertOwned()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        copied += count
                                        require(copied <= limit) { "图片或视频文件过大" }
                                        output.write(buffer, 0, count)
                                    }
                                    context.ensureActive(); assertOwned()
                                    require(copied > 0 && (expected < 0 || copied == expected)) { "图片或视频内容为空或下载不完整" }
                                    output.flush()
                                }
                            }
                        }
                    }
                    finished.complete(result)
                }
            })
            enqueued = true
            try {
                val outcome = finished.await()
                checkpoint(); outcome.getOrThrow()
                delivered = true
                return staged
            } catch (cancelled: CancellationException) {
                call.cancel()
                withContext(NonCancellable) { finished.await() }
                throw cancelled
            }
        } finally {
            if (enqueued && !finished.isCompleted) {
                call.cancel()
                withContext(NonCancellable) { finished.await() }
            }
            synchronized(lock) { calls.remove(call) }
            if (!delivered) Files.deleteIfExists(staged)
        }
    }
    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true; calls.toList().also { calls.clear() }
        }
        motionPhoto.close()
        pending.forEach(Call::cancel)
    }
    private fun extension(mime: String) = when (mime) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
    private companion object { const val MAX_IMAGE_BYTES = 32L * 1024 * 1024; const val MAX_VIDEO_BYTES = 200L * 1024 * 1024 }
}

// This actual Main type is unchanged. Task-only compile omits only this line
// so the real frozen Main class is used, without a SaveTarget override.
// Actual frozen Main DesktopDynamicSaveTarget retained; no class override.

internal suspend fun selectDynamicSaveTarget(name: String, mime: String): DesktopDynamicSaveTarget? = withContext(Dispatchers.IO) {
    var selected: DesktopDynamicSaveTarget? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply { dialogTitle = "保存图片 / 实况视频"; selectedFile = java.io.File(name) }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile.toPath()
            val exists = Files.exists(file, NOFOLLOW_LINKS)
            if (!exists || JOptionPane.showConfirmDialog(null, "所选文件已存在，是否替换？", "保存", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                selected = DesktopDynamicSaveTarget(file, exists)
        }
    }
    selected
}
private suspend fun selectDynamicSaveDirectory(): Path? = withContext(Dispatchers.IO) {
    var selected: Path? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply { dialogTitle = "保存全部图片"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selected = chooser.selectedFile.toPath()
    }
    selected
}
