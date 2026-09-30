package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private data class CommunitySearchRow(val key: String, val title: String, val description: String, val cover: String,
    val video: VideoCard? = null, val user: Long = 0, val article: Long = 0, val live: Long = 0,
    val season: Long = 0, val url: String = "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommunitySearch(initialQuery: String, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    var draft by remember(initialQuery) { mutableStateOf(initialQuery) }
    var submitted by remember(initialQuery) { mutableStateOf(initialQuery.trim()) }
    var type by remember { mutableStateOf(SearchType.VIDEO) }
    var order by remember { mutableStateOf("totalrank") }
    var suggestions by remember { mutableStateOf(emptyList<SearchSuggestTag>()) }
    var hot by remember { mutableStateOf(emptyList<HotItem>()) }
    var defaultTerm by remember { mutableStateOf("") }
    var hintError by remember { mutableStateOf<Throwable?>(null) }
    var suggestionsError by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(community) {
        try { hot = community.searchHotWords().trending?.list.orEmpty(); defaultTerm = community.searchDefault().showName }
        catch (error: Exception) { if (error is CancellationException) throw error; hintError = error }
    }
    LaunchedEffect(draft, submitted) {
        suggestions = emptyList(); suggestionsError = null
        if (draft.isNotBlank() && draft.trim() != submitted) {
            delay(300)
            try { suggestions = community.searchSuggestions(draft) }
            catch (error: Exception) { if (error is CancellationException) throw error; suggestionsError = error }
        }
    }
    fun submit(term: String) { val value = term.trim(); if (value.isNotEmpty()) { draft = value; submitted = value; suggestions = emptyList() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, singleLine = true, placeholder = { Text(defaultTerm.ifBlank { "搜索视频、UP 主或专栏" }) },
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit(draft) }))
            Button(onClick = { submit(draft.ifBlank { defaultTerm }) }) { Text("搜索") }
        }
        if (suggestions.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.take(10).forEach { suggestion -> SuggestionChip(onClick = { submit(suggestion.value.ifBlank { suggestion.term }) }, label = { Text(cleanSearchText(suggestion.name.ifBlank { suggestion.value })) }) }
        }
        suggestionsError?.let { Text(it.message ?: "搜索建议加载失败", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchType.entries.forEach { candidate -> FilterChip(selected = type == candidate, onClick = { type = candidate }, label = { Text(candidate.displayName) }) }
        }
        if (type == SearchType.VIDEO && submitted.isNotBlank()) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("totalrank" to "综合", "click" to "最多播放", "pubdate" to "最新发布").forEach { (value, label) ->
                FilterChip(selected = order == value, onClick = { order = value }, label = { Text(label) })
            }
        }
        if (submitted.isBlank()) Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("热搜", style = MaterialTheme.typography.titleLarge)
            hintError?.let { CommunityFailure(it, navigation.onLogin) }
            hot.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().clickable { submit(item.keyword.ifBlank { item.show_name }) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", color = MaterialTheme.colorScheme.primary)
                    Text(item.show_name.ifBlank { item.keyword }, modifier = Modifier.weight(1f))
                    if (item.icon.isNotBlank()) AsyncImage(model = imageUrl(item.icon), contentDescription = item.recommend_reason, modifier = Modifier.size(24.dp))
                }
            }
        } else CommunityFeed<CommunitySearchRow, Int>(Triple(submitted, type, order), 1, load = { page ->
            val result = community.typedSearch(submitted, type, page, if (type == SearchType.VIDEO) mapOf("order" to order) else emptyMap())
            CommunityBatch(communitySearchRows(result.result), result.nextPage)
        }, identity = { it.key }, onLogin = navigation.onLogin) { item ->
            val video = item.video
            if (video != null) CommunityVideoRow(video, navigation.onVideo, navigation.onUser)
            else CommunityLinkCard(item.title, item.cover, item.description) {
                when {
                    item.user > 0 -> navigation.onUser(item.user)
                    item.article > 0 -> navigation.onArticle(item.article)
                    item.live > 0 -> navigation.onLive(item.live)
                    item.season > 0 -> navigation.onBangumi(item.season)
                    item.url.isNotBlank() -> navigateCommunityUrl(item.url, navigation)
                }
            }
        }
    }
}

private fun communitySearchRows(result: CommunitySearchResult): List<CommunitySearchRow> = when (result) {
    is CommunitySearchResult.Videos -> result.data.result.orEmpty().map { raw ->
        val item = raw.toVideoItem()
        CommunitySearchRow("video:${item.bvid}", item.title, "", item.pic,
            video = VideoCard(item.bvid, item.title, item.pic, item.owner.name, item.stat.view.toLong(), item.duration,
                publishedAt = item.pubdate, authorMid = item.owner.mid))
    }
    is CommunitySearchResult.Users -> result.data.result.orEmpty().map { raw -> val item = raw.cleanupFields()
        CommunitySearchRow("user:${item.mid}", item.uname, "${item.fans} 粉丝 · ${item.videos} 个视频\n${item.usign}", item.upic, user = item.mid) }
    is CommunitySearchResult.Media -> result.data.result.orEmpty().map { item ->
        CommunitySearchRow("season:${item.seasonId}:${item.mediaId}", cleanSearchText(item.title),
            listOf(item.seasonTypeName, item.indexShow, item.areas, item.desc).filter { it.isNotBlank() }.joinToString(" · "), item.cover,
            season = item.seasonId.takeIf { it > 0 } ?: item.pgcSeasonId, url = item.gotoUrl) }
    is CommunitySearchResult.LiveRooms -> result.data.result.orEmpty().map { item ->
        CommunitySearchRow("live:${item.roomid}", cleanSearchText(item.title), "${item.uname} · ${item.online} 人 · ${item.area_v2_name}",
            item.user_cover.ifBlank { item.cover }, live = item.roomid) }
    is CommunitySearchResult.LiveUsers -> result.data.result.orEmpty().map { raw -> val item = raw.cleanupFields()
        CommunitySearchRow("live-user:${item.uid}", item.uname, "${if (item.liveStatus == 1) "直播中" else "未开播"} · ${item.attentions} 关注", item.uface,
            live = item.roomid, user = if (item.roomid <= 0) item.uid else 0) }
    is CommunitySearchResult.Articles -> result.data.result.orEmpty().map { raw -> val item = raw.cleanupFields()
        CommunitySearchRow("article:${item.id}", item.title, "${item.view} 阅读 · ${item.categoryName}\n${item.description}", item.imageUrls.firstOrNull().orEmpty(), article = item.id) }
    is CommunitySearchResult.Topics -> result.data.result.orEmpty().map { raw -> val item = raw.cleanupFields()
        CommunitySearchRow("topic:${item.topicId}", item.title, "${item.view} 阅读 · ${item.author}\n${item.description}", item.cover,
            url = "https://www.bilibili.com/v/topic/detail/?topic_id=${item.topicId}") }
    is CommunitySearchResult.Photos -> result.data.result.orEmpty().map { raw -> val item = raw.cleanupFields()
        CommunitySearchRow("photo:${item.id}", item.title, "${item.uname} · ${item.count} 张 · ${item.view} 浏览", item.cover, user = item.mid) }
}

@Composable
internal fun CommunityUserSpace(requestedMid: Long, repository: DesktopRepository, social: DesktopSocialRepository,
    community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    val account by repository.account.collectAsState()
    val mid = requestedMid.takeIf { it > 0 } ?: account?.mid ?: 0
    var profile by remember(mid) { mutableStateOf<UserProfile?>(null) }
    var error by remember(mid) { mutableStateOf<Throwable?>(null) }
    var tab by remember(mid) { mutableIntStateOf(0) }
    var selectedCollection by remember(mid) { mutableStateOf<Pair<Long, String>?>(null) }
    if (mid <= 0) { CommunityLoginGate(repository, navigation.onLogin) {}; return }
    LaunchedEffect(mid, account?.mid) { try { profile = social.userProfile(mid) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure } }
    Column(Modifier.fillMaxSize()) {
        profile?.let { user ->
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(model = imageUrl(user.avatar), contentDescription = user.name, modifier = Modifier.size(74.dp))
                Column(Modifier.weight(1f)) {
                    Text(user.name, style = MaterialTheme.typography.titleLarge)
                    Text("${user.followers} 粉丝 · ${user.followingCount} 关注 · LV${user.level}${if (user.isVip) " · 大会员" else ""}")
                    if (user.officialTitle.isNotBlank()) Text(user.officialTitle, color = MaterialTheme.colorScheme.primary)
                    Text(user.biography)
                }
                if (account?.mid != user.mid) CommunityAction(if (user.isFollowed) "取消关注" else "关注", navigation.onLogin,
                    action = { social.setFollowing(user.mid, !user.isFollowed) }, onSuccess = { profile = user.copy(isFollowed = !user.isFollowed) })
            }
        }
        error?.let { CommunityFailure(it, navigation.onLogin) }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("投稿", "动态", "关注", "合集 / 系列").forEachIndexed { index, title -> FilterChip(selected = tab == index, onClick = { tab = index; selectedCollection = null }, label = { Text(title) }) }
        }
        key(mid, tab) {
            when (tab) {
                0 -> CommunityFeed(Pair(mid, tab), 1, load = { page -> community.spaceVideos(mid, page).let { CommunityBatch(it.items, it.nextPage) } },
                    identity = { it.aid }, onLogin = navigation.onLogin) { item ->
                    CommunityVideoRow(VideoCard(item.bvid, item.title, item.pic, item.author, item.play.toLong(), personalDurationText(item.length),
                        publishedAt = item.created, authorMid = mid), navigation.onVideo, navigation.onUser)
                }
                1 -> CommunityLoginGate(repository, navigation.onLogin) {
                    CommunityFeed(Pair(mid, tab), "", load = { offset -> community.spaceDynamics(mid, offset).let { CommunityBatch(it.items, it.nextOffset) } },
                        identity = { it.id_str }, onLogin = navigation.onLogin) { CommunityDynamicCard(it, community, navigation) }
                }
                2 -> CommunityFeed(Pair(mid, tab), 1, load = { page -> community.followings(mid, page).let { CommunityBatch(it.items, it.nextPage) } },
                    identity = { it.mid }, onLogin = navigation.onLogin) { user ->
                    CommunityLinkCard(user.uname, user.face, user.sign) { navigation.onUser(user.mid) }
                }
                3 -> {
                    val selected = selectedCollection
                    if (selected != null) Column {
                        TextButton(onClick = { selectedCollection = null }) { Text("‹ 所有合集和系列") }
                        CommunityCollectionScreen(mid, selected.first, selected.second, community, navigation.onVideo, navigation.onUser, navigation.onLogin)
                    } else CommunityFeed<Pair<String, Any>, Unit>(Pair(mid, tab), Unit, load = {
                        val items = community.spaceCollections(mid).items_lists
                        CommunityBatch(items?.seasons_list.orEmpty().map { "season" to it } + items?.series_list.orEmpty().map { "series" to it }, null)
                    }, identity = { entry -> when (val item = entry.second) { is SeasonItem -> "season:${item.meta.season_id}"; is SeriesItem -> "series:${item.meta.series_id}"; else -> entry.toString() } },
                        onLogin = navigation.onLogin) { entry ->
                        when (val item = entry.second) {
                            is SeasonItem -> CommunityLinkCard(item.meta.name, item.meta.cover, "${item.meta.total} 个视频 · ${item.meta.description}") { selectedCollection = item.meta.season_id to "season" }
                            is SeriesItem -> CommunityLinkCard(item.meta.name, item.meta.cover, "${item.meta.total} 个视频 · ${item.meta.description}") { selectedCollection = item.meta.series_id to "series" }
                        }
                    }
                }
            }
        }
    }
}
