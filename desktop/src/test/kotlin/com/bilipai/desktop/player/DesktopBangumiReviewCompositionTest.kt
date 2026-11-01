package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.AccountSessionIdentity
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Collections
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopBangumiReviewCompositionTest {
    private class Call(val name:String,val args:List<Any?>,val continuation:Continuation<Any?>) {
        private var done=false
        @Synchronized fun complete(value:Any?) { check(!done);done=true;continuation.resume(value) }
        @Synchronized fun cancel() { if(!done){done=true;continuation.resumeWithException(CancellationException("fixture close"))} }
    }
    private class Fixture(val glass:Boolean=false,val width:Int=1000,val density:Float=1f,val paging:Boolean=false) {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default+CoroutineExceptionHandler { _,e -> failures+=e })
        val pending = Collections.synchronizedList(mutableListOf<Call>())
        val writes = Channel<Call>(Channel.UNLIMITED)
        val calls = Collections.synchronizedList(mutableListOf<Pair<String,List<Any?>>>())
        var rejectLatest = false
        val feedback = mutableListOf<String>()
        val routes = mutableListOf<Pair<Long,String>>()
        val web = mutableListOf<Pair<String,String>>()
        var owned = true
        val api = Proxy.newProxyInstance(BangumiApi::class.java.classLoader,arrayOf(BangumiApi::class.java)) { _,method,args ->
            val values = args!!.dropLast(1); calls += method.name to values
            when(method.name) {
                "getBangumiShortReviews" -> if (rejectLatest && values[2] == 1) BangumiReviewListResponse(code=-1,message="synthetic latest failure") else {
                    val more=values[3]=="opaque-next"
                    BangumiReviewListResponse(data=BangumiReviewListData(next=if(paging&&!more) "opaque-next" else "",list=
                        if(!paging) listOf(BangumiReviewItem(review_id=51,content="Original review",score=8,author=BangumiReviewAuthor(mid=71,uname="Author"),stat=BangumiReviewStat()))
                        else (if(more) 11..20 else 1..10).map { BangumiReviewItem(review_id=it.toLong(),content="Review $it",author=BangumiReviewAuthor(mid=72,uname="Other")) }))
                }
                "getBangumiLongReviews" -> BangumiReviewListResponse(data=BangumiReviewListData(list=listOf(
                    BangumiReviewItem(review_id=61,article_id=91,title="Long review",content="Article body",author=BangumiReviewAuthor(mid=72,uname="Other")))))
                "postBangumiShortReview","modifyBangumiShortReview","deleteBangumiShortReview","likeBangumiReview","dislikeBangumiReview" -> {
                    @Suppress("UNCHECKED_CAST")
                    val call=Call(method.name,values,args.last() as Continuation<Any?>);pending+=call;writes.trySend(call).getOrThrow();COROUTINE_SUSPENDED
                }
                else -> error("Unexpected API ${method.name}")
            }
        } as BangumiApi
        private inline fun <reified T> unused():T = Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { _,method,_ -> error("Unused port ${method.name}") } as T
        val repository=DesktopOriginalBangumiHubRepository(api,unused<BilibiliApi>(),unused<SearchApi>(),{"synthetic-csrf"},{71L})
        val vm=BangumiHubViewModel(DesktopBangumiHubEnvironment(repository,scope,{true},{owned},{ a -> if(owned){a();true}else false },MutableStateFlow(AccountSessionIdentity(71,1))))
        val progress=Proxy.newProxyInstance(DesktopOriginalVideoProgressPort::class.java.classLoader,arrayOf(DesktopOriginalVideoProgressPort::class.java)) { proxy,m,a ->
            when(m.name){"equals"->proxy===a?.singleOrNull();"hashCode"->System.identityHashCode(proxy);"toString"->"Unused progress";else->error("Unexpected progress ${m.name}")}
        } as DesktopOriginalVideoProgressPort
        val environment=DesktopOriginalBangumiPagesEnvironment(vm,
            DesktopOriginalBangumiPagesRequests(api,repository,{"synthetic-csrf"},{null},{owned},{owned}),
            DesktopOriginalBangumiReviewRequests(api,{"synthetic-csrf"},{owned},{owned}),progress,scope,
            {a->if(owned){a();true}else false},{owned},{feedback+=it},false,{BangumiFollowSession(null,null)})
        val dir=Files.createTempDirectory("review-compose")
        val context=DesktopPluginContext(DesktopPluginStore(dir.resolve("preferences")))
        val listeners = mutableSetOf<DesktopHomeWindowBackgroundPort.Listener>()
        val background=object:DesktopHomeWindowBackgroundPort {
            override val isInBackground=false
            override fun addListener(listener:DesktopHomeWindowBackgroundPort.Listener){check(listeners.add(listener))}
            override fun removeListener(listener:DesktopHomeWindowBackgroundPort.Listener){check(listeners.remove(listener))}
        }
        val platform=DesktopHomePlatform(false,true,false,false,false,false,background,0f,false)
        val tabSettings=DesktopLiquidTabSettings(context.store)
        var mounted by mutableStateOf(true)
        var mid by mutableStateOf<Long?>(71L)
        var now=0L
        val scene=ImageComposeScene((width*density).toInt(),(1200*density).toInt(),Density(density)) {
            MaterialTheme { CompositionLocalProvider(LocalDesktopOriginalBangumiPagesEnvironment provides environment,
                LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                LocalDesktopHomePlatform provides platform, LocalDesktopLiquidTabSettings provides tabSettings,
                LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=glass),
                LocalComponentMotionEnabled provides false) {
                if(mounted) key(mid) { BangumiReviewScreen(19,"Reviews",{},mid,
                    onOpenWeb={url,title->web+=url to title},onOpenArticle={id,title->routes+=id to title}) }
            } }
        }
        fun frames(n:Int=8){repeat(n){Snapshot.sendApplyNotifications();now+=20_000_000;scene.render(now).close()}}
        fun all(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::all)
        fun nodes()=scene.semanticsOwners.flatMap{all(it.unmergedRootSemanticsNode)}
        fun labeled(label:String)=nodes().filter{n->n.config.getOrNull(SemanticsProperties.Text)?.any{it.text==label}==true || n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true}
        fun click(label:String,index:Int=0){
            var n:SemanticsNode?=labeled(label).getOrNull(index);assertNotNull(n,label)
            while(n!=null&&!n.config.contains(SemanticsActions.OnClick))n=n.parent
            assertTrue(assertNotNull(n,label).config[SemanticsActions.OnClick].action!!.invoke());frames()
        }
        suspend fun until(p:()->Boolean){withTimeout(5000){while(!p()){frames();yield()}};frames()}
        suspend fun write(name:String):Call { val c=withTimeout(5000){while(writes.isEmpty){frames();yield()};writes.receive()};assertEquals(name,c.name);return c }
        suspend fun close(){scene.close();synchronized(pending){pending.forEach{it.cancel()}};scope.cancel();scope.coroutineContext[Job]!!.join();for(c in pending){c.continuation.context[Job]?.join()};assertTrue(listeners.isEmpty());assertTrue(failures.isEmpty(),failures.toString());dir.toFile().deleteRecursively()}
    }

    @Test fun selectedPageActionsAndArticleRoutingUseTheRealReviewPage()=runBlocking {
        val f=Fixture()
        try {
            f.until{f.labeled("Original review").isNotEmpty() && f.calls.count{it.first=="getBangumiLongReviews"}==1}
            assertEquals(1,f.calls.count{it.first=="getBangumiShortReviews"})
            f.click("点赞"); val like=f.write("likeBangumiReview");assertEquals(listOf<Any?>(19L,2,51L,"synthetic-csrf"),like.args)
            like.complete(SimpleApiResponse());f.until{f.labeled("取消点赞").isNotEmpty()}
            f.click("点踩");val dislike=f.write("dislikeBangumiReview");dislike.complete(SimpleApiResponse());f.until{f.labeled("取消点踩").isNotEmpty()}
            f.click("举报");assertEquals(listOf("https://www.bilibili.com/appeal/?reviewId=51&type=shortComment&mediaId=19" to "举报短评"),f.web)
            f.click("长评");f.frames(60);f.until{f.labeled("查看长评").isNotEmpty()}
            f.click("查看长评");assertEquals(listOf(91L to "Long review"),f.routes)
            f.click("短评");f.frames(60);f.until{f.labeled("编辑").isNotEmpty()}
            assertEquals(1,f.calls.count{it.first=="getBangumiShortReviews"});assertEquals(1,f.calls.count{it.first=="getBangumiLongReviews"})
            f.click("编辑");f.frames()
            val text=assertNotNull(f.nodes().firstOrNull{it.config.contains(SemanticsActions.SetText)})
            assertTrue(text.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(" edited ")))
            f.click("保存");val edit=f.write("modifyBangumiShortReview");assertEquals(listOf<Any?>(19L,51L,8,"edited","synthetic-csrf"),edit.args)
            edit.complete(SimpleApiResponse());f.until{f.feedback.contains("已更新短评")}
            f.click("删除");f.click("删除",1);val delete=f.write("deleteBangumiShortReview");assertEquals(listOf<Any?>(19L,51L,"synthetic-csrf"),delete.args)
            delete.complete(SimpleApiResponse());f.until{f.labeled("还没有点评").isNotEmpty()}
        } finally {f.close()}
    }

    @Test fun leavingCompositionCancelsPendingWriteAndLateResultCannotToastIntoNewAccountPage()=runBlocking {
        val f=Fixture()
        var pending:Call?=null
        try {
            f.until{f.labeled("Original review").isNotEmpty()}
            f.click("写短评")
            val text=assertNotNull(f.nodes().firstOrNull{it.config.contains(SemanticsActions.SetText)})
            text.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("new review"));f.frames()
            val toggle=assertNotNull(f.nodes().firstOrNull{it.config.contains(SemanticsProperties.ToggleableState)})
            toggle.config[SemanticsActions.OnClick].action!!.invoke();f.frames()
            f.click("发布");val call=f.write("postBangumiShortReview");pending=call
            assertEquals(listOf<Any?>(19L,8,"new review",1,"synthetic-csrf"),call.args)
            f.click("发布");assertEquals(1,f.calls.count{it.first=="postBangumiShortReview"})
            val requestJob=assertNotNull(call.continuation.context[Job])
            f.mounted=false;f.frames();assertTrue(requestJob.isCancelled)
            f.mid=72;f.mounted=true;f.frames()
            call.complete(SimpleApiResponse());pending=null
            withTimeout(5000){requestJob.join()};f.until{f.labeled("Original review").isNotEmpty()}
            assertTrue(f.feedback.isEmpty());assertTrue(f.labeled("编辑").isEmpty())
            assertEquals(1,f.calls.count{it.first=="postBangumiShortReview"})
        } finally { pending?.cancel();f.close() }
    }
    @Test fun pagingSortRetryAndRealListScrollingKeepTheQueryParameters()=runBlocking {
        val f=Fixture(paging=true)
        try {
            f.until{f.labeled("Review 1").isNotEmpty()}
            fun scroll() {
                val node=assertNotNull(f.nodes().firstOrNull{it.config.contains(SemanticsActions.ScrollBy)})
                assertTrue(node.config[SemanticsActions.ScrollBy].action!!.invoke(0f,1800f));f.frames(60)
            }
            repeat(3){scroll()}
            f.until{f.calls.any{it.first=="getBangumiShortReviews"&&it.second[3]=="opaque-next"}}
            val pages=f.calls.filter{it.first=="getBangumiShortReviews"}
            assertEquals(listOf("","opaque-next"),pages.map{it.second[3]})
            assertTrue(pages.all{it.second[0]==19L&&it.second[1]==20&&it.second[2]==0})
            f.rejectLatest=true;f.click("最新")
            f.until{f.labeled("synthetic latest failure").isNotEmpty()}
            assertTrue(f.labeled("重试").isNotEmpty())
            f.rejectLatest=false;f.click("重试")
            f.until{f.labeled("Review 1").isNotEmpty()}
            val latest=f.calls.filter{it.first=="getBangumiShortReviews"&&it.second[2]==1}
            assertEquals(listOf("","","opaque-next"),latest.map{it.second[3]});assertEquals(latest[0].second,latest[1].second)
            assertTrue(f.feedback.isEmpty())
        }finally{f.close()}
    }

    @Test fun publicPagerControlsRemainUsableAcrossGlassWidthsAndDensities()=runBlocking {
        for(glass in listOf(false,true))for(width in listOf(400,900))for(density in listOf(1f,2f)) {
            val f=Fixture(glass,width,density)
            try {
                f.until{f.labeled("Original review").isNotEmpty()}
                for(label in listOf("短评","长评","默认","最新")) {
                    val bounds=assertNotNull(f.labeled(label).firstOrNull(),label).boundsInRoot
                    assertTrue(bounds.width>0&&bounds.left>=0&&bounds.right<=width*density,"$label glass=$glass width=$width density=$density bounds=$bounds")
                }
                f.click("长评");f.frames(60);f.until{f.labeled("查看长评").isNotEmpty()}
                f.click("查看长评");assertEquals(listOf(91L to "Long review"),f.routes)
                f.click("短评");f.frames(60);f.until{f.labeled("Original review").isNotEmpty()}
                assertTrue(f.feedback.isEmpty())
            }finally{f.close()}
        }
    }

    @Test fun typeAndSortControlsKeepSeparateSlotsAndSortSelectionReachesTheRequest()=runBlocking {
        for(glass in listOf(false,true))for(width in listOf(400,900))for(density in listOf(1f,2f)) {
            val f=Fixture(glass,width,density)
            try {
                f.until{f.labeled("Original review").isNotEmpty()}
                fun bounds(label:String)=assertNotNull(f.labeled(label).firstOrNull(),label).boundsInRoot
                val typeRight=listOf("短评","长评").maxOf{bounds(it).right}
                val sortLeft=listOf("默认","最新").minOf{bounds(it).left}
                assertTrue(typeRight<=sortLeft,"glass=$glass width=$width density=$density type=$typeRight sort=$sortLeft")
                f.click("最新")
                f.until{f.calls.any{it.first=="getBangumiShortReviews"&&it.second[2]==1}}
                assertTrue(f.feedback.isEmpty())
            }finally{f.close()}
        }
    }

}
