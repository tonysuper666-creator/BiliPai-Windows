@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("DEPRECATION")
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.repository.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.*
import java.security.Permission
import java.util.concurrent.atomic.AtomicInteger

private val checks=mutableListOf<String>()
private fun verify(label:String,value:Boolean){check(value){label};checks+=label;println("PASS: $label")}
private class SettingsFence:SecurityManager(){
    val attempts=AtomicInteger()
    override fun checkPermission(p:Permission){}
    override fun checkConnect(host:String,port:Int){attempts.incrementAndGet();error("Network outside settings UI proof")}
    override fun checkListen(port:Int){attempts.incrementAndGet();error("Listen outside settings UI proof")}
    override fun checkExec(command:String){attempts.incrementAndGet();error("Process outside settings UI proof")}
}
private class SettingsScene(val scene:ImageComposeScene,val width:Int,val height:Int){
    var nanos=0L;var pointers=0;var textEdits=0;var scrollEvents=0
    val events=mutableListOf<JsonObject>()
    fun nodes():List<SemanticsNode>{
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
    }
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    fun text(label:String)=nodes().lastOrNull{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}
    fun shown(n:SemanticsNode)=n.boundsInRoot.width>3f&&n.boundsInRoot.height>3f&&n.boundsInRoot.center.x in 1f..(width-1f)&&n.boundsInRoot.center.y in 1f..(height-1f)
    suspend fun frame(){nanos+=32_000_000;scene.render(nanos).close();delay(5)}
    suspend fun wait(label:String,ready:()->Boolean){try{withTimeout(6500){while(!ready())frame()};repeat(8){frame()}}catch(e:TimeoutCancellationException){error("$label; labels=${labels()}")}}
    suspend fun pointer(point:Offset,label:String){
        check(point.x in 1f..(width-1f)&&point.y in 1f..(height-1f)){"Out of actual viewport $point for $label"}
        events+=buildJsonObject{put("kind","pointer-pair");put("target",label);put("x",point.x);put("y",point.y)}
        scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+45,buttons=PointerButtons())
        pointers++;repeat(12){frame()}
    }
    suspend fun scroll(delta:Float){
        val owner=nodes().lastOrNull{it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null&&shown(it)}?:error("No original vertical-scroll semantics")
        val point=owner.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Scroll,point,scrollDelta=Offset(0f,delta),timeMillis=nanos/1_000_000)
        events+=buildJsonObject{put("kind","scroll");put("delta",delta);put("x",point.x);put("y",point.y)};scrollEvents++;repeat(14){frame()}
    }
    suspend fun reveal(label:String):SemanticsNode{
        text(label)?.takeIf(::shown)?.let{return it}
        repeat(35){scroll(2f);text(label)?.takeIf(::shown)?.let{return it}}
        error("Cannot reveal actual original label $label; labels=${labels()}")
    }
    suspend fun click(label:String){val n=reveal(label);pointer(n.boundsInRoot.center,label)}
    suspend fun top(){repeat(10){scroll(-10f)}}
    suspend fun switch(label:String){
        val n=reveal(label)
        val candidates=nodes().filter{it.config.getOrNull(SemanticsProperties.ToggleableState)!=null&&shown(it)}
        val target=candidates.minByOrNull{kotlin.math.abs(it.boundsInRoot.center.y-n.boundsInRoot.center.y)}?:error("No original switch for $label")
        check(kotlin.math.abs(target.boundsInRoot.center.y-n.boundsInRoot.center.y)<50f)
        pointer(target.boundsInRoot.center,"switch:$label")
    }
    suspend fun slider(index:Int,fraction:Float,label:String){
        val sliders=nodes().filter{it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)!=null&&it.config.getOrNull(SemanticsActions.SetProgress)?.action!=null&&shown(it)}
        val n=sliders[index];val rect=n.boundsInRoot
        pointer(Offset(rect.left+rect.width*fraction,rect.center.y),"slider:$label")
    }
    suspend fun edit(value:String){
        val n=nodes().last{it.config.getOrNull(SemanticsActions.SetText)?.action!=null&&shown(it)}
        pointer(n.boundsInRoot.center,"actual manager field")
        check(checkNotNull(n.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(value)))
        textEdits++;repeat(12){frame()};verify("actual field contains $value",nodes().any{it.config.getOrNull(SemanticsProperties.EditableText)?.text==value})
        events+=buildJsonObject{put("kind","actual-editable-text");put("value",value)}
    }
    fun dump()=buildJsonObject{put("labels",JsonArray(labels().map(::JsonPrimitive)));put("nodes",JsonArray(nodes().map{n->buildJsonObject{put("id",n.id);put("bounds",n.boundsInRoot.toString());put("config",n.config.toString())}}));put("events",JsonArray(events))}
}
private class SettingsPlatformFixture(val loggedIn:Boolean):DesktopDanmakuSettingsPlatform{
    var owned=true;var pickCancelled=true;var picks=0;var opens=0;var gets=0;var adds=0;var syncs=0
    val feedback=mutableListOf<String>()
    override val cloud=object:DesktopDanmakuCloudRuleActions{
        override suspend fun getDanmakuCloudFilterRules():Result<DanmakuCloudFilterRules>{gets++;return if(loggedIn)Result.success(DanmakuCloudFilterRules(emptyList(),null)) else Result.failure(Exception("请先登录"))}
        override suspend fun addDanmakuCloudFilterRule(type:Int,filter:String):Result<DanmakuCloudFilterRule>{check(loggedIn);adds++;return Result.success(DanmakuCloudFilterRule(adds.toLong(),type,filter))}
        override suspend fun deleteDanmakuCloudFilterRule(id:Long):Result<Unit> = error("Cloud delete is not exercised: no remote baseline")
        override suspend fun syncDanmakuCloudConfig(settings:DanmakuCloudSyncSettings):Result<Unit>{check(loggedIn);syncs++;return Result.success(Unit)}
    }
    override fun isOwned()=owned
    override fun showFeedback(message:String){feedback+=message}
    override fun pickRuleFile(mimeTypes:Array<String>,onSelected:(String?)->Unit){picks++;check(mimeTypes.toList()==listOf("text/plain","application/json","text/xml","application/xml"));onSelected(if(pickCancelled)null else "file:///memory-only/settings-rule-fixture.json")}
    override suspend fun openRuleInput(fileUri:String):InputStream?{currentCoroutineContext().ensureActive();check(owned&&fileUri=="file:///memory-only/settings-rule-fixture.json");opens++;return ByteArrayInputStream("""{"keywords":["导入关键词"],"regex":["导入.*"],"userHashes":["abcdef"]}""".toByteArray())}
    override fun elapsedRealtimeMillis()=System.nanoTime()/1_000_000
    override fun onCloudSyncFailure(message:String?)=error("No cloud-config failure expected: $message")
}
fun main(args:Array<String>):Unit=runBlocking{
    val style=AppUiStyle.valueOf(args[0]);val out=Path.of(args[1]);Files.createDirectories(out)
    val width=1100;val height=900;val loggedIn=style==AppUiStyle.MIUIX
    val store=DesktopPluginStore(out.resolve("actual-global-store"));val gate=Any();val platform=SettingsPlatformFixture(loggedIn)
    val admission:((()->Unit)->Boolean)={block->synchronized(gate){if(!platform.owned)false else{block();true}}}
    val blocks=DesktopDanmakuBlockPreferences(store,admission)
    val prefs=DesktopOriginalDanmakuPreferences(store,blocks,admission)
    prefs.importLegacyWindowsDanmakuIfAbsent(com.bilipai.desktop.danmaku.DanmakuSettings())
    prefs.setDanmakuCloudSyncEnabled(true)
    val initialCloudEnabled=prefs.getDanmakuCloudSyncEnabled().first()
    var presentation by mutableStateOf(DesktopDanmakuPresentation.INLINE)
    var mounted by mutableStateOf(true);var dismisses=0
    val fence=SettingsFence();System.setSecurityManager(fence)
    val scene=ImageComposeScene(width=width,height=height,coroutineContext=coroutineContext);val ui=SettingsScene(scene,width,height)
    try{
        scene.setContent{
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false),systemLanguageTags=listOf("zh-CN")){
                DesktopDetailWindow{
                    val originalScope=presentation.originalScope()
                    val current by remember(originalScope){prefs.getDanmakuSettings(originalScope)}.collectAsState(prefs.currentSettings(originalScope))
                    val cloudEnabled by remember{prefs.getDanmakuCloudSyncEnabled()}.collectAsState(initialCloudEnabled)
                    val cloud=rememberDesktopOriginalDanmakuCloudSyncBinding(current,cloudEnabled,loggedIn,platform)
                    if(mounted) DesktopOriginalDanmakuSettingsHost(prefs,presentation,DesktopDanmakuSettingsViewport(width,height),platform,cloudEnabled,loggedIn,cloud,
                        onShowDanmakuPool={error("Pool action is outside settings UI proof")},onDismiss={dismisses++;mounted=false})
                }
            }
        }
        ui.wait("actual full Host mounts original panel"){"弹幕设置" in ui.labels()&&"字体大小" in ui.labels()}
        verify("guest/login fixture controls actual cloud section visibility",("同步弹幕设置到账号" in ui.labels())==loggedIn)
        val initialFont=prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).fontScale
        ui.reveal("字体大小");ui.slider(0,.78f,"font")
        ui.wait("actual slider release persists through same global store"){prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).fontScale!=initialFont}
        verify("original shared font key affects both presentation scopes",prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).fontScale==prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).fontScale)
        ui.reveal("透明度");val beforePortrait=prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).opacity
        val opacityLabel=ui.text("透明度")!!
        val visibleSliders=ui.nodes().filter{it.config.getOrNull(SemanticsActions.SetProgress)?.action!=null&&ui.shown(it)}
        val opacitySlider=visibleSliders.minBy{kotlin.math.abs(it.boundsInRoot.center.y-(opacityLabel.boundsInRoot.center.y+42f))}
        ui.pointer(Offset(opacitySlider.boundsInRoot.left+opacitySlider.boundsInRoot.width*.28f,opacitySlider.boundsInRoot.center.y),"slider:portrait opacity")
        ui.wait("portrait opacity actual pointer commits"){prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).opacity!=beforePortrait}
        val portraitOpacity=prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).opacity
        ui.switch("滚动弹幕")
        ui.wait("actual scroll switch persisted"){!prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).allowScroll}
        verify("portrait switch does not overwrite landscape scoped key",prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).allowScroll)
        // Change only the required presentation input, never fake a native HWND or monitor.
        presentation=DesktopDanmakuPresentation.FULLSCREEN_LANDSCAPE
        ui.wait("actual fullscreen original tabs mount"){listOf("基础","高级","屏蔽").all{it in ui.labels()}}
        verify("same preferences owner retains independent scoped opacity",prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).opacity!=portraitOpacity)
        ui.click("高级");ui.wait("original Advanced tab changes section"){"固定滚动速度" in ui.labels()&&"字体大小" !in ui.labels()}
        ui.switch("固定滚动速度");ui.wait("original advanced switch persisted"){prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).scrollFixedVelocity}
        verify("original advanced switch remains scoped",!prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).scrollFixedVelocity)
        ui.top();ui.click("屏蔽");ui.wait("original Blocking tab changes section"){"屏蔽管理" in ui.labels()&&"固定滚动速度" !in ui.labels()}
        ui.click("屏蔽管理");ui.wait("complete original manager dialog mounts"){"分类维护关键词、正则和 UID(hash) 规则" in ui.labels()}
        ui.edit("原关键词");ui.click("添加");ui.wait("actual manager add updates draft"){"原关键词" in ui.labels()}
        ui.click("保存");ui.wait("actual manager save updates sole scoped block store"){prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).blockRules.contains("原关键词")}
        verify("landscape rule save does not create a second list or overwrite portrait",prefs.currentSettings(DanmakuSettingsScope.PORTRAIT).blockRules.isEmpty())
        ui.click("屏蔽管理");ui.wait("manager reopens stored real rule"){"原关键词" in ui.labels()}
        ui.click("删除");ui.edit("取消编辑词");ui.click("添加");ui.click("取消")
        ui.wait("cancel closes only manager"){"分类维护关键词、正则和 UID(hash) 规则" !in ui.labels()}
        verify("original manager cancel discards local edited draft",prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).blockRules==listOf("原关键词"))
        ui.click("屏蔽管理");ui.wait("manager second reopen"){"原关键词" in ui.labels()}
        ui.click("删除");ui.edit("修改关键词");ui.click("添加");ui.click("保存")
        ui.wait("delete/add original edit persisted"){prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).blockRules==listOf("修改关键词")}
        ui.click("屏蔽管理");ui.wait("manager import controls"){"导入文件" in ui.labels()}
        ui.click("导入文件");verify("memory chooser cancellation opens no stream and keeps manager",platform.picks==1&&platform.opens==0&&"导入屏蔽规则" !in ui.labels())
        platform.pickCancelled=false;ui.click("导入文件");ui.wait("original importer shows parsed confirmation"){"导入屏蔽规则" in ui.labels()&&"合并导入" in ui.labels()}
        verify("required suspend file input consumed exactly one declared memory stream",platform.opens==1)
        ui.click("合并导入");ui.wait("import confirm returns to actual manager"){"导入屏蔽规则" !in ui.labels()}
        ui.click("正则 1");ui.wait("original regex tab displays imported regex"){"regex:导入.*" in ui.labels()||"导入.*" in ui.labels()}
        ui.click("UID(hash) 1");ui.wait("original UID tab displays imported hash"){ui.labels().any{it.contains("abcdef")}}
        ui.click("保存");ui.wait("original import save updates same global preference snapshot"){prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).blockRules.size==4}
        verify("full import partitions keyword/regex/hash without losing existing rule",prefs.currentSettings(DanmakuSettingsScope.LANDSCAPE).blockRules.let{it.contains("修改关键词")&&it.contains("导入关键词")&&it.any{r->r.contains("导入.*")}&&it.any{r->r.contains("abcdef")}})
        ui.top();ui.click("基础");ui.wait("original basic tab restored"){"字体 " in ui.labels().joinToString()}
        val close=ui.nodes().last{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭")==true&&ui.shown(it)}
        ui.pointer(close.boundsInRoot.center,"original panel close")
        ui.wait("actual panel close callback removes full Host"){!mounted}
        verify("one actual close callback",dismisses==1)
        verify("no external network/process/native chooser/account access",fence.attempts.get()==0)
        val classes=listOf("com.bilipai.desktop.ui.DesktopOriginalDanmakuSettingsHostKt","com.android.purebilibili.feature.video.ui.components.DanmakuSettingsPanelKt","com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences","com.bilipai.desktop.settings.DesktopDanmakuBlockPreferences","com.bilipai.desktop.plugins.DesktopPluginStore","com.bilipai.desktop.ui.DesktopOriginalDanmakuCloudSyncBindingKt","com.bilipai.desktop.ui.DesktopDanmakuSettingsPlatformKt","com.bilipai.desktop.appearance.DesktopAppearanceThemeKt","com.android.purebilibili.core.ui.components.AppLiquidAwareTabRowKt").map{name->val c=Class.forName(name);buildJsonObject{put("class",name);put("codeSource",c.protectionDomain.codeSource.location.toString());put("classSha256Bytes",java.security.MessageDigest.getInstance("SHA-256").digest(c.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readBytes()}).joinToString(""){"%02x".format(it)})}}
        Files.writeString(out.resolve("result.json"),buildJsonObject{put("status","PASS");put("style",style.name);put("viewportWidth",width);put("viewportHeight",height);put("fixtureLoggedIn",loggedIn);put("assertions",checks.size);put("checks",JsonArray(checks.map(::JsonPrimitive)));put("pointerPairs",ui.pointers);put("scrollEvents",ui.scrollEvents);put("actualEditableTextActions",ui.textEdits);put("memoryOnlyChooserCalls",platform.picks);put("memoryOnlyRuleInputCalls",platform.opens);put("cloudRuleGetCalls",platform.gets);put("memoryOnlyConfigSyncCalls",platform.syncs);put("productionClassOverrides",0);put("actualCodeSources",JsonArray(classes));put("RootOwnerAccountHWNDIntegrated",false);put("inputChooserMode","explicit-memory-only");put("events",JsonArray(ui.events));put("globalSettings",store.preferences("settings"))}.toString())
    }catch(e:Throwable){Files.writeString(out.resolve("failure-scene.json"),ui.dump().toString());throw e}
    finally{scene.close();System.setSecurityManager(null)}
}
