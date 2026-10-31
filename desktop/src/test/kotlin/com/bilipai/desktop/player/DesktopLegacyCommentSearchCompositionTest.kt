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
class DesktopLegacyCommentSearchCompositionTest {
    @Test fun legacyDefaultsSearchLoadedRepliesWithoutLoadingOrRetry() {
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
        val scene=ImageComposeScene(800,900,Density(1f),coroutineContext=scope.coroutineContext) {
            MaterialTheme { CompositionLocalProvider(LocalDesktopCommentBindings provides platform) {
                // Exactly the legacy caller's data/default arguments; no loader or state supplied.
                CommentSearchSheetContent(replies=listOf(reply),
                    onCommentClick={assertEquals(reply,it);selected++},onDismiss={dismissed++})
            } }
        }
        fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
        fun texts()=all().flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map {it.text}
        fun frames(){repeat(30){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(20)};scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString())}
        try {
            frames();frames()
            assertFalse(texts().any {it.contains("加载中")||it.contains("重试")})
            assertFalse(all().any {it.config.contains(SemanticsProperties.ProgressBarRangeInfo)})
            val input=all().first {it.config.contains(SemanticsActions.SetText)}
            input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("needle"));frames()
            assertTrue(texts().contains("部分结果 · 1 条"))
            assertTrue(texts().contains("legacy needle"))
            assertFalse(texts().any {it.contains("加载中")||it.contains("重试")})
            var result:SemanticsNode?=all().first {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text=="legacy needle"}==true}
            while(result!=null&&!result.config.contains(SemanticsActions.OnClick))result=result.parent
            assertNotNull(result).config[SemanticsActions.OnClick].action!!.invoke();frames()
            assertEquals(1,selected);assertEquals(1,dismissed)
            frames();assertEquals(1,selected);assertEquals(1,dismissed)
        } finally {scene.close();scope.cancel();scheduler.runCurrent();directory.toFile().deleteRecursively()}
    }
}
