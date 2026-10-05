package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

internal fun tabsDynamic(id:String,mid:Long=42,type:String="DYNAMIC_TYPE_WORD",visible:Boolean=true):DynamicItem=Json.decodeFromString(
    """{"id_str":"$id","type":"$type","visible":$visible,"modules":{"module_author":{"mid":$mid,"name":"User-$mid","face":"","pub_ts":100},"module_dynamic":{"desc":{"text":"Dynamic-$id"}}}}""")
internal fun tabsResponse(rows:List<DynamicItem>,offset:String="",more:Boolean=false)=DynamicFeedResponse(data=DynamicFeedData(rows,offset,more))
private suspend fun eventually(ready:()->Boolean)=withTimeout(2500){while(!ready())delay(5)}
class DesktopDynamicTabsTest {
    private val ownedCaches=mutableListOf<com.bilipai.desktop.data.DesktopDynamicCache>()
    @AfterTest fun closeOwnedCaches():Unit=runBlocking {
        try { ownedCaches.forEach { it.shutdownForRestore() } } finally { ownedCaches.clear() }
    }
private fun prefs()=dynamicAccountTestPreferences(DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("dynamic-tabs-")))) { ownedCaches+=it }
private fun state(scope:CoroutineScope,prefs:DesktopDynamicTabsPreferences=prefs(),
    owned:()->Boolean={true},follow:suspend(Int)->FollowingsData={FollowingsData()},
    live:suspend()->List<LiveRoom> = {emptyList()},unread:suspend()->UplistData?={null},
    request:suspend(Map<String,String>)->DynamicFeedResponse={tabsResponse(emptyList())})=
    DesktopDynamicUsersState(scope,prefs,42,follow,live,unread,request,owned)

    @Test fun defaultsKeysAndSharedBackingRestoreOriginalSelection():Unit=runBlocking {
        val one=prefs();val two=DesktopDynamicTabsPreferences(one.context,one.account)
        assertEquals(defaultDynamicTabVisibleIds,one.visibleTabs.first());assertEquals(allDynamicTabSpecs.map{it.id},one.tabOrder.first())
        assertFalse(one.allTabUsers.first());assertEquals(0,one.selectedTab)
        one.setVisibleTabs(setOf("article","up"));two.setOrder(listOf("up","article"));one.setAllTabUsers(true);two.setSelectedTab(3)
        assertEquals(setOf("article","up"),two.visibleTabs.first());assertEquals(listOf("up","article"),one.tabOrder.first())
        assertTrue(two.allTabUsers.first());assertEquals(3,one.selectedTab)
        val disk=Json.parseToJsonElement(Files.readString(one.context.store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals("article,up",disk["settings"]!!.jsonObject["dynamic_tab_visible_tabs"]!!.jsonPrimitive.content)
        assertEquals("up,article",disk["settings"]!!.jsonObject["dynamic_tab_order"]!!.jsonPrimitive.content)
        assertEquals(3,disk["dynamic_user_prefs_42"]!!.jsonObject["dynamic_selected_tab"]!!.jsonPrimitive.int)
        val restored=state(this,two);assertEquals(3,restored.selectedLogicalTab);restored.close()
        one.setVisibleTabs(setOf("up"));one.setOrder(listOf("up"));one.setSelectedTab(3)
        one.toggleUserPreference("dynamic_hidden_users",77)
        val noFlash=state(this,two,follow={FollowingsData(listOf(FollowingUser(77,"hidden","")),1)})
        assertEquals(4,noFlash.selectedLogicalTab,"Stored visibility is applied before the first source consumer")
        noFlash.hydrateUsers();assertTrue(noFlash.users.isEmpty(),"Persisted hidden UID must not briefly paint during initial hydration")
        noFlash.close()
    }
    @Test fun hideSelectedTabUsesOriginalFallbackAndRequestTypeNotVisibleIndex():Unit=runBlocking {
        val s=state(this);s.selectTab(3);assertEquals("article",resolveDynamicFeedRequestType(s.selectedLogicalTab))
        s.applyTabs(setOf("video","pgc"),listOf("pgc","video"))
        assertEquals(2,s.selectedLogicalTab);assertEquals("pgc",resolveDynamicFeedRequestType(s.selectedLogicalTab))
        assertEquals(2,s.preferences.selectedTab)
        s.applyTabs(emptySet(),emptyList());assertEquals(0,s.selectedLogicalTab)
        s.preferences.setVisibleTabs(setOf("all"));s.preferences.toggleVisibility("all");assertEquals(setOf("all"),s.preferences.visibleTabs.first())
        s.close()
    }
    @Test fun reorderKeepsLogicalIdentityAndSelectedUPWithoutAnotherRequest():Unit=runBlocking {
        val calls=mutableListOf<Map<String,String>>();val s=state(this,request={calls+=it;tabsResponse(listOf(tabsDynamic("remote")))})
        s.selectUser(42);eventually{!s.userLoading};assertEquals(4,s.selectedLogicalTab)
        val rows=s.userItems;s.applyTabs(defaultDynamicTabVisibleIds,listOf("up","article","pgc","video","all"))
        assertEquals("up",s.visibleTabs.first().id);assertEquals(4,s.selectedLogicalTab);assertEquals(42L,s.selectedUid)
        assertSame(rows,s.userItems);assertEquals(1,calls.size);assertEquals("42",calls.single()["host_mid"])
        s.selectUser(42);delay(180);assertEquals(1,calls.size);s.close()
    }
    @Test fun hiddenUPOpensProfileAndNeverIssuesUPRequest():Unit=runBlocking {
        var requests=0;var profile=0L;val s=state(this,request={requests++;tabsResponse(emptyList())})
        s.applyTabs(setOf("all","article"),emptyList());s.selectUser(93){profile=it};delay(150)
        assertEquals(93L,profile);assertEquals(0,requests);assertNull(s.selectedUid);assertEquals(0,s.selectedLogicalTab);s.close()
    }
    @Test fun originalUserPaginationAdvancesEmptyPageAndPreservesExactParams():Unit=runBlocking {
        val calls=mutableListOf<Map<String,String>>();val s=state(this,request={params->calls+=params
            when(calls.size){1->tabsResponse(emptyList(),"next",true);2->tabsResponse(listOf(tabsDynamic("1",81)),"tail",true)
                else->tabsResponse(listOf(tabsDynamic("2",81)))}})
        s.selectUser(81);eventually{!s.userLoading};assertEquals(listOf("","next"),calls.map{it["offset"]})
        assertEquals("81",calls[0]["host_mid"]);assertEquals("-480",calls[0]["timezone_offset"])
        assertEquals("web",calls[0]["platform"]);assertEquals("333.1387",calls[0]["web_location"])
        s.loadMoreUser();eventually{!s.userLoading};assertEquals("tail",calls.last()["offset"])
        assertEquals(listOf("1","2"),s.userItems.map{it.id_str});assertFalse(s.hasUserMore);s.close()
    }
    @Test fun latePriorUIDResultCannotReplaceNewSelectionAndLocalRowsAreProvisional():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val s=state(this,request={params->
            if(params["host_mid"]=="81"){entered.complete(Unit);withContext(NonCancellable){release.await()};tabsResponse(listOf(tabsDynamic("old",81)))}
            else tabsResponse(listOf(tabsDynamic("new",82)))})
        s.updateTimeline(listOf(tabsDynamic("local",81)));s.selectUser(81);entered.await()
        assertEquals(listOf("local"),s.visibleItems().map{it.id_str})
        s.selectUser(82);release.complete(Unit);eventually{!s.userLoading&&s.userItems.isNotEmpty()}
        assertEquals(82L,s.selectedUid);assertEquals(listOf("new"),s.userItems.map{it.id_str})
        s.selectTab(1);assertNull(s.selectedUid);assertTrue(s.userItems.isEmpty());s.close()
    }
    @Test fun sameMIDNewEpochRejectsLateFollowingAndUPResults():Unit=runBlocking {
        val epoch=AtomicLong(1);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val s=state(this,owned={epoch.get()==1L},request={entered.complete(Unit);withContext(NonCancellable){release.await()};tabsResponse(listOf(tabsDynamic("old")))},
            follow={withContext(NonCancellable){release.await()};FollowingsData(listOf(FollowingUser(99,"late","")),1)})
        s.selectUser(42);entered.await();val hydration=launch{s.hydrateUsers()};epoch.set(2);release.complete(Unit);hydration.join();delay(50)
        assertTrue(s.userItems.isEmpty());assertTrue(s.users.isEmpty());s.close()
    }
    @Test fun allFollowingPagesWhitelistLiveAndUnreadPinHideHaveActualConsumers():Unit=runBlocking {
        val pages=mutableListOf<Int>();val s=state(this,follow={page->pages+=page
            if(page==1)FollowingsData((1L..50L).map{FollowingUser(it,"User-$it","")},51)
            else FollowingsData(listOf(FollowingUser(51,"User-51","")),51)},
            live={listOf(LiveRoom(roomid=999,uid=51,uname="User-51",face=""))},
            unread={Json.decodeFromString("""{"items":[{"has_update":1,"user_profile":{"info":{"uid":51}}}]}""")})
        s.updateTimeline(listOf(tabsDynamic("stranger",111)));s.hydrateUsers()
        assertEquals(listOf(1,2),pages);assertEquals(51,s.users.size);assertTrue(s.users.none{it.uid==111L})
        assertEquals(51L,s.users.first().uid);assertTrue(s.users.first().isLive);assertEquals(setOf(51L),s.unread)
        s.preferences.toggleUserPreference("dynamic_pinned_users",1);s.preferences.toggleUserPreference("dynamic_hidden_users",2)
        s.updateUserPreferences(s.preferences.pinned.first(),s.preferences.hidden.first())
        assertEquals(1L,s.users.first().uid);assertTrue(s.users.none{it.uid==2L});assertEquals(1,s.hiddenCount)
        s.toggleShowHidden();assertTrue(s.users.single{it.uid==2L}.isHidden)
        s.selectUser(51);assertTrue(s.unread.isEmpty());eventually{!s.userLoading};s.close()
    }
    @Test fun originalUserFiltersLocalDedupAndOwnedCloseRemainEffective():Unit=runBlocking {
        val s=state(this,request={tabsResponse(listOf(tabsDynamic("same",77,"DYNAMIC_TYPE_AV"),tabsDynamic("article",77),tabsDynamic("fold",77,visible=false)))})
        val local=tabsDynamic("same",77,"DYNAMIC_TYPE_AV");s.updateTimeline(listOf(local));s.selectUser(77);eventually{!s.userLoading}
        assertEquals(listOf("same","article"),s.visibleItems().map{it.id_str});assertSame(local,s.visibleItems().first())
        s.filter=DynamicUserContentFilter.VIDEO;assertEquals(listOf("same"),s.visibleItems().map{it.id_str})
        s.filter=DynamicUserContentFilter.ARTICLE;assertEquals(listOf("article"),s.visibleItems().map{it.id_str})
        s.close();s.selectUser(88);assertEquals(77L,s.selectedUid)
    }
    @Test fun frozenOrBrokenDiskDoesNotPublishNewTabOrUserPreferences():Unit=runBlocking {
        val p=prefs();Files.createDirectory(p.context.store.root.resolve("plugin-settings.json"))
        assertFailsWith<Exception>{p.setOrder(listOf("up"))};assertEquals(allDynamicTabSpecs.map{it.id},p.tabOrder.first())
        Files.delete(p.context.store.root.resolve("plugin-settings.json"));p.setOrder(listOf("up"));val before=Files.readAllBytes(p.context.store.root.resolve("plugin-settings.json"))
        p.context.store.freezeWrites();assertFailsWith<IllegalStateException>{p.setAllTabUsers(true)}
        assertFailsWith<IllegalStateException>{p.toggleUserPreference("dynamic_pinned_users",81)}
        assertContentEquals(before,Files.readAllBytes(p.context.store.root.resolve("plugin-settings.json")))
    }
}
