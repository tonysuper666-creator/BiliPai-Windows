@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.productfullcardproof

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.home.components.cards.*
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private class Ui(val width:Int,val height:Int,context:kotlin.coroutines.CoroutineContext) {
    private val job=SupervisorJob();val errors=mutableListOf<Throwable>()
    private val scope=CoroutineScope(context+job+CoroutineExceptionHandler{_,e->errors+=e})
    val scene=ImageComposeScene(width,height,coroutineContext=scope.coroutineContext)
    var nanos=0L;val pointers=mutableListOf<JsonObject>()
    fun nodes():List<SemanticsNode> {fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
    fun texts()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000;scene.render(nanos).close();yield();delay(5);check(errors.isEmpty()){errors.toString()}}
    suspend fun await(label:String,ready:()->Boolean){try{withTimeout(6000){while(!ready())frame()};repeat(6){frame()}}
        catch(e:TimeoutCancellationException){error("$label: ${texts()}")}}
    fun matching(label:String)=nodes().filter{n->n.config.getOrNull(SemanticsProperties.Text)?.any{it.text==label}==true||
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true}
    suspend fun click(label:String) {
        await("visible $label"){matching(label).any{it.boundsInRoot.width>0&&it.boundsInRoot.height>0&&it.boundsInRoot.top>=0&&it.boundsInRoot.bottom<=height}}
        val node=matching(label).first{it.boundsInRoot.width>0&&it.boundsInRoot.height>0&&it.boundsInRoot.top>=0&&it.boundsInRoot.bottom<=height}
        val p=node.boundsInRoot.center
        pointers+=buildJsonObject{put("label",label);put("x",p.x);put("y",p.y)}
        scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+60,buttons=PointerButtons());repeat(12){frame()}
    }
    fun cover()=nodes().first{it.config.getOrNull(SemanticsProperties.TestTag)=="home_video_cover"&&it.boundsInRoot.width>0}
    fun title(title:String)=matching("视频标题: $title").first()
    fun save(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
    fun semantics(path:Path){Files.writeString(path,nodes().joinToString("\n"){"${it.boundsInRoot} ${it.config}"})}
    suspend fun close(){scene.close();job.cancelAndJoin()}
}

private suspend fun case(output:Path,style:AppUiStyle,mode:AppThemeMode,context:kotlin.coroutines.CoroutineContext):JsonObject {
    VideoCardCoverColorStore.clear()
    val dir=output.resolve(style.name.lowercase()+"-"+mode.name.lowercase());Files.createDirectories(dir)
    val root=dir.resolve("actual-task-global");val store=DesktopPluginStore(root);val pluginContext=DesktopPluginContext(store)
    store.update("settings",mapOf("grid_column_count" to JsonPrimitive(2)))
    val prefs=DesktopHomeCardPreferences(pluginContext);val visual=DesktopHomeCardVisualPreferences(pluginContext)
    val repository=DesktopRepository(DesktopSessionStore.temporary())
    val discovery=DesktopDiscoveryRepository(repository,DesktopDiscoveryPreferences(dir.resolve("actual-guest"),DesktopBlockedUpStore(pluginContext)))
    val memory=DesktopBrowseMemory();val page=memory.feeds.page<DiscoveryPage,Int>(listOf("discovery",DiscoverySection.POPULAR,0,null,null,null,null,null))
    val image=dir.resolve("synthetic-local-cover.png");val pixels=BufferedImage(640,360,BufferedImage.TYPE_INT_RGB)
    for(x in 0 until 640)for(y in 0 until 360)pixels.setRGB(x,y,if(x<480)0xc83228 else 0x2876c8)
    ImageIO.write(pixels,"png",image.toFile())
    // Original sized-avatar request is a literal file URI with its original CDN suffix in this local fixture.
    val avatarRequest=com.android.purebilibili.core.util.FormatUtils.fixImageUrl(image.toUri().toString())
    Files.copy(image,Path.of(java.net.URI(avatarRequest)))
    val title="原始服务器长标题 这是一段用于验证原卡片完整内容和省略行为的合成标题。".repeat(5)
    val parsed=Json.decodeFromString<VideoItem>("""{"bvid":"BVFullFixture","aid":77,"cid":501,"title":${JsonPrimitive(title)},"pic":${JsonPrimitive(image.toUri().toString())},"owner":{"mid":700,"name":"原始UP fixture","face":${JsonPrimitive(image.toUri().toString())}},"stat":{"view":234567,"reply":27,"danmaku":53,"favorite":17,"like":765},"duration":245,"rights":{"pay":1},"isFollowed":true,"pubdate":1700000000}""")
    val rows=listOf(DiscoveryPage(listOf(parsed,parsed.copy(bvid="BVFullSecond",cid=502,title="第二张真实映射卡片",owner=parsed.owner.copy(mid=701))),null,requestPage=3))
    page.rows=rows;page.initialized=true;page.next=null
    val ui=Ui(1100,1050,context);var settingsOpen by mutableStateOf(false)
    val videos=mutableListOf<VideoCard>();val users=mutableListOf<Long>();var logins=0;val failures=mutableListOf<Throwable>()
    try {
        ui.scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=mode,hapticFeedbackEnabled=false)) {
                CompositionLocalProvider(LocalDesktopBrowseMemory provides memory,LocalDesktopHomeCardPreferences provides prefs,
                    LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
                    LocalAppPreferenceGroupPresentation provides if(isMiuixNonGlassEnabled())AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT) {
                    androidx.compose.material3.Surface {Column(Modifier.fillMaxSize()) {
                        AppTextButton(onClick={settingsOpen=!settingsOpen}){AppText(if(settingsOpen)"返回推荐" else "原卡片设置")}
                        if(settingsOpen)Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){DesktopHomeCardVisualSettingsSection(visual,{failures+=it})}
                        else Box(Modifier.weight(1f)){DiscoveryContentScreen(DiscoverySection.POPULAR,discovery,repository,store,
                            onVideo={videos+=it},onUser={users+=it},onLogin={logins++},onBangumiPartition={})}
                    }}
                }
            }
        }
        ui.await("actual original card"){ui.matching("视频标题: $title").isNotEmpty()&&"原始UP fixture" in ui.texts()}
        check("27" in ui.texts());check("53" !in ui.texts()){"Reply takes the original positive-secondary priority over danmaku"}
        check("已关注" in ui.texts());check("04:05" in ui.texts())
        val initialTitleHeight=ui.title(title).boundsInRoot.height;val initialCover=ui.cover().boundsInRoot
        ui.save(dir.resolve("01-original-card-defaults.png"));ui.semantics(dir.resolve("01-semantics.txt"))
        ui.click("视频标题: $title");check(videos.single().bvid==parsed.bvid&&videos.single().preferredCid==501L)
        ui.click("原始UP fixture");check(users.single()==700L)
        ui.click("更多操作");ui.await("actual menu"){"🕐 稍后再看" in ui.texts()};ui.save(dir.resolve("02-original-menu.png"))
        ui.click("🕐 稍后再看");check(logins==1);check(repository.account.value==null)
        ui.click("原卡片设置")
        ui.click("标题完整显示");ui.await("committed full title"){visual.initialSettings().showFullVideoCardContent}
        ui.click("数据贴在封面上");ui.await("committed stats"){visual.initialSettings().compactVideoStatsOnCover}
        ui.click("首页视频时长：封面外");ui.click("封面内无底色")
        ui.click("发布时间");ui.await("committed publish toggle"){!visual.initialSettings().showHomePublishTime}
        ui.click("UP主头像");ui.click("UP主标识")
        ui.save(dir.resolve("03-original-controls.png"))
        // The tint control is below the visible viewport. Scroll the real original settings host.
        val scroller=ui.nodes().firstOrNull{it.config.getOrNull(SemanticsActions.ScrollBy)!=null}
        scroller?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,650f);repeat(16){ui.frame()}
        ui.click("卡片动态取色");ui.await("actual committed tint"){visual.initialSettings().homeCardDynamicTintEnabled}
        ui.click("返回推荐")
        ui.await("actual expanded title"){ui.title(title).boundsInRoot.height>initialTitleHeight*1.5f}
        ui.await("actual Coil Skia quantizer tint") {VideoCardCoverColorStore.getCachedColor(resolveVideoCardCoverCacheKey(parsed,false))!=null}
        check(ui.cover().boundsInRoot.let{ kotlin.math.abs(it.width/it.height-initialCover.width/initialCover.height)<0.01f })
        check(page.rows===rows&&page.rows.single().requestPage==3&&page.initialized&&page.next==null)
        check(failures.isEmpty())
        ui.save(dir.resolve("04-real-expanded-tinted-card.png"));ui.semantics(dir.resolve("04-semantics.txt"))
        Files.writeString(dir.resolve("actual-disk.json"),Files.readString(root.resolve("plugin-settings.json")))
        Files.writeString(dir.resolve("pointers.json"),JsonArray(ui.pointers).toString())
        return buildJsonObject{put("style",style.name);put("themeMode",mode.name);put("passed",true);put("pointerPairs",ui.pointers.size);put("actualOriginalElegantCard",true)
            put("retainedRawPage",true);put("exactBvCid",true);put("exactUpMid",true);put("anonymousMenuOpensLoginWithoutPost",true)
            put("actualFullTitleHeightBefore",initialTitleHeight);put("actualFullTitleHeightAfter",ui.title(title).boundsInRoot.height)
            put("originalReplyPriority",true);put("actualSkiaPaletteFromCoil",true);put("settingsDisk",root.toString());put("realAccount",false);put("HWND",false)}
    }catch(error:Throwable){
        runCatching{ui.save(dir.resolve("FAILED-current-frame.png"));ui.semantics(dir.resolve("FAILED-semantics.txt"))}
        Files.writeString(dir.resolve("FAILED.txt"),error.stackTraceToString())
        throw error
    }finally{ui.close()}
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0])
    val pins=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray
    val loaded=pins.map {row->val p=row.jsonObject;val name=p["class"]!!.jsonPrimitive.content;val type=Class.forName(name)
        val origin=Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath();check(origin==Path.of(p["codeSource"]!!.jsonPrimitive.content).toRealPath())
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readAllBytes()}
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        check(hash==p["sha256Bytes"]!!.jsonPrimitive.content);row
    }
    Files.writeString(output.resolve("actual-loaded-class-identities.json"),JsonArray(loaded).toString())
    val checks=mutableListOf<JsonObject>()
    fun report(passed:Boolean,error:Throwable?=null) {
        Files.writeString(output.resolve("ui.json"),buildJsonObject {
            put("passed",passed);put("checks",JsonArray(checks));put("preparedOverlays",false);put("productionOverrides",0)
            put("wholeMainOrRuntimeAcceptance",false);put("originalCardSources",true);put("networkOrAccount",false)
            put("imageComposeScene",true);put("HWND",false)
            if(error!=null)put("failure",error.stackTraceToString())
        }.toString())
    }
    try {
        for(style in AppUiStyle.entries)for(mode in listOf(AppThemeMode.LIGHT,AppThemeMode.DARK)) {
            checks+=case(output,style,mode,coroutineContext)
            report(false) // Preserve completed cases even if a later matrix cell fails.
        }
        check(checks.size==4)
        report(true)
    }catch(error:Throwable){report(false,error);throw error}
}
