package com.bilipai.desktop.data

import com.android.purebilibili.core.network.socket.LiveDanmakuConnectionHealth
import com.android.purebilibili.core.network.socket.LiveDanmakuHealthAction
import com.android.purebilibili.core.network.socket.LIVE_DANMAKU_HEALTH_CHECK_INTERVAL_MS
import com.android.purebilibili.core.network.socket.LIVE_DANMAKU_SILENCE_TIMEOUT_MS
import com.android.purebilibili.core.network.socket.markLiveDanmakuBusinessMessage
import com.android.purebilibili.core.network.socket.markLiveDanmakuConnected
import com.android.purebilibili.core.network.socket.markLiveDanmakuDisconnectedByUser
import com.android.purebilibili.core.network.socket.markLiveDanmakuHeartbeatReply
import com.android.purebilibili.core.network.socket.markLiveDanmakuServerFrameReceived
import com.android.purebilibili.core.network.socket.resolveLiveDanmakuHealthAction
import kotlin.test.*

/**
 * Regression for the live danmaku socket health policy consumed by the generated
 * LiveDanmakuClient (media extractor output): the 5s health check turns a silent
 * stream into a reconnect, while a user disconnect never resurrects a socket.
 * These cases exercise the REAL generated policy functions, not a reimplementation.

 */
class DesktopLiveDanmakuHealthPolicyTest {

    @Test fun `a stream silent past the timeout is reconnected`() {
        val health = markLiveDanmakuConnected(LiveDanmakuConnectionHealth(), nowMs = 1_000L)
        assertEquals(
            LiveDanmakuHealthAction.RECONNECT,
            resolveLiveDanmakuHealthAction(health, nowMs = 1_000L + LIVE_DANMAKU_SILENCE_TIMEOUT_MS + 1L)
        )
    }

    @Test fun `the boundary instant itself still keeps the connection alive`() {
        val health = markLiveDanmakuConnected(LiveDanmakuConnectionHealth(), nowMs = 0L)
        assertEquals(
            LiveDanmakuHealthAction.KEEP_ALIVE,
            resolveLiveDanmakuHealthAction(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS)
        )
    }

    @Test fun `a heartbeat reply refreshes the silence window`() {
        var health = markLiveDanmakuConnected(LiveDanmakuConnectionHealth(), nowMs = 0L)
        health = markLiveDanmakuHeartbeatReply(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS - LIVE_DANMAKU_HEALTH_CHECK_INTERVAL_MS)
        assertEquals(LiveDanmakuHealthAction.KEEP_ALIVE, resolveLiveDanmakuHealthAction(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS))
        assertEquals(
            LiveDanmakuHealthAction.RECONNECT,
            resolveLiveDanmakuHealthAction(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS - LIVE_DANMAKU_HEALTH_CHECK_INTERVAL_MS + LIVE_DANMAKU_SILENCE_TIMEOUT_MS + 1L)
        )
    }

    @Test fun `any server frame or business message refreshes the silence window`() {
        var health = markLiveDanmakuConnected(LiveDanmakuConnectionHealth(), nowMs = 0L)
        health = markLiveDanmakuServerFrameReceived(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS - 1L)
        assertEquals(LiveDanmakuHealthAction.KEEP_ALIVE, resolveLiveDanmakuHealthAction(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS))
        health = markLiveDanmakuBusinessMessage(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS - 1L)
        assertEquals(LiveDanmakuHealthAction.KEEP_ALIVE, resolveLiveDanmakuHealthAction(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS))
    }

    @Test fun `a user disconnect never schedules a reconnect`() {
        val health = markLiveDanmakuDisconnectedByUser(
            markLiveDanmakuConnected(LiveDanmakuConnectionHealth(), nowMs = 0L)
        )
        assertEquals(
            LiveDanmakuHealthAction.KEEP_ALIVE,
            resolveLiveDanmakuHealthAction(health, nowMs = 10L + LIVE_DANMAKU_SILENCE_TIMEOUT_MS * 10L)
        )
    }

    @Test fun `frames arriving after a user disconnect cannot refresh the window`() {
        val disconnected = markLiveDanmakuDisconnectedByUser(
            markLiveDanmakuConnected(LiveDanmakuConnectionHealth(), nowMs = 0L)
        )
        val refreshed = markLiveDanmakuServerFrameReceived(disconnected, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS)
        assertSame(disconnected, refreshed)
        assertEquals(LiveDanmakuHealthAction.KEEP_ALIVE, resolveLiveDanmakuHealthAction(refreshed, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS * 2L))
    }

    @Test fun `a client that never connected is kept alive instead of reconnecting`() {
        assertEquals(
            LiveDanmakuHealthAction.KEEP_ALIVE,
            resolveLiveDanmakuHealthAction(LiveDanmakuConnectionHealth(), nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS * 10L)
        )
    }

    @Test fun `reconnecting after silence resets the window from the new connection`() {
        var health = markLiveDanmakuConnected(LiveDanmakuConnectionHealth(), nowMs = 0L)
        assertEquals(LiveDanmakuHealthAction.RECONNECT, resolveLiveDanmakuHealthAction(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS + 1L))
        health = markLiveDanmakuConnected(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS + 1L)
        assertEquals(LiveDanmakuHealthAction.KEEP_ALIVE, resolveLiveDanmakuHealthAction(health, nowMs = LIVE_DANMAKU_SILENCE_TIMEOUT_MS + 1L + LIVE_DANMAKU_SILENCE_TIMEOUT_MS - 1L))
    }
}
