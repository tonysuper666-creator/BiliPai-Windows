package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicCacheKeys as Keys
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Existing UI contracts now use a real private Root cache session, never an unowned user namespace. */
internal fun dynamicAccountTestPreferences(context:DesktopPluginContext, register:(DesktopDynamicCache)->Unit):DesktopDynamicTabsPreferences = runBlocking {
    val sessions=DesktopSessionStore(context.store.root.resolve("synthetic-session.json"),false)
    sessions.saveAccount(mapOf("SESSDATA" to "synthetic-tabs"),AccountSummary(42,"Fixture",""))
    val cache=DesktopDynamicCache(sessions,context.store)
    register(cache)
    DesktopDynamicTabsPreferences(context,checkNotNull(cache.openCurrent()))
}

private class DynamicAccountFixture(hold:Boolean=false) {
    val root=Files.createTempDirectory("dynamic-account-root-")
    val sessions=DesktopSessionStore(root.resolve("session.json"),false)
    val store=DesktopPluginStore(root.resolve("preferences"))
    val context=DesktopPluginContext(store)
    val waiting=AtomicBoolean(false)
    val entered=CountDownLatch(1)
    val release=CountDownLatch(1)
    private val guard=object:DesktopDynamicCacheSessionGuard {
        override fun dynamicCacheOwner()=sessions.dynamicCacheOwner()
        override fun withCurrentDynamicCacheOwner(owner:DesktopDynamicCacheOwner,block:()->Unit):Boolean {
            if(hold && waiting.compareAndSet(true,false)) {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            }
            return sessions.withCurrentDynamicCacheOwner(owner,block)
        }
    }
    val cache=DesktopDynamicCache(guard,store)
    fun login(mid:Long,credential:String="synthetic-$mid")=sessions.saveAccount(mapOf("SESSDATA" to credential),AccountSummary(mid,"Fixture",""))
    suspend fun open()=checkNotNull(cache.openCurrent())
    fun prefs(session:DesktopDynamicCacheSession)=DesktopDynamicTabsPreferences(context,session)
}

private fun accountDynamic(id:String):DynamicItem=Json.decodeFromString("""{"id_str":"$id","type":"DYNAMIC_TYPE_WORD","modules":{}}""")

class DesktopDynamicAccountStorageTest {
    @Test fun stoppedRootCacheRejectsNewUserPreferenceWrites():Unit=runBlocking {
        val f=DynamicAccountFixture()
        f.login(101);val preferences=f.prefs(f.open());preferences.setSelectedTab(2)
        f.cache.shutdownForRestore()
        val before=Files.readAllBytes(f.store.root.resolve("plugin-settings.json"))
        assertFailsWith<IllegalStateException>{preferences.setSelectedTab(1)}
        assertContentEquals(before,Files.readAllBytes(f.store.root.resolve("plugin-settings.json")))
    }

    @Test fun coldRestoredSameMIDRetainsOriginalAccountUserNamespace():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            f.login(101);val first=f.open();val prefs=f.prefs(first)
            first.saveTimeline(listOf(accountDynamic("cold-A")));f.cache.flush()
            prefs.setSelectedTab(4);prefs.toggleUserPreference("dynamic_pinned_users",77)
            f.cache.shutdownForRestore();f.store.freezeWrites()
            val store=DesktopPluginStore(f.store.root)
            val cache=DesktopDynamicCache(f.sessions,store)
            try {
                val restored=checkNotNull(cache.openCurrent())
                val current=DesktopDynamicTabsPreferences(DesktopPluginContext(store),restored)
                assertEquals(listOf("cold-A"),restored.cachedAllItems.value.map{it.id_str})
                assertEquals(4,current.selectedTab);assertEquals(setOf(77L),current.pinned.first())
                assertFailsWith<IllegalStateException>{prefs.setSelectedTab(1)}
            } finally {cache.shutdownForRestore()}
        } finally {f.cache.shutdownForRestore()}
    }

    @Test fun actualAtoBtoARestoresCachePinnedHiddenAndLogicalTab():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            f.login(101);val a=f.open();val ap=f.prefs(a)
            a.saveTimeline(listOf(accountDynamic("A")));a.markNotInterested("A-hidden");f.cache.flush()
            ap.toggleUserPreference("dynamic_pinned_users",11);ap.toggleUserPreference("dynamic_hidden_users",12);ap.setSelectedTab(3)
            f.login(202);val b=f.open();val bp=f.prefs(b)
            assertTrue(b.cachedAllItems.value.isEmpty());assertTrue(bp.pinned.first().isEmpty());assertEquals(0,bp.selectedTab)
            b.saveTimeline(listOf(accountDynamic("B")));f.cache.flush();bp.setSelectedTab(2);bp.toggleUserPreference("dynamic_pinned_users",21)
            f.login(101);val returned=f.open();val restored=f.prefs(returned)
            assertEquals(listOf("A"),returned.cachedAllItems.value.map{it.id_str});assertEquals(setOf("A-hidden"),returned.notInterestedIds.value)
            assertEquals(setOf(11L),restored.pinned.first());assertEquals(setOf(12L),restored.hidden.first());assertEquals(3,restored.selectedTab)
            assertFailsWith<IllegalStateException>{ap.setSelectedTab(1)}
            f.login(202);assertEquals(listOf("B"),f.open().cachedAllItems.value.map{it.id_str})
        } finally {f.cache.shutdownForRestore()}
    }

    @Test fun sameMIDNewCredentialKeepsHistoryButOldSessionCannotWrite():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            f.login(101,"before");val old=f.open();val preferences=f.prefs(old)
            old.saveTimeline(listOf(accountDynamic("same-account")));f.cache.flush();preferences.setSelectedTab(4)
            f.login(101,"after");val next=f.open()
            assertNotEquals(old.owner,next.owner);assertEquals(old.cacheNamespace,next.cacheNamespace)
            assertEquals(listOf("same-account"),next.cachedAllItems.value.map{it.id_str});assertEquals(4,f.prefs(next).selectedTab)
            assertFailsWith<IllegalStateException>{preferences.setSelectedTab(2)}
            old.saveTimeline(listOf(accountDynamic("retired")));f.cache.flush();assertNotNull(old.writeFailure.value)
            assertEquals(listOf("same-account"),next.cachedAllItems.value.map{it.id_str})
        } finally {f.cache.shutdownForRestore()}
    }

    @Test fun guestTimelineIsNotPersistedButGuestUserPreferencesAndLocalIdsAreSeparate():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            val guest=f.open();val gp=f.prefs(guest);guest.saveTimeline(listOf(accountDynamic("guest-feed")));f.cache.flush()
            guest.markNotInterested("guest-id");gp.setSelectedTab(1);gp.toggleUserPreference("dynamic_hidden_users",77)
            assertTrue(guest.cachedAllItems.value.isEmpty());assertNull(f.store.preferences(guest.cacheNamespace)[Keys.KEY_DYNAMIC_CACHE])
            f.login(101);val a=f.open();assertTrue(a.notInterestedIds.value.isEmpty());assertTrue(f.prefs(a).hidden.first().isEmpty())
            f.sessions.logout();val again=f.open();assertEquals(setOf("guest-id"),again.notInterestedIds.value)
            assertEquals(1,f.prefs(again).selectedTab);assertEquals(setOf(77L),f.prefs(again).hidden.first())
        } finally {f.cache.shutdownForRestore()}
    }

    @Test fun lateActualPreferencePermitRejectsAccountSwitchWithoutPublishing():Unit=runBlocking {
        val f=DynamicAccountFixture(true)
        try {
            f.login(101);val a=f.open();val ap=f.prefs(a);ap.setSelectedTab(1)
            f.waiting.set(true);val late=async(Dispatchers.Default){runCatching{ap.setSelectedTab(4)}}
            assertTrue(f.entered.await(5,TimeUnit.SECONDS));f.login(202);val b=f.open();f.release.countDown()
            assertTrue(late.await().isFailure);assertEquals(0,f.prefs(b).selectedTab)
            assertEquals(1,f.store.preferences(a.userPreferenceNamespace)["dynamic_selected_tab"]!!.jsonPrimitive.int)
        } finally {f.release.countDown();f.cache.shutdownForRestore()}
    }

    @Test fun cancelledPreferenceCallerCannotMintFinalPermit():Unit=runBlocking {
        val f=DynamicAccountFixture(true)
        try {
            f.login(101);val a=f.open();val ap=f.prefs(a);ap.setSelectedTab(1)
            val before=Files.readAllBytes(f.store.root.resolve("plugin-settings.json"))
            f.waiting.set(true);val writer=launch(Dispatchers.Default){ap.setSelectedTab(4)}
            assertTrue(f.entered.await(5,TimeUnit.SECONDS));writer.cancel();f.release.countDown();writer.join()
            assertEquals(1,ap.selectedTab);assertContentEquals(before,Files.readAllBytes(f.store.root.resolve("plugin-settings.json")))
        } finally {f.release.countDown();f.cache.shutdownForRestore()}
    }

    @Test fun provenLegacyCacheMigratesWhileUnownedUserPreferencesArePreservedUnmigrated():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            f.login(101);val owner=checkNotNull(f.sessions.dynamicCacheOwner())
            val legacyUsers=mapOf("dynamic_selected_tab" to JsonPrimitive(4),"dynamic_hidden_users" to JsonArray(listOf(JsonPrimitive("77"))))
            f.store.update("dynamic_user_prefs",legacyUsers)
            f.store.update("dynamic_cache",mapOf("_desktop_session_owner_v1" to JsonPrimitive(owner.namespaceTag),
                Keys.KEY_DYNAMIC_CACHE to JsonPrimitive(Json.encodeToString(listOf(accountDynamic("legacy"))))))
            val legacyCache=f.store.preferences("dynamic_cache");val a=f.open()
            assertEquals(listOf("legacy"),a.cachedAllItems.value.map{it.id_str})
            assertEquals(0,f.prefs(a).selectedTab);assertTrue(f.prefs(a).hidden.first().isEmpty())
            assertEquals(JsonObject(legacyUsers),f.store.preferences("dynamic_user_prefs"));assertEquals(legacyCache,f.store.preferences("dynamic_cache"))
        } finally {f.cache.shutdownForRestore()}
    }

    @Test fun anotherOwnerLegacyValuesNeverSeedCurrentAccount():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            f.login(101);val owner=checkNotNull(f.sessions.dynamicCacheOwner())
            f.store.update("dynamic_cache",mapOf("_desktop_session_owner_v1" to JsonPrimitive(owner.namespaceTag),
                Keys.KEY_DYNAMIC_CACHE to JsonPrimitive(Json.encodeToString(listOf(accountDynamic("not-B"))))))
            val old=f.store.preferences("dynamic_cache");f.login(202);assertTrue(f.open().cachedAllItems.value.isEmpty())
            assertEquals(old,f.store.preferences("dynamic_cache"))
        } finally {f.cache.shutdownForRestore()}
    }

    @Test fun existingScopedDataWinsOverProvenLegacyMigration():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            f.login(101);val owner=checkNotNull(f.sessions.dynamicCacheOwner())
            f.store.update("dynamic_cache",mapOf("_desktop_session_owner_v1" to JsonPrimitive(owner.namespaceTag),
                Keys.KEY_DYNAMIC_CACHE to JsonPrimitive(Json.encodeToString(listOf(accountDynamic("old"))))))
            f.store.update("dynamic_cache_101",mapOf(Keys.KEY_DYNAMIC_CACHE to JsonPrimitive(Json.encodeToString(listOf(accountDynamic("new"))))))
            assertEquals(listOf("new"),f.open().cachedAllItems.value.map{it.id_str})
        } finally {f.cache.shutdownForRestore()}
    }

    @Test fun concurrentPreferenceTogglesUseActualStoreCASAndRejectForeignStore():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            f.login(101);val a=f.open();val ap=f.prefs(a)
            coroutineScope {listOf(11L,12L).map{id->async(Dispatchers.Default){ap.toggleUserPreference("dynamic_pinned_users",id)}}.awaitAll()}
            assertEquals(setOf(11L,12L),ap.pinned.first())
            val foreign=DesktopDynamicTabsPreferences(DesktopPluginContext(DesktopPluginStore(f.root.resolve("foreign"))),a)
            assertFailsWith<IllegalArgumentException>{foreign.selectedTab}
        } finally {f.cache.shutdownForRestore()}
    }

    @Test fun globalDisplaySettingsDoNotCreateAnUnownedAccountFallback():Unit=runBlocking {
        val f=DynamicAccountFixture()
        try {
            val global=DesktopDynamicTabsPreferences(f.context)
            global.setAllTabUsers(true);assertTrue(global.allTabUsers.first())
            assertFailsWith<IllegalStateException>{global.selectedTab}
            assertFailsWith<IllegalStateException>{global.setSelectedTab(1)}
            assertTrue(f.store.preferences("dynamic_user_prefs").isEmpty())
        } finally {f.cache.shutdownForRestore()}
    }
}
