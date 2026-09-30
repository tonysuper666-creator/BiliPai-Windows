package com.bilipai.desktop.data

import com.android.purebilibili.core.network.SPACE_DYNAMIC_FEATURES
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.ArticleContentBlock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class DesktopCommunityRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun fullDynamicPagePreservesForwardImagesRichTextAndVisibility() {
        val data = json.decodeFromString<DynamicFeedData>("""{
          "offset":"next","has_more":true,"update_baseline":"new",
          "items":[{"id_str":"100","type":"DYNAMIC_TYPE_FORWARD","visible":false,
            "modules":{"module_dynamic":{"desc":{"text":"forward with context"}}},
            "orig":{"id_str":"99","type":"DYNAMIC_TYPE_DRAW","modules":{
              "module_dynamic":{"major":{"type":"MAJOR_TYPE_DRAW","draw":{"id":7,
                "items":[{"src":"https://example.test/image","width":900,"height":600}]}}}}}}]
        }""")
        val page = communityDynamicPage(data, "previous")
        assertSame(data, page.data)
        assertEquals("next", page.nextOffset)
        assertFalse(page.items.single().visible)
        assertEquals("forward with context", page.items.single().modules.module_dynamic?.desc?.text)
        assertEquals("99", page.items.single().orig?.id_str)
        assertNotNull(page.items.single().orig?.modules?.module_dynamic?.major?.draw)
    }

    @Test
    fun dynamicCursorStopsRepeatsButKeepsAdvancingEmptyServerPages() {
        assertNull(communityDynamicPage(DynamicFeedData(offset = "same", has_more = true), "same").nextOffset)
        assertNull(communityDynamicPage(DynamicFeedData(offset = " ", has_more = true), "old").nextOffset)
        assertNull(communityDynamicPage(DynamicFeedData(offset = "next", has_more = false), "old").nextOffset)
        assertEquals("next", communityDynamicPage(DynamicFeedData(offset = "next", has_more = true), "old").nextOffset)
    }

    @Test
    fun suppliedCommentSubjectWinsOverOpusShapeAndCanUseResourceId() {
        val item = json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_DRAW",
          "basic":{"comment_id_str":"7","comment_type":11},
          "modules":{"module_dynamic":{"major":{"type":"MAJOR_TYPE_OPUS"}}}}""")
        assertEquals(CommunityCommentTarget(7, 11), dynamicCommentTarget(item))
        assertEquals(CommunityCommentTarget(8, 11), dynamicCommentTarget(item.copy(basic = DynamicBasic(comment_type = 11, rid_str = "8"))))
        assertNull(dynamicCommentTarget(DynamicItem(type = "DYNAMIC_TYPE_ARTICLE")))
    }

    @Test
    fun forwardDiscussionUsesForwardIdWhileVideoUsesAid() {
        val forward = DynamicItem(id_str = "100", type = "DYNAMIC_TYPE_FORWARD", basic = DynamicBasic("7", 11),
            orig = DynamicItem(id_str = "9", basic = DynamicBasic("7", 11)))
        assertEquals(CommunityCommentTarget(100, 17), dynamicCommentTarget(forward))
        val video = json.decodeFromString<DynamicItem>("""{"id_str":"101","type":"DYNAMIC_TYPE_AV",
          "modules":{"module_dynamic":{"major":{"type":"MAJOR_TYPE_ARCHIVE","archive":{"aid":"23","bvid":"BV1test"}}}}}""")
        assertEquals(CommunityCommentTarget(23, 1), dynamicCommentTarget(video))
    }

    @Test
    fun messageCursorHonorsEndAndRepeatedCursor() {
        val cursor = MessageFeedCursor(7, 200, false)
        assertEquals(CommunityMessageCursor(7, 200), communityMessageNext(cursor, null, 1))
        assertNull(communityMessageNext(cursor, CommunityMessageCursor(7, 200), 1))
        assertNull(communityMessageNext(cursor.copy(isEnd = true), null, 1))
        assertNull(communityMessageNext(cursor, null, 0))
        assertNull(communityMessageNext(cursor.copy(id = 0), null, 1))
    }

    @Test
    fun historyPreservesRawMessageContentAndRejectsUnmovingSequence() {
        val data = MessageHistoryData(messages = listOf(PrivateMessageItem(msg_seqno = 30, msg_type = 2,
            content = "{\"url\":\"https://example.test/image\"}", sys_cancel = true)), has_more = 1, min_seqno = 30)
        val first = communityMessageHistory(data, 0)
        assertSame(data, first.data)
        assertEquals(30L, first.nextEndSeqno)
        assertTrue(first.data.messages!!.single().sys_cancel)
        assertNull(communityMessageHistory(data, 30).nextEndSeqno)
        assertNull(communityMessageHistory(data.copy(messages = emptyList()), 0).nextEndSeqno)
    }

    @Test
    fun sessionsReuseUpstreamMicrosecondAndStrictOlderBoundaryPolicy() {
        val data = SessionListData(session_list = listOf(SessionItem(talker_id = 1, session_ts = 1_700_000_000)), has_more = 1)
        val first = communitySessionPage(data, CommunitySessionCursor(1, 0))
        assertSame(data, first.data)
        assertEquals(CommunitySessionCursor(2, 1_699_999_999_999_999), first.nextCursor)
        val next = communitySessionPage(data, first.nextCursor!!)
        assertEquals(1_699_999_999_999_998, next.nextCursor!!.endTs)
        assertNull(communitySessionPage(data.copy(has_more = 0), CommunitySessionCursor(1, 0)).nextCursor)
        assertNull(communitySessionPage(data, CommunitySessionCursor(Int.MAX_VALUE, 0)).nextCursor)
    }

    @Test
    fun searchUsesUpstreamVideoEmptyPageRuleAndTypedSearchTotals() {
        assertEquals(2, communitySearchNext(1, 1, 1, 3, video = true))
        assertNull(communitySearchNext(2, 2, 10, 0, video = true))
        assertNull(communitySearchNext(2, 1, 2, 3, video = false))
        assertEquals(4, communitySearchNext(1, 3, 4, 3, video = false))
        assertNull(communitySearchNext(Int.MAX_VALUE, 1, Int.MAX_VALUE, 1, video = true))
    }

    @Test
    fun articleUsesUpstreamRichBlockParserAndPreservesSourceMetadata() {
        val data = ArticleViewData(id = 42, title = "Article", dynamicId = "100", content =
            "<h2>Heading</h2><p>A paragraph.</p><blockquote>A quote.</blockquote><img src=\"https://example.test/image\">")
        val document = communityArticleDocument(data)
        assertSame(data, document.data)
        assertTrue(document.blocks.any { it is ArticleContentBlock.Heading && it.text == "Heading" })
        assertTrue(document.blocks.any { it is ArticleContentBlock.Paragraph && it.text.contains("A paragraph.") })
        assertTrue(document.blocks.any { it is ArticleContentBlock.Image && it.url == "https://example.test/image" })
        assertEquals("100", document.data.dynamicId)
    }

    @Test
    fun requestParametersMatchUpstreamSpaceAndSearch() {
        val dynamic = communitySpaceDynamicParams(42, "offset")
        assertEquals(SPACE_DYNAMIC_FEATURES, dynamic["features"])
        assertEquals("42", dynamic["host_mid"])
        assertEquals("333.1387", dynamic["web_location"])
        val search = communitySearchParams("term", SearchType.LIVE_USER, 2, mapOf("order" to "online"))
        assertEquals("live_user", search["search_type"])
        assertEquals("20", search["page_size"])
        assertEquals("1430654", search["web_location"])
        assertEquals("pc", search["platform"])
        assertEquals(mapOf("mid" to "42", "pn" to "2", "ps" to "30", "order" to "pubdate"),
            communitySpaceVideoParams(42, 2, "pubdate", 0, ""))
    }

    @Test
    fun knownTotalsAndEmptyPagesEndNumberedPagination() {
        assertEquals(2, communityNumberedNext(1, 10, 11, 10))
        assertNull(communityNumberedNext(2, 10, 11, 1))
        assertNull(communityNumberedNext(1, 10, 100, 0))
        assertEquals(2, communityNumberedNext(1, 10, 0, 10))
        assertNull(communityNumberedNext(Int.MAX_VALUE, 10, 0, 10))
    }

    @Test
    fun privateReadsAndExplicitDynamicWritesRejectAnonymousBeforeNetwork() = runBlocking {
        val path = Files.createTempDirectory("bp-community-").resolve("session.json")
        val community = DesktopCommunityRepository(DesktopRepository(DesktopSessionStore(path)))
        val failures = listOf(
            assertFailsWith<BiliApiException> { community.dynamicFeed() },
            assertFailsWith<BiliApiException> { community.spaceDynamics(42) },
            assertFailsWith<BiliApiException> { community.unreadMessages() },
            assertFailsWith<BiliApiException> { community.replyMessages() },
            assertFailsWith<BiliApiException> { community.mentionMessages() },
            assertFailsWith<BiliApiException> { community.likeMessages() },
            assertFailsWith<BiliApiException> { community.systemNotices() },
            assertFailsWith<BiliApiException> { community.messageSessions() },
            assertFailsWith<BiliApiException> { community.messageHistory(42) },
            assertFailsWith<BiliApiException> { community.privateNoteIds(7) },
            assertFailsWith<BiliApiException> { community.privateNote(7, "note") },
            assertFailsWith<BiliApiException> { community.publishDynamicComment(CommunityCommentTarget(7, 11), "reply") },
            assertFailsWith<BiliApiException> { community.setDynamicLike("100", true) },
            assertFailsWith<BiliApiException> { community.repostDynamic("100") })
        assertTrue(failures.all { it.apiCode == -101 })
        assertFalse(Files.exists(path))
    }

    @Test
    fun invalidPublicInputsAndEmptySuggestionsAvoidSessionBootstrap() = runBlocking {
        val path = Files.createTempDirectory("bp-community-input-").resolve("session.json")
        val community = DesktopCommunityRepository(DesktopRepository(DesktopSessionStore(path)))
        assertFailsWith<IllegalArgumentException> { community.typedSearch(" ") }
        assertFailsWith<IllegalArgumentException> { community.typedSearch("term", filters = mapOf("w_rid" to "arbitrary")) }
        assertFailsWith<IllegalArgumentException> { community.spaceVideos(0) }
        assertFailsWith<IllegalArgumentException> { community.dynamicDetail("-1") }
        assertFailsWith<IllegalArgumentException> { community.playerMetadata("BV1", 0) }
        assertFailsWith<IllegalArgumentException> { community.articleDetail(0) }
        assertTrue(community.searchSuggestions(" ").isEmpty())
        assertFalse(Files.exists(path))
    }

    @Test
    fun incompleteCsrfCannotStartDynamicMutation() = runBlocking {
        val path = Files.createTempDirectory("bp-community-csrf-").resolve("session.json")
        val sessions = DesktopSessionStore(path)
        sessions.saveAccount(mapOf("SESSDATA" to "test-only"), AccountSummary(42, "test", ""))
        val before = Files.readAllBytes(path)
        val community = DesktopCommunityRepository(DesktopRepository(sessions))
        assertEquals(-101, assertFailsWith<BiliApiException> { community.setDynamicLike("100", true) }.apiCode)
        assertContentEquals(before, Files.readAllBytes(path))
    }
}
