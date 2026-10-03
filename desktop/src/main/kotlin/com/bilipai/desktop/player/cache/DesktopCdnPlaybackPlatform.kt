package com.android.purebilibili.feature.plugin

import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.Collections

/** Windows byte-span loader boundary. Finite interval values retain the original Media3
 * business contract; no Android namespace/type stub or alternate media engine is present. */
internal const val CDN_LENGTH_UNSET = -1L
internal const val CDN_RESULT_END_OF_INPUT = -1
internal class CdnMediaDataSpec(
    val uri: URI,
    val position: Long,
    val length: Long,
    val httpMethod: Int = HTTP_METHOD_GET,
    httpBody: ByteArray? = null,
    httpRequestHeaders: Map<String, String> = emptyMap(),
) {
    val httpBody = httpBody?.clone()
    val httpRequestHeaders = Collections.unmodifiableMap(LinkedHashMap(httpRequestHeaders))
    init {
        require(position >= 0 && (length == CDN_LENGTH_UNSET || length > 0))
        require(length == CDN_LENGTH_UNSET || length - 1 <= Long.MAX_VALUE - position)
    }
    fun subrange(offset: Long): CdnMediaDataSpec {
        require(offset >= 0 && (length == CDN_LENGTH_UNSET || offset < length))
        require(offset <= Long.MAX_VALUE-position)
        return CdnMediaDataSpec(uri, position+offset,
            if (length == CDN_LENGTH_UNSET) CDN_LENGTH_UNSET else length-offset,
            httpMethod, httpBody, httpRequestHeaders)
    }
    companion object { const val HTTP_METHOD_GET = 1 }
}
internal interface CdnMediaDataSource : AutoCloseable {
    fun open(dataSpec: CdnMediaDataSpec): Long
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun getUri(): URI?
    fun getResponseHeaders(): Map<String, List<String>>
    override fun close()
}
internal interface CdnPlaybackPlatform {
    fun assertCurrent()
    fun isWifi(): Boolean
    fun networkIdentity(): String
    fun authorizedCandidates(urls: List<String>): List<String>
    fun assertParallelUrl(url: String)
    fun prepareRequest(value: Request): Request
    fun validateResponse(value: Response)
    fun bytesTransferred(count: Int)
}
