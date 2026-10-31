package com.android.purebilibili.feature.video.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopDanmakuDialogContentCompositionTest {
    @Test fun actualContentClearsDraftAndRejectsBusyOrHiddenSend() {
        val method = Class.forName("com.android.purebilibili.feature.video.ui.components.DanmakuSendDialogKt")
            .declaredMethods.single { it.name.startsWith("DanmakuSendDialogContent-") }.apply { isAccessible = true }
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val visible = mutableStateOf(true)
        val busy = mutableStateOf(false)
        val sent = mutableListOf<List<Any>>()
        val drafts = mutableListOf<Pair<String,Boolean>>()
        val scene = ImageComposeScene(450, 800, Density(1f), coroutineContext=scope.coroutineContext) {
            MaterialTheme {
                method.invoke(null, visible.value, ({} as () -> Unit),
                    ({ text:String, color:Int, mode:Int, size:Int, attention:Boolean -> sent += listOf(text,color,mode,size,attention) } as (String,Int,Int,Int,Boolean)->Unit),
                    busy.value, 0x1000000, 1, 25, " hello ", false, true,
                    ({ text:String, attention:Boolean -> drafts += text to attention } as (String,Boolean)->Unit),
                    ({ _:Int, _:Int, _:Int -> } as (Int,Int,Int)->Unit), 0f, Modifier, currentComposer, 0, 0, 0)
            }
        }
        fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun frame(){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.runCurrent()}
        fun settle(){repeat(8){scheduler.advanceTimeBy(20);frame()}}
        fun input()=all().first { it.config.contains(SemanticsProperties.EditableText) }
        fun action(label:String):SemanticsNode {
            var n:SemanticsNode?=all().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true || it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text==label }==true }
            while(n!=null&&!n.config.contains(SemanticsActions.OnClick))n=n.parent
            return assertNotNull(n)
        }
        try {
            settle()
            assertTrue(sent.isEmpty())
            action("清空").config[SemanticsActions.OnClick].action!!.invoke();settle()
            assertEquals("" to false,drafts.last())
            assertEquals("",input().config[SemanticsProperties.EditableText].text)
            input().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("  新弹幕🙂  "));settle()
            action("发送").config[SemanticsActions.OnClick].action!!.invoke();settle()
            assertEquals(listOf(listOf<Any>("新弹幕🙂",0xFFFFFF,1,25,false)),sent)
            busy.value=true;settle()
            assertTrue(input().config.contains(SemanticsProperties.Disabled))
            busy.value=false;visible.value=false;settle()
            assertTrue(input().config.contains(SemanticsProperties.Disabled))
            assertTrue(action("发送").config.contains(SemanticsProperties.Disabled))
            action("发送").config[SemanticsActions.OnClick].action?.invoke();settle()
            assertEquals(1,sent.size)
        } finally {scene.close();scope.cancel();scheduler.runCurrent()}
    }
}
