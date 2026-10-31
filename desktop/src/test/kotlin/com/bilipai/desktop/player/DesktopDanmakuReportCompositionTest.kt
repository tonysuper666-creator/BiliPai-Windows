package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalDanmakuSession
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDanmakuReportCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    private class Port : DesktopDanmakuActions {
        val calls = mutableListOf<List<Any>>()
        val result = CompletableDeferred<Result<Unit>>()
        override suspend fun reportDanmaku(cid:Long,dmid:Long,reason:Int,content:String):Result<Unit> {
            calls += listOf(cid,dmid,reason,content)
            return withContext(NonCancellable) { result.await() }
        }
        override suspend fun getDanmakuThumbupState(cid:Long,dmid:Long)=error("Unexpected stats")
        override suspend fun recallDanmaku(cid:Long,dmid:Long)=error("Unexpected recall")
        override suspend fun likeDanmaku(cid:Long,dmid:Long,like:Boolean)=error("Unexpected like")
    }
    private inner class Harness(val details: Boolean = true) : AutoCloseable {
        val port = Port()
        var owned = true
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val feedback = mutableListOf<String>()
        val environment = DesktopDanmakuSessionEnvironment(70,3,1,scope,port,{owned},{0},feedback::add,{error("Unexpected seek")})
        val session = DesktopOriginalDanmakuSession(environment)
        var blocks = 0
        var dismissals = 0
        val legacy = mutableListOf<Int>()
        val scene = ImageComposeScene(400,900,Density(1f)) {
            MaterialTheme {
                DanmakuContextReportContent(
                    canBlockUser=true,
                    onReportWithExplanation=if(details) { reason,explanation,block ->
                        session.reportDanmaku(42,reason,explanation) { if(block && environment.isOwned()) blocks++ }
                    } else null,
                    onReport={legacy += it},onDismiss={dismissals++},onBack={}
                )
            }
        }
        fun frame() { repeat(4) { Snapshot.sendApplyNotifications();scene.render().close() } }
        fun all()=scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun label(s:String)=all().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text==s }==true }
        fun clickLabel(s:String) {
            var node:SemanticsNode?=label(s)
            while(node!=null && !node.config.contains(SemanticsActions.OnClick))node=node.parent
            assertNotNull(node).config[SemanticsActions.OnClick].action!!.invoke();frame()
        }
        fun edit(text:String) {all().single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text));frame()}
        fun check() {all().single {it.config.contains(SemanticsProperties.ToggleableState)}.config[SemanticsActions.OnClick].action!!.invoke();frame()}
        override fun close(){session.close();scope.cancel();scene.close()}
    }
    @Test fun realFormTrimsOnlyEdgesAndForwardsEveryCodeUnitOnce()=runBlocking {
        for(text in listOf("  first\nsecond 😀\n第三行  ","x".repeat(10000)+"🧪", "", "  ")) Harness().use { h ->
            h.frame();h.clickLabel("其它");h.edit(text)
            if(text.isBlank()) {
                var n:SemanticsNode?=h.label("提交举报")
                while(n!=null && !n.config.contains(SemanticsActions.OnClick)) n=n.parent
                assertTrue(assertNotNull(n).config.contains(SemanticsProperties.Disabled))
                assertTrue(h.port.calls.isEmpty());assertEquals(0,h.dismissals)
            } else {
                h.clickLabel("提交举报")
                assertEquals(listOf(listOf<Any>(70L,42L,11,text.trim())),h.port.calls)
                assertEquals(1,h.dismissals);assertEquals(0,h.blocks)
                h.port.result.complete(Result.success(Unit));yield();assertEquals(0,h.blocks)
            }
        }
    }
    @Test fun completionBlocksOnceForBothResultsButNeverForCancellationOrRetiredOwner()=runBlocking {
        for(outcome in listOf("success","failure","cancel","retire")) for(block in listOf(false,true)) Harness().use {h->
            h.frame();h.clickLabel("违法违禁");if(block)h.check();h.clickLabel("提交举报")
            assertEquals(listOf(listOf<Any>(70L,42L,1,"")),h.port.calls)
            assertEquals(1,h.dismissals);assertEquals(0,h.blocks)
            if(outcome=="cancel")h.session.close()
            if(outcome=="retire")h.owned=false
            h.port.result.complete(if(outcome=="failure")Result.failure(IllegalStateException("rejected"))else Result.success(Unit));yield()
            assertEquals(if(block && outcome in listOf("success","failure"))1 else 0,h.blocks)
            assertEquals(1,h.port.calls.size);assertEquals(1,h.dismissals)
            if(outcome=="failure")assertEquals(listOf("rejected"),h.feedback)
        }
    }
    @Test fun legacyEntryKeepsOriginalReasonsAndCannotCollectDiscardedExplanation() {
        Harness(details=false).use {h->
            h.frame();assertTrue(h.all().none { it.config.contains(SemanticsActions.SetText) })
            assertTrue(h.all().none { it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text=="提交举报"}==true })
            h.clickLabel("其他");assertEquals(listOf(8),h.legacy);assertEquals(1,h.dismissals)
            assertTrue(h.port.calls.isEmpty())
        }
    }
}
