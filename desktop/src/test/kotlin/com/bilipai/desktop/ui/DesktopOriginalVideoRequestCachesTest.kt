package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.Page
import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.ContentRequestException
import com.android.purebilibili.data.repository.OnDemandRequestCache
import com.android.purebilibili.data.repository.buildVideoRequestAccountScope
import com.android.purebilibili.data.repository.resolveRequestedVideoCid
import com.android.purebilibili.data.repository.resolveVideoInfoLookupInput
import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopSessionStore
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Actual per-request adapter/original helper; no HTTP, Root factory, UI or native source. */
class DesktopOriginalVideoRequestCachesTest {
    private val bv = "BV1fixture"
    private fun info(): ViewInfo = ViewInfo(bvid = bv, aid = 17L, cid = 70L,
        pages = listOf(Page(cid = 70L, page = 1), Page(cid = 71L, page = 2)))
    private fun play(): PlayUrlData = PlayUrlData(quality = 80, timelength = 1_000L)

    private class Fixture(assertOwned: () -> Unit = {}) {
        // An ordinary borrowed raw owner, deliberately not a SupervisorJob.
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val invalidations = CopyOnWriteArrayList<Pair<String, Long>>()
        val caches = DesktopOriginalVideoRequestCaches(owner,
            buildVideoRequestAccountScope(listOf("metadata", "first", "1")),
            buildVideoRequestAccountScope(listOf("selected-playback", "second", "1")),
            { bvid, cid -> invalidations += bvid to cid }, assertOwned)
        fun <T> start(block: suspend CoroutineScope.() -> T): Deferred<T> =
            scope.async(start = CoroutineStart.UNDISPATCHED, block = block)
        suspend fun close() = withContext(NonCancellable) {
            withTimeout(5_000L) { owner.cancelAndJoin() }
        }
    }
    private suspend fun cancelled(block: suspend () -> Unit) {
        try { block(); fail("Retired/canceled request was admitted") }
        catch (_: CancellationException) { Unit }
    }

    @Test fun overlappingDetailKeepsSecondWaiterAfterFirstCancellation(): Unit = runBlocking {
        withTimeout(5_000L) {
            val f = Fixture()
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val loads = AtomicInteger()
                val raw = info()
                val first = f.start { f.caches.detail(bv, 0L) {
                    loads.incrementAndGet(); entered.complete(Unit); release.await(); Result.success(raw)
                } }
                entered.await()
                val second = f.start { f.caches.detail(bv, 0L) { error("Must join real in-flight load") } }
                first.cancelAndJoin()
                assertFalse(second.isCompleted)
                assertTrue(f.owner.isActive)
                release.complete(Unit)
                assertSame(raw, second.await().getOrThrow())
                assertSame(raw, f.caches.cachedDetail(bv, 0L))
                assertEquals(1, loads.get())
            } finally { f.close() }
        }
    }

    @Test fun lastWaiterCancellationCancelsWorkerAndNextAttemptReloads(): Unit = runBlocking {
        withTimeout(5_000L) {
            val f = Fixture()
            try {
                val entered = CompletableDeferred<Unit>()
                val retired = CompletableDeferred<Unit>()
                val never = CompletableDeferred<Unit>()
                val loads = AtomicInteger()
                val waiting = f.start { f.caches.related(bv) {
                    loads.incrementAndGet(); entered.complete(Unit)
                    try { never.await(); Result.success(emptyList()) }
                    finally { retired.complete(Unit) }
                } }
                entered.await(); waiting.cancelAndJoin(); retired.await()
                assertNull(f.caches.cachedRelated(bv))
                assertTrue(f.owner.isActive)
                assertTrue(f.caches.related(bv) { loads.incrementAndGet(); Result.success(emptyList()) }.isSuccess)
                assertEquals(2, loads.get())
            } finally { f.close() }
        }
    }

    @Test fun ordinaryInitialFailureDoesNotCancelBorrowedOwnerOrDetailSibling(): Unit = runBlocking {
        withTimeout(5_000L) {
            val f = Fixture()
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val detail = f.start { f.caches.detail(bv, 0L) {
                    entered.complete(Unit); release.await(); Result.success(info())
                } }
                entered.await()
                val failure = ContentRequestException(-404, "fixture")
                val observed = f.start {
                    try { f.caches.initialPlayUrl(bv, 70L, 80, true, null) { throw failure }
                        error("Must return original typed error to caller") }
                    catch (error: ContentRequestException) { error }
                }.await()
                assertSame(failure, observed)
                assertTrue(f.owner.isActive)
                assertFalse(detail.isCompleted)
                release.complete(Unit); assertTrue(detail.await().isSuccess)
                assertEquals(80, f.caches.initialPlayUrl(bv, 70L, 80, true, null) { play() }?.quality)
            } finally { f.close() }
        }
    }

    @Test fun originalHelperExpiresAtBoundaryAndDoesNotCacheFailureData(): Unit = runBlocking {
        withTimeout(5_000L) {
            val f = Fixture()
            try {
                val now = AtomicLong(0L)
                val loads = AtomicInteger()
                val cache = OnDemandRequestCache<String, Result<String>>(2, 10L, f.scope,
                    nowMs = { now.get() }, canCache = { it.isSuccess })
                suspend fun read(): String = cache.getOrLoad("detail") {
                    Result.success("load-${loads.incrementAndGet()}")
                }.getOrThrow()
                assertEquals("load-1", read())
                now.set(9L); assertEquals("load-1", read())
                now.set(10L); assertNull(cache.peek("detail")); assertEquals("load-2", read())
                val failedLoads = AtomicInteger()
                repeat(2) {
                    val result = cache.getOrLoad("failure") {
                        failedLoads.incrementAndGet(); Result.failure<String>(ContentRequestException(-404, "fixture"))
                    }
                    assertTrue(result.isFailure); assertNull(cache.peek("failure"))
                }
                assertEquals(2, failedLoads.get())
                assertTrue(f.owner.isActive)
            } finally { f.close() }
        }
    }

    @Test fun originalClearCompletedKeepsCurrentWaitersButPreventsOldResultReuse(): Unit = runBlocking {
        withTimeout(5_000L) {
            val f = Fixture()
            try {
                val cache = OnDemandRequestCache<String, String>(1, 100L, f.scope)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val loads = AtomicInteger()
                val first = f.start { cache.getOrLoad("detail") {
                    loads.incrementAndGet(); entered.complete(Unit); release.await(); "old"
                } }
                entered.await(); cache.clearCompleted()
                val second = f.start { cache.getOrLoad("detail") { error("Clear must not replace current worker") } }
                release.complete(Unit)
                assertEquals("old", first.await()); assertEquals("old", second.await())
                assertNull(cache.peek("detail"))
                assertEquals("fresh", cache.getOrLoad("detail") { loads.incrementAndGet(); "fresh" })
                assertEquals(2, loads.get())
            } finally { f.close() }
        }
    }

    @Test fun actualSessionReceiptRetirementAndCredentialAbaRejectHitsAndLateResults(): Unit = runBlocking {
        withTimeout(5_000L) {
            val store = DesktopSessionStore(Path.of(System.getProperty("java.io.tmpdir"),
                "bp-cache-memory-only", "unused-session.json"), persistent = false)
            val account = AccountSummary(42L, "fixture", "")
            store.saveAccount(mapOf("SESSDATA" to "first"), account)
            val receipt = store.capturePlaybackAuthorization(store.generation) { true }.receipt
            val f = Fixture { store.withPlaybackAuthorizationAdmission(receipt, { true }) { Unit } }
            try {
                assertTrue(f.caches.detail(bv, 0L) { Result.success(info()) }.isSuccess)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val late = f.start { f.caches.related(bv) {
                    entered.complete(Unit); release.await(); Result.success(emptyList())
                } }
                entered.await()
                store.saveAccount(mapOf("SESSDATA" to "second"), account)
                cancelled { f.caches.cachedDetail(bv, 0L) }
                cancelled { f.caches.invalidateDetail(bv, 0L) }
                cancelled { f.caches.forceDetail(bv, 0L, 0L, bv, 0L) }
                release.complete(Unit); cancelled { late.await() }
                store.saveAccount(mapOf("SESSDATA" to "first"), account)
                assertFalse(store.isPlaybackAuthorizationCurrent(receipt))
                cancelled { f.caches.cachedDetail(bv, 0L) }
                assertTrue(f.owner.isActive)
                assertTrue(f.invalidations.isEmpty())
                val next = store.capturePlaybackAuthorization(store.generation) { true }.receipt
                val successor = Fixture { store.withPlaybackAuthorizationAdmission(next, { true }) { Unit } }
                try {
                    val fresh = info().copy(title = "new receipt")
                    assertSame(fresh, successor.caches.detail(bv, 0L) { Result.success(fresh) }.getOrThrow())
                } finally { successor.close() }
            } finally { f.close() }
        }
    }

    @Test fun coveredPositiveCidForcePreservesFreshInitialWorker(): Unit = runBlocking {
        withTimeout(5_000L) {
            val f = Fixture()
            try {
                // The production raw method has already invalidated this exact target.
                f.caches.forceDetail(bv, 0L, 70L, bv, 70L)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val loads = AtomicInteger()
                val fresh = f.start { f.caches.initialPlayUrl(bv, 70L, 80, true, null) {
                    loads.incrementAndGet(); entered.complete(Unit); release.await(); play()
                } }
                entered.await()
                val joined = f.start { f.caches.initialPlayUrl(bv, 70L, 80, true, null) {
                    error("Identical INITIAL must share the live worker")
                } }
                val raw = info()
                f.caches.resolvedDetail(bv, 0L, raw, raw.copy(cid = 70L))
                assertFalse(fresh.isCancelled); assertFalse(fresh.isCompleted)
                assertTrue(f.invalidations.isEmpty())
                assertFalse(joined.isCompleted)
                release.complete(Unit); assertEquals(80, fresh.await()?.quality)
                assertEquals(80, joined.await()?.quality)
                assertEquals(1, loads.get())
                assertEquals(80, f.caches.initialPlayUrl(bv, 70L, 80, true, null) {
                    loads.incrementAndGet(); play()
                }?.quality)
                assertEquals(2, loads.get()) // INITIAL merges in-flight work, not completed URLs.
                assertTrue(f.owner.isActive)
            } finally { f.close() }
        }
    }

    @Test fun pendingRawDetailIsHiddenUntilUnknownCidActualInvalidationCompletes(): Unit = runBlocking {
        withTimeout(5_000L) {
            val f = Fixture()
            try {
                val entered = CompletableDeferred<Unit>()
                val retired = CompletableDeferred<Unit>()
                val stale = f.start { f.caches.initialPlayUrl(bv, 70L, 80, true, null) {
                    entered.complete(Unit)
                    try { CompletableDeferred<Unit>().await(); play() }
                    finally { retired.complete(Unit) }
                } }
                entered.await()
                f.caches.forceDetail(bv, 0L, 0L, bv, 0L)
                val raw = f.caches.detail(bv, 0L) { Result.success(info()) }.getOrThrow()
                // Exact real gap: OnDemand has stored raw data; Shared.detail has not resolved P yet.
                assertNull(f.caches.cachedDetail(bv, 0L))
                assertFalse(stale.isCompleted)
                val cid = resolveRequestedVideoCid(0L, raw.cid, raw.pages)
                f.caches.resolvedDetail(bv, 0L, raw, raw.copy(cid = cid))
                assertEquals(listOf(bv to 70L), f.invalidations.toList())
                cancelled { stale.await() }; retired.await()
                assertSame(raw, f.caches.cachedDetail(bv, 0L))
                assertEquals(80, f.caches.initialPlayUrl(bv, 70L, 80, true, null) { play() }?.quality)
            } finally { f.close() }
        }
    }

    @Test fun invalidCidFallbackAndNormalizedAvRequireTheirActualPageAndCannotBeConsumedByOtherP(): Unit = runBlocking {
        withTimeout(5_000L) {
            val fallback = Fixture()
            try {
                fallback.caches.forceDetail(bv, 0L, 999L, bv, 999L)
                val raw = fallback.caches.detail(bv, 0L) { Result.success(info()) }.getOrThrow()
                val cid = resolveRequestedVideoCid(999L, raw.cid, raw.pages)
                assertEquals(70L, cid)
                fallback.caches.resolvedDetail(bv, 0L, raw, raw.copy(cid = cid))
                assertEquals(listOf(bv to 70L), fallback.invalidations.toList())
            } finally { fallback.close() }
            val av = Fixture()
            try {
                val lookup = assertNotNull(resolveVideoInfoLookupInput(" av17 ", 0L))
                assertEquals("", lookup.bvid); assertEquals(17L, lookup.aid)
                av.caches.forceDetail(lookup.bvid, lookup.aid, 71L, "", 71L)
                val raw = av.caches.detail(lookup.bvid, lookup.aid) { Result.success(info()) }.getOrThrow()
                av.caches.resolvedDetail(lookup.bvid, lookup.aid, raw, raw.copy(cid = 70L))
                assertTrue(av.invalidations.isEmpty())
                assertNull(av.caches.cachedDetail(lookup.bvid, lookup.aid))
                val cid = resolveRequestedVideoCid(71L, raw.cid, raw.pages)
                av.caches.resolvedDetail(lookup.bvid, lookup.aid, raw, raw.copy(cid = cid))
                assertEquals(listOf(bv to 71L), av.invalidations.toList())
                assertSame(raw, av.caches.cachedDetail(lookup.bvid, lookup.aid))
                av.caches.resolvedDetail(lookup.bvid, lookup.aid, raw, raw.copy(cid = cid))
                assertEquals(1, av.invalidations.size)
            } finally { av.close() }
        }
    }
}
