package com.bilipai.desktop.danmaku

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.danmaku.parser.SpecialDanmakuSource
import com.android.purebilibili.data.repository.RemoteSpecialDanmakuSource
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import retrofit2.Retrofit
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Transport is injectable for deterministic cancellation/window tests; production routes stay upstream. */
interface DesktopDanmakuSource {
    /** Local downloads already know their real segment slots and must never request server metadata. */
    val offlineSegmentCount: Int? get() = null
    val offlineSpecialIds: List<String> get() = emptyList()
    suspend fun metadata(cid: Long, aid: Long): ByteArray
    suspend fun segment(cid: Long, index: Int): ByteArray
    suspend fun xml(cid: Long): ByteArray
    suspend fun special(url: String): ByteArray
    /** Small legacy injectables retain their existing contract. Production overrides
     * this with a seekable transport, so playback never fetches a whole special file. */
    suspend fun openSpecial(url: String): SpecialDanmakuSource = DesktopMemorySpecialSource(special(url))
}

class ApiDesktopDanmakuSource(client: OkHttpClient = publicClient()) : DesktopDanmakuSource {
    // Ordinary upstream ResponseBody routes buffer in Retrofit; special range/export
    // declarations use the fixed v029 @Streaming contract. Bound ordinary routes Bound the network source before its converter runs, rather than only after bytes().
    private val originalClient=client
    private val boundedClient = client.newBuilder().addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.body
        if (body.contentLength() > DanmakuParser.MAX_DOCUMENT_BYTES) {
            response.close()
            throw IOException("Danmaku document is too large.")
        }
        response.newBuilder().body(BoundedDanmakuBody(body)).build()
    }.build()
    private val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(boundedClient).build().create(BilibiliApi::class.java)
    override suspend fun metadata(cid: Long, aid: Long) = read { api.getDanmakuView(oid = cid, pid = aid) }
    override suspend fun segment(cid: Long, index: Int): ByteArray {
        require(cid > 0 && index > 0)
        return read { api.getDanmakuSeg(oid = cid, segmentIndex = index) }
    }
    override suspend fun xml(cid: Long) = read { api.getDanmakuXml(cid) }
    override suspend fun special(url: String): ByteArray {
        val normalized = trustedSpecialUrl(url)
        return read { api.getDanmakuSpecialDm(normalized) }
    }
    override suspend fun openSpecial(url:String):SpecialDanmakuSource {
        val normalized=trustedSpecialUrl(url)
        val transportBytes=java.util.concurrent.atomic.AtomicLong()
        // Each source has its own bounded transfer receipt. Up to eight sources
        // therefore have a finite session IO cap, including original 200 discard.
        val rangeClient=originalClient.newBuilder().addNetworkInterceptor {chain->
            trustedSpecialUrl(chain.request().url.toString()) // Reject a redirect before sending to it.
            val response=chain.proceed(chain.request())
            response.newBuilder().body(BoundedDanmakuBody(response.body,
                DesktopSpecialSourceLimits.MAX_TRANSFER_BYTES) {count->
                if(transportBytes.addAndGet(count)>DesktopSpecialSourceLimits.MAX_TRANSFER_BYTES)
                    throw IOException("Special source exceeds its transfer budget")
            }).build()
        }.build()
        val rangeApi=Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(rangeClient).build().create(BilibiliApi::class.java)
        return RemoteSpecialDanmakuSource.open(rangeApi,normalized).also {
            require(it.byteLength in 1..DesktopSpecialSourceLimits.MAX_FILE_BYTES) {"Special source is too large"}
        }
    }
    private suspend fun read(fetch: suspend () -> ResponseBody) = withContext(Dispatchers.IO) {
        fetch().use { body ->
            require(body.contentLength() <= DanmakuParser.MAX_DOCUMENT_BYTES) { "Danmaku document is too large." }
            coroutineScope {
                // A cancelable await can close a slow streaming body immediately. A blocking
                // readNBytes on the calling coroutine would otherwise delay content switches.
                val reading = async(Dispatchers.IO) {
                    body.byteStream().use { it.readNBytes(DanmakuParser.MAX_DOCUMENT_BYTES + 1) }.also {
                        require(it.size <= DanmakuParser.MAX_DOCUMENT_BYTES) { "Danmaku document is too large." }
                    }
                }
                try { reading.await() } finally { body.close() }
            }
        }
    }
    companion object {
        fun publicClient(): OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Referer", "https://www.bilibili.com/")
                    .header("User-Agent", PlaybackSource.DEFAULT_USER_AGENT).build())
            }.build()
        fun trustedSpecialUrl(raw: String): String {
            val normalized = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://") -> "https:" + raw.removePrefix("http:")
                else -> raw
            }
            val uri = URI(normalized)
            val host = uri.host?.lowercase().orEmpty()
            require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
                listOf("bilibili.com", "bilivideo.com", "hdslb.com").any { host == it || host.endsWith(".$it") }) {
                "Untrusted special danmaku address."
            }
            return normalized
        }
    }
}

internal class BoundedDanmakuBody(private val delegate: ResponseBody,
    private val limit:Long=DanmakuParser.MAX_DOCUMENT_BYTES.toLong(),private val charge:(Long)->Unit={}): ResponseBody() {
    private val bounded = object : ForwardingSource(delegate.source()) {
        private var consumed = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = super.read(sink, byteCount.coerceAtMost(limit - consumed + 1))
            if (count > 0) {consumed += count;charge(count)}
            if (consumed > limit) throw IOException("Danmaku document is too large.")
            return count
        }
    }.buffer()
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source(): BufferedSource = bounded
}
