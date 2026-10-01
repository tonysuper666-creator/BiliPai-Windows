package com.bilipai.desktop.ui

import com.android.purebilibili.feature.dynamic.components.desktopOriginalStaticGalleryExtension
import com.android.purebilibili.feature.dynamic.components.desktopOriginalStaticGalleryKeepBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** Prepared Windows codec seam. The caller supplies its existing owner checkpoint,
 * source-size limit and decoded-pixel budget, and owns both private scratch paths.
 * This function has no store/client/cache/owner, creates no target and performs no
 * final commit. Root's existing Assets cleanup and final ownership gate remain required.
 * GIF/WebP stay byte-identical; remaining input is decoded and encoded as original
 * PNG or JPEG95. Windows native decoder behaviour is an explicit platform boundary. */
internal suspend fun encodeDesktopStaticGalleryImageToStage(
    source: Path,
    staged: Path,
    imageUrl: String,
    maxEncodedBytes: Long,
    maxDecodedBytes: Long,
    checkpoint: suspend () -> Unit,
) = withContext(Dispatchers.IO) {
    require(maxEncodedBytes in 1 until Int.MAX_VALUE.toLong())
    require(maxDecodedBytes > 0)
    checkpoint()
    require(Files.isRegularFile(source, NOFOLLOW_LINKS) && Files.isRegularFile(staged, NOFOLLOW_LINKS))
    require(!Files.isSameFile(source, staged))
    val sourceSize = Files.size(source)
    require(sourceSize in 1..maxEncodedBytes) { "图片内容为空或过大" }
    if (desktopOriginalStaticGalleryKeepBytes(imageUrl)) {
        Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(staged, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= maxEncodedBytes)
                    output.write(buffer, 0, count)
                }
                require(copied == sourceSize) { "图片内容已变化" }
            }
        }
    } else {
        // Capped read prevents a changed input from allocating beyond the inherited
        // encoded-input limit; metadata is checked before native raster decoding.
        val input = Files.newInputStream(source, NOFOLLOW_LINKS).use { stream ->
            stream.readNBytes(Math.toIntExact(maxEncodedBytes + 1L))
        }
        checkpoint()
        require(input.size.toLong() == sourceSize && input.size.toLong() <= maxEncodedBytes) { "图片内容已变化" }
        Data.makeFromBytes(input).use { data ->
            Codec.makeFromData(data).use { codec ->
                val info = codec.imageInfo
                require(info.width > 0 && info.height > 0)
                val decodedBytes = Math.multiplyExact(
                    Math.multiplyExact(info.width.toLong(), info.height.toLong()),
                    maxOf(info.bytesPerPixel, 4).toLong(),
                )
                require(decodedBytes <= maxDecodedBytes) { "图片解码尺寸过大" }
            }
        }
        checkpoint()
        val format = if (desktopOriginalStaticGalleryExtension(imageUrl) == "png") EncodedImageFormat.PNG else EncodedImageFormat.JPEG
        Image.makeFromEncoded(input).use { image ->
            image.encodeToData(format, 95)?.use { encoded ->
                checkpoint()
                require(encoded.size.toLong() in 1..maxEncodedBytes) { "图片编码内容为空或过大" }
                // Output is copied in bounded chunks rather than cloning the complete
                // native encoded buffer into another Java heap ByteArray.
                Files.newOutputStream(staged, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { output ->
                    var offset = 0
                    while (offset < encoded.size) {
                        checkpoint()
                        val count = minOf(64 * 1024, encoded.size - offset)
                        output.write(encoded.getBytes(offset, count))
                        offset += count
                    }
                }
            } ?: error("图片编码失败")
        }
    }
    checkpoint()
}
