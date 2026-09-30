package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DesktopSocialRepositoryTest {
    @Test
    fun coinQuantityMustBeExplicitAndSupported() {
        assertEquals(1, validateCoinQuantity(1))
        assertEquals(2, validateCoinQuantity(2))
        listOf(-1, 0, 3, Int.MAX_VALUE).forEach { assertFailsWith<IllegalArgumentException> { validateCoinQuantity(it) } }
    }

    @Test
    fun replyTargetsKeepRootAndDirectParentSeparate() {
        assertEquals(CommentTarget(null, null), commentTarget(null, null))
        assertEquals(CommentTarget(7, 7), commentTarget(7, null))
        assertEquals(CommentTarget(7, 9), commentTarget(7, 9))
        assertFailsWith<IllegalArgumentException> { commentTarget(null, 9) }
        assertFailsWith<IllegalArgumentException> { commentTarget(0, null) }
    }

    @Test
    fun commentPagingUsesRootCountAndDoesNotCountPinnedDuplicates() {
        val pinned = ReplyItem(rpid = 1, member = ReplyMember(uname = "pinned"))
        val reply = ReplyItem(rpid = 2, rcount = 7, member = ReplyMember(mid = "42", uname = "member"),
            replies = listOf(ReplyItem(rpid = 3, root = 2), ReplyItem(rpid = 4, invisible = true)))
        val result = socialCommentPage(ReplyData(page = ReplyPage(size = 20, count = 40, acount = 400),
            topReplies = listOf(pinned), replies = listOf(pinned, reply)), 1, nested = false)
        assertEquals(listOf(1L, 2L), result.items.map { it.id })
        assertEquals(40, result.totalCount)
        assertTrue(result.hasMore)
        assertEquals(7, result.items.last().replyCount)
        assertEquals(42L, result.items.last().memberId)
        assertEquals(listOf(3L), result.items.last().previewReplies.map { it.id })
        assertFalse(socialCommentPage(ReplyData(page = ReplyPage(size = 20, count = 40), replies = listOf(reply)),
            2, nested = false).hasMore)
    }

    @Test
    fun childCommentPageRetainsRootAndInputPermissions() {
        val root = ReplyItem(rpid = 7, rcount = 21)
        val data = ReplyData(root = root, page = ReplyPage(size = 20), replies = listOf(ReplyItem(rpid = 8, root = 7)),
            control = ReplyPageControl(inputDisable = true))
        val page = socialCommentPage(data, 1, nested = true)
        assertEquals(7L, page.root?.id)
        assertEquals(7L, page.items.single().rootId)
        assertEquals(21, page.totalCount)
        assertTrue(page.hasMore)
        assertFalse(page.inputEnabled)
        assertFalse(socialCommentPage(ReplyData(page = ReplyPage(count = 99), replies = emptyList()), 1, false).hasMore)
    }

    @Test
    fun anonymousMutationsRefuseBeforeSessionNetworkRequests() = runBlocking {
        val path = Files.createTempDirectory("bp-social-").resolve("session.json")
        val social = DesktopSocialRepository(DesktopRepository(DesktopSessionStore(path)))
        val failures = listOf(
            assertFailsWith<BiliApiException> { social.createFavoriteFolder("test") },
            assertFailsWith<BiliApiException> { social.deleteFavoriteFolder(1) },
            assertFailsWith<BiliApiException> { social.setFavorite(1, 2, true) },
            assertFailsWith<BiliApiException> { social.setWatchLater(1, true) },
            assertFailsWith<BiliApiException> { social.setLike(1, true) },
            assertFailsWith<BiliApiException> { social.giveCoins(1, 1) },
            assertFailsWith<BiliApiException> { social.setFollowing(9, true) },
            assertFailsWith<BiliApiException> { social.publishComment(1, "test") })
        assertTrue(failures.all { it.apiCode == -101 })
        assertFalse(Files.exists(path))
    }

    @Test
    fun incompleteAccountCsrfCannotStartAnyPost() = runBlocking {
        val path = Files.createTempDirectory("bp-social-csrf-").resolve("session.json")
        val sessions = DesktopSessionStore(path)
        sessions.saveAccount(mapOf("SESSDATA" to "test-only"), AccountSummary(42, "test", ""))
        val before = Files.readAllBytes(path)
        val social = DesktopSocialRepository(DesktopRepository(sessions))
        assertEquals(-101, assertFailsWith<BiliApiException> { social.setLike(1, true) }.apiCode)
        assertContentEquals(before, Files.readAllBytes(path))
    }

    @Test
    fun invalidInputsFailBeforeAnyAccountOrNetworkWork() = runBlocking {
        val path = Files.createTempDirectory("bp-social-input-").resolve("session.json")
        val social = DesktopSocialRepository(DesktopRepository(DesktopSessionStore(path)))
        assertFailsWith<IllegalArgumentException> { social.giveCoins(1, 3) }
        assertFailsWith<IllegalArgumentException> { social.setFavorite(1, 0, true) }
        assertFailsWith<IllegalArgumentException> { social.publishComment(1, " ") }
        assertFailsWith<IllegalArgumentException> { social.publishComment(1, "reply", parentId = 9) }
        assertFalse(Files.exists(path))
    }
}
