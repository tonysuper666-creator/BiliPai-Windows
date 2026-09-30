// GENERATED from app/src/main/java/com/android/purebilibili/data/repository/DynamicRepository.kt; do not edit.
// LF-normalized SHA-256: 890574b7e97781458fcd6d393068d54c30864270ed914a33683d2815b3f0d63b
package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
internal class DesktopOriginalDynamicUserRepository(
 private val requestPage:suspend (Map<String,String>)->DynamicFeedResponse,
 private val stillOwned:()->Boolean={true},
) {
 private val userFeedPagination=DynamicUserPaginationRegistry()
 fun hasMore(uid:Long)=userFeedPagination.hasMore(uid)
 private suspend fun getPage(params:Map<String,String>):DynamicFeedResponse {
  currentCoroutineContext().ensureActive();if(!stillOwned())throw CancellationException("Dynamic source retired")
  val response=requestPage(params)
  currentCoroutineContext().ensureActive();if(!stillOwned())throw CancellationException("Dynamic source retired")
  return response
 }
 suspend fun getUserDynamicFeed(hostMid: Long, refresh: Boolean = false): Result<List<DynamicItem>> = withContext(Dispatchers.IO) {
     try {
         if (refresh) {
             userFeedPagination.reset(hostMid)
         }

         if (!userFeedPagination.hasMore(hostMid) && !refresh) {
             return@withContext Result.success(emptyList())
         }

         val visibleItems = mutableListOf<DynamicItem>()
         var pagesFetched = 0
         while (true) {
             val previousOffset = userFeedPagination.offset(hostMid)
             val response = fetchDynamicFeedPageWithRetry {
                 getPage(
                     params = buildSelectedUserDynamicFeedParams(
                         hostMid = hostMid,
                         offset = previousOffset
                     )
                 )
             }.getOrElse { error ->
                 return@withContext Result.failure(error)
             }

             val data = response.data
             if (data == null) {
                 userFeedPagination.update(
                     hostMid = hostMid,
                     offset = previousOffset,
                     hasMore = false
                 )
                 break
             }

             // 更新分页状态
             userFeedPagination.update(
                 hostMid = hostMid,
                 offset = data.offset,
                 hasMore = data.has_more
             )

             visibleItems += data.items
             pagesFetched += 1

             if (!shouldContinueDynamicFetchAfterFilter(
                     accumulatedVisibleCount = visibleItems.size,
                     hasMore = data.has_more,
                     previousOffset = previousOffset,
                     nextOffset = data.offset,
                     pagesFetched = pagesFetched
                 )
             ) {
                 break
             }
         }

         Result.success(visibleItems)
     } catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled } catch (e: Exception) {
         e.printStackTrace()
         Result.failure(e)
     }
 }

 private suspend fun fetchDynamicFeedPageWithRetry(
     request: suspend () -> DynamicFeedResponse
 ): Result<DynamicFeedResponse> {
     var lastError: Throwable? = null
     for (attempt in 1..DYNAMIC_FETCH_MAX_ATTEMPTS) {
         try {
             val response = request()
             if (response.code == 0) {
                 return Result.success(response)
             }
             val shouldRetry = attempt < DYNAMIC_FETCH_MAX_ATTEMPTS &&
                 isRetryableDynamicApiError(response.code, response.message)
             if (shouldRetry) {
                 delay(resolveDynamicRetryDelayMs(attempt))
                 continue
             }
             val message = resolveDynamicFriendlyErrorMessage(response.code, response.message)
             return Result.failure(Exception(message))
         } catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
             lastError = error
             val shouldRetry = attempt < DYNAMIC_FETCH_MAX_ATTEMPTS &&
                 isRetryableDynamicException(error)
             if (shouldRetry) {
                 delay(resolveDynamicRetryDelayMs(attempt))
                 continue
             }
             val message = resolveDynamicFriendlyErrorMessage(code = -1, message = error.message.orEmpty())
             return Result.failure(Exception(message, error))
         }
     }
     val message = resolveDynamicFriendlyErrorMessage(code = -1, message = lastError?.message.orEmpty())
     return Result.failure(Exception(message, lastError))
 }

 internal fun buildSelectedUserDynamicFeedParams(
     hostMid: Long,
     offset: String
 ): Map<String, String> {
     return mapOf(
         "host_mid" to hostMid.toString(),
         "offset" to offset,
         "features" to com.android.purebilibili.core.network.SPACE_DYNAMIC_FEATURES,
         "timezone_offset" to "-480",
         "platform" to "web",
         "web_location" to "333.1387"
     )
 }
}

internal class DynamicUserPaginationRegistry {
    private val stateByUser = mutableMapOf<Long, DynamicPaginationState>()

    fun reset(hostMid: Long) {
        stateByUser[hostMid] = DynamicPaginationState()
    }

    fun resetAll() {
        stateByUser.clear()
    }

    fun update(hostMid: Long, offset: String, hasMore: Boolean) {
        stateByUser[hostMid] = DynamicPaginationState(offset = offset, hasMore = hasMore)
    }

    fun offset(hostMid: Long): String {
        return stateByUser[hostMid]?.offset.orEmpty()
    }

    fun hasMore(hostMid: Long): Boolean {
        return stateByUser[hostMid]?.hasMore ?: true
    }
}
