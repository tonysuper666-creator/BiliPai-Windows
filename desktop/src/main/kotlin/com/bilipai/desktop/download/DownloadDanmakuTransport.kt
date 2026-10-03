package com.bilipai.desktop.download

import com.android.purebilibili.core.network.BilibiliApi
import com.bilipai.desktop.data.DesktopRepository
import com.android.purebilibili.danmaku.parser.DanmakuProto
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Invocation
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap

/** Platform-only binding for the verbatim upstream offline danmaku repository. */
internal object DownloadDanmakuTransport {
    @Volatile private var configured: BilibiliApi? = null
    private val metadataCounts = ConcurrentHashMap<Long, Int>()
    val api: BilibiliApi get() = configured ?: error("离线弹幕 HTTP 会话尚未初始化")
    fun metadataSegmentCount(cid: Long): Int? = metadataCounts[cid]
    fun resetMetadata(cid: Long) { metadataCounts.remove(cid) }

    fun configure(repository: DesktopRepository) {
        val client = repository.httpClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val invocation = request.tag(Invocation::class.java)
            if (!response.isSuccessful || invocation?.method()?.name != "getDanmakuView") return@addInterceptor response
            val oidIndex = invocation.method().parameterAnnotations.indexOfFirst { annotations -> annotations.any { it is Query && it.value == "oid" } }
            val cid = invocation.arguments().getOrNull(oidIndex) as? Long ?: return@addInterceptor response
            val body = response.body
            val bytes = body.bytes()
            val total = runCatching { DanmakuProto.parseWebViewReply(bytes).dmSge?.total?.toInt() }.getOrNull()
            if (total != null && total > 0) metadataCounts[cid] = total else metadataCounts.remove(cid)
            response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        }.build()
        configured = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)
    }
}
