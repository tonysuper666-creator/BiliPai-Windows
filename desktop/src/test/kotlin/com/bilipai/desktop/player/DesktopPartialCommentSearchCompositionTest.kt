package com.bilipai.desktop.player

import com.android.purebilibili.core.ui.blur.LocalFloatingChromeBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.ui.components.CommentSearchSheetContent
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopPartialCommentSearchCompositionTest {
    @Test fun fullVmPublishesPartialRootAndChildDuringLoadingAndFailureThenRetries() {
        val scheduler=TestCoroutineScheduler()
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _, t->errors+=t })
        val directory=Files.createTempDirectory("comment-search-error-")
        val context=DesktopPluginContext(DesktopPluginStore(directory))
        val searches=mutableListOf<List<Any?>>()
        val root=ReplyItem(rpid=10,content=ReplyContent(message="root needle"),replies=listOf(
            ReplyItem(rpid=11,root=10,content=ReplyContent(message="child needle"))))
        var pending:kotlin.coroutines.Continuation<Result<ReplyData>>?=null
        var threads=0
        val copies=mutableListOf<List<Any?>>()
        var feedback=0
        var rootClicks=0
        var childClicks=0
        var dismissals=0
        var containerCompositions=0
        val requests=java.lang.reflect.Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) { _,m,args -> when(m.name.substringBefore('-')) {
                "isOwned"->true
                "currentMid"->111L
                "getMobileCommentsForSubject"->{
                    val call=args!!.dropLast(1)
                    assertEquals(listOf(170001L,1,1,VIDEO_COMMENT_PAGE_SIZE),call.take(4));assertNull(call[5])
                    if(call[4]==2){
                        searches+=call
                        assertTrue(searches.size<=2)
                        ReplyData(replies=listOf(root),cursor=ReplyCursor(isEnd=true))
                    } else {assertEquals(3,call[4]);ReplyData(replies=emptyList(),cursor=ReplyCursor(isEnd=true))}
                }
                "getMobileSortedSubCommentsForSubject"->{
                    assertEquals(listOf(170001L,1,10L,2,null,0L,false),args!!.dropLast(1))
                    threads++
                    if(threads==1){
                        @Suppress("UNCHECKED_CAST")
                        val continuation=args.last() as kotlin.coroutines.Continuation<Result<ReplyData>>
                        pending=continuation
                        kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                    } else {assertEquals(2,threads);ReplyData(replies=emptyList(),cursor=ReplyCursor(isEnd=true))}
                }
                else->error("Unexpected request ${m.name}")
            } } as DesktopVideoCommentRequests
        val platform=java.lang.reflect.Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy,m,args -> when(m.name) {
                "getContext"->context
                "isOwned"->true
                "equals"->proxy===args!![0]
                "hashCode"->System.identityHashCode(proxy)
                "toString"->"Strict comment platform"
                "copyText"->{copies+=args!!.toList();Unit}
                "showFeedback"->{assertEquals("评论已复制",args!![0]);feedback++;Unit}
                else->error("Unexpected platform ${m.name}")
            } } as DesktopCommentPlatform
        val vm=VideoCommentViewModel(scope,requests).also {it.init(170001,42,CommentSortMode.HOT,0)}
        val scene=ImageComposeScene(800,900,Density(1f),coroutineContext=scope.coroutineContext) {
            val state by vm.fullSearchState.collectAsState()
            val replies by vm.fullSearchReplies.collectAsState()
            MaterialTheme { CompositionLocalProvider(LocalDesktopCommentBindings provides platform) {
                val backdrop=rememberLayerBackdrop()
                CommentSearchSheetContent(emptyList(),replies,state,onLoadAllComments={vm.loadAllCommentsForSearch()},
                    onCommentClick={assertEquals(10L,it.rpid);rootClicks++},onSubReplyClick={assertEquals(10L,it.rpid);childClicks++},onDismiss={dismissals++},miuixBackdrop=backdrop,
                    contentContainer={ content ->
                        assertSame(backdrop,LocalFloatingChromeBackdrop.current)
                        SideEffect { containerCompositions++ }
                        content()
                    })
            } }
        }
        fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
        fun texts()=all().flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map {it.text}
        fun frames(){repeat(30){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(20)};scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString())}
        fun click(text:String){
            var node:SemanticsNode?=all().first {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text==text}==true}
            while(node!=null&&!node.config.contains(SemanticsActions.OnClick))node=node.parent
            assertNotNull(node).config[SemanticsActions.OnClick].action!!.invoke()
            frames()
        }
        try {
            frames();assertEquals(1,searches.size);assertEquals(1,threads);assertTrue(vm.fullSearchState.value.isLoading)
            all().first {it.config.contains(SemanticsActions.SetText)}.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("needle"));frames()
            assertTrue(texts().containsAll(listOf("root needle","child needle","部分结果 · 2 条")))
            assertNotNull(pending).resumeWith(Result.success(Result.failure(IllegalStateException("partial read failed"))))
            frames();assertFalse(vm.fullSearchState.value.isComplete);assertTrue(vm.fullSearchState.value.isReady)
            assertTrue(texts().containsAll(listOf("root needle","child needle","partial read failed","重试")))
            click("复制");assertEquals(listOf(listOf<Any?>("root needle","评论")),copies);assertEquals(1,feedback)
            click("root needle");click("child needle")
            assertEquals(1,rootClicks);assertEquals(1,childClicks);assertEquals(2,dismissals)
            click("重试");assertEquals(2,searches.size);assertEquals(searches[0],searches[1]);assertEquals(2,threads)
            assertTrue(vm.fullSearchState.value.isComplete);assertNull(vm.fullSearchState.value.error)
            frames();assertEquals(1,copies.size);assertEquals(1,feedback);assertEquals(2,searches.size)
        } finally {scene.close();scope.cancel();scheduler.runCurrent();directory.toFile().deleteRecursively()}
    }
}
