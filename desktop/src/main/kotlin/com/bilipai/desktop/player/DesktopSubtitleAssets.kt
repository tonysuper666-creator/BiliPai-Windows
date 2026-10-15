package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.android.purebilibili.feature.video.subtitle.isTrustedBilibiliSubtitleUrl
import com.android.purebilibili.feature.video.subtitle.normalizeBilibiliSubtitleUrl
import com.android.purebilibili.feature.video.subtitle.parseBiliSubtitleBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CacheControl
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native paths survive HWND rebuild while their player owns them; inactive documents have bounded caches. */
class DesktopSubtitleAssets private constructor(
    private val client: OkHttpClient,
    private val limits: Limits,
    private val retainedNativePaths: () -> Set<Path>,
    private val deleteFile: (Path) -> Unit,
) : AutoCloseable {
    constructor(client: OkHttpClient, retainedNativePaths: () -> Set<Path> = { emptySet() }) :
        this(client, Limits(512, 32L * 1024 * 1024, 64L * 1024 * 1024), retainedNativePaths, { Files.deleteIfExists(it) })

    internal constructor(client: OkHttpClient, maximumDocuments: Int, maximumCueBytes: Long,
        maximumFileBytes: Long, retainedNativePaths: () -> Set<Path> = { emptySet() },
        deleteFile: (Path) -> Unit = { Files.deleteIfExists(it) }) :
        this(client, Limits(maximumDocuments, maximumCueBytes, maximumFileBytes), retainedNativePaths, deleteFile)

    private data class Limits(val documents: Int, val cueBytes: Long, val fileBytes: Long) {
        init { require(documents in 1..10_000 && cueBytes > 0 && fileBytes in 1..Long.MAX_VALUE / 2) }
    }
    private val directory = Files.createTempDirectory("bilipai-subtitles-")
    // The same monitor protects import publication, leases and both cleanup inventories.
    private val files = linkedSetOf<Path>()
    private val pendingDeletes = linkedSetOf<Path>()
    private val fileSizes = mutableMapOf<Path, Long>()
    private val fileCues = LinkedHashMap<Path, List<SubtitleCue>>(16, 0.75f, true)
    private val cueSizes = mutableMapOf<Path, Long>()
    private val held = mutableMapOf<Path, Int>()
    private val importing = mutableMapOf<Path, Int>()
    private val documents = mutableSetOf<Documents>()
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private var activeImports = 0

    /** Created before the IO dispatcher hop, so cancellation cannot discard an unowned returned lease. */
    internal fun openDocuments(): Documents = synchronized(files) {
        check(!closed) { "字幕缓存已关闭" }
        Documents().also { documents.add(it) }
    }

    internal inner class Documents internal constructor() : AutoCloseable {
        private var retired = false
        private val paths = mutableSetOf<Path>()
        internal suspend fun import(track: SubtitleTrackMeta, ownedCalls: Call.Factory = client): Path =
            importOwned(track, ownedCalls, this)
        internal fun cues(file: Path): List<SubtitleCue> = synchronized(files) {
            checkCurrentLocked()
            check(file in paths) { "字幕文档不属于当前导入会话" }
            cuesLocked(file)
        }
        internal fun checkCurrentLocked() {
            check(!closed && !retired && this in documents) { "字幕导入会话已关闭" }
        }
        internal fun retainLocked(file: Path) {
            checkCurrentLocked()
            if (paths.add(file)) held[file] = (held[file] ?: 0) + 1
        }
        internal fun retireLocked() {
            if (retired) return
            retired = true
            paths.forEach { file ->
                val remaining = (held[file] ?: 0) - 1
                if (remaining <= 0) held.remove(file) else held[file] = remaining
            }
            paths.clear()
            documents.remove(this)
        }
        override fun close() = synchronized(files) {
            if (retired) return@synchronized
            retireLocked()
            if (!closed) pruneLocked()
        }
    }

    /** Compatibility entry point; actual import/read/install callers use openDocuments(). */
    suspend fun import(track: SubtitleTrackMeta): Path = import(track, client)
    internal suspend fun import(track: SubtitleTrackMeta, ownedCalls: Call.Factory): Path =
        openDocuments().use { it.import(track, ownedCalls) }

    private suspend fun importOwned(track: SubtitleTrackMeta, ownedCalls: Call.Factory, owner: Documents): Path =
        withContext(Dispatchers.IO) {
            require(isTrustedBilibiliSubtitleUrl(track.subtitleUrl)) { "这条字幕地址暂不支持" }
            val url = normalizeBilibiliSubtitleUrl(track.subtitleUrl)
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = directory.resolve("$digest.srt")
            synchronized(files) {
                owner.checkCurrentLocked()
                activeImports++
                importing[file] = (importing[file] ?: 0) + 1
            }
            try {
                synchronized(files) {
                    owner.checkCurrentLocked()
                    if (Files.isRegularFile(file) && fileCues.containsKey(file)) {
                        owner.retainLocked(file)
                        touchLocked(file)
                        return@withContext file
                    }
                }
                val cachedOriginal = synchronized(files) {
                    owner.checkCurrentLocked()
                    val original = originalDocument(file)
                    if (file in fileSizes && Files.isRegularFile(file) && Files.isRegularFile(original)) {
                        require(Files.size(original) <= MAX_BYTES) { "字幕文件过大" }
                        Files.readString(original)
                    } else null
                }
                val call = if (cachedOriginal == null) ownedCalls.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).get()
                    .header("Referer", "https://www.bilibili.com").header("Cache-Control", "no-cache").header("Pragma", "no-cache").build()) else null
                synchronized(files) { owner.checkCurrentLocked(); if (call != null) calls.add(call) }
                try {
                    val text = cachedOriginal ?: download(requireNotNull(call))
                    ensureActive()
                    val document = BiliSubtitleDocument.toSrt(text)
                    require(document.isNotBlank()) { "这条字幕没有可显示的内容" }
                    val cues = parseBiliSubtitleBody(text)
                    synchronized(files) {
                        owner.checkCurrentLocked()
                        ensureActive()
                        if (Files.isRegularFile(file) && fileCues.containsKey(file)) {
                            owner.retainLocked(file)
                            touchLocked(file)
                            return@withContext file
                        }
                        if (Files.isRegularFile(file) && !Files.isRegularFile(originalDocument(file))) {
                            throw IOException("字幕缓存原文缺失，请在停止播放后清理缓存并重试。")
                        }
                        // A missing parsed document must reload the original JSON, including AI word spans.
                        // An existing native SRT remains in place while libmpv might have it open.
                        if (!Files.isRegularFile(file)) {
                            val encoded = document.toByteArray(Charsets.UTF_8)
                            val originalEncoded = text.toByteArray(Charsets.UTF_8)
                            makeRoomLocked(file, encoded.size.toLong() + originalEncoded.size)
                            val temporary = Files.createTempFile(directory, ".subtitle-", ".tmp")
                            var temporaryOriginal: Path? = null
                            var committed = false
                            var originalCommitted = false
                            try {
                                val originalStage = Files.createTempFile(directory, ".subtitle-original-", ".tmp")
                                temporaryOriginal = originalStage
                                Files.write(temporary, encoded)
                                Files.write(originalStage, originalEncoded)
                                ensureActive()
                                Files.move(originalStage, originalDocument(file), StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING)
                                originalCommitted = true
                                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
                                committed = true
                                ensureActive()
                            } catch (failure: Throwable) {
                                if (committed) Files.deleteIfExists(file)
                                if (originalCommitted) Files.deleteIfExists(originalDocument(file))
                                throw failure
                            } finally {
                                Files.deleteIfExists(temporary)
                                temporaryOriginal?.let { Files.deleteIfExists(it) }
                            }
                        }
                        fileSizes[file] = physicalDocumentBytes(file)
                        pendingDeletes.remove(file)
                        touchLocked(file)
                        fileCues[file] = cues
                        cueSizes[file] = estimateCueBytes(cues)
                        owner.retainLocked(file)
                        pruneLocked()
                    }
                    file
                } finally { synchronized(files) { calls.remove(call) } }
            } finally {
                synchronized(files) {
                    activeImports--
                    val remaining = (importing[file] ?: 0) - 1
                    if (remaining <= 0) importing.remove(file) else importing[file] = remaining
                    if (!closed) pruneLocked()
                }
            }
        }

    internal fun cues(file: Path): List<SubtitleCue> = synchronized(files) { cuesLocked(file) }
    private fun cuesLocked(file: Path): List<SubtitleCue> {
        check(!closed) { "字幕缓存已关闭" }
        return fileCues[file]?.toList() ?: error("字幕文档不在当前缓存中")
    }
    private fun touchLocked(file: Path) { files.remove(file); files.add(file); fileCues[file] }
    private fun originalDocument(file: Path): Path = file.resolveSibling(file.fileName.toString().removeSuffix(".srt") + ".json")
    private fun physicalDocumentBytes(file: Path): Long = listOf(file, originalDocument(file)).sumOf {
        if (Files.isRegularFile(it)) Files.size(it) else 0L
    }
    private fun deleteDocumentFiles(file: Path) {
        // Delete the native path first. If Windows denies it, its original cue document stays available as well.
        deleteFile(file)
        deleteFile(originalDocument(file))
    }
    private fun protectedLocked(file: Path, native: Set<Path>): Boolean =
        file in native || (held[file] ?: 0) > 0 || (importing[file] ?: 0) > 0

    private fun forgetCuesLocked(file: Path) { fileCues.remove(file); cueSizes.remove(file) }
    private fun deleteRetiredLocked(file: Path) {
        files.remove(file)
        forgetCuesLocked(file)
        try {
            deleteDocumentFiles(file)
            pendingDeletes.remove(file)
            fileSizes.remove(file)
        } catch (_: IOException) { pendingDeletes.add(file) }
    }
    private fun pruneLocked() {
        // Native ownership only protects the file. Cue objects are needed by active import owners.
        val cueCandidates = fileCues.keys.filter { (held[it] ?: 0) == 0 }
        for (file in cueCandidates) {
            if (fileCues.size <= limits.documents && cueSizes.values.sum() <= limits.cueBytes) break
            forgetCuesLocked(file)
        }
        // This callback is a read-only player-lock snapshot. Player code never calls this cache while locked.
        val native = retainedNativePaths()
        pendingDeletes.filterNot { protectedLocked(it, native) }.take(32).forEach(::deleteRetiredLocked)
        val inactive = files.filterNot { protectedLocked(it, native) }.toMutableList()
        var inactiveBytes = inactive.sumOf { fileSizes[it] ?: 0L }
        var inactiveCount = inactive.size
        for (file in inactive) {
            if (inactiveCount <= limits.documents && inactiveBytes <= limits.fileBytes) break
            inactiveCount--
            inactiveBytes -= fileSizes[file] ?: 0L
            // A failed deletion leaves the active LRU and stays in a separate retry inventory.
            // It must not make this loop advance into a newly held import to compensate.
            deleteRetiredLocked(file)
        }
    }

    /** Failed deletions have a finite overflow allowance; ownership is preserved if that allowance fills. */
    private fun makeRoomLocked(incoming: Path, incomingBytes: Long) {
        pruneLocked()
        val native = retainedNativePaths()
        fun ownedOutsideActive(): List<Path> = fileSizes.keys.filter {
            it != incoming && !protectedLocked(it, native)
        }
        fun fits(): Boolean {
            val inactive = ownedOutsideActive()
            return inactive.size + 1 <= limits.documents * 2 &&
                inactive.sumOf { fileSizes[it] ?: 0L } + incomingBytes <= limits.fileBytes * 2
        }
        for (candidate in files.filter { it != incoming && !protectedLocked(it, native) }) {
            if (fits()) break
            deleteRetiredLocked(candidate)
        }
        if (!fits()) throw IOException("临时字幕缓存无法清理，请关闭占用字幕文件的程序后重试。")
    }

    private suspend fun download(call: Call): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) { continuation.resumeWithException(failure) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        check(response.isSuccessful) { "字幕下载失败 (HTTP ${response.code})" }
                        val body = response.body ?: error("字幕内容为空")
                        require(body.contentLength() <= MAX_BYTES) { "字幕文件过大" }
                        body.byteStream().use { input ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(bytes.size() + count <= MAX_BYTES) { "字幕文件过大" }
                                bytes.write(buffer, 0, count)
                            }
                            bytes.toString(Charsets.UTF_8)
                        }
                    }
                    continuation.resume(text)
                } catch (failure: Throwable) { continuation.resumeWithException(failure) }
            }
        })
    }

    private fun estimateCueBytes(cues: List<SubtitleCue>): Long = cues.sumOf { cue ->
        cue.content.length * 2L + cue.words.sumOf { word -> word.text.length * 2L + 24L } + 32L
    }
    internal fun cacheSizes(): Pair<Long, Long> = synchronized(files) {
        fileSizes.keys.sumOf(::physicalDocumentBytes) to cueSizes.values.sum()
    }
    internal fun retainedDocumentCount(): Int = synchronized(files) { fileSizes.size }
    internal fun cachedCueDocumentCount(): Int = synchronized(files) { fileCues.size }
    internal fun activeDocumentOwners(): Int = synchronized(files) { documents.size }

    /** Caller holds the real native idle-maintenance lease; pending import owners also exclude deletion. */
    internal fun clearIdle(checkRequest: () -> Unit) = synchronized(files) {
        checkRequest(); check(!closed)
        require(calls.isEmpty() && activeImports == 0 && documents.isEmpty()) { "字幕仍在加载或提交，请稍后重试" }
        val ownedFiles = fileSizes.keys.toList()
        com.bilipai.desktop.update.UpdateStorage.existingPathWithoutLinks(directory)
        ownedFiles.flatMap { listOf(it, originalDocument(it)) }.forEach { path ->
            require(path.toAbsolutePath().normalize().parent == directory.toAbsolutePath().normalize())
            if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                com.bilipai.desktop.update.UpdateStorage.existingPathWithoutLinks(path)
                require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            }
        }
        ownedFiles.forEach { path ->
            checkRequest(); deleteDocumentFiles(path)
            files.remove(path); pendingDeletes.remove(path); fileSizes.remove(path); forgetCuesLocked(path)
        }
    }
    override fun close() {
        val pending = synchronized(files) {
            if (closed) return
            closed = true
            val active = calls.toList()
            calls.clear()
            documents.toList().forEach { it.retireLocked() }
            fileSizes.keys.toList().forEach { runCatching { deleteDocumentFiles(it) } }
            files.clear(); pendingDeletes.clear(); fileSizes.clear(); fileCues.clear(); cueSizes.clear(); held.clear()
            runCatching { Files.deleteIfExists(directory) }
            active
        }
        pending.forEach(Call::cancel)
    }
    private companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
