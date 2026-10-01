@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.votefixture

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.VideoCommentVoteCard
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.*
import java.nio.file.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

/** Original card and sole original dialog, using real App* and ImageComposeScene
 * layers. Only server responses are fake; no replacement dialog renderer. */
internal suspend fun uiProof(output: Path): List<String> = coroutineScope {
    val checks = mutableListOf<String>()
    for (style in AppUiStyle.entries) {
        val temporary = Files.createTempDirectory("bilipai-vote-ui-task-")
        val actions = VoteActions(DesktopPluginContext(DesktopPluginStore(temporary)))
        val card = ReplyVoteCard(70,"Fixture card question",5,
            listOf(ReplyVoteCardOption(11,"Alpha",3), ReplyVoteCardOption(29,"Beta",2)))
        val scene = ImageComposeScene(width=720,height=620,coroutineContext=coroutineContext)
        var nanos = 0L
        var pointerPairs = 0
        try {
            scene.setContent { DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false),
                systemLanguageTags=listOf("en-US")) {
                CompositionLocalProvider(LocalDesktopDynamicCardBindings provides actions) {
                    Box(Modifier.fillMaxSize().padding(24.dp)) { VideoCommentVoteCard(card,Modifier.fillMaxWidth()) }
                }
            } }
            // Miuix's original bottom-slide animation needs its real frame clock
            // to reach the visible button geometry before sending a pointer.
            suspend fun settle() { repeat(24) { nanos+=30_000_000L; scene.render(nanos).close(); yield(); delay(2) } }
            fun nodes(): List<SemanticsNode> {
                fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
            }
            fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            fun gate(label:String,value:Boolean) { check(value) { "$style: $label; actualTexts=${texts()}" }; checks += "$style: $label" }
            suspend fun click(label:String) {
                val matches = nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { v->v.text==label } == true }
                check(matches.isNotEmpty()) { "Missing pointer target $label; actualTexts=${texts()}" }
                val p=matches.last().boundsInWindow.center
                check(p.x in 0f..720f && p.y in 0f..620f) { "Pointer target not yet inside actual scene: $label / $p" }
                scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+45,buttons=PointerButtons())
                pointerPairs++; settle()
            }
            settle()
            gate("original vote-card count/percentage fields are rendered",texts().containsAll(listOf("Fixture card question","5 人参与 · 选择选项投票","60%","40%")))
            click("Beta")
            gate("actual option click opens the original dialog and loads the same vote ID",actions.reads==listOf(70L) && texts().contains("Fixture dialog question"))
            scene.render(nanos+1).use { Files.write(output.resolve("${style.name.lowercase()}-vote-dialog-before.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            click("投票")
            gate("initial option idx 29 survives card→dialog→submit callback",actions.submissions==listOf(Triple(70L,listOf(29),"")))
            gate("original dialog shows confirmed result",texts().contains("你已投票 · 6 人参与"))
            click("关闭")
            gate("original onVoteSuccess updates card count/options/myVote without replacing schema",
                texts().containsAll(listOf("6 人参与 · 已投票","✓ Beta","50%")) && !texts().contains("Fixture dialog question"))
            gate("real pointer pairs",pointerPairs==3)
            scene.render(nanos+1).use { Files.write(output.resolve("${style.name.lowercase()}-vote-card-after.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        } finally { scene.close() }
    }
    checks
}

private class VoteActions(override val context: DesktopPluginContext): DesktopDynamicCardPlatform {
    val reads = mutableListOf<Long>()
    val submissions = mutableListOf<Triple<Long,List<Int>,String>>()
    var info = DynamicVoteInfo(vote_id=70,title="Fixture dialog question",join_num=5,choice_cnt=1,
        options=listOf(DynamicVoteOption(11,"Alpha",3),DynamicVoteOption(29,"Beta",2)))
    override val emotes = object: DesktopDynamicEmotes {
        override fun snapshot() = emptyMap<String,String>()
        override fun currentSessionKey(): Any = "test-only-vote-owner"
        override suspend fun ensureLoaded() = snapshot()
    }
    override fun isOwned() = true
    override suspend fun getVoteInfo(voteId:Long):Result<DynamicVoteInfo> { reads+=voteId; return Result.success(info) }
    override suspend fun submitVote(voteId:Long,optionIndexes:List<Int>,dynamicId:String):Result<DynamicVoteInfo> {
        submissions+=Triple(voteId,optionIndexes,dynamicId)
        info=info.copy(join_num=6,my_votes=optionIndexes,options=listOf(DynamicVoteOption(11,"Alpha",3),DynamicVoteOption(29,"Beta",3)))
        return Result.success(info)
    }
    private fun unexpected(): Nothing = error("Unexpected non-vote UI operation")
    override fun copyText(text:String) = unexpected()
    override fun shareText(text:String) = unexpected()
    override fun showFeedback(message:String) = unexpected()
    override fun openLink(url:String) = unexpected()
    override suspend fun searchUp(name:String) = unexpected()
    override suspend fun saveImage(url:String) = unexpected()
    override suspend fun saveImages(urls:List<String>) = unexpected()
    override suspend fun saveMotionPhoto(imageUrl:String,videoUrl:String) = unexpected()
    override suspend fun saveLivePhotoVideo(videoUrl:String) = unexpected()
    override suspend fun shareImage(url:String) = unexpected()
    override suspend fun getShareTargets(size:Int) = unexpected()
    override suspend fun getMessageSessions(size:Int) = unexpected()
    override suspend fun fetchMessageUserInfo(mid:Long) = unexpected()
    override suspend fun sendDynamicShare(receiverId:Long,content:String) = unexpected()
}
