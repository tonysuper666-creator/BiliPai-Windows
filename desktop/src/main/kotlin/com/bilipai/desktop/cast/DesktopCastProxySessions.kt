package com.bilipai.desktop.cast

import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.copyPlaybackStreamHeaders
import com.bilipai.desktop.player.playbackHeader
import com.android.purebilibili.feature.cast.SsdpCastClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A private, immutable upstream registration. Its LAN address contains only an opaque identifier. */
internal class DesktopCastProxyTarget(val url: HttpUrl, val headers: Map<String, String>, val publication: DesktopCastPublicationFrame?) {
    override fun toString(): String = "DesktopCastProxyTarget(headers=${headers.size})"
}

object DesktopCastProxySessions {
    private val registrations = ConcurrentHashMap<String, DesktopCastProxyTarget>()
    private val localRegistrations = ConcurrentHashMap<String, DesktopCastLocalTarget>()
    private val lifecycle = Any()
    private val consumers = mutableMapOf<Any, () -> Boolean>()
    private var preparations = 0
    private val preparationFlow = MutableStateFlow(0)
    val pendingPreparations = preparationFlow.asStateFlow()

    /** A URL must stay valid from source resolution until the receiver starts. */
    fun acquirePreparation(): AutoCloseable {
        synchronized(lifecycle) { preparations++; preparationFlow.value = preparations }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(lifecycle) {
                preparations--; preparationFlow.value = preparations
            }
        }
    }

    /** The runtime closes this registration only after its provider has joined. */
    fun registerConsumer(inUse: () -> Boolean): AutoCloseable {
        val identity = Any()
        synchronized(lifecycle) { consumers[identity] = inUse }
        return AutoCloseable { synchronized(lifecycle) { consumers.remove(identity) } }
    }

    fun canClear(): Boolean = synchronized(lifecycle) {
        preparations == 0 && !SsdpCastClient.playbackState.value.isActive && consumers.values.none { it() }
    }

    fun register(targetUrl: String, headers: Map<String, String>): String {
        val url = targetUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效的投屏媒体地址")
        require(url.username.isEmpty() && url.password.isEmpty()) { "投屏媒体地址不能包含登录凭证" }
        val snapshot = copyPlaybackStreamHeaders(headers)
        require(snapshot.keys.none { it.lowercase(Locale.ROOT) in TRANSPORT_HEADERS }) { "投屏请求头不能改写 HTTP 传输控制字段" }
        val id = UUID.randomUUID().toString()
        val frame = su.litvak.chromecast.api.v2.DesktopCastPublication.current() as? DesktopCastPublicationFrame
        val publish = { registrations[id] = DesktopCastProxyTarget(url, snapshot, frame) }
        if (frame == null) publish() else frame.admit(publish)
        return id
    }

    /** Only the current, already-muxed offline playback source can register a file. */
    fun registerLocalFile(path: java.nio.file.Path, contentType: String,
        nativePublication: com.bilipai.desktop.player.DesktopNativePlaybackPublication): String {
        val frame = checkNotNull(su.litvak.chromecast.api.v2.DesktopCastPublication.current() as? DesktopCastPublicationFrame) {
            "本地文件投屏缺少当前播放会话"
        }
        // Filesystem checks stay outside account, entry and native admission.
        val target = DesktopCastLocalTarget.capture(path, contentType, frame, nativePublication)
        val id = UUID.randomUUID().toString()
        target.admit { localRegistrations[id] = target }
        return id
    }

    internal fun find(id: String): DesktopCastProxyTarget? = registrations[id]
    internal fun findLocal(id: String): DesktopCastLocalTarget? = localRegistrations[id]
    fun clear() {
        registrations.clear()
        val local = localRegistrations.values.toList()
        localRegistrations.clear()
        local.forEach(DesktopCastLocalTarget::retire)
    }

    /** Apply after OkHttp's bridge so explicit empty Cookie/Referer/UA cannot acquire defaults. */
    fun networkInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val registration = request.tag(DesktopCastProxyTarget::class.java)
        chain.proceed(if (registration == null) request else request.withRegisteredHeaders(registration))
    }

    private fun Request.withRegisteredHeaders(target: DesktopCastProxyTarget): Request {
        val sameOrigin = url.scheme == target.url.scheme && url.host == target.url.host && url.port == target.url.port
        val receiverRange = header("Range")
        return newBuilder().apply {
            target.headers.forEach { (name, value) ->
                if (sameOrigin && value.isNotEmpty()) header(name, value) else removeHeader(name)
            }
            receiverRange?.let { header("Range", it) }
        }.build()
    }

    private val TRANSPORT_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "te", "trailer", "upgrade", "expect", "proxy-authorization", "proxy-connection")
}

/** The native actor's source snapshot is authoritative for plugin overrides and empty values. */
internal fun desktopCastStreamHeaders(source: PlaybackSource): Map<String, String> {
    val explicit = copyPlaybackStreamHeaders(source.streamHeaders)
    return copyPlaybackStreamHeaders(linkedMapOf<String, String>().apply {
        if (explicit.playbackHeader("User-Agent") == null) put("User-Agent", source.userAgent)
        if (explicit.playbackHeader("Referer") == null) put("Referer", source.referer)
        if (explicit.playbackHeader("Cookie") == null && source.cookieHeader.isNotEmpty()) put("Cookie", source.cookieHeader)
        putAll(explicit)
    })
}
