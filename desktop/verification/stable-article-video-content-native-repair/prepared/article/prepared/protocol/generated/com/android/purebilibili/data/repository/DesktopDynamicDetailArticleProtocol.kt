// GENERATED complete original Article detail algorithm/model; existing Pair consumer is a projection.
// Original: app/src/main/java/com/android/purebilibili/data/repository/ArticleRepository.kt
// Original LF SHA-256: a04d5aaf29a8416304bc6198724247f86719b9d0e6984b6098d0a6c4732d5445
package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.*
import kotlinx.coroutines.*
import com.android.purebilibili.core.util.FormatUtils
data class ArticleDetailUiModel(
    val articleId: Long,
    val title: String,
    val summary: String,
    val authorName: String,
    val authorMid: Long,
    val authorFace: String,
    val publishTime: String,
    val bannerUrl: String?,
    val blocks: List<ArticleContentBlock>
)
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
    suspend fun getArticleDetail(articleId:Long):Result<Pair<String,List<ArticleContentBlock>>> = getArticleUiDetail(articleId).map { it.title to it.blocks }
    suspend fun getArticleUiDetail(articleId: Long): Result<ArticleDetailUiModel> = withContext(Dispatchers.IO) {
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

            val fromView = response.data.toUiModel()
            val opusBlocks = fetchOpusArticleBlocks(response.data.dynamicId)
            val merged = if (opusBlocks.isEmpty()) {
                fromView
            } else {
                fromView.copy(blocks = selectRicherArticleBlocks(fromView.blocks, opusBlocks))
            }
            ownedCatching { onArticleViewed?.let { callback -> ownedCall { callback(merged.articleId) } } }
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
    private fun ArticleViewData.toUiModel(): ArticleDetailUiModel {
        val parsedBlocks = parseArticleContentBlocks(
            structuredParagraphs = opus?.paragraphs().orEmpty(),
            htmlContent = content,
            ops = ops
        )

        val resolvedTitle = title
            .ifBlank { opus?.title.orEmpty() }
            .ifBlank { summary }
            .ifBlank { "专栏详情" }
        val resolvedSummary = summary.ifBlank {
            parsedBlocks.filterIsInstance<ArticleContentBlock.Paragraph>()
                .firstOrNull()
                ?.text
                .orEmpty()
        }
        val resolvedBanner = listOfNotNull(
            bannerUrl.normalizeImageUrl(),
            originImageUrls.firstOrNull()?.normalizeImageUrl(),
            imageUrls.firstOrNull()?.normalizeImageUrl()
        ).firstOrNull()

        return ArticleDetailUiModel(
            articleId = id,
            title = resolvedTitle,
            summary = resolvedSummary,
            authorName = author?.name.orEmpty(),
            authorMid = author?.mid ?: 0L,
            authorFace = author?.face.normalizeImageUrl().orEmpty(),
            publishTime = FormatUtils.formatPrecisePublishTime(publishTime),
            bannerUrl = resolvedBanner,
            blocks = parsedBlocks
        )
    }
    private fun String?.normalizeImageUrl(): String? {
        val value = this?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") -> value.replaceFirst("http://", "https://")
            else -> value
        }
    }
}
