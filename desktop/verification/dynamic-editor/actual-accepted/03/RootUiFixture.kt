@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import coil3.*
import coil3.decode.DataSource
import coil3.request.SuccessResult
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.android.purebilibili.feature.settings.AppThemeMode
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okio.Buffer
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import kotlin.test.*

private class RootEditorScene(val scene: ImageComposeScene) {
    var nanos=0L; var pointers=0
    fun nodes(): List<SemanticsNode> {
        fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
    }
    fun labels()=nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    fun text(s:String)=nodes().last { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text==s }==true }
    fun editable(s:String)=nodes().last { it.config.getOrNull(SemanticsProperties.EditableText)?.text==s }
    suspend fun frame() { nanos+=32_000_000;scene.render(nanos).close();delay(5) }
    suspend fun await(label:String,ready:()->Boolean) {
        try { withTimeout(6000) { while(!ready())frame() };repeat(10){frame()} }
        catch(f:TimeoutCancellationException){error("$label: ${labels()}")}
    }
    suspend fun press(n:SemanticsNode) {
        val point=n.boundsInRoot.center
        require(point.x in 0f..760f&&point.y in 0f..1200f) { "Target outside actual surface: $point" }
        scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+40,buttons=PointerButtons())
        pointers++;repeat(14){frame()}
    }
    suspend fun setText(n:SemanticsNode,value:String) {
        press(n)
        assertTrue(checkNotNull(n.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(value)))
        repeat(10){frame()}
    }
    fun screenshot(path:Path) { scene.render(nanos+1).use { Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes) } }
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val store=DesktopSessionStore(Files.createTempDirectory("editor-root-ui-").resolve("fake.json"),persistent=false)
    store.saveAccount(mapOf("SESSDATA" to "not-real-ui","bili_jct" to "fixture-csrf"),AccountSummary(42,"fixture",""))
    val repository=DesktopRepository(store)
    val requests=Collections.synchronizedList(mutableListOf<Pair<Request,String>>())
    val client=repository.httpClient.newBuilder().addInterceptor { chain ->
        val request=chain.request(); val body=Buffer().also { request.body?.writeTo(it) }.readUtf8()
        requests+=request to body
        val json=when(request.url.encodedPath) {
            "/x/dynamic/feed/create/dyn" -> """{"code":0,"data":{"dyn_id_str":"9001"}}"""
            "/x/dynamic/feed/edit/dyn" -> """{"code":0}"""
            "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"fixture","wbi_img":{"img_url":"https://fixture.invalid/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://fixture.invalid/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
            else -> error("Unexpected closed fixture path: ${request.url.encodedPath}")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }.build()
    fun field(name:String,value:Any)=repository.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(repository,value)
    field("client",client);field("visitorInitialized",true);field("visitorGeneration",store.generation)
    field("api",Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
        .addConverterFactory(Json{ignoreUnknownKeys=true;coerceInputValues=true}.asConverterFactory("application/json".toMediaType()))
        .build().create(com.android.purebilibili.core.network.BilibiliApi::class.java))
    val loader=ImageLoader.Builder(PlatformContext.INSTANCE).components {
        add(coil3.intercept.Interceptor { chain->SuccessResult(ColorImage(0xff20c7cc.toInt(),400,300),chain.request,DataSource.MEMORY) })
    }.build();SingletonImageLoader.setUnsafe(loader)
    val cells=mutableListOf<JsonObject>()
    try {
        for(style in AppUiStyle.entries)for(mode in listOf(AppThemeMode.LIGHT,AppThemeMode.DARK)) {
            val session=DesktopDynamicCardSession(repository)
            val root=DesktopDynamicEditorRoot(repository,session,this)
            val scene=ImageComposeScene(width=760,height=1200,coroutineContext=coroutineContext);val ui=RootEditorScene(scene)
            try {
                scene.setContent {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=mode,hapticFeedbackEnabled=false)) {
                        AppSurface(Modifier.fillMaxSize()) { DesktopDynamicEditorRootHost(root,null) }
                    }
                }
                val initial=DynamicPublishDraft("Root publish",title="Root title")
                root.actions.publish(initial)
                assertSame(initial,root.request!!.draft)
                ui.await("Root original publish mounted") { "发布动态" in ui.labels()&&"仅自己可见" in ui.labels() }
                ui.press(ui.text("仅自己可见"))
                ui.screenshot(output.resolve("${style.name.lowercase()}-${mode.name.lowercase()}-root-publish.png"))
                ui.press(ui.text("发布"))
                ui.await("actual Main request and Root success after dismiss") { root.request==null&&"发布成功" in ui.labels() }
                assertEquals(1L,session.contentRevision.value)
                val create=requests.last { it.first.url.encodedPath=="/x/dynamic/feed/create/dyn" }
                assertTrue(create.second.contains("Root publish"));assertTrue(create.second.contains("Root title"))
                ui.press(ui.text("确定"));assertNull(root.feedback)
                val draft=DynamicPublishDraft("Root edit",title="Original edit title")
                root.actions.edit(DynamicManageAction.Edit("9001",draft))
                assertSame(draft,root.request!!.draft);assertEquals("9001",root.request!!.dynamicId)
                ui.await("Root original edit mounted") { "编辑动态" in ui.labels()&&"保存" in ui.labels() }
                ui.setText(ui.editable("Original edit title"),"Changed original title")
                assertEquals("Original edit title",ui.editable("Original edit title").config.getOrNull(SemanticsProperties.EditableText)?.text)
                ui.setText(ui.editable("Original edit title"),"Changed title")
                ui.screenshot(output.resolve("${style.name.lowercase()}-${mode.name.lowercase()}-root-edit.png"))
                ui.press(ui.text("保存"))
                ui.await("actual Main WBI edit and Root refresh") { root.request==null&&"已更新动态" in ui.labels() }
                assertEquals(2L,session.contentRevision.value)
                val edit=requests.last { it.first.url.encodedPath=="/x/dynamic/feed/edit/dyn" }
                Files.writeString(output.resolve("root-edit-sent.json"),edit.second)
                assertTrue(edit.second.contains("Root edit"),"Root edit text missing: ${edit.second}");assertTrue(edit.second.contains("Changed title"),"Changed title missing: ${edit.second}")
                assertFalse(edit.first.url.queryParameter("w_rid").isNullOrBlank())
                cells+=buildJsonObject { put("style",style.name);put("mode",mode.name);put("pointers",ui.pointers)
                    put("actualRootHost",true);put("originalDraftIdentityRetained",true);put("actualMainPublishEditAndRefresh",true);put("original20CharacterTitleLimit",true)
                    put("nativeChooserOpened",false);put("actualSockets",false);put("HWND",false) }
            }finally{root.close();session.close();scene.close()}
        }
        Files.writeString(output.resolve("root-ui-result.json"),JsonArray(cells).toString())
        println("PASS: four actual Root-host native-theme editor surfaces, real pointer publish/edit, actual Main requests, same-session refresh.")
    }finally { loader.shutdown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
}