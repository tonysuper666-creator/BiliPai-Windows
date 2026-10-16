package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.BilibiliApi
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real owned protocol and cache; the API must stay unused for confirmed writes. */
class DesktopFollowConfirmationReadTest {
    private class Harness {
        var now = 0L
        var account: Any = "first-account"
        var owned = true
        var requests = 0
        val networkFailure = IllegalStateException("Controlled relation query")
        val cache = VideoInteractionStatusCache(ttlMs = 100L, nowMs = { now })
        private val gate = Any()
        private fun checkpoint() {
            if (!owned) throw CancellationException("Retired interaction owner")
        }
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
            arrayOf(BilibiliApi::class.java)) { _, method, _ ->
            assertEquals("getRelation", method.name)
            requests++
            throw networkFailure
        } as BilibiliApi
        val protocol = DesktopOriginalVideoEngagementProtocol(
            api, { "fixture-csrf" }, { 1L }, { "fixture-session" }, { null }, ::checkpoint,
            { error("Status queries must never publish follow mutations") },
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, ::checkpoint),
            cache, FollowingSnapshotGuard(), { account },
            { action -> synchronized(gate) { checkpoint(); action(); checkpoint() } },
        )
        fun confirmed(following: Boolean) {
            val version = cache.beginOperation(account, "follow:41")
            assertTrue(cache.publish(account, "follow:41", following, version))
            cache.finishOperation(account, "follow:41", version)
        }
    }

    @Test fun confirmedFollowReadReturnsTrueWithoutASecondRequest() = runBlocking {
        val h = Harness()
        h.confirmed(true)
        assertTrue(h.protocol.queryFollowStatus(41).getOrThrow())
        assertEquals(0, h.requests)
    }

    @Test fun confirmedUnfollowReadPreservesFalseWithoutASecondRequest() = runBlocking {
        val h = Harness()
        h.confirmed(false)
        assertFalse(h.protocol.queryFollowStatus(41).getOrThrow())
        assertEquals(0, h.requests)
    }

    @Test fun pendingMutationCannotReuseItsPreviousValue() = runBlocking {
        val h = Harness()
        val version = assertNotNull(h.cache.queryVersion(h.account, "follow:41"))
        assertTrue(h.cache.publish(h.account, "follow:41", true, version, query = true))
        h.cache.beginOperation(h.account, "follow:41")
        val failure = h.protocol.queryFollowStatus(41).exceptionOrNull()
        assertIs<IllegalStateException>(failure)
        assertEquals("关注操作尚未完成", failure.message)
        assertEquals(0, h.requests)
    }

    @Test fun expiredConfirmationRequiresARealQuery() = runBlocking {
        val h = Harness()
        h.confirmed(true)
        h.now = 100L
        assertSame(h.networkFailure, h.protocol.queryFollowStatus(41).exceptionOrNull())
        assertEquals(1, h.requests)
    }

    @Test fun anotherAccountCannotReuseThePreviousConfirmation() = runBlocking {
        val h = Harness()
        h.confirmed(true)
        h.account = "second-account"
        assertSame(h.networkFailure, h.protocol.queryFollowStatus(41).exceptionOrNull())
        assertEquals(1, h.requests)
    }

    @Test fun retiredOwnerCannotReadAConfirmedResult() = runBlocking {
        val h = Harness()
        h.confirmed(true)
        h.owned = false
        assertFailsWith<CancellationException> { h.protocol.queryFollowStatus(41) }
        assertEquals(0, h.requests)
    }
}
