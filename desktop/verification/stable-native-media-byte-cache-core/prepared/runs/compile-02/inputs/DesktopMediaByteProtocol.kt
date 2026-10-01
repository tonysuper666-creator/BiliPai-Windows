package com.bilipai.desktop.player.cache

import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.player.DesktopNativePlaybackPublication
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.copyPlaybackStreamHeaders
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.security.MessageDigest
import java.util.Collections

/** Captured same-Store admission. No latest-account lookup or HTTP client is owned here.
 * Native readers receive a Root source-lifetime Job, never the completed resolver Job.
 * calls must use the existing Repository transport and the supplied dynamic lease guard. */
internal interface DesktopMediaByteAdmission {
    val receipt: DesktopPlaybackAuthorizationReceipt
    val persistentNamespace: String
    val ownerJob: Job
    fun assertCurrent()
    fun <T> commit(block: () -> T): T
    fun calls(stillCurrent: () -> Boolean, callerJob: Job): Call.Factory
}

internal class DesktopMediaByteTrack(
    val url: String,
    mirrors: List<String>,
    val cacheKey: String,
    val representation: String,
    headers: Map<String, String>,
) {
    val urls: Set<String> = Collections.unmodifiableSet((listOf(url) + mirrors).toSet())
    val headers = copyPlaybackStreamHeaders(headers)
    init {
        require(cacheKey.isNotBlank() && cacheKey.length <= 2048)
        require(representation.isNotBlank() && representation.length <= 2048)
        require(urls.size <= 64)
        urls.forEach { raw ->
            val parsed = raw.toHttpUrl()
            require(parsed.username.isEmpty() && parsed.password.isEmpty())
        }
        require(this.headers.keys.none { it.lowercase() in TRANSPORT_HEADERS })
    }
    override fun toString() = "DesktopMediaByteTrack"
}

/** Typed SAME-client final header seam. Repository applies this after CookieJar.
 * All origin fields stay private to this in-memory registered request. */
internal class DesktopMediaOriginHeaders(headers: Map<String, String>, private val allowedOrigins: Set<String>) {
    private val values = copyPlaybackStreamHeaders(headers)
    fun apply(request: Request): Request = request.newBuilder().apply {
        val sameOrigin = request.url.origin() in allowedOrigins
        values.forEach { (key, value) ->
            if (sameOrigin || key.lowercase() !in SENSITIVE_HEADERS) header(key, value)
            else removeHeader(key)
        }
        if (!sameOrigin) { removeHeader("Cookie"); removeHeader("Authorization"); removeHeader("X-BiliPai-Force-Cookie") }
        header("Accept-Encoding", "identity")
    }.build()
    override fun toString() = "DesktopMediaOriginHeaders"
}

internal fun okhttp3.HttpUrl.origin() = "$scheme://$host:$port"
internal val TRANSPORT_HEADERS = setOf("host", "range", "content-length", "connection", "transfer-encoding", "te", "trailer", "upgrade", "expect", "proxy-authorization", "proxy-connection", "accept-encoding")
internal val SENSITIVE_HEADERS = setOf("cookie", "authorization", "proxy-authorization")
internal fun mediaDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

public enum class DesktopNativeMediaTransportMode { SEPARATE_DASH, ADAPTIVE_MPD, PROGRESSIVE_EDL }

/** Native-only immutable address projection. Remote PlaybackSource fields remain semantic.
 * Constructor and token are internal; logs deliberately have no capability paths. */
public class DesktopNativeMediaTransport internal constructor(
    internal val lease: DesktopMediaByteLease,
    internal val mode: DesktopNativeMediaTransportMode,
    internal val videoUri: String,
    internal val audioUri: String?,
    progressiveUris: List<String>,
    private val fingerprint: String,
) {
    internal val progressiveUris: List<String> = Collections.unmodifiableList(progressiveUris.toList())
    internal fun validate(source: PlaybackSource) {
        require(mediaSourceFingerprint(source) == fingerprint) { "Media transport origin changed" }
        require(mode != DesktopNativeMediaTransportMode.PROGRESSIVE_EDL || progressiveUris.size == source.progressiveSegments.size)
    }
    internal fun nativeVideo(source: PlaybackSource): String {
        validate(source)
        if (mode != DesktopNativeMediaTransportMode.PROGRESSIVE_EDL) return videoUri
        return "edl://" + progressiveUris.mapIndexed { index, address ->
            val escaped = "%${address.toByteArray(Charsets.UTF_8).size}%$address"
            source.progressiveSegments[index].durationSeconds?.let { "$escaped,0,$it" } ?: escaped
        }.joinToString(";")
    }
    /** Pure short rebind. Caller is in the SAME successful Store->entry->native transaction. */
    internal fun attach(sourceVersion: Long, publication: DesktopNativePlaybackPublication, admission: DesktopMediaByteAdmission) =
        lease.attach(sourceVersion, publication, admission)
    internal fun adopt(expectedVersion: Long, expectedPublication: DesktopNativePlaybackPublication,
        newPublication: DesktopNativePlaybackPublication, admission: DesktopMediaByteAdmission): Boolean =
        lease.adopt(expectedVersion, expectedPublication, newPublication, admission)
    internal fun retire(sourceVersion: Long) = lease.retire(sourceVersion)
    override fun toString() = "DesktopNativeMediaTransport(mode=$mode)"
}

internal fun mediaSourceFingerprint(source: PlaybackSource): String = mediaDigest(buildString {
    append(source.videoUrl); append('\u0000'); append(source.audioUrl); append('\u0000')
    source.progressiveSegments.forEach { append(it.url); append('\u0000'); append(it.durationSeconds); append('\u0000') }
    append(source.authorizationReceipt); append('\u0000'); append(source.primaryAccountEpoch)
    append('\u0000'); append(source.referer); append('\u0000'); append(source.userAgent)
    append('\u0000'); append(source.cookieHeader)
    source.streamHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (k,v) -> append('\u0000'); append(k.lowercase()); append('='); append(v) }
})

internal data class DesktopMediaByteCacheStats(val upstreamBytes: Long, val servedCacheBytes: Long,
    val diskBytesIncludingStaging: Long, val spans: Int, val registrations: Int)

internal fun DesktopMediaByteAdmission.check() { ownerJob.ensureActive(); assertCurrent(); require(persistentNamespace.matches(Regex("[a-f0-9]{64}"))) }
