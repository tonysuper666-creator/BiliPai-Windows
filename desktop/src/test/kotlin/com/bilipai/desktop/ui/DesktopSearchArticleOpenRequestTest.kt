package com.bilipai.desktop.ui

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import kotlin.test.*

/** The real Windows selection owner and real article HEAD resolver share a controlled
 * Call.Factory. No HTTP client thread, network, UI, account or parallel resolver is used. */
class DesktopSearchArticleOpenRequestTest {
    private class PendingCall(private val sent: Request, private val enqueued: (PendingCall) -> Unit) : Call {
        private var callback: Callback? = null
        private var executed = false
        var cancelled = false
            private set
        private val tags = ConcurrentHashMap<Class<*>, Any>()
        override fun <T : Any> tag(type: KClass<T>): T? = tag(type.java)
        override fun <T> tag(type: Class<out T>): T? = type.cast(tags[type]) ?: sent.tag(type)
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = tag(type.java, computeIfAbsent)
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T {
            tag(type)?.let { return it }
            val value = computeIfAbsent()
            return type.cast(tags.putIfAbsent(type, value) ?: value)
        }
        override fun request() = sent
        override fun execute(): Response = error("The production resolver must enqueue its Call")
        override fun enqueue(responseCallback: Callback) {
            check(!executed)
            executed = true
            callback = responseCallback
            enqueued(this)
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall(sent, enqueued)
        fun complete(location: String? = null) {
            val response = Response.Builder().request(sent).protocol(Protocol.HTTP_1_1)
                .code(if (location == null) 200 else 302).message("controlled article HEAD")
                .body("".toResponseBody())
            if (location != null) response.header("Location", location)
            checkNotNull(callback).onResponse(this, response.build())
        }
        fun fail() { checkNotNull(callback).onFailure(this, IOException("controlled article transport failure")) }
    }

    private class Harness : AutoCloseable {
        var owned = true
        var enqueued: (PendingCall) -> Unit = {}
        val calls = mutableListOf<PendingCall>()
        val published = mutableListOf<Pair<ArticleNavigationTarget, String>>()
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + DesktopSearchCaller() +
            CoroutineExceptionHandler { _, failure -> failures += failure })
        private val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = PendingCall(request) { enqueued(it) }.also { calls += it }
        }
        val selection = DesktopSearchArticleOpenRequest(scope, DesktopPersonalArticleResolver(factory, { owned }))
        fun open(id: Long, title: String) = selection.open(id, title) { target, selectedTitle ->
            DesktopSearchCaller.check()
            published += target to selectedTitle
        }
        fun assertArticle(id: Long, title: String) {
            assertEquals(1, published.size)
            val (target, selectedTitle) = published.single()
            assertEquals(id, assertIs<ArticleNavigationTarget.NativeArticle>(target).articleId)
            assertEquals(title, selectedTitle)
        }
        override fun close() {
            selection.close()
            scope.cancel()
            assertTrue(failures.isEmpty(), "Selection failures: $failures")
        }
    }

    @Test fun latestArticleCancelsTransportAndRejectsLateOlderResponse() {
        Harness().use { h ->
            val older = assertNotNull(h.open(11, "first article"))
            val newer = assertNotNull(h.open(22, "second article"))
            assertEquals(listOf("HEAD", "HEAD"), h.calls.map { it.request().method })
            assertTrue(older.isCancelled)
            assertTrue(h.calls[0].cancelled)
            assertFalse(h.calls[1].cancelled)
            h.calls[1].complete()
            h.calls[0].complete()
            assertTrue(newer.isCompleted)
            h.assertArticle(22, "second article")
        }
    }

    @Test fun leavingForAnotherResultAndReturningCannotOpenRetainedOldArticle() {
        Harness().use { h ->
            val old = assertNotNull(h.open(31, "before leaving Search"))
            h.selection.cancel() // The actual Search leaf calls this before every other result/back action.
            assertTrue(old.isCancelled)
            assertTrue(h.calls.single().cancelled)
            assertTrue(h.scope.isActive)
            // Return to the same retained entry before the old server response arrives.
            h.calls.single().complete()
            assertTrue(h.published.isEmpty())
            h.open(32, "after returning Search")
            h.calls[1].complete()
            h.assertArticle(32, "after returning Search")
        }
    }

    @Test fun closingRejectsLateResponseAndFurtherSelections() {
        Harness().use { h ->
            val pending = assertNotNull(h.open(41, "closing"))
            h.selection.close()
            assertTrue(pending.isCancelled)
            assertTrue(h.calls.single().cancelled)
            h.calls.single().complete()
            assertNull(h.open(42, "retired entry"))
            assertEquals(1, h.calls.size)
            assertTrue(h.published.isEmpty())
            assertTrue(h.scope.isActive)
        }
    }

    @Test fun cancellingParentCancelsTransport() {
        Harness().use { h ->
            val pending = assertNotNull(h.open(51, "retiring account scope"))
            h.scope.cancel()
            assertTrue(pending.isCancelled)
            assertTrue(h.calls.single().cancelled)
            h.calls.single().complete()
            assertTrue(h.published.isEmpty())
        }
    }

    @Test fun retiredOwnerCannotPublishNativeArticle() {
        Harness().use { h ->
            val pending = assertNotNull(h.open(61, "old account"))
            h.owned = false
            h.calls.single().complete()
            assertTrue(pending.isCancelled)
            assertTrue(h.published.isEmpty())
        }
    }

    @Test fun opusRedirectRetainsOriginalCvArticlePolicy() {
        Harness().use { h ->
            h.open(71, "original CV identity")
            h.calls.single().complete("https://www.bilibili.com/opus/987654321")
            h.assertArticle(71, "original CV identity")
        }
    }

    @Test fun immediateReplyPreservesCurrentRequestTitle() {
        Harness().use { h ->
            h.enqueued = { it.complete() }
            val immediate = assertNotNull(h.open(81, "immediate result"))
            assertTrue(immediate.isCompleted)
            assertFalse(immediate.isCancelled)
            h.assertArticle(81, "immediate result")
        }
    }

    @Test fun cancelledNetworkFailureDoesNotProduceFallbackArticle() {
        Harness().use { h ->
            h.open(91, "old fallback")
            h.open(92, "current fallback")
            h.calls[0].fail()
            assertTrue(h.published.isEmpty())
            h.calls[1].fail()
            h.assertArticle(92, "current fallback")
        }
    }

    @Test fun explicitCancelKeepsRetainedScopeAndNextSelectionUsable() {
        Harness().use { h ->
            h.open(101, "cancelled")
            h.selection.cancel()
            h.selection.cancel()
            assertTrue(h.scope.isActive)
            h.open(102, "new selection")
            h.calls[1].complete()
            h.assertArticle(102, "new selection")
        }
    }

    @Test fun invalidArticleDoesNotStartTransportOrNavigate() {
        Harness().use { h ->
            val invalid = assertNotNull(h.open(0, "invalid article"))
            assertTrue(invalid.isCompleted)
            assertTrue(h.calls.isEmpty())
            assertTrue(h.published.isEmpty())
        }
    }

    @Test fun alreadyCancelledScopeCannotResolveOrPublish() {
        Harness().use { h ->
            h.scope.cancel()
            val rejected = assertNotNull(h.open(111, "cancelled caller"))
            assertTrue(rejected.isCancelled)
            assertTrue(h.calls.isEmpty())
            assertTrue(h.published.isEmpty())
        }
    }
}
