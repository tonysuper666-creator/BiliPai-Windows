package com.bilipai.desktop.ui

import com.android.purebilibili.feature.dynamic.components.desktopOriginalMotionPhotoJpeg
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/** Existing Root owner/transport supplies downloaded files and atomic owner
 * admission. This adapter owns only one save operation's staged output; no
 * account, cookie jar, source registry, gallery list or cache is constructed.
 * EXIF is an explicit platform seam: null/failure uses the original raw-JPEG
 * fallback. It does not claim Windows Photos recognizes Motion Photo playback.
 */
internal class DesktopDynamicMotionPhotoFiles(
    private val stillOwned: () -> Boolean,
    private val withOwnedCommit: ((() -> Unit) -> Boolean),
    private val encodeJpeg95: (ByteArray) -> ByteArray = ::encodeDesktopMotionPhotoJpeg95,
    private val writeExif: ((ByteArray) -> ByteArray)? = null,
    private val onExifFallback: (Throwable?) -> Unit = {},
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val gate = Any()
    private val requestMutex = Mutex()
    private fun assertOwned() {
        if (!alive.get() || !stillOwned()) throw CancellationException("Motion Photo owner retired")
    }
    private suspend fun checkpoint() { currentCoroutineContext().ensureActive(); assertOwned() }

    suspend fun composeDownloaded(image: Path, video: Path, target: DesktopDynamicSaveTarget): Boolean = requestMutex.withLock {
        withContext(Dispatchers.IO) {
            checkpoint()
            UpdateStorage.existingPathWithoutLinks(image)
            UpdateStorage.existingPathWithoutLinks(video)
            require(Files.isRegularFile(image, NOFOLLOW_LINKS) && Files.size(image) in 1..MAX_IMAGE_BYTES) { "图片文件无效或过大" }
            val videoSize = Files.size(video)
            require(Files.isRegularFile(video, NOFOLLOW_LINKS) && videoSize in 1..MAX_VIDEO_BYTES) { "实况视频文件无效或过大" }
            val imageBytes = Files.newInputStream(image).use { it.readNBytes(MAX_IMAGE_BYTES.toInt() + 1) }
            checkpoint()
            require(imageBytes.size <= MAX_IMAGE_BYTES) { "图片文件过大" }
            val rawJpeg = encodeJpeg95(imageBytes)
            checkpoint(); requireJpeg(rawJpeg)
            val jpegWithExif = try {
                val writer = writeExif
                if (writer == null) { onExifFallback(null); rawJpeg }
                else writer(rawJpeg).also(::requireJpeg)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                checkpoint(); onExifFallback(failure); rawJpeg
            }
            checkpoint()
            // Original marker parsing, insertion order and JPEG assembly;
            // producer removes only three duplicate XMP attributes explicitly.
            val jpeg = desktopOriginalMotionPhotoJpeg(jpegWithExif, videoSize)
            checkpoint()
            val absolute = target.path.toAbsolutePath().normalize()
            val parent = requireNotNull(absolute.parent)
            validateTarget(parent, absolute, target.replaceExisting)
            val staged = Files.createTempFile(parent, ".bilipai-motion-photo-", ".tmp")
            try {
                Files.newOutputStream(staged).use { output ->
                    output.write(jpeg)
                    Files.newInputStream(video).use { input ->
                        require(copyOwned(input, output, videoSize) == videoSize) { "实况视频在保存时发生变化" }
                    }
                    output.flush()
                }
                checkpoint()
                val commitContext = currentCoroutineContext()
                val committed = withOwnedCommit {
                    synchronized(gate) {
                        commitContext.ensureActive()
                        assertOwned()
                        validateTarget(parent, absolute, target.replaceExisting)
                        if (target.replaceExisting) Files.move(staged, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        else Files.move(staged, absolute)
                    }
                }
                if (!committed) throw CancellationException("Motion Photo owner changed before commit")
                true
            } finally { Files.deleteIfExists(staged) }
        }
    }

    /** The original video never enters a single heap ByteArray. This bounded
     * copy also gives cancellation and the existing Root owner a checkpoint. */
    private suspend fun copyOwned(input: InputStream, output: OutputStream, expectedBytes: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            checkpoint()
            val count = input.read(buffer)
            if (count < 0) return copied
            copied += count
            require(copied <= expectedBytes && copied <= MAX_VIDEO_BYTES) { "实况视频在保存时发生变化或过大" }
            output.write(buffer, 0, count)
        }
    }
    override fun close() { synchronized(gate) { alive.set(false) } }
    private fun validateTarget(parent: Path, target: Path, replace: Boolean) {
        UpdateStorage.existingPathWithoutLinks(parent)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "请选择有效的保存目录" }
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            UpdateStorage.existingPathWithoutLinks(target)
            require(replace && Files.isRegularFile(target, NOFOLLOW_LINKS)) { "所选文件已存在" }
        }
    }
    private fun requireJpeg(bytes: ByteArray) {
        require(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "图片编码未生成标准 JPEG" }
    }
    private companion object {
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        const val MAX_VIDEO_BYTES = 200L * 1024 * 1024
    }
}

/** Android Bitmap JPEG quality=95 replaced only at the existing Skia native
 * codec boundary. This is not a second Motion Photo decision algorithm. */
internal fun encodeDesktopMotionPhotoJpeg95(bytes: ByteArray): ByteArray = Image.makeFromEncoded(bytes).use { image ->
    require(image.width.toLong() * image.height <= 8192L * 8192) { "图片像素超过保存限制" }
    requireNotNull(image.encodeToData(EncodedImageFormat.JPEG, 95)).use { it.bytes }
}
