package com.bilipai.desktop.ui

import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.CoroutineContext

/** Windows mapping of stable ContentUriRequestBody's known-size, one-shot path.
 * Only exact editor-selected Paths are admitted. Existing Operations supplies
 * the actual SessionStore owner gate; this owner retires its open input streams.
 */
internal class DesktopDynamicEditorSelectedImages(
    private val stillOwned: () -> Boolean,
    private val withOwnedAdmission: ((() -> Unit) -> Boolean),
) : AutoCloseable {
    private val lock = Any()
    private var active = true
    private val selected = mutableMapOf<String, Path>()
    private val streams = mutableSetOf<InputStream>()
    private fun owned() {
        if (!active || !stillOwned()) throw CancellationException("Dynamic editor owner retired")
    }
    private fun admit(context: CoroutineContext? = null, block: () -> Unit = {}) {
        val admitted = withOwnedAdmission {
            synchronized(lock) { context?.ensureActive(); owned(); block() }
        }
        if (!admitted) throw CancellationException("Dynamic editor account owner retired")
    }
    fun accept(paths: List<Path>): List<String> {
        val result = mutableListOf<String>()
        for (path in paths.take(9)) {
            admit()
            val actual = path.toRealPath()
            UpdateStorage.existingPathWithoutLinks(actual)
            require(Files.isRegularFile(actual, NOFOLLOW_LINKS)) { "请选择图片文件" }
            val uri = actual.toUri().toString()
            admit { selected[uri] = actual; result += uri }
        }
        admit()
        return result
    }
    suspend fun read(source: String): Triple<String?, String?, RequestBody> {
        // Capture the caller's publishing/editing job before the short IO child
        // finishes. The HTTP writer later executes on an OkHttp thread.
        val operationContext = currentCoroutineContext()
        return withContext(Dispatchers.IO) {
            var path: Path? = null
            admit(operationContext) { path = selected[source] ?: error("该图片不是当前编辑器所选文件") }
            val actual = checkNotNull(path)
            var size = 0L
            admit(operationContext) {
                UpdateStorage.existingPathWithoutLinks(actual)
                val attributes = Files.readAttributes(actual, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attributes.isRegularFile) { "请选择图片文件" }
                size = attributes.size()
                // Exact stable known-size branch of CommentRepository.uploadCommentImage.
                if (size <= 0L) throw Exception("图片内容为空")
                if (size > MAX_COMMENT_IMAGE_BYTES) throw Exception(IMAGE_TOO_LARGE_MESSAGE)
            }
            val mimeType = Files.probeContentType(actual)
            val mediaType = (mimeType ?: "image/jpeg").toMediaType()
            admit(operationContext)
            Triple(actual.fileName.toString(), mimeType, SelectedPathRequestBody(source, actual, mediaType, size, operationContext))
        }
    }
    private inner class SelectedPathRequestBody(
        private val source: String,
        private val path: Path,
        private val mediaType: MediaType,
        private val contentLength: Long,
        private val operationContext: CoroutineContext,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = contentLength
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            var opened: InputStream? = null
            admit(operationContext) {
                check(selected[source] == path) { "该图片不是当前编辑器所选文件" }
                UpdateStorage.existingPathWithoutLinks(path)
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attributes.isRegularFile && attributes.size() == contentLength) { "图片文件已改变，请重新选择" }
                opened = Files.newInputStream(path, READ, NOFOLLOW_LINKS).also { streams += it }
            }
            val input = checkNotNull(opened)
            try {
                input.use {
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        var count = -1
                        admit(operationContext) { count = it.read(buffer) }
                        if (count < 0) break
                        copied += count
                        require(copied <= contentLength && copied <= MAX_COMMENT_IMAGE_BYTES) { "图片文件已改变，请重新选择" }
                        // One bounded slice is consumed under SessionStore ->
                        // selected-owner ordering. Retirement admits no next slice.
                        admit(operationContext) { sink.write(buffer, 0, count) }
                    }
                    admit(operationContext) { require(copied == contentLength) { "图片文件已改变，请重新选择" } }
                }
            } finally { synchronized(lock) { streams.remove(input) } }
        }
    }
    override fun close() {
        val pending = synchronized(lock) {
            if (!active) return
            active = false; selected.clear(); streams.toList().also { streams.clear() }
        }
        // Cleanup holds neither the selected-owner nor SessionStore monitor.
        pending.forEach { runCatching { it.close() } }
    }
    private companion object {
        const val MAX_COMMENT_IMAGE_BYTES = 15L * 1024 * 1024
        const val IMAGE_TOO_LARGE_MESSAGE = "图片过大（单张最大 15MB）"
    }
}
