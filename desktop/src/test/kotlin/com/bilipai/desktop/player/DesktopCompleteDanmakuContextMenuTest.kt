package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.ui.components.DanmakuContextMenu
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopCompleteDanmakuContextMenuTest {
    @Test fun completeMenuKeepsDetailedAndLegacyReportsAndRealClipboardCallback() {
        for (mode in listOf("details", "legacy", "copy")) {
            val scheduler=TestCoroutineScheduler()
            val errors=mutableListOf<Throwable>()
            val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _, e -> errors+=e })
            val visible=mutableStateOf(true)
            val details=mutableListOf<List<Any>>()
            val legacy=mutableListOf<Int>()
            val copies=mutableListOf<Pair<String,String>>()
            var dismissals=0
            val platform=object:DesktopDanmakuPlatform {
                override fun isOwned()=true
                override fun showFeedback(message:String)=error("Unexpected feedback")
                override fun copyText(text:String,label:String){copies+=text to label}
                @Composable override fun textSelection(text:String,title:String?,onDismiss:()->Unit)=error("Unexpected selection")
            }
            val scene=ImageComposeScene(800,1000,Density(1f),coroutineContext=scope.coroutineContext) {
                MaterialTheme { CompositionLocalProvider(LocalDesktopDanmakuBindings provides platform) {
                    if(visible.value) DanmakuContextMenu(text="实际弹幕",onDismiss={dismissals++;visible.value=false},
                        onLike={error("Unexpected like")},onRecall={error("Unexpected recall")},onReport={legacy+=it},
                        onReportWithExplanation=if(mode=="details") {r,e,b->details+=listOf(r,e,b)} else null)
                } }
            }
            fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
            fun all()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
            fun label(s:String)=all().firstOrNull {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text==s}==true}
            fun frame(){repeat(15){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(20)};scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString())}
            fun click(s:String){var n:SemanticsNode?=assertNotNull(label(s),s);while(n!=null&&!n.config.contains(SemanticsActions.OnClick))n=n.parent;assertNotNull(n).config[SemanticsActions.OnClick].action!!.invoke();frame()}
            try {
                frame();assertEquals(0,dismissals);assertTrue(details.isEmpty()&&legacy.isEmpty()&&copies.isEmpty())
                if(mode=="copy") {click("复制全部");assertEquals(listOf("实际弹幕" to "弹幕"),copies)}
                else {
                    click("举报弹幕")
                    if(mode=="details") {
                        click("其它")
                        all().single {it.config.contains(SemanticsActions.SetText)}.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("  多行\n🙂  "));frame()
                        click("提交举报");assertEquals(listOf(listOf<Any>(11,"多行\n🙂",false)),details);assertTrue(legacy.isEmpty())
                    } else {assertNull(label("提交举报"));click("其他");assertEquals(listOf(8),legacy);assertTrue(details.isEmpty())}
                }
                assertEquals(1,dismissals);assertFalse(visible.value);assertNull(label("弹幕内容"));frame();assertEquals(1,dismissals)
            } finally {scene.close();scope.cancel();scheduler.runCurrent()}
        }
    }
}
