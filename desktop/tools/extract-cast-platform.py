#!/usr/bin/env python3
"""Windows bindings for the original DLNA protocol and proxy implementations."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import textwrap

BASE = "app/src/main/java/com/android/purebilibili/"
SOURCES = {
    BASE + "feature/cast/SsdpDiscovery.kt": "policy-extract",
    BASE + "feature/cast/SsdpLocalNetworkPolicy.kt": "policy-extract",
    BASE + "feature/cast/SsdpCastClient.kt": "platform-rewrite",
    BASE + "feature/cast/LocalProxyServer.kt": "platform-rewrite",
    BASE + "feature/cast/SsdpDevicePresentationPolicy.kt": "platform-rewrite",
    BASE + "feature/plugin/dlna/DlnaCastRoutePolicy.kt": "direct",
    BASE + "feature/plugin/dlna/DlnaCastPlugin.kt": "platform-rewrite",
    BASE + "data/repository/VideoCastPolicy.kt": "direct",
    BASE + "feature/video/playback/dash/LocalDashManifestBuilder.kt": "direct",
}


def inventory(repo: Path) -> list[dict]:
    return [{"path": path, "mode": mode, "features": ["dlna-cast"],
             "sha256": hashlib.sha256((_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").encode("utf-8")).hexdigest()}
            for path, mode in SOURCES.items()]


def substitute(source: str, old: str, new: str, count: int = 1) -> str:
    if source.count(old) != count:
        raise ValueError("Original DLNA platform boundary changed: " + old[:90])
    return source.replace(old, new)


def generate(repo: Path, output: Path) -> None:
    spec = importlib.util.spec_from_file_location("cast_parser", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)

    def read(path: str) -> str:
        return (_desktop_canonical_source(repo, path)).read_text(encoding="utf-8")

    def write(path: str, source: str) -> None:
        package = re.search(r"(?m)^package (\S+)$", source).group(1)
        target = output / package.replace(".", "/") / Path(path).name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("// GENERATED from " + path + "; Android platform bindings only.\n" + source, encoding="utf-8", newline="\n")

    def declaration(source: str, kind: str, name: str) -> str:
        tokens = parser.kotlin_tokens(source)
        matches = [i for i, token in enumerate(tokens[:-1]) if token[0] == kind and tokens[i + 1][0] == name]
        if len(matches) != 1:
            raise ValueError("Missing or duplicate original cast declaration: " + name)
        start = matches[0]
        end = start
        while tokens[end][0] != "(":
            end += 1
        depth = 1
        while depth:
            end += 1
            depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
        if kind == "fun":
            cursor = end + 1
            while tokens[cursor][0] not in ("=", "{"):
                cursor += 1
            if tokens[cursor][0] == "=":
                # Expression-bodied functions stop at the next declaration's line.
                line_start = source.rfind("\n", 0, tokens[start][1]) + 1
                following = re.search(r"(?m)^    (?:internal |private |suspend |data |inline )*(?:fun|class) ", source[tokens[end][2]:])
                if following is None:
                    body_end = source.rfind("\n}")
                else:
                    body_end = tokens[end][2] + following.start()
                return textwrap.dedent(source[line_start:body_end]).rstrip()
            while tokens[end][0] != "{":
                end += 1
            depth = 1
            while depth:
                end += 1
                depth += (tokens[end][0] == "{") - (tokens[end][0] == "}")
        begin = source.rfind("\n", 0, tokens[start][1]) + 1
        return textwrap.dedent(source[begin:tokens[end][2]])

    path = BASE + "feature/cast/SsdpDiscovery.kt"
    original = read(path)
    constants = re.findall(r"(?m)^    private const val (?:SSDP_ADDRESS|SSDP_PORT|DEFAULT_TIMEOUT_MS|RESEND_INTERVAL_MS|RECEIVE_SLICE_MS) = .+$", original)
    if len(constants) != 5:
        raise ValueError("Original SSDP constants changed")
    pure = [declaration(original, "fun", "buildSearchPayload"), declaration(original, "class", "SsdpDevice"),
            declaration(original, "fun", "resolveSsdpSearchPayloads"), declaration(original, "fun", "parseResponse")]
    body = "package com.android.purebilibili.feature.cast\n\nimport com.bilipai.desktop.plugins.DesktopPluginContext as Context\nimport com.bilipai.desktop.cast.DesktopSsdpNetwork\n\nobject SsdpDiscovery {\n" + "\n".join(constants) + "\n\n" + "\n\n".join(textwrap.indent(piece, "    ") for piece in pure) + "\n\n    suspend fun discover(context: Context, timeoutMs: Int = DEFAULT_TIMEOUT_MS): List<SsdpDevice> = DesktopSsdpNetwork.discover(context, timeoutMs, SSDP_ADDRESS, SSDP_PORT, RESEND_INTERVAL_MS, RECEIVE_SLICE_MS, resolveSsdpSearchPayloads(), ::parseResponse)\n}\n"
    write(path, body)

    path = BASE + "feature/cast/SsdpLocalNetworkPolicy.kt"
    original = read(path)
    write(path, "package com.android.purebilibili.feature.cast\n\n" + declaration(original, "fun", "scoreLocalNetwork") + "\n\n" + declaration(original, "fun", "isUsableSsdpDiscoveryMessage") + "\n")

    path = BASE + "feature/cast/SsdpDevicePresentationPolicy.kt"
    write(path, substitute(read(path), "package com.android.purebilibili.feature.cast", "package com.android.purebilibili.feature.cast\n\nimport com.bilipai.desktop.cast.castCatching as runCatching"))

    path = BASE + "feature/cast/SsdpCastClient.kt"
    body = read(path)
    body = substitute(body, "import com.android.purebilibili.core.lifecycle.BackgroundManager", "import com.bilipai.desktop.cast.DesktopCastLifecycle as BackgroundManager")
    body = substitute(body, "import com.android.purebilibili.core.util.Logger", "import com.bilipai.desktop.cast.DesktopCastLog as Logger\nimport com.bilipai.desktop.cast.castCatching as runCatching\nimport com.bilipai.desktop.cast.withDesktopCastResponse")
    body = substitute(body, "private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)", "private val scope = com.bilipai.desktop.plugins.DesktopPluginScopeRegistry.create(\"dlna-transport\", Dispatchers.IO)")
    body = substitute(body, "    private fun fetchDeviceProfile(descriptionLocation: String)", "    private suspend fun fetchDeviceProfile(descriptionLocation: String)")
    body = substitute(body, "    private fun sendSoapAction(", "    private suspend fun sendSoapAction(")
    body = substitute(body, "client.newCall(request).execute().use { response ->", "withDesktopCastResponse(client, request) { response ->", 2)
    body = substitute(body, "        autoplay: Boolean = true\n    ): Result<Unit>", "        autoplay: Boolean = true,\n        contentType: String = \"video/mp4\"\n    ): Result<Unit>")
    body = substitute(body, "buildDidlMetadata(mediaUrl, title, creator)", "buildDidlMetadata(mediaUrl, title, creator, contentType)")
    body = substitute(body, "private fun buildDidlMetadata(url: String, title: String, creator: String): String", "private fun buildDidlMetadata(url: String, title: String, creator: String, contentType: String = \"video/mp4\"): String")
    body = substitute(body, "        val escapedCreator = escapeXml(creator.ifBlank { \"BiliPai\" })", "        val escapedCreator = escapeXml(creator.ifBlank { \"BiliPai\" })\n        val escapedContentType = escapeXml(contentType)")
    body = substitute(body, 'protocolInfo="http-get:*:video/mp4:*"', 'protocolInfo="http-get:*:$escapedContentType:*"')
    body = substitute(body, "        val normalizedState = transportState.orEmpty().uppercase()", "        kotlinx.coroutines.currentCoroutineContext().ensureActive()\n        if (activeEndpoint != endpoint) return\n        val normalizedState = transportState.orEmpty().uppercase()")
    body = substitute(body, "import kotlinx.coroutines.isActive", "import kotlinx.coroutines.isActive\nimport kotlinx.coroutines.ensureActive")
    # Stop uses the same original endpoint, SOAP envelope and transport cleanup.
    stop = '''    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = requireActiveEndpoint()
            sendSoapAction(endpoint, "Stop", "<u:Stop xmlns:u=\\"${endpoint.serviceType}\\"><InstanceID>0</InstanceID></u:Stop>")
            clearPlaybackSession()
        }
    }

'''
    body = substitute(body, "    fun clearPlaybackSession() {", stop + "    fun clearPlaybackSession() {")
    write(path, body)

    path = BASE + "feature/cast/LocalProxyServer.kt"
    body = read(path)
    body = substitute(body, "import android.content.Context", "import com.bilipai.desktop.plugins.DesktopPluginContext as Context\nimport com.bilipai.desktop.cast.DesktopCastNetwork\nimport com.bilipai.desktop.cast.DesktopCastProxySessions\nimport com.bilipai.desktop.cast.DesktopCastProxyTarget")
    body = substitute(body, "import android.net.ConnectivityManager\n", "")
    body = substitute(body, "import com.android.purebilibili.core.util.Logger", "import com.bilipai.desktop.cast.DesktopCastLog as Logger")
    body = substitute(body, "class LocalProxyServer(port: Int = 8901) : NanoHTTPD(port)", "class LocalProxyServer(port: Int = 8901, hostname: String? = null) : NanoHTTPD(hostname, port)")
    body = substitute(body, "        .followRedirects(true)", "        .addNetworkInterceptor(DesktopCastProxySessions.networkInterceptor())\n        .followRedirects(true)")
    body = substitute(body, '        val targetUrl = params["url"]', '''        val registrationId = params["target"]
        val registration = registrationId?.let(DesktopCastProxySessions::find)
        if (registrationId != null && registration == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Target is not part of the active cast session")
        }
        val targetUrl = registration?.url?.toString() ?: params["url"]''')
    body = substitute(body, '            session.headers["range"]?.takeIf', '            if (registration != null) request.tag(DesktopCastProxyTarget::class.java, registration)\n            session.headers["range"]?.takeIf')
    # NanoHTTPD 2.3.1 still sends its InputStream for HEAD, and can gzip even an
    # empty HEAD stream. Keep the representation headers but never send bytes.
    body = substitute(body, "    override fun serve(session: IHTTPSession): NanoHTTPD.Response {", '''    override fun useGzipWhenAccepted(response: Response): Boolean =
        response.requestMethod != Method.HEAD && super.useGzipWhenAccepted(response)

    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        val response = serveRequest(session)
        if (session.method == Method.HEAD) {
            response.close()
            response.setData(java.io.ByteArrayInputStream(ByteArray(0)))
        }
        return response
    }

    private fun serveRequest(session: IHTTPSession): NanoHTTPD.Response {''')
    body = substitute(body, "            val upstreamRequest = request.build()", "            if (session.method == Method.HEAD) request.head()\n            val upstreamRequest = request.build()")
    body = substitute(body, "            val contentLength = body.contentLength()", '''            val contentLength = if (session.method == Method.HEAD) {
                upstreamResponse.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
                    ?: body.contentLength()
            } else body.contentLength()''')
    body = substitute(body, '''            val nanoResponse =
                newChunkedResponse(mapToNanoStatus(upstreamResponse.code), contentType, inputStream)

            if (contentLength != -1L) {
                nanoResponse.addHeader("Content-Length", contentLength.toString())
            }''', '''            val nanoResponse = if (contentLength >= 0L) {
                newFixedLengthResponse(mapToNanoStatus(upstreamResponse.code), contentType, inputStream, contentLength)
            } else {
                newChunkedResponse(mapToNanoStatus(upstreamResponse.code), contentType, inputStream)
            }''')
    body = substitute(body, "val server = LocalProxyServer(PORT)", "val server = LocalProxyServer(DesktopCastNetwork.proxyPort, DesktopCastNetwork.proxyBindHost)")
    body = substitute(body, "        fun stopAndClear() {\n            synchronized(bootstrapLock) {", "        fun stopAndClear() {\n            synchronized(bootstrapLock) {\n                if (!DesktopCastProxySessions.canClear()) return")
    body = substitute(body, "                manifestStore.clear()", "                manifestStore.clear()\n                DesktopCastNetwork.clearProxyTargets()")
    body = substitute(body, "                sharedServer?.stop()", "                sharedServer?.client?.dispatcher?.cancelAll()\n                sharedServer?.stop()")
    body = substitute(body, "        Logger.d(\"LocalProxyServer\", \"📺 [Proxy] 正在代理请求: $targetUrl\")", "        if (registration == null && !DesktopCastNetwork.isRegisteredProxyTarget(parsedTargetUrl.toString())) {\n            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, \"Target is not part of the active cast session\")\n        }")
    body = substitute(body, '"Upstream Error: ${upstreamResponse.code} ${body.take(120)}"', '"Upstream Error: ${upstreamResponse.code}"')
    body = substitute(body, '"Error: ${e.message}"', '"Media relay failed"')
    body = substitute(body, "            val encodedUrl = URLEncoder.encode(targetUrl, \"UTF-8\")", "            DesktopCastNetwork.registerProxyTarget(targetUrl)\n            val encodedUrl = URLEncoder.encode(targetUrl, \"UTF-8\")")
    body = substitute(body, "        fun getProxyUrl(context: Context, targetUrl: String): String {", "        fun getProxyUrl(context: Context, targetUrl: String, streamHeaders: Map<String, String>? = null): String {")
    body = substitute(body, "            // 对目标 URL 进行编码，作为参数传递", '''            if (streamHeaders != null) {
                val id = DesktopCastProxySessions.register(targetUrl, streamHeaders)
                return "http://$ipAddress:$PORT/proxy?target=$id"
            }
            // 对目标 URL 进行编码，作为参数传递''')
    body = body.replace(":$PORT", ":${sharedServer?.listeningPort ?: PORT}")
    start = body.index("        private fun resolveLocalIpv4Address(context: Context): String {")
    end = body.index("        private fun mapToNanoStatus", start)
    body = body[:start] + "        private fun resolveLocalIpv4Address(context: Context): String = DesktopCastNetwork.proxyAddress(context)\n\n" + body[end:]
    body = substitute(body, 'val upstreamResponse = client.newCall(upstreamRequest).execute()', 'val upstreamResponse = (registration?.publication?.calls(client) ?: client).newCall(upstreamRequest).execute()')
    body = substitute(body, '            manifestStore[key] = manifest', '            val frame = su.litvak.chromecast.api.v2.DesktopCastPublication.current() as? com.bilipai.desktop.cast.DesktopCastPublicationFrame\n            if (frame == null) manifestStore[key] = manifest else frame.admit { manifestStore[key] = manifest }')
    write(path, body)

    path = BASE + "feature/plugin/dlna/DlnaCastPlugin.kt"
    body = read(path)
    body = substitute(body, "import android.content.Context", "import com.bilipai.desktop.plugins.DesktopPluginContext as Context")
    body = substitute(body, "import com.android.purebilibili.core.util.Logger", "import com.bilipai.desktop.cast.DesktopCastLog as Logger")
    body = substitute(body, "import com.android.purebilibili.feature.cast.hasRawLocalNetworkAccess", "import com.bilipai.desktop.cast.hasRawLocalNetworkAccess")
    body = substitute(body, "private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)", "private val scope = com.bilipai.desktop.plugins.DesktopPluginScopeRegistry.create(\"dlna\", Dispatchers.Main.immediate)")
    body = substitute(body, "                    autoplay = media.autoplay", "                    autoplay = media.autoplay,\n                    contentType = media.contentType")
    body = substitute(body, "    private var discoveryJob: Job? = null", "    private val _discoveryError = MutableStateFlow<String?>(null)\n    val discoveryError: StateFlow<String?> = _discoveryError.asStateFlow()\n\n    private var discoveryJob: Job? = null")
    body = substitute(body, "            _isDiscovering.value = true", "            _isDiscovering.value = true\n            _discoveryError.value = null")
    # Failed scans preserve the cached routes, while coroutine cancellation must reach the caller.
    body = substitute(body, "            } catch (_: Exception) {", "            } catch (error: Exception) {\n                if (error is kotlinx.coroutines.CancellationException) throw error\n                _discoveryError.value = \"本地设备发现失败（${error.javaClass.simpleName}）\"")
    write(path, body)


def main() -> None:
    args = argparse.ArgumentParser()
    args.add_argument("--repo", type=Path, required=True)
    args.add_argument("--output", type=Path)
    args.add_argument("--inventory", action="store_true")
    parsed = args.parse_args()
    if parsed.inventory:
        print(json.dumps(inventory(parsed.repo), ensure_ascii=False, indent=2))
    if parsed.output:
        generate(parsed.repo, parsed.output)
    if not parsed.inventory and not parsed.output:
        args.error("--inventory or --output is required")


if __name__ == "__main__":
    main()
