package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.PublicVideoNoteInfoResponse
import com.android.purebilibili.data.repository.ArticleDetailUiModel
import com.android.purebilibili.feature.article.parseArticleContentBlocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Windows binding for the v0.4.6 public-note fallback of the original ArticleRepository: a public video
 * note opened as an article has no article view, so the original app reads the public note instead.
 *
 * The original Article protocol stays the sole owner of the article request. This only runs after it
 * failed or produced no blocks, never replaces a non-empty article, and keeps the original failure when
 * the note cannot be read either. Cancellation and Root ownership are checked around the note request.
 */
internal class DesktopArticlePublicNoteFallback(
    private val publicNote: suspend (Long) -> PublicVideoNoteInfoResponse,
    private val assertOwned: () -> Unit,
) {
    suspend fun load(
        articleId: Long,
        primary: suspend (Long) -> Result<ArticleDetailUiModel>,
    ): Result<ArticleDetailUiModel> {
        // The original getArticleDetail rejects an invalid id before any request, so no note is read for it.
        if (articleId <= 0L) return primary(articleId)
        val first = primary(articleId)
        if (first.getOrNull()?.blocks?.isNotEmpty() == true) return first
        val note = fetchPublicNoteArticle(articleId) ?: return first
        return Result.success(note)
    }

    private suspend fun fetchPublicNoteArticle(articleId: Long): ArticleDetailUiModel? {
        currentCoroutineContext().ensureActive()
        assertOwned()
        val response = try {
            publicNote(articleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            assertOwned()
            return null
        }
        currentCoroutineContext().ensureActive()
        assertOwned()
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
            authorFace = note.author?.face.normalizedPublicNoteImageUrl().orEmpty(),
            publishTime = "",
            bannerUrl = null,
            blocks = blocks,
        )
    }
}

/** The same https upgrade the original article model applies to author and banner images. */
private fun String?.normalizedPublicNoteImageUrl(): String? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> value.replaceFirst("http://", "https://")
        else -> value
    }
}
