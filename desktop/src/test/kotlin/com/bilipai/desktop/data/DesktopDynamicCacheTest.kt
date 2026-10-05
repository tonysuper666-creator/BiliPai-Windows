package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicCacheKeys as Keys
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import com.sun.nio.file.ExtendedOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

private fun cachedDynamic(id: String): DynamicItem = Json.decodeFromString(
    """{"id_str":"$id","type":"DYNAMIC_TYPE_WORD","modules":{}}""")
private fun cacheValues(store: DesktopPluginStore, mid:Long? = 42L) = store.preferences(com.android.purebilibili.feature.dynamic.dynamicAccountStorageName(Keys.PREFS_DYNAMIC_CACHE,mid))
private class CacheFixture(persistentSession: Boolean = false) {
    val root = Files.createTempDirectory("bp-dynamic-cache-")
    val sessions = DesktopSessionStore(root.resolve("session.json"), persistentSession)
    val store = DesktopPluginStore(root.resolve("prefs"))
    val cache = DesktopDynamicCache(sessions, store) { 1L }
    fun login(mid: Long = 42, credential: String = "synthetic-cache-session") =
        sessions.saveAccount(mapOf("SESSDATA" to credential), AccountSummary(mid, "Fixture", ""))
}

private class HeldCacheGuard(private val delegate: DesktopDynamicCacheSessionGuard) : DesktopDynamicCacheSessionGuard {
    private val checksBeforeHold = AtomicInteger(0)
    fun holdNextTransaction() { checksBeforeHold.set(2) }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    override fun dynamicCacheOwner() = delegate.dynamicCacheOwner()
    override fun withCurrentDynamicCacheOwner(owner: DesktopDynamicCacheOwner, block: () -> Unit): Boolean {
        // Admission is owner-checked too. Hold its later Root disk transaction.
        if (checksBeforeHold.getAndUpdate { if (it > 0) it - 1 else 0 } == 1) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "fixture transaction release timed out" }
        }
        return delegate.withCurrentDynamicCacheOwner(owner, block)
    }
}

/** Reproduces an old pre-check completing after a new owner already queued a save. */
private class AdmissionRaceGuard(private val delegate: DesktopDynamicCacheSessionGuard) : DesktopDynamicCacheSessionGuard {
    @Volatile var delayedOwner: DesktopDynamicCacheOwner? = null
    val delayOld = AtomicBoolean(false)
    val oldEntered = CountDownLatch(1)
    val releaseOld = CountDownLatch(1)
    val holdCurrentTransaction = AtomicInteger(0)
    val currentEntered = CountDownLatch(1)
    val releaseCurrent = CountDownLatch(1)
    private fun oldPause(owner: DesktopDynamicCacheOwner?) {
        if (owner == delayedOwner && delayOld.compareAndSet(true, false)) {
            oldEntered.countDown(); check(releaseOld.await(5, TimeUnit.SECONDS))
        }
    }
    override fun dynamicCacheOwner(): DesktopDynamicCacheOwner? {
        val snapshot = delegate.dynamicCacheOwner(); oldPause(snapshot)
        return snapshot
    }
    override fun withCurrentDynamicCacheOwner(owner: DesktopDynamicCacheOwner, block: () -> Unit): Boolean {
        oldPause(owner)
        if (owner != delayedOwner && holdCurrentTransaction.getAndUpdate { if (it > 0) it - 1 else 0 } == 1) {
            currentEntered.countDown(); check(releaseCurrent.await(5, TimeUnit.SECONDS))
        }
        return delegate.withCurrentDynamicCacheOwner(owner, block)
    }
}

class DesktopDynamicCacheTest {
    @Test fun retiredAdmissionCannotSupersedeAlreadyAcceptedNewOwnerSnapshot(): Unit = runBlocking {
        val f = CacheFixture(); f.login(); f.cache.shutdownForRestore()
        val guard = AdmissionRaceGuard(f.sessions); val cache = DesktopDynamicCache(guard, f.store)
        val old = checkNotNull(cache.openCurrent()); guard.delayedOwner = old.owner; guard.delayOld.set(true)
        val oldSave = async(Dispatchers.Default) { old.saveTimeline(listOf(cachedDynamic("retired"))) }
        assertTrue(guard.oldEntered.await(5, TimeUnit.SECONDS))
        f.login(credential = "synthetic-new-owner")
        val current = checkNotNull(cache.openCurrent())
        guard.holdCurrentTransaction.set(2)
        val mark = async(Dispatchers.Default) { current.markNotInterested("current-hidden") }
        assertTrue(guard.currentEntered.await(5, TimeUnit.SECONDS))
        current.saveTimeline(listOf(cachedDynamic("current-accepted")))
        guard.releaseOld.countDown(); oldSave.await(); assertNotNull(old.writeFailure.value)
        guard.releaseCurrent.countDown(); mark.await(); cache.flush()
        assertEquals(listOf("current-accepted"), current.cachedAllItems.value.map { it.id_str })
        assertEquals("current-accepted", Json.parseToJsonElement(cacheValues(f.store)[Keys.KEY_DYNAMIC_CACHE]!!.jsonPrimitive.content)
            .jsonArray.single().jsonObject["id_str"]!!.jsonPrimitive.content)
        cache.shutdownForRestore()
    }
    @Test fun cancellingCardWaitDoesNotCancelItsAcceptedRootTransaction(): Unit = runBlocking {
        val f = CacheFixture(); f.login(); f.cache.shutdownForRestore()
        val guard = HeldCacheGuard(f.sessions); val cache = DesktopDynamicCache(guard, f.store)
        val session = checkNotNull(cache.openCurrent()); guard.holdNextTransaction()
        val cardWait = launch(Dispatchers.Default) { session.markNotInterested("accepted-before-card-close") }
        assertTrue(guard.entered.await(5, TimeUnit.SECONDS)); cardWait.cancelAndJoin()
        assertTrue(session.notInterestedIds.value.isEmpty())
        guard.release.countDown(); cache.flush()
        assertEquals(setOf("accepted-before-card-close"), session.notInterestedIds.value)
        cache.shutdownForRestore()
    }
    @Test fun coldRestartRestoresOneHundredRawItemsWithoutTimestampExpiry(): Unit = runBlocking {
        val f = CacheFixture(persistentSession = true); f.login()
        val session = checkNotNull(f.cache.openCurrent())
        session.saveTimeline((1..110).map { cachedDynamic(it.toString()) })
        session.markNotInterested("1"); f.cache.flush()
        assertEquals(100, session.cachedAllItems.value.size)
        val encoded = cacheValues(f.store)[Keys.KEY_DYNAMIC_CACHE]!!.jsonPrimitive.content
        val payload = Json.parseToJsonElement(encoded).jsonArray.mapIndexed { index, item ->
            if (index == 0) JsonObject(item.jsonObject + ("unknown_upstream_field" to JsonPrimitive(1))) else item
        }
        f.store.update(session.cacheNamespace, mapOf(Keys.KEY_DYNAMIC_CACHE to JsonPrimitive(JsonArray(payload).toString())))
        val firstOwner = session.owner
        f.cache.shutdownForRestore(); f.store.freezeWrites()
        val restoredSessions = DesktopSessionStore(f.root.resolve("session.json"))
        val restoredStore = DesktopPluginStore(f.root.resolve("prefs"))
        val restored = DesktopDynamicCache(restoredSessions, restoredStore)
        val cold = checkNotNull(restored.openCurrent())
        assertNotEquals(firstOwner.epoch, cold.owner.epoch)
        assertEquals(firstOwner.namespaceTag, cold.owner.namespaceTag)
        assertEquals((1..100).map(Int::toString), cold.cachedAllItems.value.map { it.id_str })
        assertEquals(setOf("1"), cold.notInterestedIds.value)
        assertEquals(1L, cacheValues(restoredStore)[Keys.KEY_DYNAMIC_CACHE_TIME]!!.jsonPrimitive.long)
        // Hidden content remains in the original raw cache and can still seed the UP rail.
        assertEquals("1", cold.cachedAllItems.value.first().id_str)
        restored.shutdownForRestore()
    }

    @Test fun guestLocalActionAndOwnerSwitchNeverBorrowPreviousAccountState(): Unit = runBlocking {
        val f = CacheFixture(); val guest = checkNotNull(f.cache.openCurrent())
        assertEquals(0L, guest.owner.mid)
        guest.markNotInterested(" guest-hidden ")
        assertEquals(setOf("guest-hidden"), guest.notInterestedIds.value)
        assertFailsWith<IllegalArgumentException> { guest.markNotInterested("  ") }
        f.login(); val login = checkNotNull(f.cache.openCurrent())
        assertTrue(login.notInterestedIds.value.isEmpty()); assertTrue(login.cachedAllItems.value.isEmpty())
        login.saveTimeline(listOf(cachedDynamic("private"))); f.cache.flush()
        assertNull(cacheValues(f.store)[Keys.KEY_NOT_INTERESTED_DYNAMIC_IDS])
        assertFailsWith<IllegalStateException> { guest.markNotInterested("late-guest") }
        f.sessions.logout(); val newGuest = checkNotNull(f.cache.openCurrent())
        assertTrue(newGuest.cachedAllItems.value.isEmpty()); assertEquals(setOf("guest-hidden"),newGuest.notInterestedIds.value)
        newGuest.markNotInterested("new-guest")
        assertNull(cacheValues(f.store,null)[Keys.KEY_DYNAMIC_CACHE]); assertNull(cacheValues(f.store,null)[Keys.KEY_DYNAMIC_CACHE_TIME])
        assertEquals(setOf("guest-hidden","new-guest"), newGuest.notInterestedIds.value)
        f.cache.shutdownForRestore()
    }

    @Test fun originalFiveHundredIdOrderAndParallelAcknowledgementsArePreserved(): Unit = runBlocking {
        val f = CacheFixture(); f.login()
        val owner = checkNotNull(f.sessions.dynamicCacheOwner())
        f.store.update(Keys.PREFS_DYNAMIC_CACHE, mapOf("_desktop_session_owner_v1" to JsonPrimitive(owner.namespaceTag),
            Keys.KEY_NOT_INTERESTED_DYNAMIC_IDS to JsonArray((1..500).map { JsonPrimitive(it.toString()) })))
        val session = checkNotNull(f.cache.openCurrent())
        session.markNotInterested("501")
        assertEquals((2..501).map(Int::toString), session.notInterestedIds.value.toList())
        session.markNotInterested("2")
        assertEquals((2..501).map(Int::toString), session.notInterestedIds.value.toList())
        val first = async { session.markNotInterested("parallel-A") }
        val second = async { session.markNotInterested("parallel-B") }
        first.await(); second.await()
        assertEquals(500, session.notInterestedIds.value.size)
        assertTrue("parallel-A" in session.notInterestedIds.value); assertTrue("parallel-B" in session.notInterestedIds.value)
        f.cache.shutdownForRestore()
    }

    @Test fun acceptedEmptyLatestSaveDrainsBeforeStoreFreezeAndCannotBeRevived(): Unit = runBlocking {
        val f = CacheFixture(); f.login(); f.cache.shutdownForRestore()
        val guard = HeldCacheGuard(f.sessions); val cache = DesktopDynamicCache(guard, f.store)
        val session = checkNotNull(cache.openCurrent()); session.markNotInterested("hidden")
        guard.holdNextTransaction(); session.saveTimeline(listOf(cachedDynamic("first")))
        assertTrue(guard.entered.await(5, TimeUnit.SECONDS))
        session.saveTimeline(listOf(cachedDynamic("superseded"))); session.saveTimeline(emptyList())
        val closing = async(Dispatchers.Default) { cache.shutdownForRestore(); f.store.freezeWrites() }
        delay(50); assertFalse(closing.isCompleted)
        guard.release.countDown(); closing.await()
        assertNull(cacheValues(f.store)[Keys.KEY_DYNAMIC_CACHE]); assertNull(cacheValues(f.store)[Keys.KEY_DYNAMIC_CACHE_TIME])
        assertEquals(setOf("hidden"), session.notInterestedIds.value)
        assertTrue(session.cachedAllItems.value.isEmpty())
        assertFailsWith<IllegalStateException> { session.markNotInterested("late") }
        session.saveTimeline(listOf(cachedDynamic("must-not-return")))
        assertNotNull(session.writeFailure.value); assertNull(cacheValues(f.store)[Keys.KEY_DYNAMIC_CACHE])
        cache.shutdownForRestore()
    }

    @Test fun queuedOldOwnerCannotPublishAfterSameMidCredentialRotation(): Unit = runBlocking {
        val f = CacheFixture(); f.login(); f.cache.shutdownForRestore()
        val guard = HeldCacheGuard(f.sessions); val cache = DesktopDynamicCache(guard, f.store)
        val old = checkNotNull(cache.openCurrent()); old.markNotInterested("old-hidden")
        guard.holdNextTransaction()
        val late = async(Dispatchers.Default) { runCatching { old.markNotInterested("must-not-publish") } }
        assertTrue(guard.entered.await(5, TimeUnit.SECONDS))
        f.login(credential = "synthetic-rotated-session")
        guard.release.countDown(); assertTrue(late.await().isFailure)
        assertEquals(setOf("old-hidden"), old.notInterestedIds.value)
        val current = checkNotNull(cache.openCurrent())
        assertNotEquals(old.owner, current.owner); assertEquals(setOf("old-hidden"),current.notInterestedIds.value)
        current.markNotInterested("current-hidden")
        assertEquals(setOf("old-hidden","current-hidden"), current.notInterestedIds.value)
        assertEquals(listOf("old-hidden","current-hidden"), cacheValues(f.store)[Keys.KEY_NOT_INTERESTED_DYNAMIC_IDS]!!.jsonArray.map { it.jsonPrimitive.content })
        cache.shutdownForRestore()
    }

    @Test fun actualWindowsReplacementFailureKeepsDurableAndPublishedStateThenWriterRecovers(): Unit = runBlocking {
        val f = CacheFixture(); f.login(); val session = checkNotNull(f.cache.openCurrent())
        session.markNotInterested("before"); val file = f.store.root.resolve("plugin-settings.json")
        val original = Files.readAllBytes(file)
        FileChannel.open(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE).use {
            assertFailsWith<Exception> { session.markNotInterested("failed") }
            assertEquals(setOf("before"), session.notInterestedIds.value)
            assertContentEquals(original, Files.readAllBytes(file)); assertNotNull(session.writeFailure.value)
        }
        session.markNotInterested("after")
        assertEquals(setOf("before", "after"), session.notInterestedIds.value); assertNull(session.writeFailure.value)
        f.cache.shutdownForRestore()
    }

    @Test fun malformedPayloadIsSoftAbsenceAndMalformedNamespaceDoesNotBlockOpening(): Unit = runBlocking {
        val f = CacheFixture(); f.login(); val owner = checkNotNull(f.sessions.dynamicCacheOwner())
        f.store.update(Keys.PREFS_DYNAMIC_CACHE, mapOf("_desktop_session_owner_v1" to JsonPrimitive(owner.namespaceTag),
            Keys.KEY_DYNAMIC_CACHE to JsonPrimitive("{broken"), Keys.KEY_DYNAMIC_CACHE_TIME to JsonPrimitive(1)))
        val session = checkNotNull(f.cache.openCurrent())
        assertTrue(session.cachedAllItems.value.isEmpty()); assertNull(session.writeFailure.value)
        f.cache.shutdownForRestore()
        val root = Files.createTempDirectory("bp-invalid-dynamic-cache-")
        Files.writeString(root.resolve("plugin-settings.json"), """{"dynamic_cache_42":"invalid","unrelated":{"keep":7}}""")
        val malformedStore = DesktopPluginStore(root); val cache = DesktopDynamicCache(f.sessions, malformedStore)
        val usable = checkNotNull(cache.openCurrent())
        assertTrue(usable.cachedAllItems.value.isEmpty()); assertNotNull(usable.writeFailure.value)
        usable.saveTimeline(listOf(cachedDynamic("remote-still-usable"))); cache.flush()
        assertNotNull(usable.writeFailure.value)
        assertEquals(7, Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["unrelated"]!!.jsonObject["keep"]!!.jsonPrimitive.int)
        cache.shutdownForRestore()
    }
}
