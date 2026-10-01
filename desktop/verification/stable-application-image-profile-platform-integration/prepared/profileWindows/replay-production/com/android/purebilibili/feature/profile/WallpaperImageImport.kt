package com.android.purebilibili.feature.profile

import com.bilipai.desktop.ui.DesktopProfileWallpaperImportContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copy while the picker grant is valid; previews and saved wallpapers then use our own file. */
internal suspend fun importWallpaperImage(
    context: DesktopProfileWallpaperImportContext,
    source: String,
    destinationDirectory: File,
): File {
    var imported: File? = null
    try {
        return withContext(Dispatchers.IO) {
            val copied = copyWallpaperImage(
                context = context,
                destinationDirectory = destinationDirectory,
                openSource = { context.openSource(source) },
                validateImage = { candidate ->
                    context.validateImage(candidate)
                },
            )
            val file = context.publishImport(copied)
            imported = file
            context.ensureCurrent()
            ensureActive()
            file
        }
    } catch (error: Throwable) {
        // Also handles cancellation during the dispatch back to the UI thread.
        imported?.let(context::deleteImport)
        throw error
    }
}

internal fun copyWallpaperImage(
    context: DesktopProfileWallpaperImportContext,
    destinationDirectory: File,
    openSource: () -> InputStream?,
    validateImage: (File) -> Boolean,
): File {
    if (!destinationDirectory.isDirectory && !context.prepareDirectory(destinationDirectory)) {
        throw IOException("无法创建壁纸目录")
    }
    val destination = context.createImportFile(destinationDirectory, ".img")
    try {
        val source = openSource() ?: throw IOException("无法读取所选图片，请重新从相册选择")
        source.use { input ->
            context.openImportOutput(destination).use { output -> input.copyTo(output) }
        }
        if (destination.length() == 0L || !validateImage(destination)) {
            throw IOException("图片为空、已损坏或格式不受支持，请选择其他图片")
        }
        return destination
    } catch (error: Throwable) {
        context.deleteImport(destination)
        throw error
    }
}


/** Preserve original GIF bytes and video format; validate off the main thread. */
internal suspend fun importWallpaperMedia(
    context: DesktopProfileWallpaperImportContext,
    source: String,
    destinationDirectory: File,
): File {
    val video = withContext(Dispatchers.IO) {
        context.mimeType(source)?.startsWith("video/") == true ||
            com.android.purebilibili.core.ui.wallpaper.isVideoWallpaper(source.toString())
    }
    if (!video) return importWallpaperImage(context, source, destinationDirectory)
    var imported: File? = null
    try {
        return withContext(Dispatchers.IO) {
            if (!destinationDirectory.isDirectory && !context.prepareDirectory(destinationDirectory)) {
                throw IOException("无法创建壁纸目录")
            }
            val extension = when(context.mimeType(source)) {
                "video/mp4" -> "mp4"; "video/x-m4v" -> "m4v"; "video/webm" -> "webm"
                "video/x-matroska" -> "mkv"; "video/quicktime" -> "mov"; "video/3gpp" -> "3gp"; else -> null
            }
                ?.takeIf { com.android.purebilibili.core.ui.wallpaper.isVideoWallpaper("wallpaper.$it") }
                ?: context.lastPathSegment(source)?.substringAfterLast('.', "")?.takeIf {
                    it.lowercase() in setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp")
                } ?: "video"
            val file = context.createImportFile(destinationDirectory, ".$extension")
            imported = file
            val input = context.openSource(source)
                ?: throw IOException("无法读取所选视频")
            input.use { stream -> context.openImportOutput(file).use { stream.copyTo(it) } }
            val width = context.videoWidth(file)
            if (width <= 0) throw IOException("视频损坏或格式不受支持")
            val published = context.publishImport(file)
            imported = published
            context.ensureCurrent()
            ensureActive()
            published
        }
    } catch (error: Throwable) {
        imported?.let(context::deleteImport)
        throw error
    }
}
