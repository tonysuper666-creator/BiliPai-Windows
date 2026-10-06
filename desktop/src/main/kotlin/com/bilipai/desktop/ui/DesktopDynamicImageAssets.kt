package com.bilipai.desktop.ui

import com.android.purebilibili.feature.dynamic.components.normalizeImageUrl
import com.android.purebilibili.feature.dynamic.components.normalizeLivePhotoVideoUrl
import com.android.purebilibili.feature.dynamic.components.resolveImageShareMimeType
import com.android.purebilibili.feature.dynamic.components.desktopOriginalStaticGalleryFileName
import com.android.purebilibili.feature.dynamic.components.desktopOriginalStaticGalleryMimeType
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.bilipai.desktop.data.DesktopDynamicCacheOwner
import com.bilipai.desktop.data.DesktopDynamicCacheSessionGuard
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.awt.Component
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
    private val selectTarget: suspend (String, String) -> DesktopDynamicSaveTarget? = { name, mime -> selectDynamicSaveTarget(name, mime) },
    private val selectDirectory: suspend () -> Path? = { selectDynamicSaveDirectory() },
    private val imageSaveLocations: DesktopImageSaveLocations? = null,
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
    private fun isOwned() = !closed && stillOwned() && imageSaveLocations?.isActive() != false
    private fun assertOwned() { if (!isOwned()) throw CancellationException("图片操作已结束，请重新打开预览") }
    private suspend fun checkpoint() { currentCoroutineContext().ensureActive(); assertOwned() }
    private fun commitOwned(block: () -> Unit): Boolean = sessionGuard.withCurrentDynamicCacheOwner(expectedOwner) {
        synchronized(lock) {
            assertOwned()
            if (imageSaveLocations == null) block()
            else if (!imageSaveLocations.withCommit(block)) throw CancellationException("图片保存设置已结束")
        }
    }
    private val exif = DesktopDynamicMotionPhotoExif(::isOwned)
    private val motionPhoto = DesktopDynamicMotionPhotoFiles(::isOwned, ::commitOwned, writeExif = exif::write)

    /** Original preview share bytes, using this SAME anonymous owned download actor; never decoded. */
    internal suspend fun readOriginalShareBytes(rawUrl: String): ByteArray = withContext(Dispatchers.IO) {
        checkpoint()
        val url = normalizeImageUrl(rawUrl)
        require(url.isNotBlank()) { "图片地址为空" }
        val source = downloadTo(url, scratchDirectory(), MAX_IMAGE_BYTES)
        try {
            checkpoint(); require(Files.size(source) in 1..MAX_IMAGE_BYTES)
            Files.readAllBytes(source).also { checkpoint(); require(it.size.toLong() in 1..MAX_IMAGE_BYTES) }
        } finally { Files.deleteIfExists(source) }
    }

    suspend fun saveImage(rawUrl: String): Boolean = saveMutex.withLock {
        saveImageUnlocked(rawUrl)
    }
    suspend fun saveImages(rawUrls: List<String>): Boolean = saveMutex.withLock {
        checkpoint()
        if (rawUrls.isEmpty()) return@withLock false
        // Root uses the global remembered location. Explicit chooser seams remain
        // available for callers that intentionally supply their own save target.
        val parent = if (imageSaveLocations == null) selectDirectory() ?: return@withLock false else null
        checkpoint()
        val stamp = System.currentTimeMillis()
        var allSaved = true
        for ((index, raw) in rawUrls.withIndex()) {
            checkpoint()
            val saved = try {
                val target = parent?.resolve("BiliPai-$stamp-${index + 1}.${extension(resolveImageShareMimeType(normalizeImageUrl(raw)))}")
                saveImageUnlocked(raw, target?.let { DesktopDynamicSaveTarget(it, false) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                checkpoint()
                false
            }
            if (!saved) allSaved = false
        }
        allSaved
    }
    private suspend fun saveImageUnlocked(rawUrl: String, selectedTarget: DesktopDynamicSaveTarget? = null): Boolean = withContext(Dispatchers.IO) {
        checkpoint()
        val url = normalizeImageUrl(rawUrl)
        require(url.isNotBlank()) { "图片地址为空" }
        val name = desktopOriginalStaticGalleryFileName(url)
        val target = if (imageSaveLocations == null) selectedTarget ?: selectTarget(name, desktopOriginalStaticGalleryMimeType(url)) ?: return@withContext false else null
        checkpoint()
        // One anonymous download remains owned by this Assets operation. Each
        // destination attempt opens it afresh; a failed custom write cannot leave
        // an exhausted input stream for the default destination.
        val source = downloadTo(url, scratchDirectory(), MAX_IMAGE_BYTES)
        try {
            if (imageSaveLocations == null) writeStatic(source, checkNotNull(target), url)
            else imageSaveLocations.save(name, ::checkpoint, ::commitOwned) { writeStatic(source, it, url) }
        } finally { Files.deleteIfExists(source) }
    }
    /** Original Profile gallery writes its supplied bytes with JPEG display name. Reuse this sole save actor. */
    suspend fun saveProfileGalleryBytes(bytes: ByteArray, fileName: String, profileOwns: () -> Boolean,
        profileCommit: ((() -> Unit) -> Boolean)): Boolean = saveCapturedGalleryBytes(bytes,fileName,"image/jpeg",profileOwns,profileCommit)

    /** Native frame bytes remain PNG. Same Assets/Locations actor and publication gate. */
    internal suspend fun saveNativeFrameBytes(bytes:ByteArray,fileName:String,stillOwned:()->Boolean,
        commit:((()->Unit)->Boolean)):Boolean = saveCapturedGalleryBytes(bytes,fileName,"image/png",stillOwned,commit)

    private suspend fun saveCapturedGalleryBytes(bytes:ByteArray,fileName:String,mime:String,profileOwns:()->Boolean,
        profileCommit:((()->Unit)->Boolean)):Boolean = saveMutex.withLock {
        saveCapturedGalleryBytesUnlocked(bytes,fileName,mime,profileOwns,profileCommit)
    }

    private suspend fun saveCapturedGalleryBytesUnlocked(bytes:ByteArray,fileName:String,mime:String,profileOwns:()->Boolean,
        profileCommit:((()->Unit)->Boolean)):Boolean {
        require(mime=="image/jpeg" || mime=="image/png")
        suspend fun profileCheckpoint() {checkpoint();if(!profileOwns())throw CancellationException("Profile gallery entry retired")}
        profileCheckpoint();require(bytes.size.toLong() in 1..MAX_IMAGE_BYTES)
        val profileCaller=currentCoroutineContext()
        fun commitProfileOwned(block: () -> Unit): Boolean = commitOwned {
            if(!profileCommit {profileCaller.ensureActive();if(!profileOwns())throw CancellationException("Profile gallery entry retired");block()})
                throw CancellationException("Profile gallery entry retired")
        }
        require(fileName == Path.of(fileName).fileName.toString() && fileName.isNotBlank())
        suspend fun write(target: DesktopDynamicSaveTarget): Boolean = withContext(Dispatchers.IO) {
            profileCheckpoint();val parent=validateTarget(target)
            val stage=Files.createTempFile(parent,".bilipai-profile-gallery-",".tmp")
            try {
                Files.newOutputStream(stage).use { output ->
                    var offset=0
                    while(offset<bytes.size) {profileCheckpoint();val count=minOf(64*1024,bytes.size-offset);output.write(bytes,offset,count);offset+=count}
                }
                if(!commitProfileOwned {validateTarget(target);Files.move(stage,target.path.toAbsolutePath().normalize())})
                    throw CancellationException("Profile gallery owner retired")
                true
            } finally {Files.deleteIfExists(stage)}
        }
        return if(imageSaveLocations==null)write(selectTarget(fileName,mime) ?: return false)
        else imageSaveLocations.save(fileName,::profileCheckpoint,::commitProfileOwned,::write)
    }

    /** Original DownloadManager cover bytes/$title.jpg, on the SAME existing save actor.
     * The Windows file-name mapping reuses the existing manager sanitizer; no static
     * image transcoding is substituted for this original raw-byte cover operation. */
    suspend fun saveVideoCoverToGallery(rawUrl: String, title: String, videoOwns: () -> Boolean,
        videoCommit: ((() -> Unit) -> Boolean)): Boolean = saveMutex.withLock {
        requireNotNull(imageSaveLocations) { "Root global image save locations are required" }
        suspend fun videoCheckpoint() {
            checkpoint()
            if (!videoOwns()) throw CancellationException("Video cover entry retired")
        }
        videoCheckpoint()
        val source = downloadTo(rawUrl.replace("http://", "https://"), scratchDirectory(), MAX_IMAGE_BYTES)
        try {
            withContext(Dispatchers.IO) {
                videoCheckpoint()
                val bytes = java.io.ByteArrayOutputStream().use { output ->
                    Files.newInputStream(source).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            videoCheckpoint()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                    output.toByteArray()
                }
                videoCheckpoint()
                saveCapturedGalleryBytesUnlocked(bytes,
                    com.bilipai.desktop.download.DesktopDownloadManager.safeOutputName(title) + ".jpg", "image/jpeg",
                    videoOwns, videoCommit)
            }
        } finally { Files.deleteIfExists(source) }
    }

    suspend fun saveLivePhotoVideo(rawUrl: String): Boolean = saveMutex.withLock {
        checkpoint()
        val url = normalizeLivePhotoVideoUrl(rawUrl) ?: error("实况视频地址不受支持")
        val name = "BiliPai_Live_${System.currentTimeMillis()}.mp4"
        if (imageSaveLocations == null) {
            val target = selectTarget(name, "video/mp4") ?: return@withLock false
            checkpoint(); writeRaw(url, target, MAX_VIDEO_BYTES)
        } else imageSaveLocations.saveDefaultVideo(name, ::checkpoint, ::commitOwned) {
            writeRaw(url, it, MAX_VIDEO_BYTES)
        }
    }
    suspend fun saveMotionPhoto(rawImageUrl: String, rawVideoUrl: String): Boolean = saveMutex.withLock {
        checkpoint()
        val imageUrl = normalizeImageUrl(rawImageUrl)
        require(imageUrl.isNotBlank()) { "图片地址为空" }
        val videoUrl = normalizeLivePhotoVideoUrl(rawVideoUrl) ?: error("实况视频地址不受支持")
        val name = "BiliPai_Live_${System.currentTimeMillis()}.jpg"
        val target = if (imageSaveLocations == null) selectTarget(name, "image/jpeg") ?: return@withLock false else null
        checkpoint()
        withContext(Dispatchers.IO) {
            val parent = scratchDirectory()
            var image: Path? = null
            var video: Path? = null
            try {
                image = downloadTo(imageUrl, parent, MAX_IMAGE_BYTES)
                video = downloadTo(videoUrl, parent, MAX_VIDEO_BYTES)
                checkpoint()
                if (imageSaveLocations == null) motionPhoto.composeDownloaded(image, video, checkNotNull(target))
                else imageSaveLocations.save(name, ::checkpoint, ::commitOwned) {
                    motionPhoto.composeDownloaded(image, video, it)
                }
            } finally {
                image?.let(Files::deleteIfExists)
                video?.let(Files::deleteIfExists)
            }
        }
    }

    private fun scratchDirectory(): Path = Path.of(System.getProperty("java.io.tmpdir")).toRealPath().also {
        UpdateStorage.existingPathWithoutLinks(it)
    }

    private suspend fun writeStatic(source: Path, target: DesktopDynamicSaveTarget, imageUrl: String): Boolean = withContext(Dispatchers.IO) {
        checkpoint()
        val parent = validateTarget(target)
        val staged = Files.createTempFile(parent, ".bilipai-image-", ".tmp")
        try {
            encodeDesktopStaticGalleryImageToStage(source, staged, imageUrl, MAX_IMAGE_BYTES, MAX_DECODED_IMAGE_BYTES, ::checkpoint)
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

    private suspend fun writeRaw(url: String, target: DesktopDynamicSaveTarget, limit: Long): Boolean = withContext(Dispatchers.IO) {
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
    private companion object {
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        const val MAX_VIDEO_BYTES = 200L * 1024 * 1024
        const val MAX_DECODED_IMAGE_BYTES = 256L * 1024 * 1024
    }
}

internal data class DesktopDynamicSaveTarget(val path: Path, val replaceExisting: Boolean)

internal suspend fun selectDynamicSaveTarget(name: String, mime: String, parent: Component? = null,
    stillOwned: () -> Boolean = { true }): DesktopDynamicSaveTarget? = withContext(Dispatchers.IO) {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (!stillOwned()) return@withContext null
    var selected: DesktopDynamicSaveTarget? = null
    SwingUtilities.invokeAndWait {
        // The original chooser is still the only chooser. Recheck at its actual EDT launch.
        if (caller[Job]?.isActive == false || !stillOwned()) return@invokeAndWait
        val chooser = JFileChooser().apply { dialogTitle = "保存图片 / 实况视频"; selectedFile = java.io.File(name) }
        if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            if (caller[Job]?.isActive == false) return@invokeAndWait
            val file = chooser.selectedFile.toPath()
            val exists = Files.exists(file, NOFOLLOW_LINKS)
            if (!exists || (stillOwned() && JOptionPane.showConfirmDialog(parent, "所选文件已存在，是否替换？", "保存", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION))
                selected = DesktopDynamicSaveTarget(file, exists)
        }
    }
    selected
}
internal suspend fun selectDynamicSaveDirectory(parent: Component? = null, title: String = "保存全部图片"): Path? = withContext(Dispatchers.IO) {
    var selected: Path? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply { dialogTitle = title; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) selected = chooser.selectedFile.toPath()
    }
    selected
}
