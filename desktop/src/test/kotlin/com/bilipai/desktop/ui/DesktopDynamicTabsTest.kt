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
        assertEquals(0,one.selectedTab)
        one.setVisibleTabs(setOf("article","video"));two.setOrder(listOf("video","article"));two.setSelectedTab(3)
        assertEquals(setOf("article","video"),two.visibleTabs.first());assertEquals(listOf("video","article"),one.tabOrder.first())
        assertEquals(3,one.selectedTab)
        val disk=Json.parseToJsonElement(Files.readString(one.context.store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals("article,video",disk["settings"]!!.jsonObject["dynamic_tab_visible_tabs"]!!.jsonPrimitive.content)
        assertEquals("video,article",disk["settings"]!!.jsonObject["dynamic_tab_order"]!!.jsonPrimitive.content)
        assertNull(disk["settings"]!!.jsonObject["dynamic_all_tab_horizontal_user_list_visible"],"The retired strip switch is never written")
        assertEquals(3,disk["dynamic_user_prefs_42"]!!.jsonObject["dynamic_selected_tab"]!!.jsonPrimitive.int)
        val restored=state(this,two);assertEquals(3,restored.selectedLogicalTab);restored.close()
        one.setVisibleTabs(setOf("pgc"));one.setOrder(listOf("pgc"));one.setSelectedTab(3)
        one.toggleUserPreference("dynamic_hidden_users",77)
        val noFlash=state(this,two,follow={FollowingsData(listOf(FollowingUser(77,"hidden","")),1)})
        assertEquals(2,noFlash.selectedLogicalTab,"Stored visibility is applied before the first source consumer")
        noFlash.hydrateUsers();assertTrue(noFlash.users.isEmpty(),"Persisted hidden UID must not briefly paint during initial hydration")
        noFlash.close()
    }
    @Test fun storedUpTabIdsFoldIntoAllAndTheFourTabCountReplacesTheUpIndex():Unit=runBlocking {
        val p=prefs()
        // The setters store the id strings of an older session unchanged; only the getters fold them.
        p.setVisibleTabs(setOf("video","up"));p.setOrder(listOf("up","video","all","up"))
        val disk=Json.parseToJsonElement(Files.readString(p.context.store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals("video,up",disk["settings"]!!.jsonObject["dynamic_tab_visible_tabs"]!!.jsonPrimitive.content)
        assertEquals("up,video,all,up",disk["settings"]!!.jsonObject["dynamic_tab_order"]!!.jsonPrimitive.content)
        assertEquals(setOf("video","all"),p.visibleTabs.first(),"The retired id keeps its place as All")
        assertEquals(listOf("all","video"),p.tabOrder.first(),"A merged id appears once, at the first of the two places")
        assertEquals(listOf("all","video","pgc","article"),allDynamicTabSpecs.map{it.id})
        assertEquals(0,resolveDynamicSelectedTab(4,allDynamicTabSpecs.size),"A saved UP index from an older session opens All")
        p.setSelectedTab(4);assertEquals(0,p.selectedTab)
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
    @Test fun reorderKeepsLogicalIdentityAndSelectedAuthorWithoutAnotherRequest():Unit=runBlocking {
        val calls=mutableListOf<Map<String,String>>();val s=state(this,request={calls+=it;tabsResponse(listOf(tabsDynamic("remote")))})
        s.selectUser(42);eventually{!s.userLoading};assertEquals(0,s.selectedLogicalTab,"The author filters the current category")
        val rows=s.userItems;s.applyTabs(defaultDynamicTabVisibleIds,listOf("article","pgc","video","all"))
        assertEquals("article",s.visibleTabs.first().id);assertEquals(0,s.selectedLogicalTab);assertEquals(42L,s.selectedUid)
        assertSame(rows,s.userItems);assertEquals(1,calls.size);assertEquals("42",calls.single()["host_mid"])
        s.selectUser(42);delay(180);assertEquals(1,calls.size);s.close()
    }
    @Test fun authorSelectionKeepsTheCategoryAndPgcMovesToAll():Unit=runBlocking {
        val calls=mutableListOf<Map<String,String>>()
        val s=state(this,request={calls+=it;tabsResponse(listOf(tabsDynamic("author",93)))})
        s.selectTab(1);s.selectUser(93);eventually{!s.userLoading}
        assertEquals(1,s.selectedLogicalTab,"A strip click filters the current category");assertEquals(93L,s.selectedUid)
        assertTrue(shouldUseSelectedUserDynamicFeed(s.selectedLogicalTab,s.selectedUid))
        s.selectTab(2);assertEquals(93L,s.selectedUid,"pgc keeps the selection but never applies it")
        assertFalse(shouldUseSelectedUserDynamicFeed(s.selectedLogicalTab,s.selectedUid))
        assertNull(resolveDynamicRefreshUserId(s.selectedLogicalTab,s.selectedUid))
        s.selectUser(94);eventually{!s.userLoading}
        assertEquals(0,s.selectedLogicalTab,"pgc has no author feed: a new selection returns to All");assertEquals(94L,s.selectedUid)
        assertEquals(listOf("93","94"),calls.map{it["host_mid"]})
        s.selectUser(DYNAMIC_UP_PANEL_ALL_UID);assertNull(s.selectedUid,"The All dynamics entry clears the filter");assertEquals(0,s.selectedLogicalTab)
        s.close()
    }
    @Test fun stripListsAllDynamicsFirstThenSelfAndTheEntryIsSelectedWithoutAnAuthor():Unit=runBlocking {
        val s=state(this,follow={FollowingsData(listOf(FollowingUser(7,"seven","")),1)})
        s.hydrateUsers()
        assertEquals(listOf(DYNAMIC_UP_PANEL_ALL_UID,42L,7L),s.panelUsers().map{it.uid})
        assertEquals("全部动态",s.panelUsers().first().name)
        assertTrue(isDynamicUpPanelItemSelected(s.selectedUid,DYNAMIC_UP_PANEL_ALL_UID));s.selectUser(7);eventually{!s.userLoading}
        assertFalse(isDynamicUpPanelItemSelected(s.selectedUid,DYNAMIC_UP_PANEL_ALL_UID));assertTrue(isDynamicUpPanelItemSelected(s.selectedUid,7))
        assertTrue(shouldShowDynamicHorizontalUserList(true,0));assertTrue(shouldShowDynamicHorizontalUserList(true,1))
        assertFalse(shouldShowDynamicHorizontalUserList(true,2),"The official pgc feed has no author strip");assertTrue(shouldShowDynamicHorizontalUserList(true,3))
        s.close()
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
        s.selectTab(1);assertEquals(82L,s.selectedUid,"A category tab keeps the author filter");assertEquals(listOf("new"),s.userItems.map{it.id_str});s.close()
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
        s.selectTab(1);assertEquals(listOf("same"),s.visibleItems().map{it.id_str},"The video category filters the author rows")
        s.selectTab(3);assertEquals(listOf("article"),s.visibleItems().map{it.id_str},"The article category filters the author rows")
        val categoryRows=listOf(tabsDynamic("category-local",77,"DYNAMIC_TYPE_DRAW"),tabsDynamic("category-foreign",78,"DYNAMIC_TYPE_DRAW"))
        assertEquals(listOf("category-local","article"),s.visibleItems(categoryRows).map{it.id_str},
            "The current category's own timeline rows replace the All rows as the local rows")
        s.close();s.selectUser(88);assertEquals(77L,s.selectedUid)
    }
    @Test fun frozenOrBrokenDiskDoesNotPublishNewTabOrUserPreferences():Unit=runBlocking {
        val p=prefs();Files.createDirectory(p.context.store.root.resolve("plugin-settings.json"))
        assertFailsWith<Exception>{p.setOrder(listOf("article"))};assertEquals(allDynamicTabSpecs.map{it.id},p.tabOrder.first())
        Files.delete(p.context.store.root.resolve("plugin-settings.json"));p.setOrder(listOf("article"));val before=Files.readAllBytes(p.context.store.root.resolve("plugin-settings.json"))
        p.context.store.freezeWrites();assertFailsWith<IllegalStateException>{p.setVisibleTabs(setOf("pgc"))}
        assertFailsWith<IllegalStateException>{p.toggleUserPreference("dynamic_pinned_users",81)}
        assertContentEquals(before,Files.readAllBytes(p.context.store.root.resolve("plugin-settings.json")))
    }
}
