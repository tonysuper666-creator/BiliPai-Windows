package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.search.TopicDetailUiState
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal suspend fun runStoryTopicUiFixture(output: Path): Int = coroutineScope {
    var count=0
    fun verify(ok:Boolean,message:String) { check(ok) { message }; count++ }
    for(style in AppUiStyle.entries) {
        val data=StoryTopicFixtureData(); data.homes={ (1..8).map(::storyFixtureVideo) }
        val requests=mutableListOf<DesktopStoryPlaybackRequest>();val releases=mutableListOf<DesktopStoryOwner>()
        var active by mutableStateOf(true);var snapshot by mutableStateOf(DesktopStoryPlaybackSnapshot())
        var user=0L;var search=0;var back=0
        val scene=ImageComposeScene(700,980,Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=AppThemeMode.DARK)) {
                ProvideAppThemeConfig(AppThemeConfig(hapticFeedbackEnabled=false)) {
                    DesktopStoryScreen(data,DesktopStorySeed("BV-seed",88,title="种子视频"),isActive=active,playback=snapshot,
                        onPlaybackRequest={ requests += it; if(it.select) snapshot=DesktopStoryPlaybackSnapshot(it.owner,it.queue[it.index].bvid) },
                        onReleasePlayback={ releases += it },onBack={ back++ },onUser={ user=it },onSearch={ search++ }) { _,card,modifier ->
                        Box(modifier.background(Color(0xff183b52)),contentAlignment=androidx.compose.ui.Alignment.Center) {
                            AppText("根播放器槽：${card.bvid}",color=Color.White)
                        }
                    }
                }
            }
        }
        var nanos=0L
        suspend fun settle(frames:Int=8) { repeat(frames) { nanos+=40_000_000;scene.render(nanos).close();delay(4) } }
        fun nodes(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::nodes)
        fun node(text:String)=scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        suspend fun click(text:String) {
            val p=node(text).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move,p,timeMillis=nanos/1_000_000)
            scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000+1,buttons=PointerButtons(isPrimaryPressed=true))
            scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+40,buttons=PointerButtons());settle()
        }
        try {
            settle(16)
            verify(requests.any { it.select && it.index==0 && it.queue.first().preferredCid==88L },"$style seed auto-selection retains owner and CID")
            val owner=requests.first().owner
            verify(requests.all { it.owner==owner && it.owner.sessionEpoch==7L },"$style unified requests retain one route/account owner")
            verify(requests.filter { it.select }.size==1,"$style loading/appending must not reload current seed")
            verify(requests.any { !it.select && it.queue.size==9 },"$style original shuffled feed append updates host queue without selection")
            scene.render(nanos).use { image -> Files.write(output.resolve("story-${style.name.lowercase()}.png"), image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            click("下一条");settle(16)
            verify(requests.last { it.select }.index==1 && requests.last { it.select }.queue[1].preferredCid>100,"$style actual next pointer commits a single CID-aware queue selection")
            val countBefore=requests.count { it.select }
            scene.sendKeyEvent(KeyEvent(Key.PageDown,KeyEventType.KeyDown));settle(18)
            verify(requests.count { it.select }==countBefore+1 && requests.last { it.select }.index==2,"$style page-down keyboard selects next settled page")
            val beforeDrag=requests.count { it.select }
            val start=Offset(350f,760f);val base=nanos/1_000_000
            scene.sendPointerEvent(PointerEventType.Press,start,timeMillis=base,buttons=PointerButtons(isPrimaryPressed=true),type=PointerType.Touch)
            repeat(8) { step ->
                scene.sendPointerEvent(PointerEventType.Move,Offset(start.x,start.y-(step+1)*60f),timeMillis=base+(step+1)*40,buttons=PointerButtons(isPrimaryPressed=true),type=PointerType.Touch)
                nanos+=40_000_000;scene.render(nanos).close();delay(3)
            }
            verify(requests.count { it.select }==beforeDrag,"$style dragging must not activate intermediate pages")
            scene.sendPointerEvent(PointerEventType.Release,Offset(start.x,start.y-480f),timeMillis=base+360,buttons=PointerButtons(),type=PointerType.Touch);settle(20)
            verify(requests.count { it.select }==beforeDrag+1 && requests.last { it.select }.index==3,"$style real vertical drag commits only the final next page: before=$beforeDrag selections=${requests.filter { it.select }.map { it.index }}")
            click("搜索");verify(search==1,"$style search dispatch")
            click("‹ 返回");verify(back==1,"$style back dispatch")
            val selected=requests.last { it.select }.queue[3]
            click(selected.author);verify(user==selected.authorMid,"$style current author routes to real MID")
            val beforeRootNext=requests.count { it.select };val rootQueue=requests.last { it.select }.queue
            snapshot=DesktopStoryPlaybackSnapshot(owner,rootQueue[4].bvid,queueIndex=4,cid=rootQueue[4].preferredCid);settle(20)
            verify(requests.count { it.select }==beforeRootNext && node("5 / 9").config.getOrNull(SemanticsProperties.Text)!=null,
                "$style root queue continuation scrolls to current item without another load")
            snapshot=snapshot.copy(error="播放器初始化失败");settle()
            verify(scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text=="播放器初始化失败" }==true },"$style player initialization error is visible")
            snapshot=snapshot.copy(owner=DesktopStoryOwner("foreign",7),error="不属于此页面");settle()
            verify(scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.none { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text=="不属于此页面" }==true },"$style foreign-source error cannot leak into Story state")
            active=false;settle()
            verify(releases==listOf(owner),"$style inactive releases precisely the owned route")
            verify(requests.last().owner==owner,"$style inactive must not produce new playback requests")
        } finally { scene.close() }
        verify(releases.size==1,"$style dispose after inactivity must not double-release source")

        var topicState by mutableStateOf(TopicDetailUiState(details=TopicTopDetails(TopicCreator(51,"话题作者"),TopicItem(91,"离线话题","原话题描述",view=9000,discuss=11)),
            items=listOf(DynamicItem(id_str="one")),offset="next",hasMore=true,
            sortOptions=listOf(TopicSortOption(0,"热门"),TopicSortOption(1,"最新"))))
        var creator=0L;var more=0;var video=0
        val topicScene=ImageComposeScene(900,760,Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=AppThemeMode.LIGHT)) {
                ProvideAppThemeConfig(AppThemeConfig(hapticFeedbackEnabled=false)) {
                AppSurface(Modifier.fillMaxSize()) {
                    DesktopTopicContent(topicState,{ creator=it },{ topicState=topicState.copy(selectedSortBy=it) },{ more++ },{}) { item ->
                        AppCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                            AppText("原 DynamicItem ${item.id_str}")
                            AppTextButton({ video++ }) { AppText("站内视频") }
                        } }
                    }
                }
                }
            }
        }
        suspend fun topicSettle() { repeat(6) { nanos+=40_000_000;topicScene.render(nanos).close();delay(3) } }
        suspend fun topicClick(text:String) {
            val n=topicScene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text==text }==true }
            val p=n.boundsInRoot.center
            topicScene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
            topicScene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+40,buttons=PointerButtons());topicSettle()
        }
        try {
            topicSettle()
            topicScene.render(nanos).use { image -> Files.write(output.resolve("topic-${style.name.lowercase()}.png"),image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            topicClick("话题作者");verify(creator==51L,"$style topic header actual MID click")
            topicClick("最新");verify(topicState.selectedSortBy==1,"$style original topic sort value dispatch")
            topicClick("加载更多");verify(more==1,"$style topic pagination actual pointer dispatch")
            topicClick("站内视频");verify(video==1,"$style topic DynamicItem renderer slot routes within product")
            topicState=topicState.copy(isSwitchingSort=true);topicSettle();topicClick("热门")
            verify(topicState.selectedSortBy==1,"$style sorting disables duplicate switch")
        } finally { topicScene.close() }
    }
    val empty=StoryTopicFixtureData();var emptyRequests=0;var emptyReleases=0
    val idleScene=ImageComposeScene(700,980,Density(1f)) {
        DesktopAppearanceTheme(DesktopThemeSettings()) {
            DesktopStoryScreen(empty,playback=DesktopStoryPlaybackSnapshot(),onPlaybackRequest={ emptyRequests++ },
                onReleasePlayback={ emptyReleases++ },onBack={},onUser={},onSearch={}) { _,_,_ -> error("Empty list must not attach player") }
        }
    }
    repeat(12) { idleScene.render(it*40_000_000L).close();delay(3) }
    verify(emptyRequests==0,"An empty Story list must never acquire playback/update-block ownership")
    idleScene.close()
    verify(emptyReleases==0,"Disposing an idle Story must never release another player owner")
    count
}

class DesktopStoryTopicUiTest {
    @org.junit.jupiter.api.Test fun realPortraitAndTopicInteractions():Unit=runBlocking {
        check(runStoryTopicUiFixture(Files.createDirectories(Path.of("build/reports/story-topic-parity")))==46)
    }
}
