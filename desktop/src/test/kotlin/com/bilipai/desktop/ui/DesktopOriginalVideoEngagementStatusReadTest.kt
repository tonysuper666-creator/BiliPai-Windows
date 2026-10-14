package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoCoinBalanceLoader
import com.android.purebilibili.feature.video.viewmodel.VideoEngagementActions
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy

class DesktopOriginalVideoEngagementStatusReadTest {
    @TempDir lateinit var temporary: File

    private fun environment(
        owned: () -> Boolean,
        cached: (Long) -> Boolean?,
        query: suspend (Long) -> Result<Boolean>,
    ): DesktopOriginalVideoEngagementEnvironment {
        val actions = Proxy.newProxyInstance(VideoEngagementActions::class.java.classLoader,
            arrayOf(VideoEngagementActions::class.java)) { _, method, _ ->
            error("A status read must not mutate through ${method.name}")
        } as VideoEngagementActions
        return DesktopOriginalVideoEngagementEnvironment(
            DesktopPluginContext(DesktopPluginStore(temporary.toPath())),
            CoroutineScope(SupervisorJob()), actions, VideoCoinBalanceLoader { error("No coin request") },
            owned, { action -> if (owned()) { action(); true } else false },
            interactionSession = { temporary },
            cachedFollowStatusReader = cached, followStatusQuery = query,
        )
    }

    @Test fun unknownAndFailedStatusReadsKeepTheirMeaning() = runBlocking {
        val cachedMids = mutableListOf<Long>()
        val queriedMids = mutableListOf<Long>()
        val failure = IllegalStateException("Controlled request failure")
        val env = environment({ true }, { cachedMids += it; null }, {
            queriedMids += it; Result.failure(failure)
        })
        try {
            assertNull(env.cachedFollowStatus(41))
            assertSame(failure, env.queryFollowStatus(41).exceptionOrNull())
            assertEquals(listOf(41L), cachedMids)
            assertEquals(listOf(41L), queriedMids)
        } finally { env.scope.cancel() }
    }

    @Test fun retirementInsideCachedReaderRejectsItsResult() {
        var owned = true
        val env = environment({ owned }, { owned = false; true }, { error("No network query") })
        try {
            try { env.cachedFollowStatus(41); fail("Retired cached result escaped") }
            catch (_: CancellationException) { }
        } finally { env.scope.cancel() }
    }

    @Test fun queryCompletionAfterEntryRetirementCannotEscape() = runBlocking {
        var owned = true
        val response = CompletableDeferred<Result<Boolean>>()
        var started = 0
        var escaped = false
        var rejected = false
        val env = environment({ owned }, { null }, { started++; response.await() })
        try {
            val request = launch(start = CoroutineStart.UNDISPATCHED) {
                try { env.queryFollowStatus(41); escaped = true }
                catch (_: CancellationException) { rejected = true }
            }
            assertEquals(1, started)
            owned = false
            response.complete(Result.success(true))
            request.join()
            assertTrue(rejected)
            assertFalse(escaped)
        } finally { env.scope.cancel() }
    }
}
