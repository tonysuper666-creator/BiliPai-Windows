package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import com.android.purebilibili.feature.article.buildArticleHistoryReportFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The original article-history form, with the Windows source/account lifetime guard. */
internal class DesktopArticleHistoryReporter(private val repository: DesktopRepository,
    private val privacy: DesktopSearchPreferences) {
    suspend fun report(articleId: Long, expectedSessionEpoch: Long, expectedAccountMid: Long?): Boolean =
        withContext(Dispatchers.IO) {
            reportDesktopArticleHistory(articleId, privacy::isPrivacyModeEnabledSync, expectedSessionEpoch,
                expectedAccountMid, { repository.sessionEpoch }, { repository.account.value?.mid },
                repository::requireCsrf, HistoryRefreshBus::notifyChanged) { fields ->
                // Runs after platform request policy, before BridgeInterceptor obtains account cookies.
                val client = repository.httpClient.newBuilder().retryOnConnectionFailure(false).addInterceptor { chain ->
                    if (repository.sessionEpoch != expectedSessionEpoch || repository.account.value?.mid != expectedAccountMid ||
                        privacy.isPrivacyModeEnabledSync()) throw BiliApiException(-101, "阅读会话已变化")
                    chain.proceed(chain.request())
                }.build()
                val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
                    .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                    .build().create(BilibiliApi::class.java)
                api.reportHistory(fields).code
            }
        }
}

internal suspend fun reportDesktopArticleHistory(articleId: Long, privacyEnabled: () -> Boolean,
    expectedEpoch: Long, expectedAccountMid: Long?, epoch: () -> Long, mid: () -> Long?, csrf: () -> String,
    onReported: () -> Unit = {}, send: suspend (Map<String, String>) -> Int): Boolean = try {
    currentCoroutineContext().ensureActive()
    if (epoch() != expectedEpoch) false
    else if (privacyEnabled()) true // HistoryRepository's successful privacy no-op.
    else if (expectedAccountMid == null || expectedAccountMid <= 0) true // Guest has no authorized article-history credentials.
    else if (mid() != expectedAccountMid) false
    else {
        val fields = buildArticleHistoryReportFields(articleId, csrf())
        when {
            epoch() != expectedEpoch || mid() != expectedAccountMid -> false
            privacyEnabled() -> true
            fields == null -> true // The original builder ignores invalid IDs / blank CSRF.
            else -> {
                currentCoroutineContext().ensureActive()
                val code = send(fields)
                currentCoroutineContext().ensureActive()
                if (code == 0 && epoch() == expectedEpoch && mid() == expectedAccountMid) {
                    onReported(); true
                } else false
            }
        }
    }
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { false }

/** A failed optional history write must not discard successfully loaded article content. */
internal suspend fun <T> articleContentWithBestEffortHistory(content: T, report: suspend () -> Boolean): T {
    try { report() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* Keep the original ArticleRepository best-effort behavior. */ }
    currentCoroutineContext().ensureActive()
    return content
}
