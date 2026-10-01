// GENERATED selected original Article view/opus/consumed title+blocks.
// Original: app/src/main/java/com/android/purebilibili/data/repository/ArticleRepository.kt
// Original LF SHA-256: a04d5aaf29a8416304bc6198724247f86719b9d0e6984b6098d0a6c4732d5445
package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.*
import kotlinx.coroutines.*
internal class DesktopDynamicDetailArticleProtocol(
    private val articleApi: ArticleApi,
    private val dynamicApi: DynamicApi,
    private val signDetailParams: suspend (Map<String,String>) -> Map<String,String>,
    private val assertOwner: () -> Unit,
    private val onArticleViewed: (suspend (Long) -> Unit)?,
) {
    private suspend inline fun <T> ownedCall(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); assertOwner()
        return block().also { currentCoroutineContext().ensureActive(); assertOwner() }
    }
    private inline fun <T> ownedCatching(block: () -> T): Result<T> = try {
        assertOwner(); Result.success(block().also { assertOwner() })
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { assertOwner(); Result.failure(failure) }
    suspend fun getArticleDetail(articleId: Long): Result<Pair<String,List<ArticleContentBlock>>> = withContext(Dispatchers.IO) {
        if (articleId <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("Invalid article id: $articleId"))
        }

        ownedCatching {
            val response = ownedCall { articleApi.getArticleView(
                signDetailParams(
                    mapOf(
                        "id" to articleId.toString(),
                        "gaia_source" to "main_web",
                        "web_location" to "333.976"
                    )
                )
            ) }

            if (response.code != 0 || response.data == null) {
                throw IllegalStateException(response.message.ifBlank { "Article detail unavailable" })
            }

            val fromView = response.data.toDetailTitleAndBlocks()
            val opusBlocks = fetchOpusArticleBlocks(response.data.dynamicId)
            val merged = if (opusBlocks.isEmpty()) {
                fromView
            } else {
                fromView.first to selectRicherArticleBlocks(fromView.second, opusBlocks)
            }
            ownedCatching { onArticleViewed?.let { callback -> ownedCall { callback(response.data.id) } } }
            merged
        }
    }
    private suspend fun fetchOpusArticleBlocks(dynamicId: String): List<ArticleContentBlock> {
        val opusId = dynamicId.trim()
        if (opusId.isEmpty()) return emptyList()
        return ownedCatching {
            val response = ownedCall { dynamicApi.getOpusDetail(
                signDetailParams(
                    mapOf(
                        "id" to opusId,
                        "timezone_offset" to "-480",
                        "features" to OPUS_DETAIL_FEATURES
                    )
                )
            ) }
            if (response.code != 0) return@ownedCatching emptyList()
            opusContentBlocksToArticleBlocks(
                response.data?.item?.modules?.module_dynamic?.major?.opus?.contentBlocks.orEmpty()
            )
        }.getOrDefault(emptyList())
    }
    private fun ArticleViewData.toDetailTitleAndBlocks():Pair<String,List<ArticleContentBlock>> {
        val parsedBlocks = parseArticleContentBlocks(
            structuredParagraphs = opus?.paragraphs().orEmpty(),
            htmlContent = content,
            ops = ops
        )

        val resolvedTitle = title
            .ifBlank { opus?.title.orEmpty() }
            .ifBlank { summary }
            .ifBlank { "专栏详情" }
        return resolvedTitle to parsedBlocks
    }
}
