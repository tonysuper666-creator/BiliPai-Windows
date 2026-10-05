package com.bilipai.desktop.ui

import com.android.purebilibili.core.events.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.test.*

/** Actual generated Favorites action and its real scope/caller retirement.
 * The mounted key/captured source contract is checked by the persistent source
 * test; this headless test exercises the resulting owner without an AWT peer. */
class DesktopVideoFavoriteSourceRetirementTest {
    private class Harness {
        val root = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bus = BrandSuccessEvents { root.isActive }
        fun publication(cid: Long) = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create("BVsame", aid = 17, cid = cid),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://fixture.invalid/same")))
        val first = publication(70)
        @Volatile var accepted = first
        val ownerScopes = mutableListOf<CoroutineScope>()
        var response: (Array<out Any?>) -> Any = { SimpleApiResponse() }
        val events = CopyOnWriteArrayList<BrandSuccessFeedback>()
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
            assertEquals("dealFavorite", method.name); response(args.orEmpty())
        } as BilibiliApi
        fun owner(expected: DesktopOriginalVideoAcceptedPublication): Pair<CoroutineScope, DesktopFavoriteEnvironment> {
            val scope = CoroutineScope(root.coroutineContext + SupervisorJob(root.coroutineContext.job))
            ownerScopes += scope
            fun current() = root.isActive && scope.isActive && accepted === expected
            val env = DesktopFavoriteEnvironment.forFolderDrawer(scope, api, ::current, { "synthetic-csrf" }, { 1L }, {})
            env.mountBrandFeedback(bus) { action -> if (current()) { action(); true } else false }
            return scope to env
        }
        suspend fun close() {
            ownerScopes.forEach { it.cancel() }
            ownerScopes.forEach { it.coroutineContext.job.join() }
            bus.close(); root.cancel(); root.coroutineContext.job.join()
        }
    }

    private suspend fun CoroutineScope.exerciseLateResponse(newCid: Long) {
        val h = Harness()
        val collect = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { h.bus.events.collect { h.events += it } }
        try {
            val pending = CompletableDeferred<Continuation<Any?>>()
            h.response = { args ->
                @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                pending.complete(continuation.intercepted()); COROUTINE_SUSPENDED
            }
            val (oldScope, oldEnvironment) = h.owner(h.first)
            val oldCaller = oldScope.async { oldEnvironment.actions.favoriteVideo(17, true, folderId = 9) }
            val continuation = withTimeout(2_000) { pending.await() }
            h.accepted = h.publication(newCid)
            // Even before Compose applies old-key disposal, its ownership
            // predicate remains captured to the old accepted reference.
            assertTrue(oldScope.isActive)
            continuation.resumeWith(Result.success(SimpleApiResponse()))
            val retiredResult = oldCaller.await()
            assertTrue(retiredResult.isFailure)
            assertIs<CancellationException>(retiredResult.exceptionOrNull())
            assertTrue(oldScope.isActive); assertTrue(h.events.isEmpty())
            // Exactly the existing original owner-scope cancellation on key disposal.
            oldScope.cancel()
            h.response = { SimpleApiResponse() }
            val (newScope, newEnvironment) = h.owner(h.accepted)
            assertTrue(newScope.async { newEnvironment.actions.favoriteVideo(17, true, folderId = 9) }.await().getOrThrow())
            assertEquals(BrandSuccessKind.FAVORITE, h.events.single().kind)
            assertTrue(h.bus.isCurrent(h.events.single()))
        } finally { collect.cancelAndJoin(); h.close() }
    }

    @Test fun sameAidNewCidRetiresActualPendingCallerAndSuccessorCanSave(): Unit = runBlocking {
        exerciseLateResponse(71)
    }
    @Test fun sameValueNewAcceptedReferenceRetiresActualPendingCaller(): Unit = runBlocking {
        exerciseLateResponse(70)
    }
    @Test fun naturalCompletedCallerReceiptStillRetiresWithItsOriginalScope(): Unit = runBlocking {
        val h = Harness()
        val collect = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { h.bus.events.collect { h.events += it } }
        try {
            val (scope, environment) = h.owner(h.first)
            val caller = scope.async { environment.actions.favoriteVideo(17, true, folderId = 9) }
            assertTrue(caller.await().getOrThrow()); assertFalse(caller.isCancelled)
            val old = h.events.single(); assertTrue(h.bus.isCurrent(old))
            h.accepted = h.publication(70); scope.cancel(); scope.coroutineContext.job.join()
            assertFalse(h.bus.isCurrent(old))
            assertFalse(caller.isCancelled) // natural completion alone is not the retirement authority
        } finally { collect.cancelAndJoin(); h.close() }
    }
}
