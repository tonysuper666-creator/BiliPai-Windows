package com.bilipai.desktop.danmaku

import com.android.purebilibili.data.repository.resolveDanmakuSegmentCount
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.feature.video.danmaku.buildCommandDanmakuItem
import com.android.purebilibili.feature.video.danmaku.segmentWindowForPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class DanmakuFormat { PROTOBUF, XML }
data class DanmakuWindowResult(
    val document: DanmakuDocument,
    val format: DanmakuFormat,
    val segments: List<Int> = emptyList(),
    val commands: List<CommandDanmakuItem> = emptyList(),
    val warning: String? = null,
)

/** Three-segment playback windows and bounded per-content LRU caching follow the upstream policy. */
class DanmakuWindowLoader(
    private val source: DesktopDanmakuSource,
    private val cid: Long,
    private val aid: Long = 0,
    private val durationMs: Long = 0,
) {
    init { require(cid > 0 && aid >= 0 && durationMs >= 0) }
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Int, ByteArray>(8, 0.75f, true)
    private var cacheBytes = 0L
    private var totalSegments = resolveDanmakuSegmentCount(durationMs, null).coerceIn(1, 10_000)
    private var metadataAvailable = false
    private var activeSegments = emptyList<Int>()
    private var xmlFallback: DanmakuDocument? = null
    private var special = DanmakuDocument()
    private var commands = emptyList<CommandDanmakuItem>()
    private var metadataWarning: String? = null

    internal fun cachedBytes(): Long = synchronized(cache) {cacheBytes}

    fun windowForPosition(positionMs: Long) = segmentWindowForPosition(positionMs, totalSegments)

    suspend fun initial(positionMs: Long = 0): DanmakuWindowResult = mutex.withLock {
        source.offlineSegmentCount?.let { count ->
            totalSegments = count.coerceIn(1, 10_000)
            metadataAvailable = true
            special = loadSpecial(source.offlineSpecialIds)
            return@withLock loadWindow(positionMs)
        }
        val metadata = try {
            val bytes = source.metadata(cid, aid)
            requireProtocolBytes(bytes)
            bytes.takeIf { it.isNotEmpty() }?.let(DanmakuProto::parseWebViewReply)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            metadataWarning = "弹幕元数据不可用，已尝试兼容格式。"
            null
        }
        metadataAvailable = metadata != null
        totalSegments = resolveDanmakuSegmentCount(durationMs, metadata?.dmSge?.total?.toInt()).coerceIn(1, 10_000)
        commands = metadata?.commandDms.orEmpty().take(500).mapNotNull(::buildCommandDanmakuItem)
        special = loadSpecial(metadata?.specialDms.orEmpty())
        loadWindow(positionMs)
    }

    suspend fun move(positionMs: Long): DanmakuWindowResult? = mutex.withLock {
        if (windowForPosition(positionMs) == activeSegments || !metadataAvailable && xmlFallback != null) return@withLock null
        loadWindow(positionMs)
    }

    private suspend fun loadWindow(positionMs: Long): DanmakuWindowResult {
        val indices = windowForPosition(positionMs)
        val results = coroutineScope {
            indices.map { index -> async {
                val cached = synchronized(cache) { cache[index] }
                if (cached != null) return@async index to Result.success(cached)
                val bytes = try {
                    source.segment(cid, index).also(::requireProtocolBytes).let { Result.success(it) }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    Result.failure(failure)
                }
                bytes.getOrNull()?.let { store(index, it) }
                index to bytes
            } }.awaitAll()
        }
        val available = results.mapNotNull { it.second.getOrNull() }
        if (available.isNotEmpty()) {
            val document = DanmakuParser.parseProtobuf(available)
            activeSegments = indices
            val partial = if (available.size < indices.size) "部分弹幕分段未加载（${available.size}/${indices.size}），已显示可用内容。" else null
            return DanmakuWindowResult(combine(document, special), DanmakuFormat.PROTOBUF, indices, commands,
                partial ?: metadataWarning)
        }
        val xml = xmlFallback ?: try {
            DanmakuParser.parseDocument(source.xml(cid).inputStream()).also { xmlFallback = it }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            throw IllegalStateException("分段与兼容弹幕均加载失败：${failure.message ?: "network error"}", failure)
        }
        activeSegments = indices
        return DanmakuWindowResult(combine(xml, special), DanmakuFormat.XML, emptyList(), commands,
            "当前使用 XML 兼容弹幕，分段接口暂不可用。")
    }

    private suspend fun loadSpecial(urls: List<String>): DanmakuDocument = coroutineScope {
        val parallel = Semaphore(2)
        val parts = urls.distinct().take(8).map { url -> async {
            parallel.withPermit {
                try {
                    val bytes = source.special(url)
                    require(bytes.size <= 2 * 1024 * 1024) { "Special danmaku is too large." }
                    if (bytes.take(64).toByteArray().toString(Charsets.UTF_8).trimStart().startsWith("<"))
                        DanmakuParser.parseDocument(bytes.inputStream()) else DanmakuParser.parseProtobuf(listOf(bytes))
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    metadataWarning = "部分高级弹幕暂未加载。"
                    DanmakuDocument()
                }
            }
        } }.awaitAll()
        parts.fold(DanmakuDocument(), ::combine)
    }

    private fun store(index: Int, bytes: ByteArray) = synchronized(cache) {
        if (bytes.size > MAX_CACHE_BYTES) return@synchronized
        cache.remove(index)?.let { cacheBytes -= it.size }
        while (cache.isNotEmpty() && (cache.size >= 9 || cacheBytes + bytes.size > MAX_CACHE_BYTES)) {
            val iterator = cache.entries.iterator()
            val eldest = iterator.next()
            cacheBytes -= eldest.value.size
            iterator.remove()
        }
        cache[index] = bytes
        cacheBytes += bytes.size
    }

    private fun combine(first: DanmakuDocument, second: DanmakuDocument): DanmakuDocument {
        val comments = (first.comments + second.comments)
            .distinctBy { if (it.serverId > 0) "id:${it.serverId}" else "${it.timeSeconds}:${it.mode}:${it.color}:${it.text}" }
            .sortedWith(compareBy<DanmakuComment> { it.timeSeconds }.thenBy { it.id })
            .take(25_000).mapIndexed { index, comment -> comment.copy(id = index) }
        return DanmakuDocument(comments, (first.advanced + second.advanced).distinctBy { it.id }.sortedBy { it.startTimeMs }.take(5_000),
            first.serverDisabled || second.serverDisabled)
    }

    private fun requireProtocolBytes(bytes: ByteArray) {
        require(bytes.size <= 4 * 1024 * 1024) { "Danmaku segment is too large." }
        require(bytes.firstOrNull() != '{'.code.toByte() && bytes.firstOrNull() != '['.code.toByte()) {
            "Danmaku endpoint returned an API error instead of protobuf."
        }
    }

    companion object { private const val MAX_CACHE_BYTES = 16 * 1024 * 1024 }
}
