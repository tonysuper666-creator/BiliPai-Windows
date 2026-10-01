// GENERATED selected original owned detail protocol; task-only.
// Original: app/src/main/java/com/android/purebilibili/data/repository/DynamicRepository.kt
// Original LF SHA-256: 890574b7e97781458fcd6d393068d54c30864270ed914a33683d2815b3f0d63b
package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.*
import kotlinx.coroutines.*
internal class DesktopDynamicDetailProtocol(
    private val dynamicApi: DynamicApi,
    private val signDetailParams: suspend (Map<String,String>) -> Map<String,String>,
    private val fetchArticle: suspend (Long) -> Pair<String,List<ArticleContentBlock>>?,
    private val assertOwner: () -> Unit,
) {
    private suspend inline fun <T> ownedCall(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); assertOwner()
        return block().also { currentCoroutineContext().ensureActive(); assertOwner() }
    }
    private inline fun <T> ownedCatching(block: () -> T): Result<T> = try {
        assertOwner(); Result.success(block().also { assertOwner() })
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { assertOwner(); Result.failure(failure) }
    suspend fun getDynamicDetail(dynamicId: String, seedItem: DynamicItem?): Result<DynamicItem> = withContext(Dispatchers.IO) {
        try {
            val cleanedId = dynamicId.trim()
            if (cleanedId.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("dynamicId 不能为空"))
            }

            val seed = seedItem
            val candidates = mutableListOf<DynamicItem>()

            val webItem = fetchWebDetailItem(id = cleanedId)
            webItem?.let(candidates::add)

            var opusFallbackCvId: Long? = null
            if (shouldRequestOpusDetailForDynamicDetail(webItem = webItem, seedItem = seed)) {
                val opusFetch = fetchOpusDetail(cleanedId)
                opusFetch.item?.let(candidates::add)
                opusFallbackCvId = opusFetch.fallbackCvId
            }

            val preferredAfterWeb = resolvePreferredDynamicDetailItem(candidates)
            if (preferredAfterWeb != null &&
                shouldFetchStandardDetailForPlainTextDynamic(preferredAfterWeb)
            ) {
                fetchDesktopDetailItem(cleanedId)?.let { desktopItem ->
                    candidates += mergeDynamicDetailWithLongerDesc(
                        desktopItem = preferredAfterWeb,
                        standardItem = desktopItem,
                    )
                }
            }

            if (preferredAfterWeb == null || shouldFallbackForDynamicDetail(preferredAfterWeb)) {
                fetchDesktopDetailItem(cleanedId)?.let(candidates::add)
            }

            val rid = seed?.basic?.rid_str.orEmpty()
            if (shouldFetchDynamicDetailByRid(resolvePreferredDynamicDetailItem(candidates), rid)) {
                fetchWebDetailItem(id = null, rid = rid, type = 2)?.let(candidates::add)
            }

            seed?.let(candidates::add)
            val resolved = resolvePreferredDynamicDetailItem(candidates)
            if (resolved != null) {
                var merged = mergeRicherOpusDetailContent(resolved, candidates)
                val opusBlocks = merged.modules.module_dynamic?.major?.opus?.contentBlocks.orEmpty()
                val cvId = resolveOpusArticleFallbackCvId(
                    fallbackId = opusFallbackCvId,
                    commentType = merged.basic?.comment_type ?: 0,
                    commentIdStr = merged.basic?.comment_id_str.orEmpty()
                )
                if (cvId != null && shouldFetchArticleFallbackForOpus(opusBlocks, opusFallbackCvId)) {
                    ownedCall { fetchArticle(cvId) }?.let { article ->
                        merged = mergeArticleDetailIntoOpus(
                            base = merged,
                            title = article.first,
                            blocks = article.second
                        )
                    }
                }
                return@withContext Result.success(
                    mergeDynamicDetailInteractionMetadata(
                        detailItem = merged,
                        seedItem = seed
                    )
                )
            }

            Result.failure(Exception("动态详情为空"))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive(); assertOwner()
            
            Result.failure(e)
        }
    }
    private data class OpusDetailFetch(
        val item: DynamicItem?,
        val fallbackCvId: Long?
    )
    private suspend fun fetchWebDetailItem(
        id: String? = null,
        rid: String? = null,
        type: Int? = null
    ): DynamicItem? {
        return ownedCatching {
            val response = ownedCall { dynamicApi.getDynamicDetail(
                id = id,
                rid = rid,
                type = type
            ) }
            response.data?.item?.takeIf { response.code == 0 }
        }.getOrNull()
    }

    private suspend fun fetchOpusDetail(dynamicId: String): OpusDetailFetch {
        return ownedCatching {
            val response = ownedCall { dynamicApi.getOpusDetail(
                signDetailParams(
                    mapOf(
                        "id" to dynamicId,
                        "timezone_offset" to "-480",
                        "features" to OPUS_DETAIL_FEATURES
                    )
                )
            ) }
            if (response.code != 0) {
                return@ownedCatching OpusDetailFetch(item = null, fallbackCvId = null)
            }
            OpusDetailFetch(
                item = response.data?.item,
                fallbackCvId = response.data?.fallback?.id?.takeIf { it > 0L }
            )
        }.getOrElse { error ->

            OpusDetailFetch(item = null, fallbackCvId = null)
        }
    }

    private suspend fun fetchDesktopDetailItem(dynamicId: String): DynamicItem? {
        return ownedCatching {
            val response = ownedCall { dynamicApi.getDynamicDetailFallback(id = dynamicId) }
            response.data?.item?.takeIf { response.code == 0 }
        }.getOrNull()
    }
}
