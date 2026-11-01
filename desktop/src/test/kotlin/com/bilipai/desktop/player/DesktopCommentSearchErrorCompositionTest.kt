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
class DesktopCommentSearchErrorCompositionTest {
    @Test fun readyWithErrorShowsActualErrorAndRetryRequestsFirstPageAgain() {
        val scheduler=TestCoroutineScheduler()
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _, t->errors+=t })
        val directory=Files.createTempDirectory("comment-search-error-")
        val context=DesktopPluginContext(DesktopPluginStore(directory))
        val searches=mutableListOf<List<Any?>>()
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
                        if(searches.size==1) Class.forName("kotlin.ResultKt").getMethod("createFailure",Throwable::class.java)
                            .invoke(null,IllegalStateException("Network diagnostic: fixture failure"))
                        else {assertEquals(2,searches.size);ReplyData(replies=emptyList(),cursor=ReplyCursor(isEnd=true))}
                    } else {assertEquals(3,call[4]);ReplyData(replies=emptyList(),cursor=ReplyCursor(isEnd=true))}
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
                else->error("Unexpected platform ${m.name}")
            } } as DesktopCommentPlatform
        val vm=VideoCommentViewModel(scope,requests).also {it.init(170001,42,CommentSortMode.HOT,0)}
        val scene=ImageComposeScene(800,900,Density(1f),coroutineContext=scope.coroutineContext) {
            val state by vm.fullSearchState.collectAsState()
            val replies by vm.fullSearchReplies.collectAsState()
            MaterialTheme { CompositionLocalProvider(LocalDesktopCommentBindings provides platform) {
                val backdrop=rememberLayerBackdrop()
                CommentSearchSheetContent(emptyList(),replies,state,onLoadAllComments={vm.loadAllCommentsForSearch()},
                    onCommentClick={error("No result action")},onDismiss={error("No dismissal")},miuixBackdrop=backdrop,
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
        try {
            frames();assertEquals(1,searches.size);assertTrue(vm.fullSearchState.value.isReady);assertTrue(containerCompositions>0)
            val input=all().first {it.config.contains(SemanticsActions.SetText)}
            input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("needle"));frames()
            assertTrue(texts().contains("Network diagnostic: fixture failure"))
            assertFalse(texts().any {it.contains("未找到")||it.contains("已全量加载")})
            var retry:SemanticsNode?=all().first {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text=="重试"}==true}
            while(retry!=null&&!retry.config.contains(SemanticsActions.OnClick))retry=retry.parent
            assertNotNull(retry).config[SemanticsActions.OnClick].action!!.invoke();frames()
            assertEquals(2,searches.size);assertEquals(searches[0],searches[1])
            assertNull(vm.fullSearchState.value.error);assertTrue(vm.fullSearchState.value.isComplete)
            assertFalse(texts().contains("Network diagnostic: fixture failure"))
            frames();assertEquals(2,searches.size)
        } finally {scene.close();scope.cancel();scheduler.runCurrent();directory.toFile().deleteRecursively()}
    }
}
