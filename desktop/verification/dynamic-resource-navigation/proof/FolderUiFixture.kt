@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import coil3.*
import coil3.decode.DataSource
import coil3.request.SuccessResult
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.navigation.navigateOriginalDynamicCollection
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import kotlin.test.*

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val loader=ImageLoader.Builder(PlatformContext.INSTANCE).components {
        add(coil3.intercept.Interceptor { chain -> SuccessResult(ColorImage(0xff317dad.toInt(),400,300),chain.request,DataSource.MEMORY) })
    }.build();SingletonImageLoader.setUnsafe(loader)
    val evidence=mutableListOf<JsonObject>()
    try {
        for(style in AppUiStyle.entries) for(mode in listOf(AppThemeMode.LIGHT,AppThemeMode.DARK)) for(width in listOf(640,960)) {
            val requests=java.util.Collections.synchronizedList(mutableListOf<Request>())
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request();requests+=request
                check(request.url.host=="api.bilibili.com" && request.url.encodedPath=="/x/v3/fav/resource/list")
                check(request.url.queryParameter("media_id")=="812" && request.url.queryParameter("ps")=="20")
                val json=if(request.url.queryParameter("pn")=="1") """{"code":0,"data":{"has_more":true,"medias":[
                    {"id":101,"type":2,"bvid":"BV1xx411c7mD","title":"原版视频资源","cover":"fixture-cover","upper":{"mid":9,"name":"公开UP"},"ugc":{"first_cid":77}},
                    {"id":333,"type":21,"season_id":33,"title":"嵌套合集资源","cover":"fixture-cover"}]}}"""
                    else """{"code":0,"data":{"has_more":false,"medias":[{"id":202,"type":2,"bvid":"BV1yy411c7mD","title":"第二页视频资源","cover":"fixture-cover"}]}}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture-no-network")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val repository=DesktopRepository(DesktopSessionStore.temporary())
            val community=DesktopCommunityRepository(repository)
            val folder=DesktopSpaceRepository(client,{}, { it }, { 1L })
            val clicked=mutableListOf<PersonalResource>();val users=mutableListOf<Long>()
            var mid=0L;var id=0L;var type="";var title=""
            navigateOriginalDynamicCollection(812,22,"动态里的原版收藏夹","https://www.bilibili.com/medialist/detail/ml812",
                onFavorite={t,i,m,n->type=t;id=i;mid=m;title=n},onWeb={_,_->error("Unexpected Web route")})
            assertEquals("favorite",type)
            val scene=ImageComposeScene(width,760,Density(1f),coroutineContext=coroutineContext)
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=mode)) {
                    Surface { CommunityCollectionScreen(mid,id,type,community,onVideo={error("Folder uses raw resource route")},
                        onUser={users+=it},onLogin={error("Public folder must not require an account")},space=folder,
                        onResource={clicked+=it},initialTitle=title) }
                }
            }
            var nanos=0L;var pointers=0
            fun nodes():List<SemanticsNode> {
                fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}
            }
            fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
            fun text(value:String)=nodes().first{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==value}==true}
            suspend fun frame(){nanos+=30_000_000;scene.render(nanos).close();delay(5)}
            suspend fun await(value:String){withTimeout(6000){while(value !in labels())frame()};repeat(6){frame()}}
            suspend fun click(value:String){
                val point=text(value).boundsInRoot.center
                assertTrue(point.x in 0f..width.toFloat() && point.y in 0f..760f)
                scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+40,buttons=PointerButtons())
                pointers++;repeat(8){frame()}
            }
            try {
                await("嵌套合集资源");assertTrue(title in labels());assertTrue("2 项" in labels())
                click("原版视频资源");val video=(clicked.last() as PersonalResource.Favorite).item
                assertEquals(101L,video.id);assertEquals(77L,video.toVideoItem().cid)
                click("公开UP");assertEquals(listOf(9L),users)
                click("嵌套合集资源");val nested=(clicked.last() as PersonalResource.Favorite).item
                assertEquals(21,nested.type);assertEquals(333L,nested.id);assertEquals(33L,nested.season_id)
                click("加载更多");await("第二页视频资源");assertTrue("3 项" in labels())
                click("第二页视频资源");assertEquals(202L,(clicked.last() as PersonalResource.Favorite).item.id)
                assertEquals(listOf("1","2"),requests.map{it.url.queryParameter("pn")});assertTrue("加载更多" !in labels())
                val name="${style.name.lowercase()}-${mode.name.lowercase()}-$width.png"
                scene.render(nanos).use{image->image.encodeToData(EncodedImageFormat.PNG)!!.use{data->Files.write(output.resolve(name),data.bytes)}}
                click("刷新");await("2 项");assertTrue("第二页视频资源" !in labels())
                assertEquals(listOf("1","2","1"),requests.map{it.url.queryParameter("pn")})
                evidence+=buildJsonObject{put("style",style.name);put("mode",mode.name);put("width",width);put("pointerPairs",pointers);put("png",name);put("requestPages",JsonArray(requests.map{JsonPrimitive(it.url.queryParameter("pn"))}))}
            } finally {scene.close();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
        }
        Files.writeString(output.resolve("ui-result.json"),buildJsonObject{put("passed",true);put("cells",JsonArray(evidence));put("realNetwork",false);put("MainShellExecuted",false);put("nativeWindow",false)}.toString())
        println("PASS: 8 theme/width cells, 48 pointer pairs, public original favorite route/read/raw resources/pagination/refresh")
    } finally {loader.shutdown()}
}
