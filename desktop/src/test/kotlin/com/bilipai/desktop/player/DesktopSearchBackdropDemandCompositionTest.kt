package com.bilipai.desktop.player

import com.android.purebilibili.core.ui.blur.*
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
class DesktopSearchBackdropDemandCompositionTest {
    @Test fun demandTokenIsStableAcrossRecompositionAndReleasedAcrossBackdropAndDisposal() {
        val scheduler=TestCoroutineScheduler()
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _, t->errors+=t })
        val directory=Files.createTempDirectory("comment-search-error-")
        val context=DesktopPluginContext(DesktopPluginStore(directory))
        val demand=FloatingChromeBackdropDemand()
        var explicit by mutableStateOf(false)
        var shown by mutableStateOf(true)
        var revision by mutableStateOf(0)
        val field=FloatingChromeBackdropDemand::class.java.getDeclaredField("consumers").apply {isAccessible=true}
        fun tokens()=(field.get(demand) as List<*>).toList()
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
            MaterialTheme { CompositionLocalProvider(LocalDesktopCommentBindings provides platform,
                LocalFloatingChromeBackdropDemand provides demand) {
                val backdrop=rememberLayerBackdrop()
                if(shown) CommentSearchSheetContent(replies=emptyList(),upMid=revision.toLong(),
                    onCommentClick={error("No action")},onDismiss={error("No dismissal")},
                    miuixBackdrop=if(explicit)backdrop else null)
            } }
        }
        fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
        fun texts()=all().flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map {it.text}
        fun frames(){repeat(30){Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(20)};scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString())}
        try {
            frames();assertTrue(demand.isRequired);val first=tokens().single()
            revision++;frames();assertSame(first,tokens().single())
            explicit=true;frames();assertFalse(demand.isRequired);assertTrue(tokens().isEmpty())
            explicit=false;frames();val second=tokens().single();assertNotSame(first,second)
            revision++;frames();assertSame(second,tokens().single())
            shown=false;frames();assertFalse(demand.isRequired);assertTrue(tokens().isEmpty())
        } finally {scene.close();scope.cancel();scheduler.runCurrent();directory.toFile().deleteRecursively()}
    }
}
