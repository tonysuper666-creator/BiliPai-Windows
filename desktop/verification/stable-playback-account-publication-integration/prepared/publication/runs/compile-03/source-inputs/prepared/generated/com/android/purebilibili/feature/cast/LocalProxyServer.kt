// GENERATED from app/src/main/java/com/android/purebilibili/feature/cast/LocalProxyServer.kt; Android platform bindings only.
package com.android.purebilibili.feature.cast

import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.cast.DesktopCastNetwork
import com.bilipai.desktop.cast.DesktopCastProxySessions
import com.bilipai.desktop.cast.DesktopCastProxyTarget
import com.bilipai.desktop.cast.DesktopCastLog as Logger
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行在手机上的轻量级 HTTP 代理服务器。
 * 作用：拦截 DLNA/Chromecast 设备的播放请求，转发给 Bilibili 服务器并修改请求头，从而绕过防盗链 (403 Forbidden)。
 *
 * 原理：
 * 1. 电视/DLNA 设备请求: http://<手机IP>:<端口>/proxy?url=<编码后的B站视频URL>
 * 2. 代理服务器解析 `url` 参数。
 * 3. 代理服务器伪装成合法客户端（添加 User-Agent, Referer）向 B站请求数据。
 * 4. 代理服务器将 B 站返回的数据流（InputStream）直接流式传输给电视。
 */
class LocalProxyServer(port: Int = 8901, hostname: String? = null) : NanoHTTPD(hostname, port) {

    private val client = OkHttpClient.Builder()
        .addNetworkInterceptor(DesktopCastProxySessions.networkInterceptor())
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun useGzipWhenAccepted(response: Response): Boolean =
        response.requestMethod != Method.HEAD && super.useGzipWhenAccepted(response)

    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        val response = serveRequest(session)
        if (session.method == Method.HEAD) {
            response.close()
            response.setData(java.io.ByteArrayInputStream(ByteArray(0)))
        }
        return response
    }

    private fun serveRequest(session: IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri ?: "/"

        if (session.method == Method.OPTIONS) {
            val corsHeaders = corsHeaders()
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").also { resp ->
                corsHeaders.forEach { (key, value) -> resp.addHeader(key, value) }
            }
        }

        if (uri.startsWith("/dash/") && uri.endsWith(".mpd")) {
            val key = uri.removePrefix("/dash/").removeSuffix(".mpd")
            val manifest = manifestStore[key]
            if (manifest != null) {
                val resp = newFixedLengthResponse(Response.Status.OK, DASH_CONTENT_TYPE, manifest)
                corsHeaders().forEach { (key, value) -> resp.addHeader(key, value) }
                return resp
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Manifest not found")
        }

        if (uri == "/proxy") {
            return serveProxy(session)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun serveProxy(session: IHTTPSession): NanoHTTPD.Response {
        val params = session.parameters.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
        val registrationId = params["target"]
        val registration = registrationId?.let(DesktopCastProxySessions::find)
        if (registrationId != null && registration == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Target is not part of the active cast session")
        }
        val targetUrl = registration?.url?.toString() ?: params["url"]

        if (targetUrl.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing 'url' parameter")
        }
        if (!isSupportedTargetUrl(targetUrl)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Unsupported target URL")
        }
        val parsedTargetUrl = targetUrl.toHttpUrlOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid target URL")

        if (registration == null && !DesktopCastNetwork.isRegisteredProxyTarget(parsedTargetUrl.toString())) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Target is not part of the active cast session")
        }

        try {
            val referer = params["referer"] ?: "https://www.bilibili.com"
            val userAgent =
                params["ua"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

            val request = Request.Builder()
                .url(parsedTargetUrl)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
            if (registration != null) request.tag(DesktopCastProxyTarget::class.java, registration)
            session.headers["range"]?.takeIf { it.isNotBlank() }?.let { rangeHeader ->
                request.header("Range", rangeHeader)
            }
            if (session.method == Method.HEAD) request.head()
            val upstreamRequest = request.build()

            val upstreamResponse = (registration?.publication?.calls(client) ?: client).newCall(upstreamRequest).execute()

            if (!upstreamResponse.isSuccessful) {
                val body = upstreamResponse.body.string()
                upstreamResponse.close()
                return newFixedLengthResponse(
                    mapToNanoStatus(upstreamResponse.code),
                    MIME_PLAINTEXT,
                    "Upstream Error: ${upstreamResponse.code}"
                )
            }

            val body = upstreamResponse.body
            val inputStream = UpstreamRelayInputStream(upstreamResponse, body.byteStream())
            val contentType = upstreamResponse.header("Content-Type") ?: "video/mp4"
            val contentLength = if (session.method == Method.HEAD) {
                upstreamResponse.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
                    ?: body.contentLength()
            } else body.contentLength()

            val nanoResponse = if (contentLength >= 0L) {
                newFixedLengthResponse(mapToNanoStatus(upstreamResponse.code), contentType, inputStream, contentLength)
            } else {
                newChunkedResponse(mapToNanoStatus(upstreamResponse.code), contentType, inputStream)
            }
            upstreamResponse.header("Content-Range")?.let { nanoResponse.addHeader("Content-Range", it) }
            upstreamResponse.header("Accept-Ranges")?.let { nanoResponse.addHeader("Accept-Ranges", it) }

            corsHeaders().forEach { (key, value) -> nanoResponse.addHeader(key, value) }

            return nanoResponse

        } catch (e: Exception) {
            Logger.e("LocalProxyServer", "📺 [Proxy] 代理请求处理失败", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Media relay failed")
        }
    }

    companion object {
        const val PORT = 8901
        const val DASH_CONTENT_TYPE = "application/dash+xml"

        @Volatile private var sharedServer: LocalProxyServer? = null
        private val bootstrapLock = Any()
        private val manifestStore = ConcurrentHashMap<String, String>()

        @JvmStatic
        fun ensureStarted(): Boolean {
            synchronized(bootstrapLock) {
                if (sharedServer != null) return false
                val server = LocalProxyServer(DesktopCastNetwork.proxyPort, DesktopCastNetwork.proxyBindHost)
                server.start()
                sharedServer = server
                return true
            }
        }

        fun stopAndClear() {
            synchronized(bootstrapLock) {
                if (!DesktopCastProxySessions.canClear()) return
                sharedServer?.client?.dispatcher?.cancelAll()
                sharedServer?.stop()
                sharedServer = null
                manifestStore.clear()
                DesktopCastNetwork.clearProxyTargets()
            }
        }

        fun dashManifestPath(key: String): String = "/dash/$key.mpd"

        fun corsHeaders(): Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "Range, Content-Type, Origin, Accept",
            "Access-Control-Max-Age" to "3600"
        )

        fun registerDashManifest(context: Context, manifest: String): String {
            ensureStarted()
            val digest = MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray(Charsets.UTF_8))
            val key = digest.joinToString("") { "%02x".format(it) }.take(12)
            val frame = su.litvak.chromecast.api.v2.DesktopCastPublication.current() as? com.bilipai.desktop.cast.DesktopCastPublicationFrame
            if (frame == null) manifestStore[key] = manifest else frame.admit { manifestStore[key] = manifest }
            val ipAddress = resolveLocalIpv4Address(context)
            return "http://$ipAddress:${sharedServer?.listeningPort ?: PORT}${dashManifestPath(key)}"
        }

        /**
         * 生成代理 URL供 DLNA 设备使用
         * @param context 用于获取 Wi-Fi IP 地址
         * @param targetUrl 实际的 B 站视频 URL
         * @return 代理服务器的完整 URL
         */
        fun getProxyUrl(context: Context, targetUrl: String, streamHeaders: Map<String, String>? = null): String {
            val ipAddress = resolveLocalIpv4Address(context)

            if (streamHeaders != null) {
                val id = DesktopCastProxySessions.register(targetUrl, streamHeaders)
                return "http://$ipAddress:${sharedServer?.listeningPort ?: PORT}/proxy?target=$id"
            }
            // 对目标 URL 进行编码，作为参数传递
            DesktopCastNetwork.registerProxyTarget(targetUrl)
            val encodedUrl = URLEncoder.encode(targetUrl, "UTF-8")

            return "http://$ipAddress:${sharedServer?.listeningPort ?: PORT}/proxy?url=$encodedUrl"
        }

        internal fun pickBestIpv4Address(addresses: List<InetAddress>): String? {
            return addresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }

        internal fun isSupportedTargetUrl(url: String): Boolean {
            val scheme = url.toHttpUrlOrNull()?.scheme ?: return false
            return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        }

        private fun resolveLocalIpv4Address(context: Context): String = DesktopCastNetwork.proxyAddress(context)

        private fun mapToNanoStatus(code: Int): Response.Status {
            return when (code) {
                200 -> Response.Status.OK
                206 -> Response.Status.PARTIAL_CONTENT
                400 -> Response.Status.BAD_REQUEST
                401 -> Response.Status.UNAUTHORIZED
                403 -> Response.Status.FORBIDDEN
                404 -> Response.Status.NOT_FOUND
                else -> Response.Status.INTERNAL_ERROR
            }
        }
    }

    private class UpstreamRelayInputStream(
        private val upstreamResponse: okhttp3.Response,
        private val delegate: InputStream
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray): Int = delegate.read(b)

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun close() {
            try {
                delegate.close()
            } finally {
                upstreamResponse.close()
            }
        }
    }
}
