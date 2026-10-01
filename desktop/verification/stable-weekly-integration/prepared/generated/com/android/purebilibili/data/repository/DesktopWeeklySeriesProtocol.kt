// GENERATED from app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt; do not edit.
// LF-normalized SHA-256: 1aa112f16f2ccecaf3d26e00e6092e24d96ac6020c7624eff32121dd5199a496
package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
internal class DesktopWeeklySeriesProtocol(private val api: BilibiliApi) {
    suspend fun getWeeklyPeriods(): Result<List<PopularSeriesPeriod>> = withContext(Dispatchers.IO) {
            try {
                val response = api.getWeeklySeriesList()
                if (response.code != 0) {
                    Result.failure(Exception(response.message.ifBlank { "每周必看期数加载失败(${response.code})" }))
                } else {
                    Result.success(response.data?.list.orEmpty().filter { it.number > 0 }
                        .distinctBy { it.number }.sortedByDescending { it.number })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getWeeklyPeriod(number: Int): Result<PopularSeriesOneData> = withContext(Dispatchers.IO) {
            try {
                val response = api.getWeeklySeriesVideos(number)
                if (response.code != 0 || response.data == null) {
                    Result.failure(Exception(response.message.ifBlank { "第${number}期加载失败(${response.code})" }))
                } else {
                    Result.success(response.data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
