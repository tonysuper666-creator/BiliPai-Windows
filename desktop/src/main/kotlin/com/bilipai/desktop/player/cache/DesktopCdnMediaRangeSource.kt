package com.bilipai.desktop.player.cache

import com.android.purebilibili.feature.plugin.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InputStream
import java.net.URI

/** Captured capabilities of the SAME Root preferences platform, never an optimistic default.
 * identityHash identifies the actual profile/adapter/SSID without exposing those values. */
internal data class DesktopCdnNetworkObservation(val wifi: Boolean, val identityHash: String) {
    init { require(identityHash.matches(Regex("[a-f0-9]{64}"))) }
}

internal interface DesktopMediaByteCdnAdmission : DesktopMediaByteAdmission {
    /** Native observation runs outside Store/entry monitors with ownership checks on both sides. */
    fun cdnNetwork(): DesktopCdnNetworkObservation
}

/** Request policy on the existing Repository client. No credentials, cache or mutable authority.
 * The network interceptor rejects redirects before OkHttp can follow them; the app interceptor
 * returns raw 412/429 to the original transfer runtime without relaxing account admission. */
internal object DesktopCdnRangeRequestPolicy {
    fun rejectRedirect(response: Response) {
        if (response.code in setOf(300, 301, 302, 303, 307, 308)) {
            response.close()
            throw IOException("Captured CDN range redirect rejected")
        }
    }
    override fun toString() = "DesktopCdnRangeRequestPolicy"
}

/** One finite staged span, borrowing the same captured track, Repository calls, native lease,
 * caller Job and SpanStore. This adapter owns only its active range calls and streams. */
internal class DesktopCdnMediaRangeSource(
    private val track: DesktopMediaByteTrack,
    private val metadata: DesktopMediaResource,
    private val calls: Call.Factory,
    private val caller: Job,
    private val network: () -> DesktopCdnNetworkObservation,
    private val owned: () -> Unit,
    private val onBytes: (Int) -> Unit,
    background: Boolean,
) : AutoCloseable {
    private fun check() { caller.ensureActive(); owned() }
    private fun request(value: Request): Request {
        check()
        if (value.url.toString() !in track.urls) throw IOException("CDN address is outside captured playurl")
        return value.newBuilder()
            .tag(DesktopCdnRangeRequestPolicy::class.java, DesktopCdnRangeRequestPolicy)
            .tag(DesktopMediaOriginHeaders::class.java,
                DesktopMediaOriginHeaders(track.headers, track.urls.map { it.toHttpUrl().origin() }.toSet()))
            .build().also { check() }
    }
    private fun response(value: Response) {
        check()
        if (value.request.url.toString() !in track.urls) throw IOException("CDN final address is outside captured playurl")
        DesktopCdnRangeRequestPolicy.rejectRedirect(value)
        val encoding = value.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", true)) throw IOException("CDN encoding is not identity")
        if (value.code == 206) {
            val range = parseContentRange(value.header("Content-Range")) ?: throw IOException("CDN range missing")
            if (range.third != metadata.total) throw IOException("CDN representation length changed")
            if (metadata.validatorName != null && value.header(metadata.validatorName) != metadata.validatorValue)
                throw IOException("CDN representation validator changed")
        }
        check()
    }
    private fun candidates(values: List<String>): List<String> {
        check()
        // Runtime supplies original signed-route membership + its ten-minute validity window.
        // The track adds the exact SAME representation and captured request authorization.
        return values.filter { it in track.urls && it in CdnTransferRuntime.candidates(it) }.also { check() }
    }
    private val upstream = object : CdnMediaDataSource {
        @Volatile private var call: Call? = null
        @Volatile private var result: Response? = null
        @Volatile private var input: InputStream? = null
        private var remaining = 0L
        private var uri: URI? = null
        override fun open(spec: CdnMediaDataSpec): Long {
            check(); require(spec.length > 0 && spec.httpMethod == CdnMediaDataSpec.HTTP_METHOD_GET && spec.httpBody == null)
            val range = Request.Builder().url(spec.uri.toString()).apply {
                spec.httpRequestHeaders.forEach { (key, value) -> header(key, value) }
                header("Range", "bytes=${spec.position}-${DesktopMediaByteSpanStore.checkedEnd(spec.position, spec.length)-1}")
                header("Accept-Encoding", "identity")
            }.build()
            val current = calls.newCall(request(range)); call = current
            try {
                check()
                val opened = current.execute(); result = opened
                response(opened)
                if (opened.code != 206 || parseContentRange(opened.header("Content-Range")) !=
                    Triple(spec.position, spec.position + spec.length - 1, metadata.total))
                    throw IOException("CDN single interval mismatch")
                input = opened.body?.byteStream() ?: throw IOException("CDN single body missing")
                remaining = spec.length; uri = spec.uri
                check(); return remaining
            } catch (failure: Throwable) { close(); throw failure }
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(); require(offset >= 0 && length >= 0 && offset <= bytes.size-length)
            if (length == 0) return 0
            val stream = input ?: throw IOException("CDN single source closed")
            if (remaining == 0L) {
                if (stream.read() != -1) throw IOException("CDN single interval oversized")
                check(); return CDN_RESULT_END_OF_INPUT
            }
            val count = stream.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            check()
            if (count <= 0) throw IOException("CDN single interval truncated")
            remaining -= count; return count
        }
        override fun getUri(): URI? = uri
        override fun getResponseHeaders(): Map<String, List<String>> = result?.headers?.toMultimap().orEmpty()
        override fun close() {
            call?.cancel(); call = null
            try { result?.close() } finally { result = null; input = null; uri = null }
        }
    }
    private val platform = object : CdnPlaybackPlatform {
        override fun assertCurrent() = check()
        override fun isWifi(): Boolean { check(); return network().wifi.also { check() } }
        override fun networkIdentity(): String { check(); return network().identityHash.also { check() } }
        override fun authorizedCandidates(urls: List<String>) = candidates(urls)
        override fun assertParallelUrl(url: String) {
            if (candidates(listOf(url)).isEmpty()) throw IOException("CDN signed range authorization expired")
        }
        override fun prepareRequest(value: Request): Request = request(value)
        override fun validateResponse(value: Response) = response(value)
        override fun bytesTransferred(count: Int) { check(); onBytes(count); check() }
    }
    private val source = CdnPlaybackDataSource(upstream, background = background, client = calls, platform = platform)
    fun open(url: String, start: Long, length: Long) = source.open(
        CdnMediaDataSpec(URI(url), start, length, httpRequestHeaders = track.headers))
    fun read(bytes: ByteArray, offset: Int, length: Int) = source.read(bytes, offset, length)
    fun cancel() = source.cancelPendingRequests()
    override fun close() = source.close()
}
