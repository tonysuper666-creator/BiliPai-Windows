package com.bilipai.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.android.purebilibili.core.plugin.FeedKind
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.search.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

internal data class CommunitySearchRow(val key: String, val title: String, val description: String, val cover: String,
    val video: VideoCard? = null, val rawVideo: VideoItem? = null, val user: Long = 0, val article: Long = 0, val live: Long = 0,
    val season: Long = 0, val url: String = "", val blockedOwnerMid: Long = 0)

private class CommunitySearchState(initialQuery: String) {
    var draft by mutableStateOf(initialQuery)
    var submitted by mutableStateOf(initialQuery.trim())
    var type by mutableStateOf(SearchType.VIDEO)
    var filters by mutableStateOf(DesktopSearchFilters())
    var hot by mutableStateOf<SearchTrendingBundle?>(null)
    var defaultTerm by mutableStateOf("")
    var hintError by mutableStateOf<Throwable?>(null)
    var trendingError by mutableStateOf<Throwable?>(null)
    var discover by mutableStateOf<List<HotItem>?>(null)
    var discoverError by mutableStateOf<Throwable?>(null)
    var historyError by mutableStateOf<Throwable?>(null)
    var hintsRevision by mutableIntStateOf(0)
    var discoverRevision by mutableIntStateOf(0)
    var filterExpanded by mutableStateOf(false)
    var initialRecorded by mutableStateOf(false)
    val landingScroll = ScrollState(0)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommunitySearch(initialQuery: String, community: DesktopCommunityRepository, navigation: CommunityNavigation,
    runtime: DesktopPluginRuntime? = null, defaultSearchHintEnabled: Boolean = true) {
    val account by community.account.collectAsState()
    val epoch by community.accountEpoch.collectAsState()
    val blocked by community.blockedUps.mids.collectAsState()
    val memory = LocalDesktopBrowseMemory.current
    val stateKey = listOf("search-screen", epoch, account?.mid, initialQuery)
    val state = remember(memory, stateKey) { memory?.screen(stateKey) { CommunitySearchState(initialQuery) } ?: CommunitySearchState(initialQuery) }
    var draft by state::draft; var submitted by state::submitted; var type by state::type; var filters by state::filters
    val preferences = community.searchPreferences
    val navigationContext = com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences.current?.context
    val savedTabOrder by remember(navigationContext) {
        navigationContext?.let(com.android.purebilibili.core.store.DesktopOriginalFullNavigationSettings::getSearchFilterTabOrder)
            ?: kotlinx.coroutines.flow.flowOf(emptyList<String>())
    }.collectAsState(emptyList())
    val orderedSearchTabs = remember(savedTabOrder) {
        com.android.purebilibili.feature.search.resolveSearchFilterTabs(savedTabOrder)
    }
    val history by remember(preferences, account?.mid) { preferences.history(account?.mid) }.collectAsState()
    val privacy by preferences.privacyMode.collectAsState()
    val suggestionsEnabled by preferences.suggestionsEnabled.collectAsState()
    val displayedSearchHint = state.defaultTerm.takeIf { defaultSearchHintEnabled }.orEmpty()
    val resolvedSubmitKeyword = resolveSearchSubmitKeyword(draft, displayedSearchHint)
    val search = community.search
    val scope = rememberCoroutineScope()
    var suggestions by remember(state) { mutableStateOf(emptyList<SearchSuggestTag>()) }
    var suggestionsError by remember(state) { mutableStateOf<Throwable?>(null) }
    var settingsBusy by remember(state) { mutableStateOf(false) }
    val nativePlugins = runtime?.plugins?.collectAsState()?.value
    val jsonPlugins = runtime?.jsonPlugins?.collectAsState()?.value
    val pluginConfig = runtime?.store?.snapshot("plugin_prefs")?.collectAsState()?.value
    val transform = remember(runtime, nativePlugins, jsonPlugins, pluginConfig, blocked) { { rows: List<CommunitySearchRow> ->
        val visible = desktopVisibleSearchRows(rows, blocked)
        if (runtime == null || visible.none { it.rawVideo != null }) visible else {
            val originals = visible.associateBy { it.rawVideo?.bvid }
            runtime.filterFeedItems(visible.mapNotNull { it.rawVideo }, FeedKind.SEARCH).mapNotNull { video ->
                originals[video.bvid]?.copy(video = discoveryVideoCard(video), rawVideo = video)
            }
        }
    } }
    fun saveHistory(term: String) { val mid = account?.mid; scope.launch {
        try { preferences.record(mid, term); state.historyError = null }
        catch (error: Exception) { if (error is CancellationException) throw error; state.historyError = error }
    } }
    fun submit(term: String) { val value = term.trim(); if (value.isNotEmpty()) {
        draft = value; submitted = value; suggestions = emptyList(); saveHistory(value)
    } }
    LaunchedEffect(state) { if (!state.initialRecorded && submitted.isNotBlank()) {
        state.initialRecorded = true; saveHistory(submitted)
    } }
    LaunchedEffect(search, state, state.hintsRevision) {
        if (state.defaultTerm.isBlank() || state.hintsRevision > 0) {
            state.hintError = null
            try { state.defaultTerm = search.defaultHint() }
            catch (error: Exception) { if (error is CancellationException) throw error; state.hintError = error }
        }
    }
    LaunchedEffect(search, state, state.hintsRevision) {
        if (state.hot == null || state.hintsRevision > 0) {
            state.trendingError = null
            try { state.hot = search.trending() }
            catch (error: Exception) { if (error is CancellationException) throw error; state.trendingError = error }
        }
    }
    LaunchedEffect(search, state, history, privacy, suggestionsEnabled, state.discoverRevision) {
        state.discoverError = null
        try { state.discover = search.discover(personalized = suggestionsEnabled && !privacy) }
        catch (error: Exception) { if (error is CancellationException) throw error; state.discoverError = error }
    }
    LaunchedEffect(draft, submitted) {
        suggestions = emptyList(); suggestionsError = null
        if (draft.isNotBlank() && draft.trim() != submitted) {
            delay(300)
            try { suggestions = community.searchSuggestions(draft).filter { it.term.isNotBlank() || it.value.isNotBlank() || it.name.isNotBlank() } }
            catch (error: Exception) { if (error is CancellationException) throw error; suggestionsError = error }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, singleLine = true, placeholder = { Text(displayedSearchHint.ifBlank { resolveSearchDefaultPlaceholder() }) },
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit(resolvedSubmitKeyword) }))
            Button(enabled = resolvedSubmitKeyword.isNotBlank(), onClick = { submit(resolvedSubmitKeyword) }) { Text("搜索") }
            if (submitted.isNotBlank()) TextButton(onClick = { draft = ""; submitted = ""; suggestions = emptyList() }) { Text("搜索首页") }
        }
        Row(Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("无痕搜索"); Switch(privacy, { enabled -> if (!settingsBusy) { settingsBusy = true; scope.launch {
                try { preferences.setPrivacyMode(enabled) } catch (error: Exception) { if (error is CancellationException) throw error; state.historyError = error }
                finally { settingsBusy = false }
            } } }, enabled = !settingsBusy)
            Text("搜索推荐词"); Switch(suggestionsEnabled, { enabled -> if (!settingsBusy) { settingsBusy = true; scope.launch {
                try { preferences.setSuggestionsEnabled(enabled) } catch (error: Exception) { if (error is CancellationException) throw error; state.historyError = error }
                finally { settingsBusy = false }
            } } }, enabled = !settingsBusy)
            if (privacy) Text("不保存本次搜索历史", style = MaterialTheme.typography.bodySmall)
        }
        if (suggestions.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.take(10).forEach { suggestion -> SuggestionChip(onClick = { submit(suggestion.value.ifBlank { suggestion.term.ifBlank { cleanSearchText(suggestion.name) } }) }, label = { Text(cleanSearchText(suggestion.name.ifBlank { suggestion.value })) }) }
        }
        suggestionsError?.let { Text(it.message ?: "搜索建议加载失败", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        state.historyError?.let { Text(it.message ?: "搜索历史保存失败", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
        Row(Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            orderedSearchTabs.forEach { candidate -> FilterChip(selected = type == candidate, onClick = { type = candidate }, label = { Text(candidate.displayName) }) }
        }
        if (submitted.isNotBlank()) CommunitySearchFilters(type, filters, { filters = it }, state.filterExpanded, { state.filterExpanded = it })
        if (submitted.isBlank()) Column(Modifier.fillMaxSize().verticalScroll(state.landingScroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            resolveSearchLandingSectionOrder().forEach { section -> when (section) {
                SearchLandingSection.TRENDING -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("热搜", style = MaterialTheme.typography.titleLarge); TextButton(onClick = { state.hintsRevision++ }) { Text("刷新") }
                    }
                    state.hintError?.let { Text(it.message ?: "默认搜索词加载失败", style = MaterialTheme.typography.bodySmall) }
                    state.trendingError?.let { CommunityFailure(it, navigation.onLogin) { state.hintsRevision++ } }
                    if (state.hot == null && state.trendingError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.hot?.pinnedItems?.forEach { item -> CommunitySearchHotRow(item, "置顶", ::submit) }
                    state.hot?.items?.forEachIndexed { index, item -> CommunitySearchHotRow(item, (index + 1).toString(), ::submit) }
                }
                SearchLandingSection.HISTORY -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索历史", style = MaterialTheme.typography.titleLarge)
                        TextButton(enabled = history.isNotEmpty(), onClick = { val mid = account?.mid; scope.launch {
                            try { preferences.clear(mid); state.historyError = null }
                            catch (error: Exception) { if (error is CancellationException) throw error; state.historyError = error }
                        } }) { Text("清空") }
                    }
                    if (history.isEmpty()) Text("暂无搜索历史", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { history.forEach { item ->
                        InputChip(selected = false, onClick = { submit(item.keyword) }, label = { Text(item.keyword) },
                            trailingIcon = { TextButton(onClick = { val mid = account?.mid; scope.launch {
                                try { preferences.delete(mid, item); state.historyError = null }
                                catch (error: Exception) { if (error is CancellationException) throw error; state.historyError = error }
                            } }, contentPadding = PaddingValues(4.dp)) { Text("×") } })
                    } }
                }
                SearchLandingSection.DISCOVER -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("搜索发现", style = MaterialTheme.typography.titleLarge); TextButton(onClick = { state.discoverRevision++ }) { Text("换一批") }
                    }
                    state.discoverError?.let { CommunityFailure(it, navigation.onLogin) { state.discoverRevision++ } }
                    if (state.discover == null && state.discoverError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.discover.orEmpty().forEach { item ->
                        SuggestionChip(onClick = { submit(item.keyword.ifBlank { item.show_name }) }, label = { Column {
                            Text(item.show_name.ifBlank { item.keyword }); if (item.recommend_reason.isNotBlank()) Text(item.recommend_reason, style = MaterialTheme.typography.labelSmall)
                        } })
                    } }
                }
            } }
        } else CommunityFeed<CommunitySearchRow, Int>(listOf(epoch, submitted, type, filters.requestKey(type)), 1, load = { page ->
            val result = if (type == SearchType.VIDEO) search.videos(submitted, page, filters.videoOrder, filters.durations, filters.videoTid, filters.pubBegin, filters.pubEnd)
                else community.typedSearch(submitted, type, page, filters.parameters(type))
            CommunityBatch(communitySearchRows(result.result), result.nextPage)
        }, identity = { it.key }, onLogin = navigation.onLogin, transform = transform) { item ->
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

@Composable private fun CommunitySearchHotRow(item: HotItem, position: String, submit: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { submit(item.keyword.ifBlank { item.show_name }) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(position, color = MaterialTheme.colorScheme.primary)
        Text(item.show_name.ifBlank { item.keyword }, Modifier.weight(1f))
        if (item.icon.isNotBlank()) AsyncImage(imageUrl(item.icon), item.recommend_reason, Modifier.size(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable private fun CommunitySearchFilters(type: SearchType, filters: DesktopSearchFilters,
    onChange: (DesktopSearchFilters) -> Unit, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    var dates by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (type) {
            SearchType.VIDEO -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    resolveSearchVideoOrderOptions().forEach { value -> FilterChip(filters.videoOrder == value, { onChange(filters.copy(videoOrder = value)) }, label = { Text(resolveSearchOrderChipLabel(value)) }) }
                    TextButton(onClick = { onExpanded(!expanded) }) { Text(if (expanded) "收起筛选" else "时长 / 分区 / 发布时间") }
                }
                if (expanded) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        resolveSearchVideoDurationOptions().forEach { value -> FilterChip(if (value == SearchDuration.ALL) filters.durations.isEmpty() else value in filters.durations,
                            { onChange(filters.copy(durations = toggleSearchDurationSelection(filters.durations, value))) }, label = { Text(resolveSearchDurationChipLabel(value)) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        resolveSearchVideoZoneOptions().forEach { value -> FilterChip(filters.videoTid == value.tid, { onChange(filters.copy(videoTid = value.tid)) }, label = { Text(value.label) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SearchVideoPubTimeType.entries.forEach { value -> FilterChip(filters.pubType == value, {
                            if (value == SearchVideoPubTimeType.CUSTOM) dates = true else onChange(filters.withPubType(value))
                        }, label = { Text(value.label) }) }
                        if (filters.pubType == SearchVideoPubTimeType.CUSTOM) TextButton(onClick = { dates = true }) { Text("${searchDateLabel(filters.pubBegin)} – ${searchDateLabel(filters.pubEnd)}") }
                        TextButton(onClick = { onChange(filters.copy(videoOrder = SearchOrder.TOTALRANK, durations = emptySet(), videoTid = 0).withPubType(SearchVideoPubTimeType.ALL)) }) { Text("重置视频筛选") }
                    }
                }
            }
            SearchType.UP -> {
                CommunitySearchOptions(SearchUpOrder.entries, filters.upOrder, { it.displayName }) { onChange(filters.copy(upOrder = it)) }
                CommunitySearchOptions(SearchOrderSort.entries, filters.upSort, { it.displayName }) { onChange(filters.copy(upSort = it)) }
                CommunitySearchOptions(SearchUserType.entries, filters.userType, { it.displayName }) { onChange(filters.copy(userType = it)) }
            }
            SearchType.LIVE -> CommunitySearchOptions(SearchLiveOrder.entries, filters.liveOrder, { it.displayName }) { onChange(filters.copy(liveOrder = it)) }
            SearchType.ARTICLE -> {
                CommunitySearchOptions(SearchOrder.entries, filters.articleOrder, { it.displayName }) { onChange(filters.copy(articleOrder = it)) }
                CommunitySearchOptions(SearchArticleCategory.entries, filters.articleCategory, { it.displayName }) { onChange(filters.copy(articleCategory = it)) }
            }
            SearchType.PHOTO -> {
                CommunitySearchOptions(SearchOrder.entries.filter { it != SearchOrder.ATTENTION }, filters.photoOrder, { it.displayName }) { onChange(filters.copy(photoOrder = it)) }
                CommunitySearchOptions(SearchPhotoCategory.entries, filters.photoCategory, { it.displayName }) { onChange(filters.copy(photoCategory = it)) }
            }
            else -> Unit
        }
    }
    if (dates) {
        val selected = rememberDateRangePickerState(initialSelectedStartDateMillis = filters.pubBegin?.times(1000), initialSelectedEndDateMillis = filters.pubEnd?.times(1000))
        DatePickerDialog(onDismissRequest = { dates = false }, confirmButton = {
            TextButton(enabled = selected.selectedStartDateMillis != null && selected.selectedEndDateMillis != null, onClick = {
                onChange(filters.withCustomRange(requireNotNull(selected.selectedStartDateMillis) / 1000, requireNotNull(selected.selectedEndDateMillis) / 1000)); dates = false
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { dates = false }) { Text("取消") } }) { DateRangePicker(selected, Modifier.heightIn(max = 510.dp)) }
    }
}

@Composable private fun <T> CommunitySearchOptions(values: List<T>, selected: T, label: (T) -> String, choose: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value -> FilterChip(value == selected, { choose(value) }, label = { Text(label(value)) }) }
    }
}

private fun searchDateLabel(value: Long?): String = value?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: "未选择"

internal fun communitySearchRows(result: CommunitySearchResult): List<CommunitySearchRow> = when (result) {
    is CommunitySearchResult.Videos -> result.data.result.orEmpty().map { raw ->
        val item = raw.toVideoItem()
        CommunitySearchRow("video:${item.bvid}", item.title, "", item.pic,
            rawVideo = item, video = VideoCard(item.bvid, item.title, item.pic, item.owner.name, item.stat.view.toLong(), item.duration,
                publishedAt = item.pubdate, authorMid = item.owner.mid), blockedOwnerMid = item.owner.mid)
    }
    is CommunitySearchResult.Users -> result.data.result.orEmpty().map { raw -> val item = raw.cleanupFields()
        CommunitySearchRow("user:${item.mid}", item.uname, "${item.fans} 粉丝 · ${item.videos} 个视频\n${item.usign}", item.upic, user = item.mid, blockedOwnerMid = item.mid) }
    is CommunitySearchResult.Media -> result.data.result.orEmpty().map { item ->
        CommunitySearchRow("season:${item.seasonId}:${item.mediaId}", cleanSearchText(item.title),
            listOf(item.seasonTypeName, item.indexShow, item.areas, item.desc).filter { it.isNotBlank() }.joinToString(" · "), item.cover,
            season = item.seasonId.takeIf { it > 0 } ?: item.pgcSeasonId, url = item.gotoUrl) }
    is CommunitySearchResult.LiveRooms -> result.data.result.orEmpty().map { item ->
        CommunitySearchRow("live:${item.roomid}", cleanSearchText(item.title), "${item.uname} · ${item.online} 人 · ${item.area_v2_name}",
            item.user_cover.ifBlank { item.cover }, live = item.roomid, blockedOwnerMid = item.uid) }
    is CommunitySearchResult.LiveUsers -> result.data.result.orEmpty().map { raw -> val item = raw.cleanupFields()
        CommunitySearchRow("live-user:${item.uid}", item.uname, "${if (item.liveStatus == 1) "直播中" else "未开播"} · ${item.attentions} 关注", item.uface,
            live = item.roomid, user = if (item.roomid <= 0) item.uid else 0, blockedOwnerMid = item.uid) }
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
    val blocked by community.blockedUps.mids.collectAsState()
    val dynamicTransform = remember(blocked) { { rows: List<DynamicItem> -> desktopVisibleDynamicItems(rows, blocked) } }
    var profile by remember(mid) { mutableStateOf<UserProfile?>(null) }
    var error by remember(mid) { mutableStateOf<Throwable?>(null) }
    var tab by remember(mid) { mutableIntStateOf(0) }
    var selectedCollection by remember(mid) { mutableStateOf<Pair<Long, String>?>(null) }
    if (mid <= 0) { CommunityLoginGate(repository, navigation.onLogin) {}; return }
    LaunchedEffect(mid, account?.mid) { try { profile = social.userProfile(mid) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure } }
    Column(Modifier.fillMaxSize()) {
        profile?.let { user ->
            Box(Modifier.fillMaxWidth()) {
            DesktopSkinSpaceBackground(isOwner = account?.mid == user.mid, modifier = Modifier.matchParentSize())
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
                        identity = { it.id_str }, onLogin = navigation.onLogin, transform = dynamicTransform, dynamicContent = true) { CommunityDynamicCard(it, community, navigation) }
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

/** Match the original video/UP/live-room/live-user categories; other search types keep their source behavior. */
internal fun desktopVisibleSearchRows(rows: List<CommunitySearchRow>, blocked: Set<Long>): List<CommunitySearchRow> =
    rows.filter { it.blockedOwnerMid !in blocked }
