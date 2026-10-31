package com.bilipai.desktop.player

import com.android.purebilibili.core.plugin.BuiltinPlugin
import com.android.purebilibili.feature.plugin.SponsorBlockPlugin
import com.android.purebilibili.feature.plugin.SponsorBlockPlaybackSession
import com.android.purebilibili.plugin.sdk.PlayerPluginApi
import com.android.purebilibili.plugin.sdk.PlayerPluginSession
import com.android.purebilibili.plugin.sdk.SkipAction
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlayerPluginSessionsTest {
    private class Session : PlayerPluginSession {
        val positions = mutableListOf<Long>()
        var ends = 0
        override suspend fun onVideoLoad(bvid: String, cid: Long) = Unit
        override suspend fun onPositionUpdate(positionMs: Long): SkipAction? { positions += positionMs; return null }
        override fun onVideoEnd() { ends++ }
    }
    private class Definition(override val id: String = "player") : BuiltinPlugin, PlayerPluginApi {
        override val name = id
        override val version = "1"
        override val description = "test player"
        val sessions = mutableListOf<Session>()
        override fun createSession() = Session().also(sessions::add)
    }
    private fun CoroutineScope.owner(key: String = "BV1", load: suspend (BuiltinPlugin, PlayerPluginSession, String, Long) -> Unit = { _, s, b, c -> s.onVideoLoad(b, c) }) =
        DesktopPlayerPluginSessions(this, key, 1, load) { _, _ -> }

    @Test fun realSponsorDefinitionCreatesIndependentRawSessions() {
        val definition = SponsorBlockPlugin()
        val first = definition.createSession()
        val second = definition.createSession()
        assertIs<SponsorBlockPlaybackSession>(first)
        assertIs<SponsorBlockPlaybackSession>(second)
        assertNotSame(first, second)
        first.onVideoEnd()
        second.onVideoEnd()
    }

    @Test fun independentPlayersDoNotShareMutableState() = runTest {
        val definition = Definition()
        val first = owner(); val second = owner()
        first.update(listOf(definition)); second.update(listOf(definition)); runCurrent()
        first.awaitLoads(); second.awaitLoads()
        first.ready().single().onPositionUpdate(100)
        assertEquals(listOf(100L), definition.sessions[0].positions)
        assertTrue(definition.sessions[1].positions.isEmpty())
        first.close()
        assertEquals(1, definition.sessions[0].ends)
        assertEquals(0, definition.sessions[1].ends)
        assertEquals(1, second.ready().size)
        second.close()
    }

    @Test fun sameDefinitionUpdateRetainsSessionAndPositionAcrossHandoff() = runTest {
        val definition = Definition(); val owner = owner()
        owner.update(listOf(definition)); runCurrent()
        val session = owner.ready().single()
        session.onPositionUpdate(900)
        owner.update(listOf(definition)); runCurrent()
        assertSame(session, owner.ready().single())
        assertEquals(listOf(900L), definition.sessions.single().positions)
        assertEquals(0, definition.sessions.single().ends)
        owner.close(); owner.close()
        assertEquals(1, definition.sessions.single().ends)
    }

    @Test fun retiredNonCancellableLoadCannotPublishIntoNewHost() = runTest {
        val gate = CompletableDeferred<Unit>(); val definition = Definition()
        val old = owner("old") { _, _, _, _ -> withContext(NonCancellable) { gate.await() } }
        old.update(listOf(definition)); runCurrent()
        val oldHandle = old.current().single()
        val waiting = async { old.awaitLoads() }; runCurrent()
        old.close(); runCurrent()
        assertTrue(waiting.isCancelled)
        val current = owner("new"); current.update(listOf(definition)); runCurrent()
        gate.complete(Unit); runCurrent()
        assertFalse(old.isCurrent(oldHandle))
        assertTrue(old.ready().isEmpty())
        assertEquals(1, current.ready().size)
        assertEquals(1, definition.sessions.first().ends)
        assertEquals(0, definition.sessions.last().ends)
        current.close()
    }

    @Test fun reloadReplacesOnlySelectedPluginAndRejectsOldHandle() = runTest {
        val first = Definition("first"); val second = Definition("second"); val owner = owner()
        owner.update(listOf(first, second)); runCurrent()
        val old = owner.ready().first(); val retained = owner.ready().last()
        val reload = launch { owner.reload("first") }; runCurrent(); reload.join()
        assertFalse(owner.isCurrent(old))
        assertSame(retained, owner.ready().last())
        assertEquals(2, first.sessions.size)
        assertEquals(1, first.sessions.first().ends)
        assertEquals(1, second.sessions.size)
        assertFailsWith<CancellationException> { owner.ensureLoaded(old) }
        owner.close()
    }

    @Test fun canceledAdmissionUnblocksWaitAndCanRetrySameSession() = runTest {
        var accepted = false
        val definition = Definition(); val owner = owner { _, _, _, _ ->
            if (!accepted) throw CancellationException("old account admission")
        }
        owner.update(listOf(definition)); runCurrent()
        assertFailsWith<CancellationException> { owner.awaitLoads() }
        assertTrue(owner.ready().isEmpty())
        accepted = true
        val handle = owner.current().single()
        val retry = launch { owner.ensureLoaded(handle) }; runCurrent(); retry.join()
        assertSame(handle, owner.ready().single())
        assertEquals(1, definition.sessions.size)
        owner.close()
    }
}
