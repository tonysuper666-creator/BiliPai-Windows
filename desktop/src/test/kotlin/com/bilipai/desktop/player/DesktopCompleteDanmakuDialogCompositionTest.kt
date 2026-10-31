package com.android.purebilibili.feature.video.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopCompleteDanmakuDialogCompositionTest {
    @Suppress("UNCHECKED_CAST") private fun embeddedLocal() = Class.forName("com.bilipai.desktop.ui.DesktopWindowsVideoInteractionSectionKt")
        .getDeclaredField("LocalDesktopWindowsVideoInteractionWindow").apply { isAccessible=true }.get(null) as ProvidableCompositionLocal<Boolean>

    @Test fun completeDialogCloseAndControlledSendHideDoNotRepeatDismissOnReopen() {
        val scheduler=TestCoroutineScheduler()
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _, e -> errors+=e })
        val visible=mutableStateOf(true)
        val opening=mutableStateOf(1)
        val busy=mutableStateOf(false)
        val dismissals=mutableListOf<Int>()
        val sends=mutableListOf<String>()
        val scene=ImageComposeScene(450,800,Density(1f),coroutineContext=scope.coroutineContext) {
            MaterialTheme {
                CompositionLocalProvider(embeddedLocal() provides true) {
                    key(opening.value) {
                        val id=opening.value
                        DanmakuSendDialog(visible=visible.value,
                            onDismiss={dismissals+=id;visible.value=false},
                            onSend={text,_,_,_,_->sends+=text;busy.value=true},
                            isSending=busy.value, initialText=" draft$id ")
                    }
                }
            }
        }
        fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun settle(){repeat(20){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(20)};scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString())}
        fun action(label:String):SemanticsNode {
            var n:SemanticsNode?=all().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true || it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text==label }==true }
            while(n!=null&&!n.config.contains(SemanticsActions.OnClick))n=n.parent
            return assertNotNull(n)
        }
        fun input()=all().firstOrNull { it.config.contains(SemanticsProperties.EditableText) }
        fun reopen(id:Int){opening.value=id;busy.value=false;visible.value=true;settle();assertEquals(" draft$id ",assertNotNull(input()).config[SemanticsProperties.EditableText].text)}
        try {
            settle();assertTrue(dismissals.isEmpty());assertTrue(sends.isEmpty())
            action("关闭").config[SemanticsActions.OnClick].action!!.invoke();settle()
            assertEquals(listOf(1),dismissals);assertNull(input());settle();assertEquals(listOf(1),dismissals)
            reopen(2)
            visible.value=false;settle();assertNull(input())
            // The upstream public dialog deliberately suppresses onDismiss when its parent already hid it.
            assertEquals(listOf(1),dismissals)
            reopen(3)
            action("发送").config[SemanticsActions.OnClick].action!!.invoke();settle()
            assertEquals(listOf("draft3"),sends)
            assertTrue(assertNotNull(input()).config.contains(SemanticsProperties.Disabled))
            visible.value=false;settle();assertNull(input());assertEquals(listOf(1),dismissals)
            reopen(4)
            action("关闭").config[SemanticsActions.OnClick].action!!.invoke();settle();settle()
            assertEquals(listOf(1,4),dismissals);assertEquals(listOf("draft3"),sends);assertNull(input())
        } finally {scene.close();scope.cancel();scheduler.runCurrent()}
        assertTrue(errors.isEmpty(),errors.toString())
    }
}
