package com.bilipai.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
class DesktopInteractionModalLifecycleTest {
    @Suppress("UNCHECKED_CAST") private fun embeddedLocal() = Class.forName("com.bilipai.desktop.ui.DesktopWindowsVideoInteractionSectionKt")
        .getDeclaredField("LocalDesktopWindowsVideoInteractionWindow").apply { isAccessible=true }.get(null) as ProvidableCompositionLocal<Boolean>
    private fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)

    @Test fun embeddedContentProgrammaticCloseReopenAndRetiredLeaseAreIsolated() {
        for (kind in listOf("完成", "发送", "programmatic")) {
            val scheduler=TestCoroutineScheduler()
            val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler))
            val opening=mutableStateOf(1)
            val mounted=mutableStateOf(true)
            val events=mutableListOf<String>()
            var programClose:()->Unit={}
            var oldClose:()->Unit={}
            var oldResult:()->Boolean={false}
            var escapeCount=0
            var backCount=0
            val focus=FocusRequester()
            val scene=ImageComposeScene(450,700,Density(1f),coroutineContext=scope.coroutineContext) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { if(it.buttons.isBackPressed) backCount++ }.onKeyEvent { if(it.key==Key.Escape && it.type==KeyEventType.KeyDown){escapeCount++;true}else false }.focusRequester(focus).focusable()) {
                        CompositionLocalProvider(embeddedLocal() provides true) {
                            if(mounted.value) key(opening.value) {
                                val id=opening.value
                                val visible=remember { mutableStateOf(true) }
                                val lease=remember { DesktopWindowsVideoInteractionLease({true},{action->action();true}) }
                                DisposableEffect(lease) { onDispose { lease.close() } }
                                val close={ visible.value=false }
                                SideEffect {
                                    programClose=close
                                    if(id==1){oldClose=close;oldResult={lease.commit { events+="old-result";visible.value=false }}}
                                }
                                DesktopWindowsVideoInteractionModalSheet(
                                    visible=visible.value,
                                    onDismissStarted={events+="$id:start"},
                                    onDismissRequest={events+="$id:finish";mounted.value=false},
                                ) {
                                    Button(onClick=close){Text("完成")}
                                    Button(onClick={lease.commit { events+="$id:send";visible.value=false }}){Text("发送")}
                                }
                            }
                        }
                    }
                }
            }
            fun frames(){repeat(6){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(16)};scheduler.runCurrent()}
            fun click(label:String){
                var n:SemanticsNode?=scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.first { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text==label }==true }
                while(n!=null&&!n.config.contains(SemanticsActions.OnClick))n=n.parent
                assertNotNull(n).config[SemanticsActions.OnClick].action!!.invoke();frames()
            }
            try {
                frames();focus.requestFocus();frames()
                assertTrue(scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown)));frames()
                assertEquals(1,escapeCount);assertTrue(events.isEmpty())
                scene.sendPointerEvent(PointerEventType.Press,Offset(400f,600f),buttons=PointerButtons(isBackPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,Offset(400f,600f),buttons=PointerButtons())
                frames();assertEquals(1,backCount);assertTrue(events.isEmpty())
                if(kind=="programmatic"){programClose();frames()} else click(kind)
                assertEquals(if(kind=="发送")listOf("1:send","1:start","1:finish")else listOf("1:start","1:finish"),events)
                frames();oldClose();frames();assertEquals(1,events.count { it=="1:finish" })
                opening.value=2;mounted.value=true;frames()
                oldClose();assertFalse(oldResult());frames()
                assertTrue(mounted.value);assertFalse(events.contains("old-result"))
                click("完成");frames()
                assertEquals(1,events.count { it=="2:start" });assertEquals(1,events.count { it=="2:finish" })
            } finally {scene.close();scope.cancel();scheduler.runCurrent()}
        }
    }

    @Test fun nonEmbeddedControlledSheetMatchesDirectUpstreamLifecycle() {
        fun run(wrapped:Boolean):Pair<List<String>,List<Rect?>> {
            val scheduler=TestCoroutineScheduler()
            val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler))
            val visible=mutableStateOf(true)
            val events=mutableListOf<String>()
            val geometry=mutableListOf<Rect?>()
            val scene=ImageComposeScene(450,700,Density(1f),coroutineContext=scope.coroutineContext) {
                MaterialTheme {
                    CompositionLocalProvider(embeddedLocal() provides false, LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=false)) {
                        val state=rememberModalBottomSheetState(skipPartiallyExpanded=true)
                        val start={events+="start";Unit}
                        val end={events+="finish";Unit}
                        val body:@Composable ColumnScope.()->Unit={Text("真实弹层内容",Modifier.height(180.dp))}
                        if(wrapped) DesktopWindowsVideoInteractionModalSheet(onDismissRequest=end, sheetState=state, visible=visible.value,onDismissStarted=start,dragHandle=null,content=body)
                        else AppModalBottomSheet(onDismissRequest=end,sheetState=state,visible=visible.value,onDismissStarted=start,dragHandle=null,content=body)
                    }
                }
            }
            fun frames(){repeat(60){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();geometry+=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}.firstOrNull {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text=="真实弹层内容"}==true}?.boundsInRoot;scheduler.advanceTimeBy(16)};scheduler.runCurrent()}
            try {
                frames();assertTrue(scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}.any {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text=="真实弹层内容"}==true})
                assertTrue(events.isEmpty());visible.value=false;frames();frames()
                assertEquals(listOf("start","finish"),events)
                return events.toList() to geometry.toList()
            } finally {scene.close();scope.cancel();scheduler.runCurrent()}
        }
        assertEquals(run(false),run(true))
    }
}
