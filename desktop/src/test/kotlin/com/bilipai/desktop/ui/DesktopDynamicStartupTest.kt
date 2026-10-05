package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

private suspend fun startupEventually(ready:()->Boolean)=withTimeout(5000){while(!ready())delay(5)}
class DesktopDynamicStartupTest {
    private val ownedCaches=mutableListOf<com.bilipai.desktop.data.DesktopDynamicCache>()
    @AfterTest fun closeOwnedCaches():Unit=runBlocking {
        try { ownedCaches.forEach { it.shutdownForRestore() } } finally { ownedCaches.clear() }
    }
private fun startupPreferences()=dynamicAccountTestPreferences(
    DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("dynamic-startup-")))) { ownedCaches+=it }
private fun followingRows(page:Int,total:Int)=FollowingsData(
    ((page-1)*50+1..minOf(page*50,total)).map{FollowingUser(it.toLong(),"User-$it","")},total)
private fun startupState(scope:CoroutineScope,prefs:DesktopDynamicTabsPreferences=startupPreferences(),
    owned:()->Boolean={true},following:suspend(Int)->FollowingsData={FollowingsData()},
    live:suspend()->List<LiveRoom> = {emptyList()},unread:suspend()->UplistData?={null},
    now:()->Long={1000L},wait:suspend(Long)->Unit={})=
    DesktopDynamicUsersState(scope,prefs,42,following,live,unread,
        requestPage={tabsResponse(emptyList())},stillOwned=owned,nowMs=now,startupDelay=wait)

    @Test fun startupWaitsForFeedAndLiveThenLoadsOnlyOneFollowingPage():Unit=runBlocking {
        val feedEntered=CompletableDeferred<Unit>();val liveEntered=CompletableDeferred<Unit>()
        val feedDone=CompletableDeferred<Unit>();val liveDone=CompletableDeferred<Unit>()
        val delayEntered=CompletableDeferred<Long>();val delayDone=CompletableDeferred<Unit>()
        val pages=mutableListOf<Int>();var feedCalls=0;var unreadCalls=0
        val state=startupState(this,following={pages+=it;followingRows(it,120)},
            live={liveEntered.complete(Unit);liveDone.await();emptyList()},
            unread={unreadCalls++;null},wait={delayEntered.complete(it);delayDone.await()})
        try {
            state.activateStartupLoads{feedCalls++;feedEntered.complete(Unit);feedDone.await()}
            state.activateStartupLoads{error("Duplicate activation")}
            feedEntered.await();liveEntered.await();startupEventually{unreadCalls==1}
            assertTrue(pages.isEmpty());assertFalse(delayEntered.isCompleted)
            feedDone.complete(Unit);yield();assertFalse(delayEntered.isCompleted)
            liveDone.complete(Unit);assertEquals(1200L,delayEntered.await());assertTrue(pages.isEmpty())
            delayDone.complete(Unit);startupEventually{state.users.size==50}
            assertEquals(listOf(1),pages);assertEquals(1,feedCalls)
            state.selectTab(4);startupEventually{state.users.size==120}
            assertEquals(listOf(1,1,2,3),pages)
            state.selectTab(4);yield();assertEquals(listOf(1,1,2,3),pages)
        }finally{state.close()}
    }
    @Test fun restoredUPTabLoadsAllFollowingsWhilePrimaryFeedIsStillPending():Unit=runBlocking {
        val prefs=startupPreferences();prefs.setSelectedTab(4)
        val feedDone=CompletableDeferred<Unit>();val delayEntered=CompletableDeferred<Long>()
        val pages=mutableListOf<Int>()
        val state=startupState(this,prefs,following={pages+=it;followingRows(it,51)},wait={delayEntered.complete(it)})
        try {
            state.activateStartupLoads{feedDone.await()}
            startupEventually{state.users.size==51};assertEquals(listOf(1,2),pages)
            assertFalse(delayEntered.isCompleted)
            feedDone.complete(Unit);assertEquals(1200L,delayEntered.await());yield()
            assertEquals(listOf(1,2),pages,"Fresh complete list must survive deferred startup hydration")
        }finally{state.close()}
    }
    @Test fun enteringUPDuringPartialHydrationQueuesOneCompleteReload():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val pages=mutableListOf<Int>()
        val state=startupState(this,following={page->pages+=page
            if(pages.size==1){entered.complete(Unit);release.await()};followingRows(page,51)})
        try {
            state.activateStartupLoads{};entered.await()
            state.selectTab(4);state.selectTab(4);assertEquals(listOf(1),pages)
            release.complete(Unit);startupEventually{state.users.size==51}
            assertEquals(listOf(1,1,2),pages)
        }finally{state.close()}
    }
    @Test fun staleFullListIsHydratedAgainAtTheOriginalFiveMinuteBoundary():Unit=runBlocking {
        val prefs=startupPreferences();prefs.setSelectedTab(4)
        val now=AtomicLong(1000);val feedDone=CompletableDeferred<Unit>();val pages=mutableListOf<Int>()
        val state=startupState(this,prefs,following={pages+=it;followingRows(it,51)},now=now::get,
            wait={assertEquals(1200L,it);now.addAndGet(FOLLOWINGS_REFRESH_TTL_MS)})
        try {
            state.activateStartupLoads{feedDone.await()};startupEventually{state.users.size==51}
            feedDone.complete(Unit);startupEventually{pages.size==3}
            assertEquals(listOf(1,2,1),pages);assertEquals(50,state.users.size)
        }finally{state.close()}
    }
    @Test fun changedCredentialEpochDuringDelayCannotStartFollowingRequests():Unit=runBlocking {
        val epoch=AtomicLong(1);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var requests=0
        val state=startupState(this,owned={epoch.get()==1L},following={requests++;followingRows(it,1)},
            wait={entered.complete(Unit);release.await()})
        try {
            state.activateStartupLoads{};entered.await();epoch.incrementAndGet();release.complete(Unit)
            yield();assertEquals(0,requests);assertTrue(state.users.isEmpty())
        }finally{state.close()}
    }
    @Test fun closingStartupCancelsFeedLiveUnreadAndNeverSchedulesFollowings():Unit=runBlocking {
        val feedEntered=CompletableDeferred<Unit>();val liveEntered=CompletableDeferred<Unit>()
        val unreadEntered=CompletableDeferred<Unit>();val cancelled=AtomicLong();var followingCalls=0
        val state=startupState(this,following={followingCalls++;FollowingsData()},
            live={liveEntered.complete(Unit);try{awaitCancellation()}finally{cancelled.incrementAndGet()}},
            unread={unreadEntered.complete(Unit);try{awaitCancellation()}finally{cancelled.incrementAndGet()}})
        state.activateStartupLoads{feedEntered.complete(Unit);try{awaitCancellation()}finally{cancelled.incrementAndGet()}}
        feedEntered.await();liveEntered.await();unreadEntered.await();state.close()
        startupEventually{cancelled.get()==3L};assertEquals(0,followingCalls)
        state.activateStartupLoads{error("Retired source must not activate")};state.close()
    }
    @Test fun closingAnInFlightFollowingPageRejectsLateRowsAndFurtherPages():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var requests=0
        val state=startupState(this,following={requests++;entered.complete(Unit)
            withContext(NonCancellable){release.await()};followingRows(it,51)})
        try {
            state.activateStartupLoads{};entered.await();state.selectTab(4);state.close();release.complete(Unit)
            yield();delay(20);assertEquals(1,requests);assertTrue(state.users.isEmpty());assertNull(state.followingsError)
        }finally{state.close();release.complete(Unit)}
    }
    @Test fun concurrentHostAndFeedInitializationMakeOneOriginalRequest():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var requests=0
        val state=DesktopDynamicTimelineState("all",{_,_,_->requests++;entered.complete(Unit);release.await()
            tabsResponse(listOf(tabsDynamic("first")))})
        val first=async{state.initialize(false)};entered.await()
        val second=async{state.initialize(false)};release.complete(Unit)
        assertTrue(first.await());assertFalse(second.await());assertEquals(1,requests)
        assertTrue(state.fetch(true,false));assertEquals(2,requests,"Explicit refresh remains available")
    }
    @Test fun failedInitializationWaitsForExplicitRetry():Unit=runBlocking {
        var requests=0
        val state=DesktopDynamicTimelineState("video",{_,_,_->
            if(requests++==0)DynamicFeedResponse(code=-352,message="fixture")else tabsResponse(listOf(tabsDynamic("retry")))})
        assertFalse(state.initialize(false));assertFalse(state.initialize(false));assertEquals(1,requests)
        assertTrue(state.fetch(true,false));assertEquals(2,requests);assertEquals(listOf("retry"),state.page.items.map{it.id_str})
    }
}
