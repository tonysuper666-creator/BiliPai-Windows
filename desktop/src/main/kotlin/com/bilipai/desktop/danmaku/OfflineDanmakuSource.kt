package com.bilipai.desktop.danmaku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import com.android.purebilibili.danmaku.parser.SpecialDanmakuSource
import com.android.purebilibili.data.repository.LocalSpecialDanmakuSource

/** List positions are the original one-based segment slots; a missing file stays in its slot. */
class OfflineDanmakuSource(
    standardSegments: List<Path>,
    specialSegments: List<Path> = emptyList(),
) : DesktopDanmakuSource {
    private val standard = standardSegments.map { it.toAbsolutePath().normalize() }
    private val special = specialSegments.map { it.toAbsolutePath().normalize() }
    init {
        require(standard.size <= 10_000) { "Too many offline danmaku segments." }
        require(special.size <= 8) { "Too many offline special danmaku segments." }
    }
    override val offlineSegmentCount: Int = standard.size.coerceAtLeast(1)
    override val offlineSpecialIds = special.indices.map { "offline-special:$it" }

    override suspend fun metadata(cid: Long, aid: Long): ByteArray =
        error("Offline playback does not request danmaku metadata.")

    override suspend fun segment(cid: Long, index: Int): ByteArray {
        require(index > 0)
        // A special-only download still has one valid empty standard slot.
        if (standard.isEmpty() && index == 1) return ByteArray(0)
        val file = standard.getOrNull(index - 1) ?: error("离线弹幕第 $index 段未下载。")
        return read(file, 4 * 1024 * 1024)
    }

    override suspend fun xml(cid: Long): ByteArray = error("离线弹幕分段不可用，请检查下载文件。")

    override suspend fun special(url: String): ByteArray {
        val index = offlineSpecialIds.indexOf(url)
        require(index >= 0) { "Unknown offline special danmaku asset." }
        return read(special[index], 2 * 1024 * 1024)
    }

    override suspend fun openSpecial(url:String):SpecialDanmakuSource = withContext(Dispatchers.IO) {
        val index=offlineSpecialIds.indexOf(url)
        require(index>=0) {"Unknown offline special danmaku asset"}
        val path=special[index]
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {"Offline special asset is unavailable"}
        require(Files.size(path)<=DesktopSpecialSourceLimits.MAX_FILE_BYTES) {"Offline special asset is too large"}
        // The sole download manager already validated this path against its task directory.
        val original=LocalSpecialDanmakuSource(path.toFile())
        object:SpecialDanmakuSource by original,DesktopLegacySpecialXmlSource {}
    }

    private suspend fun read(file: Path, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        require(Files.isRegularFile(file)) { "离线弹幕文件不存在：${file.fileName}" }
        require(Files.size(file) <= limit) { "Offline danmaku segment is too large." }
        Files.newInputStream(file).use { stream ->
            stream.readNBytes(limit + 1).also { require(it.size <= limit) { "Offline danmaku segment is too large." } }
        }
    }
}
