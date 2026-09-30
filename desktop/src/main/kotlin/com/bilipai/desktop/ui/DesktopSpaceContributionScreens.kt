package com.bilipai.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.*

enum class DesktopSpaceContributionSection(val title: String) {
    HOME("主页"), VIDEOS("投稿"), LIKED("赞过"), COINS("投币"), FOLLOW_BANGUMI("追番"), AUDIO("音频"), COURSES("课堂")
}

internal class DesktopSpaceContributionState {
    var order by mutableStateOf(VideoSortOrder.PUBDATE)
    var keyword by mutableStateOf("")
    var draft by mutableStateOf("")
    var interactionRows = mapOf<Boolean, List<VideoItem>>()
    var followedRows = emptyList<FollowBangumiItem>()
    var audioRows = emptyList<SpaceAudioItem>()
    val sortScroll = ScrollState(0)
}

/** Embed in the retained UP route; raw song/course/follow objects preserve all navigation identities. */
@Composable
fun DesktopSpaceContributionScreen(mid: Long, section: DesktopSpaceContributionSection, repository: DesktopRepository,
    backend: DesktopSpaceContributionsRepository, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit,
    onFollowBangumi: (FollowBangumiItem) -> Unit, onAudio: (SpaceAudioItem) -> Unit,
    onCourse: (SpaceCheeseItem) -> Unit, onLogin: () -> Unit) {
    require(mid > 0)
    val account by repository.account.collectAsState()
    val epoch by repository.sessionEpochFlow.collectAsState()
    val owner = listOf("up-space-contributions", epoch, account?.mid, mid)
    val memory = LocalDesktopBrowseMemory.current
    val state = remember(memory, owner) { memory?.screen(owner) { DesktopSpaceContributionState() } ?: DesktopSpaceContributionState() }
    CompositionLocalProvider(LocalCommunityFeedNamespace provides owner) {
    when (section) {
        DesktopSpaceContributionSection.HOME -> CommunityFeed<DesktopSpaceHome, Unit>(section, Unit,
            load = { CommunityBatch(listOf(backend.home(mid)), null) }, identity = { "home" }, onLogin = onLogin) { home ->
            if (home.notice.isNotBlank()) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("公告", style = MaterialTheme.typography.titleMedium); Text(home.notice)
            } }
            home.topVideo?.let { top ->
                Text("置顶视频${top.reason.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                val bvid = top.bvid.ifBlank { top.aid.takeIf { it > 0 }?.let(com.android.purebilibili.core.util.IdUtils::av2bv).orEmpty() }
                CommunityVideoRow(VideoCard(bvid, top.title, top.pic, "", top.stat.view, top.duration,
                    publishedAt = top.pubdate, authorMid = mid), onVideo, onUser)
            }
            if (home.topVideo == null && home.notice.isBlank()) Text("暂无置顶视频或公告", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DesktopSpaceContributionSection.VIDEOS -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(state.sortScroll).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoSortOrder.entries.forEach { order -> FilterChip(selected = state.order == order, onClick = { state.order = order }, label = { Text(order.displayName) }) }
            }
            Row(Modifier.padding(20.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = state.draft, onValueChange = { state.draft = it }, label = { Text("搜索 TA 的视频") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { state.keyword = state.draft.trim() }) { Text("搜索") }
                TextButton(onClick = { state.draft = ""; state.keyword = "" }) { Text("全部") }
            }
            CommunityFeed<SpaceVideoItem, Int?>(listOf(section, state.order, state.keyword), null,
                load = { page -> backend.videos(mid, page, state.order, keyword = state.keyword).let { CommunityBatch(it.items, it.nextPage) } },
                identity = { it.bvid.ifBlank { it.aid.toString() } }, onLogin = onLogin) { item ->
                CommunityVideoRow(VideoCard(item.bvid, item.title, item.pic, item.author, item.play.toLong(), personalDurationText(item.length),
                    publishedAt = item.created, authorMid = mid), onVideo, onUser)
            }
        }
        DesktopSpaceContributionSection.LIKED, DesktopSpaceContributionSection.COINS -> {
            val coins = section == DesktopSpaceContributionSection.COINS
            CommunityFeed<VideoItem, Int>(section, 1, load = { page ->
                val previous = if (page == 1) emptyList() else state.interactionRows[coins].orEmpty()
                val result = backend.interactions(mid, coins, page, previous)
                state.interactionRows = state.interactionRows + (coins to (previous + result.items).distinctBy { it.bvid.ifBlank { it.id.toString() } })
                CommunityBatch(result.items, result.nextPage)
            }, identity = { it.bvid.ifBlank { it.id.toString() } }, onLogin = onLogin) { item ->
                CommunityVideoRow(VideoCard(item.bvid, item.title, item.pic, item.owner.name, item.stat.view.toLong(), item.duration,
                    preferredCid = item.cid, publishedAt = item.pubdate, authorMid = item.owner.mid), onVideo, onUser)
            }
        }
        DesktopSpaceContributionSection.FOLLOW_BANGUMI -> CommunityFeed<FollowBangumiItem, Int>(section, 1, load = { page ->
            val previous = if (page == 1) emptyList() else state.followedRows
            val result = backend.followedBangumi(mid, page, previous)
            state.followedRows = (previous + result.items).distinctBy { it.seasonId.takeIf { id -> id > 0 } ?: it.mediaId }
            CommunityBatch(result.items, result.nextPage)
        }, identity = { it.seasonId.takeIf { id -> id > 0 } ?: it.mediaId }, onLogin = onLogin) { item ->
            CommunityLinkCard(item.title, item.cover, listOf(item.seasonTypeName, item.badge, item.progress,
                item.newEp?.indexShow.orEmpty(), item.evaluate).filter(String::isNotBlank).joinToString(" · ")) { onFollowBangumi(item) }
        }
        DesktopSpaceContributionSection.AUDIO -> CommunityFeed<SpaceAudioItem, Int>(section, 1, load = { page ->
            val previous = if (page == 1) emptyList() else state.audioRows
            val result = backend.audios(mid, page, previous)
            state.audioRows = (previous + result.items).distinctBy { it.id }
            CommunityBatch(result.items, result.nextPage)
        }, identity = { it.id }, onLogin = onLogin) { audio ->
            val plays = (audio.statistic?.play ?: audio.play_count.toLong()).coerceAtLeast(0L)
            CommunityLinkCard(audio.title, audio.cover, listOf(audio.author.ifBlank { audio.uname },
                "$plays 次播放", "${audio.duration / 60}:${(audio.duration % 60).toString().padStart(2, '0')}", audio.intro)
                .filter(String::isNotBlank).joinToString(" · ")) { onAudio(audio) }
        }
        DesktopSpaceContributionSection.COURSES -> CommunityFeed<SpaceCheeseItem, Int>(section, 1,
            load = { page -> backend.courses(mid, page).let { CommunityBatch(it.items, it.nextPage) } },
            identity = { it.seasonId.takeIf { id -> id > 0 } ?: Triple(it.title, it.cover, it.ctime) }, onLogin = onLogin) { item ->
            CommunityLinkCard(item.title, item.cover, (item.marks + item.status + item.ctime).filter(String::isNotBlank).joinToString(" · ")) { onCourse(item) }
        }
    }
    }
}
