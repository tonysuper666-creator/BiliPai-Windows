package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.video.ui.components.*
import androidx.compose.ui.text.LinkAnnotation
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import kotlin.test.*

private fun reply(id:Long,root:Long=0,action:Int=0,like:Int=7,nested:List<ReplyItem>?=null):ReplyItem = Json.decodeFromString<ReplyItem>("""{
 "rpid":$id,"oid":1,"mid":77,"root":$root,"parent":0,"count":0,"rcount":0,"action":$action,"like":$like,
 "member":{"mid":"77","uname":"Original reply user","avatar":""},"content":{"message":"Original reply"}}
 """).copy(replies=nested)

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    assertEquals("Hello @name ",appendDynamicComposerToken("Hello","@name "))
    assertEquals("Hello [smile]",appendDynamicComposerToken("Hello ","[smile]"))
    val child=reply(2,root=1,action=1,like=4)
    val original=reply(1,nested=listOf(child))
    val updated=applyDynamicCommentHateInList(listOf(original),2,true)
    assertEquals(2,updated[0].replies!![0].action);assertEquals(3,updated[0].replies!![0].like)
    assertEquals(original.action,updated[0].action)
    assertEquals(DynamicCommentComposerTarget(1,2,"Original reply user"),resolveDynamicCommentReplyTarget(child))
    assertTrue(resolveDynamicCommentActionCapabilities(child,77,77).canDelete)
    assertFalse(resolveDynamicCommentActionCapabilities(child,77,77).canToggleTop)
    assertEquals(7,resolveDynamicCommentReportReasons().size)
    val target=DynamicCommentTarget(1,1)
    val empty=DynamicCommentLoadAttempt(target,emptyList(),10,0)
    val populated=DynamicCommentLoadAttempt(target,listOf(original),8,1)
    assertSame(populated,selectPreferredDynamicCommentAttempt(listOf(empty,populated),10))
    assertFalse(resolveDynamicMainCommentPageEnd(true,1,2,10))
    val state=SubReplyUiState(items=listOf(original).toImmutableList(),totalCount=2)
    val merged=resolveDynamicSubReplyStateAfterSuccess(state,listOf(original,child),2,false)
    assertEquals(listOf(1L,2L),merged.items.map{it.rpid});assertEquals(merged.items,merged.baseItems)
    assertEquals(CommentSortMode.HOT,CommentSortMode.fromApiMode(0));assertEquals(2,CommentSortMode.NEWEST.apiMode)
    val rich=buildRichCommentAnnotatedString("Hello @UP #Topic# {vote:42} 00:30 02:00 [emote]",
        atNameToMid=mapOf("UP" to 77L),topics=setOf("Topic"),renderableEmoteKeys=setOf("[emote]"),maxTimestampSeconds=60L)
    val links=rich.getLinkAnnotations(0,rich.length).mapNotNull { (it.item as? LinkAnnotation.Clickable)?.tag }
    assertTrue("USER:77" in links);assertTrue("TOPIC:Topic" in links);assertTrue("VOTE:42" in links)
    assertTrue("TS:30" in links);assertFalse("TS:120" in links)
    assertEquals(0.72f,resolveReplyAvatarFaceFraction(true));assertEquals(1f,resolveReplyAvatarFaceFraction(false))
    assertEquals(listOf(1L),resolveVisibleSubReplies(listOf(original,child),false,1).map{it.rpid})
    val reset=merged.resetForSort(SubReplySortMode.HOT);assertTrue(reset.items.isEmpty());assertNull(reset.grpcNextOffset)
    var owned=true
    val files=DesktopDynamicEditorSelectedImages{owned}
    val temp=Files.createTempDirectory("editor-images-");val image=temp.resolve("selected.png");Files.write(image,byteArrayOf(1,2,3))
    val source=files.accept(listOf(image)).single();assertContentEquals(byteArrayOf(1,2,3),files.read(source).third)
    assertFailsWith<IllegalStateException>{files.read("https://fixture.invalid/not-selected.png")}
    owned=false;assertFailsWith<CancellationException>{files.read(source)}
    owned=true;files.close();assertFailsWith<CancellationException>{files.read(source)}
    Files.writeString(output.resolve("policy-result.json"),"""{"passed":true,"originalNestedReplyReducers":true,"originalTargetCapabilities":true,"originalCommentFallbackAndSubReplyPolicies":true,"selectedActualLocalFileBytesAndRetiredOwner":true,"HTTP":false,"HWND":false,"fullDetailUI":false}""")
    println("PASS: exact original comment/reply policies and selected temp image bytes/retirement; no fake full Detail UI.")
}
