package com.bilipai.desktop.ui
import kotlinx.coroutines.*
/** Original raw XML/deflate transform over the SAME actual invocation API.
 * The raw Overlay/cache authority remains existing; no new client/CID cache. */
internal suspend fun desktopOriginalBangumiRawDanmaku(binding: DesktopOriginalVideoRepositoryBinding,
    cid: Long, assertPresenter: () -> Unit): ByteArray? = withContext(Dispatchers.IO) {
    ensureActive(); binding.assertCurrent(); assertPresenter()
    try {
        val bytes = binding.primaryApi.getDanmakuXml(cid).use { body ->
            require(body.contentLength() <= com.bilipai.desktop.danmaku.DanmakuParser.MAX_DOCUMENT_BYTES)
            body.byteStream().use { it.readNBytes(com.bilipai.desktop.danmaku.DanmakuParser.MAX_DOCUMENT_BYTES + 1) }
                .also { require(it.size <= com.bilipai.desktop.danmaku.DanmakuParser.MAX_DOCUMENT_BYTES) }
        }
        ensureActive(); binding.assertCurrent(); assertPresenter()
        if (bytes.isEmpty()) return@withContext null
            val result: ByteArray?
            
            // 检查首字节判断是否压缩
            // XML 以 '<' 开头 (0x3C)
            if (bytes[0] == 0x3C.toByte()) {
                com.android.purebilibili.core.util.Logger.d("DanmakuRepo", " Danmaku is plain XML, size=${bytes.size}")
                result = bytes
            } else {
                // 尝试 Deflate 解压
                com.android.purebilibili.core.util.Logger.d("DanmakuRepo", " Danmaku appears compressed, attempting deflate...")
                result = try {
                    val inflater = java.util.zip.Inflater(true) // nowrap=true
                    inflater.setInput(bytes)
                    val outputStream = java.io.ByteArrayOutputStream(bytes.size * 3)
                    val tempBuffer = ByteArray(1024)
                    while (!inflater.finished()) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = inflater.inflate(tempBuffer)
                        if (count == 0) {
                             if (inflater.needsInput()) break
                             if (inflater.needsDictionary()) break
                        }
                        require(outputStream.size() + count <= com.bilipai.desktop.danmaku.DanmakuParser.MAX_DOCUMENT_BYTES) { "Danmaku document too large" }
                    outputStream.write(tempBuffer, 0, count)
                    }
                    inflater.end()
                    val decompressed = outputStream.toByteArray()
                    com.android.purebilibili.core.util.Logger.d("DanmakuRepo", " Danmaku decompressed: ${bytes.size} → ${decompressed.size} bytes")
                    decompressed
                } catch (e: Exception) {
                    android.util.Log.e("DanmakuRepo", " Deflate failed: ${e.message}")
                    e.printStackTrace()
                    // 解压失败，返回原始数据
                    bytes
                }
            }
            

        ensureActive(); binding.assertCurrent(); assertPresenter()
        result
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        ensureActive(); binding.assertCurrent(); assertPresenter()
        android.util.Log.e("DanmakuRepo", "getDanmakuRawData failed: ${failure.message}")
        null
    }
}
