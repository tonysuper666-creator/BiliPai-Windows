package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.CaptchaData
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.awt.Desktop
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A user-operated official Geetest page. Only challenge data enters the local browser page. */
class DesktopCaptchaBridge(private val login: DesktopLoginRepository) {
    suspend fun verify(captcha: CaptchaData): DesktopCaptchaResult = withContext(Dispatchers.IO) {
        val gt = captcha.geetest?.gt?.takeIf(String::isNotBlank) ?: throw BiliApiException(-105, "不支持此验证类型，请使用扫码登录")
        val challenge = captcha.geetest?.challenge?.takeIf(String::isNotBlank) ?: throw BiliApiException(-105, "验证参数不完整")
        val url = "https://api.geetest.com/gettype.php".toHttpUrl().newBuilder().addQueryParameter("gt", gt).build()
        val config = login.captchaClient.newCall(Request.Builder().url(url).get().build()).awaitConfig(gt, challenge)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bilipai-captcha").apply { isDaemon = true } }
        server.executor = executor
        val route = "/${UUID.randomUUID()}"
        val origin = "http://127.0.0.1:${server.address.port}"
        val result = CompletableDeferred<DesktopCaptchaResult>()
        server.createContext("/") { exchange ->
            try {
                val validHost = exchange.requestHeaders.getFirst("Host") == "127.0.0.1:${server.address.port}"
                if (!validHost || !exchange.remoteAddress.address.isLoopbackAddress) {
                    exchange.sendResponseHeaders(403, -1)
                } else if (exchange.requestURI.rawQuery == null && exchange.requestURI.path == route && exchange.requestMethod == "GET") {
                    val bytes = desktopGeetestHtml(config, "$route/result").toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    exchange.responseHeaders.add("Cache-Control", "no-store")
                    exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
                    exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
                    exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
                } else if (exchange.requestURI.rawQuery == null && exchange.requestURI.path == "$route/result" && exchange.requestMethod == "POST" &&
                    exchange.requestHeaders.getFirst("Origin") == origin &&
                    exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';') == "application/json") {
                    val bytes = exchange.requestBody.readNBytes(8193)
                    require(bytes.size <= 8192) { "验证结果过长" }
                    val payload = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    if (payload["cancel"]?.jsonPrimitive?.booleanOrNull == true) result.completeExceptionally(BiliApiException(-105, "验证已关闭，请重新验证"))
                    else if (payload["failed"]?.jsonPrimitive?.booleanOrNull == true) result.completeExceptionally(BiliApiException(-105, "安全验证失败，请重新验证"))
                    else {
                        fun field(name: String) = payload[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 2048 && it.none { c -> c.code < 0x20 } }
                            ?: throw IllegalArgumentException("验证结果不完整")
                        val verified = DesktopCaptchaResult(field("challenge"), field("validate"), field("seccode"))
                        requireCaptchaResult(captcha, verified)
                        result.complete(verified)
                    }
                    exchange.sendResponseHeaders(204, -1)
                } else exchange.sendResponseHeaders(404, -1)
            } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(400, -1) } }
            finally { exchange.close() }
        }
        try {
            server.start()
            require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { "系统浏览器不可用，请使用扫码登录" }
            Desktop.getDesktop().browse(URI("$origin$route"))
            try { withTimeout(5 * 60_000L) { result.await() } }
            catch (_: TimeoutCancellationException) { throw BiliApiException(-105, "安全验证已超时，请重新验证") }
        } finally { server.stop(0); executor.shutdownNow(); result.cancel() }
    }
}

private suspend fun Call.awaitConfig(gt: String, challenge: String): JsonObject = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(BiliApiException(-105, "验证配置加载失败")) }
        override fun onResponse(call: Call, response: Response) {
            val parsed = runCatching { response.use {
                check(it.isSuccessful) { "验证配置加载失败 (HTTP ${it.code})" }
                desktopGeetestConfig(it.body.string(), gt, challenge)
            } }
            if (continuation.isActive) parsed.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    })
}

internal fun desktopGeetestConfig(raw: String, gt: String, challenge: String): JsonObject {
    // Original CaptchaManager parseGeetestConfig with kotlinx JSON replacing Android JSONObject.
    val root = Json.parseToJsonElement(raw.trim().removePrefix("(").removeSuffix(")")).jsonObject
    check(root["status"]?.jsonPrimitive?.content == "success") { "验证配置无效" }
    val data = root["data"]?.jsonObject ?: error("验证配置为空")
    return buildJsonObject {
        data.forEach { (name, value) -> put(name, value) }
        put("gt", gt); put("challenge", challenge); put("offline", false); put("new_captcha", true)
        put("product", "bind"); put("width", "100%"); put("https", true); put("protocol", "https://")
    }
}

internal fun desktopGeetestHtml(config: JsonObject, resultPath: String): String {
    require(resultPath.matches(Regex("/[0-9a-f-]{36}/result")))
    val safeJson = config.toString().replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>BiliPai 安全验证</title><style>body{font-family:system-ui;background:#f5f7fb;color:#1f2431;padding:32px}main{max-width:600px;margin:auto;background:white;padding:28px;border-radius:18px}button{padding:12px 20px}</style>
<main><h2>请完成安全验证</h2><p id="status">正在加载官方验证…</p><button id="retry">重新打开验证</button><p>完成后返回 BiliPai。此页面不会读取账号密码或登录 Cookie。</p></main>
<script src="https://static.geetest.com/static/js/fullpage.0.0.0.js"></script><script>
const config=$safeJson;
function report(value){return fetch('$resultPath',{method:'POST',headers:{'Content-Type':'application/json'},credentials:'omit',body:JSON.stringify(value)});}
let captchaObj=window.Geetest(config).onReady(function(){document.getElementById('status').textContent='请按官方提示完成验证';captchaObj.verify();})
.onSuccess(function(){let r=captchaObj.getValidate();if(!r){report({failed:true});return;}report({challenge:r.geetest_challenge||config.challenge,validate:r.geetest_validate,seccode:r.geetest_seccode}).then(function(){document.getElementById('status').textContent='验证完成，请返回 BiliPai';});})
.onError(function(){document.getElementById('status').textContent='验证失败，请返回 BiliPai 重新验证';report({failed:true});})
.onClose(function(){report({cancel:true});});
document.getElementById('retry').onclick=function(){captchaObj.verify();};
</script></html>"""
}
