package com.bilipai.desktop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Task-owned selected file handles only. Android's URI resolver is replaced by
 * exact user-selected Paths; existing HTTP images are reused by original edit
 * matching before this reader is called. No URL fetching or account data access.
 */
internal class DesktopDynamicEditorSelectedImages(
    private val stillOwned: () -> Boolean,
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val selected = ConcurrentHashMap<String, Path>()
    private fun owned() {
        if (!active.get() || !stillOwned()) throw CancellationException("Dynamic editor owner retired")
    }
    fun accept(paths: List<Path>): List<String> {
        owned()
        return paths.take(9).map { path ->
            val actual = path.toRealPath()
            require(Files.isRegularFile(actual)) { "请选择图片文件" }
            val uri = actual.toUri().toString()
            owned(); selected[uri] = actual
            uri
        }.also { owned() }
    }
    suspend fun read(source: String): Triple<String?, String?, ByteArray> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive(); owned()
        val path = selected[source] ?: error("该图片不是当前编辑器所选文件")
        val bytes = Files.readAllBytes(path)
        coroutineContext.ensureActive(); owned()
        Triple(path.fileName.toString(), Files.probeContentType(path), bytes)
    }
    override fun close() { active.set(false); selected.clear() }
}
