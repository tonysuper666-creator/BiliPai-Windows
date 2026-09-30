package com.bilipai.desktop.ui

import com.android.purebilibili.feature.dynamic.components.normalizeImageUrl
import com.android.purebilibili.feature.dynamic.components.resolveImageShareMimeType
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-selected Windows file replaces Android MediaStore, preserving original
 * URL normalization, MIME/bytes and the explicit save action. No account jar is
 * used for image/video download; source/card retirement cancels its own Calls.
 */
internal class DesktopDynamicImageAssets(
    client: OkHttpClient,
    private val stillOwned: () -> Boolean,
    private val selectTarget: suspend (String, String) -> DesktopDynamicSaveTarget? = ::selectDynamicSaveTarget,
    private val selectDirectory: suspend () -> Path? = ::selectDynamicSaveDirectory,
) : AutoCloseable {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private fun assertOwned() { check(!closed && stillOwned()) { "图片操作已结束，请重新打开预览" } }

    suspend fun saveImage(rawUrl: String): Boolean {
        val url = normalizeImageUrl(rawUrl)
        require(url.isNotBlank()) { "图片地址为空" }
        val mime = resolveImageShareMimeType(url)
        synchronized(lock) { assertOwned() }
        val target = selectTarget("BiliPai-${System.currentTimeMillis()}.${extension(mime)}", mime) ?: return false
        return write(url, target, MAX_IMAGE_BYTES)
    }
    suspend fun saveImages(rawUrls: List<String>): Boolean {
        if (rawUrls.isEmpty()) return false
        synchronized(lock) { assertOwned() }
        val parent = selectDirectory() ?: return false
        val stamp = System.currentTimeMillis()
        for ((index, raw) in rawUrls.withIndex()) {
            val url = normalizeImageUrl(raw)
            val target = parent.resolve("BiliPai-$stamp-${index + 1}.${extension(resolveImageShareMimeType(url))}")
            write(url, DesktopDynamicSaveTarget(target, replaceExisting = false), MAX_IMAGE_BYTES)
        }
        return true
    }
    suspend fun saveLivePhotoVideo(url: String): Boolean {
        require(url.startsWith("https://") || url.startsWith("http://")) { "实况视频地址不受支持" }
        synchronized(lock) { assertOwned() }
        val target = selectTarget("BiliPai-live-${System.currentTimeMillis()}.mp4", "video/mp4") ?: return false
        return write(url, target, MAX_VIDEO_BYTES)
    }
    private suspend fun write(url: String, target: DesktopDynamicSaveTarget, limit: Int): Boolean = withContext(Dispatchers.IO) {
        ensureActive(); synchronized(lock) { assertOwned() }
        val absolute = target.path.toAbsolutePath().normalize()
        val parent = requireNotNull(absolute.parent)
        UpdateStorage.existingPathWithoutLinks(parent)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "请选择有效的保存目录" }
        if (Files.exists(absolute, NOFOLLOW_LINKS)) {
            UpdateStorage.existingPathWithoutLinks(absolute)
            require(target.replaceExisting && Files.isRegularFile(absolute, NOFOLLOW_LINKS)) { "所选文件已存在" }
        }
        val call = client.newCall(Request.Builder().url(url).header("Referer", "https://www.bilibili.com/").get().build())
        synchronized(lock) { assertOwned(); calls.add(call) }
        try {
            val bytes = download(call, limit)
            ensureActive()
            synchronized(lock) {
                assertOwned(); ensureActive()
                // Recheck actual parent and selected path after the HTTP wait.
                UpdateStorage.existingPathWithoutLinks(parent)
                if (Files.exists(absolute, NOFOLLOW_LINKS)) {
                    UpdateStorage.existingPathWithoutLinks(absolute)
                    require(target.replaceExisting && Files.isRegularFile(absolute, NOFOLLOW_LINKS)) { "所选文件已存在" }
                }
                val temporary = Files.createTempFile(parent, ".bilipai-image-", ".tmp")
                try {
                    Files.write(temporary, bytes); ensureActive(); assertOwned()
                    if (target.replaceExisting) Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    // ATOMIC_MOVE permits implementation-specific replacement of
                    // an existing target even without REPLACE_EXISTING. The plain
                    // same-directory move preserves the user's no-overwrite choice.
                    else Files.move(temporary, absolute)
                } finally { Files.deleteIfExists(temporary) }
            }
            true
        } finally { synchronized(lock) { calls.remove(call) } }
    }
    private suspend fun download(call: Call, limit: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        check(it.isSuccessful) { "图片下载失败 (HTTP ${it.code})" }
                        val body = it.body ?: error("图片内容为空")
                        require(body.contentLength() <= limit) { "图片或视频文件过大" }
                        body.byteStream().use { input ->
                            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                require(bytes.size() + count <= limit) { "图片或视频文件过大" }
                                bytes.write(buffer, 0, count)
                            }
                            bytes.toByteArray().also { require(it.isNotEmpty()) { "图片内容为空" } }
                        }
                    }
                    continuation.resume(bytes)
                } catch (failure: Throwable) { continuation.resumeWithException(failure) }
            }
        })
    }
    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true; calls.toList().also { calls.clear() }
        }
        pending.forEach(Call::cancel)
    }
    private fun extension(mime: String) = when (mime) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
    private companion object { const val MAX_IMAGE_BYTES = 32 * 1024 * 1024; const val MAX_VIDEO_BYTES = 200 * 1024 * 1024 }
}
internal data class DesktopDynamicSaveTarget(val path: Path, val replaceExisting: Boolean)
private suspend fun selectDynamicSaveTarget(name: String, mime: String): DesktopDynamicSaveTarget? = withContext(Dispatchers.IO) {
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
