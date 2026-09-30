package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.MessageSendPayloadFactory
import com.android.purebilibili.feature.list.*
import com.android.purebilibili.feature.video.note.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class DesktopCommunityMutationTest {
    @Test
    fun noteFieldsReuseOriginalRichTextAndTimestampCodec() {
        val document = VideoNoteEditorDocument(" My note ", listOf(
            VideoNoteBlock.Text("formatted\n", bold = true, highlight = true, unorderedList = true),
            VideoNoteBlock.Timestamp(seconds = 65, cid = 900, index = 1, cidCount = 3)))
        val encoded = VideoNoteContentCodec.encode(document)
        val fields = communityNoteFields(77, document, encoded, "123", false, "test-only")
        assertEquals("77", fields["oid"]); assertEquals("0", fields["oid_type"])
        assertEquals("My note", fields["title"]); assertEquals("123", fields["note_id"])
        assertEquals("0", fields["publish"]); assertEquals("save", fields["from"])
        assertEquals(encoded.contentLength.toString(), fields["cont_len"])
        val roundTrip = VideoNoteContentCodec.decode(" My note ", fields.getValue("content"))
        assertEquals(document, roundTrip)
        val tag = Json.parseToJsonElement(fields.getValue("tags")).jsonArray.single().jsonObject
        assertEquals(900L, tag.getValue("cid").jsonPrimitive.long)
        assertEquals(65L, tag.getValue("seconds").jsonPrimitive.long)
        assertEquals(1, tag.getValue("index").jsonPrimitive.int)
        assertEquals(1, tag.getValue("pos").jsonPrimitive.int)
    }

    @Test
    fun notePublishIsExplicitAndNewNoteOmitsId() {
        val document = VideoNoteEditorDocument("note", listOf(VideoNoteBlock.Text("body")))
        val fields = communityNoteFields(7, document, VideoNoteContentCodec.encode(document), null, true, "test-only")
        assertEquals("1", fields["publish"]); assertFalse(fields.containsKey("note_id"))
        assertEquals("web", fields["platform"]); assertEquals("1", fields["cls"])
    }

    @Test
    fun sharingUsesOriginalReadableNoteAndVideoUrl() {
        val shared = buildVideoNoteShareText("Video", "BV1xx411c7mD", VideoNoteEditorDocument("", listOf(
            VideoNoteBlock.Text("body"), VideoNoteBlock.Timestamp(62, 123, 0, 1))))
        assertTrue(shared.contains("body[01:02]"))
        assertTrue(shared.endsWith("https://www.bilibili.com/video/BV1xx411c7mD"))
    }

    @Test
    fun privateTextPayloadPreservesQuotesBackslashAndNewlines() {
        val text = "quote \" and slash \\ and\nnext line"
        val content = Json.parseToJsonElement(MessageSendPayloadFactory.buildTextContent(text)).jsonObject
        assertEquals(text, content.getValue("content").jsonPrimitive.content)
        assertEquals("123", MessageSendPayloadFactory.buildWithdrawContent(123))
        assertEquals(MessageSendPayloadFactory.buildTextContent(text), communityMessageTextContent(text))
    }

    @Test
    fun privateTextControlsPreserveWindowsNewlinesTabsAndOtherCharacters() {
        val text = "Windows\r\nline\ttab\u0000zero\bbackspace\u000Cformfeed"
        val content = Json.parseToJsonElement(communityMessageTextContent(text)).jsonObject
        assertEquals(text, content.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun onlyOwnedAvailableMessagesCanBeWithdrawn() {
        val owned = PrivateMessageItem(sender_uid = 42, receiver_id = 77, msg_key = 99)
        requireCommunityWithdraw(owned, 42)
        assertFailsWith<IllegalArgumentException> { requireCommunityWithdraw(owned, 77) }
        assertFailsWith<IllegalArgumentException> { requireCommunityWithdraw(owned.copy(msg_key = 0), 42) }
        assertFailsWith<IllegalArgumentException> { requireCommunityWithdraw(owned.copy(msg_status = 1), 42) }
        assertFailsWith<IllegalArgumentException> { requireCommunityWithdraw(owned.copy(sys_cancel = true), 42) }
    }

    @Test
    fun imagesRejectUnsupportedMimeEmptyBytesAndOversize() {
        validateCommunityImage("image.png", "image/png", byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { validateCommunityImage("image.svg", "image/svg+xml", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { validateCommunityImage("image.jpg", "image/jpeg", byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { validateCommunityImage("image.jpg", "image/jpeg", ByteArray(15 * 1024 * 1024 + 1)) }
    }

    @Test
    fun writeAndAllBusinessPrivateReadEndpointsRejectAnonymousBeforeBootstrap() = runBlocking {
        val path = Files.createTempDirectory("bp-write-").resolve("session.json")
        val community = DesktopCommunityRepository(DesktopRepository(DesktopSessionStore(path)))
        val document = VideoNoteEditorDocument("note", listOf(VideoNoteBlock.Text("body")))
        val failures = listOf(
            assertFailsWith<BiliApiException> { community.sendTextMessage(77, "hello") },
            assertFailsWith<BiliApiException> { community.markSessionRead(77, 1, 123) },
            assertFailsWith<BiliApiException> { community.uploadPrivateImage("image.png", "image/png", byteArrayOf(1)) },
            assertFailsWith<BiliApiException> { community.uploadDynamicImage("image.png", "image/png", byteArrayOf(1)) },
            assertFailsWith<BiliApiException> { community.savePrivateNote(7, document) },
            assertFailsWith<BiliApiException> { community.deletePrivateNote(7, "123") },
            assertFailsWith<BiliApiException> { community.publishDynamic(DynamicPublishDraft("hello")) },
            assertFailsWith<BiliApiException> { community.setCommentLike(CommunityCommentTarget(7, 1), 99, true) },
            assertFailsWith<BiliApiException> { community.deleteComment(CommunityCommentTarget(7, 1), 99) },
            assertFailsWith<BiliApiException> { community.personalHistory() },
            assertFailsWith<BiliApiException> { community.personalFavorites(1) },
            assertFailsWith<BiliApiException> { community.personalWatchLater() },
            assertFailsWith<BiliApiException> { community.likedVideos() })
        assertTrue(failures.all { it.apiCode == -101 }); assertFalse(Files.exists(path))
    }

    @Test
    fun historyUsesOriginalBusinessNavigationResumeAndDeleteIdentifiers() {
        val data = HistoryData(title = "article", history = HistoryPage(oid = 10, cid = 11, business = "article-list"), progress = 65)
        val item = data.toHistoryItem()
        assertEquals(11L, item.videoItem.id)
        assertEquals(HistoryNavigationKind.ARTICLE, resolveHistoryNavigationKind(item))
        assertEquals("article_11", resolveHistoryDeleteKid(item))
        assertEquals(65_000L, resolveHistoryResumePositionMs(item))
        val course = HistoryData(history = HistoryPage(oid = 22, business = "pugv")).toHistoryItem()
        assertEquals(HistoryNavigationKind.CHEESE, resolveHistoryNavigationKind(course))
        assertEquals("cheese_22", resolveHistoryDeleteKid(course))
    }

    @Test
    fun missingCsrfCannotSendNoteOrMessageUnderSavedAccount() = runBlocking {
        val path = Files.createTempDirectory("bp-csrf-").resolve("session.json")
        val session = DesktopSessionStore(path)
        session.saveAccount(mapOf("SESSDATA" to "test-only"), AccountSummary(42, "test", ""))
        val before = Files.readAllBytes(path)
        val community = DesktopCommunityRepository(DesktopRepository(session))
        assertEquals(-101, assertFailsWith<BiliApiException> { community.sendTextMessage(77, "hi") }.apiCode)
        assertEquals(-101, assertFailsWith<BiliApiException> { community.savePrivateNote(7, VideoNoteEditorDocument("note", listOf(VideoNoteBlock.Text("body")))) }.apiCode)
        assertContentEquals(before, Files.readAllBytes(path))
    }
}
