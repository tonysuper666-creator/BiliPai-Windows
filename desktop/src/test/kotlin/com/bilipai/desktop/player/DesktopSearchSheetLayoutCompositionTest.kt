package com.bilipai.desktop.player

import com.android.purebilibili.core.ui.blur.LocalFloatingChromeBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
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
class DesktopSearchSheetLayoutCompositionTest {
    @Test fun filtersAndResultsFitAndScrollAcrossGlassWidthsAndDensities() {
        for(glass in listOf(false,true)) for(density in listOf(1f,2f)) for(width in listOf(400,900)) {
        val scheduler=TestCoroutineScheduler()
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _, t->errors+=t })
        val directory=Files.createTempDirectory("comment-search-error-")
        val context=DesktopPluginContext(DesktopPluginStore(directory))
        var selected=0
        var dismissed=0
        val reply=ReplyItem(rpid=12,content=ReplyContent(message="legacy needle"))
        val platform=java.lang.reflect.Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy,m,args -> when(m.name) {
                "getContext"->context
                "isOwned"->true
                "equals"->proxy===args!![0]
                "hashCode"->System.identityHashCode(proxy)
                "toString"->"Strict comment platform"
                else->error("Unexpected platform ${m.name}")
            } } as DesktopCommentPlatform
        val scene=ImageComposeScene((width*density).toInt(),(1000*density).toInt(),Density(density),coroutineContext=scope.coroutineContext) {
            MaterialTheme { CompositionLocalProvider(LocalDesktopCommentBindings provides platform, LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=glass)) {
                // Exactly the legacy caller's data/default arguments; no loader or state supplied.
                CommentSearchSheetContent(replies=(1L..40L).map { reply.copy(rpid=it,content=ReplyContent(message="needle $it")) },
                    onCommentClick={assertEquals(reply,it);selected++},onDismiss={dismissed++})
            } }
        }
        fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
        fun texts()=all().flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map {it.text}
        fun frames(){repeat(30){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(20)};scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString())}
        try {
            frames()
            all().first {it.config.contains(SemanticsActions.SetText)}.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("needle"));frames()
            assertTrue(texts().contains("部分结果 · 40 条"))
            for(label in listOf("全部评论","只看UP主","充电评论")) {
                val n=all().first {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text==label}==true}
                assertTrue(n.boundsInRoot.left>=0f && n.boundsInRoot.right<=width*density,"$label ${n.boundsInRoot}")
            }
            val list=all().first {it.config.contains(SemanticsActions.ScrollBy)&&it.config.contains(SemanticsProperties.VerticalScrollAxisRange)}
            val before=list.config[SemanticsProperties.VerticalScrollAxisRange].value()
            list.config[SemanticsActions.ScrollBy].action!!.invoke(0f,500f*density);frames()
            assertTrue(list.config[SemanticsProperties.VerticalScrollAxisRange].value()>before)
            assertEquals(0,selected);assertEquals(0,dismissed)
        } finally {scene.close();scope.cancel();scheduler.runCurrent();directory.toFile().deleteRecursively()}
        }
    }
}
