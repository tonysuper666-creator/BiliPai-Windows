package com.bilipai.desktop.ui

import com.android.purebilibili.core.database.entity.BlockedUp
import com.bilipai.desktop.data.DesktopBlockedUpRepository
import com.bilipai.desktop.data.DesktopRepository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Same actual global block store and original local-first sync result. The request API/CSRF/
 * bootstrap are the Home binding's owner-tagged views over Root's one shared HTTP graph. */
internal fun desktopHomeBlockedPort(
    actual: DesktopBlockedUpRepository,
    repository: DesktopRepository,
    gate: DesktopHomeRetainedGate,
    requests: DesktopHomeRootRequestBinding,
): DesktopHomeBlockedRequests {
    // Preserve the existing DesktopBlockedUpRepository's one-shot POST body boundary.
    // This wraps the SAME owner-tagged shared Call.Factory; it creates no HTTP client,
    // CookieJar, dispatcher/pool or credentials. API DTO/serialization stays identical.
    val tagged = repository.ownedHomeCallFactory(gate.epoch, gate::owns)
    val mutationApi = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .callFactory(Call.Factory { request ->
            val body = request.body
            val once = if (request.method == "POST" && body != null)
                request.newBuilder().method("POST", object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                }).build() else request
            tagged.newCall(once)
        })
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }
            .asConverterFactory("application/json".toMediaType()))
        .build().create(BilibiliApi::class.java)
    return object : DesktopHomeBlockedRequests {
    override fun getAllBlockedUps() = actual.store.records
    override suspend fun blockUp(mid: Long, name: String, face: String) {
        currentCoroutineContext().ensureActive()
        if (!gate.commit { actual.store.upsert(BlockedUp(mid = mid, name = name, face = face)) })
            throw CancellationException("Home local block owner retired")
    }
    override suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String) =
        actual.blockUpWithBilibiliSync(mid, name, face, expectedSessionEpoch = gate.epoch,
            stillOwned = gate::owns, commitLocal = gate::commit,
            ownedApi = mutationApi, ownedCsrf = requests.environment.csrf,
            ensureOwnedSession = requests.environment.ensureBuvid3FromSpi)
    }
}
