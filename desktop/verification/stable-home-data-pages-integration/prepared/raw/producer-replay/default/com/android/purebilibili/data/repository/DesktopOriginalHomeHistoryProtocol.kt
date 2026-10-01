package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.*

internal class DesktopOriginalHomeHistoryProtocol(private val api:BilibiliApi) {
    suspend fun getHistoryList(
        ps: Int = 30,
        max: Long = 0,
        viewAt: Long = 0,
        business: String? = null,
        type: String? = null
    ): Result<HistoryResult> {
        return withContext(Dispatchers.IO) {
            try {
                val cursorQuery = resolveHistoryCursorQuery(
                    max = max,
                    viewAt = viewAt,
                    business = business
                )
                val typeQuery = type?.trim()?.takeIf { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
                com.android.purebilibili.core.util.Logger.d(
                    "HistoryRepo",
                    "🔴 Fetching history: ps=$ps, max=${cursorQuery.max}, viewAt=${cursorQuery.viewAt}, business=${cursorQuery.business}, type=$typeQuery"
                )
                val response = api.getHistoryList(
                    ps = ps,
                    max = cursorQuery.max,
                    viewAt = cursorQuery.viewAt,
                    business = cursorQuery.business,
                    type = typeQuery
                )
                com.android.purebilibili.core.util.Logger.d("HistoryRepo", "🔴 Response code=${response.code}, items=${response.data?.list?.size ?: 0}")

                if (response.code == 0) {
                    val list = response.data?.list ?: emptyList()
                    val cursor = response.data?.cursor
                    com.android.purebilibili.core.util.Logger.d(
                        "HistoryRepo",
                        "🔴 Cursor: max=${cursor?.max}, view_at=${cursor?.view_at}, business=${cursor?.business}"
                    )
                    Result.success(HistoryResult(list, cursor))
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
                android.util.Log.e("HistoryRepo", " Error: ${e.message}")
                Result.failure(e)
            }
        }
    }
}
