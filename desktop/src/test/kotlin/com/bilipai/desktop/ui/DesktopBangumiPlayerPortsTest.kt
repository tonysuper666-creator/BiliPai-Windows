package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.SendDanmakuData
import com.android.purebilibili.feature.bangumi.BangumiFollowSession
import com.android.purebilibili.feature.bangumi.currentBangumiFollowSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** The v0.4.6 PGC view model reaches the ordinary danmaku protocol, the account epoch and the like status only
 * through these small ports. Pure values: no window, native player, GPU or network. */
class DesktopBangumiPlayerPortsTest {
    private data class Sent(val aid: Long, val cid: Long, val message: String, val progress: Long,
        val color: Int, val fontSize: Int, val mode: Int, val colorful: Boolean)

    @Test fun portSetBuiltWithoutADanmakuBindingCannotSend(): Unit = runBlocking {
        assertTrue(DesktopOriginalBangumiUnavailableDanmaku.isDanmakuServerDisabled(7L))
        val result = DesktopOriginalBangumiUnavailableDanmaku.sendDanmaku(1L, 7L, "hello", 0L, 16777215, 25, 1, false)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test fun serverDisabledReadPassesTheAnswerAndFailsClosedOnAChangedSource() {
        val open = DesktopOriginalBangumiPlayerDanmakuView({ false }, { _, _, _, _, _, _, _, _ -> error("no send") }, {})
        assertFalse(open.isDanmakuServerDisabled(5L))
        val closed = DesktopOriginalBangumiPlayerDanmakuView({ true }, { _, _, _, _, _, _, _, _ -> error("no send") }, {})
        assertTrue(closed.isDanmakuServerDisabled(5L))
        // The ordinary binding throws when the accepted source or the CID changed: that reads as closed.
        val moved = DesktopOriginalBangumiPlayerDanmakuView({ error("Danmaku CID/source changed") },
            { _, _, _, _, _, _, _, _ -> error("no send") }, {})
        assertTrue(moved.isDanmakuServerDisabled(5L))
    }

    @Test fun serverDisabledReadNeverSwallowsCancellationOrACallFromARetiredPresenter() {
        val cancelled = DesktopOriginalBangumiPlayerDanmakuView({ throw CancellationException("source retired") },
            { _, _, _, _, _, _, _, _ -> error("no send") }, {})
        assertFailsWith<CancellationException> { cancelled.isDanmakuServerDisabled(5L) }
        var reads = 0
        val retired = DesktopOriginalBangumiPlayerDanmakuView({ reads++; false },
            { _, _, _, _, _, _, _, _ -> error("no send") }, { throw CancellationException("presenter retired") })
        assertFailsWith<CancellationException> { retired.isDanmakuServerDisabled(5L) }
        assertEquals(0, reads)
    }

    @Test fun sendForwardsEveryArgumentOnceAndChecksOwnershipBeforeAndAfter(): Unit = runBlocking {
        val sent = mutableListOf<Sent>()
        var ownership = 0
        val expected = SendDanmakuData(dmid = 9L, dmid_str = "9")
        val view = DesktopOriginalBangumiPlayerDanmakuView({ false }, { aid, cid, message, progress, color, fontSize, mode, colorful ->
            sent += Sent(aid, cid, message, progress, color, fontSize, mode, colorful)
            Result.success(expected)
        }, { ownership++ })
        val result = view.sendDanmaku(11L, 22L, "第一条", 4_500L, 0xFF0000, 25, 1, true)
        assertSame(expected, result.getOrNull())
        assertEquals(listOf(Sent(11L, 22L, "第一条", 4_500L, 0xFF0000, 25, 1, true)), sent)
        assertEquals(2, ownership)
    }

    @Test fun aFailedSendIsAFailedResultButCancellationAndRetirementPropagate(): Unit = runBlocking {
        val failure = IllegalStateException("该剧集已关闭弹幕")
        val failing = DesktopOriginalBangumiPlayerDanmakuView({ false }, { _, _, _, _, _, _, _, _ -> throw failure }, {})
        val result = failing.sendDanmaku(1L, 2L, "x", 0L, 16777215, 25, 1, false)
        assertSame(failure, result.exceptionOrNull())
        val cancelling = DesktopOriginalBangumiPlayerDanmakuView({ false },
            { _, _, _, _, _, _, _, _ -> throw CancellationException("source retired") }, {})
        assertFailsWith<CancellationException> { cancelling.sendDanmaku(1L, 2L, "x", 0L, 16777215, 25, 1, false) }
        // A presenter that retires while the request is in flight never receives the late result.
        var checks = 0
        val retiring = DesktopOriginalBangumiPlayerDanmakuView({ false },
            { _, _, _, _, _, _, _, _ -> Result.success(SendDanmakuData()) },
            { if (++checks == 2) throw CancellationException("presenter retired") })
        assertFailsWith<CancellationException> { retiring.sendDanmaku(1L, 2L, "x", 0L, 16777215, 25, 1, false) }
    }

    @Test fun followSessionFollowsTheAccountEpochAndNeverCarriesACredential() {
        val first = desktopOriginalBangumiFollowSession(4L)
        assertEquals(first, desktopOriginalBangumiFollowSession(4L))
        assertNotEquals(first, desktopOriginalBangumiFollowSession(5L))
        assertNull(first.mid)
        assertTrue(first.session.orEmpty().startsWith("account-epoch:"))
        // The policy default is the single constant identity: no account tracking for callers that pass none.
        assertEquals(BangumiFollowSession(null, null), currentBangumiFollowSession())
    }

    @Test fun aPortWithOnlyTheOlderLikeReadDerivesTheQueryAndKeepsFailuresVisible(): Unit = runBlocking {
        var next: () -> Boolean = { true }
        val actions = object : DesktopOriginalBangumiPlayerActions {
            override suspend fun checkLikeStatus(aid: Long): Boolean = next()
            override suspend fun checkCoinStatus(aid: Long): Int = 0
        }
        assertEquals(true, actions.queryLikeStatus(1L).getOrNull())
        next = { error("network") }
        val failed = actions.queryLikeStatus(1L)
        assertTrue(failed.isFailure)
        assertEquals("network", failed.exceptionOrNull()?.message)
        next = { throw CancellationException("entry retired") }
        assertFailsWith<CancellationException> { actions.queryLikeStatus(1L) }
        assertNull(actions.cachedLikeStatus(1L))
    }
}
