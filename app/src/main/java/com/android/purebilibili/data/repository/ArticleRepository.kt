package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.network.OPUS_DETAIL_FEATURES
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.ArticleViewData
import com.android.purebilibili.feature.article.ArticleContentBlock
import com.android.purebilibili.feature.article.opusContentBlocksToArticleBlocks
import com.android.purebilibili.feature.article.parseArticleContentBlocks
import com.android.purebilibili.feature.article.selectRicherArticleBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

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

object ArticleRepository {
    private val articleApi = NetworkModule.articleApi
    private val navApi = NetworkModule.api

    suspend fun getArticleDetail(articleId: Long): Result<ArticleDetailUiModel> = withContext(Dispatchers.IO) {
        if (articleId <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("Invalid article id: $articleId"))
        }

        runCatching {
            val response = try {
                articleApi.getArticleView(
                    signWithWbi(
                        mapOf(
                            "id" to articleId.toString(),
                            "gaia_source" to "main_web",
                            "web_location" to "333.976"
                        )
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@runCatching fetchPublicNoteArticle(articleId) ?: throw error
            }

            if (response.code != 0 || response.data == null) {
                fetchPublicNoteArticle(articleId)?.let { return@runCatching it }
                throw IllegalStateException(response.message.ifBlank { "Article detail unavailable" })
            }

            val data = requireNotNull(response.data)
            val fromView = data.toUiModel()
            val opusBlocks = fetchOpusArticleBlocks(data.dynamicId)
            var merged = if (opusBlocks.isEmpty()) {
                fromView
            } else {
                fromView.copy(blocks = selectRicherArticleBlocks(fromView.blocks, opusBlocks))
            }
            if (merged.blocks.isEmpty()) {
                fetchPublicNoteArticle(articleId)?.let { merged = it }
            }
            runCatching { HistoryRepository.reportArticleView(merged.articleId) }
            merged
        }
    }

    private suspend fun fetchPublicNoteArticle(articleId: Long): ArticleDetailUiModel? {
        val response = try {
            navApi.getPublicVideoNoteInfo(articleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (response.code != 0) return null
        val note = response.data ?: return null
        val blocks = parseArticleContentBlocks(emptyList(), note.content)
        if (blocks.isEmpty()) return null
        return ArticleDetailUiModel(
            articleId = note.cvid.takeIf { it > 0L } ?: articleId,
            title = note.title.ifBlank { "公开笔记" },
            summary = note.summary,
            authorName = note.author?.name.orEmpty(),
            authorMid = note.author?.mid ?: 0L,
            authorFace = note.author?.face.normalizeImageUrl().orEmpty(),
            publishTime = "",
            bannerUrl = null,
            blocks = blocks,
        )
    }

    private suspend fun fetchOpusArticleBlocks(dynamicId: String): List<ArticleContentBlock> {
        val opusId = dynamicId.trim()
        if (opusId.isEmpty()) return emptyList()
        return runCatching {
            val response = NetworkModule.dynamicApi.getOpusDetail(
                signWithWbi(
                    mapOf(
                        "id" to opusId,
                        "timezone_offset" to "-480",
                        "features" to OPUS_DETAIL_FEATURES
                    )
                )
            )
            if (response.code != 0) return@runCatching emptyList()
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

    private suspend fun signWithWbi(params: Map<String, String>): Map<String, String> {
        return try {
            val navResp = navApi.getNavInfo()
            val wbiImg = navResp.data?.wbi_img
            val imgKey = wbiImg?.img_url?.substringAfterLast("/")?.substringBefore(".") ?: ""
            val subKey = wbiImg?.sub_url?.substringAfterLast("/")?.substringBefore(".") ?: ""
            if (imgKey.isNotEmpty() && subKey.isNotEmpty()) {
                WbiUtils.sign(params, imgKey, subKey)
            } else {
                params
            }
        } catch (_: Exception) {
            params
        }
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
