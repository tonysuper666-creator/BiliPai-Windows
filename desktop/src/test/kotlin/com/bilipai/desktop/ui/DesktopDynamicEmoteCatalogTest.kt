package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.EmoteData
import com.android.purebilibili.data.model.response.EmoteItem
import com.android.purebilibili.data.model.response.EmotePackage
import com.android.purebilibili.data.model.response.EmoteResponse
import com.android.purebilibili.feature.dynamic.components.DesktopDynamicEmoteCatalog
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** No transport: only the actual generated catalog, API schema and dispatcher. */
private class QueuedEmoteDispatcher : CoroutineDispatcher() {
    private val pending = ArrayDeque<Runnable>()
    val pendingCount: Int get() = pending.size
    override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
    fun runNext() { checkNotNull(pending.pollFirst()) { "Expected one queued continuation" }.run() }
    fun drain() {
        repeat(100) { (pending.pollFirst() ?: return).run() }
        error("Unexpected unbounded fixture scheduling")
    }
}

private class PausedEmoteApi {
    val businesses = mutableListOf<String>()
    private var dynamicContinuation: Continuation<EmoteResponse>? = null
    val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { proxy, method, args ->
        when (method.name) {
            "getEmotes" -> {
                @Suppress("UNCHECKED_CAST")
                val params = requireNotNull(args)[0] as Map<String, String>
                val business = requireNotNull(params["business"])
                businesses += business
                if (business == "dynamic") {
                    check(dynamicContinuation == null)
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args.last() as Continuation<EmoteResponse>
                    // Interception makes resume an observable queued step, rather
                    // than executing the suspend state machine inline in the test.
                    dynamicContinuation = continuation.intercepted()
                    COROUTINE_SUSPENDED
                } else response(business)
            }
            "toString" -> "TaskEmoteApi"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected API method: ${method.name}")
        }
    } as BilibiliApi

    fun resumeDynamic() {
        val continuation = requireNotNull(dynamicContinuation)
        dynamicContinuation = null
        continuation.resume(response("dynamic"))
    }
    private fun response(business: String) = EmoteResponse(0, data = EmoteData(packages = listOf(
        EmotePackage(1, business, emote = listOf(EmoteItem(1, "[$business-fixture]", "https://example.invalid/$business.png")))
    )))
}

private fun rawEmoteCache(catalog: DesktopDynamicEmoteCatalog): Any =
    catalog.javaClass.getDeclaredField("cached").apply { isAccessible = true }.get(catalog)

class DesktopDynamicEmoteCatalogTest {
    @Test fun initiallyRetiredCatalogUsesCancellationAndDoesNotStartApi(): Unit = runBlocking {
        val api = PausedEmoteApi()
        val catalog = DesktopDynamicEmoteCatalog(api.api, { 42L }, { true }, { false })
        assertFailsWith<CancellationException> { catalog.ensureLoaded() }
        assertTrue(api.businesses.isEmpty())
        assertEquals(emptyMap<String, String>(), rawEmoteCache(catalog))
        assertTrue(catalog.snapshot().isEmpty())
    }

    @Test fun retirementDuringActualCatalogLoadDoesNotPublishLateEntries(): Unit = runBlocking {
        val dispatcher = QueuedEmoteDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val epoch = AtomicLong(1)
        val api = PausedEmoteApi()
        val catalog = DesktopDynamicEmoteCatalog(api.api, { 42L }, { true }, { epoch.get() == 1L })
        try {
            val loading = scope.async { catalog.ensureLoaded() }
            dispatcher.runNext()
            assertEquals(listOf("dynamic"), api.businesses)
            assertFalse(loading.isCompleted)
            epoch.incrementAndGet()
            api.resumeDynamic()
            dispatcher.drain()
            assertFailsWith<CancellationException> { loading.await() }
            assertEquals(emptyMap<String, String>(), rawEmoteCache(catalog))
            assertTrue(catalog.snapshot().isEmpty())
        } finally { scope.cancel(); dispatcher.drain() }
    }

    @Test fun retiredMutexWaiterCannotReturnTheFirstLoadFullyCachedEntries(): Unit = runBlocking {
        val dispatcher = QueuedEmoteDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val epoch = AtomicLong(1)
        val api = PausedEmoteApi()
        val catalog = DesktopDynamicEmoteCatalog(api.api, { 42L }, { true }, { epoch.get() == 1L })
        try {
            val first = scope.async { catalog.ensureLoaded() }
            dispatcher.runNext() // first owns catalog mutex and pauses at dynamic API
            val waiter = scope.async { catalog.ensureLoaded() }
            dispatcher.runNext() // second entered alive, then waits for that mutex
            assertFalse(first.isCompleted)
            assertFalse(waiter.isCompleted)
            api.resumeDynamic()
            assertTrue(dispatcher.pendingCount > 0)
            dispatcher.runNext() // dynamic+reply, cache publication, unlock; waiter stays queued
            assertTrue(first.isCompleted)
            assertFalse(waiter.isCompleted)
            assertTrue(dispatcher.pendingCount > 0)
            assertEquals(listOf("dynamic", "reply"), api.businesses)
            val validFirstMap = first.await()
            assertTrue("[dynamic-fixture]" in validFirstMap)
            assertTrue("[reply-fixture]" in validFirstMap)
            val publishedCache = rawEmoteCache(catalog)
            epoch.incrementAndGet()
            dispatcher.drain()
            assertFailsWith<CancellationException> { waiter.await() }
            assertEquals(listOf("dynamic", "reply"), api.businesses)
            assertSame(publishedCache, rawEmoteCache(catalog))
            assertTrue(catalog.snapshot().isEmpty())
        } finally { scope.cancel(); dispatcher.drain() }
    }
}
