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
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import kotlin.test.*

private class EditorScene(val scene: ImageComposeScene) {
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
private class EditorPlatform : DesktopDynamicEditorPlatform {
    var active=true
    val calls=mutableListOf<String>()
    override fun isOwned()=active
    override val emotes=object:DesktopDynamicEmotes {
        override fun snapshot()=linkedMapOf("[原表情]" to "https://fixture.invalid/emote.png")
        override fun currentSessionKey():Any=7L
        override suspend fun ensureLoaded()=snapshot()
    }
    override fun pickImages(maxItems:Int,onSelected:(List<String>)->Unit) {
        calls+="pick:$maxItems";onSelected((1..10).map { "file:///synthetic/image$it.png" })
    }
    override fun chooseDateAndTime(initialMillis:Long,onSelected:(Int,Int,Int,Int,Int)->Unit) {
        calls+="date";onSelected(2030,2,4,5,6)
    }
    override suspend fun searchMentionUsers(query:String):Result<List<MentionSearchUser>> {
        calls+="mention:$query"
        return Result.success(listOf(Json.decodeFromString("""{"uid":77,"name":"Original mention","fans":42,"face":"https://fixture.invalid/avatar.png"}""")))
    }
    override suspend fun searchPublishTopics(query:String):Result<List<DynamicTopicSearchItem>> {
        calls+="topic:$query"
        return Result.success(listOf(Json.decodeFromString("""{"id":99,"name":"Original topic","stat_desc":"fixture"}""")))
    }
    override suspend fun createVote(title:String,options:List<String>,description:String,choiceCount:Int,durationDays:Int):Result<DynamicCreatedVote> {
        calls+="vote:$title:${options.joinToString("|")}:$choiceCount:$durationDays"
        return Result.success(DynamicCreatedVote(333,title))
    }
    override suspend fun createReserve(title:String,livePlanStartTimeSeconds:Long,subType:Int):Result<DynamicCreatedReserve> {
        calls+="reserve:$title:$livePlanStartTimeSeconds:$subType"
        return Result.success(DynamicCreatedReserve(444,title))
    }
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val loader=ImageLoader.Builder(PlatformContext.INSTANCE).components {
        add(coil3.intercept.Interceptor { chain->SuccessResult(ColorImage(0xff20c7cc.toInt(),400,300),chain.request,DataSource.MEMORY) })
    }.build();SingletonImageLoader.setUnsafe(loader)
    val proofs=mutableListOf<JsonObject>()
    try {
        for(style in AppUiStyle.entries) {
            val platform=EditorPlatform();var submitted:DynamicPublishDraft?=null;var cancelled=0
            val scene=ImageComposeScene(width=760,height=1200,coroutineContext=coroutineContext);val ui=EditorScene(scene)
            var surface by mutableStateOf("composer");var sort by mutableStateOf(CommentSortMode.HOT)
            var vote:DynamicCreatedVote?=null;var reserve:DynamicCreatedReserve?=null
            try {
                scene.setContent {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                        AppSurface(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalDesktopDynamicEditorBindings provides platform) {
                                when(surface) {
                                    "composer"->DynamicPublishComposer(DynamicPublishDraft(text="Seed text",title="Seed title"),false,false,null,{cancelled++},{submitted=it})
                                    "images"->DynamicPublishComposer(DynamicPublishDraft(text="Image edit",imageUris=listOf("https://fixture.invalid/existing.png"),
                                        existingImages=listOf(DynamicCreatePic("https://fixture.invalid/existing.png",100,50,1f))),true,false,null,{cancelled++},{submitted=it})
                                    "vote"->DynamicCreateVoteDialog({cancelled++},{vote=it})
                                    "reserve"->DynamicCreateReserveDialog({cancelled++},{reserve=it})
                                    "header"->DynamicInlineCommentHeader(10001,sort,{sort=it})
                                }
                            }
                        }
                    }
                }
                ui.await("original composer mounted") { "发布动态" in ui.labels() && "@用户" in ui.labels() }
                ui.setText(ui.editable("Seed title"),"Edited title")
                ui.press(ui.text("仅自己可见"))
                ui.press(ui.text("@用户"));ui.await("original mention picker") { "@ 用户" in ui.labels() }
                val field=ui.nodes().last { it.config.getOrNull(SemanticsActions.SetText)!=null }
                ui.setText(field,"needle");ui.press(ui.text("搜索"))
                ui.await("original API results rendered") { "Original mention" in ui.labels() }
                ui.press(ui.text("Original mention"));ui.await("return to composer") { "@ 用户" !in ui.labels() }
                ui.press(ui.text("话题"));ui.await("original topic picker") { "选择话题" in ui.labels() }
                ui.press(ui.text("搜索"));ui.await("original topic result") { "#Original topic#" in ui.labels() }
                ui.press(ui.text("#Original topic#"));ui.await("topic selected in toolbar") { "选择话题" !in ui.labels() }
                ui.press(ui.text("表情"));ui.await("shared original catalog picker") { "[原表情]" in ui.labels() }
                ui.press(ui.text("[原表情]"));ui.await("catalog selected") { "选择表情" !in ui.labels() }
                ui.screenshot(output.resolve("${style.name.lowercase()}-original-composer.png"))
                ui.press(ui.text("发布"));ui.await("actual draft callback") { submitted!=null }
                val draft=checkNotNull(submitted)
                assertEquals("Edited title",draft.title);assertTrue(draft.private)
                assertEquals(listOf(DynamicPublishMention(77,"Original mention")),draft.mentions)
                assertEquals(listOf("[原表情]"),draft.emotes);assertEquals(DynamicPublishTopic(99,"Original topic"),draft.topic)
                assertEquals("Seed text @Original mention [原表情]",draft.text)
                ui.press(ui.text("取消"));assertEquals(1,cancelled)
                surface="images";submitted=null
                ui.await("original image edit draft") { "编辑动态" in ui.labels()&&"图片 1/9" in ui.labels() }
                // Scroll the original LazyRow, never inject image state into the composer.
                val scroll=ui.nodes().lastOrNull { (it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue?.invoke()?:0f)>0f }
                scroll?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(90f,0f)
                repeat(15){ui.frame()}
                ui.press(ui.text("图片 1/9"));ui.await("real selection callback limits unique images") { "图片 9/9" in ui.labels()&&"pick:9" in platform.calls }
                ui.press(ui.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("移除图片")==true })
                ui.await("existing remote image removed") { "图片 8/9" in ui.labels() }
                ui.press(ui.text("保存"));ui.await("image draft submitted") { submitted!=null }
                assertEquals((1..8).map { "file:///synthetic/image$it.png" },submitted!!.imageUris)
                assertTrue(submitted!!.existingImages.isEmpty())
                ui.screenshot(output.resolve("${style.name.lowercase()}-original-image-edit.png"))
                surface="vote";ui.await("original create vote") { "发起投票" in ui.labels() }
                val edits=ui.nodes().filter { it.config.getOrNull(SemanticsActions.SetText)!=null }.sortedBy { it.boundsInRoot.top }
                assertEquals(6,edits.size)
                ui.setText(edits[0],"Original vote");ui.setText(edits[2],"Choice A");ui.setText(edits[3],"Choice B")
                ui.press(ui.text("多选"));ui.press(ui.text("3 天"));ui.press(ui.text("创建"))
                ui.await("vote request callback") { vote!=null };assertEquals(333L,vote!!.voteId)
                assertTrue(platform.calls.contains("vote:Original vote:Choice A|Choice B||:2:3"))
                ui.screenshot(output.resolve("${style.name.lowercase()}-original-vote.png"))
                surface="reserve";ui.await("original reserve") { "添加直播预约" in ui.labels() }
                ui.setText(ui.nodes().last { it.config.getOrNull(SemanticsActions.SetText)!=null },"Original reserve")
                ui.press(ui.text("大航海直播"));ui.press(ui.nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text.startsWith("开播时间 ") }==true })
                ui.await("date callback") { platform.calls.contains("date") }
                ui.press(ui.text("创建"));ui.await("reserve created") { reserve!=null }
                assertEquals(444L,reserve!!.reserveId);assertTrue(platform.calls.any { it.startsWith("reserve:Original reserve:")&&it.endsWith(":1") })
                ui.screenshot(output.resolve("${style.name.lowercase()}-original-reserve.png"))
                surface="header";ui.await("original inline comment header") { "1万条回复" in ui.labels()&&"最新" in ui.labels() }
                ui.press(ui.text("最新"));assertEquals(CommentSortMode.NEWEST,sort)
                ui.screenshot(output.resolve("${style.name.lowercase()}-original-comment-header.png"))
                proofs+=buildJsonObject { put("style",style.name);put("originalComposerDraftAndPrivateMentionTopicEmote",true);put("originalVoteReserveControlsAndCallbacks",true)
                    put("originalInlineHeaderSort",true);put("originalNineImagesAndRemovalReuseBoundary",true);put("pointers",ui.pointers);put("HTTP",false);put("HWND",false);put("liquidChrome",false);put("fullDetailCommentItems",false) }
            } finally {scene.close()}
        }
        Files.writeString(output.resolve("ui-result.json"),JsonArray(proofs).toString())
        println("PASS: both original native themes; composer/private/@/topic/shared emotes/draft; vote/reserve callbacks; inline count/sort. No HTTP/HWND/liquid/full-detail claim.")
    } finally {loader.shutdown()}
}
