@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.proof

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
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
import java.util.concurrent.CopyOnWriteArrayList
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs

private class Ui(val width:Int,val height:Int,context:kotlin.coroutines.CoroutineContext) {
    val errors=CopyOnWriteArrayList<Throwable>();private val job=SupervisorJob()
    private val scope=CoroutineScope(context+job+CoroutineExceptionHandler{_,error->errors+=error})
    val scene=ImageComposeScene(width,height,coroutineContext=scope.coroutineContext)
    var nanos=0L;val pointers=mutableListOf<JsonObject>()
    fun nodes():List<SemanticsNode> {
        fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}
    }
    fun texts()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000;scene.render(nanos).close();yield();delay(4);check(errors.isEmpty()){errors.toString()}}
    suspend fun await(label:String,ready:()->Boolean){try{withTimeout(5000){while(!ready())frame()};repeat(12){frame()}}
        catch(error:TimeoutCancellationException){error("$label: ${texts()}")}}
    suspend fun click(text:String,first:Boolean=false) {
        fun top():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
            return scene.semanticsOwners.lastOrNull()?.let{tree(it.unmergedRootSemanticsNode)}.orEmpty()}
        await("visible $text"){top().any{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text==text}==true&&it.boundsInRoot.width>0&&it.boundsInRoot.height>0}}
        val choices=top().filter{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text==text}==true&&it.boundsInRoot.width>0&&it.boundsInRoot.height>0&&it.boundsInRoot.top>=0&&it.boundsInRoot.bottom<=height}
        val node=if(first)choices.first() else choices.last()
        val p=node.boundsInRoot.center;check(p.x in 0f..width.toFloat()&&p.y in 0f..height.toFloat()){"Clipped $text ${node.boundsInRoot}"}
        pointers+=buildJsonObject{put("text",text);put("x",p.x);put("y",p.y);put("pressMillis",nanos/1_000_000);put("releaseMillis",nanos/1_000_000+55)}
        scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());repeat(12){frame()}
    }
    fun cover(title:String)=nodes().single{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(title)==true}
    fun geometry(titles:List<String>)=JsonArray(titles.mapNotNull{title->nodes().singleOrNull{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(title)==true}?.let{n->buildJsonObject{
        val r=n.boundsInRoot;put("title",title);put("left",r.left);put("top",r.top);put("right",r.right);put("bottom",r.bottom);put("ratio",r.width/r.height)
    }}})
    fun actionBounds():JsonArray=JsonArray(nodes().filter{node->node.config.getOrNull(SemanticsProperties.Text)?.any{it.text in listOf("UP 主页","预览与合集","不感兴趣")}==true}
        .mapNotNull{node->var action=node;while(action.config.getOrNull(SemanticsActions.OnClick)==null&&action.parent!=null)action=action.parent!!
            // Semantics boundsInRoot is viewport-clipped. Measure the real layout size first;
            // a partially scrolled second row is not a shrunken button.
            val size=action.size
            if(size.width<=0||size.height<=0)return@mapNotNull null
            check(size.width>=48&&size.height>=48){"Footer action target shrunk: ${node.config} actualSize=$size"}
            val p=action.positionInRoot;val rect=androidx.compose.ui.geometry.Rect(p.x,p.y,p.x+size.width,p.y+size.height)
            if(rect.top<0||rect.bottom>height)return@mapNotNull null
            check(rect.left>=0&&rect.right<=width){"Footer action escaped viewport: $rect"}
            buildJsonObject{put("text",node.config.getOrNull(SemanticsProperties.Text)!!.joinToString{it.text});put("left",rect.left);put("top",rect.top)
                put("right",rect.right);put("bottom",rect.bottom);put("actualOnClickAncestor",true);put("minimum48Dp",true)}})
    fun save(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
    fun imagePixel(title:String,fraction:Float):Int {
        val bounds=cover(title).boundsInRoot
        return scene.render(nanos+1).use{image->
            val pixels=ImageIO.read(java.io.ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes))
            pixels.getRGB((bounds.left+bounds.width*fraction).toInt(),bounds.center.y.toInt()) and 0xffffff
        }
    }
    suspend fun close(){scene.close();job.cancelAndJoin()}
}

private fun retainedGrid(page:CommunityFeedState<*,*>):LazyStaggeredGridState {
    val field=Class.forName("com.bilipai.desktop.ui.DiscoveryScreensKt").getDeclaredField("discoveryGridScroll");field.isAccessible=true
    @Suppress("UNCHECKED_CAST") val states=field.get(null) as java.util.WeakHashMap<CommunityFeedState<*,*>,LazyStaggeredGridState>
    return checkNotNull(states[page])
}

private suspend fun case(output:Path,style:AppUiStyle,theme:AppThemeMode,width:Int,context:kotlin.coroutines.CoroutineContext):JsonObject {
    val id="${style.name.lowercase()}-${theme.name.lowercase()}-$width";val directory=output.resolve(id);Files.createDirectories(directory)
    val globalRoot=directory.resolve("actual-task-global");Files.createDirectories(globalRoot)
    val store=DesktopPluginStore(globalRoot);val prefs=DesktopHomeCardPreferences(DesktopPluginContext(store))
    val repository=DesktopRepository(DesktopSessionStore.temporary())
    val discovery=DesktopDiscoveryRepository(repository,DesktopDiscoveryPreferences(directory.resolve("actual-guest"),DesktopBlockedUpStore(DesktopPluginContext(store))))
    val memory=DesktopBrowseMemory();val key=listOf("discovery",DiscoverySection.POPULAR,0,null,null,null,null,null)
    val page=memory.feeds.page<DiscoveryPage,Int>(key)
    val titles=(1..24).map{"Fixture-$it"}
    val cover=directory.resolve("actual-local-cover.png")
    val pixels=BufferedImage(640,360,BufferedImage.TYPE_INT_RGB)
    for(x in 0 until 640)for(y in 0 until 360)pixels.setRGB(x,y,when {x<160->0xc83228;x>=480->0x2876c8;else->0x25a070})
    ImageIO.write(pixels,"png",cover.toFile())
    val data=titles.mapIndexed{index,title->VideoItem(bvid="BVFixture${index+1}",title=title,cid=501L+index,
        pic=cover.toUri().toString(),owner=Owner(mid=700L+index,name="Synthetic-UP-${index+1}"),duration=120,stat=Stat(view=1000))}
    val rows=listOf(DiscoveryPage(data,null,title="Actual retained fixture",requestPage=3))
    page.rows=rows;page.initialized=true;page.next=null
    val ui=Ui(width,1000,context);val failures=CopyOnWriteArrayList<Throwable>();val clicked=mutableListOf<VideoCard>();val clickedUsers=mutableListOf<Long>()
    val geometry=linkedMapOf<String,JsonElement>()
    try {
        ui.scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=theme,hapticFeedbackEnabled=false)) {
                CompositionLocalProvider(LocalDesktopBrowseMemory provides memory,LocalDesktopHomeCardPreferences provides prefs,
                    LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
                    LocalAppPreferenceGroupPresentation provides if(isMiuixNonGlassEnabled())AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT) {
                    androidx.compose.material3.Surface {
                        Column(Modifier.fillMaxSize()) {
                            DesktopHomeCardSettingsSection(prefs,{failures+=it},Modifier.fillMaxWidth().padding(6.dp))
                            Box(Modifier.weight(1f)) {
                                DiscoveryContentScreen(DiscoverySection.POPULAR,discovery,repository,store,
                                    onVideo={clicked+=it},onUser={clickedUsers+=it},onLogin={},onBangumiPartition={})
                            }
                        }
                    }
                }
            }
        }
        ui.await("real actual Discovery cards"){titles.take(2).all{it in ui.texts()}&&"网格列数" in ui.texts()}
        val grid=retainedGrid(page)
        val viewportWidth=width.coerceAtMost(1280)
        val beforeColumns=(viewportWidth/180).coerceAtMost(if(width<840)6 else if(width<1200)6 else if(width<1600)7 else 8)
        val initialCovers=titles.take(beforeColumns).map{ui.cover(it).boundsInRoot}
        check(initialCovers.map{it.left.toInt()}.distinct().size==beforeColumns)
        check(initialCovers.all{abs(it.top-initialCovers.first().top)<1f})
        val initialRatio=if(width==800)16f/10f else 16f/9f
        check(abs(initialCovers.first().width/initialCovers.first().height-initialRatio)<0.025f)
        check(abs(initialCovers[0].left-((width-viewportWidth)/2f+6f))<1f);check(abs(initialCovers[1].left-initialCovers[0].right-6f)<1f)
        ui.await("actual local cover decoded"){ui.imagePixel(titles[0],0.5f)==0x25a070}
        check(ui.imagePixel(titles[0],0.2f)==0xc83228)
        geometry["default"]=ui.geometry(titles);ui.save(directory.resolve("01-original-default-layout.png"))
        val originalFooterTargets=ui.actionBounds();check(originalFooterTargets.isNotEmpty())
        Files.writeString(directory.resolve("actual-footer-hit-targets.json"),originalFooterTargets.toString())
        ui.click("UP 主页",first=true);check(clickedUsers.single()==700L)
        ui.click("网格列数");ui.save(directory.resolve("02-original-column-options.png"));ui.click("2 列")
        ui.await("fixed2 real disk and grid"){prefs.initialSettings().gridColumnCount==2&&ui.cover(titles[1]).boundsInRoot.left>width/2f-5f}
        geometry["fixed2"]=ui.geometry(titles)
        val fixedWidth=ui.cover(titles[0]).boundsInRoot.width
        ui.click("推荐流卡片宽度");ui.click("宽")
        ui.await("width preset committed while fixed columns win"){prefs.initialSettings().homeFeedCardWidthPreset==HomeFeedCardWidthPreset.WIDE}
        check(abs(ui.cover(titles[0]).boundsInRoot.width-fixedWidth)<1f)
        ui.click("网格列数");ui.click("自动")
        ui.await("actual WIDE automatic geometry"){prefs.initialSettings().gridColumnCount==0&&ui.cover(titles[0]).boundsInRoot.width<fixedWidth-20f}
        val automatic=(viewportWidth/260).coerceAtLeast(2)
        check(titles.take(automatic).map{ui.cover(it).boundsInRoot.left.toInt()}.distinct().size==automatic)
        geometry["wideAuto"]=ui.geometry(titles);ui.save(directory.resolve("03-wide-auto-layout.png"))
        ui.click("卡片封面比例：16:10");ui.click("4:3")
        ui.await("actual original OFFICIAL ratio"){prefs.initialSettings().homeFeedCardStyle==HomeFeedCardStyle.OFFICIAL&&
            abs(ui.cover(titles[0]).boundsInRoot.let{it.width/it.height}-4f/3f)<0.025f}
        geometry["official"]=ui.geometry(titles);ui.save(directory.resolve("04-official-layout.png"))
        check(ui.imagePixel(titles[0],0.2f)==0x25a070){"Original Crop must actually remove source side pixels for 4:3"}
        // Existing video action carries the actual CID; neither layout transition recreates the source page.
        ui.click(titles[0]);check(clicked.single().preferredCid==501L)
        check(page.rows===rows&&page.rows.single().requestPage==3&&page.initialized&&page.next==null)
        check(retainedGrid(page)===grid)
        grid.scrollToItem(4,20);repeat(12){ui.frame()}
        val anchor=grid.firstVisibleItemIndex;check(anchor>0)
        // Real disk update through the same shared global backing, no page key mutation.
        prefs.setStyle(HomeFeedCardStyle.CURRENT);repeat(24){ui.frame()}
        check(retainedGrid(page)===grid&&page.rows===rows&&grid.firstVisibleItemIndex>0)
        geometry["scrolledCurrent"]=ui.geometry(titles);ui.save(directory.resolve("05-retained-scroll.png"))
        check(failures.isEmpty()&&ui.errors.isEmpty())
        val document=Json.parseToJsonElement(Files.readString(globalRoot.resolve("plugin-settings.json"))).jsonObject
        check(document["settings"]!!.jsonObject["grid_column_count"]!!.jsonPrimitive.int==0)
        check(document["settings"]!!.jsonObject["home_feed_card_width_preset"]!!.jsonPrimitive.int==3)
        check(document["settings"]!!.jsonObject["home_feed_card_style"]!!.jsonPrimitive.int==0)
        Files.writeString(directory.resolve("actual-disk.json"),document.toString())
        Files.writeString(directory.resolve("geometry.json"),JsonObject(geometry).toString())
        Files.writeString(directory.resolve("pointers.json"),JsonArray(ui.pointers).toString())
        return buildJsonObject{put("case",id);put("passed",true);put("actualPointerPairs",ui.pointers.size);put("defaultColumns",beforeColumns);put("autoWideColumns",automatic)
            put("actualDefaultRatio",initialRatio);put("observedGapDp",6);put("retainedActualPageAndGrid",true);put("retainedScrolledAnchor",true);put("exactCidClick",501)
            put("actualRepositoryConstructed",true);put("noPageFetchNeeded",true);put("sourcePageNumber",3);put("actualSharedGlobalDiskWrites",true)
            put("actualLocalCoverDecodeAndCropPixels",true);put("actualViewportContentCapDp",viewportWidth);put("actualFooterTargetsMinimum48Dp",true);put("exactUpMidPointerCallback",700)}
    }finally{ui.close()}
}

private suspend fun compactCase(output:Path,style:AppUiStyle,context:kotlin.coroutines.CoroutineContext):JsonObject {
    val id="${style.name.lowercase()}-compact-500";val directory=output.resolve(id);Files.createDirectories(directory)
    val root=directory.resolve("actual-task-global");Files.createDirectories(root)
    Files.writeString(root.resolve("plugin-settings.json"),"""{"settings":{"grid_column_count":6,"grid_column_count_compact":2}}""")
    val store=DesktopPluginStore(root);val preferences=DesktopHomeCardPreferences(DesktopPluginContext(store))
    val repository=DesktopRepository(DesktopSessionStore.temporary())
    val discovery=DesktopDiscoveryRepository(repository,DesktopDiscoveryPreferences(directory.resolve("actual-guest"),DesktopBlockedUpStore(DesktopPluginContext(store))))
    val memory=DesktopBrowseMemory();val page=memory.feeds.page<DiscoveryPage,Int>(listOf("discovery",DiscoverySection.POPULAR,0,null,null,null,null,null))
    val rows=listOf(DiscoveryPage((1..12).map{VideoItem(bvid="BVCompact$it",title="Compact-$it",owner=Owner(mid=it.toLong(),name="Synthetic"))},null,requestPage=4))
    page.rows=rows;page.initialized=true
    val ui=Ui(500,1000,context)
    try {
        ui.scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=AppThemeMode.LIGHT,hapticFeedbackEnabled=false)) {
                CompositionLocalProvider(LocalDesktopBrowseMemory provides memory,LocalDesktopHomeCardPreferences provides preferences,
                    LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
                    LocalAppPreferenceGroupPresentation provides if(isMiuixNonGlassEnabled())AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT) {
                    androidx.compose.material3.Surface {Column(Modifier.fillMaxSize()) {
                        DesktopHomeCardSettingsSection(preferences,{throw it},Modifier.fillMaxWidth().padding(6.dp))
                        Box(Modifier.weight(1f)){DiscoveryContentScreen(DiscoverySection.POPULAR,discovery,repository,store,{},{},{},{})}
                    }}
                }
            }
        }
        ui.await("actual Compact memory applied"){"Compact-2" in ui.texts()}
        check("网格列数" !in ui.texts()&&"推荐流卡片宽度" !in ui.texts())
        val before=ui.cover("Compact-1").boundsInRoot;check(before.width>230&&before.width<250)
        val grid=retainedGrid(page)
        ui.save(directory.resolve("01-imported-compact2.png"))
        preferences.setGridColumnCount(5);repeat(18){ui.frame()}
        check(abs(ui.cover("Compact-1").boundsInRoot.width-before.width)<1f)
        preferences.setGridColumnCountCompact(3);repeat(18){ui.frame()}
        check(ui.cover("Compact-1").boundsInRoot.width<170)
        check((1..3).map{ui.cover("Compact-$it").boundsInRoot.left.toInt()}.distinct().size==3)
        ui.click("卡片封面比例：16:10");ui.click("4:3")
        ui.await("actual Compact style flow"){preferences.initialSettings().homeFeedCardStyle==HomeFeedCardStyle.OFFICIAL&&
            abs(ui.cover("Compact-1").boundsInRoot.let{it.width/it.height}-4f/3f)<0.025f}
        check(page.rows===rows&&retainedGrid(page)===grid)
        ui.save(directory.resolve("02-compact3-original-cover-choice.png"))
        Files.writeString(directory.resolve("geometry.json"),ui.geometry((1..12).map{"Compact-$it"}).toString())
        Files.writeString(directory.resolve("pointers.json"),JsonArray(ui.pointers).toString())
        Files.writeString(directory.resolve("actual-disk.json"),Files.readString(root.resolve("plugin-settings.json")))
        return buildJsonObject{put("case",id);put("passed",true);put("actualPointerPairs",ui.pointers.size);put("compactMemoryFlow",true)
            put("originalWideOnlyControlsHidden",true);put("wideWritePreservesCompactGeometry",true);put("compactSetterConsumed",true)
            put("compactSetterWasFixtureActionNotUnimplementedPinch",true);put("retainedActualPageAndGrid",true);put("sourcePageNumber",4)}
    }finally{ui.close()}
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);val checks=mutableListOf<JsonObject>()
    val pins=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray
    val actual=pins.map{row->val pin=row.jsonObject;val name=pin["class"]!!.jsonPrimitive.content;val type=Class.forName(name)
        val origin=Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath();check(origin==Path.of(pin["jar"]!!.jsonPrimitive.content).toRealPath())
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readAllBytes()}
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        check(sha==pin["sha256Bytes"]!!.jsonPrimitive.content);buildJsonObject{put("class",name);put("actualCodeSource",origin.toString());put("sha256Bytes",sha)}}
    Files.writeString(output.resolve("actual-main-class-identities.json"),JsonArray(actual).toString())
    for(style in AppUiStyle.entries)for((theme,width) in listOf(AppThemeMode.LIGHT to 800,AppThemeMode.DARK to 960,AppThemeMode.DARK to 1680)) {
        checks+=case(output,style,theme,width,coroutineContext);println("PASS actual original controls/Discovery $style $theme $width")
    }
    for(style in AppUiStyle.entries){checks+=compactCase(output,style,coroutineContext);println("PASS actual Compact memory/consumer $style 500")}
    Files.writeString(output.resolve("ui.json"),buildJsonObject{put("passed",true);put("actualFlows",checks.size);put("checks",JsonArray(checks))
        put("actualPointerPairs",checks.sumOf{it["actualPointerPairs"]!!.jsonPrimitive.int});put("productionOverrides",0);put("storeOrRendererReplacements",0)
        put("wholeMainOrRootIntegrated",false);put("HWND",false);put("realAccountOrRemoteApi",false);put("caption","Actual immutable main product Discovery, original controls/layout policy, grid/Store/theme/component consumers")}.toString())
}
