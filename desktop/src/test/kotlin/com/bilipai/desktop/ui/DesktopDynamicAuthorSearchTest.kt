package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.data.DesktopDynamicCache
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopDynamicAuthorSearchTest {
    private val caches = mutableListOf<DesktopDynamicCache>()
    @AfterTest fun closeCaches(): Unit = runBlocking { caches.forEach { it.shutdownForRestore() }; caches.clear() }
    private fun preferences() = dynamicAccountTestPreferences(
        DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("dynamic-author-search-")))) { caches += it }
    private fun following(page: Int, total: Int) = FollowingsData(
        ((page - 1) * 50 + 1..minOf(page * 50, total)).map { FollowingUser(it.toLong(), "User-$it", "") }, total)
    private fun state(scope: CoroutineScope, prefs: DesktopDynamicTabsPreferences = preferences(),
        owned: () -> Boolean = { true }, follow: suspend (Int) -> FollowingsData = { FollowingsData() },
        request: suspend (Map<String, String>) -> DynamicFeedResponse = { tabsResponse(emptyList()) },
        wait: suspend (Long) -> Unit = {}) = DesktopDynamicUsersState(scope, prefs, 42,
            follow, { emptyList() }, { null }, request, owned, startupDelay = wait)
    private suspend fun eventually(predicate: () -> Boolean) { withTimeout(5000) { while (!predicate()) delay(5) } }

    @Test fun searchFromPgcRevealsTheMergedAllFeedForThisPageOnlyAndUsesTheExistingSelectedUserFeed(): Unit = runBlocking {
        val prefs = preferences(); prefs.setVisibleTabs(setOf("pgc", "article")); prefs.setOrder(listOf("pgc", "article"))
        val calls = mutableListOf<Map<String, String>>()
        val value = state(this, prefs, request = { calls += it; tabsResponse(listOf(tabsDynamic("author", 81))) })
        try {
            value.applyTabs(prefs.visibleTabs.first(), prefs.tabOrder.first())
            assertEquals(listOf("pgc", "article"), value.visibleTabs.map { it.id })
            assertEquals(2, value.selectedLogicalTab)
            value.selectAuthorSearchResult(81)
            eventually { !value.userLoading && value.userItems.isNotEmpty() }
            assertEquals(81L, value.selectedUid); assertEquals(0, value.selectedLogicalTab)
            assertEquals("81", calls.single()["host_mid"])
            assertEquals(setOf("pgc", "article"), prefs.visibleTabs.first())
            assertEquals(listOf("pgc", "article"), prefs.tabOrder.first())
            value.applyTabs(setOf("pgc", "article"), listOf("pgc", "article"))
            assertEquals(listOf("pgc", "article", "all"), value.visibleTabs.map { it.id })
            assertEquals(0, value.selectedLogicalTab)
            val successor = state(this, prefs)
            try { assertEquals(listOf("pgc", "article"), successor.visibleTabs.map { it.id }); assertNull(successor.selectedUid) }
            finally { successor.close() }
        } finally { value.close() }
    }

    @Test fun searchInsideACategoryKeepsItAndOnlyFiltersByTheAuthor(): Unit = runBlocking {
        val prefs = preferences(); prefs.setVisibleTabs(setOf("video", "article")); prefs.setOrder(listOf("article", "video"))
        val calls = mutableListOf<Map<String, String>>()
        val value = state(this, prefs, request = { calls += it; tabsResponse(listOf(tabsDynamic("author", 81))) })
        try {
            assertEquals(3, value.selectedLogicalTab)
            value.selectAuthorSearchResult(81)
            eventually { !value.userLoading && value.userItems.isNotEmpty() }
            assertEquals(3, value.selectedLogicalTab, "The article category is kept")
            assertEquals(listOf("article", "video"), value.visibleTabs.map { it.id }, "No hidden tab is revealed")
            assertEquals(81L, value.selectedUid); assertEquals("81", calls.single()["host_mid"])
        } finally { value.close() }
    }

    @Test fun selectingTheSameAuthorKeepsCachedRowsAndTheCurrentCategory(): Unit = runBlocking {
        var calls = 0
        val value = state(this, request = { calls++; tabsResponse(listOf(tabsDynamic("kept", 81))) })
        try {
            value.selectAuthorSearchResult(81); eventually { !value.userLoading && value.userItems.isNotEmpty() }
            val cached = value.userItems
            value.selectTab(3)
            value.selectAuthorSearchResult(81)
            assertEquals(3, value.selectedLogicalTab)
            assertFalse(value.userLoading); assertEquals(81L, value.selectedUid)
            assertSame(cached, value.userItems); yield(); assertEquals(1, calls)
        } finally { value.close() }
    }

    @Test fun invalidOrRetiredSearchCallbacksCannotChangePreferencesOrStartRequests(): Unit = runBlocking {
        val prefs = preferences(); prefs.setSelectedTab(0)
        val epoch = AtomicLong(1); var calls = 0
        val value = state(this, prefs, owned = { epoch.get() == 1L },
            follow = { calls++; following(it, 1) }, request = { calls++; tabsResponse(emptyList()) })
        try {
            val file = prefs.context.store.root.resolve("plugin-settings.json"); val before = Files.readAllBytes(file)
            value.selectAuthorSearchResult(0); value.selectAuthorSearchResult(-1)
            epoch.incrementAndGet()
            value.prepareAuthorSearch(); value.selectAuthorSearchResult(81); yield()
            assertEquals(0, calls); assertNull(value.selectedUid); assertEquals(0, value.selectedLogicalTab)
            assertContentEquals(before, Files.readAllBytes(file))
        } finally { value.close() }
    }

    @Test fun searchDuringAnActivePartialHydrationQueuesOneCompleteFollowingReload(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val pages = mutableListOf<Int>()
        // The pgc category has no strip, so activation runs the partial hydration alone (a strip tab loads the full list at once).
        val pgc = preferences(); pgc.setSelectedTab(2)
        val value = state(this, pgc, follow = { page ->
            pages += page
            if (pages.size == 1) { entered.complete(Unit); release.await() }
            following(page, 51)
        })
        try {
            value.activateStartupLoads {}; entered.await(); assertTrue(value.authorSearchLoading)
            repeat(5) { value.prepareAuthorSearch() }
            release.complete(Unit)
            eventually { value.users.size == 51 && !value.authorSearchLoading }
            assertEquals(listOf(1, 1, 2), pages)
            value.prepareAuthorSearch(); yield(); assertEquals(listOf(1, 1, 2), pages)
        } finally { release.complete(Unit); value.close() }
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runUntil(predicate: () -> Boolean) {
            var count = 0
            while (!predicate()) { check(count++ < 1000); checkNotNull(queue.poll()) { "No queued fixture continuation" }.run() }
        }
        fun drain() {
            var count = 0
            while (true) { val next = queue.poll() ?: return; check(count++ < 1000); next.run() }
        }
    }

    @Test fun fullRequestQueuedBeforePartialStartsIsRetainedWhenItsActualJobFindsTheLoaderBusy(): Unit = runBlocking {
        val dispatcher = QueueDispatcher(); val child = CoroutineScope(coroutineContext + dispatcher)
        val delaySeen = CompletableDeferred<Unit>(); val delayRelease = CompletableDeferred<Unit>()
        val firstSeen = CompletableDeferred<Unit>(); val firstRelease = CompletableDeferred<Unit>(); val pages = mutableListOf<Int>()
        val pgc = preferences(); pgc.setSelectedTab(2)
        val value = state(child, pgc, follow = { page ->
            pages += page
            if (pages.size == 1) { firstSeen.complete(Unit); firstRelease.await() }
            following(page, 51)
        }, wait = { delaySeen.complete(Unit); delayRelease.await() })
        try {
            value.activateStartupLoads {}; dispatcher.runUntil { delaySeen.isCompleted }
            // Both jobs are queued while isLoading=false; force partial before full, without sleeps.
            delayRelease.complete(Unit); value.prepareAuthorSearch()
            dispatcher.runUntil { firstSeen.isCompleted }; dispatcher.drain()
            assertTrue(value.authorSearchLoading); assertEquals(listOf(1), pages)
            firstRelease.complete(Unit); dispatcher.drain()
            assertEquals(51, value.users.size); assertFalse(value.authorSearchLoading)
            assertEquals(listOf(1, 1, 2), pages)
        } finally {
            delayRelease.complete(Unit); firstRelease.complete(Unit); value.close(); dispatcher.drain()
        }
    }

    @Test fun aFailedFollowingBatchClearsLoadingAndTheNextSearchCanRetry(): Unit = runBlocking {
        var calls = 0
        val value = state(this, follow = { page -> if (calls++ == 0) throw IOException("Synthetic following failure") else following(page, 1) })
        try {
            value.prepareAuthorSearch()
            eventually { value.followingsError != null && !value.authorSearchLoading }
            assertEquals(1, calls); assertTrue(value.users.isEmpty())
            value.prepareAuthorSearch(); eventually { value.users.size == 1 && !value.authorSearchLoading }
            assertNull(value.followingsError); assertEquals(2, calls)
        } finally { value.close() }
    }

    @Test fun retiredOwnerRejectsLateFollowingRowsAndCannotStartAnotherPageOrSearch(): Unit = runBlocking {
        val epoch = AtomicLong(1); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>(); var requests = 0
        val value = state(this, owned = { epoch.get() == 1L }, follow = { page ->
            requests++; entered.complete(Unit)
            try { withContext(NonCancellable) { release.await() }; following(page, 51) }
            finally { finished.complete(Unit) }
        })
        try {
            value.prepareAuthorSearch(); entered.await(); epoch.incrementAndGet(); value.close(); release.complete(Unit)
            finished.await(); yield()
            assertEquals(1, requests); assertTrue(value.users.isEmpty()); assertNull(value.followingsError)
            value.prepareAuthorSearch(); value.selectAuthorSearchResult(81); yield()
            assertEquals(1, requests); assertNull(value.selectedUid)
        } finally { release.complete(Unit); value.close() }
    }
}
