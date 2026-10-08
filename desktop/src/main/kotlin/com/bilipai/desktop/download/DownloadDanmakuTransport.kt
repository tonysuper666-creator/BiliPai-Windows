package com.bilipai.desktop.download

import com.android.purebilibili.core.network.BilibiliApi
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.danmaku.ApiDesktopDanmakuSource
import com.bilipai.desktop.danmaku.BoundedDanmakuBody
import com.bilipai.desktop.danmaku.DesktopSpecialSourceLimits
import com.bilipai.desktop.player.DesktopPlaybackPublication
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import retrofit2.Invocation
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable carriers of the existing task/Store authorization; no transport or cache state. */
internal object DownloadDanmakuTransport {
    internal class TaskOwner(
        val source: PlaybackSource,
        val publication: DesktopPlaybackPublication,
        val callerJob: Job,
        val stillOwned: () -> Boolean,
        private val taskAdmission: ((() -> Unit) -> Boolean),
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<TaskOwner>
        fun <T> admit(block: () -> T): T = publication.admit(source, stillOwned) {
            callerJob.ensureActive()
            var result: Result<T>? = null
            if (!taskAdmission {
                callerJob.ensureActive()
                result = runCatching(block)
            }) throw CancellationException("离线弹幕下载任务已退役")
            requireNotNull(result).getOrThrow()
        }
        fun assertCurrent() = admit { Unit }
    }

    internal class Binding(
        val owner: TaskOwner,
        val repository: DesktopRepository,
        val cacheReceipt: DesktopPlaybackAuthorizationReceipt,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Binding>
        suspend fun <T> admit(block: () -> T): T {
            val context = currentCoroutineContext()
            context.ensureActive()
            return owner.admit { context.ensureActive(); block() }
        }
        suspend fun assertCurrent() = admit { Unit }
        suspend fun api(): BilibiliApi = requestApi(this, currentCoroutineContext().job)
    }

    suspend fun <T> withTask(source: PlaybackSource, publication: DesktopPlaybackPublication,
        callerJob: Job, stillOwned: () -> Boolean, taskAdmission: ((() -> Unit) -> Boolean),
        block: suspend () -> T): T {
        val owner = TaskOwner(source, publication, callerJob, stillOwned, taskAdmission)
        owner.assertCurrent()
        return withContext(owner) { block() }
    }

    suspend fun taskOwner(): TaskOwner {
        val context = currentCoroutineContext()
        context.ensureActive()
        return (context[TaskOwner] ?: throw CancellationException("离线弹幕缺少原下载任务归属"))
            .also { it.assertCurrent() }
    }

    suspend fun currentBinding(): Binding {
        val context = currentCoroutineContext()
        context.ensureActive()
        return (context[Binding] ?: throw CancellationException("离线弹幕缺少捕获的请求绑定"))
            .also { it.assertCurrent() }
    }

    suspend fun <T> withApi(repository: DesktopRepository, block: suspend () -> T): T {
        val owner = taskOwner()
        val receipt = owner.source.authorizationReceipt
            ?: throw CancellationException("离线弹幕缺少完整播放授权")
        return withContext(Binding(owner, repository, receipt)) { block() }
    }

    private fun requestApi(binding: Binding, requestJob: Job): BilibiliApi {
        val owner = binding.owner
        requestJob.ensureActive()
        owner.assertCurrent()
        // Same Repository client, dispatcher/pool and captured Store CookieJar. A facade, not newBuilder().
        // Queue admission keeps the original root worker; body cancellation follows this actual child request.
        val calls = owner.publication.calls(binding.repository.httpClient, owner.source, owner.stillOwned, requestJob)
        val bounded = Call.Factory { request ->
            val method = request.tag(Invocation::class.java)?.method()?.name
            val special = method == "getDanmakuSpecialDm"
            val limit = when (method) {
                "getDanmakuView" -> 4L * 1024 * 1024
                "getDanmakuSpecialDm" -> DesktopSpecialSourceLimits.MAX_FILE_BYTES
                "getDanmakuSeg", "getDanmakuXml" -> null
                else -> throw IOException("离线弹幕请求不属于原下载接口")
            }
            val policy = DesktopDownloadDanmakuRequestPolicy(owner, special, limit)
            policy.validate(request)
            calls.newCall(request.newBuilder().tag(DesktopDownloadDanmakuRequestPolicy::class.java, policy).build())
        }
        return Retrofit.Builder().baseUrl("https://api.bilibili.com/").callFactory(bounded)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)
    }
}

/** Existing Repository network hook applies this tag on every redirect, before its body converter reads. */
internal class DesktopDownloadDanmakuRequestPolicy(
    private val owner: DownloadDanmakuTransport.TaskOwner,
    private val special: Boolean,
    private val limit: Long?,
) {
    private fun assertCurrent() {
        try { owner.assertCurrent() }
        catch (retired: CancellationException) { throw IOException("离线弹幕请求归属已退役", retired) }
    }
    fun validate(request: Request) {
        assertCurrent()
        if (special) {
            if (!request.url.isHttps) throw IOException("离线特殊弹幕重定向必须使用 HTTPS")
            ApiDesktopDanmakuSource.trustedSpecialUrl(request.url.toString())
        }
    }
    fun bind(response: Response): Response {
        try {
            assertCurrent()
            val original = response.body
            if (limit != null && original.contentLength() > limit)
                throw IOException("离线弹幕响应超过原字节限制")
            val bounded = if (limit == null) original else BoundedDanmakuBody(original, limit)
            val input = object : ForwardingSource(bounded.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    assertCurrent()
                    return super.read(sink, byteCount).also { assertCurrent() }
                }
            }.buffer()
            val guarded = object : ResponseBody() {
                override fun contentType() = bounded.contentType()
                override fun contentLength() = bounded.contentLength()
                override fun source() = input
            }
            return response.newBuilder().body(guarded).build()
        } catch (failure: Throwable) {
            try { response.close() }
            catch (closeFailure: Throwable) {
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }
}
