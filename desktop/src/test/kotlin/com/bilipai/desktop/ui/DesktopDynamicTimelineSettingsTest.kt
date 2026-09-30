package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopDynamicSettings.DynamicFeedLayoutMode
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.data.desktopVisibleDynamicItems
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

internal fun fixtureDynamic(id:String,ts:Long,mid:Long=0,visible:Boolean=true):DynamicItem=Json.decodeFromString(
    """{"id_str":"$id","type":"DYNAMIC_TYPE_WORD","visible":$visible,"modules":{
      "module_author":{"mid":$mid,"name":"Fixture","face":"","pub_ts":$ts},
      "module_dynamic":{"desc":{"text":"Dynamic-$id"}}}}""")
private fun response(items:List<DynamicItem>,offset:String="",baseline:String="",more:Boolean=false,updates:Int=0)=
    DynamicFeedResponse(data=DynamicFeedData(items,offset,more,baseline,updates))

class DesktopDynamicTimelineSettingsTest {
    @Test fun loadMoreRejectsBusyAndExhaustedRequestsBeforeCallingOriginalTransport():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val offsets=mutableListOf<String>()
        val state=DesktopDynamicTimelineState("all",{_,offset,_->offsets+=offset
            if(offset.isEmpty())response(listOf(fixtureDynamic("first",2)),"tail","first",true)
            else {entered.complete(Unit);release.await();response(listOf(fixtureDynamic("last",1)),more=false)}})
        assertTrue(state.initialize(false))
        val append=async{state.loadMore(false)};entered.await()
        assertFalse(state.loadMore(false));assertEquals(listOf("","tail"),offsets)
        release.complete(Unit);assertTrue(append.await());assertFalse(state.loadMore(false))
        assertEquals(listOf("first","last"),state.page.items.map{it.id_str});assertEquals(listOf("","tail"),offsets)
    }
    @Test fun originalDefaultsAndSameGlobalNamespaceAreUsed():Unit=runBlocking {
        val root=Files.createTempDirectory("dynamic-defaults-");val store=DesktopPluginStore(root)
        val prefs=DesktopDynamicTimelinePreferences(DesktopPluginContext(store))
        assertFalse(prefs.incrementalRefresh.first());assertEquals(DynamicFeedLayoutMode.WATERFALL,prefs.layoutMode.first())
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
        store.update("unrelated",mapOf("keep" to JsonPrimitive("old")))
        val second=DesktopDynamicTimelinePreferences(DesktopPluginContext(DesktopPluginStore(root)))
        prefs.setIncrementalRefresh(true);second.setLayoutMode(DynamicFeedLayoutMode.LIST)
        assertTrue(second.incrementalRefresh.first());assertEquals(DynamicFeedLayoutMode.LIST,prefs.layoutMode.first())
        val disk=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(true,disk["settings"]!!.jsonObject["incremental_timeline_refresh"]!!.jsonPrimitive.boolean)
        assertEquals(1,disk["settings"]!!.jsonObject["dynamic_feed_layout_mode"]!!.jsonPrimitive.int)
        assertEquals("old",disk["unrelated"]!!.jsonObject["keep"]!!.jsonPrimitive.content)
        assertFalse(Files.exists(root.resolve("discovery")))
    }
    @Test fun realAtomicWriteFailureDoesNotPublishOrOverwritePreviousSettings():Unit=runBlocking {
        val root=Files.createTempDirectory("dynamic-write-failure-");val store=DesktopPluginStore(root)
        val prefs=DesktopDynamicTimelinePreferences(DesktopPluginContext(store))
        Files.createDirectory(root.resolve("plugin-settings.json"))
        assertFailsWith<Exception>{prefs.setLayoutMode(DynamicFeedLayoutMode.LIST)}
        assertEquals(DynamicFeedLayoutMode.WATERFALL,prefs.layoutMode.first());assertTrue(Files.isDirectory(root.resolve("plugin-settings.json")))
    }
    @Test fun frozenGenerationRejectsBothSettingsWithoutRevivingBacking():Unit=runBlocking {
        val root=Files.createTempDirectory("dynamic-restore-fence-");val store=DesktopPluginStore(root)
        val one=DesktopDynamicTimelinePreferences(DesktopPluginContext(store));val two=DesktopDynamicTimelinePreferences(DesktopPluginContext(DesktopPluginStore(root)))
        one.setIncrementalRefresh(true);val original=Files.readAllBytes(root.resolve("plugin-settings.json"));store.freezeWrites()
        assertFailsWith<IllegalStateException>{one.setLayoutMode(DynamicFeedLayoutMode.LIST)}
        assertFailsWith<IllegalStateException>{two.setIncrementalRefresh(false)}
        assertContentEquals(original,Files.readAllBytes(root.resolve("plugin-settings.json")))
        assertTrue(two.incrementalRefresh.first());assertEquals(DynamicFeedLayoutMode.WATERFALL,one.layoutMode.first())
    }
    @Test fun invalidSettingsNamespaceCannotMasqueradeAsDefaultPreferences():Unit=runBlocking {
        val root=Files.createTempDirectory("dynamic-namespace-error-")
        val original="""{"settings":"corrupt namespace","unrelated":{"keep":7}}""";Files.writeString(root.resolve("plugin-settings.json"),original)
        assertFailsWith<IllegalArgumentException>{DesktopDynamicTimelinePreferences(DesktopPluginContext(DesktopPluginStore(root)))}
        assertEquals(original,Files.readString(root.resolve("plugin-settings.json")))
    }
    @Test fun incrementalBaselineFetchesAllNewPagesAndPreservesOldTailPagination():Unit=runBlocking {
        val calls=mutableListOf<Triple<String,String,String>>();var request=0
        val old=fixtureDynamic("3",30);val responses=listOf(
            response(listOf(old,fixtureDynamic("2",20),fixtureDynamic("1",10)),"old-tail","3",true),
            response(listOf(fixtureDynamic("6",60),fixtureDynamic("5",50)),"new-next","6",true,4),
            response(listOf(fixtureDynamic("4",40),fixtureDynamic("3",30)),"new-tail","ignored-second-baseline",true,99),
            response(listOf(fixtureDynamic("0",0)),"end","",false))
        val state=DesktopDynamicTimelineState("all",{type,offset,baseline->calls+=Triple(type,offset,baseline);responses[request++]})
        assertTrue(state.fetch(true,false));assertTrue(state.fetch(true,true))
        assertEquals(listOf("6","5","4","3","2","1"),state.page.items.map{it.id_str})
        assertSame(old,state.page.items[3]);assertEquals("3",state.page.incrementalRefreshBoundaryKey);assertEquals(3,state.page.incrementalPrependedCount)
        assertEquals(Triple("all","","3"),calls[1]);assertEquals(Triple("all","new-next",""),calls[2])
        assertTrue(state.fetch(false,true));assertEquals(Triple("all","old-tail",""),calls[3]);assertFalse(state.page.hasMore)
    }
    @Test fun missingOverlapReplacesAndSynchronizesFreshPagination():Unit=runBlocking {
        val offsets=mutableListOf<String>();var index=0
        val replies=listOf(response(listOf(fixtureDynamic("old",1)),"old-offset","old",true),
            response(listOf(fixtureDynamic("new",2)),"fresh-offset","new",true,1),response(listOf(fixtureDynamic("tail",0))))
        val state=DesktopDynamicTimelineState("video",{_,offset,_->offsets+=offset;replies[index++]})
        state.fetch(true,false);state.fetch(true,true)
        assertEquals(listOf("new"),state.page.items.map{it.id_str});assertNull(state.page.incrementalRefreshBoundaryKey)
        state.fetch(false,true);assertEquals(listOf("","","fresh-offset"),offsets)
    }
    @Test fun disabledIncrementalRefreshRequestsNoBaselineAndReplacesOldRows():Unit=runBlocking {
        val baselines=mutableListOf<String>();var index=0
        val replies=listOf(response(listOf(fixtureDynamic("old",1)),"tail","old",true),response(listOf(fixtureDynamic("new",2))))
        val state=DesktopDynamicTimelineState("pgc",{_,_,baseline->baselines+=baseline;replies[index++]})
        state.fetch(true,false);state.fetch(true,false)
        assertEquals(listOf("new"),state.page.items.map{it.id_str});assertEquals(listOf("",""),baselines)
    }
    @Test fun foldedCardsStayInOriginalTimelineAndBlockedFilterStillHidesOnlyAuthor():Unit=runBlocking {
        val rows=listOf(fixtureDynamic("folded",2,visible=false),fixtureDynamic("blocked",1,mid=12),fixtureDynamic("visible",0))
        val state=DesktopDynamicTimelineState("all",{_,_,_->response(rows)})
        state.fetch(true,false);assertEquals(3,state.page.items.size);assertFalse(state.page.items[0].visible)
        assertEquals(listOf("folded","visible"),desktopVisibleDynamicItems(state.page.items,setOf(12)).map{it.id_str})
    }
    @Test fun ordinaryAppendRetainsExistingDuplicatePayloadAndStopsEmptyAdvancingPages():Unit=runBlocking {
        var requests=0;val old=fixtureDynamic("old",2)
        val replies=listOf(response(listOf(old),"one","old",true),response(emptyList(),"two","",true),
            response(listOf(fixtureDynamic("old",2).copy(visible=false),fixtureDynamic("tail",1)),"end","",false))
        val state=DesktopDynamicTimelineState("all",{_,_,_->replies[requests++]})
        state.fetch(true,false);state.fetch(false,false)
        assertEquals(3,requests);assertSame(old,state.page.items.first());assertEquals(listOf("old","tail"),state.page.items.map{it.id_str})
        state.fetch(false,false);assertEquals(3,requests)
    }
    @Test fun appendFailureKeepsRowsAndRetriesTheSameTail():Unit=runBlocking {
        var request=0;val offsets=mutableListOf<String>()
        val state=DesktopDynamicTimelineState("all",{_,offset,_->offsets+=offset;when(request++){
            0->response(listOf(fixtureDynamic("old",1)),"tail","old",true)
            1->DynamicFeedResponse(code=-352,message="risk control fixture")
            else->response(listOf(fixtureDynamic("more",0)))}})
        assertTrue(state.fetch(true,false));assertFalse(state.fetch(false,false))
        assertEquals(DynamicFeedErrorSource.APPEND,state.page.errorSource);assertEquals(listOf("old"),state.page.items.map{it.id_str})
        assertTrue(state.error!!.message!!.contains("风控"));assertTrue(state.fetch(false,false));assertEquals(listOf("","tail","tail"),offsets)
    }
    @Test fun cancelledTaskRestoresItsOwnedPageAndReleasesLoadingMutex():Unit=runBlocking {
        var requests=0;val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        val state=DesktopDynamicTimelineState("all",{_,_,_->if(requests++==0)response(listOf(fixtureDynamic("old",1)),"tail","old",true)
            else {entered.complete(Unit);gate.await();response(listOf(fixtureDynamic("new",2)))}})
        state.fetch(true,false);val before=state.page
        val job=launch{state.fetch(true,true)};entered.await();job.cancelAndJoin()
        assertEquals(before,state.page);assertFalse(state.busy);assertNull(state.error)
    }
    @Test fun sameMidChangedEpochRejectsLateResponseBeforeOriginalPaginationPublishes():Unit=runBlocking {
        val epoch=AtomicLong(1);val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        val state=DesktopDynamicTimelineState("all",{_,_,_->entered.complete(Unit);withContext(NonCancellable){gate.await()};response(listOf(fixtureDynamic("late",2)))},{epoch.get()==1L})
        val task=async{runCatching{state.fetch(true,false)}};entered.await();epoch.incrementAndGet();gate.complete(Unit)
        assertTrue(task.await().exceptionOrNull() is CancellationException);assertTrue(state.page.items.isEmpty());assertFalse(state.initialized);assertFalse(state.busy)
    }
    @Test fun originalCachePlaceholderAndStableSortRemainAuthoritative():Unit=runBlocking {
        val first=fixtureDynamic("a",10);val second=fixtureDynamic("b",10)
        val before=DynamicTimelinePageState(items=kotlinx.collections.immutable.persistentListOf(first,second),isCachePlaceholder=true)
        val result=resolveDynamicTimelinePageAfterSuccess(before,listOf(second,fixtureDynamic("c",30)),true,true,false)
        assertEquals(listOf("c","b"),result.items.map{it.id_str});assertNull(result.incrementalRefreshBoundaryKey);assertFalse(result.isCachePlaceholder)
        assertEquals(listOf("a","b"),sortDynamicTimelineItemsByPublishTime(listOf(first,second)).map{it.id_str})
    }
}
