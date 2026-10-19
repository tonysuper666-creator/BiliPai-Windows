package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.PublicVideoNoteInfoData
import com.android.purebilibili.data.model.response.PublicVideoNoteInfoResponse
import com.android.purebilibili.data.model.response.VideoNoteAuthor
import com.android.purebilibili.data.repository.ArticleDetailUiModel
import com.android.purebilibili.feature.article.ArticleContentBlock
import com.android.purebilibili.feature.article.parseArticleContentBlocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Windows binding of the v0.4.6 public-note fallback and the top-level Quill array parser it relies on. */
class DesktopArticlePublicNoteFallbackTest {
    private val noteContent = """[{"insert":"公开笔记正文\n"}]"""

    private fun article(blocks: List<ArticleContentBlock>) = ArticleDetailUiModel(
        articleId = 7L, title = "专栏", summary = "", authorName = "", authorMid = 0L, authorFace = "",
        publishTime = "", bannerUrl = null, blocks = blocks,
    )

    private fun note(content: String = noteContent, code: Int = 0, cvid: Long = 0L, withData: Boolean = true) =
        PublicVideoNoteInfoResponse(
            code = code,
            data = if (!withData) null else PublicVideoNoteInfoData(
                cvid = cvid, title = "", summary = "摘要", content = content,
                author = VideoNoteAuthor(mid = 9L, name = "作者", face = "//example.test/face.png"),
            ),
        )

    private fun fallback(calls: MutableList<Long>, reply: (Long) -> PublicVideoNoteInfoResponse) =
        DesktopArticlePublicNoteFallback({ id -> calls += id; reply(id) }, {})

    private val empty = article(emptyList())
    private val full = article(listOf(ArticleContentBlock.Paragraph("正文")))

    @Test
    fun topLevelQuillArrayContentRendersTextParagraphs() {
        val blocks = parseArticleContentBlocks(structuredParagraphs = emptyList(), htmlContent = noteContent)
        assertEquals("公开笔记正文", (blocks.first() as ArticleContentBlock.Paragraph).text)
    }

    @Test
    fun aFailedArticleViewFallsBackToThePublicNote() = runBlocking {
        val calls = mutableListOf<Long>()
        val result = fallback(calls) { note() }.load(7L) { Result.failure(IllegalStateException("no article")) }
        val model = result.getOrThrow()
        assertEquals(listOf(7L), calls)
        assertEquals(7L, model.articleId)
        assertEquals("公开笔记", model.title)
        assertEquals("摘要", model.summary)
        assertEquals("作者", model.authorName)
        assertEquals(9L, model.authorMid)
        assertEquals("https://example.test/face.png", model.authorFace)
        assertEquals("", model.publishTime)
        assertEquals(null, model.bannerUrl)
        assertEquals("公开笔记正文", (model.blocks.single() as ArticleContentBlock.Paragraph).text)
    }

    @Test
    fun theNoteCvidWinsOverTheRequestedIdWhenItIsPositive() = runBlocking {
        val model = fallback(mutableListOf()) { note(cvid = 4242L) }
            .load(7L) { Result.failure(IllegalStateException("no article")) }.getOrThrow()
        assertEquals(4242L, model.articleId)
    }

    @Test
    fun anArticleWithBlocksIsNeverReplacedAndNeverReadsTheNote() = runBlocking {
        val calls = mutableListOf<Long>()
        val first = Result.success(full)
        val result = fallback(calls) { note() }.load(7L) { first }
        assertEquals(first, result)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun anArticleWithoutBlocksIsReplacedByAReadableNoteOnly() = runBlocking {
        val replaced = fallback(mutableListOf()) { note() }.load(7L) { Result.success(empty) }.getOrThrow()
        assertEquals("公开笔记正文", (replaced.blocks.single() as ArticleContentBlock.Paragraph).text)
        val first = Result.success(empty)
        assertEquals(first, fallback(mutableListOf()) { note(content = "") }.load(7L) { first })
    }

    @Test
    fun theOriginalFailureSurvivesWhenTheNoteCannotBeReadEither() = runBlocking {
        val failure = Result.failure<ArticleDetailUiModel>(IllegalStateException("no article"))
        assertEquals(failure, fallback(mutableListOf()) { note(code = -404) }.load(7L) { failure })
        assertEquals(failure, fallback(mutableListOf()) { note(withData = false) }.load(7L) { failure })
        assertEquals(failure, fallback(mutableListOf()) { note(content = "not a note") }.load(7L) { failure })
        val throwing = DesktopArticlePublicNoteFallback({ throw java.io.IOException("offline") }, {})
        assertEquals(failure, throwing.load(7L) { failure })
    }

    @Test
    fun anInvalidArticleIdKeepsTheOriginalFailureAndNeverReadsTheNote() = runBlocking {
        listOf(0L, -1L).forEach { id ->
            val calls = mutableListOf<Long>()
            val failure = Result.failure<ArticleDetailUiModel>(IllegalArgumentException("Invalid article id: $id"))
            assertEquals(failure, fallback(calls) { note() }.load(id) { failure })
            assertTrue(calls.isEmpty(), "no public note request may be issued for article id $id")
        }
    }

    @Test
    fun cancellationAndRetiredOwnershipAreNotSwallowed() {
        val cancelled = DesktopArticlePublicNoteFallback({ throw CancellationException("cancelled") }, {})
        val cancelledFailure = runBlocking {
            runCatching { cancelled.load(7L) { Result.failure(IllegalStateException("no article")) } }.exceptionOrNull()
        }
        assertTrue(cancelledFailure is CancellationException, "cancellation must reach the caller")
        val retired = DesktopArticlePublicNoteFallback({ note() }, { throw CancellationException("Article entry retired") })
        val retiredFailure = runBlocking {
            runCatching { retired.load(7L) { Result.success(empty) } }.exceptionOrNull()
        }
        assertTrue(retiredFailure is CancellationException, "a retired owner must stop the fallback")
    }
}
