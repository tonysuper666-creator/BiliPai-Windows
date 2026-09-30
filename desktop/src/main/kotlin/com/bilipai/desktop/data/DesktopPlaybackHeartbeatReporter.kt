package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.buildPlaybackHeartbeatFields
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

internal class DesktopPlaybackHeartbeatReporter(private val repository: DesktopRepository,
    private val privacy: DesktopSearchPreferences) {
    suspend fun report(bvid: String, cid: Long, playedTimeSec: Long, realPlayedTimeSec: Long, startTsSec: Long,
        aid: Long, epid: Long, sid: Long, videoType: Int, subType: Int?, expectedSessionEpoch: Long): Boolean = withContext(Dispatchers.IO) {
        reportDesktopPlaybackHeartbeat(privacy::isPrivacyModeEnabledSync, expectedSessionEpoch,
            { repository.sessionEpoch }, { repository.account.value?.mid }, repository::requireCsrf,
            bvid, cid, playedTimeSec, realPlayedTimeSec, startTsSec, aid, epid, sid, videoType, subType,
            onReported = HistoryRefreshBus::notifyChanged) { fields ->
            // This guard runs immediately before BridgeInterceptor/transport, after the normal
            // repository policy interceptor. An old source cannot acquire a new account's cookie.
            val client = repository.httpClient.newBuilder().retryOnConnectionFailure(false).addInterceptor { chain ->
                if (repository.sessionEpoch != expectedSessionEpoch || privacy.isPrivacyModeEnabledSync())
                    throw BiliApiException(-101, "播放会话已变化")
                chain.proceed(chain.request())
            }.build()
            val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                .build().create(BilibiliApi::class.java)
            api.reportHeartbeat(fields).code
        }
    }
}

internal suspend fun reportDesktopPlaybackHeartbeat(privacyEnabled: () -> Boolean, expectedEpoch: Long,
    epoch: () -> Long, mid: () -> Long?, csrf: () -> String, bvid: String, cid: Long, playedTimeSec: Long,
    realPlayedTimeSec: Long, startTsSec: Long, aid: Long = 0, epid: Long = 0, sid: Long = 0,
    videoType: Int = 3, subType: Int? = null, onReported: () -> Unit = {}, send: suspend (Map<String, String>) -> Int): Boolean {
    return try {
        if (epoch() != expectedEpoch) return false
        if (privacyEnabled()) return true // Original privacy policy: successful no-op.
        val accountMid = mid()?.takeIf { it > 0 } ?: return false
        val token = csrf().takeIf { it.isNotBlank() } ?: return false
        val fields = buildPlaybackHeartbeatFields(bvid, aid, cid, epid, sid, accountMid, playedTimeSec,
            realPlayedTimeSec, startTsSec, token, videoType, subType)
        if (epoch() != expectedEpoch || mid() != accountMid) return false
        if (privacyEnabled()) return true
        val code = send(fields)
        if (code == 0 && epoch() == expectedEpoch) { onReported(); true } else false
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}
