package com.bilipai.desktop.plugins.js

import com.android.purebilibili.feature.settings.screen.validateDesktopJsImportUrlOrError
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun runDesktopJsPublicHttpFixture(): Unit = runBlocking {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newCachedThreadPool()
    server.executor = executor
    val base = "http://127.0.0.1:${server.address.port}"
    val cookieSeen = AtomicInteger()
    val uaSeen = AtomicInteger()
    val slowEntered = CountDownLatch(1)
    val blockedEntered = CountDownLatch(1)
    val blockedRelease = CountDownLatch(1)
    fun response(path: String, status: Int, contentType: String, bytes: ByteArray) {
        server.createContext(path) { exchange ->
            if (exchange.requestHeaders.getFirst("Cookie") != null) cookieSeen.incrementAndGet()
            if (exchange.requestHeaders.getFirst("User-Agent") == "BiliPai") uaSeen.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.responseHeaders.add("Set-Cookie", "account-secret=fake-loopback; Path=/")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
    response("/script", 200, "application/javascript; charset=utf-8", "const 中文 = '✓';".toByteArray())
    response("/charset", 200, "text/plain; charset=ISO-8859-1", "café".toByteArray(Charsets.ISO_8859_1))
    response("/bom", 200, "text/plain; charset=ISO-8859-1", byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "中文".toByteArray())
    response("/missing", 404, "text/plain", "missing".toByteArray())
    response("/large", 200, "text/plain", ByteArray(1_048_577))
    response("/image", 200, "image/png", byteArrayOf(1, 2, 3, 4))
    server.createContext("/chunked") { exchange ->
        exchange.sendResponseHeaders(200, 0)
        runCatching { exchange.responseBody.use { it.write(ByteArray(1_048_577)) } }
    }
    server.createContext("/redirect") { exchange ->
        exchange.responseHeaders.add("Location", "$base/script")
        exchange.sendResponseHeaders(302, -1); exchange.close()
    }
    server.createContext("/foreign") { exchange ->
        exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/script")
        exchange.sendResponseHeaders(302, -1); exchange.close()
    }
    server.createContext("/slow") { exchange ->
        exchange.sendResponseHeaders(200, 0)
        runCatching {
            exchange.responseBody.use { output ->
                output.write(1); output.flush(); slowEntered.countDown()
                while (true) { Thread.sleep(50); output.write(2); output.flush() }
            }
        }
    }
    server.createContext("/blocked") { exchange ->
        blockedEntered.countDown()
        blockedRelease.await(10, TimeUnit.SECONDS)
        runCatching { exchange.sendResponseHeaders(200, -1); exchange.close() }
    }
    server.start()
    val client = DesktopJsPublicHttp { it.startsWith("$base/") }
    val results = mutableListOf<String>()
    suspend fun checkCase(name: String, block: suspend () -> Unit) { block(); results += name; println("PASS $name") }
    suspend fun rejected(block: suspend () -> Unit): Throwable {
        var failure: Throwable? = null
        try { block() } catch (caught: Throwable) { failure = caught }
        return checkNotNull(failure) { "Expected rejection" }
    }
    try {
        checkCase("upstream URL validation preserves empty/scheme/host errors") {
            check(validateDesktopJsImportUrlOrError("  ") == "请输入链接地址")
            listOf("file:///c:/secret", "https:///missing", "not a URL", "javascript:alert(1)").forEach {
                check(validateDesktopJsImportUrlOrError(it) == "请输入有效的 http/https 地址")
            }
            check(validateDesktopJsImportUrlOrError("  $base/script  ") == null)
        }
        checkCase("upstream UTF8 text and User-Agent without any persistent Cookie") {
            repeat(2) { check(client.downloadScript(" $base/script ") == "const 中文 = '✓';") }
            check(cookieSeen.get() == 0 && uaSeen.get() == 2)
        }
        checkCase("original ResponseBody.string charset and BOM decoding") {
            check(client.downloadScript("$base/charset") == "café")
            check(client.downloadScript("$base/bom") == "中文")
        }
        checkCase("original HTTP status error retained") {
            check(rejected { client.downloadScript("$base/missing") }.message == "JS 插件下载失败: HTTP 404")
        }
        checkCase("declared and unknown-length oversized bodies rejected") {
            check(rejected { client.downloadScript("$base/large") }.message?.contains("大小限制") == true)
            check(rejected { client.downloadScript("$base/chunked") }.message?.contains("大小限制") == true)
        }
        checkCase("same-authority redirect allowed and every redirected authority checked") {
            check(client.downloadScript("$base/redirect") == "const 中文 = '✓';")
            check(rejected { client.downloadScript("$base/foreign") }.message?.contains("重定向地址") == true)
        }
        checkCase("image bytes use public channel without Cookie inheritance") {
            check(client.downloadImage("$base/image").contentEquals(byteArrayOf(1, 2, 3, 4)))
            check(cookieSeen.get() == 0)
            rejected { client.downloadImage("file:///c:/secret") }
        }
        checkCase("cancelling caller interrupts real streaming body and joins it promptly") {
            val work = async { client.downloadScript("$base/slow") }
            withContext(Dispatchers.IO) { check(slowEntered.await(3, TimeUnit.SECONDS)) }
            withTimeout(2_000) { work.cancelAndJoin() }
            check(client.downloadScript("$base/script") == "const 中文 = '✓';")
        }
        checkCase("restore shutdown cancels in-flight call joins and rejects late operations") {
            val work = async { runCatching { client.downloadScript("$base/blocked") } }
            withContext(Dispatchers.IO) { check(blockedEntered.await(3, TimeUnit.SECONDS)) }
            withTimeout(2_000) { client.shutdownForRestore() }
            check(work.await().isFailure)
            check(rejected { client.downloadScript("$base/script") }.message == "JS 公共网络服务已停止")
            client.shutdownForRestore()
        }
    } finally {
        blockedRelease.countDown(); client.shutdownForRestore(); server.stop(0)
        executor.shutdownNow(); check(executor.awaitTermination(3, TimeUnit.SECONDS))
    }
    println("Actual loopback remote import: ${results.size} PASS")
}
