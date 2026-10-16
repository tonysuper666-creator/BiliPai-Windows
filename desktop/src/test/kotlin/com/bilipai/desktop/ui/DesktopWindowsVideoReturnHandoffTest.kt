package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopWindowsVideoReturnHandoffTest {
    private val child = BiliPaiNavKey.VideoDetail("BVchild", sourceRoute = "video/BVparent")
    private val parent = BiliPaiNavKey.VideoDetail("BVparent", sourceRoute = "home")

    @Test fun `no visual host keeps the actual synchronous back result`() {
        val handoff = DesktopWindowsVideoReturnHandoff { true }
        var commits = 0
        assertTrue(handoff.request(child, parent, { true }) { commits++; true })
        assertEquals(1, commits)
        assertFalse(handoff.request(child, parent, { true }) { commits++; false })
        assertEquals(2, commits)
    }

    @Test fun `delayed cover and clip commit exactly once without double back`() {
        val handoff = DesktopWindowsVideoReturnHandoff { true }
        lateinit var captured: DesktopWindowsVideoReturnRequest
        var hostCalls = 0
        var commits = 0
        handoff.bind { captured = it; hostCalls++; true }
        assertTrue(handoff.request(child, parent, { true }) { commits++; true })
        assertTrue(handoff.request(child, parent, { true }) { fail("duplicate back") })
        assertEquals(1, hostCalls)
        assertEquals(0, commits)
        assertSame(child, captured.leavingKey)
        assertSame(parent, captured.targetKey)
        assertTrue(captured.commit())
        assertFalse(captured.commit())
        assertEquals(1, commits)
    }

    @Test fun `leaving and returning to an equal route cannot revive its old request`() {
        val handoff = DesktopWindowsVideoReturnHandoff { true }
        lateinit var captured: DesktopWindowsVideoReturnRequest
        var actualEntry: BiliPaiNavKey = child
        val capturedRevision = 8L
        var actualRevision = capturedRevision
        handoff.bind { captured = it; true }
        assertTrue(handoff.request(child, parent,
            { actualEntry === child && actualRevision == capturedRevision }) { fail("stale commit") })
        actualEntry = child.copy() // Equal typed route is a different physical entry.
        actualRevision++
        assertEquals(child, actualEntry)
        assertFalse(captured.isCurrent())
        assertFalse(captured.commit())
        actualEntry = child
        assertFalse(captured.commit()) // Revision remains authoritative even with original reference.
    }

    @Test fun `exact host unbind cancels old callbacks but cannot unbind its successor`() {
        val handoff = DesktopWindowsVideoReturnHandoff { true }
        lateinit var first: DesktopWindowsVideoReturnRequest
        lateinit var second: DesktopWindowsVideoReturnRequest
        val old: (DesktopWindowsVideoReturnRequest) -> Boolean = { first = it; true }
        val replacement: (DesktopWindowsVideoReturnRequest) -> Boolean = { second = it; true }
        handoff.bind(old)
        assertTrue(handoff.request(child, parent, { true }) { fail("retired host") })
        handoff.bind(replacement)
        handoff.unbind(old)
        assertFalse(first.commit())
        var commits = 0
        assertTrue(handoff.request(child, parent, { true }) { commits++; true })
        assertTrue(second.commit())
        assertEquals(1, commits)
        handoff.unbind(replacement)
    }

    @Test fun `actual navigation retirement rejects queued image and timeout events`() {
        val handoff = DesktopWindowsVideoReturnHandoff { true }
        lateinit var captured: DesktopWindowsVideoReturnRequest
        handoff.bind { captured = it; true }
        assertTrue(handoff.request(child, parent, { true }) { fail("retired navigation") })
        handoff.retirePending()
        assertFalse(captured.commit())
    }

    @Test fun `immediate cache hit reports the actual rejected checkpoint without replay`() {
        val handoff = DesktopWindowsVideoReturnHandoff { true }
        var commits = 0
        handoff.bind { assertFalse(it.commit()); false }
        assertFalse(handoff.request(child, parent, { true }) { commits++; false })
        assertEquals(1, commits)
    }

    @Test fun `home intent replaces a pending related back instead of committing its target`() {
        val handoff = DesktopWindowsVideoReturnHandoff { true }
        val captured = mutableListOf<DesktopWindowsVideoReturnRequest>()
        handoff.bind { captured += it; true }
        assertTrue(handoff.request(child, parent, { true }) { fail("superseded back") })
        var homeCommits = 0
        assertTrue(handoff.request(child, BiliPaiNavKey.MainHost, { true }) { homeCommits++; true })
        assertEquals(2, captured.size)
        assertFalse(captured[0].commit())
        assertTrue(captured[1].commit())
        assertEquals(1, homeCommits)
    }

    @Test fun `cancelled visual preparation cannot retain a delayed navigation claim`() {
        val handoff = DesktopWindowsVideoReturnHandoff { true }
        lateinit var captured: DesktopWindowsVideoReturnRequest
        val cancelled = java.util.concurrent.CancellationException("Root retired")
        val callback: (DesktopWindowsVideoReturnRequest) -> Boolean = { captured = it; throw cancelled }
        handoff.bind(callback)
        assertSame(cancelled, assertFailsWith<java.util.concurrent.CancellationException> {
            handoff.request(child, parent, { true }) { fail("cancelled preparation") }
        })
        assertFalse(captured.commit())
        handoff.unbind(callback)
        assertTrue(handoff.request(child, parent, { true }) { true })
    }
}
