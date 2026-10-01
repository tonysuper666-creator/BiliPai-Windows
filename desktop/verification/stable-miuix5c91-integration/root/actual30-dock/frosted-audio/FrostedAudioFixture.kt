@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.frostedAudioProof

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.core.util.*
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.home.components.*
import com.android.purebilibili.feature.audio.screen.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Files
import top.yukonga.miuix.kmp.blur.*

private class Life:LifecycleOwner {
    override val lifecycle=LifecycleRegistry.createUnsafe(this).apply { currentState=Lifecycle.State.RESUMED }
}
private class Ui(val scene:ImageComposeScene) {
    var nanos=0L;var pointers=0
    fun nodes():List<SemanticsNode> {
        fun visit(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::visit)
        return scene.semanticsOwners.flatMap { visit(it.unmergedRootSemanticsNode) }
    }
    fun text(n:SemanticsNode,label:String):Boolean = n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { label in it.text } ||
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label in it } || n.children.any { text(it,label) }
    fun button(label:String):SemanticsNode = nodes().last { text(it,label) && it.config.getOrNull(SemanticsActions.OnClick)?.action!=null }
    fun navigationButton(index:Int):SemanticsNode = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick)?.action!=null }[index]
    suspend fun frame() { nanos+=32_000_000;scene.render(nanos).close();delay(3) }
    suspend fun settle() { repeat(30) { frame() } }
    suspend fun await(p:()->Boolean) { try { withTimeout(7000) { while(!p())frame() } } catch(t:TimeoutCancellationException) { println("SEMANTICS "+nodes().joinToString("\n") { it.config.toString() });throw t };repeat(6) { frame() } }
    suspend fun click(p:Offset) {
        scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true));repeat(3) { frame() }
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+30,buttons=PointerButtons());pointers++;settle()
    }
    suspend fun swipe(from:Offset,to:Offset) {
        scene.sendPointerEvent(PointerEventType.Press,from,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true));repeat(3) { frame() }
        for(i in 1..8) { scene.sendPointerEvent(PointerEventType.Move,Offset(from.x+(to.x-from.x)*i/8,from.y),timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true));frame() }
        scene.sendPointerEvent(PointerEventType.Release,to,timeMillis=nanos/1_000_000,buttons=PointerButtons());pointers++;settle()
    }
}

fun main(args:Array<String>):Unit=runBlocking {
    val out=Path.of(args[0]).toRealPath();val candidate=Path.of(args[1]).toRealPath();val main=Path.of(args[2]).toRealPath()
    var assertions=0;fun prove(ok:Boolean,label:String) { check(ok) { label };assertions++ }
    for((name,expected) in listOf(
        "com.android.purebilibili.feature.home.components.DesktopOriginalFrostedNavigationKt" to candidate,
        "com.android.purebilibili.feature.audio.screen.AudioNowPlayingBarKt" to candidate,
        "com.android.purebilibili.core.ui.transition.NowPlayingBarHandoffState" to candidate,
        "com.bilipai.desktop.ui.DesktopOriginalAudioNowPlayingBinding" to candidate,
        "com.android.purebilibili.core.util.CardPositionManager" to main,
        "com.android.purebilibili.feature.home.components.TopTabStylePolicyKt" to main,
        "com.android.purebilibili.feature.home.HomeScrollOffsetPolicyKt" to main,
        "com.android.purebilibili.feature.home.DesktopFavoriteScrollLocalsKt" to main,
        "com.bilipai.desktop.audio.ListenAudioSession" to main,
        "com.bilipai.desktop.player.MpvPlayer" to main,
    )) prove(Path.of(Class.forName(name).protectionDomain.codeSource.location.toURI()).toRealPath()==expected,"actual sole code source $name")
    val store=DesktopPluginStore(out.resolve("sole-fixture-preferences"));val context=DesktopPluginContext(store)
    val settings=DesktopOriginalFrostedSettings(context)
    var prefs=settings.preferences.first()
    prove(prefs.navigationIconCrossScaleEnabled && !prefs.androidNativeLiquidGlassEnabled && !prefs.isBottomBarSearchEnabled,"original Home defaults")
    prove(prefs.linkedDockMergeOnScrollEnabled && prefs.bottomBarSearchLayoutMode==BottomBarSearchLayoutMode.FULL_DOCK && prefs.bottomBarSearchAutoExpandMode==BottomBarSearchAutoExpandMode.EXPAND_AT_HOME_TOP,"source full dock defaults")
    store.update("settings",mapOf("bottom_bar_search_layout_mode" to JsonPrimitive(1),"bottom_bar_search_auto_expand_mode" to JsonPrimitive(2),"liquid_glass_mode" to JsonPrimitive(2),"liquid_glass_strength" to JsonPrimitive(0.5f)))
    prefs=settings.preferences.first()
    prove(prefs.bottomBarSearchLayoutMode==BottomBarSearchLayoutMode.HOME_AND_SEARCH && prefs.bottomBarSearchAutoExpandMode==BottomBarSearchAutoExpandMode.DISABLED,"original enums same Store")
    prove(kotlin.math.abs(prefs.liquidGlassProgress-0.84f)<0.0001f,"original legacy liquid mode migration")
    store.update("settings",mapOf("liquid_glass_material_progress_v2" to JsonPrimitive(0.37f),"bottom_bar_label_mode" to JsonPrimitive(0)))
    prove(settings.preferences.first().liquidGlassProgress==0.37f,"original V2 preference precedence")
    prove(canOpenAudioNowPlayingBarSource(true) && !canOpenAudioNowPlayingBarSource(false) && !canOpenAudioNowPlayingBarSource(true,false) && !canOpenAudioNowPlayingBarSource(true,true,true),"source layout IME morph opening gate")
    val normal=resolveAudioNowPlayingBarRowMetrics(400,0f,0f,1f)
    val playing=resolveAudioNowPlayingBarRowMetrics(400,1f,0f,1f)
    val searching=resolveAudioNowPlayingBarRowMetrics(400,1f,1f,1f)
    prove(normal.coverPx==40 && normal.titleWidthPx==186 && normal.extraWidthPx==48 && normal.artistHeightPx==20,"source expanded metrics")
    prove(playing.coverPx==32 && playing.titleWidthPx==294 && playing.extraWidthPx==0 && playing.artistHeightPx==0,"source playback metrics")
    prove(searching.coverPx==32 && searching.titleWidthPx==0 && searching.playWidthPx==0 && searching.contentStartPx==184,"source search metrics")
    prove(resolveNowPlayingBarReturnVisibility(NowPlayingBarHandoffState.Returning("BVfixture",true),"BVfixture")==0f && resolveNowPlayingBarReturnVisibility(NowPlayingBarHandoffState.Returning("BVother",true),"BVfixture")==1f && resolveNowPlayingBarReturnVisibility(NowPlayingBarHandoffState.Returning("BVfixture",false),"BVfixture")==1f,"only matching morph pixel owner hides bar")
    prove(shouldRotateMusicArtwork(true,false) && !shouldRotateMusicArtwork(false,false) && !shouldRotateMusicArtwork(true,true) && resolveMusicArtworkRotationDurationMs(2f)==12000,"source rotation pause and reduce motion")
    prove(resolveAudioNowPlayingVisible(true,false,false,true,true) && !resolveAudioNowPlayingVisible(true,true,false,true,true) && !resolveAudioNowPlayingVisible(true,false,true,true,true) && !resolveAudioNowPlayingVisible(true,false,false,true,true,isPlayerDestination=true),"source player and PiP visibility exclusions")
    prove(resolveHomeSideBarClickAction(BottomNavItem.HOME,500,300)==HomeSideBarClickAction.HOME_DOUBLE_TAP && resolveDynamicSideBarClickAction(BottomNavItem.DYNAMIC,500,300),"source double tap policy")
    var pointerPairs=0;val cases=mutableListOf<String>()
    for(style in listOf(AppUiStyle.MATERIAL3,AppUiStyle.MIUIX)) for(floating in listOf(false,true)) {
        println("CASE $style/$floating")
        val scene=ImageComposeScene(width=800,height=240,coroutineContext=coroutineContext);val ui=Ui(scene);val life=Life();val back=DesktopCommentDialogNavigation()
        var alive=true;val offset=mutableFloatStateOf(0f);val scrolling=mutableStateOf(false);val phase=mutableStateOf(LinkedDockPhase.Expanded)
        val owner=DesktopOriginalLinkedDockOwner(offset,scrolling,phase,{phase.value=it},{alive})
        var current by mutableStateOf(BottomNavItem.HOME);val navigations=mutableListOf<BottomNavItem>()
        val bridge=DesktopLiquidTabSettings(store)
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                    DesktopDetailWindow {
                        val layer=rememberGraphicsLayer();val backdrop=rememberLayerBackdrop(layer)
                        CompositionLocalProvider(LocalLifecycleOwner provides life,LocalNavigationEventDispatcherOwner provides back,LocalDesktopLiquidTabSettings provides bridge,LocalDesktopDetailForeground provides true) {
                            DesktopOriginalLinkedDockOwnerProvider(owner) { scrollModifier ->
                                Box(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxSize().then(scrollModifier).layerBackdrop(backdrop).background(Color.White))
                                    DesktopOriginalFrostedAudioNavigation(context,owner,
                                        DesktopOriginalFrostedNavigationState(current,listOf(BottomNavItem.HOME,BottomNavItem.DYNAMIC,BottomNavItem.HISTORY,BottomNavItem.PROFILE),emptyMap(),mapOf("HOME" to "HOME TEST","DYNAMIC" to "DYNAMIC TEST","PROFILE" to "PROFILE TEST"),3,floating,true,true,false,false,false,MotionTier.Normal,null,{false},true,0),
                                        DesktopOriginalFrostedNavigationActions({current=it;navigations.add(it)},{},{},{},{},{},{},null,null,null,null),
                                        null,DesktopOriginalNowPlayingVisibility(false,false,false,true,false,false,false,false),
                                        DesktopOriginalNowPlayingNavigation("home",true,NowPlayingBarHandoffState.Idle,false,{},{_,_->},{}),
                                        null,backdrop,null,Modifier.align(Alignment.BottomCenter))
                                }
                            }
                        }
                    }
                }
            }
            ui.await { ui.nodes().count { it.config.getOrNull(SemanticsActions.OnClick)?.action!=null }>=4 };ui.settle()
            ui.click(ui.navigationButton(1).boundsInRoot.center)
            prove(current==BottomNavItem.DYNAMIC && navigations.lastOrNull()==BottomNavItem.DYNAMIC,"$style/$floating full original navigation actual pointer callback")
            ui.click(ui.navigationButton(3).boundsInRoot.center)
            prove(current==BottomNavItem.PROFILE && navigations.lastOrNull()==BottomNavItem.PROFILE,"$style/$floating full original profile switch")
            alive=false;ui.click(ui.navigationButton(1).boundsInRoot.center)
            prove(navigations.size==2,"$style/$floating retired Root callback rejected")
            cases+="$style-$floating-whole-frosted-required-root-consumer";pointerPairs+=ui.pointers
        } finally { alive=false;scene.close();life.lifecycle.currentState=Lifecycle.State.DESTROYED }
    }
    for(style in listOf(AppUiStyle.MATERIAL3,AppUiStyle.MIUIX)) {
        val scene=ImageComposeScene(width=700,height=220,coroutineContext=coroutineContext);val ui=Ui(scene);val life=Life()
        var stable by mutableStateOf(true);var compact by mutableStateOf(false);var iconOnly by mutableFloatStateOf(0f);var mergeProgress by mutableFloatStateOf(0f)
        var handoff by mutableStateOf<NowPlayingBarHandoffState>(NowPlayingBarHandoffState.Idle)
        var expand=0;var collapse=0;var pause=0;var next=0;var previous=0;var dismiss=0;var audioAlive=true
        try {
            CardPositionManager.clear()
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                    DesktopDetailWindow {
                        CompositionLocalProvider(LocalLifecycleOwner provides life,LocalDesktopDetailForeground provides true) {
                            Box(Modifier.fillMaxSize()) {
                                AudioNowPlayingBar(AudioNowPlayingBarState("BVfixture","Fixture title","Fixture artist","","",false,1f),
                                    sourceIsOwned={audioAlive},onExpand={expand++},onCompactClick=if(compact){{collapse++}}else null,isLayoutStable=stable,
                                    onPlayPause={pause++},onSkipNext={next++},onSkipPrevious={previous++},onDismiss={dismiss++},
                                    sourceRoute="home",handoff=handoff,glassEnabled=false,liftAboveBottomBar=false,consumeNavigationBarsPadding=false,dockHosted=true,
                                    dockMergeProgress={mergeProgress},iconOnlyProgress={iconOnly},modifier=Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
            }
            ui.await { ui.nodes().any { ui.text(it,"当前视频：Fixture title") } };ui.settle()
            val bar=ui.button("当前视频：Fixture title")
            ui.click(Offset(180f,bar.boundsInRoot.center.y))
            prove(expand==1 && CardPositionManager.lastClickedVideoSourceKey=="home:BVfixture","$style real audio body click writes sole source manager")
            prove(CardPositionManager.lastClickedCardBounds?.width==700f && CardPositionManager.lastClickedCoverBounds?.width==40f,"$style measured bar/cover bounds")
            prove(CardPositionManager.lastClickedVideoSourceLayout==VideoCardSourceLayout.SIDE_BY_SIDE && CardPositionManager.lastClickedVideoSourceChromeSnapshot?.isNowPlayingBar==true,"$style original chrome and layout snapshot")
            ui.click(ui.button("播放").boundsInRoot.center);prove(pause==1,"$style real original play control")
            ui.click(ui.button("关闭听视频条").boundsInRoot.center);prove(dismiss==1,"$style real original dismiss control")
            ui.swipe(Offset(250f,bar.boundsInRoot.center.y),Offset(130f,bar.boundsInRoot.center.y));prove(next==1,"$style source skip next gesture")
            ui.swipe(Offset(130f,bar.boundsInRoot.center.y),Offset(250f,bar.boundsInRoot.center.y));prove(previous==1,"$style source skip previous gesture")
            stable=false;ui.settle();CardPositionManager.clear();ui.click(Offset(180f,bar.boundsInRoot.center.y))
            prove(expand==1 && CardPositionManager.lastClickedCardBounds==null,"$style layout unstable expansion rejected")
            compact=true;ui.settle();ui.click(Offset(180f,bar.boundsInRoot.center.y));prove(collapse==1 && expand==1,"$style compact click restores dock phase before expand")
            compact=false;stable=true;mergeProgress=1f;iconOnly=1f;ui.settle();ui.click(Offset(350f,bar.boundsInRoot.center.y))
            prove(expand==2 && CardPositionManager.lastClickedVideoSourceLayout==VideoCardSourceLayout.COVER_ONLY && CardPositionManager.lastClickedCoverBounds?.width==32f,"$style original compact source cover-only geometry")
            handoff=NowPlayingBarHandoffState.Returning("BVfixture",true);ui.settle()
            prove(ui.nodes().any { ui.text(it,"当前视频：Fixture title") && it.config.contains(SemanticsProperties.Disabled) },"$style matching return owner disables original source action")
            ui.click(Offset(350f,bar.boundsInRoot.center.y));prove(expand==2,"$style hidden return source rejects pointer expansion")
            handoff=NowPlayingBarHandoffState.Returning("BVother",true);ui.settle()
            prove(ui.nodes().any { ui.text(it,"当前视频：Fixture title") },"$style different return target keeps source bar")
            audioAlive=false;CardPositionManager.clear();ui.click(Offset(350f,bar.boundsInRoot.center.y))
            prove(expand==2 && CardPositionManager.lastClickedCardBounds==null,"$style retired audio owner cannot write global bounds")
            cases+="$style-full-audio-controls-bounds-morph-gestures";pointerPairs+=ui.pointers
        } finally { scene.close();life.lifecycle.currentState=Lifecycle.State.DESTROYED;CardPositionManager.clear() }
    }
    val result=buildJsonObject { put("status","PASS");put("assertions",assertions);put("caseCount",cases.size);put("cases",JsonArray(cases.map{JsonPrimitive(it)}));put("pointerPairs",pointerPairs);put("themes",2);put("actualOriginalRootConsumer",true);put("originalAudioDirectUiFixture",true);put("actualNativePlayerRuntimeNotExercised",true);put("noHTTP",true);put("noHWND",true);put("preparedOnly",true) }
    Files.writeString(out.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),result)+"\n");println(result)
}
