package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.applyDynamicLikeCountChange
import com.android.purebilibili.feature.dynamic.DynamicLikeRequestGate
import com.android.purebilibili.feature.dynamic.dynamicAccountStorageName
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicCacheKeys as Keys
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

private fun mutationItem(id: String = "123", count: Int = 8): DynamicItem = Json.decodeFromString(
    """{"id_str":"$id","type":"DYNAMIC_TYPE_WORD","modules":{
        "module_author":{"mid":77,"name":"Fixture"},"module_dynamic":{"desc":{"text":"Original content"}},
        "module_stat":{"like":{"count":$count,"status":false},"forward":{"count":3}}}}""")
private fun mutationResponse(rows: List<DynamicItem>, more: Boolean = false) =
    DynamicFeedResponse(data = DynamicFeedData(rows, if (more) "tail" else "", more, "baseline"))
private class MutationFixture {
    val root = Files.createTempDirectory("bp-dynamic-confirmed-")
    val sessions = DesktopSessionStore(root.resolve("session.json"), persistent = false)
    val store = DesktopPluginStore(root.resolve("prefs"))
    val cache = DesktopDynamicCache(sessions, store)
    fun login(token: String = "fixture-A") = sessions.saveAccount(mapOf("SESSDATA" to token), AccountSummary(42, "Fixture", ""))
    fun registry() = DesktopDynamicCardStateRegistry(sessions, cache, sessions.generation)
    fun cacheNamespace() = dynamicAccountStorageName(Keys.PREFS_DYNAMIC_CACHE,
        checkNotNull(sessions.dynamicCacheOwner()).mid)
    suspend fun cachedItems(): List<DynamicItem> {
        cache.flush()
        val encoded = store.preferences(cacheNamespace())[Keys.KEY_DYNAMIC_CACHE]?.jsonPrimitive?.content
        return encoded?.let { Json.decodeFromString<List<DynamicItem>>(it) }.orEmpty()
    }
}

class DesktopDynamicCardStateRegistryTest {
    @Test fun cancelledCardScopeReleasesLikeGateWithoutRunningBody(): Unit = runBlocking {
        val gate = DynamicLikeRequestGate()
        val parent = SupervisorJob().apply { cancel() }
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        var ran = false
        val child = launchDesktopDynamicLike(gate, scope, "123") { ran = true }!!
        child.join()
        assertTrue(child.isCancelled); assertFalse(ran)
        assertTrue(gate.tryAcquire("123")); gate.release("123")
    }

    @Test fun cardDisposedBeforeQueuedLikeStartsReleasesSharedGate(): Unit = runBlocking {
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val parent = SupervisorJob(); val scope = CoroutineScope(parent + dispatcher)
        val gate = DynamicLikeRequestGate(); var ran = false
        val child = launchDesktopDynamicLike(gate, scope, "123") { ran = true }!!
        assertFalse(gate.tryAcquire("123"))
        parent.cancel()
        while (pending.isNotEmpty()) pending.removeFirst().run()
        child.join(); assertFalse(ran)
        assertTrue(gate.tryAcquire("123")); gate.release("123")
    }

    @Test fun onlyCompletionReleasesGateAndSiblingCanRetryAfterCancellation(): Unit = runBlocking {
        val parent = SupervisorJob(); val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        val gate = DynamicLikeRequestGate(); val response = CompletableDeferred<Unit>()
        val first = launchDesktopDynamicLike(gate, scope, "123") { response.await() }!!
        assertNull(launchDesktopDynamicLike(gate, scope, "123") { error("Duplicate admitted") })
        first.cancelAndJoin()
        val nextResponse = CompletableDeferred<Unit>()
        val second = launchDesktopDynamicLike(gate, scope, "123") { nextResponse.await() }!!
        first.cancel(); assertFalse(gate.tryAcquire("123"))
        nextResponse.complete(Unit); second.join()
        assertTrue(gate.tryAcquire("123")); gate.release("123")
        parent.cancel()
    }

    @Test fun confirmedReducersUpdateEveryRawModelAndOnlyCurrentAllWritesCache(): Unit = runBlocking {
        val f = MutationFixture(); f.login(); val session = f.cache.openCurrent()!!; val registry = f.registry()
        val item = mutationItem(); val sibling = mutationItem("124")
        fun timeline(type: String, rows: List<DynamicItem>): DesktopDynamicTimelineState {
            lateinit var model: DesktopDynamicTimelineState
            model = DesktopDynamicTimelineState(type, { _, _, _ -> mutationResponse(rows, true) },
                onAllTimelineChanged = { if (registry.isCurrentAll(model)) session.saveTimeline(it) })
            registry.register(model); return model
        }
        val retired = timeline("all", listOf(item, mutationItem("retired"))); retired.initialize(false)
        val video = timeline("video", listOf(item)); video.initialize(false)
        val current = timeline("all", listOf(item, sibling)); current.initialize(false)
        val generic = CommunityFeedState<DynamicItem, String>().also { it.rows = listOf(item, sibling); registry.register(it) }
        registry.register(current)
        val envelope = current.page
        registry.bindings.likeConfirmed("123", true)
        registry.bindings.repostConfirmed("123")
        for (rows in listOf(retired.page.items, video.page.items, current.page.items, generic.rows)) {
            assertEquals(9, rows.first().modules.module_stat!!.like.count)
            assertTrue(rows.first().modules.module_stat!!.like.status)
            assertEquals(4, rows.first().modules.module_stat!!.forward.count)
        }
        assertSame(sibling, current.page.items.last())
        assertEquals(envelope.copy(items = current.page.items), current.page)
        assertEquals(listOf("123", "124"), f.cachedItems().map { it.id_str })
        assertEquals(9, f.cachedItems().first().modules.module_stat!!.like.count)
        registry.bindings.removed("123")
        assertTrue(video.page.items.isEmpty()); assertEquals(listOf("124"), f.cachedItems().map { it.id_str })
        registry.close(); f.cache.shutdownForRestore()
    }

    @Test fun selectedUpAndAllMirrorAreChangedExactlyOnce(): Unit = runBlocking {
        val f = MutationFixture(); f.login(); val registry = f.registry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = DesktopDynamicTabsPreferences(DesktopPluginContext(f.store),checkNotNull(f.cache.openCurrent()))
        val item = mutationItem()
        val users = DesktopDynamicUsersState(scope, prefs, 42, { FollowingsData() }, { emptyList() }, { null },
            { mutationResponse(listOf(item)) }, { true })
        users.updateTimeline(listOf(item)); registry.register(users)
        users.selectUser(77)
        withTimeout(3000) { while (users.userLoading) delay(5) }
        assertEquals(listOf("123"), users.userItems.map { it.id_str })
        registry.bindings.likeConfirmed("123", true); registry.bindings.repostConfirmed("123")
        assertEquals(9, users.userItems.single().modules.module_stat!!.like.count)
        assertEquals(4, users.visibleItems().single().modules.module_stat!!.forward.count)
        registry.bindings.removed("123"); assertTrue(users.userItems.isEmpty()); assertTrue(users.visibleItems().isEmpty())
        users.close(); scope.cancel(); registry.close(); f.cache.shutdownForRestore()
    }

    @Test fun delayedRetiredAllFetchCannotBecomeTheLastCacheWriter(): Unit = runBlocking {
        val f = MutationFixture(); f.login(); val session = f.cache.openCurrent()!!; val registry = f.registry()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        lateinit var old: DesktopDynamicTimelineState
        old = DesktopDynamicTimelineState("all", { _, _, _ -> entered.complete(Unit); release.await(); mutationResponse(listOf(mutationItem("old"))) },
            onAllTimelineChanged = { if (registry.isCurrentAll(old)) session.saveTimeline(it) })
        registry.register(old); val request = async { old.initialize(false) }; entered.await()
        lateinit var current: DesktopDynamicTimelineState
        current = DesktopDynamicTimelineState("all", { _, _, _ -> mutationResponse(listOf(mutationItem("current"))) },
            onAllTimelineChanged = { if (registry.isCurrentAll(current)) session.saveTimeline(it) })
        registry.register(current); assertTrue(current.initialize(false)); release.complete(Unit); assertTrue(request.await())
        assertEquals(listOf("current"), f.cachedItems().map { it.id_str })
        registry.close(); f.cache.shutdownForRestore()
    }

    @Test fun guestNotInterestedKeepsRawRowsAndEmptyCacheDoesNotEraseIds(): Unit = runBlocking {
        val f = MutationFixture(); val registry = f.registry(); val session = f.cache.openCurrent()!!
        val raw = CommunityFeedState<DynamicItem, String>().also { it.rows = listOf(mutationItem()); registry.register(it) }
        registry.bindings.markNotInterested("123"); session.saveTimeline(emptyList()); f.cache.flush()
        assertEquals(listOf("123"), raw.rows.map { it.id_str })
        assertEquals(setOf("123"), session.notInterestedIds.value)
        assertFalse(Keys.KEY_DYNAMIC_CACHE in f.store.preferences(f.cacheNamespace()))
        registry.close(); f.cache.shutdownForRestore()
    }

    @Test fun sameMidCredentialReplacementAndClosedOwnerRejectOldCallbacks(): Unit = runBlocking {
        val f = MutationFixture(); f.login(); val registry = f.registry()
        val raw = CommunityFeedState<DynamicItem, String>().also { it.rows = listOf(mutationItem()); registry.register(it) }
        f.login("fixture-B")
        registry.bindings.likeConfirmed("123", true); registry.bindings.removed("123")
        assertEquals(8, raw.rows.single().modules.module_stat!!.like.count)
        assertFailsWith<IllegalStateException> { registry.bindings.markNotInterested("123") }
        registry.close(); assertFailsWith<IllegalStateException> { registry.bindings.markNotInterested("123") }
        f.cache.shutdownForRestore()
    }

    @Test fun pendingAppendSuccessFailureAndCancellationPreserveConfirmedFields(): Unit = runBlocking {
        for (result in listOf("success", "failure", "cancel")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
            val state = DesktopDynamicTimelineState("all", { _, _, _ ->
                if (calls++ == 0) mutationResponse(listOf(mutationItem()), true)
                else { entered.complete(Unit); release.await()
                    if (result == "failure") DynamicFeedResponse(code = -400, message = "fixture failure")
                    else mutationResponse(listOf(mutationItem("124"))) }
            })
            assertTrue(state.initialize(false))
            val request = async { state.loadMore(false) }; entered.await()
            state.mutateDynamicItems { applyDynamicLikeCountChange(it, "123", true) }
            if (result == "cancel") request.cancelAndJoin() else { release.complete(Unit); assertEquals(result == "success", request.await()) }
            assertEquals(9, state.page.items.first().modules.module_stat!!.like.count, result)
            assertTrue(state.page.items.first().modules.module_stat!!.like.status, result)
            assertFalse(state.busy, result); assertFalse(state.page.isLoading, result)
        }
    }

    @Test fun unfoldUsesOriginalPolicyAndKeepsPaginationEnvelope(): Unit = runBlocking {
        val f = MutationFixture(); f.login(); val registry = f.registry()
        val anchor = mutationItem().let { it.copy(modules = it.modules.copy(module_fold = DynamicFoldModule(listOf("124"), "展开1条相关动态"))) }
        val hidden = mutationItem("124").copy(visible = false)
        val state = DesktopDynamicTimelineState("all", { _, _, _ -> mutationResponse(listOf(anchor, hidden), true) })
        registry.register(state); state.initialize(false); val before = state.page
        registry.bindings.unfoldRelated("123")
        assertNull(state.page.items.first().modules.module_fold); assertTrue(state.page.items.last().visible)
        assertEquals(before.copy(items = state.page.items), state.page)
        registry.close(); f.cache.shutdownForRestore()
    }

    @Test fun lateOpusReadbackKeepsConfirmedFieldsAndFreshContent(): Unit = runBlocking {
        val old = mutationItem(); val updated = applyDynamicLikeCountChange(listOf(old), "123", true).single()
        val fresh = mutationItem(count = 800).let { it.copy(modules = it.modules.copy(module_dynamic = it.modules.module_dynamic!!.copy(desc = DynamicDesc("Richer opus content")))) }
        val result = mergeDesktopDynamicDetailReadback(DynamicDetailData(fresh), DynamicDetailData(updated), true, false, false, false).item!!
        assertEquals("Richer opus content", result.modules.module_dynamic!!.desc!!.text)
        assertEquals(9, result.modules.module_stat!!.like.count); assertTrue(result.modules.module_stat!!.like.status)
        assertEquals(fresh.modules.module_stat!!.forward, result.modules.module_stat!!.forward)
        assertNull(mergeDesktopDynamicDetailReadback(DynamicDetailData(fresh), null, false, false, false, true).item)
        assertEquals(fresh, mergeDesktopDynamicDetailReadback(DynamicDetailData(fresh), DynamicDetailData(updated), false, false, false, false).item)
        // A publish/delete count confirmed after either detail request starts
        // survives its late response; unrelated server fields remain fresh.
        for(count in listOf(1,7)) {
            val confirmed=updated.copy(modules=updated.modules.copy(module_stat=updated.modules.module_stat!!.let{
                it.copy(comment=it.comment.copy(count=count))}))
            val stale=fresh.copy(modules=fresh.modules.copy(module_stat=fresh.modules.module_stat!!.let{
                it.copy(comment=it.comment.copy(count=500))}))
            val merged=mergeDesktopDynamicDetailReadback(DynamicDetailData(stale),DynamicDetailData(confirmed),
                false,false,false,false,commentChanged=true).item!!
            assertEquals(count,merged.modules.module_stat!!.comment.count)
            assertEquals(stale.modules.module_stat!!.like,merged.modules.module_stat!!.like)
            assertEquals(stale.modules.module_stat!!.forward,merged.modules.module_stat!!.forward)
            assertEquals("Richer opus content",merged.modules.module_dynamic!!.desc!!.text)
            assertEquals(stale,mergeDesktopDynamicDetailReadback(DynamicDetailData(stale),DynamicDetailData(confirmed),
                false,false,false,false).item)
            val other=stale.copy(id_str="124")
            assertEquals(other,mergeDesktopDynamicDetailReadback(DynamicDetailData(other),DynamicDetailData(confirmed),
                false,false,false,false,commentChanged=true).item)
        }
    }
}
