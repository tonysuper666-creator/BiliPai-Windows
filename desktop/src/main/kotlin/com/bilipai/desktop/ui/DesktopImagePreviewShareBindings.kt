package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.feature.dynamic.components.normalizeImageUrl
import com.android.purebilibili.feature.dynamic.components.resolveImageShareMimeType
import com.android.purebilibili.feature.video.share.VideoShareCoverFile
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path

internal typealias DesktopImagePreviewMediaShare = suspend (
    Path, String, String, () -> Boolean, (Boolean) -> Unit
) -> Boolean

/** Borrows the retained Root's ONE share-file pool, existing anonymous Assets download,
 * and serialized native actor. A successful call means panel shown, never receiver delivery.
 * Native retirement tracks host/route ownership; the normally completed action Job is not
 * a lifetime token for a recipient's file grant. Unknown grants remain bounded by the pool. */
internal class DesktopImagePreviewShareBindings(
    private val readOriginal: suspend (String) -> ByteArray,
    private val files: DesktopVideoShareFiles,
    private val rootOwned: () -> Boolean,
    private val mediaShare: DesktopImagePreviewMediaShare,
) {
    /** Already-saved PNG: borrows the SAME pool/native share actor, without an HTTP request or second capture. */
    internal suspend fun shareCapturedNativeImage(png:ByteArray, title:String, callerOwned:()->Boolean):Boolean = coroutineScope {
        val caller = currentCoroutineContext()[Job] ?: throw CancellationException("Screenshot share needs an actual caller")
        fun owned() = rootOwned() && callerOwned()
        fun checkpoint() { caller.ensureActive(); if (!owned()) throw CancellationException("Screenshot source retired") }
        checkpoint(); files.assertAdmission(); require(png.size in 1..32*1024*1024)
        val bytes = png.copyOf()
        val watcher = launch(Dispatchers.Default) {
            while (isActive) { if (!owned()) { caller.cancel(CancellationException("Screenshot source retired")); break }; delay(40) }
        }
        var file:VideoShareCoverFile? = null; var exposed=false
        try {
            val prepared=files.publish("BiliPai_screenshot_${System.currentTimeMillis()}.png", "image/png", beforeCommit=::checkpoint) { target ->
                checkpoint(); Files.write(target,bytes); checkpoint()
            }
            file=prepared; checkpoint(); files.mayExpose(prepared); exposed=true
            mediaShare(prepared.path,title,"",::owned) { safe -> files.retire(prepared,safe) }.also { checkpoint() }
        } finally { watcher.cancel(); if (!exposed) file?.let { files.retire(it,true) } }
    }
    suspend fun shareImage(rawUrl: String, callerOwned: () -> Boolean): Boolean = coroutineScope {
        val caller = currentCoroutineContext()[Job]
            ?: throw CancellationException("图片分享需要实际预览请求")
        fun owned() = rootOwned() && callerOwned()
        fun checkpoint() { caller.ensureActive(); if (!owned()) throw CancellationException("图片预览所有者已退役") }
        checkpoint()
        // The pool's publication fence also prevents downloading before RootRetainer publication.
        files.assertAdmission()
        val url = normalizeImageUrl(rawUrl)
        if (url.isBlank()) return@coroutineScope false
        val mime = resolveImageShareMimeType(url)
        val extension = when (mime) {
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/png" -> "png"
            else -> "jpg"
        }
        val watcher = launch(Dispatchers.Default) {
            while (isActive) {
                if (!owned()) { caller.cancel(CancellationException("图片预览所有者已退役")); break }
                delay(40)
            }
        }
        var file: VideoShareCoverFile? = null
        var exposed = false
        try {
            val bytes = readOriginal(url)
            checkpoint(); require(bytes.size in 1..32 * 1024 * 1024) { "分享图片过大或为空" }
            val prepared = files.publish("BiliPai_share_${System.currentTimeMillis()}.$extension", mime,
                beforeCommit = ::checkpoint) { target ->
                checkpoint(); Files.write(target, bytes); checkpoint()
            }
            file = prepared
            checkpoint()
            files.mayExpose(prepared)
            exposed = true
            // Native actor owns exact-once retirement even when prepare throws or is cancelled.
            mediaShare(prepared.path, "BiliPai 图片分享", url, ::owned) { safe -> files.retire(prepared, safe) }
                .also { checkpoint() }
        } finally {
            watcher.cancel()
            if (!exposed) file?.let { files.retire(it, true) }
        }
    }
}

internal val LocalDesktopImagePreviewShareBindings = staticCompositionLocalOf<DesktopImagePreviewShareBindings> {
    error("Image preview share requires the actual retained Root owner")
}
