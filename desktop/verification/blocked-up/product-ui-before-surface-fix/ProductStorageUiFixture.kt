@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.data.repository.BlockedUpImportItem
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.*

private class ActualProductScene(val scene:ImageComposeScene,private val checks:MutableList<String>,private val label:String) {
    var nanos=0L
    fun nodes():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
    suspend fun waitFor(description:String,test:()->Boolean){try{withTimeout(4000){while(!test())frame()};repeat(8){frame()}}
        catch(failure:TimeoutCancellationException){error("$label: $description; actual labels=${labels()}")}}
    fun verify(description:String,value:Boolean){assertTrue(value,"$label: $description");checks+="$label: $description";println("PASS: $label: $description")}
    suspend fun pointer(p:Offset){scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());repeat(8){frame()}}
    fun text(label:String)=nodes().filter{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}
    suspend fun click(label:String){val actual=text(label).lastOrNull{it.boundsInRoot.center.y in 1f..638f}?:error("$this: no visible $label: ${labels()}");pointer(actual.boundsInRoot.center)}
    suspend fun scrollTo(label:String){repeat(14){
        if(text(label).any{it.boundsInRoot.center.y in 80f..610f})return
        val source=nodes().lastOrNull{it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null}
            ?:error("No actual product scroll source for $label: ${labels()}")
        verify("actual source scroll action accepted for $label",source.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f,220f))
        repeat(10){frame()}
    };error("Cannot show $label in actual finite viewport: ${labels()}")}
    fun screenshot(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}

private suspend fun startupAndStopped(style:AppUiStyle,output:Path,checks:MutableList<String>,context:kotlin.coroutines.CoroutineContext) {
    val root=Files.createTempDirectory("product-discovery-startup-")
    val corrupt=root.resolve("discovery/plugin-settings.json");Files.createDirectories(corrupt.parent);Files.writeString(corrupt,"not JSON / private fixture text")
    val store=DesktopPluginStore(root);val theme=DesktopThemePrefs(store);theme.setUiStyle(style)
    val blocked=DesktopBlockedUpStore(DesktopPluginContext(store));val repository=DesktopRepository(DesktopSessionStore.temporary())
    val closing=AtomicBoolean(false);var factories=0;var readyEffects=0;var readyDisposed=0;var restartClicks=0
    val guard=DesktopDiscoveryStorageGuard({factories++;openDesktopDiscoveryStorage(repository){DesktopDiscoveryPreferences(store.root,blocked)}},
        sessionEpoch={repository.sessionEpoch},stillOwned={!closing.get()})
    val scene=ImageComposeScene(width=720,height=640,coroutineContext=context);val ui=ActualProductScene(scene,checks,"$style/actual-boundary")
    try {
        scene.setContent {
            val epoch by repository.sessionEpochFlow.collectAsState()
            val settings by theme.settings.collectAsState(theme.initialSettings())
            DesktopDiscoveryStorageBoundary(guard,epoch,{restartClicks++},Modifier.fillMaxSize(),
                errorTheme={body->DesktopAppearanceTheme(settings){body()}}) {source->
                // Test-owned finite ready consumer. Never constructs DesktopReadyApp, MpvPlayer or PluginRuntime.
                DesktopAppearanceTheme(settings){
                    LaunchedEffect(source){readyEffects++}
                    DisposableEffect(source){onDispose{readyDisposed++}}
                    AppText("原每次推荐条数：${source.refreshCount.value}",Modifier.padding(20.dp))
                }
            }
        }
        ui.waitFor("actual constructor error has retry button"){"重试读取" in ui.labels()}
        ui.verify("product constructor fails and keeps corrupt file",factories==1&&Files.readString(corrupt)=="not JSON / private fixture text")
        ui.verify("no ready consumer before actual repair",readyEffects==0&&readyDisposed==0)
        ui.verify("product error labels hide raw bytes and path",ui.labels().none{it.contains("private fixture")||it.contains(root.toString())})
        ui.screenshot(output.resolve("${style.name.lowercase()}-startup-error.png"))
        Files.writeString(corrupt,"""{"feed_api":{"home_refresh_count":14},"future_namespace":{"keep":"原资料"}}""")
        val repaired=Files.readAllBytes(corrupt)
        ui.click("重试读取");ui.waitFor("actual pointer retries original constructor"){"原每次推荐条数：14" in ui.labels()}
        ui.verify("actual same guard remains live after ready",guard.isActive&&factories==2&&readyEffects==1&&readyDisposed==0)
        ui.verify("ready uses same original global blocked-UP store",guard.result.value?.getOrNull()?.blockedUps===blocked)
        ui.verify("repair and unrelated namespace remain untouched",repaired.contentEquals(Files.readAllBytes(corrupt)))
        ui.screenshot(output.resolve("${style.name.lowercase()}-startup-ready.png"))
        closing.set(true);guard.close()
        ui.waitFor("actual product stopped branch visible"){"本地设置读取已停止，请重新启动应用。" in ui.labels()}
        ui.verify("close disposes actual ready consumer",!guard.isActive&&readyDisposed==1&&!guard.canRetry)
        ui.verify("stopped branch contains usable restart action","重新启动应用" in ui.labels()&&"重试读取" !in ui.labels())
        ui.click("重新启动应用")
        ui.verify("actual stopped restart pointer invokes supplied callback",restartClicks==1&&factories==2)
        ui.verify("stopped retry cannot reopen product store",!guard.load())
        ui.screenshot(output.resolve("${style.name.lowercase()}-stopped-restart.png"))
    }finally{scene.close();guard.close()}
}

private suspend fun settingsManagement(style:AppUiStyle,output:Path,checks:MutableList<String>,context:kotlin.coroutines.CoroutineContext) {
    val root=Files.createTempDirectory("product-blocked-settings-tree-")
    val store=DesktopPluginStore(root);val pluginContext=DesktopPluginContext(store)
    val theme=DesktopThemePrefs(store);theme.setUiStyle(style)
    val repository=DesktopRepository(DesktopSessionStore.temporary());val blocked=DesktopBlockedUpStore(pluginContext)
    blocked.import((1L..36L).map{BlockedUpImportItem(it,"Fixture-$it","")})
    val manager=DesktopBlockedUpRepository(repository,blocked)
    val discovery=openDesktopDiscoveryStorage(repository){DesktopDiscoveryPreferences(store.root,blocked)}
    val privacy=DesktopPrivacySectionBindings(pluginContext,DesktopSearchPreferences(root))
    val navigator=DesktopSettingsNavigator()
    val search=DesktopSettingsSearchController(DesktopSettingsSearchRepository(pluginContext){false})
    val historyScope=CoroutineScope(context)
    var managementCompositions=0;var logins=0;var unrelatedContent=0
    val scene=ImageComposeScene(width=720,height=640,coroutineContext=context);val ui=ActualProductScene(scene,checks,"$style/actual-settings-tree")
    try {
        scene.setContent {
            val settings by theme.settings.collectAsState(theme.initialSettings())
            DesktopAppearanceTheme(settings) {
                DesktopSettingsTree(navigator,search,historyScope,discovery,privacy,
                    onFailure={throw it},appearanceContent={unrelatedContent++},pluginsContent={unrelatedContent++},
                    playbackContent={unrelatedContent++},backupContent={_,_->unrelatedContent++},
                    blockedListContent={managementCompositions++;DesktopBlockedListScreen(manager,{logins++})},systemContent={unrelatedContent++})
            }
        }
        val privacyTitle=SettingsRootCategory.PRIVACY_PERMISSION.title
        ui.waitFor("original root list contains original privacy category"){privacyTitle in ui.labels()}
        ui.scrollTo(privacyTitle);ui.click(privacyTitle)
        ui.waitFor("actual root category pointer updates navigation"){(navigator.state.value.current as? DesktopSettingsPage.Category)?.category==SettingsRootCategory.PRIVACY_PERMISSION}
        val blockedTitle=settingsDestinationCopy(SettingsSearchTarget.BLOCKED_LIST).title
        ui.scrollTo(blockedTitle);ui.click(blockedTitle)
        ui.waitFor("actual original privacy entry reaches management"){(navigator.state.value.current as? DesktopSettingsPage.Detail)?.target==SettingsSearchTarget.BLOCKED_LIST&&"已屏蔽的 UP 主" in ui.labels()}
        ui.verify("root route invokes real original management consumer",managementCompositions>0&&unrelatedContent==0)
        ui.verify("same global store supplies original list",manager.store===blocked&&blocked.records.value.size==36)
        val scrolls=ui.nodes().filter{it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null}
        ui.verify("management has one actual bounded source scroll viewport",scrolls.size==1&&scrolls.single().boundsInRoot.height.isFinite()&&
            scrolls.single().boundsInRoot.height in 100f..640f&&scrolls.single().boundsInRoot.bottom<=641f)
        ui.screenshot(output.resolve("${style.name.lowercase()}-blocked-route.png"))
        ui.scrollTo("Fixture-1")
        val first=ui.text("Fixture-1").first{it.boundsInRoot.center.y in 80f..610f}
        val remove=ui.text("解除屏蔽").filter{it.boundsInRoot.center.y in 80f..610f}.minByOrNull{abs(it.boundsInRoot.center.y-first.boundsInRoot.center.y)}
            ?:error("No actual visible unblock action: ${ui.labels()}")
        ui.pointer(remove.boundsInRoot.center)
        ui.waitFor("actual local-first unblock persists through product handler"){1L !in blocked.mids.value}
        ui.verify("actual original row action removes expected UID only",blocked.mids.value==(2L..36L).toSet())
        ui.verify("guest path did not request login or open unrelated destinations",logins==0&&unrelatedContent==0)
        val actualDocument=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        ui.verify("actual atomic persistence retains canonical namespace",actualDocument["blocked_ups"] is JsonObject&&actualDocument["settings"] is JsonObject)
        ui.screenshot(output.resolve("${style.name.lowercase()}-blocked-unblock.png"))
        ui.click("返回")
        ui.waitFor("actual product back pointer restores privacy category"){navigator.state.value.current is DesktopSettingsPage.Category}
        ui.verify("original back route does not rewrite list",blocked.mids.value==(2L..36L).toSet())
    }finally{scene.close();SettingsSearchFocusController.clear()}
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output);val checks=mutableListOf<String>()
    for(style in AppUiStyle.entries){startupAndStopped(style,output,checks,coroutineContext);settingsManagement(style,output,checks,coroutineContext)}
    Files.writeString(output.resolve("result.json"),buildJsonObject{put("passed",true);put("styles",2);put("cases",4)
        put("checks",JsonArray(checks.map(::JsonPrimitive)));put("actualProductClassesOnly",true);put("pureTestSourcesOnly",true)
        put("HWND",false);put("MpvPlayerConstructed",false);put("PluginRuntimeConstructed",false);put("userAccountFiles",false);put("HTTP",false)
        put("fullDesktopShellClaimed",false);put("realProcessRestartClaimed",false);put("backupRestoreFixPresentClaimed",false)
        put("listScrollMethod","actual source SemanticsActions.ScrollBy; route and row buttons actual Press/Release")}.toString())
}
