package com.bilipai.desktop.plugins.js

import com.android.purebilibili.feature.settings.screen.downloadJsRemotePlugin
import com.android.purebilibili.feature.settings.screen.validateDesktopJsImportUrlOrError
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.TimeUnit

/** Bound only while executing the unchanged upstream remote-import function. */
object DesktopJsRemoteNetwork {
    private val binding = ThreadLocal<OkHttpClient>()
    val okHttpClient: OkHttpClient get() = binding.get() ?: error("JS 远程导入未绑定网络会话")
    internal fun <T> withClient(client: OkHttpClient, block: () -> T): T {
        check(binding.get() == null)
        binding.set(client)
        try { return block() } finally { binding.remove() }
    }
}

/** Public plugin import/images never inherit the application CookieJar or authenticated client. */
class DesktopJsPublicHttp(private val acceptHttpUrl: (String) -> Boolean = { true }) {
    private val gate = Any()
    private val slots = Semaphore(4)
    private var stopped = false
    private val active = mutableSetOf<Session>()
    private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(8, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).build()

    suspend fun downloadScript(rawUrl: String): String {
        validateDesktopJsImportUrlOrError(rawUrl)?.let { throw IllegalArgumentException(it) }
        val url = rawUrl.trim()
        return runBounded(1_048_576) { downloadJsRemotePlugin(url) }
    }

    suspend fun downloadImage(url: String): ByteArray {
        validateDesktopJsImportUrlOrError(url)?.let { throw IllegalArgumentException(it) }
        return runBounded(2_097_152) {
            val request = Request.Builder().url(url.trim()).header("User-Agent", "BiliPai").build()
            DesktopJsRemoteNetwork.okHttpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "JS 媒体图片加载失败: HTTP ${response.code}" }
                response.body.bytes()
            }
        }
    }

    private suspend fun <T> runBounded(limit: Int, block: () -> T): T = slots.withPermit {
        coroutineScope {
            val session = synchronized(gate) {
                check(!stopped) { "JS 公共网络服务已停止" }
                Session().also(active::add)
            }
            val boundedClient = client.newBuilder()
                .eventListenerFactory { call -> session.register(call); EventListener.NONE }
                .addInterceptor { chain ->
                    val request = chain.request()
                    require(request.url.scheme in setOf("http", "https") && acceptHttpUrl(request.url.toString())) { "JS 公共网络地址不被允许" }
                    chain.proceed(request).let { response ->
                        if (!response.isSuccessful) response
                        else try {
                            val body = response.body
                            require(body.contentLength() <= limit) { "JS 公共网络响应超过大小限制" }
                            val contentType = body.contentType()
                            val buffer = Buffer()
                            body.source().use { source ->
                                while (true) {
                                    val count = source.read(buffer, minOf(8192L, limit + 1L - buffer.size))
                                    if (count == -1L) break
                                    require(buffer.size <= limit) { "JS 公共网络响应超过大小限制" }
                                }
                            }
                            response.newBuilder().body(buffer.readByteArray().toResponseBody(contentType)).build()
                        } catch (failure: Throwable) { response.close(); throw failure }
                    }
                }
                // Check every redirected request as well as the initial URL.
                .addNetworkInterceptor { chain ->
                    require(chain.request().url.scheme in setOf("http", "https") && acceptHttpUrl(chain.request().url.toString())) { "JS 公共网络重定向地址不被允许" }
                    chain.proceed(chain.request())
                }.build()
            val work = async(Dispatchers.IO) {
                try { DesktopJsRemoteNetwork.withClient(boundedClient, block) }
                catch (failure: Exception) { currentCoroutineContext().ensureActive(); throw failure }
            }
            work.invokeOnCompletion {
                synchronized(gate) { active.remove(session) }
                session.finished.complete(Unit)
            }
            try { work.await() }
            finally {
                session.cancel()
                withContext(NonCancellable) { work.cancelAndJoin() }
            }
        }
    }

    suspend fun shutdownForRestore(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val old = synchronized(gate) { stopped = true; active.toList() }
        old.forEach(Session::cancel)
        old.forEach { it.finished.await() }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        check(client.dispatcher.executorService.awaitTermination(10, TimeUnit.SECONDS)) { "JS 公共网络线程未停止" }
    }

    private class Session {
        private val gate = Any()
        private var cancelled = false
        private val calls = mutableSetOf<Call>()
        val finished = CompletableDeferred<Unit>()
        fun register(call: Call) = synchronized(gate) {
            if (cancelled) call.cancel() else calls.add(call)
            Unit
        }
        fun cancel() {
            val old = synchronized(gate) { cancelled = true; calls.toList().also { calls.clear() } }
            old.forEach(Call::cancel)
        }
    }
}
