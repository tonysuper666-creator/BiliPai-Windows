@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.linkedDockProof

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.store.DesktopOriginalLinkedDockSettings
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.feature.home.components.*
import com.android.purebilibili.navigation.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Path
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home

private class Life : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
}
private class Ui(val scene: ImageComposeScene) {
    var nanos = 0L
    var pointers = 0
    fun nodes(): List<SemanticsNode> {
        fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
        return scene.semanticsOwners.flatMap { visit(it.unmergedRootSemanticsNode) }
    }
    fun search(): SemanticsNode = nodes().first { "搜索" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    fun editable(): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
    suspend fun frame() { nanos += 32_000_000; scene.render(nanos).close(); delay(3) }
    suspend fun settle() { repeat(25) { frame() } }
    suspend fun await(p: () -> Boolean) { withTimeout(5000) { while (!p()) frame() }; repeat(6) { frame() } }
    suspend fun click(p: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, p, timeMillis=nanos/1_000_000, buttons=PointerButtons(isPrimaryPressed=true))
        repeat(3) { frame() }
        scene.sendPointerEvent(PointerEventType.Release, p, timeMillis=nanos/1_000_000+30, buttons=PointerButtons())
        pointers++; settle()
    }
}

fun main(args: Array<String>): Unit = runBlocking {
    val out = Path.of(args[0]).toRealPath()
    val candidate = Path.of(args[1]).toRealPath()
    val main = Path.of(args[2]).toRealPath()
    var assertions = 0
    val cases = mutableListOf<String>()
    fun prove(ok: Boolean, label: String) { check(ok) { label }; assertions++ }
    for ((name, expected) in listOf(
        "com.android.purebilibili.feature.home.components.LinkedBottomDockKt" to candidate,
        "com.android.purebilibili.navigation.DesktopOriginalBottomSearchSubmitKt" to candidate,
        "top.yukonga.miuix.kmp.icon.extended.HomeKt" to candidate,
        "com.bilipai.desktop.ui.DesktopOriginalLinkedDockOwner" to candidate,
        "com.bilipai.desktop.plugins.DesktopPluginStore" to main,
        "com.android.purebilibili.feature.home.components.FloatingBottomBarKt" to main,
    )) {
        val clazz = Class.forName(name)
        prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath() == expected, "real source $name")
    }
    prove(MiuixIcons.Light.Home.name == "Home.Light" && MiuixIcons.Home.name == "Home.Regular", "unchanged full icon Light and Regular ABI")
    val store = DesktopPluginStore(out.resolve("sole-global-fixture-store"))
    val context = DesktopPluginContext(store)
    prove(DesktopOriginalLinkedDockSettings.getBottomBarLabelMode(context).first() == 0, "original label default")
    prove(!DesktopOriginalLinkedDockSettings.getBottomBarSearchEnabled(context).first(), "original search default")
    prove(DesktopOriginalLinkedDockSettings.getLinkedDockMergeOnScrollEnabled(context).first(), "original merge default")
    prove(!DesktopOriginalLinkedDockSettings.getListScopedSearchEnabled(context).first(), "original scoped search default")
    store.update("settings", mapOf("bottom_bar_label_mode" to JsonPrimitive(2), "bottom_bar_search_enabled" to JsonPrimitive(true), "linked_dock_merge_on_scroll_enabled" to JsonPrimitive(true), "list_scoped_search_enabled" to JsonPrimitive(true)))
    prove(DesktopOriginalLinkedDockSettings.getBottomBarLabelMode(context).first() == 2 && DesktopOriginalLinkedDockSettings.getBottomBarSearchEnabled(context).first(), "same actual Store source preferences")
    cases += "actual-global-store-read"
    val history = Channel<String>(Channel.CONFLATED)
    val favorite = Channel<String>(Channel.CONFLATED)
    val later = Channel<String>(Channel.CONFLATED)
    val global = mutableListOf<String>()
    val native = mutableListOf<BilibiliNavigationTarget>()
    var alive = true
    fun submit(text: String, tab: BottomNavItem, mainHost: Boolean = true, scoped: Boolean = true) = submitDesktopOriginalBottomBarSearchKeyword(text, true, scoped, mainHost, tab, history, favorite, later, global::add, {native.add(it)}, {alive})
    submit("  h  ", BottomNavItem.HISTORY); prove(history.tryReceive().getOrNull() == "h", "same History scoped channel trim")
    submit("  f  ", BottomNavItem.FAVORITE); prove(favorite.tryReceive().getOrNull() == "f", "same Favorites scoped channel trim")
    submit("  w  ", BottomNavItem.WATCHLATER); prove(later.tryReceive().getOrNull() == "w", "same WatchLater scoped channel trim")
    submit(" global ", BottomNavItem.HISTORY, mainHost=false); prove(global.last() == "global", "child destination global search")
    submit("  general  ", BottomNavItem.FAVORITE, scoped=false); prove(global.last() == "general", "disabled scoped preference global search")
    submit("BV1xx411c7mD", BottomNavItem.HOME); prove(native.single() is BilibiliNavigationTarget.Video, "original native target parser")
    val oldCount = global.size; submit("   ", BottomNavItem.HOME); prove(global.size == oldCount, "empty original submit ignore")
    alive = false; submit("retired", BottomNavItem.HISTORY); prove(history.tryReceive().isFailure && global.size == oldCount, "retired owner refuses source route")
    alive = true; cases += "original-submit-native-and-three-channels"
    val offset = mutableFloatStateOf(10f)
    val scrolling = mutableStateOf(false)
    val phase = mutableStateOf(LinkedDockPhase.Expanded)
    val dockOwner = DesktopOriginalLinkedDockOwner(offset, scrolling, phase, { phase.value=it }, {alive})
    prove(dockOwner.nestedScrollConnection.onPreScroll(Offset(0f,-3f),NestedScrollSource.UserInput) == Offset.Zero && offset.floatValue == 13f, "actual nested scroll source no consumed delta")
    dockOwner.nestedScrollConnection.onPreScroll(Offset(0f,4f),NestedScrollSource.UserInput); prove(offset.floatValue == 9f, "continuous reverse offset")
    dockOwner.nestedScrollConnection.onPreScroll(Offset(0f,-0.2f),NestedScrollSource.UserInput); prove(offset.floatValue == 9f, "source tiny delta threshold")
    alive=false; dockOwner.nestedScrollConnection.onPreScroll(Offset(0f,-10f),NestedScrollSource.UserInput); prove(offset.floatValue == 9f, "retired page offset untouched")
    alive=true; cases += "owned-original-nested-scroll"
    prove(resolveLinkedDockInitialPhase(BottomNavItem.HOME,true,false) == LinkedDockPhase.Expanded, "original Home initial phase")
    prove(resolveLinkedDockInitialPhase(BottomNavItem.DYNAMIC,true,true) == LinkedDockPhase.Playback, "original active audio resting phase")
    prove(resolveLinkedDockInitialPhase(BottomNavItem.HOME,false,false,LinkedDockPhase.Playback) == LinkedDockPhase.Compact, "source saved playback audio removed")
    prove(resolveLinkedDockPhaseOnSearchDismiss(true,LinkedDockPhase.Compact) == LinkedDockPhase.Playback, "source dismiss audio correction")
    prove(resolveLinkedDockPhaseOnSearchDismiss(false,LinkedDockPhase.Expanded) == LinkedDockPhase.Expanded, "source previous phase restored")
    prove(accumulateDockScroll(12f,-1f) == -1f && accumulateDockScroll(12f,3f) == 15f, "source direction accumulation")
    prove(!shouldResetLinkedDockSearchQuery(LinkedDockPhase.Search) && shouldResetLinkedDockSearchQuery(LinkedDockPhase.Compact), "source query clear on leave search")
    prove(!shouldRequestBottomBarSearchIme(false) && shouldRequestBottomBarSearchIme(true), "source IME only user request")
    prove(shouldEnableLinkedDockBackHandler(LinkedDockPhase.Search,true) && !shouldEnableLinkedDockBackHandler(LinkedDockPhase.Search,false), "source Back only top-level search")
    cases += "original-phase-ime-policies"
    var pointerPairs=0
    for(style in listOf(AppUiStyle.MATERIAL3,AppUiStyle.MIUIX)) {
        val scene=ImageComposeScene(width=800,height=220,coroutineContext=coroutineContext)
        val ui=Ui(scene)
        val navOwner=DesktopCommentDialogNavigation()
        val life=Life()
        val sceneOffset=mutableFloatStateOf(0f)
        val sceneScrolling=mutableStateOf(false)
        val scenePhase=mutableStateOf(LinkedDockPhase.Expanded)
        val actualOwner=DesktopOriginalLinkedDockOwner(sceneOffset,sceneScrolling,scenePhase,{scenePhase.value=it},{alive})
        var sceneGlass by mutableStateOf(false)
        var sceneTop by mutableStateOf(true)
        var sceneAudio by mutableStateOf(false)
        var audioSlotCalls=0
        var lastMerge=0f
        var lastIcon=0f
        var lastSurface=0f
        var emptySearches=0
        val searches=mutableListOf<String>()
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                    val background=rememberGraphicsLayer()
                    val backdrop=rememberLayerBackdrop(background)
                    CompositionLocalProvider(LocalLifecycleOwner provides life,LocalNavigationEventDispatcherOwner provides navOwner,LocalDesktopDetailForeground provides true) {
                        DesktopOriginalLinkedDockOwnerProvider(actualOwner) { pageScrollModifier ->
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().then(pageScrollModifier).layerBackdrop(backdrop).background(Color.White))
                                // Test-only slot exercises the original dock slot/progress contract;
                                // it is never a production substitute for AudioNowPlayingBar.
                                val testAudioSlot:LinkedDockNowPlayingSlot?=if(sceneAudio) {
                                    { audioModifier, merge, icon, surface, _, _ ->
                                        SideEffect { audioSlotCalls++;lastMerge=merge();lastIcon=icon();lastSurface=surface() }
                                        Box(audioModifier) { AppText("Fixture audio slot") }
                                    }
                                } else null
                                DesktopOriginalLinkedDock(preferenceContext=context,currentItem=BottomNavItem.HOME,firstItem=BottomNavItem.HOME,firstLabel="首页",collapseRequested=false,isTopLevelDestination=sceneTop,isMainHost=true,hasActiveAudioPlayback=sceneAudio,onSearchClick={emptySearches++},onOpenSearch={searches.add(it)},onOpenNativeTarget={native.add(it)},historyListScopedSearchChannel=history,favoriteListScopedSearchChannel=favorite,watchLaterListScopedSearchChannel=later,containerColor=Color.White,backdrop=backdrop,glassEnabled=sceneGlass,liquidGlassTuning=LocalLiquidGlassRenderConfig.current.tuning,iconStyle=if(style==AppUiStyle.MIUIX)SharedFloatingBottomBarIconStyle.MIUIX else SharedFloatingBottomBarIconStyle.MATERIAL,navigationItemCount=2,navigationMinEdgePadding=8.dp,nowPlayingContent=testAudioSlot,animateNowPlayingPresence=true,blurEnabled=false,hazeState=null,modifier=Modifier.align(Alignment.BottomCenter),navigationContent={
                                    // Test-only caller slot: production requires Root's complete original navigation renderer.
                                    Box(Modifier.fillMaxSize()) { AppText("Fixture navigation slot") }
                                })
                            }
                        }
                    }
                }
            }
            ui.await{ui.nodes().any{"搜索" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()}};ui.settle()
            prove(scenePhase.value==LinkedDockPhase.Expanded,"$style actual source initial expanded")
            sceneScrolling.value=true;ui.settle()
            actualOwner.nestedScrollConnection.onPreScroll(Offset(0f,-80f),NestedScrollSource.UserInput);ui.settle()
            prove(scenePhase.value==LinkedDockPhase.Compact,"$style source actual continuous scroll24dp compact")
            prove(ui.editable()?.config?.getOrNull(SemanticsProperties.Focused)!=true,"$style scroll expansion does not request focus")
            sceneScrolling.value=false;ui.settle()
            ui.click(ui.search().boundsInRoot.center)
            ui.await{scenePhase.value==LinkedDockPhase.Search&&ui.editable()!=null};ui.settle()
            prove(ui.editable()?.config?.getOrNull(SemanticsProperties.Focused)==true,"$style click requests actual text field focus")
            checkNotNull(ui.editable()?.config?.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString("  source-search  "));ui.settle()
            ui.click(ui.search().boundsInRoot.center)
            prove(searches.lastOrNull()=="source-search"&&scenePhase.value==LinkedDockPhase.Compact,"$style original submit trimmed and previous phase restored #854")
            prove(ui.editable()?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().isEmpty(),"$style submitted query clears")
            prove(ui.editable()?.config?.getOrNull(SemanticsProperties.Focused)!=true,"$style submit clears actual focus")
            sceneTop=false;sceneScrolling.value=true;ui.settle();actualOwner.nestedScrollConnection.onPreScroll(Offset(0f,100f),NestedScrollSource.UserInput);ui.settle()
            prove(scenePhase.value==LinkedDockPhase.Compact,"$style covered child preserves phase on reverse scroll")
            sceneTop=true;sceneScrolling.value=false;ui.settle();sceneGlass=true;ui.settle()
            ui.click(ui.search().boundsInRoot.center);ui.await{scenePhase.value==LinkedDockPhase.Search};ui.settle()
            navOwner.input.backCompleted();ui.settle()
            prove(scenePhase.value==LinkedDockPhase.Compact,"$style same actual Back dispatcher dismiss original glass search")
            sceneAudio=true;ui.settle()
            prove(scenePhase.value==LinkedDockPhase.Playback&&audioSlotCalls>0,"$style actual active-session reconciliation and complete original slot presence")
            prove(lastMerge in 0f..1f&&lastIcon in 0f..1f&&lastSurface==0f,"$style original stable slot progress providers")
            sceneAudio=false;ui.settle()
            prove(scenePhase.value==LinkedDockPhase.Compact,"$style actual original audio dismissal reconciliation")
            pointerPairs+=ui.pointers
            cases+="offscreen-$style-full-original-dock-search-glass"
        } finally { scene.close();navOwner.close() }
    }
    history.close();favorite.close();later.close()
    java.nio.file.Files.writeString(out.resolve("result.json"),buildJsonObject { put("assertions",assertions);put("pointerPairs",pointerPairs);put("caseCount",cases.size);put("cases",JsonArray(cases.map(::JsonPrimitive)));put("noHTTP",true);put("noHWND",true);put("productionNavigationRendererNotClaimed",true);put("productionNowPlayingRendererNotClaimed",true) }.toString())
    println("PASS $assertions assertions, ${cases.size} cases, $pointerPairs actual Compose pointer pairs")
}
