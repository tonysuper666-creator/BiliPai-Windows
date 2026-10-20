package com.android.purebilibili.core.ui.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.android.purebilibili.feature.home.isStaticHomeWallpaperUri
import com.bilipai.desktop.ui.desktopHomeWallpaperFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/*
 * Windows meaning of the v0.4.6 WallpaperMediaType.kt (rememberIsStaticWallpaper).
 *
 * The Android original asks BitmapFactory for the bounds and MIME type of a `.img` import or a content: URI. Windows has
 * no content: URIs and no BitmapFactory, but it does have the same legacy `.img` imports: the wallpaper importer stores
 * every picked image as `wallpaper_<id>.img`, so the file name says nothing and a GIF can hide behind it. The decision
 * is therefore taken from the first bytes of the local file, with no decode and no ImageIO. A wallpaper is static when
 * the header names a still image container with a positive size; GIF and WebP stay animated-capable (the original
 * rejects image/gif and image/webp the same way), video is out of scope for the Windows wallpaper and never static.
 */

/** Containers a local wallpaper file can hold. The signature decides, never the file name. */
internal enum class DesktopWallpaperImageFormat { PNG, JPEG, BMP, GIF, WEBP }

/** Container and pixel size read from a header, without decoding a pixel. */
internal data class DesktopWallpaperImageHeader(val format: DesktopWallpaperImageFormat, val width: Int, val height: Int)

/** JPEG puts its frame header behind EXIF and ICC segments; one mebibyte reaches it for every ordinary photo. */
internal const val DESKTOP_WALLPAPER_HEADER_LIMIT_BYTES = 1 shl 20

/** The container and size named by [bytes], or null when it is not a PNG, JPEG, BMP, GIF or WebP header with a size. */
internal fun parseDesktopWallpaperImageHeader(bytes: ByteArray): DesktopWallpaperImageHeader? {
    fun u8(at: Int): Int = bytes[at].toInt() and 0xFF
    fun be16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
    fun be32(at: Int): Long = (be16(at).toLong() shl 16) or be16(at + 2).toLong()
    fun le16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
    fun le24(at: Int): Int = le16(at) or (u8(at + 2) shl 16)
    fun le32(at: Int): Long = le16(at).toLong() or (le16(at + 2).toLong() shl 16)
    fun has(at: Int, vararg expected: Int): Boolean =
        bytes.size >= at + expected.size && expected.indices.all { u8(at + it) == expected[it] }
    fun header(format: DesktopWallpaperImageFormat, width: Long, height: Long): DesktopWallpaperImageHeader? =
        if (width in 1L..Int.MAX_VALUE.toLong() && height in 1L..Int.MAX_VALUE.toLong()) {
            DesktopWallpaperImageHeader(format, width.toInt(), height.toInt())
        } else null

    if (has(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
        // IHDR is always the first chunk: length, "IHDR", width, height
        return if (bytes.size >= 24 && has(12, 0x49, 0x48, 0x44, 0x52)) {
            header(DesktopWallpaperImageFormat.PNG, be32(16), be32(20))
        } else null
    }
    if (has(0, 0xFF, 0xD8)) {
        var at = 2
        while (at + 4 <= bytes.size) {
            if (u8(at) != 0xFF) return null
            val marker = u8(at + 1)
            if (marker == 0xFF) { at += 1; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { at += 2; continue }
            if (marker == 0xD9 || marker == 0xDA) return null
            val length = be16(at + 2)
            if (length < 2) return null
            // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC): marker, length, precision, height, width
            val frame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (frame) {
                return if (at + 9 <= bytes.size) {
                    header(DesktopWallpaperImageFormat.JPEG, be16(at + 7).toLong(), be16(at + 5).toLong())
                } else null
            }
            at += 2 + length
        }
        return null
    }
    if (has(0, 0x42, 0x4D) && bytes.size >= 26) {
        val dib = le32(14)
        if (dib == 12L) return header(DesktopWallpaperImageFormat.BMP, le16(18).toLong(), le16(20).toLong())
        if (dib >= 40L) {
            val width = le32(18).toInt()
            val height = le32(22).toInt() // negative for a top-down bitmap
            if (height == Int.MIN_VALUE) return null
            return header(DesktopWallpaperImageFormat.BMP, width.toLong(), (if (height < 0) -height else height).toLong())
        }
        return null
    }
    if ((has(0, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || has(0, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) && bytes.size >= 10) {
        return header(DesktopWallpaperImageFormat.GIF, le16(6).toLong(), le16(8).toLong())
    }
    if (has(0, 0x52, 0x49, 0x46, 0x46) && has(8, 0x57, 0x45, 0x42, 0x50) && bytes.size >= 16) {
        if (has(12, 0x56, 0x50, 0x38, 0x58) && bytes.size >= 30) { // VP8X: 24-bit canvas width and height, each minus one
            return header(DesktopWallpaperImageFormat.WEBP, le24(24).toLong() + 1L, le24(27).toLong() + 1L)
        }
        if (has(12, 0x56, 0x50, 0x38, 0x4C) && bytes.size >= 25 && u8(20) == 0x2F) { // VP8L: two 14-bit sizes, each minus one
            val bits = le32(21)
            return header(DesktopWallpaperImageFormat.WEBP, (bits and 0x3FFFL) + 1L, ((bits shr 14) and 0x3FFFL) + 1L)
        }
        if (has(12, 0x56, 0x50, 0x38, 0x20) && bytes.size >= 30 && has(23, 0x9D, 0x01, 0x2A)) { // VP8: two 14-bit sizes
            return header(DesktopWallpaperImageFormat.WEBP, (le16(26) and 0x3FFF).toLong(), (le16(28) and 0x3FFF).toLong())
        }
        return null
    }
    return null
}

/** The original rule: a decodable size and a MIME type that is neither image/gif nor image/webp. */
internal fun desktopWallpaperHeaderIsStatic(header: DesktopWallpaperImageHeader?): Boolean =
    header != null && header.width > 0 && header.height > 0 &&
        header.format != DesktopWallpaperImageFormat.GIF && header.format != DesktopWallpaperImageFormat.WEBP

/** Reads at most [DESKTOP_WALLPAPER_HEADER_LIMIT_BYTES] of [path]; an unreadable file has no header. */
internal fun probeDesktopWallpaperImageHeader(path: Path): DesktopWallpaperImageHeader? =
    runCatching { Files.newInputStream(path).use { it.readNBytes(DESKTOP_WALLPAPER_HEADER_LIMIT_BYTES) } }
        .getOrNull()
        ?.let(::parseDesktopWallpaperImageHeader)

/** The header of the local file a wallpaper URI names; content: and network URIs have none on Windows. */
internal fun probeDesktopWallpaperUri(uri: String): DesktopWallpaperImageHeader? =
    runCatching { desktopHomeWallpaperFile(uri) }.getOrNull()?.let(::probeDesktopWallpaperImageHeader)

/** `.img` is the importer's name for every picked image; content: is the Android document scheme (never a Windows file). */
internal fun desktopWallpaperNeedsHeaderProbe(uri: String): Boolean =
    uri.startsWith("content:") || uri.substringBefore('?').substringAfterLast('.').equals("img", ignoreCase = true)

/** The non-composable decision behind [rememberIsStaticWallpaper]; [probe] is injectable so tests need no file. */
internal fun desktopWallpaperIsStatic(
    uri: String,
    probe: (String) -> DesktopWallpaperImageHeader? = ::probeDesktopWallpaperUri,
): Boolean =
    if (desktopWallpaperNeedsHeaderProbe(uri)) desktopWallpaperHeaderIsStatic(probe(uri)) else isStaticHomeWallpaperUri(uri)

/**
 * Whether [uri] may be admitted to static glass sampling. Starts from the extension rule and corrects it from the file
 * header for `.img` imports, off the main thread, exactly where the Android original reads BitmapFactory bounds.
 */
@Composable
internal fun rememberIsStaticWallpaper(uri: String): Boolean {
    val needsProbe = desktopWallpaperNeedsHeaderProbe(uri)
    val result by produceState(initialValue = !needsProbe && isStaticHomeWallpaperUri(uri), key1 = uri) {
        value = if (!needsProbe) isStaticHomeWallpaperUri(uri) else withContext(Dispatchers.IO) { desktopWallpaperIsStatic(uri) }
    }
    return result
}
