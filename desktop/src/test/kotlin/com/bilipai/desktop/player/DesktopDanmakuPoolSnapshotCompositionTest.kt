package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.danmaku.engine.DanmakuItem
import com.android.purebilibili.feature.video.ui.components.DanmakuPoolSheet
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopDanmakuPoolSnapshotCompositionTest {
    @Test fun fullPoolKeepsSnapshotUntilRefreshAndResetsForNewVideo() {
        fun item(id:Long,text:String)=DanmakuItem().apply { danmakuId=id;this.text=text;showAtTime=id*1000;userHash="sender$id" }
        val first=item(1,"第一条弹幕")
        val second=item(2,"第二条弹幕")
        val third=item(3,"新视频弹幕")
        val list=mutableStateOf(listOf(first))
        val cid=mutableStateOf(100L)
        val seeks=mutableListOf<Long>()
        val feedback=mutableListOf<String>()
        val errors=mutableListOf<Throwable>()
        val platform=object:DesktopDanmakuPlatform {
            override fun isOwned()=true
            override fun showFeedback(message:String){feedback+=message}
            override fun copyText(text:String,label:String){error("Unexpected clipboard action")}
            @Composable override fun textSelection(text:String,title:String?,onDismiss:()->Unit){error("Unexpected text-selection action")}
        }
        val scheduler=TestCoroutineScheduler()
        val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _,e->errors+=e })
        val scene=ImageComposeScene(800,800,Density(1f),coroutineContext=scope.coroutineContext) {
            MaterialTheme { CompositionLocalProvider(LocalDesktopDanmakuBindings provides platform) {
                DanmakuPoolSheet(danmakuList=list.value,videoCid=cid.value,onSeekTo={seeks+=it},onDismiss={error("Unexpected dismiss")})
            } }
        }
        fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
        fun text(label:String)=all().firstOrNull {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text==label}==true}
        fun frames(){repeat(12){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(20)};scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString())}
        fun clickable(label:String):SemanticsNode {
            var node:SemanticsNode?=assertNotNull(text(label))
            while(node!=null&&!node.config.contains(SemanticsActions.OnClick))node=node.parent
            return assertNotNull(node)
        }
        try {
            frames();assertNotNull(text("共 1 条"));assertNotNull(text(first.text!!));assertTrue(seeks.isEmpty())
            list.value=listOf(first,second);frames()
            assertNotNull(text("共 1 条"));assertNull(text(second.text!!))
            clickable("刷新").config[SemanticsActions.OnClick].action!!.invoke();frames()
            assertNotNull(text("共 2 条"));assertNotNull(text(second.text!!))
            val row=clickable(second.text!!)
            assertTrue(row.config.contains(SemanticsActions.OnLongClick),"Existing desktop action menu must remain reachable")
            row.config[SemanticsActions.OnClick].action!!.invoke();frames()
            assertEquals(listOf(2000L),seeks);assertEquals(1,feedback.size)
            list.value=listOf(third);cid.value=200L;frames()
            assertNotNull(text("共 1 条"));assertNotNull(text(third.text!!));assertNull(text(first.text!!));assertNull(text(second.text!!))
            assertEquals(listOf(2000L),seeks)
        } finally {scene.close();scope.cancel();scheduler.runCurrent()}
        assertTrue(errors.isEmpty(),errors.toString())
    }
}
