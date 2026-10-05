package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.plugin.feed.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.list.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Actual generated repository/policy + owned environment + same RSS Store. The
 * interceptor answers all calls without real network/account/native/profile access. */
class DesktopPersonalRecapBindingTest {
    @Test fun originalAggregatorsKeepTypeDurationAndRealReadingWindowSemantics() {
        val rss=aggregatePersonalRssRecap(mapOf("s\u001fa" to 100L,"s\u001fb" to 101L,"other\u001fx" to 99L),100L)
        assertEquals(2,rss.readCount);assertEquals("s",rss.topSourceKey);assertEquals(listOf(100L,101L),rss.readTimestampsMs)
        val videos=listOf(item("finished",progress=-1,duration=120),item("partial",progress=500,duration=30),
            item("pgc",business="pgc",progress=20,duration=60),item("cheese",business="cheese",progress=-3),
            item("article",business="article"),item("old",viewAt=999L))
        val stats=aggregatePersonalVideoRecap(videos,1_000L)
        assertEquals(4,stats.videoCount);assertEquals(170L,stats.totalDurationSec);assertEquals(1,stats.finishedCount)
        assertEquals(4,stats.topUps.single().watchCount);assertEquals(17L,stats.topUps.single().mid)
        assertEquals(4,stats.watchTimestampsMs.size)
        val now=System.currentTimeMillis()
        assertEquals(now-7L*24*60*60*1000,resolvePersonalRecapWindowStart(now,PersonalRecapWindow.LAST_SEVEN_DAYS))
        assertEquals(now-30L*24*60*60*1000,resolvePersonalRecapWindowStart(now,PersonalRecapWindow.LAST_MONTH))
        assertTrue(resolvePersonalRecapWindowStart(now,PersonalRecapWindow.TODAY) <= now)
    }
    @Test fun actualRepositoryCursorStopsAfterWindowBoundaryAndUsesOriginalParameters(): Unit = runBlocking {
        fixture { h ->
            h.plan(listOf(item("one")),50,1_010L)
            h.plan(listOf(item("outside",viewAt=900)),49,900L)
            val request=h.binding.beginRequest()
            val stats=request.read { PersonalRecapRepository.videoRecap(h.binding.history,request::check,1_000_000L) }
            assertEquals(1,stats.videoCount);assertEquals(2,h.cursorRequests().size)
            assertNull(h.cursorRequests()[0].queryParameter("max"))
            assertEquals("50",h.cursorRequests()[1].queryParameter("max"))
            assertEquals("1010",h.cursorRequests()[1].queryParameter("view_at"))
            assertTrue(h.cursorRequests().all { it.queryParameter("ps")=="30" })
            request.finish()
        }
    }
    @Test fun originalRepeatedCursorAndTenPageIncompleteBudgetRemainReal(): Unit = runBlocking {
        fixture { h ->
            h.plan(listOf(item("one")),50,1_010L);h.plan(listOf(item("two")),50,1_010L)
            val request=h.binding.beginRequest()
            assertEquals(2,request.read { PersonalRecapRepository.videoRecap(h.binding.history,request::check,1_000_000L) }.videoCount)
            assertEquals(2,h.cursorRequests().size);request.finish()
        }
        fixture { h ->
            repeat(10) { page->h.plan((0 until 30).map { item("$page-$it") },100L-page,2_000L-page) }
            val request=h.binding.beginRequest()
            val stats=request.read { PersonalRecapRepository.videoRecap(h.binding.history,request::check,1_000_000L) }
            assertEquals(300,stats.videoCount);assertTrue(stats.historyMayBeIncomplete)
            assertEquals(10,h.cursorRequests().size);request.finish()
        }
    }
    @Test fun actualRssReadAndHistoryResponsePublishAsOneCurrentSnapshot(): Unit = runBlocking {
        fixture { h ->
            FeedReadingStore.recordRead(h.context,"builtin:source\u001fitem",atMs=1_000_001L)
            h.plan(listOf(item("real-api")),0,0)
            val request=h.binding.beginRequest()
            val rss=request.read { PersonalRecapRepository.rssRecap(h.context,1_000_000L) }
            val video=request.read { PersonalRecapRepository.videoRecap(h.binding.history,request::check,1_000_000L) }
            val cache=mutableMapOf<PersonalRecapWindow,HistoryRecapSnapshot>()
            request.publish { cache[PersonalRecapWindow.TODAY]=HistoryRecapSnapshot(emptyList(),rss,video) }
            assertEquals(1,cache.getValue(PersonalRecapWindow.TODAY).rssStats.readCount)
            assertEquals(1,cache.getValue(PersonalRecapWindow.TODAY).videoStats.videoCount)
            request.finish()
        }
    }
    @Test fun retiredActualAccountRejectsSuspendedApiAndDoesNotStartNextPage(): Unit = runBlocking {
        fixture { h ->
            val pending=h.plan(listOf(item("old-account")),50,1_010L,delayed=true)
            val result=async {
                val request=h.binding.beginRequest()
                try { request.read { PersonalRecapRepository.videoRecap(h.binding.history,request::check,1_000_000L) } }
                finally { request.finish() }
            }
            pending.entered.await();h.epoch.incrementAndGet();pending.release.countDown()
            assertIs<CancellationException>(runCatching { result.await() }.exceptionOrNull())
            assertEquals(1,h.cursorRequests().size);assertTrue(h.feedback.isEmpty())
        }
    }
    @Test fun finalActualEntryAdmissionRejectsLateCacheAndKeepsRetainedSnapshot(): Unit = runBlocking {
        fixture { h ->
            h.plan(listOf(item("new")),0,0)
            val retained=HistoryRecapSnapshot(emptyList(),PersonalRssRecapStats(),aggregatePersonalVideoRecap(listOf(item("old")),1_000L))
            val cache=mutableMapOf(PersonalRecapWindow.TODAY to retained)
            val request=h.binding.beginRequest()
            val next=request.read { PersonalRecapRepository.videoRecap(h.binding.history,request::check,1_000_000L) }
            h.beforeAdmission={h.epoch.incrementAndGet()}
            assertIs<CancellationException>(runCatching {
                request.publish { cache[PersonalRecapWindow.TODAY]=HistoryRecapSnapshot(emptyList(),PersonalRssRecapStats(),next) }
            }.exceptionOrNull())
            assertSame(retained,cache.getValue(PersonalRecapWindow.TODAY));request.finish()
        }
    }
    @Test fun oldSameEntryRequestCannotPublishOrClearSuccessorBusy(): Unit = runBlocking {
        fixture { h ->
            val old=h.binding.beginRequest();val next=h.binding.beginRequest();var busy=true;var published=0
            old.cleanup { busy=false };old.finish()
            assertTrue(busy)
            assertIs<CancellationException>(runCatching { old.publish { published++ } }.exceptionOrNull())
            next.publish { published++ };assertEquals(1,published)
            next.cleanup { busy=false };next.finish();assertFalse(busy)
        }
    }
    @Test fun cancelledActualCallerOnlyReleasesItsOwnLoadingWhileEntryLives(): Unit = runBlocking {
        fixture { h ->
            val begun=CompletableDeferred<Unit>();var busy=true
            val job=launch(start=CoroutineStart.UNDISPATCHED) {
                val request=h.binding.beginRequest();begun.complete(Unit)
                try { awaitCancellation() }
                finally { request.cleanup { busy=false };request.finish() }
            }
            begun.await();job.cancelAndJoin();assertFalse(busy)
            val successor=h.binding.beginRequest();successor.publish { busy=true };assertTrue(busy);successor.finish()
        }
    }
    @Test fun actualRecapQueriesNeverAdvanceTheExistingHistoryVmCursor(): Unit = runBlocking {
        fixture { h ->
            val vm=HistoryViewModel(h.environment)
            h.plan(listOf(item("list-row")),42,1_042L);vm.loadData()
            withTimeout(5_000) { vm.uiState.first { !it.isLoading && it.items.size==1 } }
            val retained=assertNotNull(vm.getHistoryItem("list-row"))
            h.plan(listOf(item("recap-only")),0,0)
            val request=h.binding.beginRequest()
            request.read { PersonalRecapRepository.videoRecap(h.binding.history,request::check,1_000_000L) };request.finish()
            assertSame(retained,vm.getHistoryItem("list-row"));assertNull(vm.getHistoryItem("recap-only"))
            h.plan(listOf(item("next-list")),0,0);vm.loadMore(retry=true)
            withTimeout(5_000) { vm.uiState.first { it.items.size==2 } }
            assertEquals("42",h.cursorRequests().last().queryParameter("max"))
            assertEquals("1042",h.cursorRequests().last().queryParameter("view_at"))
        }
    }

    private suspend fun fixture(block:suspend(Harness)->Unit) {
        val h=Harness()
        try { block(h);assertTrue(h.unexpected.isEmpty(),h.unexpected.joinToString()) }
        finally { h.close() }
    }
    private class Plan(val text:String,delayed:Boolean) {
        val entered=CompletableDeferred<HttpUrl>();val release=CountDownLatch(if(delayed)1 else 0)
    }
    private class Harness {
        val path=Files.createTempDirectory("recap-owned-api-")
        val context=DesktopPluginContext(DesktopPluginStore(path));val epoch=AtomicLong(1)
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default)
        val feedback=ConcurrentLinkedQueue<String>();val unexpected=ConcurrentLinkedQueue<String>()
        val requests=ConcurrentLinkedQueue<HttpUrl>();private val plans=ConcurrentLinkedQueue<Plan>();private val all=ConcurrentLinkedQueue<Plan>()
        var beforeAdmission:(()->Unit)?=null
        private val json=Json { ignoreUnknownKeys=true }
        private val client=OkHttpClient.Builder().addInterceptor { chain->
            val request=chain.request();requests.add(request.url)
            if(request.method!="GET") {unexpected.add("mutation ${request.method}");throw IOException("unexpected mutation")}
            val text=when(request.url.encodedPath) {
                "/x/v2/history/shadow"->"{\"code\":0,\"data\":false}"
                "/x/web-interface/history/cursor"->{
                    val plan=plans.poll()?:run{unexpected.add("unplanned ${request.url}");throw IOException("unplanned local response")}
                    plan.entered.complete(request.url);check(plan.release.await(5,TimeUnit.SECONDS));plan.text
                }
                else->{unexpected.add("unknown ${request.url.encodedPath}");throw IOException("unknown local endpoint")}
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic local API")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val api=Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        val environment=DesktopFavoriteEnvironment(scope,api,null,null,null,{epoch.get()==1L},{"synthetic"},{17L},
            {feedback.add(it)},MutableSharedFlow<Long>(),{_,_->0L},{false},{},{null},{"android"})
        val binding=DesktopPersonalRecapBinding(context,environment,DesktopFavoritePreferences(context.store),{epoch.get()==1L},{action->
            beforeAdmission?.invoke();if(epoch.get()!=1L)false else{action();true}
        })
        fun plan(items:List<HistoryData>,max:Long,viewAt:Long,delayed:Boolean=false):Plan = Plan(
            Json { encodeDefaults=true }.encodeToString(HistoryResponse(data=HistoryListData(list=items,cursor=HistoryCursor(max=max,view_at=viewAt,business="archive")))),delayed
        ).also {all.add(it);plans.add(it)}
        fun cursorRequests()=requests.filter {it.encodedPath=="/x/web-interface/history/cursor"}
        suspend fun close() {
            all.forEach {it.release.countDown()};job.cancelAndJoin()
            client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();path.toFile().deleteRecursively()
        }
    }
    companion object {
        private fun item(bvid:String,business:String="archive",viewAt:Long=1_010L,progress:Int=37,duration:Int=120) = HistoryData(
            title=bvid,author_name="UP",author_mid=17L,duration=duration,progress=progress,view_at=viewAt,
            history=HistoryPage(bvid=bvid,cid=7007L,oid=170001L,business=business,page=1))
    }
}
