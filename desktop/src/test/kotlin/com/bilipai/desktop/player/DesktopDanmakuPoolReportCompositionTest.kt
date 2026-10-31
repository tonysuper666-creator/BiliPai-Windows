package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.android.purebilibili.danmaku.engine.DanmakuItem
import com.android.purebilibili.feature.video.ui.components.DanmakuPoolSheet
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalDanmakuSession
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class,ExperimentalCoroutinesApi::class)
class DesktopDanmakuPoolReportCompositionTest {
    private class Port:DesktopDanmakuActions {
        val calls=mutableListOf<List<Any>>()
        val result=CompletableDeferred<Result<Unit>>()
        override suspend fun reportDanmaku(cid:Long,dmid:Long,reason:Int,content:String):Result<Unit> {
            calls+=listOf(cid,dmid,reason,content)
            return withContext(NonCancellable){result.await()}
        }
        override suspend fun getDanmakuThumbupState(cid:Long,dmid:Long)=error("Unexpected stats")
        override suspend fun recallDanmaku(cid:Long,dmid:Long)=error("Unexpected recall")
        override suspend fun likeDanmaku(cid:Long,dmid:Long,like:Boolean)=error("Unexpected like")
    }
    private fun item(hash:String="original",self:Boolean=false)=DanmakuItem().apply {danmakuId=42;userHash=hash;text="选中弹幕";isSelf=self}
    private inner class Harness(hash:String="original",self:Boolean=false,val details:Boolean=true):AutoCloseable {
        val port=Port()
        var owned=true
        val scheduler=TestCoroutineScheduler()
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler{_,e->errors+=e})
        val environment=DesktopDanmakuSessionEnvironment(70,3,1,scope,port,{owned},{0},{},{})
        val session=DesktopOriginalDanmakuSession(environment)
        val blocks=mutableListOf<String>()
        val legacy=mutableListOf<Pair<Long,Int>>()
        val list=mutableStateOf(listOf(item(hash,self)))
        val cid=mutableStateOf(70L)
        val platform=object:DesktopDanmakuPlatform {
            override fun isOwned()=owned
            override fun showFeedback(message:String)=Unit
            override fun copyText(text:String,label:String){error("Unexpected copy")}
            @Composable override fun textSelection(text:String,title:String?,onDismiss:()->Unit){error("Unexpected text selection")}
        }
        val scene=ImageComposeScene(800,1000,Density(1f),coroutineContext=scope.coroutineContext) {
            MaterialTheme {CompositionLocalProvider(LocalDesktopDanmakuBindings provides platform) {
                DanmakuPoolSheet(danmakuList=list.value,videoCid=cid.value,onSeekTo={error("Unexpected seek")},onDismiss={error("Unexpected pool dismissal")},
                    onReportDanmaku={id,reason->legacy+=id to reason},
                    onReportDanmakuWithSender=if(details) {id,sender,reason,explanation,block->
                        session.reportDanmaku(id,reason,explanation) {if(block && sender.isNotBlank() && environment.isOwned() && platform.isOwned())blocks+=sender}
                    } else null)
            }}
        }
        fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
        fun label(s:String)=all().firstOrNull {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text==s}==true}
        fun frame(){repeat(12){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(20)};scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString())}
        fun action(s:String,key:SemanticsPropertyKey<AccessibilityAction<()->Boolean>> = SemanticsActions.OnClick){
            var n:SemanticsNode?=assertNotNull(label(s),s)
            while(n!=null&&!n.config.contains(key))n=n.parent
            assertNotNull(n).config[key].action!!.invoke();frame()
        }
        fun open(){frame();action("选中弹幕",SemanticsActions.OnLongClick)}
        fun report(block:Boolean,explanation:String="") {
            open();action("举报弹幕");action(if(explanation.isEmpty())"违法违禁" else "其它")
            if(explanation.isNotEmpty()){all().single {it.config.contains(SemanticsActions.SetText) && nodes(it).any {n->n.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text=="请说明举报原因"}==true}}.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(explanation));frame()}
            if(block){all().single {it.config.contains(SemanticsProperties.ToggleableState)}.config[SemanticsActions.OnClick].action!!.invoke();frame()}
            action("提交举报");assertNull(label("提交举报"));assertTrue(blocks.isEmpty())
        }
        override fun close(){session.close();scene.close();scope.cancel();scheduler.runCurrent()}
    }
    @Test fun completionUsesSubmittedSenderAfterRefreshAndRejectsRetiredVideo() {
        for(outcome in listOf("success","failure","cancel","retire")) for(block in listOf(false,true)) Harness().use {h->
            val message="  多行\n🙂"+"x".repeat(10000)+"  "
            h.report(block,message)
            assertEquals(listOf(listOf<Any>(70L,42L,11,message.trim())),h.port.calls)
            h.list.value.single().userHash="mutated-after-submit"
            h.list.value=listOf(item("replacement"));h.frame();h.action("刷新")
            if(outcome=="retire"){h.owned=false;h.cid.value=71;h.frame()}
            if(outcome=="cancel")h.session.close()
            if(outcome in listOf("success","failure"))h.open()
            h.port.result.complete(if(outcome=="failure")Result.failure(IllegalStateException("rejected"))else Result.success(Unit));h.frame()
            assertEquals(if(block&&outcome in listOf("success","failure"))listOf("original")else emptyList(),h.blocks)
            assertEquals(1,h.port.calls.size);assertNull(h.label("提交举报"))
            if(outcome in listOf("success","failure"))assertNotNull(h.label("弹幕操作"))
        }
    }
    @Test fun emptySenderSelfAndLegacyCannotOfferUnusableBlockOrExplanation() {
        Harness(hash="").use {h->h.open();h.action("举报弹幕");assertNull(h.label("同时屏蔽该发送者"));h.action("违法违禁");h.action("提交举报");h.port.result.complete(Result.success(Unit));h.frame();assertTrue(h.blocks.isEmpty());assertEquals(1,h.port.calls.size)}
        Harness(self=true).use {h->h.open();h.action("举报弹幕");assertNull(h.label("同时屏蔽该发送者"));h.action("违法违禁");h.action("提交举报");h.port.result.complete(Result.success(Unit));h.frame();assertTrue(h.blocks.isEmpty());assertEquals(1,h.port.calls.size)}
        Harness(details=false).use {h->h.open();h.action("举报弹幕");assertNull(h.label("提交举报"));assertTrue(h.all().none {it.config.contains(SemanticsProperties.ToggleableState)});h.action("其他");assertEquals(listOf(42L to 8),h.legacy);assertTrue(h.port.calls.isEmpty())}
    }
}
